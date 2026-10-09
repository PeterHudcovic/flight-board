package com.flightboard.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flightboard.config.FlightBoardProperties;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Generated synthetic data; no real flight fixtures and no HTTP client. */
public final class StubFlightSource implements FlightSource {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mmXXX", Locale.ROOT);
    private final FlightBoardProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;

    public StubFlightSource(FlightBoardProperties properties, ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public String fetch() {
        var root = mapper.createObjectNode();
        var departures = root.putArray("departures");
        var windowStart = clock.instant().plus(properties.request().offsetMinutes(), ChronoUnit.MINUTES)
                .truncatedTo(ChronoUnit.MINUTES);
        int count = Math.min(12, properties.request().durationMinutes());
        for (int index = 0; index < count; index++) {
            long offset = (long) properties.request().durationMinutes() * (index + 1) / (count + 1);
            var scheduled = windowStart.plus(offset, ChronoUnit.MINUTES).atZone(properties.board().timezone());
            var departure = departures.addObject();
            departure.put("number", "ZZ " + (1000 + index));
            departure.put("status", index == 0 ? "Boarding" : "Expected");
            departure.put("codeshareStatus", "IsOperator");
            departure.put("isCargo", false);
            var movement = departure.putObject("movement");
            movement.putObject("scheduledTime").put("local", FORMAT.format(scheduled));
            movement.putObject("revisedTime").put("local", FORMAT.format(scheduled));
            movement.putObject("airport").put("name", "Exampleville " + (index + 1)).put("iata", "AAA");
            movement.put("terminal", "2");
            movement.put("checkInDesk", "100-102");
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not generate synthetic source data");
        }
    }
}
