package org.gautelis.archimesh.integration.federation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;
import org.gautelis.archimesh.integration.*;
import org.gautelis.archimesh.integration.IntegrationIdentity.ReservedImport;
import org.gautelis.federation.Identity.*;

/** Database/config-only local protection. No HTTP under model serialization. */
@ApplicationScoped
public class FederatedFieldPolicy {
    @Inject FederationRuntime federation;
    @Inject IntegrationRuntime integration;
    public Optional<Set<String>> protectedKeys(ReservedImport binding) {
        if (!federation.enabled()) return Optional.empty();
        var key=binding.external(); var repository=new Repository(key.system(),key.targetInstanceId());
        var source=federation.settings().sources().stream().filter(value -> value.repository().equals(repository)).findFirst();
        if (source.isEmpty()) return Optional.empty();
        var type=source.get().types().get(binding.remoteType());
        if (type==null || !type.archimateType().equals(binding.archimateType())) return Optional.empty();
        var proof=integration.metadata().identities(federation.settings().ownerInstanceId()).find(new Resource(repository,ResourceKind.OBJECT,key.resourceId()));
        if (proof.isEmpty() || !proof.get().canonical().id().equals(binding.canonical().canonicalId())
                || !proof.get().canonical().semanticKind().equals(type.semanticKind()) || !proof.get().representation().externalType().equals(binding.remoteType())) return Optional.empty();
        var fields=new HashSet<String>(Set.of(ExternalObjectCatalogService.CANONICAL_PROPERTY,"archimateType"));
        type.fields().keySet().forEach(field -> fields.add(field.startsWith("property:") ? field.substring(9) : field));
        return Optional.of(Set.copyOf(fields));
    }
}
