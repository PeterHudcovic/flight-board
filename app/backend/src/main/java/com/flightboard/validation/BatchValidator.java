package com.flightboard.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flightboard.config.FlightBoardProperties;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BatchValidator {
    private static final Logger LOG = LoggerFactory.getLogger(BatchValidator.class);
    private static final DateTimeFormatter SOURCE_TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mmXXX", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final Pattern CHECK_IN = Pattern.compile("^(\\d{1,3})(?:[,-](\\d{1,3}))?$");
    private final FlightBoardProperties.Board board;
    private final ObjectMapper mapper;

    public BatchValidator(FlightBoardProperties.Board board, ObjectMapper mapper) {
        this.board = board;
        this.mapper = mapper;
    }

    public ValidatedBatch validate(String json, String runId) throws BatchValidationException {
        JsonNode root;
        try {
            if (json == null) {
                throw new BatchValidationException("Response is not valid JSON", null);
            }
            root = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
        } catch (JsonProcessingException exception) {
            throw new BatchValidationException("Response is not valid JSON", null);
        }
        if (root == null || !root.isObject() || !root.path("departures").isArray()) {
            throw new BatchValidationException("departures must be an array in a root object", null);
        }
        JsonNode departures = root.path("departures");
        List<ParsedDeparture> validated = new ArrayList<>();
        // Validate every departure before filtering: even an invalid hidden flight rejects the batch.
        for (int index = 0; index < departures.size(); index++) {
            JsonNode departure = departures.get(index);
            if (!departure.isObject()) {
                throw new BatchValidationException("Departure must be an object", index);
            }
            String number = text(departure.path("number"));
            number = number.codePoints().filter(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c))
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
            if (number.isEmpty()) {
                throw new BatchValidationException("Departure number must be a nonblank string", index);
            }
            OffsetDateTime scheduled;
            try {
                scheduled = OffsetDateTime.parse(text(departure.path("movement").path("scheduledTime").path("local")), SOURCE_TIME);
            } catch (DateTimeParseException exception) {
                throw new BatchValidationException("Departure scheduled time must be parsable", index);
            }
            validated.add(new ParsedDeparture(departure, number, scheduled));
        }
        List<BoardFlight> flights = validated.stream()
                .filter(parsed -> visible(parsed.departure()))
                .map(parsed -> map(parsed, runId))
                .sorted(Comparator.comparing(BoardFlight::scheduledAt).thenComparing(BoardFlight::number))
                .limit(board.maxFlights()).toList();
        return new ValidatedBatch(flights, departures.size());
    }

    private boolean visible(JsonNode departure) {
        JsonNode cargo = departure.path("isCargo");
        return !(cargo.isBoolean() && cargo.booleanValue())
                && !"IsCodeshared".equals(text(departure.path("codeshareStatus")))
                && !"Departed".equals(text(departure.path("status")))
                && (board.terminal().isEmpty()
                || board.terminal().equals(text(departure.path("movement").path("terminal"))));
    }

    private BoardFlight map(ParsedDeparture parsed, String runId) {
        JsonNode departure = parsed.departure();
        JsonNode movement = departure.path("movement");
        String expected = "";
        JsonNode revisedNode = movement.path("revisedTime").path("local");
        if (!revisedNode.isMissingNode() && !revisedNode.isNull()) {
            try {
                var revised = OffsetDateTime.parse(text(revisedNode), SOURCE_TIME);
                if (!revised.toInstant().equals(parsed.scheduled().toInstant())) {
                    expected = DISPLAY_TIME.format(revised.atZoneSameInstant(board.timezone()));
                }
            } catch (DateTimeParseException exception) {
                LOG.warn("Ignored invalid optional revised time for run {} flight {}", runId, parsed.number());
            }
        }
        JsonNode airport = movement.path("airport");
        String destination = DestinationNames.resolve(text(airport.path("iata")), text(airport.path("name")));
        String status = text(departure.path("status"));
        String remark = switch (status) {
            case "Boarding" -> "Boarding";
            case "GateClosed" -> "Gate closed";
            case "Delayed" -> "Delayed";
            case "Canceled" -> "Cancelled";
            case "Expected", "CheckIn", "CanceledUncertain" -> "";
            default -> {
                LOG.warn("Unknown or missing source status for run {} flight {}", runId, parsed.number());
                yield "";
            }
        };
        var color = switch (status) {
            case "Boarding" -> BoardFlight.RemarkColor.YELLOW;
            case "Canceled" -> BoardFlight.RemarkColor.RED;
            default -> BoardFlight.RemarkColor.WHITE;
        };
        return new BoardFlight(parsed.number(), parsed.scheduled().toInstant(),
                DISPLAY_TIME.format(parsed.scheduled().atZoneSameInstant(board.timezone())), expected,
                destination, checkIn(text(movement.path("checkInDesk"))), "",
                remark, color, text(movement.path("terminal")));
    }

    private static String checkIn(String value) {
        var matcher = CHECK_IN.matcher(value);
        if (!matcher.matches()) {
            return "";
        }
        return matcher.group(2) != null && matcher.group(1).equals(matcher.group(2)) ? matcher.group(1) : value;
    }

    private static String text(JsonNode node) {
        return node.isTextual() ? node.textValue() : "";
    }

    private record ParsedDeparture(JsonNode departure, String number, OffsetDateTime scheduled) {}
}
