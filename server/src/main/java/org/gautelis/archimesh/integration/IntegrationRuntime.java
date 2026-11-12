package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.gautelis.archimesh.integration.turboea.TurboEaClient;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** No remote calls or model hooks at startup; disabled mode allocates no client or database driver. */
@Startup
@ApplicationScoped
public class IntegrationRuntime {
    @ConfigProperty(name="app.integrations.enabled",defaultValue="false") boolean enabled;
    @ConfigProperty(name="app.integrations.configuration") Optional<String> configuration;
    @ConfigProperty(name="app.neo4j.uri") String neo4jUri;
    @ConfigProperty(name="app.neo4j.username") String neo4jUser;
    @ConfigProperty(name="app.neo4j.password") String neo4jPassword;
    @Inject ObjectMapper json;
    @Inject RemoteMutationCoordinator coordination=new RemoteMutationCoordinator();

    private IntegrationConfiguration settings;
    private final Map<String,TurboEaClient> clients = new HashMap<>();
    private Neo4jIntegrationStateRepository repository;

    @PostConstruct void start() {
        if (!enabled) return;
        try {
            Path path = Path.of(configuration.filter(value -> !value.isBlank()).orElseThrow());
            if (Files.size(path) > 1_048_576) throw new IllegalArgumentException();
            settings = json.readerFor(IntegrationConfiguration.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(Files.readAllBytes(path));
            for (var target : settings.targets()) {
                if (System.getenv(target.tokenEnvironment()) == null || System.getenv(target.tokenEnvironment()).isBlank())
                    throw new IllegalArgumentException();
                clients.put(target.key(), new TurboEaClient(target.baseUrl(),target.expectedApiVersion(),
                        () -> System.getenv(target.tokenEnvironment()),Duration.ofMillis(target.timeoutMillis()),target.maxResponseBytes(),json));
            }
        } catch (Exception invalid) {
            stop();
            // Parser errors may contain values; never attach/log the original exception.
            throw new IllegalStateException("Invalid integration configuration or missing server token environment variable");
        }
    }

    public boolean enabled() { return enabled; }
    public IntegrationConfiguration settings() { requireEnabled(); return settings; }
    public ExternalRepositoryConnector connector(String targetKey) {
        requireEnabled();
        var client = clients.get(targetKey);
        if (client == null) throw new IllegalArgumentException("Unknown integration target");
        return client;
    }
    public ExternalRepositoryWriter writer(String targetKey) {
        requireEnabled();
        var target=settings.targets().stream().filter(t -> t.key().equals(targetKey)).findFirst().orElseThrow();
        if (!target.publicationAccess().enabled()) throw new IllegalStateException("Publication is disabled for this target");
        return clients.get(targetKey);
    }
    public ExternalRepositoryWriter centralWriter(String targetKey) {
        requireEnabled();
        var target=settings.targets().stream().filter(t -> t.key().equals(targetKey)).findFirst().orElseThrow();
        if (!target.centralEditAccess().enabled()) throw new IllegalStateException("Central edits are disabled");
        return clients.get(targetKey);
    }
    /** Explicit inspection; callers must run this before using a target's configured mapping. */
    public ExternalRepositoryConnector.Metamodel validateTarget(String targetKey) {
        var connector = connector(targetKey);
        connector.healthVersion();
        var metamodel = connector.metamodel();
        settings.targets().stream().filter(target -> target.key().equals(targetKey)).findFirst().orElseThrow()
                .mapping().validate(metamodel);
        return metamodel;
    }
    /** No network startup dependency: schema/driver are opened only when metadata is first used. */
    public synchronized IntegrationStateRepository metadata() {
        requireEnabled();
        if (repository == null) {
            var driver = GraphDatabase.driver(neo4jUri,AuthTokens.basic(neo4jUser,neo4jPassword));
            var candidate = new Neo4jIntegrationStateRepository(driver,json,true);
            try { candidate.installSchema(); repository=candidate; }
            catch (RuntimeException failure) { candidate.close(); throw failure; }
        }
        return repository;
    }
    public void attachScope(String model,String generation,String snapshotDigest,long scopeVersion,com.fasterxml.jackson.databind.JsonNode snapshot,com.fasterxml.jackson.databind.JsonNode migrations) {
        if (!metadata().modelGeneration(model).equals(generation) || !PublicationJson.digest(snapshot).equals(snapshotDigest)) throw new PublicationModel.Failure(409,"ATTACHMENT_LOCAL_CHANGED");
        var scope=modelScope(model);
        if (scope.version()!=scopeVersion) throw new PublicationModel.Failure(409,"ATTACHMENT_REVIEW_STALE");
        if (!migrations.isEmpty()) {
            if (!metadata().load(settings().instanceId(),IntegrationStateRepository.DocumentKind.OPERATIONS,"control").map(d -> d.data().path("paused").asBoolean()).orElse(false)) throw new PublicationModel.Failure(409,"MIGRATION_REQUIRES_PAUSE");
            metadata().migrateMappings(settings().instanceId(),model,migrations);
        }
        String cursor="";
        do {
            var page=metadata().list(settings().instanceId(),IntegrationStateRepository.DocumentKind.INBOUND_STATE,"",cursor,100);
            for (var state:page) if (model.equals(state.data().path("sourceModelId").asText())) {
                var d=(com.fasterxml.jackson.databind.node.ObjectNode)state.data(); d.remove(java.util.List.of("pending","conflict","code","lastAcceptedBatch"));
                settings().targets().stream().filter(t -> t.key().equals(d.path("targetKey").asText())).findFirst().ifPresent(t -> d.put("mappingVersion",t.mapping().version()));
                d.putObject("baseline"); d.put("state","PAUSED").put("modelGeneration",generation).put("attempts",0).put("nextAttemptAt",0).put("code","REATTACHED_BASELINE_REVIEW_REQUIRED");
                metadata().save(new IntegrationStateRepository.StateDocument(state.instanceId(),state.kind(),state.id(),state.version(),d));
            }
            if (page.size()<100) break; cursor=page.getLast().id();
        } while (true);
        var d=(com.fasterxml.jackson.databind.node.ObjectNode)scope.data(); d.put("generation",generation).put("paused",false).put("updatedAt",System.currentTimeMillis());
        metadata().save(new IntegrationStateRepository.StateDocument(scope.instanceId(),scope.kind(),scope.id(),scopeVersion,d));
    }
    public IntegrationStateRepository.StateDocument setPause(boolean paused,long expectedVersion,String actor) {
        var data=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("paused",paused).put("updatedAt",System.currentTimeMillis()).put("updatedBy",actor);
        return metadata().save(new IntegrationStateRepository.StateDocument(settings().instanceId(),IntegrationStateRepository.DocumentKind.OPERATIONS,"control",expectedVersion,data));
    }
    /** Durable administrative pause is independent of feature disablement and ownership guards. */
    public void requireRunning() {
        requireEnabled();
        if (metadata().load(settings().instanceId(),IntegrationStateRepository.DocumentKind.OPERATIONS,"control").map(d -> d.data().path("paused").asBoolean()).orElse(false))
            throw new PublicationModel.Failure(409,"INTEGRATION_PAUSED");
    }
    public IntegrationStateRepository.StateDocument modelScope(String model) {
        String instance=settings().instanceId(), id=PublicationJson.key("model-scope:",model);
        var existing=metadata().load(instance,IntegrationStateRepository.DocumentKind.MODEL_SCOPE,id);
        if (existing.isPresent()) return existing.get();
        if (!coordination.tryLock()) throw new PublicationModel.Failure(409,"INTEGRATION_BUSY");
        try {
        var data=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("sourceModelId",model)
                .put("generation",metadata().modelGeneration(model)).put("paused",metadata().hasModelMetadata(instance,model));
        try { return metadata().save(new IntegrationStateRepository.StateDocument(instance,IntegrationStateRepository.DocumentKind.MODEL_SCOPE,id,0,data)); }
        catch (IllegalStateException race) { return metadata().load(instance,IntegrationStateRepository.DocumentKind.MODEL_SCOPE,id).orElseThrow(() -> race); }
        } finally { coordination.unlock(); }
    }
    public String requireModelActive(String model) {
        requireRunning(); var scope=modelScope(model); String current=metadata().modelGeneration(model);
        if (scope.data().path("paused").asBoolean() || !scope.data().path("generation").asText().equals(current)) throw new PublicationModel.Failure(409,"MODEL_REATTACHMENT_REQUIRED");
        return current;
    }
    public void requireGeneration(String model,String generation) {
        if (!requireModelActive(model).equals(generation)) throw new PublicationModel.Failure(409,"MODEL_GENERATION_CHANGED");
    }
    private void requireEnabled() { if (!enabled()) throw new IllegalStateException("External integration is disabled"); }
    @PreDestroy synchronized void stop() {
        clients.values().forEach(TurboEaClient::close); clients.clear();
        if (repository != null) { repository.close(); repository=null; }
    }
}
