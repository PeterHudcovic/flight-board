package com.flightboard.persistence;

/** Never retains a driver exception, URI, credentials or command payload. */
public final class StoreException extends RuntimeException {
    private final String code;

    public StoreException(String code) {
        super(switch (code) {
            case "PUBLISH_UNCERTAIN" -> "Publication commit could not be confirmed";
            case "RETRY_LIMIT" -> "Publication retry limit exceeded";
            default -> "Database operation failed";
        });
        this.code = code;
    }

    public String code() { return code; }
}
