package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gautelis.federation.*;
import org.neo4j.driver.*;
import java.util.*;
import static org.gautelis.federation.Identity.*;
import static org.gautelis.archimesh.integration.IntegrationIdentity.required;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;

/** One view over the existing integration registry, not an independent identity database/writer. */
final class Neo4jFederationRegistry implements IdentityRegistry {
    private final Driver driver;
    private final ObjectMapper json;
    private final String instance;
    Neo4jFederationRegistry(Driver driver,ObjectMapper json,String instance) {
        this.driver=driver; this.json=json; this.instance=required(instance);
    }
    static IntegrationIdentity.ExternalResourceKey key(Resource resource) {
        var kind=resource.kind()==ResourceKind.RELATIONSHIP ? IntegrationIdentity.ResourceKind.RELATION
                : resource.repository().system().equals("turboea") ? IntegrationIdentity.ResourceKind.CARD : IntegrationIdentity.ResourceKind.ENTITY;
        if (resource.kind()==ResourceKind.RELATIONSHIP && !resource.repository().system().equals("turboea"))
            throw new IllegalArgumentException("Aspect-reference relationships need an explicit adapter contract");
        return new IntegrationIdentity.ExternalResourceKey(resource.repository().system(),resource.repository().instance(),kind,resource.nativeId());
    }
    static Resource resource(IntegrationIdentity.ExternalResourceKey key) {
        return new Resource(new Repository(key.system(),key.targetInstanceId()),key.object() ? ResourceKind.OBJECT : ResourceKind.RELATIONSHIP,key.resourceId());
    }
    static Map<String,Object> parameters(IntegrationIdentity.ExternalResourceKey key) {
        var p=new HashMap<String,Object>(); p.put("system",key.system()); p.put("target",key.targetInstanceId());
        p.put("resourceKind",key.resourceKind().name()); p.put("resourceId",key.indexId());
        p.put("nativeId",key.resourceKind()==IntegrationIdentity.ResourceKind.ENTITY ? key.resourceId() : null);
        return p;
    }
    @Override public Optional<Binding> find(Resource resource) {
        var key=key(resource);
        try (var session=driver.session()) { return session.executeRead(tx -> {
            var rows=tx.run("""
                    MATCH (e:IntegrationExternalBinding {system:$system,targetInstanceId:$target,resourceKind:$resourceKind,resourceId:$resourceId})
                    MATCH (c:IntegrationCanonicalObject {canonicalId:e.canonicalId})
                    RETURN properties(e) AS e,properties(c) AS c
                    """,parameters(key));
            if (!rows.hasNext()) return Optional.empty();
            var row=rows.single(); var e=row.get("e"); var c=row.get("c");
            checkNative(e,key); checkOwner(c);
            if (c.get("semanticKind").isNull() || e.get("externalType").isNull()) throw new Failure(409,"IDENTITY_MIGRATION_REQUIRED");
            return Optional.of(new Binding(new Canonical(c.get("canonicalId").asString(),c.get("semanticKind").asString()),
                    new Representation(resource,e.get("externalType").asString()),e.get("identityProofId").asString("legacy-migration")));
        }); }
    }
    @Override public Binding reserveSource(Representation source,Canonical candidate,Evidence evidence) {
        return write(source,candidate,evidence,false);
    }
    @Override public Binding bindRepresentation(Canonical canonical,Representation representation,Evidence evidence) {
        return write(representation,canonical,evidence,true);
    }
    private Binding write(Representation representation,Canonical requested,Evidence evidence,boolean adopt) {
        try (var session=driver.session()) { return session.executeWrite(tx -> writeInTransaction(tx,representation,requested,evidence,adopt)); }
    }
    Binding writeInTransaction(TransactionContext tx,Representation representation,Canonical requested,Evidence evidence,boolean adopt) {
        Objects.requireNonNull(representation); Objects.requireNonNull(requested); Objects.requireNonNull(evidence);
        var key=key(representation.resource()); var p=parameters(key);
        p.put("candidate",required(requested.id())); p.put("semantic",required(requested.semanticKind()));
        p.put("externalType",required(representation.externalType())); p.put("owner",instance);
        String proof="identity-proof:"+IntegrationIdentity.sha256(evidence.correlation()); p.put("proof",proof);
        String fingerprint=PublicationJson.digest(json.valueToTree(List.of(adopt ? "BIND" : "RESERVE",representation,requested,evidence)));
            if (adopt) {
                var c=tx.run("MATCH (c:IntegrationCanonicalObject {canonicalId:$candidate}) SET c.fence=coalesce(c.fence,0)+1 RETURN properties(c) AS c",p);
                if (!c.hasNext()) throw new Failure(409,"CANONICAL_IDENTITY_REQUIRED");
                var value=c.single().get("c"); checkOwner(value);
                if (!requested.semanticKind().equals(value.get("semanticKind").asString(null))) throw new Failure(409,"CANONICAL_SEMANTICS_CONFLICT");
            }
            var external=tx.run("""
                    MERGE (e:IntegrationExternalBinding {system:$system,targetInstanceId:$target,resourceKind:$resourceKind,resourceId:$resourceId})
                    ON CREATE SET e.canonicalId=$candidate,e.nativeId=$nativeId,e.externalType=$externalType,e.identityProofId=$proof
                    SET e.fence=coalesce(e.fence,0)+1 RETURN properties(e) AS e
                    """,p).single().get("e");
            checkNative(external,key);
            String canonical=external.get("canonicalId").asString(); p.put("canonical",canonical);
            if (adopt && !canonical.equals(requested.id())) throw new Failure(409,"EXTERNAL_IDENTITY_CONFLICT");
            if (!representation.externalType().equals(external.get("externalType").asString(null))) throw new Failure(409,"EXTERNAL_TYPE_CONFLICT_OR_MIGRATION_REQUIRED");
            var c=tx.run("""
                    MERGE (c:IntegrationCanonicalObject {canonicalId:$canonical})
                    ON CREATE SET c.kind=$semantic,c.semanticKind=$semantic,c.registryInstanceId=$owner,c.identityVersion=1
                    SET c.fence=coalesce(c.fence,0)+1 RETURN properties(c) AS c
                    """,p).single().get("c");
            checkOwner(c);
            if (!requested.semanticKind().equals(c.get("semanticKind").asString(null))) throw new Failure(409,"CANONICAL_SEMANTICS_CONFLICT");
            var canonicalValue=new Canonical(canonical,c.get("semanticKind").asString());
            var data=json.createObjectNode().put("fingerprint",fingerprint).put("canonicalId",canonical).put("actor",evidence.actor())
                    .put("correlation",evidence.correlation()).put("contentDigest",evidence.contentDigest()).put("mode",adopt ? "BIND" : "RESERVE");
            data.set("representation",json.valueToTree(representation));
            var prior=tx.run("MATCH (d:IntegrationStateDocument {instanceId:$owner,kind:'IDENTITY_PROOF',id:$proof}) RETURN d.dataJson AS data",p);
            if (prior.hasNext()) {
                try { if (!fingerprint.equals(json.readTree(prior.single().get("data").asString()).path("fingerprint").asText())) throw new Failure(409,"IDENTITY_REQUEST_ID_REUSED"); }
                catch (java.io.IOException invalid) { throw new Failure(409,"CORRUPT_IDENTITY_PROOF"); }
            } else {
                p.put("data",data.toString());
                tx.run("CREATE (d:IntegrationStateDocument {instanceId:$owner,kind:'IDENTITY_PROOF',id:$proof,version:1,dataJson:$data})",p).consume();
            }
            return new Binding(canonicalValue,representation,external.get("identityProofId").asString("legacy-migration"));
    }
    private void checkOwner(Value canonical) {
        if (!instance.equals(canonical.get("registryInstanceId").asString(null))) throw new Failure(409,"IDENTITY_OWNER_OR_MIGRATION_REQUIRED");
    }
    static void checkNative(Value external,IntegrationIdentity.ExternalResourceKey key) {
        if (key.resourceKind()==IntegrationIdentity.ResourceKind.ENTITY && !key.resourceId().equals(external.get("nativeId").asString(null)))
            throw new Failure(409,"NATIVE_IDENTITY_INDEX_CONFLICT");
    }
}
