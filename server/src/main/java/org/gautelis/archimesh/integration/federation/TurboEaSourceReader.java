package org.gautelis.archimesh.integration.federation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.gautelis.archimesh.integration.ExternalRepositoryConnector;
import org.gautelis.federation.Identity.*;
import org.gautelis.federation.http.*;

/** Facade over the existing Turbo EA client; no second HTTP client or writer. */
final class TurboEaSourceReader implements SourceReader {
    private final Repository repository; private final ExternalRepositoryConnector connector; private final ObjectMapper json;
    TurboEaSourceReader(Repository repository,ExternalRepositoryConnector connector,ObjectMapper json) { this.repository=repository; this.connector=connector; this.json=json; }
    @Override public Snapshot read(Representation representation,Set<Field> fields) {
        if (!repository.equals(representation.resource().repository()) || representation.resource().kind()!=ResourceKind.OBJECT || fields.isEmpty() || fields.size()>32
                || fields.stream().anyMatch(field -> !field.aspect().equals("card"))) throw new RepositoryFailure(RepositoryFailure.Kind.CONFIGURATION);
        connector.healthVersion();
        var card=connector.getObject(representation.resource().nativeId());
        if (!card.id().equals(representation.resource().nativeId()) || !card.type().equals(representation.externalType())) throw new RepositoryFailure(RepositoryFailure.Kind.INCOMPATIBLE);
        var node=json.createObjectNode().put("name",card.name()).put("description",card.description()).put("status",card.status());
        node.set("attributes",card.attributes()); node.set("lifecycle",card.lifecycle());
        var values=new TreeMap<Field,Value>(); fields.forEach(field -> values.put(field,Value.of(node.at(field.path()))));
        var lifecycle="ACTIVE".equals(card.status()) ? Lifecycle.ACTIVE : "ARCHIVED".equals(card.status()) ? Lifecycle.SOFT_DELETED : Lifecycle.UNKNOWN;
        return SourceReader.snapshot(representation,lifecycle,values);
    }
}
