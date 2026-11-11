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
    private void requireEnabled() { if (!enabled) throw new IllegalStateException("External integration is disabled"); }
    @PreDestroy synchronized void stop() {
        clients.values().forEach(TurboEaClient::close); clients.clear();
        if (repository != null) { repository.close(); repository=null; }
    }
}
