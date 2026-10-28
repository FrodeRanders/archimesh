package org.gautelis.archimesh.service;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Opt-in maintenance; the durable model state makes each bounded pass restartable. */
@Startup
@ApplicationScoped
public class CheckpointMaintenanceService {
    private static final Logger LOG = LoggerFactory.getLogger(CheckpointMaintenanceService.class);
    @Inject ArchimeshService service;
    @Inject Neo4jRepository repository;
    @Inject CommitDeliveryService delivery;

    @ConfigProperty(name="app.checkpoints.maintenance.enabled", defaultValue="false")
    boolean enabled;
    @ConfigProperty(name="app.checkpoints.maintenance.interval-seconds", defaultValue="300")
    long intervalSeconds = 300;
    @ConfigProperty(name="app.checkpoints.maintenance.min-revisions", defaultValue="1000")
    long minRevisions = 1000;
    @ConfigProperty(name="app.checkpoints.maintenance.retain-checkpoints", defaultValue="2")
    int retainCheckpoints = 2;
    @ConfigProperty(name="app.checkpoints.maintenance.models-per-run", defaultValue="10")
    int modelsPerRun = 10;

    private ScheduledExecutorService executor;
    private String lastModelId;

    @PostConstruct
    void start() {
        if (!enabled) return;
        if (intervalSeconds < 1 || minRevisions < 1 || retainCheckpoints < 1 || modelsPerRun < 1) {
            throw new IllegalArgumentException("Checkpoint maintenance policy values must be positive");
        }
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task,"archimesh-checkpoint-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::runOnce,intervalSeconds,intervalSeconds,TimeUnit.SECONDS);
        LOG.info("Checkpoint maintenance enabled: interval={}s minRevisions={} retainCheckpoints={} modelsPerRun={}",
                intervalSeconds,minRevisions,retainCheckpoints,modelsPerRun);
    }

    void runOnce() {
        if (!enabled || Thread.currentThread().isInterrupted()) return;
        try {
            delivery.requireWriterLease();
            var models = repository.listModelCatalog().stream().map(model -> model.modelId()).sorted().toList();
            if (models.isEmpty()) return;
            int start = 0;
            if (lastModelId != null) {
                while (start < models.size() && models.get(start).compareTo(lastModelId) <= 0) start++;
                if (start == models.size()) start = 0;
            }
            for (int offset = 0; offset < Math.min(modelsPerRun,models.size()); offset++) {
                if (Thread.currentThread().isInterrupted()) return;
                delivery.requireWriterLease();
                String id = models.get((start+offset)%models.size());
                try {
                    var result = service.maintainCheckpoints(id,minRevisions,retainCheckpoints);
                    if (result.checkpointCreated() || result.prunedBatchCount() > 0 || result.deletedCheckpointCount() > 0) {
                        LOG.info("Checkpoint maintenance: {}",result);
                    }
                } catch (Exception failure) {
                    // A damaged or deleted model must not starve maintenance for other models.
                    LOG.warn("Checkpoint maintenance deferred for model {}",id,failure);
                }
                lastModelId = id;
            }
        } catch (Exception failure) {
            // Catch here so a transient database/lease failure does not cancel future scheduled runs.
            LOG.warn("Checkpoint maintenance pass deferred",failure);
        }
    }

    @PreDestroy
    void stop() {
        if (executor != null) executor.shutdownNow();
    }

    void onShutdown(@Observes ShutdownEvent event) { stop(); }
}
