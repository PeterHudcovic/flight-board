package com.flightboard.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flightboard.config.FlightBoardProperties;
import com.flightboard.config.SourceConfiguration;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;

public final class TestData {
    public static final ObjectMapper MAPPER = new SourceConfiguration().objectMapper();

    private TestData() {}

    public static FlightBoardProperties properties() {
        return new FlightBoardProperties(FlightBoardProperties.SourceMode.STUB,
                new FlightBoardProperties.AeroDataBox(URI.create("https://aerodatabox.p.rapidapi.com"),
                        "aerodatabox.p.rapidapi.com", ""),
                new FlightBoardProperties.Fetch(Duration.ofMinutes(30), Duration.ofMinutes(1),
                        Duration.ofMinutes(5), Duration.ofMinutes(2)),
                new FlightBoardProperties.Request("PRG", -60, 720),
                new FlightBoardProperties.Board(Duration.ofMinutes(75), Duration.ofHours(12),
                        "2", 36, ZoneId.of("Europe/Prague")),
                new FlightBoardProperties.Admin(8082, "127.0.0.1"), "test-version");
    }

    public static FlightBoardProperties httpProperties(String baseUrl, Duration timeout) {
        var defaults = properties();
        return new FlightBoardProperties(FlightBoardProperties.SourceMode.AERODATABOX,
                new FlightBoardProperties.AeroDataBox(URI.create(baseUrl), "test-host.example", "dummy-test-key"),
                new FlightBoardProperties.Fetch(defaults.fetch().interval(), defaults.fetch().checkInterval(),
                        defaults.fetch().lockDuration(), timeout),
                defaults.request(), defaults.board(), defaults.admin(), defaults.version());
    }

    public static ObjectNode departure(String number, String scheduled) {
        var departure = MAPPER.createObjectNode();
        departure.put("number", number).put("status", "Expected").put("codeshareStatus", "IsOperator")
                .put("isCargo", false);
        var movement = departure.putObject("movement");
        movement.putObject("scheduledTime").put("local", scheduled);
        movement.put("terminal", "2").put("checkInDesk", "100-102");
        movement.putObject("airport").put("name", "Exampleville").put("iata", "AAA");
        return departure;
    }

    public static String batch(ObjectNode... departures) {
        var root = MAPPER.createObjectNode();
        ArrayNode array = root.putArray("departures");
        for (var departure : departures) {
            array.add(departure);
        }
        return root.toString();
    }

    public static ObjectNode movement(ObjectNode departure) {
        return (ObjectNode) departure.get("movement");
    }
}
