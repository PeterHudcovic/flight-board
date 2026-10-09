package com.flightboard.config;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("flightboard")
public record FlightBoardProperties(
        SourceMode source, AeroDataBox aerodatabox, Fetch fetch, Request request,
        Board board, Admin admin, String version) {

    public FlightBoardProperties {
        if (source == null || aerodatabox == null || fetch == null || request == null
                || board == null || admin == null || version == null || version.isBlank()) {
            throw new IllegalArgumentException("Flight Board configuration is incomplete");
        }
        if (source == SourceMode.AERODATABOX && aerodatabox.apiKey().isBlank()) {
            throw new IllegalArgumentException("AERODATABOX_API_KEY is required for aerodatabox mode");
        }
    }

    public enum SourceMode { STUB, AERODATABOX }

    public record AeroDataBox(URI baseUrl, String host, String apiKey) {
        public AeroDataBox {
            if (baseUrl == null || baseUrl.getHost() == null
                    || !("https".equalsIgnoreCase(baseUrl.getScheme())
                    || "http".equalsIgnoreCase(baseUrl.getScheme()))
                    || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
                    || baseUrl.getFragment() != null) {
                throw new IllegalArgumentException("Source base URL must be an HTTP(S) URL without credentials, query or fragment");
            }
            if (host == null || !host.matches("[A-Za-z0-9.-]+(?::[0-9]+)?")) {
                throw new IllegalArgumentException("Source host must be a valid header hostname");
            }
            apiKey = apiKey == null ? "" : apiKey;
            if (apiKey.chars().anyMatch(c -> c < 32 || c > 126)) {
                throw new IllegalArgumentException("API key contains invalid header characters");
            }
        }

        @Override
        public String toString() {
            return "AeroDataBox[baseUrl=" + baseUrl + ", host=" + host + ", apiKey=<redacted>]";
        }
    }

    public record Fetch(Duration interval, Duration checkInterval, Duration lockDuration, Duration runTimeout) {
        public Fetch {
            positive(interval, "Fetch interval");
            positive(checkInterval, "Fetch check interval");
            positive(lockDuration, "Fetch lock duration");
            positive(runTimeout, "Fetch run timeout");
            if (runTimeout.compareTo(lockDuration) >= 0) {
                throw new IllegalArgumentException("Fetch run timeout must be shorter than lock duration");
            }
        }
    }

    public record Request(String airport, int offsetMinutes, int durationMinutes) {
        public Request {
            airport = airport == null ? "" : airport.toUpperCase(Locale.ROOT);
            if (!airport.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("Source airport must be a three-letter IATA code");
            }
            if (durationMinutes < 1 || durationMinutes > 720) {
                throw new IllegalArgumentException("Source duration must be between 1 and 720 minutes");
            }
        }
    }

    public record Board(Duration staleAfter, Duration maxAge, String terminal, int maxFlights, ZoneId timezone) {
        public Board {
            positive(staleAfter, "Board stale age");
            positive(maxAge, "Board maximum age");
            if (staleAfter.compareTo(maxAge) >= 0 || maxFlights < 1 || timezone == null) {
                throw new IllegalArgumentException("Board requires a positive flight limit, a timezone and stale age below maximum age");
            }
            terminal = terminal == null ? "" : terminal.strip();
            if (!terminal.isEmpty() && !terminal.equals("1") && !terminal.equals("2")) {
                throw new IllegalArgumentException("Board terminal must be empty, 1 or 2");
            }
        }
    }

    public record Admin(int port, String address) {
        public Admin {
            if (port != 8082 || !"127.0.0.1".equals(address)) {
                throw new IllegalArgumentException("Admin must listen on 127.0.0.1:8082 per the contract");
            }
        }
    }

    private static void positive(Duration duration, String name) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
