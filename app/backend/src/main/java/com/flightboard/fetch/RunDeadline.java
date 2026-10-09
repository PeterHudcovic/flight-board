package com.flightboard.fetch;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** A monotonic budget shared by claim, source, validation and transaction retries. */
public final class RunDeadline {
    private final long started = System.nanoTime();
    private final long budget;

    public RunDeadline(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Deadline duration must be positive");
        }
        budget = duration.toNanos();
    }

    public Duration remaining() {
        long nanos = budget - (System.nanoTime() - started);
        if (nanos <= 0 || Thread.currentThread().isInterrupted()) {
            throw new RunTimeoutException();
        }
        return Duration.ofNanos(nanos);
    }

    public long remainingMillis() {
        // MongoDB interprets zero as unlimited; always round a live budget up to 1 ms.
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining().toNanos()));
    }

    public void check() { remaining(); }

    public boolean expired() { return budget - (System.nanoTime() - started) <= 0; }

    public static final class RunTimeoutException extends RuntimeException {
        public RunTimeoutException() { super("Run time limit exceeded"); }
    }
}
