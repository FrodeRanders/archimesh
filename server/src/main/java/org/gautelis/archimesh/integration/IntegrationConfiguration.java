package org.gautelis.archimesh.integration;

import org.gautelis.archimesh.integration.turboea.TurboEaClient;
import org.gautelis.archimesh.integration.turboea.TurboEaMapping;
import java.net.URI;
import java.util.HashSet;
import java.util.List;

/** Non-secret target definitions loaded only when explicitly enabled. */
public record IntegrationConfiguration(int schemaVersion, String instanceId, List<Target> targets) {
    /** Explicit approval that the listed types/fields form a shared catalog for these named users. */
    public record CatalogAccess(boolean approved, java.util.Set<String> readers, java.util.Set<String> importers,
                                java.util.Set<String> cardTypes, java.util.Set<String> fields) {
        public CatalogAccess {
            readers = readers == null ? java.util.Set.of() : java.util.Set.copyOf(readers);
            importers = importers == null ? java.util.Set.of() : java.util.Set.copyOf(importers);
            cardTypes = cardTypes == null ? java.util.Set.of() : java.util.Set.copyOf(cardTypes);
            fields = fields == null ? java.util.Set.of() : java.util.Set.copyOf(fields);
            readers.forEach(IntegrationIdentity::required); importers.forEach(IntegrationIdentity::required);
            cardTypes.forEach(IntegrationIdentity::required); fields.forEach(IntegrationIdentity::required);
            if (!readers.containsAll(importers)) throw new IllegalArgumentException("Importers must be catalog readers");
            if (approved && (readers.isEmpty() || cardTypes.isEmpty() || !fields.contains("name")))
                throw new IllegalArgumentException("Approved shared catalog requires readers, types and name visibility");
        }
        public static CatalogAccess denied() { return new CatalogAccess(false,null,null,null,null); }
    }
    public record Target(String key, String instanceId, URI baseUrl, String tokenEnvironment,
                         String expectedApiVersion, int timeoutMillis, int maxResponseBytes, TurboEaMapping mapping,
                         CatalogAccess catalogAccess, PublicationAccess publicationAccess) {
        public Target {
            IntegrationIdentity.required(key); IntegrationIdentity.required(instanceId);
            IntegrationIdentity.required(expectedApiVersion);
            if (tokenEnvironment == null || !tokenEnvironment.matches("[A-Z][A-Z0-9_]{0,127}"))
                throw new IllegalArgumentException("Target tokenEnvironment must name an environment variable");
            TurboEaClient.validateBaseUrl(baseUrl);
            if (mapping == null || timeoutMillis < 1 || timeoutMillis > 120_000
                    || maxResponseBytes < 1024 || maxResponseBytes > 16 * 1024 * 1024)
                throw new IllegalArgumentException("Target mapping and bounded HTTP limits are required");
            catalogAccess = catalogAccess == null ? CatalogAccess.denied() : catalogAccess;
            if (!mapping.cardTypes().keySet().containsAll(catalogAccess.cardTypes()))
                throw new IllegalArgumentException("Catalog types must have explicit mappings");
            publicationAccess=publicationAccess==null ? PublicationAccess.denied() : publicationAccess;
            if (!mapping.cardTypes().keySet().containsAll(publicationAccess.cardTypes())
                    || !mapping.relationTypes().keySet().containsAll(publicationAccess.relationTypes()))
                throw new IllegalArgumentException("Publication types require explicit mappings");
        }
    }
    public record PublicationAccess(boolean enabled,java.util.Set<String> publishers,java.util.Set<String> cardTypes,
                                    java.util.Set<String> relationTypes,java.util.Set<String> fields,
                                    boolean acceptUnconditionalWriteRisk,boolean completeAttributeReadsApproved) {
        public PublicationAccess {
            publishers=publishers==null ? java.util.Set.of() : java.util.Set.copyOf(publishers);
            cardTypes=cardTypes==null ? java.util.Set.of() : java.util.Set.copyOf(cardTypes);
            relationTypes=relationTypes==null ? java.util.Set.of() : java.util.Set.copyOf(relationTypes);
            fields=fields==null ? java.util.Set.of() : java.util.Set.copyOf(fields);
            publishers.forEach(IntegrationIdentity::required); cardTypes.forEach(IntegrationIdentity::required);
            relationTypes.forEach(IntegrationIdentity::required); fields.forEach(IntegrationIdentity::required);
            if (enabled && (publishers.isEmpty() || cardTypes.isEmpty())) throw new IllegalArgumentException("Publication needs named publishers and approved card types");
            for (String field:fields) if (!(java.util.Set.of("name","description","lifecycle").contains(field) || field.startsWith("attributes.")))
                throw new IllegalArgumentException("Unsupported publication field; status/identity changes are not allowed");
        }
        public static PublicationAccess denied() { return new PublicationAccess(false,null,null,null,null,false,false); }
    }

    public IntegrationConfiguration {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported integration configuration version");
        IntegrationIdentity.required(instanceId);
        targets = List.copyOf(targets);
        if (targets.isEmpty() || targets.size() > 20) throw new IllegalArgumentException("Configure between one and twenty targets");
        var keys = new HashSet<String>(); var instances = new HashSet<String>();
        for (var target : targets) {
            if (!keys.add(target.key()) || !instances.add(target.instanceId()))
                throw new IllegalArgumentException("Duplicate integration target key or instance identity");
        }
    }
}
