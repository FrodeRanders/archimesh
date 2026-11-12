package org.gautelis.archimesh.integration;

import io.quarkus.runtime.Startup;
import jakarta.annotation.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.concurrent.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.DocumentKind;

@Startup
@ApplicationScoped
public class CentralEditWorker {
    @Inject IntegrationRuntime runtime;
    @Inject CentralEditService edits;
    @ConfigProperty(name="app.integrations.central-edits.worker-enabled",defaultValue="true") boolean enabled;
    private ScheduledExecutorService worker;
    private String cursor="";
    @PostConstruct void start() {
        if (!enabled || !runtime.enabled() || runtime.settings().targets().stream().noneMatch(t -> t.centralEditAccess().enabled())) return;
        worker=Executors.newSingleThreadScheduledExecutor(r -> { var t=new Thread(r,"archimesh-central-edits"); t.setDaemon(true); return t; });
        worker.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS);
    }
    void tick() {
        try {
            var page=runtime.metadata().list(runtime.settings().instanceId(),DocumentKind.CENTRAL_EDIT,"central:",cursor,10);
            for (var job:page) { edits.run(job.id()); cursor=job.id(); }
            if (page.size()<10) cursor="";
        } catch (RuntimeException unavailable) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Central edit iteration deferred; inspect integration metadata availability"); }
    }
    @PreDestroy void stop() { if (worker!=null) worker.shutdownNow(); }
}
