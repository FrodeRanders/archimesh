package org.gautelis.archimesh.integration.federation;

import com.fasterxml.jackson.databind.*;
import io.quarkus.runtime.Startup;
import jakarta.annotation.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.gautelis.archimesh.auth.AuthorizationSubject;
import org.gautelis.archimesh.auth.AuthorizationRoleMapper;
import org.gautelis.archimesh.integration.*;
import org.gautelis.federation.Identity.*;
import org.gautelis.federation.http.*;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;

/** Optional authenticated owner boundary; all source reads stay outside model acceptance. */
@Startup @ApplicationScoped
public class FederationRuntime {
    @ConfigProperty(name="app.federation.enabled",defaultValue="false") boolean enabled;
    @ConfigProperty(name="app.federation.configuration") Optional<String> path;
    @ConfigProperty(name="app.authz.enabled",defaultValue="false") boolean authorizationEnabled;
    @Inject IntegrationRuntime integration;
    @Inject ObjectMapper json;
    @Inject AuthorizationRoleMapper roleMapper;
    private FederationConfiguration configuration;
    private ReaderDispatch readers;
    private final List<DataHubReader> owned=new ArrayList<>();
    @PostConstruct void start() {
        if (!enabled) return;
        try {
            if (!authorizationEnabled || !integration.enabled()) throw new IllegalArgumentException();
            Path file=Path.of(path.filter(value -> !value.isBlank()).orElseThrow());
            if (Files.size(file)>1_048_576) throw new IllegalArgumentException();
            configuration=json.readerFor(FederationConfiguration.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(Files.readAllBytes(file));
            if (!configuration.ownerInstanceId().equals(integration.settings().instanceId())) throw new IllegalArgumentException();
            if (configuration.grants().stream().anyMatch(grant -> !roleMapper.federationRoles().contains(grant.requiredRole()))) throw new IllegalArgumentException();
            var map=new HashMap<Repository,SourceReader>();
            for (var source:configuration.sources()) {
                if (source.repository().system().equals("datahub")) {
                    String token=System.getenv(source.tokenEnvironment()); if (token==null || token.isBlank()) throw new IllegalArgumentException();
                    var reader=new DataHubReader(source.repository(),source.baseUrl(),() -> System.getenv(source.tokenEnvironment()),Duration.ofMillis(source.timeoutMillis()),source.maxResponseBytes(),source.approvedPlainHttp());
                    owned.add(reader); map.put(source.repository(),reader);
                } else {
                    var target=integration.settings().targets().stream().filter(value -> value.key().equals(source.turboTargetKey())).findFirst().orElseThrow();
                    if (!target.instanceId().equals(source.repository().instance())) throw new IllegalArgumentException();
                    map.put(source.repository(),new TurboEaSourceReader(source.repository(),integration.connector(target.key()),json));
                }
            }
            readers=new ReaderDispatch(map);
        } catch (Exception invalid) { stop(); throw new IllegalStateException("Invalid federation source configuration, owner or credentials"); }
    }
    public boolean enabled() { return enabled; }
    public FederationConfiguration settings() { if (!enabled || configuration==null) throw new Failure(404,"FEDERATION_DISABLED"); return configuration; }
    public FederationConfiguration.Source source(String key) { return settings().sources().stream().filter(source -> source.key().equals(key)).findFirst().orElseThrow(() -> new Failure(403,"FEDERATION_SOURCE_DENIED")); }
    public FederationConfiguration.Type type(FederationConfiguration.Source source,String type) {
        var result=source.types().get(type); if (result==null) throw new Failure(403,"FEDERATION_TYPE_DENIED"); return result;
    }
    public void authorize(AuthorizationSubject subject,FederationConfiguration.Operation operation,String key,String nativeId) {
        if (subject==null || subject.userId()==null || subject.roles()==null) throw new Failure(403,"FEDERATION_PRINCIPAL_REQUIRED");
        var grant=settings().grants().stream().filter(value -> value.principal().equals(subject.userId())).findFirst().orElseThrow(() -> new Failure(403,"FEDERATION_PRINCIPAL_REQUIRED"));
        if (!subject.roles().contains(grant.requiredRole()) || !grant.operations().contains(operation) || !grant.resources().getOrDefault(key,Set.of()).contains(nativeId)) throw new Failure(403,"FEDERATION_SCOPE_DENIED");
    }
    public SourceReader.Snapshot read(FederationConfiguration.Source source,Representation representation) {
        return readers.read(representation,Set.copyOf(type(source,representation.externalType()).fields().values()));
    }
    public String digest() { return PublicationJson.digest(json.valueToTree(settings())); }
    @PreDestroy void stop() { owned.forEach(DataHubReader::close); owned.clear(); }
}
