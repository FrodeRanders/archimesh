package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.neo4j.driver.Driver;
import java.util.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.*;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;

/** Versioned metadata archive. Only whitelisted independent labels; never model graph nodes or credentials. */
public final class IntegrationArchive {
    public record Node(String label,Map<String,Object> properties) { public Node { properties=Map.copyOf(properties); } }
    public record Package(String format,String instanceId,String exportedAt,JsonNode targets,List<Node> nodes,String digest) { }
    public static final String LEGACY_FORMAT="archimesh-integration-v1",FORMAT="archimesh-integration-v2";
    private static final Set<String> EXTENSIONS=Set.of("semanticKind","registryInstanceId","identityVersion","externalType","nativeId","identityProofId");
    public static final int MAX_NODES=20000,MAX_BYTES=8388608;
    private static final Map<String,List<String>> KEYS=Map.of(
        "IntegrationCanonicalObject",List.of("canonicalId"),
        "IntegrationExternalBinding",List.of("system","targetInstanceId","resourceKind","resourceId"),
        "IntegrationModelBinding",List.of("instanceId","sourceModelId","entityKind","entityId"),
        "IntegrationPublicationIdentity",List.of("instanceId","sourceModelId","entityKind","entityId"),
        "IntegrationIdentityClaim",List.of("instanceId","sourceModelId","entityKind","entityId"),
        "IntegrationStateDocument",List.of("instanceId","kind","id"));
    private static final Map<String,Set<String>> PROPERTIES=Map.of(
        "IntegrationCanonicalObject",Set.of("canonicalId","kind","semanticKind","registryInstanceId","identityVersion","fence"),
        "IntegrationExternalBinding",Set.of("system","targetInstanceId","resourceKind","resourceId","canonicalId","fence","externalType","nativeId","identityProofId"),
        "IntegrationModelBinding",Set.of("instanceId","sourceModelId","entityKind","entityId","canonicalId","archimateType","mappingVersion","opBatchId","state","bindingMode","acceptedRevision","system","targetInstanceId","resourceKind","resourceId","fence","externalType","nativeId"),
        "IntegrationPublicationIdentity",Set.of("instanceId","sourceModelId","entityKind","entityId","canonicalId","archimateType","mappingVersion","externalType"),
        "IntegrationIdentityClaim",Set.of("instanceId","sourceModelId","entityKind","entityId","canonicalId","fence"),
        "IntegrationStateDocument",Set.of("instanceId","kind","id","version","dataJson","fence","operationState","verifiedAt","nextAttemptAt","updatedAt"));
    private final Driver driver;
    private final ObjectMapper json;
    public IntegrationArchive(Driver driver,ObjectMapper json) { this.driver=driver; this.json=json; }
    public List<Node> capture(String instance) {
        IntegrationIdentity.required(instance);
        try (var session=driver.session()) {
            return session.executeRead(tx -> {
                var nodes=new ArrayList<Node>();
                var scoped=tx.run("MATCH (n {instanceId:$instance}) WHERE n:IntegrationModelBinding OR n:IntegrationPublicationIdentity OR n:IntegrationIdentityClaim OR (n:IntegrationStateDocument AND NOT (n.kind='OPERATIONS' AND n.id='control')) RETURN labels(n) AS labels,properties(n) AS p LIMIT $limit",Map.of("instance",instance,"limit",MAX_NODES+1));
                while (scoped.hasNext()) { var row=scoped.next(); var labels=row.get("labels").asList(v -> v.asString()); if (labels.size()!=1 || !KEYS.containsKey(labels.getFirst())) throw new Failure(409,"ARCHIVE_LABEL_CONFLICT"); nodes.add(new Node(labels.getFirst(),row.get("p").asMap())); }
                var canonical=new TreeSet<String>(); for (var node:nodes) if (node.properties().get("canonicalId") instanceof String id) canonical.add(id);
                tx.run("MATCH (c:IntegrationCanonicalObject {registryInstanceId:$instance}) RETURN c.canonicalId AS id LIMIT $limit",Map.of("instance",instance,"limit",MAX_NODES+1)).forEachRemaining(row -> canonical.add(row.get("id").asString()));
                var globals=tx.run("MATCH (n) WHERE (n:IntegrationCanonicalObject OR n:IntegrationExternalBinding) AND n.canonicalId IN $canonical RETURN labels(n) AS labels,properties(n) AS p LIMIT $limit",Map.of("canonical",canonical,"limit",MAX_NODES+1));
                while (globals.hasNext()) { var row=globals.next(); var labels=row.get("labels").asList(v -> v.asString()); if (labels.size()!=1) throw new Failure(409,"ARCHIVE_LABEL_CONFLICT"); nodes.add(new Node(labels.getFirst(),row.get("p").asMap())); }
                validate(instance,nodes,json); nodes.sort(Comparator.comparing(n -> n.label()+KEYS.get(n.label()).stream().map(k -> n.properties().get(k).toString()).toList().toString())); return List.copyOf(nodes);
            });
        }
    }
    public static boolean supported(String format) { return FORMAT.equals(format) || LEGACY_FORMAT.equals(format); }
    public static void validate(String format,String instance,List<Node> nodes,ObjectMapper json) {
        if (!supported(format)) throw new Failure(400,"ARCHIVE_FORMAT_INVALID");
        if (LEGACY_FORMAT.equals(format) && nodes!=null && nodes.stream().anyMatch(n -> n!=null &&
                (n.properties().keySet().stream().anyMatch(EXTENSIONS::contains) || n.label().equals("IntegrationCanonicalObject") && n.properties().containsKey("fence")
                || "IDENTITY_PROOF".equals(n.properties().get("kind")) || "ENTITY".equals(n.properties().get("resourceKind")))))
            throw new Failure(400,"ARCHIVE_LEGACY_EXTENSION_INVALID");
        validate(instance,nodes,json);
    }
    public static void validate(String instance,List<Node> nodes,ObjectMapper json) {
        IntegrationIdentity.required(instance);
        if (nodes==null || nodes.size()>MAX_NODES || json.valueToTree(nodes).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BYTES) throw new Failure(413,"ARCHIVE_TOO_LARGE");
        var unique=new HashSet<List<Object>>(); var canonical=new HashSet<String>();
        for (var node:nodes) {
            if (node==null || node.label()==null) throw new Failure(400,"ARCHIVE_NODE_INVALID");
            var allowed=PROPERTIES.get(node.label()); var p=node.properties();
            if (allowed==null || !allowed.containsAll(p.keySet())) throw new Failure(400,"ARCHIVE_PROPERTIES_INVALID");
            var identity=new ArrayList<Object>(); identity.add(node.label());
            for (String key:KEYS.get(node.label())) { if (!(p.get(key) instanceof String v)) throw new Failure(400,"ARCHIVE_IDENTITY_REQUIRED"); IntegrationIdentity.required(v); identity.add(v); }
            if (!unique.add(identity)) throw new Failure(400,"ARCHIVE_DUPLICATE_IDENTITY");
            if (p.containsKey("instanceId") && !instance.equals(p.get("instanceId"))) throw new Failure(409,"ARCHIVE_INSTANCE_MISMATCH");
            for (var e:p.entrySet()) if (!(e.getValue() instanceof String || e.getValue() instanceof Long || e.getValue() instanceof Integer || e.getValue() instanceof Boolean)) throw new Failure(400,"ARCHIVE_PROPERTY_TYPE_INVALID");
            var numeric=Set.of("fence","acceptedRevision","version","verifiedAt","nextAttemptAt","updatedAt","identityVersion");
            for (var e:p.entrySet()) {
                if (numeric.contains(e.getKey())) { if (!(e.getValue() instanceof Number n) || n.longValue()<0) throw new Failure(400,"ARCHIVE_NUMBER_INVALID"); }
                else if (!(e.getValue() instanceof String)) throw new Failure(400,"ARCHIVE_TEXT_REQUIRED");
            }
            if (!node.label().equals("IntegrationStateDocument")) {
                var required=new HashSet<>(allowed); required.remove("fence"); required.remove("bindingMode"); required.removeAll(EXTENSIONS);
                if (!p.keySet().containsAll(required)) throw new Failure(400,"ARCHIVE_PROPERTIES_MISSING");
                for (String key:required) if (!numeric.contains(key)) IntegrationIdentity.required((String)p.get(key));
            }
            if (node.label().equals("IntegrationModelBinding")) {
                try {
                    var kind=IntegrationIdentity.EntityKind.valueOf((String)p.get("entityKind"));
                    new IntegrationIdentity.ModelElementKey(instance,(String)p.get("sourceModelId"),kind,(String)p.get("entityId"));
                    var resource=IntegrationIdentity.ResourceKind.valueOf((String)p.get("resourceKind"));
                    if ((kind==IntegrationIdentity.EntityKind.ELEMENT)!=(resource!=IntegrationIdentity.ResourceKind.RELATION)) throw new IllegalArgumentException();
                    IntegrationIdentity.BindingState.valueOf((String)p.get("state"));
                    if (!Set.of("CREATE","LINK","PUBLISHED").contains(p.getOrDefault("bindingMode","CREATE"))) throw new IllegalArgumentException();
                } catch (IllegalArgumentException invalid) { throw new Failure(400,"ARCHIVE_BINDING_INVALID"); }
            }
            if (node.label().equals("IntegrationCanonicalObject")) {
                canonical.add((String)p.get("canonicalId")); IntegrationIdentity.required((String)p.get("kind"));
                if (p.containsKey("semanticKind") || p.containsKey("registryInstanceId") || p.containsKey("identityVersion")) {
                    if (!(p.get("semanticKind") instanceof String semantic) || !(p.get("registryInstanceId") instanceof String owner)
                            || !(p.get("identityVersion") instanceof Number version) || version.longValue()!=1 || !instance.equals(owner)) throw new Failure(400,"ARCHIVE_REGISTRY_IDENTITY_INVALID");
                    IntegrationIdentity.required(semantic); IntegrationIdentity.required(owner);
                }
            }
            if (node.label().equals("IntegrationExternalBinding") || node.label().equals("IntegrationModelBinding")) {
                try {
                    var resource=IntegrationIdentity.ResourceKind.valueOf((String)p.get("resourceKind"));
                    String nativeId=resource==IntegrationIdentity.ResourceKind.ENTITY ? (String)p.get("nativeId") : (String)p.get("resourceId");
                    var key=new IntegrationIdentity.ExternalResourceKey((String)p.get("system"),(String)p.get("targetInstanceId"),resource,nativeId);
                    if (!key.indexId().equals(p.get("resourceId")) || resource!=IntegrationIdentity.ResourceKind.ENTITY && p.containsKey("nativeId")) throw new IllegalArgumentException();
                    if (resource==IntegrationIdentity.ResourceKind.ENTITY && !p.containsKey("externalType")) throw new IllegalArgumentException();
                    for (String field:List.of("externalType","identityProofId")) if (p.containsKey(field)) IntegrationIdentity.required((String)p.get(field));
                } catch (Exception invalid) { throw new Failure(400,"ARCHIVE_EXTERNAL_IDENTITY_INVALID"); }
            }
            if (node.label().equals("IntegrationStateDocument")) {
                try {
                    var kind=DocumentKind.valueOf((String)p.get("kind"));
                    if (kind==DocumentKind.OPERATIONS && p.get("id").equals("control")) throw new IllegalArgumentException();
                    var data=json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree((String)p.get("dataJson"));
                    if (((Number)p.get("version")).longValue()<1) throw new IllegalArgumentException();
                    new StateDocument(instance,kind,(String)p.get("id"),((Number)p.get("version")).longValue(),data);
                } catch (Exception invalid) { throw new Failure(400,"ARCHIVE_DOCUMENT_INVALID"); }
            }
        }
        for (var node:nodes) if (!node.label().equals("IntegrationCanonicalObject") && node.properties().containsKey("canonicalId") && !canonical.contains(node.properties().get("canonicalId"))) throw new Failure(400,"ARCHIVE_CANONICAL_REFERENCE_MISSING");
        var neutral=new HashSet<String>();
        for (var node:nodes) if (node.label().equals("IntegrationCanonicalObject") && node.properties().containsKey("semanticKind")) neutral.add((String)node.properties().get("canonicalId"));
        for (var node:nodes) if (node.label().equals("IntegrationExternalBinding") && neutral.contains(node.properties().get("canonicalId"))
                && (!node.properties().containsKey("identityProofId") || !node.properties().containsKey("externalType"))) throw new Failure(400,"ARCHIVE_IDENTITY_PROOF_OR_TYPE_MISSING");
        for (var node:nodes) if (Set.of("IntegrationModelBinding","IntegrationPublicationIdentity").contains(node.label())
                && neutral.contains(node.properties().get("canonicalId")) && !node.properties().containsKey("externalType")) throw new Failure(400,"ARCHIVE_EXTERNAL_TYPE_MISSING");
        var proofs=new HashMap<String,JsonNode>();
        for (var node:nodes) if (node.label().equals("IntegrationStateDocument") && "IDENTITY_PROOF".equals(node.properties().get("kind"))) {
            try { var data=json.readTree((String)node.properties().get("dataJson"));
                if (!canonical.contains(data.path("canonicalId").asText())) throw new Failure(400,"ARCHIVE_PROOF_CANONICAL_MISSING");
                proofs.put((String)node.properties().get("id"),data);
            } catch (java.io.IOException invalid) { throw new Failure(400,"ARCHIVE_DOCUMENT_INVALID"); }
        }
        for (var node:nodes) if (node.properties().containsKey("identityProofId") && (!proofs.containsKey(node.properties().get("identityProofId"))
                || !node.properties().get("canonicalId").equals(proofs.get(node.properties().get("identityProofId")).path("canonicalId").asText()))) throw new Failure(400,"ARCHIVE_IDENTITY_PROOF_MISSING");
        var externalKeys=new HashSet<List<Object>>(); var referenceKeys=List.of("canonicalId","system","targetInstanceId","resourceKind","resourceId");
        for (var node:nodes) if (node.label().equals("IntegrationExternalBinding")) externalKeys.add(referenceKeys.stream().map(node.properties()::get).toList());
        for (var node:nodes) if (node.label().equals("IntegrationModelBinding") && !externalKeys.contains(referenceKeys.stream().map(node.properties()::get).toList())) throw new Failure(400,"ARCHIVE_EXTERNAL_REFERENCE_MISSING");
    }
    public void restore(String instance,List<Node> nodes) {
        validate(instance,nodes,json);
        try (var session=driver.session()) {
            session.executeWrite(tx -> {
                if (tx.run("MATCH (n {instanceId:$instance}) WHERE n:IntegrationModelBinding OR n:IntegrationPublicationIdentity OR n:IntegrationIdentityClaim OR (n:IntegrationStateDocument AND NOT (n.kind='OPERATIONS' AND n.id='control')) RETURN n LIMIT 1",Map.of("instance",instance)).hasNext()) throw new Failure(409,"ARCHIVE_DESTINATION_NOT_EMPTY");
                var control=tx.run("MATCH (d:IntegrationStateDocument {instanceId:$instance,kind:'OPERATIONS',id:'control'}) SET d.fence=coalesce(d.fence,0)+1 RETURN d.dataJson AS data",Map.of("instance",instance));
                try { if (!control.hasNext() || !json.readTree(control.single().get("data").asString()).path("paused").asBoolean()) throw new Failure(409,"ARCHIVE_REQUIRES_PAUSE"); }
                catch (java.io.IOException corrupt) { throw new Failure(409,"ARCHIVE_REQUIRES_PAUSE"); }
                if (tx.run("MATCH (c:IntegrationCanonicalObject {registryInstanceId:$instance}) RETURN c LIMIT 1",Map.of("instance",instance)).hasNext()) throw new Failure(409,"ARCHIVE_DESTINATION_NOT_EMPTY");
                String restoredGeneration="restored:"+UUID.randomUUID();
                for (var node:nodes) {
                    var p=new HashMap<>(node.properties()); String label=node.label();
                    if (label.equals("IntegrationStateDocument")) {
                        try {
                            var data=(ObjectNode)json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree((String)p.get("dataJson")); String kind=(String)p.get("kind");
                            if (kind.equals("MODEL_SCOPE")) data.put("paused",true).put("restored",true);
                            if (Set.of("JOB","PUBLICATION_JOB","CENTRAL_EDIT","INBOUND_POLICY","PUBLICATION_POLICY").contains(kind) && data.has("modelGeneration")) {
                                data.set("archivedModelGeneration",data.get("modelGeneration")); data.put("modelGeneration",restoredGeneration).put("restoredCapability",true);
                            }
                            if (kind.equals("INBOUND_POLICY")) data.put("enabled",false);
                            if (kind.equals("PUBLICATION_POLICY") && data.path("policy").isObject()) { data.set("archivedPolicy",data.path("policy").deepCopy()); ((ObjectNode)data.path("policy")).put("mode","DISABLED"); }
                            if (kind.equals("RECONCILIATION")) data.put("state","PAUSED").put("code","RESTORED_SCAN_REQUIRES_NEW_REQUEST");
                            p.put("dataJson",data.toString()); p.put("operationState",data.path("state").asText("UNRECORDED"));
                        } catch (java.io.IOException corrupt) { throw new Failure(400,"ARCHIVE_DOCUMENT_INVALID"); }
                    }
                    String where=KEYS.get(label).stream().map(k -> "n."+k+"=$p."+k).collect(java.util.stream.Collectors.joining(" AND "));
                    var old=tx.run("MATCH (n:"+label+") WHERE "+where+" RETURN properties(n) AS p",Map.of("p",p));
                    if (old.hasNext()) {
                        var prior=new HashMap<>(old.single().get("p").asMap()); prior.remove("fence"); var compare=new HashMap<>(p); compare.remove("fence");
                        if (p.containsKey("instanceId") || !prior.equals(compare)) throw new Failure(409,"ARCHIVE_GLOBAL_IDENTITY_CONFLICT");
                    } else tx.run("CREATE (n:"+label+") SET n=$p",Map.of("p",p)).consume();
                }
                return null;
            });
        }
    }
}
