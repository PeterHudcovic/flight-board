package com.flightboard.source;

import static com.flightboard.support.TestData.MAPPER;
import static com.flightboard.support.TestData.properties;
import static org.assertj.core.api.Assertions.assertThat;

import com.flightboard.validation.BatchValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class StubFlightSourceTest {
    @Test
    void generatesDeterministicSyntheticDataInTheConfiguredWindowWithoutAKey() throws Exception {
        var config = properties();
        var now = Instant.parse("2030-06-01T22:00:00Z");
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var source = new StubFlightSource(config, MAPPER, clock);
        assertThat(config.aerodatabox().apiKey()).isEmpty();
        assertThat(source.fetch()).isEqualTo(source.fetch());
        var batch = new BatchValidator(config.board(), MAPPER).validate(source.fetch(), "stub-run");
        assertThat(batch.sourceFlightCount()).isEqualTo(12);
        assertThat(batch.flights()).hasSize(12).allSatisfy(flight -> {
            assertThat(flight.number()).startsWith("ZZ");
            assertThat(flight.destination()).startsWith("EXAMPLEVILLE");
            assertThat(flight.terminal()).isEqualTo("2");
            assertThat(flight.bagDrop()).isEmpty();
            assertThat(flight.scheduledAt()).isAfterOrEqualTo(now.minusSeconds(3600)).isBefore(now.plusSeconds(39600));
        });
    }

    @Test
    void clockChangesMoveTheSyntheticDataInsteadOfUsingExpiredFixedFixtures() throws Exception {
        var first = new StubFlightSource(properties(), MAPPER,
                Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC));
        var second = new StubFlightSource(properties(), MAPPER,
                Clock.fixed(Instant.parse("2030-01-02T00:00:00Z"), ZoneOffset.UTC));
        var validator = new BatchValidator(properties().board(), MAPPER);
        var a = validator.validate(first.fetch(), "first").flights().getFirst();
        var b = validator.validate(second.fetch(), "second").flights().getFirst();
        assertThat(b.scheduledAt()).isEqualTo(a.scheduledAt().plusSeconds(86400));
    }
}
