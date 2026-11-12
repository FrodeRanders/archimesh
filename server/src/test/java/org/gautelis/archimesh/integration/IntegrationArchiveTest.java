package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.gautelis.archimesh.integration.IntegrationArchive.*;

class IntegrationArchiveTest {
    final ObjectMapper json=new ObjectMapper();
    Node canonical() { return new Node("IntegrationCanonicalObject",Map.of("canonicalId","urn:test:a","kind","Application")); }
    @Test void legacyNativeTypeNamesDoNotBecomeFederationDocumentKinds() {
        for (String kind:List.of("FEDERATION_APP","IDENTITY_PROOF"))
            validate(LEGACY_FORMAT,"instance",List.of(new Node("IntegrationCanonicalObject",Map.of("canonicalId","urn:test:a","kind",kind))),json);
    }
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
    @Test void versionTwoPreservesNativeEntityIdsAndRequiresTheirIndexAndProvenanceClosure() {
        String nativeId="urn:li:dataset:"+"å".repeat(2000);
        var key=new IntegrationIdentity.ExternalResourceKey("datahub","source",IntegrationIdentity.ResourceKind.ENTITY,nativeId);
        var canonical=new Node("IntegrationCanonicalObject",Map.of("canonicalId","urn:test:a","kind","catalog.dataset",
                "semanticKind","catalog.dataset","registryInstanceId","instance","identityVersion",1L));
        var properties=new HashMap<String,Object>(); properties.put("canonicalId","urn:test:a"); properties.put("system","datahub");
        properties.put("targetInstanceId","source"); properties.put("resourceKind","ENTITY"); properties.put("resourceId",key.indexId());
        properties.put("nativeId",nativeId); properties.put("externalType","dataset"); properties.put("identityProofId","proof");
        var proof=new Node("IntegrationStateDocument",Map.of("instanceId","instance","kind","IDENTITY_PROOF","id","proof","version",1L,"dataJson","{\"canonicalId\":\"urn:test:a\",\"mode\":\"RESERVE\"}"));
        var valid=List.of(canonical,new Node("IntegrationExternalBinding",properties),proof);
        validate(FORMAT,"instance",valid,json);
        assertThrows(PublicationModel.Failure.class,() -> validate(LEGACY_FORMAT,"instance",valid,json));
        properties.put("nativeId",nativeId+"changed");
        assertThrows(PublicationModel.Failure.class,() -> validate("instance",List.of(canonical,new Node("IntegrationExternalBinding",properties),proof),json));
        properties.put("nativeId",nativeId); properties.remove("identityProofId");
        assertThrows(PublicationModel.Failure.class,() -> validate("instance",List.of(canonical,new Node("IntegrationExternalBinding",properties),proof),json));
    }
    @Test void legacyVersionRemainsReadableButCannotSmuggleNewFields() {
        validate(LEGACY_FORMAT,"instance",List.of(canonical()),json);
        assertThrows(PublicationModel.Failure.class,() -> validate("unknown","instance",List.of(canonical()),json));
        var properties=new HashMap<>(canonical().properties()); properties.put("externalType","Application");
        assertThrows(PublicationModel.Failure.class,() -> validate(LEGACY_FORMAT,"instance",List.of(new Node("IntegrationCanonicalObject",properties)),json));
    }
}
