package com.flightboard.api;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flightboard.FlightBoardApplication;
import com.flightboard.persistence.MongoFetchStore;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebAcceptanceTest {
    @Container
    static final GenericContainer<?> mongo = new GenericContainer<>("mongo:8.0.32")
            .withEnv("MONGO_INITDB_ROOT_USERNAME", "test-admin")
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", "synthetic-admin-password")
            .withCopyToContainer(Transferable.of("syntheticReplicaSetKeyForTestsOnly1234567890".getBytes(StandardCharsets.US_ASCII), 0400),
                    "/tmp/test-replica-key")
            .withCommand("bash", "-c", "chown mongodb:mongodb /tmp/test-replica-key; exec docker-entrypoint.sh mongod --replSet rs0 --bind_ip_all --keyFile /tmp/test-replica-key")
            .withExposedPorts(27017).waitingFor(Wait.forLogMessage(".*Waiting for connections.*\\n", 2))
            .withStartupTimeout(Duration.ofMinutes(3));
    static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1).build();
    static ConfigurableApplicationContext context;
    static MongoClient admin;
    static MongoDatabase database;
    static MongoFetchStore store;
    static ObjectMapper mapper;
    static boolean pausedContainer;
    static Duration startupTime;

    @BeforeAll
    static void startWhileDatabaseIsUnavailable() throws Exception {
        var init = mongo.execInContainer("mongosh", "--quiet", "-u", "test-admin", "-p", "synthetic-admin-password",
                "--authenticationDatabase", "admin", "--eval", "rs.initiate({_id:'rs0',members:[{_id:0,host:'127.0.0.1:27017'}]})");
        assertThat(init.getExitCode()).isZero();
        admin = MongoClients.create(MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri("test-admin:synthetic-admin-password")))
                .timeout(5, TimeUnit.SECONDS).build());
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(() -> admin.getDatabase("admin")
                .runCommand(new Document("hello", 1)).getBoolean("isWritablePrimary", false));
        admin.getDatabase("admin").runCommand(new Document("createUser", "flightboard-app").append("pwd", "synthetic-app-password")
                .append("roles", List.of(new Document("role", "readWrite").append("db", "flightboard"))));
        database = admin.getDatabase("flightboard");
        database.getCollection("fetch_control").insertOne(new Document("_id", MongoFetchStore.CONTROL_ID)
                .append("paused", true).append("nextRunAt", new Date(0)).append("lockedBy", null)
                .append("lockedUntil", new Date(0)).append("fetchSeq", 9L));
        database.getCollection("board_current").insertOne(board("expired-before-startup", Instant.now().minus(Duration.ofHours(13))));
        pauseContainer();
        long started = System.nanoTime();
        context = new SpringApplicationBuilder(FlightBoardApplication.class).run(
                "--spring.data.mongodb.uri=" + uri("flightboard-app:synthetic-app-password"),
                "--FLIGHTBOARD_SOURCE=stub", "--FLIGHTBOARD_FETCH_CHECK_INTERVAL=PT0.2S", "--FLIGHTBOARD_VERSION=synthetic-test-sha");
        startupTime = Duration.ofNanos(System.nanoTime() - started);
        store = context.getBean(MongoFetchStore.class);
        mapper = context.getBean(ObjectMapper.class);
    }

    @AfterAll
    static void close() {
        resumeContainer();
        if (context != null) { context.close(); }
        if (admin != null) { admin.close(); }
    }

    @BeforeEach
    void isolate(TestInfo info) {
        int order = info.getTestMethod().orElseThrow().getAnnotation(Order.class).value();
        if (order > 1) {
            resumeContainer();
            await().atMost(Duration.ofSeconds(25)).until(store::ready);
        }
        if (order > 2) {
            store.pause(true);
            database.getCollection("fetch_control").updateOne(eq("_id", MongoFetchStore.CONTROL_ID),
                    combine(set("lockedBy", null), set("lockedUntil", new Date(0))));
            database.getCollection("board_current").deleteMany(new Document());
            database.getCollection("fetch_runs").deleteMany(new Document());
        }
    }

    @Test @Order(1)
    void startsWithoutMongoAndOnlyReadinessFails() throws Exception {
        try {
            assertThat(startupTime).isLessThan(Duration.ofSeconds(15));
            assertThat(context.isActive()).isTrue();
            assertThat(get(8081, "/actuator/health/liveness").statusCode()).isEqualTo(200);
            var readiness = get(8080, "/readyz");
            assertThat(readiness.statusCode()).isEqualTo(503);
            assertThat(json(readiness).path("status").asText()).isEqualTo("DOWN");
            var api = get(8080, "/api/departures");
            assertThat(api.statusCode()).isEqualTo(503);
            assertThat(json(api).path("code").asText()).isEqualTo("DATABASE_UNAVAILABLE");
            assertThat(post(8082, "/admin/pause").statusCode()).isEqualTo(503);
            assertThat(api.body() + readiness.body()).doesNotContain("synthetic-app-password", "mongodb://", "exception", "stackTrace");
        } finally { resumeContainer(); }
    }

    @Test @Order(2)
    void recoversWithoutRestartAndCleansExpiredBoardShortlyAfterStartup() throws Exception {
        await().atMost(Duration.ofSeconds(10)).until(() -> database.getCollection("board_current").countDocuments() == 0);
        assertThat(store.readControl().getBoolean("paused")).isTrue();
        assertThat(((Number) store.readControl().get("fetchSeq")).longValue()).isEqualTo(9);
        assertThat(get(8080, "/readyz").statusCode()).isEqualTo(200);
        assertThat(get(8081, "/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test @Order(3)
    void noBoardAndExpiredBoardReturnNoDataWithNoStore() throws Exception {
        for (boolean expired : List.of(false, true)) {
            if (expired) { database.getCollection("board_current").insertOne(board("expired", Instant.now().minus(Duration.ofHours(13)))); }
            var response = get(8080, "/api/departures");
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(json(response).path("code").asText()).isEqualTo("NO_DATA");
            assertNoStore(response);
        }
        var status = get(8080, "/api/status");
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(json(status).path("version").asText()).isEqualTo("synthetic-test-sha");
        assertThat(json(status).path("dataAgeSeconds").isNull()).isTrue();
        assertThat(json(status).path("stale").asBoolean()).isTrue();
        assertNoStore(status);
    }

    @Test @Order(4)
    void validEmptyBoardIsSuccessAndStaleAgeUsesOriginalPublicationTime() throws Exception {
        Instant published = Instant.now().minus(Duration.ofMinutes(76)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        database.getCollection("board_current").insertOne(board("valid-empty", published));
        var response = get(8080, "/api/departures");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("flights").isArray()).isTrue();
        assertThat(json(response).path("flights")).isEmpty();
        assertThat(Instant.parse(json(response).path("publishedAt").asText())).isEqualTo(published);
        assertThat(json(response).path("dataAgeSeconds").asLong()).isGreaterThanOrEqualTo(4560);
        assertThat(json(response).path("stale").asBoolean()).isTrue();
        assertNoStore(response);
    }

    @Test @Order(5)
    void statusReturnsSanitizedLastErrorAndSuccessfulRun() throws Exception {
        Instant now = Instant.now();
        database.getCollection("board_current").insertOne(board("good", now));
        database.getCollection("fetch_runs").insertOne(new Document("_id", "good").append("result", "SUCCESS")
                .append("startedAt", Date.from(now.minusSeconds(1))).append("finishedAt", Date.from(now))
                .append("flightCount", 0).append("sourceFlightCount", 0).append("httpStatus", 200));
        database.getCollection("fetch_runs").insertOne(new Document("_id", "failed").append("result", "ERROR")
                .append("finishedAt", Date.from(now)).append("httpStatus", 429)
                .append("error", new Document("code", "SOURCE_HTTP_STATUS").append("message", "private provider body synthetic-app-password dummy-test-key")));
        var response = get(8080, "/api/status");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("lastSuccessfulRun").path("runId").asText()).isEqualTo("good");
        assertThat(json(response).path("lastError").path("httpStatus").asInt()).isEqualTo(429);
        assertThat(json(response).path("lastError").path("message").asText()).isEqualTo("Source request failed");
        assertThat(json(response).path("paused").asBoolean()).isTrue();
        assertThat(json(response).path("stale").asBoolean()).isFalse();
        assertThat(response.body()).doesNotContain("private provider body", "synthetic-app-password", "dummy-test-key");
        assertThat(get(8080, "/readyz").statusCode()).isEqualTo(200);
        assertThat(get(8081, "/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertNoStore(response);
    }

    @Test @Order(6)
    void adminOperationsAreIsolatedAndRespectPauseAndLock() throws Exception {
        for (int port : List.of(8080, 8081)) {
            for (String operation : List.of("pause", "resume", "fetch-now")) {
                assertThat(post(port, "/admin/" + operation).statusCode()).isEqualTo(404);
            }
        }
        database.getCollection("fetch_control").updateOne(eq("_id", MongoFetchStore.CONTROL_ID),
                set("nextRunAt", Date.from(Instant.now().plusSeconds(3600))));
        Date next = store.readControl().getDate("nextRunAt");
        assertThat(post(8082, "/admin/pause").statusCode()).isEqualTo(200);
        var paused = post(8082, "/admin/fetch-now");
        assertThat(paused.statusCode()).isEqualTo(409);
        assertThat(json(paused).path("code").asText()).isEqualTo("PAUSED");
        assertThat(post(8082, "/admin/resume").statusCode()).isEqualTo(200);
        assertThat(store.readControl().getDate("nextRunAt")).isEqualTo(next);
        database.getCollection("fetch_control").updateOne(eq("_id", MongoFetchStore.CONTROL_ID),
                combine(set("lockedBy", "another-run"), set("lockedUntil", Date.from(Instant.now().plusSeconds(60)))));
        var locked = post(8082, "/admin/fetch-now");
        assertThat(locked.statusCode()).isEqualTo(409);
        assertThat(json(locked).path("code").asText()).isEqualTo("LOCKED");
        assertThat(store.readControl().getString("lockedBy")).isEqualTo("another-run");
    }

    @Test @Order(7)
    void adminDoesNotListenOnAnyNonLoopbackIpv4Address() throws Exception {
        var addresses = NetworkInterface.networkInterfaces().filter(network -> {
            try { return network.isUp() && !network.isLoopback(); } catch (java.net.SocketException exception) { return false; }
        }).flatMap(NetworkInterface::inetAddresses).filter(address -> address instanceof Inet4Address && !address.isLoopbackAddress()).toList();
        assertThat(addresses).isNotEmpty();
        for (var address : addresses) {
            try (var socket = new Socket()) {
                assertThatThrownBy(() -> socket.connect(new InetSocketAddress(address, 8082), 500))
                        .isInstanceOf(java.io.IOException.class);
            }
        }
        assertThat(get(8082, "/admin/pause").statusCode()).isEqualTo(405);
        assertThat(get(8082, "/admin/pause/other").statusCode()).isEqualTo(404);
    }

    @Test @Order(8)
    void fetchNowSchedulesOneStubRunAndApiReturnsItsFlights() throws Exception {
        database.getCollection("fetch_control").updateOne(eq("_id", MongoFetchStore.CONTROL_ID), set("nextRunAt", Date.from(Instant.now().plusSeconds(3600))));
        assertThat(post(8082, "/admin/resume").statusCode()).isEqualTo(200);
        assertThat(post(8082, "/admin/fetch-now").statusCode()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(10)).until(() -> store.readBoard().isPresent());
        store.pause(true);
        var response = get(8080, "/api/departures");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("flights")).hasSize(12);
        assertThat(json(response).path("stale").asBoolean()).isFalse();
        assertThat(json(response).path("publishedAt").isTextual()).isTrue();
        assertThat(database.getCollection("fetch_runs").countDocuments(eq("result", "SUCCESS"))).isEqualTo(1);
        assertNoStore(response);
    }

    @Test @Order(9)
    void databaseOutageAfterStartupFailsReadinessButNotLivenessAndRecovers() throws Exception {
        pauseContainer();
        try {
            long started = System.nanoTime();
            assertThat(get(8080, "/readyz").statusCode()).isEqualTo(503);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            assertThat(get(8081, "/actuator/health/liveness").statusCode()).isEqualTo(200);
            var status = get(8080, "/api/status");
            assertThat(status.statusCode()).isEqualTo(503);
            assertThat(json(status).path("code").asText()).isEqualTo("DATABASE_UNAVAILABLE");
            assertNoStore(status);
            assertThat(context.isActive()).isTrue();
        } finally { resumeContainer(); }
        await().atMost(Duration.ofSeconds(20)).until(store::ready);
        assertThat(get(8080, "/readyz").statusCode()).isEqualTo(200);
    }

    @Test @Order(10)
    void actuatorExposesOnlyHealthAndDoesNotExposeHealthOnPublicPort() throws Exception {
        assertThat(get(8080, "/actuator/health").statusCode()).isEqualTo(404);
        assertThat(get(8081, "/actuator/env").statusCode()).isEqualTo(404);
        assertThat(get(8081, "/actuator/metrics").statusCode()).isEqualTo(404);
        assertThat(get(8081, "/api/status").statusCode()).isEqualTo(404);
        var missing = get(8080, "/api/missing");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertNoStore(missing);
    }

    static Document board(String runId, Instant published) {
        return new Document("_id", MongoFetchStore.BOARD_ID).append("runId", runId).append("fetchSeq", 8L)
                .append("publishedAt", Date.from(published)).append("flights", List.of());
    }
    static String uri(String credentials) {
        return "mongodb://" + credentials + "@" + mongo.getHost() + ":" + mongo.getMappedPort(27017)
                + "/admin?authSource=admin&directConnection=true&replicaSet=rs0&heartbeatFrequencyMS=500";
    }
    static void pauseContainer() {
        DockerClientFactory.instance().client().pauseContainerCmd(mongo.getContainerId()).exec(); pausedContainer = true;
    }
    static void resumeContainer() {
        if (pausedContainer) { DockerClientFactory.instance().client().unpauseContainerCmd(mongo.getContainerId()).exec(); pausedContainer = false; }
    }
    static HttpResponse<String> get(int port, String path) throws Exception { return request(port, path, false); }
    static HttpResponse<String> post(int port, String path) throws Exception { return request(port, path, true); }
    static HttpResponse<String> request(int port, String path, boolean post) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        return http.send((post ? request.POST(HttpRequest.BodyPublishers.noBody()) : request.GET()).build(), HttpResponse.BodyHandlers.ofString());
    }
    static JsonNode json(HttpResponse<String> response) throws Exception { return mapper.readTree(response.body()); }
    static void assertNoStore(HttpResponse<String> response) { assertThat(response.headers().firstValue("Cache-Control")).contains("no-store"); }
}
