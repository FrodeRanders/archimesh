package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.*;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;

/** Immutable checked-in definitions; listing/resolution confers no model/target permission. */
@Startup @ApplicationScoped
public class PublicationProfiles {
    @ConfigProperty(name="app.publication.profiles-enabled",defaultValue="false") boolean enabled;
    @ConfigProperty(name="app.publication.profiles-configuration") Optional<String> configuration;
    @Inject ObjectMapper json;
    private List<PublicationProfile.Definition> definitions=List.of();
    @PostConstruct void start() {
        if(!enabled) return;
        try {
            var path=Path.of(configuration.filter(v -> !v.isBlank()).orElseThrow());
            if(Files.size(path)>524288) throw new IllegalArgumentException();
            definitions=json.readerFor(PublicationProfile.Catalog.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .<PublicationProfile.Catalog>readValue(Files.readAllBytes(path)).profiles();
        } catch(Exception invalid) { throw new IllegalStateException("Invalid publication profile configuration"); }
    }
    public List<PublicationProfile.Definition> available() { return enabled ? definitions : List.of(); }
    public PublicationProfile.Definition resolve(PublicationProfile.Pin pin) {
        if(pin==null) return null;
        if(!enabled) throw new Failure(403,"PUBLICATION_PROFILES_DISABLED");
        var profile=definitions.stream().filter(p -> p.profileId().equals(pin.profileId()) && p.version().equals(pin.version())).findFirst()
            .orElseThrow(() -> new Failure(409,"PUBLICATION_PROFILE_VERSION_UNAVAILABLE"));
        if(!profile.enabled()) throw new Failure(403,"PUBLICATION_PROFILE_DISABLED");
        if(!profile.pin(json).equals(pin)) throw new Failure(409,"PUBLICATION_PROFILE_CONTENT_CHANGED");
        return profile;
    }
}
