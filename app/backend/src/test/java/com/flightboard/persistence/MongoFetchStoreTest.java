package com.flightboard.persistence;

import static com.flightboard.support.TestData.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.flightboard.FlightBoardApplication;
import com.flightboard.config.FlightBoardProperties;
import com.flightboard.fetch.FetchCoordinator;
import com.flightboard.fetch.FetchResult;
import com.flightboard.fetch.RunClaim;
import com.flightboard.fetch.RunDeadline;
import com.flightboard.source.FlightSource;
import com.flightboard.source.SourceException;
import com.flightboard.validation.BatchValidator;
import com.flightboard.validation.ValidatedBatch;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.images.builder.Transferable;

@Testcontainers
class MongoFetchStoreTest {
    // Invented credentials exist only in this ephemeral test container.
    @Container
    static final GenericContainer<?> mongo = new GenericContainer<>(DockerImageName.parse("mongo:8.0.32"))
            .withEnv("MONGO_INITDB_ROOT_USERNAME", "test-admin")
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", "synthetic-admin-password")
            .withCopyToContainer(Transferable.of("syntheticReplicaSetKeyForTestsOnly1234567890".getBytes(StandardCharsets.US_ASCII), 0400),
                    "/tmp/test-replica-key")
            .withCommand("bash", "-c", "chown mongodb:mongodb /tmp/test-replica-key; exec docker-entrypoint.sh mongod --replSet rs0 --bind_ip_all --keyFile /tmp/test-replica-key --setParameter enableTestCommands=1")
            .withExposedPorts(27017).waitingFor(Wait.forLogMessage(".*Waiting for connections.*\\n", 2))
            .withStartupTimeout(Duration.ofMinutes(3));

    static MongoClient admin;
    static MongoClient app;
    static MongoDatabase db;
    static final AtomicInteger transactionStarts = new AtomicInteger();
    static final AtomicInteger commits = new AtomicInteger();
    MongoFetchStore store;
    FlightBoardProperties config;
    BatchValidator validator;

    @BeforeAll
    static void createReplicaSetAndRestrictedUser() throws Exception {
        var initialized = mongo.execInContainer("mongosh", "--quiet", "--username", "test-admin", "--password",
                "synthetic-admin-password", "--authenticationDatabase", "admin", "--eval",
                "rs.initiate({_id:'rs0',members:[{_id:0,host:'127.0.0.1:27017'}]})");
        assertThat(initialized.getExitCode()).isZero();
        admin = MongoClients.create(settings("test-admin:synthetic-admin-password"));
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(() -> admin.getDatabase("admin")
                .runCommand(new Document("hello", 1)).getBoolean("isWritablePrimary", false));
        admin.getDatabase("admin").runCommand(new Document("createUser", "flightboard-app")
                .append("pwd", "synthetic-app-password").append("roles", List.of(new Document("role", "readWrite").append("db", "flightboard"))));
        app = MongoClients.create(MongoClientSettings.builder(settings("flightboard-app:synthetic-app-password"))
                .addCommandListener(new CommandListener() {
                    @Override public void commandStarted(CommandStartedEvent event) {
                        if (event.getCommand().containsKey("startTransaction")) { transactionStarts.incrementAndGet(); }
                        if (event.getCommandName().equals("commitTransaction")) { commits.incrementAndGet(); }
                    }
                }).build());
        db = admin.getDatabase("flightboard");
    }

