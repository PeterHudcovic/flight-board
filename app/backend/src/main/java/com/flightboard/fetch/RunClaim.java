package com.flightboard.fetch;

import java.time.Instant;

public record RunClaim(String runId, long fetchSeq, Instant startedAt, Instant deadlineAt, Instant lockedUntil) {}
