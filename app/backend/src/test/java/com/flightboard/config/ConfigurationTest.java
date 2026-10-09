package com.flightboard.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightboard.source.AeroDataBoxSource;
import com.flightboard.source.FlightSource;
import com.flightboard.source.StubFlightSource;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

class ConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(SourceConfiguration.class, PropertyBinding.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FlightBoardProperties.class)
    static class PropertyBinding {}

    @Test
    void defaultsUseStubWithoutAKeyAndMatchTheContract() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FlightSource.class)).isInstanceOf(StubFlightSource.class);
            var config = context.getBean(FlightBoardProperties.class);
            assertThat(config.source()).isEqualTo(FlightBoardProperties.SourceMode.STUB);
            assertThat(config.aerodatabox().apiKey()).isEmpty();
            assertThat(config.fetch().interval()).isEqualTo(Duration.ofMinutes(30));
            assertThat(config.fetch().checkInterval()).isEqualTo(Duration.ofMinutes(1));
            assertThat(config.fetch().lockDuration()).isEqualTo(Duration.ofMinutes(5));
            assertThat(config.fetch().runTimeout()).isEqualTo(Duration.ofMinutes(2));
            assertThat(config.board().staleAfter()).isEqualTo(Duration.ofMinutes(75));
            assertThat(config.board().maxAge()).isEqualTo(Duration.ofHours(12));
            assertThat(config.board().terminal()).isEqualTo("2");
            assertThat(config.board().maxFlights()).isEqualTo(36);
            assertThat(config.board().timezone()).isEqualTo(ZoneId.of("Europe/Prague"));
            assertThat(config.request()).isEqualTo(new FlightBoardProperties.Request("PRG", -60, 720));
            assertThat(config.admin()).isEqualTo(new FlightBoardProperties.Admin(8082, "127.0.0.1"));
            assertThat(context.getEnvironment().getProperty("spring.data.mongodb.database")).isEqualTo("flightboard");
            assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("8080");
            assertThat(context.getEnvironment().getProperty("management.server.port")).isEqualTo("8081");
        });
    }

    @Test
    void mapsUnderscoreSeparatedNamesExplicitlyIncludingEmptyTerminal() {
        runner.withPropertyValues(
                "FLIGHTBOARD_SOURCE=aerodatabox", "AERODATABOX_API_KEY=dummy-test-key",
                "FLIGHTBOARD_AERODATABOX_BASE_URL=http://127.0.0.1:12345",
                "FLIGHTBOARD_AERODATABOX_HOST=test-host.example", "FLIGHTBOARD_MONGODB_DATABASE=testdb",
                "FLIGHTBOARD_FETCH_INTERVAL=PT40M", "FLIGHTBOARD_FETCH_CHECK_INTERVAL=PT20S",
                "FLIGHTBOARD_FETCH_LOCK_DURATION=PT4M", "FLIGHTBOARD_FETCH_RUN_TIMEOUT=PT30S",
                "FLIGHTBOARD_SOURCE_AIRPORT=AAA", "FLIGHTBOARD_SOURCE_OFFSET_MINUTES=-30",
                "FLIGHTBOARD_SOURCE_DURATION_MINUTES=300", "FLIGHTBOARD_BOARD_STALE_AFTER=PT90M",
                "FLIGHTBOARD_BOARD_MAX_AGE=PT6H", "FLIGHTBOARD_BOARD_TERMINAL=",
                "FLIGHTBOARD_BOARD_MAX_FLIGHTS=12", "FLIGHTBOARD_BOARD_TIMEZONE=UTC",
                "FLIGHTBOARD_ADMIN_PORT=8082", "FLIGHTBOARD_ADMIN_ADDRESS=127.0.0.1",
                "FLIGHTBOARD_VERSION=synthetic-sha", "SERVER_PORT=18080", "MANAGEMENT_SERVER_PORT=18081")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(FlightSource.class)).isInstanceOf(AeroDataBoxSource.class);
                    var config = context.getBean(FlightBoardProperties.class);
                    assertThat(config.aerodatabox().baseUrl()).isEqualTo(URI.create("http://127.0.0.1:12345"));
                    assertThat(config.aerodatabox().host()).isEqualTo("test-host.example");
                    assertThat(config.aerodatabox().apiKey()).isEqualTo("dummy-test-key");
                    assertThat(config.toString()).doesNotContain("dummy-test-key");
                    assertThat(config.fetch()).isEqualTo(new FlightBoardProperties.Fetch(Duration.ofMinutes(40),
                            Duration.ofSeconds(20), Duration.ofMinutes(4), Duration.ofSeconds(30)));
                    assertThat(config.request()).isEqualTo(new FlightBoardProperties.Request("AAA", -30, 300));
                    assertThat(config.board()).isEqualTo(new FlightBoardProperties.Board(Duration.ofMinutes(90),
                            Duration.ofHours(6), "", 12, ZoneId.of("UTC")));
                    assertThat(config.version()).isEqualTo("synthetic-sha");
                    assertThat(context.getEnvironment().getProperty("spring.data.mongodb.database")).isEqualTo("testdb");
                    assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("18080");
                    assertThat(context.getEnvironment().getProperty("management.server.port")).isEqualTo("18081");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "FLIGHTBOARD_SOURCE=unknown", "FLIGHTBOARD_SOURCE=aerodatabox",
            "FLIGHTBOARD_FETCH_INTERVAL=PT0S", "FLIGHTBOARD_FETCH_RUN_TIMEOUT=PT6M",
            "FLIGHTBOARD_SOURCE_DURATION_MINUTES=721", "FLIGHTBOARD_SOURCE_DURATION_MINUTES=0",
            "FLIGHTBOARD_SOURCE_AIRPORT=not-an-airport", "FLIGHTBOARD_BOARD_MAX_FLIGHTS=0",
            "FLIGHTBOARD_BOARD_TIMEZONE=not-a-zone", "FLIGHTBOARD_BOARD_TERMINAL=3",
            "FLIGHTBOARD_BOARD_STALE_AFTER=PT13H", "FLIGHTBOARD_ADMIN_ADDRESS=0.0.0.0",
            "FLIGHTBOARD_ADMIN_PORT=8080", "FLIGHTBOARD_AERODATABOX_BASE_URL=ftp://example.invalid",
            "FLIGHTBOARD_AERODATABOX_BASE_URL=http://user:password@example.invalid"
    })
    void failsFastOnInvalidConfiguration(String setting) {
        runner.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
    }
}
