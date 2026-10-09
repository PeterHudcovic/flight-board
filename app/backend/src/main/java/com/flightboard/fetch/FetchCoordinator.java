package com.flightboard.fetch;

import com.flightboard.config.FlightBoardProperties;
import com.flightboard.persistence.FetchStore;
import com.flightboard.persistence.StoreException;
import com.flightboard.source.FlightSource;
import com.flightboard.source.SourceException;
import com.flightboard.validation.BatchValidationException;
import com.flightboard.validation.ValidatedBatch;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FetchCoordinator implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(FetchCoordinator.class);
    private final FetchStore store;
    private final FlightSource source;
    private final BatchProcessor processor;
    private final FlightBoardProperties properties;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean checking = new AtomicBoolean();

    public FetchCoordinator(FetchStore store, FlightSource source, BatchProcessor processor, FlightBoardProperties properties) {
        this.store = store;
        this.source = source;
        this.processor = processor;
        this.properties = properties;
    }

    public void checkAsync() {
        workers.execute(this::runOnce);
    }

    /** Returns by the total run deadline even if a faulty processor ignores interruption. */
    public FetchResult runOnce() {
        String runId = UUID.randomUUID().toString();
        var deadline = new RunDeadline(properties.fetch().runTimeout());
        var future = workers.submit(() -> execute(runId, deadline));
        try {
            return future.get(deadline.remaining().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException | RunDeadline.RunTimeoutException exception) {
            future.cancel(true);
            LOG.warn("Run {} exceeded its total time limit", runId);
            return new FetchResult(runId, FetchResult.State.TIMEOUT, "RUN_TIMEOUT");
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            LOG.warn("Run {} was interrupted", runId);
            return new FetchResult(runId, FetchResult.State.ERROR, "INTERRUPTED");
        } catch (ExecutionException exception) {
            LOG.warn("Run {} failed unexpectedly", runId);
            return new FetchResult(runId, FetchResult.State.ERROR, "INTERNAL");
        }
    }

    private FetchResult execute(String runId, RunDeadline deadline) {
        if (!checking.compareAndSet(false, true)) {
            return new FetchResult(null, FetchResult.State.SKIPPED, "LOCAL_RUN_ACTIVE");
        }
        RunClaim claim = null;
        String code = "INTERNAL";
        String reason = "Run failed";
        Integer status = null;
        Integer index = null;
        boolean completed = false;
        try {
            deadline.check();
            claim = store.claim(runId, deadline).orElse(null);
            if (claim == null) { return new FetchResult(null, FetchResult.State.SKIPPED, "NOT_DUE_OR_LOCKED_OR_PAUSED"); }
            LOG.info("Run {} claimed sequence {}", runId, claim.fetchSeq());
            deadline.check();
            String json = source.fetch(deadline.remaining());
            status = properties.source() == FlightBoardProperties.SourceMode.AERODATABOX ? 200 : null;
            deadline.check();
            ValidatedBatch batch = processor.validate(json, runId);
            deadline.check();
            if (store.publish(claim, batch, deadline)) {
                LOG.info("Run {} published {} board flights from {} source flights", runId, batch.flights().size(), batch.sourceFlightCount());
                completed = true;
                return new FetchResult(runId, FetchResult.State.SUCCESS, null);
            }
            code = "PUBLICATION_REJECTED";
            reason = "Publication conditions were not met";
        } catch (RunDeadline.RunTimeoutException exception) {
            code = "RUN_TIMEOUT";
            reason = "Run time limit exceeded";
        } catch (SourceException exception) {
            code = deadline.expired() ? "RUN_TIMEOUT" : "SOURCE_" + exception.kind().name();
            reason = deadline.expired() ? "Run time limit exceeded" : exception.getMessage();
            status = exception.httpStatus();
        } catch (BatchValidationException exception) {
            code = "INVALID_BATCH";
            reason = exception.getMessage();
            index = exception.departureIndex();
        } catch (StoreException exception) {
            code = deadline.expired() ? "RUN_TIMEOUT" : exception.code();
            reason = deadline.expired() ? "Run time limit exceeded" : exception.getMessage();
        } catch (RuntimeException exception) {
            // External exceptions can contain source bodies or credentials: never echo them.
            code = "INTERNAL";
            reason = "Run failed";
        } finally {
            // Keep the local guard held until the worker's failure cleanup has finished.
            // Successful and skipped paths need no cleanup; failed paths continue below.
            if (claim == null || completed) { checking.set(false); }
        }
        LOG.warn("Run {} failed with {}", runId, code);
        Thread.interrupted(); // Allow best-effort DB cleanup after cancellation.
        try { if (claim != null) { store.fail(claim, code, reason, status, index); } }
        catch (RuntimeException exception) { LOG.warn("Run {} failure bookkeeping unavailable", runId); }
        finally { checking.set(false); }
        return new FetchResult(runId, "RUN_TIMEOUT".equals(code) ? FetchResult.State.TIMEOUT : FetchResult.State.ERROR, code);
    }

    @Override
    public void close() { workers.shutdownNow(); }

    @FunctionalInterface
    public interface BatchProcessor {
        ValidatedBatch validate(String json, String runId) throws BatchValidationException;
    }
}
