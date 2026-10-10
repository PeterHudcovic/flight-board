package com.flightboard.validation;

import java.util.Locale;
import java.util.Map;

/** Small display-name overrides; unlisted airports keep the source-name fallback. */
final class DestinationNames {
    private static final Map<String, String> OVERRIDES = Map.ofEntries(
            Map.entry("BVA", "PARIS BEAUVAIS"),
            Map.entry("FRA", "FRANKFURT"),
            Map.entry("RHO", "RHODES"),
            Map.entry("KGS", "KOS"),
            Map.entry("PMI", "PALMA DE MALLORCA"),
            Map.entry("CRL", "BRUSSELS CHARLEROI"),
            Map.entry("BGY", "MILAN BERGAMO"),
            Map.entry("CIA", "ROME CIAMPINO"),
            Map.entry("FCO", "ROME"),
            Map.entry("ORY", "PARIS"),
            Map.entry("CDG", "PARIS"),
            Map.entry("LTN", "LONDON"),
            Map.entry("STN", "LONDON"),
            Map.entry("LGW", "LONDON"),
            Map.entry("LHR", "LONDON"));

    private DestinationNames() {}

    static String resolve(String iata, String airportName) {
        String code = iata.strip().toUpperCase(Locale.ROOT);
        String override = OVERRIDES.get(code);
        if (override != null) { return override; }
        String name = airportName.strip();
        return (name.isBlank() || "Unknown".equalsIgnoreCase(name) ? code : name.toUpperCase(Locale.ROOT));
    }
}
