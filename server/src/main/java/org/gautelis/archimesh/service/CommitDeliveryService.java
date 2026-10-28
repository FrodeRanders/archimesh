package org.gautelis.archimesh.service;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.enterprise.event.Observes;
import io.quarkus.runtime.ShutdownEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Ordered, at-least-once delivery of publication intents committed with the model. */
@ApplicationScoped
public class CommitDeliveryService {
    private static final Logger LOG = LoggerFactory.getLogger(CommitDeliveryService.class);
    @Inject Neo4jRepository repository;
    @Inject KafkaPublisher publisher;
    @ConfigProperty(name="app.delivery.concurrent-models",defaultValue="4")
    int concurrentModels = 4;
    @ConfigProperty(name="app.delivery.batches-per-model",defaultValue="25")
    int batchesPerModel = 25;

    private static final int MODELS_PER_SCAN = 100;
    private static final long RETRY_DELAY_NANOS = TimeUnit.SECONDS.toNanos(1);
    private ScheduledExecutorService executor;
    ExecutorService workers;
    LongSupplier nanoTime = System::nanoTime;
    private final String owner = java.util.UUID.randomUUID().toString();
    private volatile boolean leaseValid;
    private boolean stopping;
    private final Set<String> activeModels = ConcurrentHashMap.newKeySet();
    private final Map<String,Long> deferredModels = new ConcurrentHashMap<>();
    private String lastModelId;
    private final java.util.concurrent.atomic.AtomicBoolean draining = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean drainQueued = new java.util.concurrent.atomic.AtomicBoolean();

    public synchronized void start() {
        if (executor != null) return;
        if (concurrentModels < 1 || batchesPerModel < 1) throw new IllegalArgumentException("Delivery limits must be positive");
        leaseValid = repository.acquireServerLease(owner, 30_000);
        if (!leaseValid) throw new IllegalStateException("Another Archimesh server owns this repository; only one authoritative server is supported");
        workers = Executors.newFixedThreadPool(concurrentModels, r -> {
            Thread thread = new Thread(r,"archimesh-model-delivery");
            thread.setDaemon(true);
            return thread;
        });
        executor = Executors.newScheduledThreadPool(2, r -> {
            Thread thread = new Thread(r, "archimesh-commit-delivery");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::renewLease, 5, 5, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(this::drain, 1, 1, TimeUnit.SECONDS);
    }

    public synchronized void requestDrain() {
        // Coalesce submit bursts instead of queueing one scan per accepted batch.
        if (executor == null || executor.isShutdown() || !drainQueued.compareAndSet(false,true)) return;
        try {
            executor.execute(() -> {
                try { drain(); }
                finally { drainQueued.set(false); }
            });
        } catch (RejectedExecutionException stopped) {
            drainQueued.set(false);
        }
    }

    public void requireWriterLease() {
        if (!leaseValid) throw new IllegalStateException("This server does not hold the authoritative writer lease");
    }

    void renewLease() {
        boolean renewed;
        try { renewed = repository.acquireServerLease(owner,30_000); }
        catch (Exception failure) { renewed = false; LOG.warn("Writer lease renewal failed",failure); }
        synchronized (this) {
            // An already-running renewal must not reactivate delivery after shutdown.
            if (!stopping) leaseValid = renewed;
        }
    }

    void drain() {
        if (!leaseValid || workers == null || !draining.compareAndSet(false, true)) return;
        try {
            if (activeModels.size() >= concurrentModels) return;
            long now = nanoTime.getAsLong();
            deferredModels.entrySet().removeIf(entry -> now - entry.getValue() >= 0);
            var models = repository.pendingBroadcastModels(lastModelId,MODELS_PER_SCAN);
            if (models.isEmpty() && lastModelId != null) models = repository.pendingBroadcastModels(null,MODELS_PER_SCAN);
            for (String modelId : models) {
                if (!leaseValid) return;
                if (activeModels.size() >= concurrentModels) break;
                lastModelId = modelId;
                if (deferredModels.containsKey(modelId) || !activeModels.add(modelId)) continue;
                try { workers.execute(() -> drainModel(modelId)); }
                catch (RejectedExecutionException stopped) { activeModels.remove(modelId); }
            }
        } catch (Exception failure) {
            if (leaseValid) LOG.warn("Pending model scan deferred; durable delivery will retry", failure);
        } finally {
            draining.set(false);
        }
    }

    private void drainModel(String modelId) {
        try {
            if (!leaseValid) return;
            for (var batch : repository.pendingBroadcasts(modelId,batchesPerModel)) {
                if (!leaseValid || Thread.currentThread().isInterrupted()) return;
                // Do not time out and release this model while its broker send is still in flight.
                publisher.publishOps(modelId,batch).get();
                if (!leaseValid) return;
                repository.markBroadcastPublished(modelId,batch.path("opBatchId").asText());
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            // Retain this batch and its successors; independent models keep their own workers.
            deferredModels.put(modelId,nanoTime.getAsLong()+RETRY_DELAY_NANOS);
            if (leaseValid) LOG.warn("Commit publication deferred for model {}; durable delivery will retry",modelId,failure);
        } finally {
            activeModels.remove(modelId);
        }
    }

    @PreDestroy
    synchronized void stop() {
        stopping = true;
        leaseValid = false;
        if (executor != null) executor.shutdownNow();
        if (workers != null) workers.shutdownNow();
        // The repository releases its lease before closing the driver, independent of CDI destruction order.
    }

    void onShutdown(@Observes ShutdownEvent event) {
        stop();
    }
}
