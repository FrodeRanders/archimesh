package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.gautelis.archimesh.integration.IntegrationArchive.*;

class IntegrationArchiveTest {
    final ObjectMapper json=new ObjectMapper();
    Node canonical() { return new Node("IntegrationCanonicalObject",Map.of("canonicalId","urn:test:a","kind","Application")); }
    @Test void acceptsOnlyIndependentWhitelistedLabelsAndProperties() {
        validate("instance",List.of(canonical()),json);
        assertThrows(PublicationModel.Failure.class,() -> validate("instance",List.of(new Node("Model",Map.of("modelId","secret"))),json));
        assertThrows(PublicationModel.Failure.class,() -> validate("instance",List.of(new Node("IntegrationCanonicalObject",Map.of("canonicalId","urn:test:a","kind","Application","modelId","model"))),json));
    }
    @Test void rejectsDuplicateIdentitiesMissingReferencesAndInvalidTypes() {
        assertThrows(PublicationModel.Failure.class,() -> validate("instance",List.of(canonical(),canonical()),json));
        var external=new Node("IntegrationExternalBinding",Map.of("system","turboea","targetInstanceId","target","resourceKind","CARD","resourceId","card","canonicalId","urn:test:a"));
        assertThrows(PublicationModel.Failure.class,() -> validate("instance",List.of(external),json)); validate("instance",List.of(canonical(),external),json);
        assertThrows(PublicationModel.Failure.class,() -> validate("instance",List.of(new Node("IntegrationCanonicalObject",Map.of("canonicalId","urn:test:a","kind",true))),json));
    }
    @Test void refusesToRestoreControlStateOrCrossInstanceDocuments() {
        var control=new Node("IntegrationStateDocument",Map.of("instanceId","instance","kind","OPERATIONS","id","control","version",1L,"dataJson","{\"paused\":false}"));
        assertThrows(PublicationModel.Failure.class,() -> validate("instance",List.of(control),json));
        var other=new Node("IntegrationStateDocument",Map.of("instanceId","other","kind","CENTRAL_EDIT","id","edit","version",1L,"dataJson","{}"));
        assertThrows(PublicationModel.Failure.class,() -> validate("instance",List.of(other),json));
    }
    @Test void preservesOpaqueUnknownOutcomeIntentAndLosslessNumbersInsideDocuments() {
        String text="{\"state\":\"AMBIGUOUS\",\"proposed\":{\"budget\":1.12345678901234567890123456789},\"inFlight\":{\"operationId\":\"pending\"}}";
        var doc=new Node("IntegrationStateDocument",Map.of("instanceId","instance","kind","CENTRAL_EDIT","id","edit","version",2L,"dataJson",text));
        validate("instance",List.of(doc),json); assertEquals(text,doc.properties().get("dataJson"));
    }
}
