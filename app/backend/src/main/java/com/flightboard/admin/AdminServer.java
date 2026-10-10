package com.flightboard.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flightboard.api.ApiErrors.ErrorBody;
import com.flightboard.config.FlightBoardProperties;
import com.flightboard.persistence.MongoFetchStore;
import com.flightboard.persistence.StoreException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.SmartLifecycle;

/** Separate server: its routes are never registered in the API or management MVC context. */
public final class AdminServer implements SmartLifecycle {
    private final MongoFetchStore store;
    private final ObjectMapper mapper;
    private final FlightBoardProperties.Admin config;
    private HttpServer server;
    private ExecutorService workers;

    public AdminServer(MongoFetchStore store, ObjectMapper mapper, FlightBoardProperties properties) {
        this.store = store;
        this.mapper = mapper;
        this.config = properties.admin();
    }

    @Override
    public synchronized void start() {
        if (server != null) { return; }
        try {
            var listener = HttpServer.create(new InetSocketAddress(config.address(), config.port()), 16);
            workers = Executors.newVirtualThreadPerTaskExecutor();
            listener.setExecutor(workers);
            listener.createContext("/", this::handle);
            listener.start();
            server = listener;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot bind admin listener to 127.0.0.1:8082");
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String route = exchange.getRequestURI().getPath();
            if (!route.equals("/admin/pause") && !route.equals("/admin/resume") && !route.equals("/admin/fetch-now")) {
                respond(exchange, 404, new ErrorBody("NOT_FOUND", "Endpoint not found")); return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                respond(exchange, 405, new ErrorBody("METHOD_NOT_ALLOWED", "Use POST")); return;
            }
            try {
                if (!store.initialized()) { throw new StoreException("DATABASE"); }
                switch (route) {
                    case "/admin/pause" -> { store.pause(true); respond(exchange, 200, Map.of("paused", true)); }
                    case "/admin/resume" -> { store.pause(false); respond(exchange, 200, Map.of("paused", false)); }
                    case "/admin/fetch-now" -> {
                        var result = store.fetchNow();
                        switch (result) {
                            case ACCEPTED -> respond(exchange, 202, Map.of("accepted", true, "message", "Fetch scheduled for the next check"));
                            case PAUSED -> respond(exchange, 409, new ErrorBody("PAUSED", "Fetching is paused"));
                            case LOCKED -> respond(exchange, 409, new ErrorBody("LOCKED", "A fetch run is active"));
                        }
                    }
                    default -> throw new IllegalStateException();
                }
            } catch (RuntimeException exception) {
                respond(exchange, 503, new ErrorBody("DATABASE_UNAVAILABLE", "Database is temporarily unavailable"));
            }
        }
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] json = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, json.length);
        exchange.getResponseBody().write(json);
    }

    @Override
    public synchronized void stop() {
        if (server != null) { server.stop(0); server = null; }
        if (workers != null) { workers.shutdownNow(); workers = null; }
    }

    @Override public synchronized boolean isRunning() { return server != null; }
}
