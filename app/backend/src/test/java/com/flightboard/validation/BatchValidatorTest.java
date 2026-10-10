package com.flightboard.validation;

import static com.flightboard.support.TestData.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flightboard.config.FlightBoardProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class BatchValidatorTest {
    private final BatchValidator validator = new BatchValidator(properties().board(), MAPPER);

    @Test
    void acceptsAnEmptyBatchAsAnImmutableSuccessfulResult() throws Exception {
        var result = validator.validate(batch(), "empty-run");
        assertThat(result.flights()).isEmpty();
        assertThat(result.sourceFlightCount()).isZero();
        assertThatThrownBy(() -> result.flights().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "{invalid", "null", "[]", "{}", "{\"departures\":null}",
            "{\"departures\":{}}", "{\"departures\":\"not-an-array\"}",
            "{\"departures\":[]} {}", "{\"departures\":[],\"departures\":[]}"
    })
    void rejectsInvalidRootStructuresAndTrailingOrDuplicateJson(String json) {
        assertThatThrownBy(() -> validator.validate(json, "invalid-root-run")).isInstanceOf(BatchValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "false", "123", "\"string\"", "[]"})
    void rejectsNonObjectDepartureElements(String element) {
        var exception = catchThrowableOfType(BatchValidationException.class,
                () -> validator.validate("{\"departures\":[" + element + "]}", "invalid-element-run"));
        assertThat(exception.departureIndex()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "null", "number", "", "  \t\n", "\u00a0"})
    void rejectsMissingWrongTypeOrBlankNumbers(String value) {
        var departure = departure("ZZ 1234", "2030-01-01 10:00+01:00");
        switch (value) {
            case "missing" -> departure.remove("number");
            case "null" -> departure.putNull("number");
            case "number" -> departure.put("number", 1234);
            default -> departure.put("number", value);
        }
        assertThatThrownBy(() -> validator.validate(batch(departure), "invalid-number-run"))
                .isInstanceOf(BatchValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "2030-01-01T10:00+01:00", "2030-02-30 10:00+01:00",
            "2030-01-01 25:00+01:00", "2030-01-01 10:00", "2030-01-01 10:00+99:00"})
    void parsesRequiredTimeStrictlyWithTheContractSpaceFormat(String scheduled) {
        assertThatThrownBy(() -> validator.validate(batch(departure("ZZ 1234", scheduled)), "invalid-time-run"))
                .isInstanceOf(BatchValidationException.class);
    }

    @Test
    void rejectsTheWholeBatchEvenIfAnInvalidFlightWouldHaveBeenFilteredOut() {
        var valid = departure("ZZ 1234", "2030-01-01 10:00+01:00");
        var invalid = departure("ZZ 5678", "bad-time");
        invalid.put("isCargo", true).put("status", "Departed");
        movement(invalid).put("terminal", "1");
        var exception = catchThrowableOfType(BatchValidationException.class,
                () -> validator.validate(batch(valid, invalid), "partial-run"));
        assertThat(exception.departureIndex()).isEqualTo(1);
        assertThat(exception.getMessage()).doesNotContain("bad-time");
    }

    @Test
    void mapsAnEntireFlightAndStripsAllSpaceCharactersFromItsNumber() throws Exception {
        var departure = departure("ZZ \t12\u00a034", "2030-06-01 23:50+02:00");
        movement(departure).putObject("revisedTime").put("local", "2030-06-02 00:10+02:00");
        departure.put("status", "Boarding");
        var flight = validator.validate(batch(departure), "mapping-run").flights().getFirst();
        assertThat(flight.number()).isEqualTo("ZZ1234");
        assertThat(flight.scheduledAt()).isEqualTo(Instant.parse("2030-06-01T21:50:00Z"));
        assertThat(flight.scheduled()).isEqualTo("23:50");
        assertThat(flight.expected()).isEqualTo("00:10");
        assertThat(flight.destination()).isEqualTo("EXAMPLEVILLE");
        assertThat(flight.checkIn()).isEqualTo("100-102");
        assertThat(flight.bagDrop()).isEmpty();
        assertThat(flight.remark()).isEqualTo("Boarding");
        assertThat(flight.remarkColor()).isEqualTo(BoardFlight.RemarkColor.YELLOW);
    }

    @Test
    void doesNotRejectMissingOptionalDataOrInventTerminalTwo() throws Exception {
        var departure = departure("ZZ 1234", "2030-01-01 10:00+01:00");
        var movement = movement(departure);
        movement.remove("terminal");
        movement.remove("airport");
        movement.remove("checkInDesk");
        assertThat(validator.validate(batch(departure), "terminal-two-run").flights()).isEmpty();
        var allTerminals = new BatchValidator(new FlightBoardProperties.Board(Duration.ofMinutes(75),
                Duration.ofHours(12), "", 36, ZoneId.of("Europe/Prague")), MAPPER);
        var flight = allTerminals.validate(batch(departure), "all-terminals-run").flights().getFirst();
        assertThat(flight.terminal()).isEmpty();
        assertThat(flight.destination()).isEmpty();
        assertThat(flight.checkIn()).isEmpty();
        assertThat(flight.expected()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(value = {"Exampleville|AAA|EXAMPLEVILLE", "Exampleville||EXAMPLEVILLE",
            "unknown|AAA|AAA", "Unknown||", "|AAA|AAA", "||"}, delimiter = '|')
    void destinationDoesNotDependOnIata(String name, String iata, String expected) throws Exception {
        var departure = departure("ZZ 1234", "2030-01-01 10:00+01:00");
        var airport = (ObjectNode) movement(departure).get("airport");
        airport.removeAll();
        if (name != null) airport.put("name", name);
        if (iata != null) airport.put("iata", iata);
        var flight = validator.validate(batch(departure), "destination-run").flights().getFirst();
        assertThat(flight.destination()).isEqualTo(expected == null ? "" : expected);
    }

    @ParameterizedTest
    @CsvSource(value = {"BVA|PARIS BEAUVAIS", "FRA|FRANKFURT", "RHO|RHODES", "KGS|KOS",
            "PMI|PALMA DE MALLORCA", "CRL|BRUSSELS CHARLEROI", "BGY|MILAN BERGAMO",
            "CIA|ROME CIAMPINO", "FCO|ROME", "ORY|PARIS", "CDG|PARIS", "LTN|LONDON",
            "STN|LONDON", "LGW|LONDON", "LHR|LONDON"}, delimiter = '|')
    void knownAirportsUseCityOverridesBeforeTheSourceName(String iata, String expected) throws Exception {
        var departure = departure("ZZ 1234", "2030-01-01 10:00+01:00");
        var airport = (ObjectNode) movement(departure).get("airport");
        airport.put("iata", " " + iata.toLowerCase(Locale.ROOT) + " ").put("name", "Invented Airport Name");
        assertThat(validator.validate(batch(departure), "city-name-run").flights().getFirst().destination())
                .isEqualTo(expected);
        airport.remove("name");
        assertThat(validator.validate(batch(departure), "city-name-without-source-name-run")
                .flights().getFirst().destination()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(value = {"100|100", "100-102|100-102", "100,102|100,102", "100,100|100",
            "100-100|100", "0430|", "100-102-104|", "A100|", "1,2,3|", "|"}, delimiter = '|')
    void checkInIsNormalizedOrLeftEmpty(String value, String expected) throws Exception {
        var departure = departure("ZZ 1234", "2030-01-01 10:00+01:00");
        movement(departure).put("checkInDesk", value);
        assertThat(validator.validate(batch(departure), "check-in-run").flights().getFirst().checkIn())
                .isEqualTo(expected == null ? "" : expected);
    }

    @ParameterizedTest
    @CsvSource(value = {"Expected||WHITE", "CheckIn||WHITE", "Boarding|Boarding|YELLOW",
            "GateClosed|Gate closed|WHITE", "Delayed|Delayed|WHITE", "Canceled|Cancelled|RED",
            "CanceledUncertain||WHITE"}, delimiter = '|')
    void mapsStatusesWithoutInventingOpensAt(String status, String remark, String color) throws Exception {
        var departure = departure("ZZ 1234", "2030-01-01 10:00+01:00");
        departure.put("status", status);
        var flight = validator.validate(batch(departure), "status-run").flights().getFirst();
        assertThat(flight.remark()).isEqualTo(remark == null ? "" : remark);
        assertThat(flight.remarkColor()).isEqualTo(BoardFlight.RemarkColor.valueOf(color));
    }

    @Test
    void logsUnknownAndMissingStatusWithRunAndFlightWithoutEchoingRawStatus(CapturedOutput output) throws Exception {
        var unknown = departure("ZZ 1234", "2030-01-01 10:00+01:00");
        unknown.put("status", "Untrusted\nraw-status-value");
        var missing = departure("ZZ 5678", "2030-01-01 10:00+01:00");
        missing.remove("status");
        var result = validator.validate(batch(unknown, missing), "unknown-status-run");
        assertThat(result.flights()).allSatisfy(flight -> assertThat(flight.remark()).isEmpty());
        assertThat(output).contains("unknown-status-run", "ZZ1234", "ZZ5678")
                .doesNotContain("raw-status-value");
    }

    @Test
    void emptyExpectedTimeForEqualInstantsAndInvalidOptionalTime(CapturedOutput output) throws Exception {
        var same = departure("ZZ 1234", "2030-01-01 10:00+01:00");
        movement(same).putObject("revisedTime").put("local", "2030-01-01 09:00Z");
        var invalid = departure("ZZ 5678", "2030-01-01 10:00+01:00");
        movement(invalid).putObject("revisedTime").put("local", "not-a-time");
        assertThat(validator.validate(batch(same, invalid), "optional-time-run").flights())
                .allSatisfy(flight -> assertThat(flight.expected()).isEmpty());
        assertThat(output).contains("optional-time-run", "ZZ5678").doesNotContain("not-a-time");
    }

    @Test
    void removesCargoCodesharesDepartedAndOtherOrMissingTerminals() throws Exception {
        var accepted = departure("ZZ 1000", "2030-01-01 10:00+01:00");
        var cargo = departure("ZZ 1001", "2030-01-01 10:00+01:00");
        cargo.put("isCargo", true);
        var codeshare = departure("ZZ 1002", "2030-01-01 10:00+01:00");
        codeshare.put("codeshareStatus", "IsCodeshared");
        var departed = departure("ZZ 1003", "2030-01-01 10:00+01:00");
        departed.put("status", "Departed");
        var terminalOne = departure("ZZ 1004", "2030-01-01 10:00+01:00");
        movement(terminalOne).put("terminal", "1");
        var missingTerminal = departure("ZZ 1005", "2030-01-01 10:00+01:00");
        movement(missingTerminal).remove("terminal");
        var result = validator.validate(batch(accepted, cargo, codeshare, departed, terminalOne, missingTerminal), "filter-run");
        assertThat(result.sourceFlightCount()).isEqualTo(6);
        assertThat(result.flights()).extracting(BoardFlight::number).containsExactly("ZZ1000");
    }

    @Test
    void sortsAcrossMidnightByInstantAndBreaksTiesByFlightNumber() throws Exception {
        var midnight = departure("ZZ 2000", "2030-06-02 00:10+02:00");
        var laterNumber = departure("ZZ 1001", "2030-06-01 23:50+02:00");
        var earlierNumber = departure("ZZ 1000", "2030-06-01 23:50+02:00");
        assertThat(validator.validate(batch(midnight, laterNumber, earlierNumber), "midnight-run").flights())
                .extracting(BoardFlight::number).containsExactly("ZZ1000", "ZZ1001", "ZZ2000");
    }

    @Test
    void sortsTheRepeatedAutumnHourByInstantInsteadOfWallClock() throws Exception {
        var later = departure("ZZ 2000", "2030-10-27 02:10+01:00");
        var earlier = departure("ZZ 1000", "2030-10-27 02:50+02:00");
        assertThat(validator.validate(batch(later, earlier), "dst-run").flights())
                .extracting(BoardFlight::number).containsExactly("ZZ1000", "ZZ2000");
    }

    @Test
    void selectsTheFirst36AfterSortingAndFiltering() throws Exception {
        var departures = new ArrayList<ObjectNode>();
        for (int index = 39; index >= 0; index--) {
            departures.add(departure("ZZ " + (1000 + index), "2030-01-01 10:00+01:00"));
        }
        var result = validator.validate(batch(departures.toArray(ObjectNode[]::new)), "limit-run");
        assertThat(result.sourceFlightCount()).isEqualTo(40);
        assertThat(result.flights()).hasSize(36);
        assertThat(result.flights().getFirst().number()).isEqualTo("ZZ1000");
        assertThat(result.flights().getLast().number()).isEqualTo("ZZ1035");
    }

    @Test
    void uppercaseDoesNotDependOnTheMachineLocale() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var departure = departure("ZZ 1234", "2030-01-01 10:00+01:00");
            ((ObjectNode) movement(departure).get("airport")).put("name", "invented city");
            assertThat(validator.validate(batch(departure), "locale-run").flights().getFirst().destination())
                    .isEqualTo("INVENTED CITY");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
