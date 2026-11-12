package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.neo4j.driver.*;
import java.util.*;
import static org.gautelis.archimesh.integration.IntegrationIdentity.required;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;

/** Metadata-only semantic classification. One paused, digest-checked transaction; no model/remote writes. */
final class IdentityMigration {
    private final Driver driver;
    private final ObjectMapper json;
    IdentityMigration(Driver driver,ObjectMapper json) { this.driver=driver; this.json=json; }
    private record Snapshot(ObjectNode review,List<Map<String,Object>> bindings,List<Map<String,Object>> documents) { }
    JsonNode preview(String instance,String canonical,String semantic) {
        required(instance); required(canonical); required(semantic);
        new org.gautelis.federation.Identity.Canonical(canonical,semantic);
        try (var session=driver.session()) { return session.executeRead(tx -> snapshot(tx,instance,canonical,semantic).review()); }
    }
    private Snapshot snapshot(TransactionContext tx,String instance,String canonical,String semantic) {
        var control=tx.run("MATCH (d:IntegrationStateDocument {instanceId:$instance,kind:'OPERATIONS',id:'control'}) RETURN d.dataJson AS data,d.version AS version",Map.of("instance",instance));
        if (!control.hasNext()) throw new Failure(409,"IDENTITY_MIGRATION_REQUIRES_PAUSE");
        var pause=control.single();
        if (!parse(pause.get("data").asString()).path("paused").asBoolean()) throw new Failure(409,"IDENTITY_MIGRATION_REQUIRES_PAUSE");
        var params=Map.<String,Object>of("canonical",canonical);
        var objects=tx.run("MATCH (c:IntegrationCanonicalObject {canonicalId:$canonical}) RETURN properties(c) AS p",params);
        if (!objects.hasNext()) throw new Failure(409,"CANONICAL_IDENTITY_REQUIRED");
        var object=clean(objects.single().get("p").asMap());
        if (object.containsKey("semanticKind")) throw new Failure(409,"CANONICAL_SEMANTICS_ALREADY_CLASSIFIED");
        if (object.containsKey("registryInstanceId") && !instance.equals(object.get("registryInstanceId"))) throw new Failure(409,"IDENTITY_REGISTRY_OWNER_CONFLICT");
        var external=tx.run("MATCH (e:IntegrationExternalBinding {canonicalId:$canonical}) RETURN properties(e) AS p ORDER BY e.system,e.targetInstanceId,e.resourceKind,e.resourceId LIMIT 20001",params).list(r -> clean(r.get("p").asMap()));
        if (external.isEmpty() || external.size()>100) throw new Failure(409,"IDENTITY_MIGRATION_SCOPE_INVALID");
        for (var e:external) if (!"turboea".equals(e.get("system")) || !Set.of("CARD","RELATION").contains(e.get("resourceKind"))
                || !Objects.equals(e.getOrDefault("externalType",object.get("kind")),object.get("kind"))) throw new Failure(409,"LEGACY_EXTERNAL_TYPE_CONFLICT");
        var bindings=new ArrayList<>(tx.run("MATCH (n {canonicalId:$canonical}) WHERE n:IntegrationModelBinding OR n:IntegrationPublicationIdentity OR n:IntegrationIdentityClaim RETURN labels(n)[0] AS label,properties(n) AS p ORDER BY n.instanceId,n.sourceModelId,n.entityKind,n.entityId LIMIT 20001",params).list(r -> {
            var p=clean(r.get("p").asMap()); p.put("label",r.get("label").asString()); return p;
        }));
        if (bindings.size()>10000 || bindings.stream().anyMatch(b -> !instance.equals(b.get("instanceId")))) throw new Failure(409,"IDENTITY_MIGRATION_CROSS_INSTANCE");
        bindings.sort(Comparator.comparing(b -> b.get("label").toString()+":"+b.get("sourceModelId")+":"+b.get("entityKind")+":"+b.get("entityId")));
        var models=new TreeSet<String>(); bindings.forEach(b -> models.add((String)b.get("sourceModelId")));
        var docs=tx.run("MATCH (d:IntegrationStateDocument {instanceId:$instance}) WHERE NOT (d.kind='OPERATIONS' AND d.id='control') RETURN properties(d) AS p ORDER BY d.kind,d.id LIMIT 20001",Map.of("instance",instance)).list(r -> clean(r.get("p").asMap()));
        if (docs.size()>20000) throw new Failure(413,"IDENTITY_MIGRATION_TOO_LARGE");
        var affected=new ArrayList<Map<String,Object>>();
        for (var d:docs) {
            var data=parse((String)d.get("dataJson"));
            if (!models.contains(data.path("sourceModelId").asText()) && !references(data,canonical)) continue;
            if (uncertain(data)) throw new Failure(409,"IDENTITY_MIGRATION_UNCERTAIN_OUTCOME");
            affected.add(d);
        }
        var review=json.createObjectNode().put("instanceId",instance).put("canonicalId",canonical).put("legacyType",(String)object.get("kind"))
                .put("semanticKind",semantic).put("controlVersion",pause.get("version").asLong());
        review.set("canonical",json.valueToTree(object)); review.set("external",json.valueToTree(external));
        review.set("bindings",json.valueToTree(bindings)); review.set("documents",json.valueToTree(affected));
        review.set("models",json.valueToTree(models));
        if (review.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>IntegrationArchive.MAX_BYTES) throw new Failure(413,"IDENTITY_MIGRATION_TOO_LARGE");
        review.put("metadataDigest",PublicationJson.digest(review));
        return new Snapshot(review,bindings,affected);
    }
    void apply(String instance,String canonical,String semantic,String digest,String actor) {
        required(instance); required(canonical); required(semantic); required(actor);
        new org.gautelis.federation.Identity.Canonical(canonical,semantic);
        if (digest==null || !digest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Migration review digest required");
        try (var session=driver.session()) { session.executeWrite(tx -> {
            tx.run("MATCH (d:IntegrationStateDocument {instanceId:$instance,kind:'OPERATIONS',id:'control'}) SET d.fence=coalesce(d.fence,0)+1",Map.of("instance",instance)).consume();
            tx.run("MATCH (c:IntegrationCanonicalObject {canonicalId:$canonical}) SET c.fence=coalesce(c.fence,0)+1",Map.of("canonical",canonical)).consume();
            var snapshot=snapshot(tx,instance,canonical,semantic);
            if (!digest.equals(snapshot.review().path("metadataDigest").asText())) throw new Failure(409,"IDENTITY_MIGRATION_REVIEW_STALE");
            var p=new HashMap<String,Object>(); p.put("instance",instance); p.put("canonical",canonical); p.put("semantic",semantic);
            p.put("legacy",snapshot.review().path("legacyType").asText()); p.put("actor",actor);
            String proof="identity-migration:"+digest; p.put("proof",proof);
            tx.run("MATCH (c:IntegrationCanonicalObject {canonicalId:$canonical}) SET c.semanticKind=$semantic,c.registryInstanceId=$instance,c.identityVersion=1",p).consume();
            tx.run("MATCH (e:IntegrationExternalBinding {canonicalId:$canonical}) SET e.externalType=coalesce(e.externalType,$legacy),e.identityProofId=$proof",p).consume();
            tx.run("MATCH (b {canonicalId:$canonical}) WHERE b:IntegrationModelBinding OR b:IntegrationPublicationIdentity SET b.externalType=coalesce(b.externalType,$legacy)",p).consume();
            String quarantined="migrated:"+UUID.randomUUID();
            for (var d:snapshot.documents()) {
                var data=(ObjectNode)parse((String)d.get("dataJson")); String kind=(String)d.get("kind");
                if (kind.equals("IDENTITY_PROOF")) continue;
                if (data.has("modelGeneration")) { data.set("archivedModelGeneration",data.get("modelGeneration")); data.put("modelGeneration",quarantined); }
                if (kind.equals("MODEL_SCOPE")) data.put("paused",true).put("identityMigration",digest);
                if (kind.equals("INBOUND_POLICY")) data.put("enabled",false);
                if (kind.equals("PUBLICATION_POLICY") && data.path("policy").isObject()) {
                    data.set("archivedPolicy",data.path("policy").deepCopy()); ((ObjectNode)data.path("policy")).put("mode","DISABLED");
                }
                if (Set.of("JOB","PUBLICATION_JOB","CENTRAL_EDIT","RECONCILIATION").contains(kind)) {
                    if (data.has("state")) data.set("archivedState",data.get("state"));
                    data.put("state","PAUSED").put("code","IDENTITY_MIGRATION_REQUIRES_NEW_REQUEST");
                }
                if (Set.of("INBOUND_STATE","PUBLICATION_RESOURCE").contains(kind)) {
                    data.remove("baseline"); data.put("state","PAUSED").put("code","IDENTITY_MIGRATION_BASELINE_REVIEW_REQUIRED");
                }
                var update=new HashMap<String,Object>(); update.put("instance",instance); update.put("kind",kind); update.put("id",d.get("id"));
                update.put("version",d.get("version")); update.put("data",data.toString()); update.put("state",data.path("state").asText("UNRECORDED"));
                var rows=tx.run("MATCH (d:IntegrationStateDocument {instanceId:$instance,kind:$kind,id:$id,version:$version}) SET d.dataJson=$data,d.version=d.version+1,d.operationState=$state RETURN d",update);
                if (!rows.hasNext()) throw new Failure(409,"IDENTITY_MIGRATION_REVIEW_STALE"); rows.consume();
            }
            // An existing model binding must not become active merely because its former scope was absent.
            for (var model:snapshot.review().path("models")) {
                p.put("model",model.asText()); p.put("scope",PublicationJson.key("model-scope:",model.asText()));
                if (!tx.run("MATCH (d:IntegrationStateDocument {instanceId:$instance,kind:'MODEL_SCOPE',id:$scope}) RETURN d",p).hasNext()) {
                    var generation=tx.run("MATCH (m:Model {modelId:$model,registered:true}) RETURN m.integrationGeneration AS generation",p);
                    if (!generation.hasNext()) throw new Failure(409,"IDENTITY_MIGRATION_MODEL_REVIEW_REQUIRED");
                    var data=json.createObjectNode().put("sourceModelId",model.asText()).put("generation",generation.single().get("generation").asString()).put("paused",true).put("identityMigration",digest);
                    p.put("scopeData",data.toString());
                    tx.run("CREATE (d:IntegrationStateDocument {instanceId:$instance,kind:'MODEL_SCOPE',id:$scope,version:1,dataJson:$scopeData})",p).consume();
                }
            }
            var audit=json.createObjectNode().put("canonicalId",canonical).put("mode","MIGRATE").put("legacyType",p.get("legacy").toString())
                    .put("semanticKind",semantic).put("actor",actor).put("metadataDigest",digest);
            p.put("data",audit.toString());
            tx.run("CREATE (d:IntegrationStateDocument {instanceId:$instance,kind:'IDENTITY_PROOF',id:$proof,version:1,dataJson:$data})",p).consume();
            return null;
        }); }
    }
    private ObjectNode parse(String text) {
        try { return (ObjectNode)json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(text); }
        catch (Exception invalid) { throw new Failure(409,"CORRUPT_INTEGRATION_METADATA"); }
    }
    private boolean references(JsonNode node,String canonical) {
        if (node.isTextual()) return canonical.equals(node.asText());
        for (var child:node) if (references(child,canonical)) return true;
        return false;
    }
    private boolean uncertain(JsonNode node) {
        if (node.isObject() && (node.has("inFlight") || node.has("pending") || Set.of("AMBIGUOUS","IN_FLIGHT").contains(node.path("state").asText()))) return true;
        for (var child:node) if (uncertain(child)) return true;
        return false;
    }
    private static Map<String,Object> clean(Map<String,Object> source) {
        var p=new HashMap<>(source); p.remove("fence"); return p;
    }
}
