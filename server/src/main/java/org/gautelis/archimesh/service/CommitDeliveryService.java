package org.gautelis.archimesh.service;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.enterprise.event.Observes;
import io.quarkus.runtime.ShutdownEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Ordered, at-least-once delivery of publication intents committed with the model. */
@ApplicationScoped
public class CommitDeliveryService {
    private static final Logger LOG = LoggerFactory.getLogger(CommitDeliveryService.class);
    @Inject Neo4jRepository repository;
    @Inject KafkaPublisher publisher;
    private ScheduledExecutorService executor;
    private final String owner = java.util.UUID.randomUUID().toString();
    private volatile boolean leaseValid;
    private final java.util.concurrent.atomic.AtomicBoolean draining = new java.util.concurrent.atomic.AtomicBoolean();

    public synchronized void start() {
        if (executor != null) return;
        leaseValid = repository.acquireServerLease(owner, 30_000);
        if (!leaseValid) throw new IllegalStateException("Another Archimesh server owns this repository; only one authoritative server is supported");
        executor = Executors.newScheduledThreadPool(2, r -> {
            Thread thread = new Thread(r, "archimesh-commit-delivery");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> {
            try { leaseValid = repository.acquireServerLease(owner, 30_000); }
            catch (Exception failure) { leaseValid = false; LOG.warn("Writer lease renewal failed", failure); }
        }, 5, 5, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(this::drain, 1, 1, TimeUnit.SECONDS);
    }

    public synchronized void requestDrain() {
        if (executor != null) executor.execute(this::drain);
    }

    public void requireWriterLease() {
        if (!leaseValid) throw new IllegalStateException("This server does not hold the authoritative writer lease");
    }

    void drain() {
        if (!leaseValid || !draining.compareAndSet(false, true)) return;
        try {
            for (var batch : repository.pendingBroadcasts()) {
                if (!leaseValid) return;
                String modelId = batch.path("modelId").asText();
                publisher.publishOps(modelId, batch).join();
                if (!leaseValid) return;
                repository.markBroadcastPublished(modelId, batch.path("opBatchId").asText());
            }
        } catch (Exception failure) {
            // Leave the intent pending. A retry/restart may duplicate a broadcast, never lose it.
            if (leaseValid) LOG.warn("Commit publication deferred; durable delivery will retry", failure);
        } finally {
            draining.set(false);
        }
    }

    @PreDestroy
    void stop() {
        leaseValid = false;
        if (executor != null) executor.shutdownNow();
        // The repository releases its lease before closing the driver, independent of CDI destruction order.
    }

    void onShutdown(@Observes ShutdownEvent event) {
        stop();
    }
}
