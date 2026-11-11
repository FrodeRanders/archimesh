package org.gautelis.archimesh.integration;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.DocumentKind;

/** One bounded publication executor; Kafka only wakes a policy scan, and periodic polling repairs missed signals. */
@Startup
@ApplicationScoped
public class PublicationWorker {
    @Inject IntegrationRuntime runtime;
    @Inject ArchitecturePublicationService publication;
    @ConfigProperty(name="app.integrations.publication.worker-enabled",defaultValue="true") boolean enabled;
    @ConfigProperty(name="app.integrations.publication.interval-seconds",defaultValue="300") long interval;
    @ConfigProperty(name="kafka.bootstrap.servers",defaultValue="localhost:9092") String brokers;
    @ConfigProperty(name="kafka.topic.prefix",defaultValue="archimesh.model") String topics;
    private ScheduledExecutorService executor;
    private ExecutorService signals;
    private volatile org.apache.kafka.clients.consumer.KafkaConsumer<String,String> consumer;
    private volatile boolean stopping;
    private final Set<String> dirty=ConcurrentHashMap.newKeySet();
    private long nextScan;
    private String policyCursor="",jobCursor="";
    @PostConstruct void start() {
        if (!enabled || !runtime.enabled() || runtime.settings().targets().stream().noneMatch(t -> t.publicationAccess().enabled())) return;
        if (interval<5 || interval>86400) throw new IllegalArgumentException("Publication polling interval must be 5..86400 seconds");
        executor=Executors.newSingleThreadScheduledExecutor(r -> { var t=new Thread(r,"archimesh-publication"); t.setDaemon(true); return t; });
        signals=Executors.newSingleThreadExecutor(r -> { var t=new Thread(r,"archimesh-publication-signals"); t.setDaemon(true); return t; });
        executor.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS); signals.submit(this::consume);
    }
    void tick() {
        if (stopping) return;
        try {
            String instance=runtime.settings().instanceId(); var state=runtime.metadata();
            if (System.currentTimeMillis()>=nextScan || !dirty.isEmpty()) {
                dirty.clear();
                var policies=state.list(instance,DocumentKind.PUBLICATION_POLICY,"pub-policy:",policyCursor,25);
                for (var policy:policies) {
                    policyCursor=policy.id();
                    try { publication.schedulePolicy(policy); } catch (RuntimeException rejected) { /* One paused/unavailable model must not stop others. */ }
                }
                if (policies.size()<25) { policyCursor=""; nextScan=System.currentTimeMillis()+interval*1000; }
            }
            var jobs=state.list(instance,DocumentKind.PUBLICATION_JOB,"pub-job:",jobCursor,10);
            for (var job:jobs) {
                jobCursor=job.id(); if (stopping) break;
                publication.runJob(job.id());
            }
            if (jobs.size()<10) jobCursor="";
        } catch (RuntimeException unavailable) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Publication iteration deferred; inspect integration status and database availability");
        }
    }
    private void consume() {
        var properties=new Properties(); properties.put("bootstrap.servers",brokers);
        properties.put("group.id","archimesh-publication-"+runtime.settings().instanceId());
        properties.put("key.deserializer",StringDeserializer.class.getName()); properties.put("value.deserializer",StringDeserializer.class.getName());
        properties.put("auto.offset.reset","latest"); properties.put("enable.auto.commit","true");
        while (!stopping) {
            try (var connection=new org.apache.kafka.clients.consumer.KafkaConsumer<String,String>(properties)) {
                consumer=connection; connection.subscribe(List.of(topics+".ops"));
                while (!stopping) for (var record:connection.poll(Duration.ofSeconds(1)))
                    if (record.key()!=null && !record.key().isBlank() && record.key().length()<=512 && dirty.size()<1000) dirty.add(record.key());
            } catch (RuntimeException disconnected) {
                if (!stopping) try { Thread.sleep(1000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            } finally { consumer=null; }
        }
    }
    @PreDestroy void stop() {
        stopping=true; var connection=consumer; if (connection!=null) connection.wakeup();
        if (signals!=null) signals.shutdownNow(); if (executor!=null) executor.shutdownNow();
    }
}
