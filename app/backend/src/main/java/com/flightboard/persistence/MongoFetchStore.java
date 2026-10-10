package com.flightboard.persistence;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

import com.flightboard.config.FlightBoardProperties;
import com.flightboard.fetch.RunClaim;
import com.flightboard.fetch.RunDeadline;
import com.flightboard.validation.BoardFlight;
import com.flightboard.validation.ValidatedBatch;
import com.mongodb.ClientSessionOptions;
import com.mongodb.MongoException;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.bson.Document;
import org.bson.conversions.Bson;

/** Raw driver transactions implement the fencing rules from specification Appendix A1. */
public final class MongoFetchStore implements FetchStore {
    public static final String CONTROL_ID = "control";
    public static final String BOARD_ID = "current";
    public static final int MAX_TRANSACTION_ATTEMPTS = 3;
    private final MongoClient client;
    private final MongoDatabase database;
    private final FlightBoardProperties properties;
    private final Clock clock;
    private volatile boolean initialized;

    public MongoFetchStore(MongoClient client, String databaseName, FlightBoardProperties properties, Clock clock) {
        if (databaseName == null || databaseName.isBlank()) {
            throw new IllegalArgumentException("Application database must be specified separately from authentication");
        }
        this.client = client;
        this.database = client.getDatabase(databaseName).withWriteConcern(WriteConcern.MAJORITY)
                .withReadConcern(ReadConcern.MAJORITY).withReadPreference(ReadPreference.primary());
        this.properties = properties;
        this.clock = clock;
    }

    public void initialize() {
        var deadline = new RunDeadline(Duration.ofSeconds(10));
        operation(deadline, db -> {
            for (String collection : List.of("fetch_control", "fetch_runs", "board_current")) {
                try {
                    db.createCollection(collection);
                } catch (MongoException exception) {
                    if (exception.getCode() != 48) { throw exception; } // Concurrent NamespaceExists is harmless.
                }
            }
            db.getCollection("fetch_runs").createIndex(Indexes.ascending("startedAt"),
                    new IndexOptions().name("fetch_runs_7d").expireAfter(7L, TimeUnit.DAYS));
            try {
                db.getCollection("fetch_control").updateOne(eq("_id", CONTROL_ID),
                        combine(setOnInsert("paused", false), setOnInsert("nextRunAt", Date.from(clock.instant())),
                                setOnInsert("lockedBy", null), setOnInsert("lockedUntil", new Date(0)),
                                setOnInsert("fetchSeq", 0L)), new UpdateOptions().upsert(true));
            } catch (MongoException exception) {
                if (exception.getCode() != 11000) { throw exception; }
                // Another initializer won the fixed-ID insert. Never reset its state.
            }
            return null;
        });
        initialized = true;
    }

    public boolean initialized() { return initialized; }

    /** Readiness has a bounded primary read and never exposes a driver exception. */
    public boolean ready() {
        if (!initialized) { return false; }
        try { return readControl() != null; }
        catch (RuntimeException exception) { return false; }
    }

    @Override
    public Optional<RunClaim> claim(String runId, RunDeadline deadline) {
        return transaction(deadline, session -> {
            var db = database;
            var fetch = properties.fetch();
            var values = new Document("lockedBy", runId).append("runStartedAt", "$$NOW")
                    .append("lockedUntil", after(fetch.lockDuration()))
                    .append("runDeadline", after(fetch.runTimeout()))
                    .append("nextRunAt", after(fetch.interval()))
                    .append("fetchSeq", new Document("$add", List.of("$fetchSeq", 1L)));
            Document won = db.getCollection("fetch_control").findOneAndUpdate(
                    session,
                    and(eq("_id", CONTROL_ID), eq("paused", false), freeLock(),
                            expr(new Document("$lte", List.of("$nextRunAt", "$$NOW")))),
                    List.of(new Document("$set", values)),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            if (won == null) { return Optional.empty(); }
            var claim = new RunClaim(runId, ((Number) won.get("fetchSeq")).longValue(),
                    won.getDate("runStartedAt").toInstant(), won.getDate("runDeadline").toInstant(),
                    won.getDate("lockedUntil").toInstant());
            // A crash cannot leave a committed claim without its run record.
            deadline.check();
            db.getCollection("fetch_runs").insertOne(session, runDocument(claim).append("result", "RUNNING"));
            return Optional.of(claim);
        });
    }

    @Override
    public boolean publish(RunClaim claim, ValidatedBatch batch, RunDeadline deadline) {
        return transaction(deadline, session -> {
            if (!writePublication(session, claim, batch, deadline)) {
                abort(session);
                return false;
            }
            return true;
        });
    }

    private <T> T transaction(RunDeadline deadline, Function<ClientSession, T> action) {
        try (ClientSession session = client.startSession(ClientSessionOptions.builder()
                .defaultTimeout(deadline.remainingMillis(), TimeUnit.MILLISECONDS).build())) {
            var attempts = new AtomicInteger();
            var options = TransactionOptions.builder().readConcern(ReadConcern.SNAPSHOT)
                    .writeConcern(WriteConcern.MAJORITY).readPreference(ReadPreference.primary())
                    .timeout(deadline.remainingMillis(), TimeUnit.MILLISECONDS).build();
            // The convenient driver API shares one timeout across the body and ALL commit retries.
            // Manual commitTransaction resets its timeout, which would incorrectly extend the run.
            return session.withTransaction(() -> {
                deadline.check();
                if (attempts.incrementAndGet() > MAX_TRANSACTION_ATTEMPTS) {
                    throw new StoreException("RETRY_LIMIT");
                }
                T result = action.apply(session);
                deadline.check();
                return result;
            }, options);
        } catch (MongoException exception) {
            throw new StoreException(exception.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)
                    ? "PUBLISH_UNCERTAIN" : "DATABASE");
        }
    }