    static MongoClientSettings settings(String credentials) {
        return MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri(credentials)))
                .timeout(5L, TimeUnit.SECONDS).build();
    }

    static String uri(String credentials) {
        return "mongodb://" + credentials + "@" + mongo.getHost() + ":" + mongo.getMappedPort(27017)
                + "/admin?authSource=admin&directConnection=true&replicaSet=rs0";
    }

    @AfterAll
    static void closeClients() { if (app != null) { app.close(); } if (admin != null) { admin.close(); } }

    @BeforeEach
    void resetDatabase() {
        disableFailure();
        db.drop();
        config = withTimeout(Duration.ofSeconds(5));
        validator = new BatchValidator(config.board(), MAPPER);
        store = new MongoFetchStore(app, "flightboard", config, Clock.systemUTC());
        store.initialize();
        transactionStarts.set(0);
        commits.set(0);
    }

    @AfterEach
    void resetFailpoint() { disableFailure(); }

    @Test
    void restrictedUserInitializesCollectionsIndexAndPublishesIntoFirstDatabase() throws Exception {
        var run = claim("first");
        assertThat(store.publish(run, validBatch(), deadline())).isTrue();
        var board = store.readBoard().orElseThrow();
        assertThat(board.runId()).isEqualTo("first");
        assertThat(board.fetchSeq()).isEqualTo(1);
        assertThat(board.flights()).hasSize(1);
        assertThat(store.readControl().get("lockedBy")).isNull();
        assertThat(store.latestRun("SUCCESS").orElseThrow().getInteger("flightCount")).isEqualTo(1);
        var ttl = db.getCollection("fetch_runs").listIndexes().into(new java.util.ArrayList<>()).stream()
                .filter(index -> "fetch_runs_7d".equals(index.getString("name"))).findFirst().orElseThrow();
        assertThat(((Number) ttl.get("expireAfterSeconds")).longValue()).isEqualTo(604800);
        assertThat(ttl.get("key")).isEqualTo(new Document("startedAt", 1));
        assertThatThrownBy(() -> app.getDatabase("another-database").getCollection("forbidden").insertOne(new Document("x", 1)))
                .isInstanceOf(MongoException.class);
    }

    @Test
    void concurrentInitializationDoesNotResetPauseLockScheduleOrCounter() throws Exception {
        var sentinel = new Document("_id", MongoFetchStore.CONTROL_ID).append("paused", true)
                .append("nextRunAt", Date.from(Instant.now().plusSeconds(1000)))
                .append("lockedBy", "existing-run").append("lockedUntil", Date.from(Instant.now().plusSeconds(500)))
                .append("fetchSeq", 43L);
        db.getCollection("fetch_control").replaceOne(eq("_id", MongoFetchStore.CONTROL_ID), sentinel);
        try (var threads = Executors.newFixedThreadPool(8)) {
            var jobs = java.util.stream.IntStream.range(0, 8).mapToObj(i -> threads.submit(store::initialize)).toList();
            for (var job : jobs) { job.get(15, TimeUnit.SECONDS); }
        }
        assertThat(store.readControl()).isEqualTo(sentinel);
        db.drop();
        try (var threads = Executors.newFixedThreadPool(8)) {
            var jobs = java.util.stream.IntStream.range(0, 8).mapToObj(i -> threads.submit(store::initialize)).toList();
            for (var job : jobs) { job.get(15, TimeUnit.SECONDS); }
        }
        assertThat(db.getCollection("fetch_control").countDocuments()).isEqualTo(1);
        assertThat(((Number) store.readControl().get("fetchSeq")).longValue()).isZero();
    }

    @Test
    void twoConcurrentReplicasMakeExactlyOneClaim() throws Exception {
        var go = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> { go.await(); return store.claim("replica-a", deadline()); });
            var second = threads.submit(() -> { go.await(); return store.claim("replica-b", deadline()); });
            go.countDown();
            assertThat(List.of(first.get(), second.get()).stream().filter(java.util.Optional::isPresent).count()).isEqualTo(1);
        }
        assertThat(((Number) store.readControl().get("fetchSeq")).longValue()).isEqualTo(1);
        assertThat(store.readControl().getDate("nextRunAt")).isAfter(new Date());
    }

    @Test
    void pauseAndResumePreserveScheduleAndDoNotStopAnAlreadyClaimedRun() throws Exception {
        var run = claim("active-before-pause");
        Date next = store.readControl().getDate("nextRunAt");
        store.pause(true);
        assertThat(store.claim("paused-replica", deadline())).isEmpty();
        assertThat(store.fetchNow()).isEqualTo(MongoFetchStore.FetchNowResult.PAUSED);
        assertThat(store.publish(run, validBatch(), deadline())).isTrue();
        store.pause(false);
        assertThat(store.readControl().getDate("nextRunAt")).isEqualTo(next);
        assertThat(store.claim("not-yet-due", deadline())).isEmpty();
    }

    @Test
    void crashRecoveryWaitsForBothExpiredLockAndSchedule() {
        claim("crashed");
        expireLock();
        assertThat(store.claim("too-early", deadline())).isEmpty();
        makeDue();
        assertThat(store.claim("recovered", deadline()).orElseThrow().fetchSeq()).isEqualTo(2);
    }

    @Test
    void fetchNowRejectsActiveLockAndChangesOnlyTheScheduleWhenAccepted() {
        claim("active");
        var locked = store.readControl();
        assertThat(store.fetchNow()).isEqualTo(MongoFetchStore.FetchNowResult.LOCKED);
        assertThat(store.readControl()).isEqualTo(locked);
        expireLock();
        assertThat(store.fetchNow()).isEqualTo(MongoFetchStore.FetchNowResult.ACCEPTED);
        assertThat(((Number) store.readControl().get("fetchSeq")).longValue()).isEqualTo(1);
        assertThat(store.claim("next-check", deadline())).isPresent();
    }

    @Test
    void expiredLockOrRunDeadlineCannotPublishAndTransactionLeavesLockUntouched() throws Exception {
        var run = claim("expired-lock");
        expireLock();
        var before = store.readControl();
        assertThat(store.publish(run, validBatch(), deadline())).isFalse();
        assertThat(store.readControl()).isEqualTo(before);
        assertThat(store.readBoard()).isEmpty();
        db.getCollection("fetch_control").updateOne(eq("_id", MongoFetchStore.CONTROL_ID),
                combine(set("lockedUntil", Date.from(Instant.now().plusSeconds(100))), set("runDeadline", new Date(0))));
        assertThat(store.publish(run, validBatch(), deadline())).isFalse();
        assertThat(store.readBoard()).isEmpty();
    }

    @Test
    void olderSequenceCannotReplaceNewerBoardAndReleaseIsRolledBack() throws Exception {
        var older = claim("older");
        expireLock(); makeDue();
        var newer = claim("newer");
        assertThat(store.publish(newer, validBatch(), deadline())).isTrue();
        db.getCollection("fetch_control").updateOne(eq("_id", MongoFetchStore.CONTROL_ID),
                combine(set("lockedBy", older.runId()), set("lockedUntil", Date.from(older.lockedUntil())),
                        set("runDeadline", Date.from(older.deadlineAt()))));
        assertThat(store.publish(older, validBatch(), deadline())).isFalse();
        assertThat(store.readBoard().orElseThrow().runId()).isEqualTo("newer");
        assertThat(store.readControl().getString("lockedBy")).isEqualTo("older");
    }

    @Test
    void finalConditionalWriteFailureAbortsBothBoardAndLockRelease() throws Exception {
        var run = store.claim("no-run-record", deadline()).orElseThrow();
        db.getCollection("fetch_runs").deleteOne(eq("_id", run.runId()));
        assertThat(store.publish(run, validBatch(), deadline())).isFalse();
        assertThat(store.readBoard()).isEmpty();
        assertThat(store.readControl().getString("lockedBy")).isEqualTo(run.runId());
    }

    @Test
    void failedRunNeverReleasesAnotherRunsLock() {
        var older = claim("old-worker");
        expireLock(); makeDue();
        claim("new-worker");
        store.fail(older, "SOURCE_TIMEOUT", "Source request timed out", null, null);
        assertThat(store.readControl().getString("lockedBy")).isEqualTo("new-worker");
        assertThat(store.latestRun("ERROR").orElseThrow().getString("_id")).isEqualTo("old-worker");
    }

    @Test
    void validEmptyBatchReplacesFlightsAndExpiredBoardCanBePublishedAgain() throws Exception {
        assertThat(store.publish(claim("nonempty"), validBatch(), deadline())).isTrue();
        store.fetchNow();
        assertThat(store.publish(claim("empty"), validator.validate(batch(), "empty"), deadline())).isTrue();
        assertThat(store.readBoard().orElseThrow().flights()).isEmpty();
        db.getCollection("board_current").updateOne(eq("_id", MongoFetchStore.BOARD_ID),
                set("publishedAt", Date.from(Instant.now().minus(Duration.ofHours(13)))));
        assertThat(store.readBoard()).isEmpty();
        assertThat(db.getCollection("board_current").countDocuments()).isEqualTo(1);
        assertThat(store.deleteExpiredBoard()).isEqualTo(1);
        store.fetchNow();
        assertThat(store.publish(claim("after-cleanup"), validBatch(), deadline())).isTrue();
        assertThat(store.deleteExpiredBoard()).isZero();
        assertThat(store.readBoard().orElseThrow().runId()).isEqualTo("after-cleanup");
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500})
    void sourceErrorsPreserveBoardRecordStatusAndReleaseOwnLock(int status) throws Exception {
        assertThat(store.publish(claim("good"), validBatch(), deadline())).isTrue();
        var before = store.readBoard().orElseThrow();
        store.fetchNow();
        FlightSource failing = () -> { throw new SourceException(SourceException.Kind.HTTP_STATUS, "Source returned HTTP " + status, status); };
        try (var coordinator = coordinator(failing, validator::validate, config)) {
            assertThat(coordinator.runOnce().state()).isEqualTo(FetchResult.State.ERROR);
        }
        assertThat(store.readBoard().orElseThrow()).isEqualTo(before);
        assertThat(store.latestRun("ERROR").orElseThrow().getInteger("httpStatus")).isEqualTo(status);
        assertThat(store.readControl().get("lockedBy")).isNull();
    }

    @Test
    void invalidBatchKeepsLastBoardAndRecordsFirstInvalidIndex() throws Exception {
        assertThat(store.publish(claim("good"), validBatch(), deadline())).isTrue();
        store.fetchNow();
        try (var coordinator = coordinator(() -> "{\"departures\":[null]}", validator::validate, config)) {
            assertThat(coordinator.runOnce().code()).isEqualTo("INVALID_BATCH");
        }
        assertThat(store.readBoard().orElseThrow().runId()).isEqualTo("good");
        assertThat(store.latestRun("ERROR").orElseThrow().get("error", Document.class).getInteger("departureIndex")).isZero();
        assertThat(store.readControl().get("lockedBy")).isNull();
    }

    @Test
    void transientTransactionRetryReusesOneDownloadedBatch() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FlightSource source = () -> {
            calls.incrementAndGet();
            transactionStarts.set(0);
            commits.set(0);
            injectFailure(new Document("failCommands", List.of("update")).append("errorCode", 112)
                    .append("errorLabels", List.of("TransientTransactionError")), new Document("times", 1));
            return rawBatch();
        };
        try (var coordinator = coordinator(source, validator::validate, config)) {
            assertThat(coordinator.runOnce().state()).isEqualTo(FetchResult.State.SUCCESS);
        }
        assertThat(calls).hasValue(1);
        assertThat(transactionStarts).hasValue(2);
        assertThat(store.readBoard()).isPresent();
    }

    @Test
    void unknownCommitRetryDoesNotRepeatTransactionBodyOrSource() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FlightSource source = () -> {
            calls.incrementAndGet();
            transactionStarts.set(0);
            commits.set(0);
            injectFailure(new Document("failCommands", List.of("commitTransaction"))
                    .append("writeConcernError", new Document("code", 64).append("errmsg", "synthetic uncertain commit"))
                    .append("errorLabels", List.of("UnknownTransactionCommitResult")), new Document("times", 1));
            return rawBatch();
        };
        try (var coordinator = coordinator(source, validator::validate, config)) {
            assertThat(coordinator.runOnce().state()).isEqualTo(FetchResult.State.SUCCESS);
        }
        assertThat(calls).hasValue(1);
        assertThat(transactionStarts).hasValue(1);
        assertThat(commits.get()).isGreaterThanOrEqualTo(2);
        assertThat(store.latestRun("SUCCESS")).isPresent();
    }

    @Test
    void repeatedUnknownCommitsShareTheRemainingDeadlineAndKeepCommittedSuccess() {
        var calls = new AtomicInteger();
        FlightSource source = () -> {
            calls.incrementAndGet();
            transactionStarts.set(0);
            commits.set(0);
            injectFailure(new Document("failCommands", List.of("commitTransaction"))
                    .append("writeConcernError", new Document("code", 64).append("errmsg", "synthetic uncertain commit"))
                    .append("errorLabels", List.of("UnknownTransactionCommitResult")), "alwaysOn");
            return rawBatch();
        };
        try (var coordinator = coordinator(source, validator::validate, withTimeout(Duration.ofMillis(500)))) {
            long began = System.nanoTime();
            var result = coordinator.runOnce();
            assertThat(result.state()).isIn(FetchResult.State.TIMEOUT, FetchResult.State.ERROR);
            assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(1));
            disableFailure();
            assertThat(calls).hasValue(1);
            assertThat(transactionStarts).hasValue(1);
            assertThat(commits.get()).isGreaterThan(1);
            // The injected write-concern error can hide a successful server-side commit.
            // Failure bookkeeping must preserve that board's atomic SUCCESS record.
            await().atMost(Duration.ofSeconds(5)).until(() -> store.readControl().get("lockedBy") == null);
            var board = store.readBoard().orElseThrow();
            assertThat(db.getCollection("fetch_runs").find(eq("_id", board.runId())).first().getString("result"))
                    .isEqualTo("SUCCESS");
            store.fail(new RunClaim(board.runId(), board.fetchSeq(), board.publishedAt(),
                    board.publishedAt().plusSeconds(120), board.publishedAt().plusSeconds(300)),
                    "PUBLISH_UNCERTAIN", "Publication commit could not be confirmed", 200, null);
            assertThat(store.latestRun("ERROR")).isEmpty();
            assertThat(store.latestRun("SUCCESS")).isPresent();
        }
    }

    @Test
    void persistentTransientFailureStopsAfterThreeBodiesAndPreservesBoard() {
        AtomicInteger calls = new AtomicInteger();
        FlightSource source = () -> {
            calls.incrementAndGet();
            transactionStarts.set(0);
            commits.set(0);
            injectFailure(new Document("failCommands", List.of("update")).append("errorCode", 112)
                    .append("errorLabels", List.of("TransientTransactionError")), "alwaysOn");
            return rawBatch();
        };
        try (var coordinator = coordinator(source, validator::validate, config)) {
            assertThat(coordinator.runOnce().code()).isEqualTo("RETRY_LIMIT");
        }
        disableFailure();
        assertThat(calls).hasValue(1);
        assertThat(transactionStarts).hasValue(3);
        assertThat(store.readBoard()).isEmpty();
    }

    @Test
    void totalDeadlineIncludesValidationAndLateWorkersCannotPublish() throws Exception {
        var shortConfig = withTimeout(Duration.ofMillis(150));
        var entered = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        FetchCoordinator.BatchProcessor slow = (json, runId) -> {
            entered.countDown();
            boolean ready = false;
            while (!ready) {
                try { ready = released.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { /* Deliberately noncooperative test processor. */ }
            }
            return validator.validate(json, runId);
        };
        try (var coordinator = coordinator(() -> rawBatch(), slow, shortConfig)) {
            long began = System.nanoTime();
            var result = coordinator.runOnce();
            assertThat(entered.getCount()).isZero();
            assertThat(result.state()).isEqualTo(FetchResult.State.TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(1));
            assertThat(store.readBoard()).isEmpty();
            released.countDown();
            await().atMost(Duration.ofSeconds(5)).until(() -> store.latestRun("ERROR").isPresent());
            assertThat(store.readControl().get("lockedBy")).isNull();
            assertThat(store.readBoard()).isEmpty();
        } finally { released.countDown(); }
    }

    @Test
    void slowDatabasePublicationCannotGetAFreshFullRunTimeout() {
        var shortConfig = withTimeout(Duration.ofMillis(350));
        FlightSource source = () -> {
            injectFailure(new Document("failCommands", List.of("update")).append("blockConnection", true)
                    .append("blockTimeMS", 1200), new Document("times", 1));
            return rawBatch();
        };
        try (var coordinator = coordinator(source, validator::validate, shortConfig)) {
            long began = System.nanoTime();
            var result = coordinator.runOnce();
            assertThat(result.state()).isIn(FetchResult.State.TIMEOUT, FetchResult.State.ERROR);
            assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(1));
            await().atMost(Duration.ofSeconds(5)).until(() -> store.latestRun("ERROR").isPresent());
            assertThat(store.readBoard()).isEmpty();
        }
    }

    @Test
    void fullApplicationUsesAdminAuthenticationButFlightboardDatabaseAndStartsStubScheduler() {
        try (var context = new SpringApplicationBuilder(FlightBoardApplication.class).web(WebApplicationType.NONE)
                .properties(Map.of("spring.data.mongodb.uri", uri("flightboard-app:synthetic-app-password"),
                        "spring.data.mongodb.database", "flightboard", "FLIGHTBOARD_SOURCE", "stub"))
                .run()) {
            var liveStore = context.getBean(MongoFetchStore.class);
            await().atMost(Duration.ofSeconds(10)).until(() -> liveStore.readBoard().isPresent());
            assertThat(liveStore.readBoard().orElseThrow().flights()).hasSize(12);
            assertThat(db.getCollection("board_current").countDocuments()).isEqualTo(1);
        }
    }

    RunClaim claim(String id) {
        var claim = store.claim(id, deadline()).orElseThrow();
        return claim;
    }

    RunDeadline deadline() { return new RunDeadline(Duration.ofSeconds(5)); }

    ValidatedBatch validBatch() throws Exception { return validator.validate(rawBatch(), "synthetic"); }

    static String rawBatch() { return batch(departure("ZZ 1234", "2030-06-01 23:50+02:00")); }

    FetchCoordinator coordinator(FlightSource source, FetchCoordinator.BatchProcessor processor, FlightBoardProperties props) {
        return new FetchCoordinator(store, source, processor, props);
    }

    static FlightBoardProperties withTimeout(Duration timeout) {
        var defaults = properties();
        return new FlightBoardProperties(defaults.source(), defaults.aerodatabox(),
                new FlightBoardProperties.Fetch(defaults.fetch().interval(), defaults.fetch().checkInterval(),
                        defaults.fetch().lockDuration(), timeout), defaults.request(), defaults.board(), defaults.admin(), defaults.version());
    }

    void makeDue() { db.getCollection("fetch_control").updateOne(eq("_id", MongoFetchStore.CONTROL_ID), set("nextRunAt", new Date(0))); }

    void expireLock() { db.getCollection("fetch_control").updateOne(eq("_id", MongoFetchStore.CONTROL_ID), set("lockedUntil", new Date(0))); }

    static void injectFailure(Document failure, Object mode) {
        admin.getDatabase("admin").runCommand(new Document("configureFailPoint", "failCommand").append("mode", mode).append("data", failure));
    }

    static void disableFailure() {
        if (admin != null) { injectFailure(new Document(), "off"); }
    }
}
