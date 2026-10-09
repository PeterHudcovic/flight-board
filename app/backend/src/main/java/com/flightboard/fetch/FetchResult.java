package com.flightboard.fetch;

public record FetchResult(String runId, State state, String code) {
    public enum State { SKIPPED, SUCCESS, ERROR, TIMEOUT }
}