    private boolean writePublication(ClientSession session, RunClaim claim, ValidatedBatch batch, RunDeadline deadline) {
        var control = database.getCollection("fetch_control");
        var validOwner = and(eq("_id", CONTROL_ID), eq("lockedBy", claim.runId()),
                expr(new Document("$and", List.of(new Document("$gt", List.of("$lockedUntil", "$$NOW")),
                        new Document("$gt", List.of("$runDeadline", "$$NOW"))))));
        var released = control.updateOne(session, validOwner, List.of(new Document("$set",
                new Document("lockedBy", null).append("lockedUntil", new Date(0)).append("lastPublishedAt", "$$NOW"))));
        if (released.getModifiedCount() != 1) { return false; }
        deadline.check();
        Instant publishedAt = control.find(session, eq("_id", CONTROL_ID)).first().getDate("lastPublishedAt").toInstant();
        var boards = database.getCollection("board_current");
        Document replacement = boardDocument(claim, batch, publishedAt);
        if (boards.find(session, eq("_id", BOARD_ID)).projection(new Document("_id", 1)).first() == null) {
            boards.insertOne(session, replacement);
        } else if (boards.replaceOne(session, and(eq("_id", BOARD_ID), lt("fetchSeq", claim.fetchSeq())), replacement)
                .getModifiedCount() != 1) {
            return false;
        }
        deadline.check();
        // Final server-side deadline/lock-expiry fence also makes SUCCESS atomic with the board.
        var runGuard = and(eq("_id", claim.runId()), eq("result", "RUNNING"),
                expr(new Document("$and", List.of(new Document("$gt", List.of("$deadlineAt", "$$NOW")),
                        new Document("$gt", List.of("$lockedUntil", "$$NOW"))))));
        return database.getCollection("fetch_runs").updateOne(session, runGuard,
                combine(set("result", "SUCCESS"), set("finishedAt", Date.from(publishedAt)), set("httpStatus", sourceStatus()),
                        set("sourceFlightCount", batch.sourceFlightCount()), set("flightCount", batch.flights().size())))
                .getModifiedCount() == 1;
    }

    @Override
    public void fail(RunClaim claim, String code, String reason, Integer httpStatus, Integer departureIndex) {
        // Failure bookkeeping has a separate bounded budget; it can never publish data.
        var cleanup = new RunDeadline(Duration.ofSeconds(2));
        try {
            operation(cleanup, db -> {
                var errors = new Document("code", code).append("message", reason).append("departureIndex", departureIndex);
                var update = combine(set("result", "ERROR"), set("finishedAt", Date.from(clock.instant())),
                        set("httpStatus", httpStatus), set("error", errors),
                        setOnInsert("startedAt", Date.from(claim.startedAt())), setOnInsert("fetchSeq", claim.fetchSeq()));
                try {
                    db.getCollection("fetch_runs").updateOne(and(eq("_id", claim.runId()), ne("result", "SUCCESS")),
                            update, new UpdateOptions().upsert(true));
                } catch (MongoException exception) {
                    if (exception.getCode() != 11000) { throw exception; } // A committed success must stay successful.
                }
                return null;
            });
        } finally {
            operation(cleanup, db -> {
                db.getCollection("fetch_control").updateOne(and(eq("_id", CONTROL_ID), eq("lockedBy", claim.runId())),
                        combine(set("lockedBy", null), set("lockedUntil", new Date(0))));
                return null;
            });
        }
    }

    public void pause(boolean paused) {
        operation(new RunDeadline(Duration.ofSeconds(2)), db -> {
            db.getCollection("fetch_control").updateOne(eq("_id", CONTROL_ID), set("paused", paused));
            return null;
        });
    }

