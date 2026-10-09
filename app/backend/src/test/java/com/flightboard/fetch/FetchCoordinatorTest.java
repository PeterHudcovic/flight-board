package com.flightboard.fetch;

import static com.flightboard.support.TestData.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.flightboard.config.FlightBoardProperties;
import com.flightboard.persistence.FetchStore;
import com.flightboard.source.FlightSource;
import com.flightboard.validation.ValidatedBatch;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FetchCoordinatorTest {
    @Test
    void successfulChecksReleaseLocalGuardAndUseUniqueRunIds() {
        var store = new MemoryStore();
        var sourceCalls = new AtomicInteger();
        try (var coordinator = new FetchCoordinator(store, () -> { sourceCalls.incrementAndGet(); return batch(); },
                (json, id) -> new ValidatedBatch(List.of(), 0), properties())) {
            var first = coordinator.runOnce();
            var second = coordinator.runOnce();
            assertThat(first.state()).isEqualTo(FetchResult.State.SUCCESS);
            assertThat(second.state()).isEqualTo(FetchResult.State.SUCCESS);
            assertThat(first.runId()).isNotEqualTo(second.runId());
            assertThat(sourceCalls).hasValue(2);
            assertThat(store.publications).hasValue(2);
        }
    }

    @Test
    void httpReceivesRemainingBudgetAfterDatabaseClaim() {
        var store = new MemoryStore() {
            @Override public Optional<RunClaim> claim(String id, RunDeadline deadline) {
                try { Thread.sleep(150); } catch (InterruptedException exception) { throw new AssertionError(exception); }
                return super.claim(id, deadline);
            }
        };
        var received = new AtomicReference<Duration>();
        FlightSource source = new FlightSource() {
            @Override public String fetch() { throw new AssertionError("Missing deadline"); }
            @Override public String fetch(Duration remaining) { received.set(remaining); return batch(); }
        };
        try (var coordinator = new FetchCoordinator(store, source, (json, id) -> new ValidatedBatch(List.of(), 0),
                withTimeout(Duration.ofSeconds(2)))) {
            assertThat(coordinator.runOnce().state()).isEqualTo(FetchResult.State.SUCCESS);
            assertThat(received.get()).isLessThan(Duration.ofMillis(1900)).isPositive();
        }
    }

    @Test
    void timeoutDuringUncooperativeSourceBlocksOverlappingChecksAndCannotPublishLate() throws Exception {
        var store = new MemoryStore();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        FlightSource source = () -> {
            entered.countDown();
            boolean ready = false;
            while (!ready) {
                try { ready = release.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { /* Exercise a broken adapter ignoring cancellation. */ }
            }
            return batch();
        };
        try (var coordinator = new FetchCoordinator(store, source, (json, id) -> new ValidatedBatch(List.of(), 0),
                withTimeout(Duration.ofMillis(300)))) {
            long started = System.nanoTime();
            assertThat(coordinator.runOnce().state()).isEqualTo(FetchResult.State.TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            assertThat(entered.getCount()).isZero();
            assertThat(coordinator.runOnce().code()).isEqualTo("LOCAL_RUN_ACTIVE");
            release.countDown();
            await().atMost(Duration.ofSeconds(2)).until(() -> store.failures.get() == 1);
            assertThat(store.error.get()).isEqualTo("RUN_TIMEOUT");
            assertThat(store.publications).hasValue(0);
        } finally { release.countDown(); }
    }

    @Test
    void unexpectedExceptionIsSanitizedBeforeRunPersistence() {
        var store = new MemoryStore();
        try (var coordinator = new FetchCoordinator(store, () -> { throw new IllegalStateException("private-response-and-dummy-key"); },
                (json, id) -> new ValidatedBatch(List.of(), 0), properties())) {
            assertThat(coordinator.runOnce().code()).isEqualTo("INTERNAL");
            assertThat(store.reason.get()).isEqualTo("Run failed");
            assertThat(store.publications).hasValue(0);
        }
    }

    static FlightBoardProperties withTimeout(Duration timeout) {
        var defaults = properties();
        return new FlightBoardProperties(defaults.source(), defaults.aerodatabox(),
                new FlightBoardProperties.Fetch(defaults.fetch().interval(), defaults.fetch().checkInterval(),
                        defaults.fetch().lockDuration(), timeout), defaults.request(), defaults.board(), defaults.admin(), defaults.version());
    }

    static class MemoryStore implements FetchStore {
        final AtomicInteger publications = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicReference<String> error = new AtomicReference<>();
        final AtomicReference<String> reason = new AtomicReference<>();
        @Override public Optional<RunClaim> claim(String id, RunDeadline deadline) {
            return Optional.of(new RunClaim(id, 1, Instant.now(), Instant.now().plusSeconds(120), Instant.now().plusSeconds(300)));
        }
        @Override public boolean publish(RunClaim claim, ValidatedBatch batch, RunDeadline deadline) {
            deadline.check(); publications.incrementAndGet(); return true;
        }
        @Override public void fail(RunClaim claim, String code, String message, Integer status, Integer index) {
            error.set(code); reason.set(message); failures.incrementAndGet();
        }
    }
}
