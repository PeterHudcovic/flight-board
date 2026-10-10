package com.flightboard.api;

import com.flightboard.config.FlightBoardProperties;
import com.flightboard.persistence.MongoFetchStore;
import com.flightboard.persistence.StoreException;
import com.flightboard.source.SourceException;
import com.flightboard.validation.BoardFlight;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bson.Document;
import org.springframework.stereotype.Service;

@Service
public final class ApiService {
    private static final Set<String> ERROR_CODES = Stream.concat(
            Stream.of("RUN_TIMEOUT", "INVALID_BATCH", "PUBLICATION_REJECTED", "RETRY_LIMIT", "PUBLISH_UNCERTAIN", "DATABASE", "INTERNAL"),
            Stream.of(SourceException.Kind.values()).map(kind -> "SOURCE_" + kind.name())).collect(Collectors.toUnmodifiableSet());
    private final MongoFetchStore store;
    private final FlightBoardProperties properties;
    private final Clock clock;

    public ApiService(MongoFetchStore store, FlightBoardProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    public Departures departures() {
        requireInitialized();
        var board = store.readBoard().orElseThrow(NoDataException::new);
        Duration age = age(board.publishedAt());
        return new Departures(board.flights(), board.publishedAt(), age.toSeconds(), stale(age), board.runId());
    }

    public Status status() {
        requireInitialized();
        var control = store.readControl();
        if (control == null) { throw new StoreException("DATABASE"); }
        var board = store.readBoard().orElse(null);
        Duration age = board == null ? null : age(board.publishedAt());
        var success = store.latestRun("SUCCESS").map(ApiService::success).orElse(null);
        var error = store.latestRun("ERROR").map(ApiService::error).orElse(null);
        return new Status(properties.version(), control.getBoolean("paused", false), instant(control, "nextRunAt"),
                board == null ? null : board.publishedAt(), age == null ? null : age.toSeconds(),
                age == null || stale(age), success, error);
    }

    private void requireInitialized() {
        if (!store.initialized()) { throw new StoreException("DATABASE"); }
    }

    private Duration age(Instant publishedAt) {
        Duration age = Duration.between(publishedAt, clock.instant());
        return age.isNegative() ? Duration.ZERO : age;
    }

    private boolean stale(Duration age) { return age.compareTo(properties.board().staleAfter()) >= 0; }

    private static Success success(Document run) {
        return new Success(run.getString("_id"), instant(run, "startedAt"), instant(run, "finishedAt"),
                run.getInteger("httpStatus"), run.getInteger("sourceFlightCount"), run.getInteger("flightCount"));
    }

    private static Error error(Document run) {
        var detail = run.get("error", Document.class);
        String storedCode = detail == null ? null : detail.getString("code");
        String code = ERROR_CODES.contains(storedCode == null ? "" : storedCode) ? storedCode : "RUN_FAILED";
        // Return a bounded, known message; never expose persisted exception text or provider data.
        String message = switch (code) {
            case "INVALID_BATCH" -> "Source response failed batch validation";
            case "RUN_TIMEOUT", "SOURCE_TIMEOUT" -> "Fetch run time limit exceeded";
            case "PUBLICATION_REJECTED" -> "Publication conditions were not met";
            case "PUBLISH_UNCERTAIN" -> "Publication commit could not be confirmed";
            case "RETRY_LIMIT" -> "Transaction retry limit exceeded";
            case "DATABASE" -> "Database operation failed";
            default -> code.startsWith("SOURCE_") ? "Source request failed" : "Fetch run failed";
        };
        return new Error(run.getString("_id"), instant(run, "finishedAt"), code, message,
                run.getInteger("httpStatus"), detail == null ? null : detail.getInteger("departureIndex"));
    }

    private static Instant instant(Document document, String field) {
        Date value = document.getDate(field);
        return value == null ? null : value.toInstant();
    }

    public record Departures(List<BoardFlight> flights, Instant publishedAt, long dataAgeSeconds, boolean stale, String runId) {}
    public record Status(String version, boolean paused, Instant nextRunAt, Instant publishedAt,
                         Long dataAgeSeconds, boolean stale, Success lastSuccessfulRun, Error lastError) {}
    public record Success(String runId, Instant startedAt, Instant finishedAt, Integer httpStatus,
                          Integer sourceFlightCount, Integer flightCount) {}
    public record Error(String runId, Instant finishedAt, String code, String message, Integer httpStatus, Integer departureIndex) {}
    public static final class NoDataException extends RuntimeException {}
}