    public FetchNowResult fetchNow() {
        return operation(new RunDeadline(Duration.ofSeconds(2)), db -> {
            var updated = db.getCollection("fetch_control").findOneAndUpdate(
                    and(eq("_id", CONTROL_ID), eq("paused", false), freeLock()),
                    List.of(new Document("$set", new Document("nextRunAt", "$$NOW"))));
            if (updated != null) { return FetchNowResult.ACCEPTED; }
            var current = db.getCollection("fetch_control").find(eq("_id", CONTROL_ID)).first();
            return current != null && current.getBoolean("paused", false) ? FetchNowResult.PAUSED : FetchNowResult.LOCKED;
        });
    }

    public Document readControl() {
        return operation(new RunDeadline(Duration.ofSeconds(2)), db -> db.getCollection("fetch_control")
                .find(eq("_id", CONTROL_ID)).first());
    }

    public Optional<BoardSnapshot> readBoard() {
        return operation(new RunDeadline(Duration.ofSeconds(2)), db -> Optional.ofNullable(db.getCollection("board_current")
                .find(and(eq("_id", BOARD_ID), expr(new Document("$gte", List.of("$publishedAt",
                        new Document("$subtract", List.of("$$NOW", properties.board().maxAge().toMillis()))))))).first())
                .map(MongoFetchStore::snapshot));
    }

    public Optional<Document> latestRun(String result) {
        return operation(new RunDeadline(Duration.ofSeconds(2)), db -> Optional.ofNullable(db.getCollection("fetch_runs")
                .find(eq("result", result)).sort(new Document("finishedAt", -1)).first()));
    }

    public long deleteExpiredBoard() {
        return operation(new RunDeadline(Duration.ofSeconds(5)), db -> db.getCollection("board_current")
                .deleteOne(and(eq("_id", BOARD_ID), expr(new Document("$lt", List.of("$publishedAt",
                        new Document("$subtract", List.of("$$NOW", properties.board().maxAge().toMillis())))))))
                .getDeletedCount());
    }

    private MongoDatabase db(RunDeadline deadline) {
        return database.withTimeout(deadline.remainingMillis(), TimeUnit.MILLISECONDS);
    }

    private <T> T operation(RunDeadline deadline, Function<MongoDatabase, T> action) {
        try { return action.apply(db(deadline)); }
        catch (MongoException exception) { throw new StoreException("DATABASE"); }
    }

    private static void abort(ClientSession session) {
        if (session.hasActiveTransaction()) {
            try { session.abortTransaction(); } catch (MongoException ignored) { /* Server expiry is the fallback. */ }
        }
    }

    private static Bson freeLock() {
        return or(eq("lockedBy", null), expr(new Document("$lte", List.of("$lockedUntil", "$$NOW"))));
    }

    private static Document after(Duration duration) {
        return new Document("$add", List.of("$$NOW", duration.toMillis()));
    }

    private Integer sourceStatus() {
        return properties.source() == FlightBoardProperties.SourceMode.AERODATABOX ? 200 : null;
    }

    private static Document runDocument(RunClaim claim) {
        return new Document("_id", claim.runId()).append("fetchSeq", claim.fetchSeq())
                .append("startedAt", Date.from(claim.startedAt())).append("deadlineAt", Date.from(claim.deadlineAt()))
                .append("lockedUntil", Date.from(claim.lockedUntil()));
    }

    private static Document boardDocument(RunClaim claim, ValidatedBatch batch, Instant publishedAt) {
        return new Document("_id", BOARD_ID).append("runId", claim.runId()).append("fetchSeq", claim.fetchSeq())
                .append("publishedAt", Date.from(publishedAt)).append("flights", batch.flights().stream().map(flight ->
                        new Document("number", flight.number()).append("scheduledAt", Date.from(flight.scheduledAt()))
                                .append("scheduled", flight.scheduled()).append("expected", flight.expected())
                                .append("destination", flight.destination()).append("checkIn", flight.checkIn())
                                .append("bagDrop", flight.bagDrop()).append("remark", flight.remark())
                                .append("remarkColor", flight.remarkColor().name()).append("terminal", flight.terminal())).toList());
    }

    private static BoardSnapshot snapshot(Document board) {
        var flights = board.getList("flights", Document.class).stream().map(flight -> new BoardFlight(
                flight.getString("number"), flight.getDate("scheduledAt").toInstant(), flight.getString("scheduled"),
                flight.getString("expected"), flight.getString("destination"), flight.getString("checkIn"),
                flight.getString("bagDrop"), flight.getString("remark"),
                BoardFlight.RemarkColor.valueOf(flight.getString("remarkColor")), flight.getString("terminal"))).toList();
        return new BoardSnapshot(board.getString("runId"), ((Number) board.get("fetchSeq")).longValue(),
                board.getDate("publishedAt").toInstant(), flights);
    }

    public enum FetchNowResult { ACCEPTED, PAUSED, LOCKED }
}
