package com.flightboard.validation;

/** Rejection metadata suitable for a run record; never includes raw source data. */
public final class BatchValidationException extends Exception {
    private final Integer departureIndex;

    public BatchValidationException(String reason, Integer departureIndex) {
        super(reason);
        this.departureIndex = departureIndex;
    }

    public Integer departureIndex() { return departureIndex; }
}
