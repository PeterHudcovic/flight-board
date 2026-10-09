package com.flightboard.source;

/** Safe failure metadata: never includes response bodies, API keys or request headers. */
public final class SourceException extends Exception {
    public enum Kind { HTTP_STATUS, TIMEOUT, IO, INTERRUPTED, RESPONSE_TOO_LARGE, INVALID_ENCODING }

    private final Kind kind;
    private final Integer httpStatus;

    public SourceException(Kind kind, String message, Integer httpStatus) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public Kind kind() { return kind; }
    public Integer httpStatus() { return httpStatus; }
}
