package org.gautelis.archimesh.integration;

import io.quarkus.runtime.Startup;
import jakarta.annotation.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.concurrent.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.DocumentKind;

@Startup @ApplicationScoped
public class IntegrationOperationsWorker {
    @Inject IntegrationRuntime runtime;
    @Inject IntegrationOperationsService operations;
    @ConfigProperty(name="app.integrations.operations.worker-enabled",defaultValue="true") boolean enabled;
    private ScheduledExecutorService executor;
    private String cursor="";
    @PostConstruct void start() {
        if (!enabled || !runtime.enabled() || runtime.settings().operators().isEmpty()) return;
        executor=Executors.newSingleThreadScheduledExecutor(r -> { var t=new Thread(r,"archimesh-integration-operations"); t.setDaemon(true); return t; });
        executor.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS);
    }
    void tick() {
        try {
            runtime.requireRunning();
            var page=runtime.metadata().list(runtime.settings().instanceId(),DocumentKind.RECONCILIATION,"scan:",cursor,10);
            for (var job:page) { operations.runScan(job.id()); cursor=job.id(); }
            if (page.size()<10) cursor="";
        } catch (RuntimeException unavailable) { /* Durable jobs resume after pause, restart or infrastructure recovery. */ }
    }
    @PreDestroy void stop() { if (executor!=null) executor.shutdownNow(); }
}
