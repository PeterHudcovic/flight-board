package com.flightboard.source;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.flightboard.support.TestData.*;
import static org.assertj.core.api.Assertions.*;

import com.flightboard.config.FlightBoardProperties;
import com.flightboard.validation.BatchValidationException;
import com.flightboard.validation.BatchValidator;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.http.Fault;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AeroDataBoxSourceTest {
    private static final String PATH = "/flights/airports/iata/PRG";

    @RegisterExtension
    static WireMockExtension server = WireMockExtension.newInstance()
            .options(wireMockConfig().bindAddress("127.0.0.1").dynamicPort()).build();

    @Test
    void sendsOneContractRequestAndReturnsTheCompleteResponse() throws Exception {
        String body = batch(departure("ZZ 1234", "2030-06-01 23:50+02:00"));
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(body)));
        var config = httpProperties(server.baseUrl(), Duration.ofSeconds(10));
        try (var source = new AeroDataBoxSource(config)) {
            assertThat(source.fetch()).isEqualTo(body);
        }
        server.verify(1, getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("offsetMinutes", equalTo("-60"))
                .withQueryParam("durationMinutes", equalTo("720"))
                .withQueryParam("direction", equalTo("Departure"))
                .withQueryParam("withLeg", equalTo("false"))
                .withQueryParam("withCodeshared", equalTo("false"))
                .withQueryParam("withCargo", equalTo("false"))
                .withHeader("X-RapidAPI-Key", equalTo("dummy-test-key"))
                .withHeader("X-RapidAPI-Host", equalTo("test-host.example")));
        assertThat(server.getAllServeEvents()).hasSize(1);
    }

    @Test
    void usesConfiguredAirportWindowAndBasePath() throws Exception {
        var defaults = httpProperties(server.baseUrl() + "/gateway/", Duration.ofSeconds(10));
        var config = new FlightBoardProperties(defaults.source(), defaults.aerodatabox(), defaults.fetch(),
                new FlightBoardProperties.Request("AAA", -30, 300), defaults.board(), defaults.admin(), defaults.version());
        server.stubFor(get(urlPathEqualTo("/gateway/flights/airports/iata/AAA")).willReturn(okJson(batch())));
        try (var source = new AeroDataBoxSource(config)) {
            assertThat(source.fetch()).isEqualTo(batch());
        }
        server.verify(1, getRequestedFor(urlPathEqualTo("/gateway/flights/airports/iata/AAA"))
                .withQueryParam("offsetMinutes", equalTo("-30"))
                .withQueryParam("durationMinutes", equalTo("300")));
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 429, 500})
    void failsOnNon200ResponsesWithoutRetryingOrExposingTheBody(int status) throws Exception {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                .withBody("private provider body with dummy-test-key")));
        try (var source = new AeroDataBoxSource(httpProperties(server.baseUrl(), Duration.ofSeconds(10)))) {
            var exception = catchThrowableOfType(SourceException.class, source::fetch);
            assertThat(exception.kind()).isEqualTo(SourceException.Kind.HTTP_STATUS);
            assertThat(exception.httpStatus()).isEqualTo(status);
            assertThat(exception.toString()).doesNotContain("private provider body", "dummy-test-key");
        }
        server.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void doesNotFollowRedirectsOrForwardTheKeyToAnotherEndpoint() throws Exception {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(302)
                .withHeader("Location", server.baseUrl() + "/redirect-target")));
        try (var source = new AeroDataBoxSource(httpProperties(server.baseUrl(), Duration.ofSeconds(10)))) {
            var exception = catchThrowableOfType(SourceException.class, source::fetch);
            assertThat(exception.httpStatus()).isEqualTo(302);
        }
        server.verify(0, getRequestedFor(urlPathEqualTo("/redirect-target")));
        assertThat(server.getAllServeEvents()).hasSize(1);
    }

    @Test
    void timesOutWithoutRetrying() throws Exception {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(batch()).withFixedDelay(1500)));
        try (var source = new AeroDataBoxSource(httpProperties(server.baseUrl(), Duration.ofMillis(500)))) {
            var exception = catchThrowableOfType(SourceException.class, source::fetch);
            assertThat(exception.kind()).isEqualTo(SourceException.Kind.TIMEOUT);
        }
        server.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void timeoutAlsoCoversAResponseBodyThatStallsAfterHeaders() throws Exception {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(" ".repeat(1000) + batch())
                .withChunkedDribbleDelay(10, 2000)));
        try (var source = new AeroDataBoxSource(httpProperties(server.baseUrl(), Duration.ofMillis(500)))) {
            var exception = catchThrowableOfType(SourceException.class, source::fetch);
            assertThat(exception.kind()).isEqualTo(SourceException.Kind.TIMEOUT);
        }
        server.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void rejectsAnErrorStatusWithoutWaitingForItsSlowBody() throws Exception {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(429)
                .withBody("private provider body".repeat(100)).withChunkedDribbleDelay(10, 2000)));
        try (var source = new AeroDataBoxSource(httpProperties(server.baseUrl(), Duration.ofMillis(500)))) {
            var exception = catchThrowableOfType(SourceException.class, source::fetch);
            assertThat(exception.kind()).isEqualTo(SourceException.Kind.HTTP_STATUS);
            assertThat(exception.httpStatus()).isEqualTo(429);
        }
        server.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void invalidJsonFromA200ResponseIsRejectedByBatchValidation() throws Exception {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("{bad json")));
        try (var source = new AeroDataBoxSource(httpProperties(server.baseUrl(), Duration.ofSeconds(10)))) {
            var validator = new BatchValidator(properties().board(), MAPPER);
            assertThatThrownBy(() -> validator.validate(source.fetch(), "invalid-json-run"))
                    .isInstanceOf(BatchValidationException.class).hasMessage("Response is not valid JSON");
        }
    }

    @Test
    void transportFailureIsSafeAndDoesNotRepeatTheProviderRequest() throws Exception {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));
        try (var source = new AeroDataBoxSource(httpProperties(server.baseUrl(), Duration.ofSeconds(10)))) {
            var exception = catchThrowableOfType(SourceException.class, source::fetch);
            assertThat(exception.kind()).isEqualTo(SourceException.Kind.IO);
            assertThat(exception.toString()).doesNotContain("dummy-test-key");
        }
        server.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void failureOnAReusedConnectionDoesNotRepeatTheProviderRequest() throws Exception {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(batch())));
        try (var source = new AeroDataBoxSource(httpProperties(server.baseUrl(), Duration.ofSeconds(10)))) {
            assertThat(source.fetch()).isEqualTo(batch());
            server.resetAll();
            server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));
            assertThat(catchThrowableOfType(SourceException.class, source::fetch).kind())
                    .isEqualTo(SourceException.Kind.IO);
        }
        server.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void limitsResponseSizeBeforeBufferingAnUnboundedBody() throws Exception {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(ok("x".repeat(AeroDataBoxSource.MAX_RESPONSE_BYTES + 1))));
        try (var source = new AeroDataBoxSource(httpProperties(server.baseUrl(), Duration.ofSeconds(10)))) {
            var exception = catchThrowableOfType(SourceException.class, source::fetch);
            assertThat(exception.kind()).isEqualTo(SourceException.Kind.RESPONSE_TOO_LARGE);
        }
    }

    @Test
    void rejectsMalformedUtf8InsteadOfInventingReplacementCharacters() throws Exception {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withBody(new byte[] {(byte) 0xc3, (byte) 0x28})));
        try (var source = new AeroDataBoxSource(httpProperties(server.baseUrl(), Duration.ofSeconds(10)))) {
            var exception = catchThrowableOfType(SourceException.class, source::fetch);
            assertThat(exception.kind()).isEqualTo(SourceException.Kind.INVALID_ENCODING);
            assertThat(exception.httpStatus()).isEqualTo(200);
        }
    }

    @Test
    void preservesInterruptionAndDoesNotRequestDataWhenAlreadyInterrupted() throws Exception {
        try (var source = new AeroDataBoxSource(httpProperties(server.baseUrl(), Duration.ofSeconds(10)))) {
            try {
                Thread.currentThread().interrupt();
                var exception = catchThrowableOfType(SourceException.class, source::fetch);
                assertThat(exception.kind()).isEqualTo(SourceException.Kind.INTERRUPTED);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
        server.verify(0, getRequestedFor(urlPathEqualTo(PATH)));
    }
}
