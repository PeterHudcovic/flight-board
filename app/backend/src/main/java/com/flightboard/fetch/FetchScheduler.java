package com.flightboard.fetch;

import com.flightboard.config.FlightBoardProperties;
import com.flightboard.persistence.MongoFetchStore;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public final class FetchScheduler implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(FetchScheduler.class);
    private final FetchCoordinator coordinator;
    private final MongoFetchStore store;
    private final FlightBoardProperties properties;
    private ScheduledExecutorService scheduler;

    public FetchScheduler(FetchCoordinator coordinator, MongoFetchStore store, FlightBoardProperties properties) {
        this.coordinator = coordinator;
        this.store = store;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) { return; }
        scheduler = Executors.newScheduledThreadPool(2);
        scheduler.scheduleWithFixedDelay(this::check, 0,
                Math.max(1, properties.fetch().checkInterval().toMillis()), TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::cleanup, TimeUnit.MINUTES.toMillis(1),
                TimeUnit.DAYS.toMillis(1), TimeUnit.MILLISECONDS);
    }

    private void check() {
        try {
            if (!store.initialized()) {
                store.initialize();
                // Also clean up after delayed DB recovery, before the first source request.
                cleanup();
            }
            coordinator.checkAsync();
        } catch (RuntimeException exception) {
            LOG.warn("Database initialization/check unavailable; retrying at the next check");
        }
    }

    private void cleanup() {
        if (!store.initialized()) { return; }
        try { store.deleteExpiredBoard(); }
        catch (RuntimeException exception) { LOG.warn("Expired-board cleanup unavailable"); }
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
    }

    @Override
    public synchronized boolean isRunning() { return scheduler != null; }
}
