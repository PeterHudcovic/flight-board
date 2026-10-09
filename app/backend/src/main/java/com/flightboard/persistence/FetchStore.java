package com.flightboard.persistence;

import com.flightboard.fetch.RunClaim;
import com.flightboard.fetch.RunDeadline;
import com.flightboard.validation.ValidatedBatch;
import java.util.Optional;

public interface FetchStore {
    Optional<RunClaim> claim(String runId, RunDeadline deadline);
    boolean publish(RunClaim claim, ValidatedBatch batch, RunDeadline deadline);
    void fail(RunClaim claim, String code, String reason, Integer httpStatus, Integer departureIndex);
}
