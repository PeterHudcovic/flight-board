package com.flightboard.source;

import com.flightboard.config.FlightBoardProperties;
import java.net.URI;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

public final class AeroDataBoxSource implements FlightSource {
    public static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final FlightBoardProperties properties;
    private final CloseableHttpClient client;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public AeroDataBoxSource(FlightBoardProperties properties) {
        this.properties = properties;
        Duration timeout = properties.fetch().runTimeout();
        Duration connectTimeout = timeout.compareTo(Duration.ofSeconds(10)) < 0 ? timeout : Duration.ofSeconds(10);
        var connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(connectTimeout)).build()).build();
        client = HttpClients.custom().setConnectionManager(connections)
                .disableAutomaticRetries().disableRedirectHandling().disableCookieManagement()
                .disableContentCompression()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.of(timeout))
                        .setResponseTimeout(Timeout.of(timeout)).build())
                .build();
    }

    @Override
    public String fetch() throws SourceException {
        return fetch(properties.fetch().runTimeout());
    }

    @Override
    public String fetch(Duration remaining) throws SourceException {
        if (Thread.currentThread().isInterrupted()) {
            throw new SourceException(SourceException.Kind.INTERRUPTED, "Source request interrupted", null);
        }
        Duration timeout = remaining.compareTo(properties.fetch().runTimeout()) < 0
                ? remaining : properties.fetch().runTimeout();
        if (timeout.isZero() || timeout.isNegative()) {
            throw new SourceException(SourceException.Kind.TIMEOUT, "Source request timed out", null);
        }
        HttpGet request = new HttpGet(requestUri());
        request.setConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.of(timeout))
                .setResponseTimeout(Timeout.of(timeout)).build());
        request.setHeader("X-RapidAPI-Key", properties.aerodatabox().apiKey());
        request.setHeader("X-RapidAPI-Host", properties.aerodatabox().host());
        request.setHeader("Accept", "application/json");
        var responseFuture = executor.submit(() -> readResponse(request));
        try {
            // The deadline includes connection acquisition, headers and the entire body.
            return responseFuture.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            request.cancel();
            responseFuture.cancel(true);
            throw new SourceException(SourceException.Kind.TIMEOUT, "Source request timed out", null);
        } catch (InterruptedException exception) {
            request.cancel();
            responseFuture.cancel(true);
            Thread.currentThread().interrupt();
            throw new SourceException(SourceException.Kind.INTERRUPTED, "Source request interrupted", null);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof SourceException safeException) {
                throw safeException;
            }
            for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof SocketTimeoutException) {
                    throw new SourceException(SourceException.Kind.TIMEOUT, "Source request timed out", null);
                }
            }
            throw new SourceException(SourceException.Kind.IO, "Source request failed", null);
        }
    }

    private String readResponse(HttpGet request) throws IOException, SourceException {
        // Manual resource scope avoids automatic consumption of arbitrarily large error bodies.
        try (var response = client.executeOpen(null, request, null)) {
            int status = response.getCode();
            if (status != 200) {
                request.cancel();
                throw new SourceException(SourceException.Kind.HTTP_STATUS,
                        "Source returned HTTP " + status, status);
            }
            byte[] body;
            if (response.getEntity() == null) {
                body = new byte[0];
            } else {
                body = response.getEntity().getContent().readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (body.length > MAX_RESPONSE_BYTES) {
                request.cancel();
                throw new SourceException(SourceException.Kind.RESPONSE_TOO_LARGE,
                        "Source response exceeds the size limit", status);
            }
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(body)).toString();
            } catch (CharacterCodingException exception) {
                throw new SourceException(SourceException.Kind.INVALID_ENCODING,
                        "Source response is not valid UTF-8", status);
            }
        }
    }

    private URI requestUri() {
        String base = properties.aerodatabox().baseUrl().toString().replaceAll("/+$", "");
        var request = properties.request();
        return URI.create(base + "/flights/airports/iata/" + request.airport()
                + "?offsetMinutes=" + request.offsetMinutes()
                + "&durationMinutes=" + request.durationMinutes()
                + "&direction=Departure&withLeg=false&withCodeshared=false&withCargo=false");
    }

    @Override
    public void close() {
        client.close(CloseMode.IMMEDIATE);
        executor.shutdownNow();
        executor.close();
    }
}
