package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Startup;
import jakarta.annotation.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.gautelis.archimesh.integration.turboea.TurboEaSignals;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.*;

/** Poll every bound occurrence, including missing/paused policies. SSE only advances the next bounded scan. */
@Startup
@ApplicationScoped
public class InboundWorker {
    @Inject IntegrationRuntime runtime;
    @Inject RemoteMutationCoordinator coordination;
    @Inject ExternalChangeIngestService inbound;
    @Inject ObjectMapper json;
    @ConfigProperty(name="app.integrations.inbound.worker-enabled",defaultValue="true") boolean enabled;
    @ConfigProperty(name="app.integrations.inbound.interval-seconds",defaultValue="300") long interval;
    private ScheduledExecutorService polls;
    private ExecutorService listeners;
    private final Set<String> dirty=ConcurrentHashMap.newKeySet();
    private final Map<String,String> cursors=new HashMap<>();
    private final Map<String,Long> due=new HashMap<>();
    private final Map<String,TurboEaSignals> streams=new ConcurrentHashMap<>();
    private volatile boolean stopping;
    @PostConstruct void start() {
        if (!enabled || !runtime.enabled() || runtime.settings().targets().stream().noneMatch(t -> t.inboundAccess().enabled())) return;
        if (interval<5 || interval>86400) throw new IllegalArgumentException("Inbound interval must be 5..86400 seconds");
        polls=Executors.newSingleThreadScheduledExecutor(r -> { var t=new Thread(r,"archimesh-inbound"); t.setDaemon(true); return t; });
        listeners=Executors.newCachedThreadPool(r -> { var t=new Thread(r,"archimesh-inbound-sse"); t.setDaemon(true); return t; });
        for (var target:runtime.settings().targets()) if (target.inboundAccess().enabled() && target.inboundAccess().sseEnabled()) listeners.submit(() -> listen(target));
        polls.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS);
    }
    void tick() {
        if (stopping) return;
        try { runtime.requireRunning(); } catch (RuntimeException paused) { return; }
        for (var target:runtime.settings().targets()) if (target.inboundAccess().enabled()) {
            String key=target.key();
            if (System.currentTimeMillis()<due.getOrDefault(key,0L) && !dirty.remove(key)) continue;
            try {
                var page=runtime.metadata().bindings(runtime.settings().instanceId(),target.instanceId(),null,cursors.getOrDefault(key,""),10);
                for (var binding:page) { if (stopping) return; inbound.reconcile(key,binding,false); cursors.put(key,ExternalChangeIngestService.cursor(binding)); }
                if (page.size()<10) { cursors.put(key,""); due.put(key,System.currentTimeMillis()+interval*1000); dirty.remove(key); }
            } catch (RuntimeException unavailable) {
                due.put(key,System.currentTimeMillis()+5000);
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Inbound scan deferred; inspect integration metadata and database availability");
            }
        }
    }
    private void listen(IntegrationConfiguration.Target target) {
        int attempts=0;
        try (var stream=new TurboEaSignals(target.baseUrl(),() -> System.getenv(target.tokenEnvironment()),json,Duration.ofMillis(target.timeoutMillis()))) {
            streams.put(target.key(),stream);
            while (!stopping) {
                try {
                    signalState(target,"LISTENING",null);
                    stream.listen(id -> dirty.add(target.key()),Duration.ofSeconds(30)); attempts=0;
                    if (!stopping) Thread.sleep(1000);
                } catch (ConnectorException failure) {
                    attempts++; boolean retry=failure.retryable() && attempts<4;
                    signalState(target,retry ? "RETRY" : "POLL_ONLY","EXTERNAL_"+failure.kind());
                    if (!retry) return;
                    Thread.sleep(Math.max(failure.retryAfter().toMillis(),(1L<<attempts)*1000+ThreadLocalRandom.current().nextLong(1000)));
                }
            }
        } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        catch (RuntimeException unavailable) { /* Durable polling is independent of SSE and remains active. */ }
        finally { streams.remove(target.key()); }
    }
    private void signalState(IntegrationConfiguration.Target target,String status,String code) {
        if (!coordination.tryLock()) return;
        try {
            runtime.requireRunning();
            String id="in-signal:"+target.key(); var state=runtime.metadata();
            var prior=state.load(runtime.settings().instanceId(),DocumentKind.CHECKPOINT,id);
            var data=json.createObjectNode().put("state",status).put("targetInstanceId",target.instanceId()).put("updatedAt",System.currentTimeMillis());
            if (code!=null) data.put("code",code);
            state.save(new StateDocument(runtime.settings().instanceId(),DocumentKind.CHECKPOINT,id,prior.map(StateDocument::version).orElse(0L),data));
        } catch (PublicationModel.Failure paused) { /* Polling and signal callbacks remain harmless while paused. */ }
        finally { coordination.unlock(); }
    }
    @PreDestroy void stop() {
        stopping=true;
        if (polls!=null) polls.shutdownNow(); if (listeners!=null) listeners.shutdownNow(); streams.values().forEach(TurboEaSignals::close);
    }
}
