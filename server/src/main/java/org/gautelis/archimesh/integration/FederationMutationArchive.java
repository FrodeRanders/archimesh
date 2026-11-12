package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.gautelis.federation.TargetMutationLedger;
import static org.gautelis.federation.TargetMutationLedger.State;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;

/** Portable archive closure for pending write fences; a missing lane must never revive a create. */
final class FederationMutationArchive {
    private FederationMutationArchive() { }
    static void validate(String owner,List<IntegrationArchive.Node> nodes,ObjectMapper json) {
        var intents=new HashMap<String,TargetMutationLedger.Intent>(); var data=new HashMap<String,JsonNode>(); var lanes=new HashMap<String,JsonNode>();
        try {
            for (var node:nodes) {
                if (!node.label().equals("IntegrationStateDocument")) continue;
                String kind=(String)node.properties().get("kind"), id=(String)node.properties().get("id");
                if (kind.equals("FEDERATION_INTENT")) {
                    var value=json.readTree((String)node.properties().get("dataJson")); data.put(id,value);
                    intents.put(id,Neo4jTargetMutationLedger.decode(id,((Number)node.properties().get("version")).longValue(),value,json));
                } else if (kind.equals("FEDERATION_LANE")) lanes.put(id,json.readTree((String)node.properties().get("dataJson")));
            }
            for (var item:intents.entrySet()) {
                var intent=item.getValue(); var request=intent.request();
                String lane=TargetMutationLedger.laneId(request.canonical().id(),request.target().repository(),request.target().kind());
                boolean pending=Set.of(State.PREPARED,State.IN_FLIGHT,State.UNCERTAIN,State.QUARANTINED).contains(intent.state());
                if (pending && (!lanes.containsKey(lane) || !item.getKey().equals(lanes.get(lane).path("activeIntent").asText()))) throw new IllegalArgumentException();
                if (Set.of(State.IN_FLIGHT,State.UNCERTAIN).contains(intent.state()) && intent.attempt()==null) throw new IllegalArgumentException();
                if (Set.of(State.PREPARED,State.CANCELLED,State.QUARANTINED).contains(intent.state()) && intent.attempt()!=null) throw new IllegalArgumentException();
                if (intent.state()==State.VERIFIED && (intent.verifiedNativeId()==null || !request.expectedReadbackDigest().equals(data.get(item.getKey()).path("readback").path("contentDigest").asText()))) throw new IllegalArgumentException();
                if (intent.state()==State.VERIFIED) verified(nodes,intent,data.get(item.getKey()),json);
                boolean canonical=nodes.stream().anyMatch(node -> node.label().equals("IntegrationCanonicalObject")
                    && request.canonical().id().equals(node.properties().get("canonicalId")) && request.canonical().semanticKind().equals(node.properties().get("semanticKind")) && owner.equals(node.properties().get("registryInstanceId")));
                if (!canonical) throw new IllegalArgumentException();
            }
            for (var entry:lanes.entrySet()) {
                var lane=entry.getValue(); boolean active=lane.has("activeIntent"); String reference=lane.path(active ? "activeIntent" : "lastIntent").asText();
                var intent=intents.get(reference); if (intent==null || !lane.path("state").asText().equals(active ? "ACTIVE" : "IDLE")) throw new IllegalArgumentException();
                var request=intent.request();
                if (!entry.getKey().equals(TargetMutationLedger.laneId(request.canonical().id(),request.target().repository(),request.target().kind()))
                        || !request.canonical().id().equals(lane.path("canonicalId").asText()) || !request.target().repository().system().equals(lane.path("system").asText())
                        || !request.target().repository().instance().equals(lane.path("targetInstanceId").asText()) || !request.target().kind().name().equals(lane.path("resourceKind").asText())) throw new IllegalArgumentException();
                if (active && Set.of(State.VERIFIED,State.CANCELLED).contains(intent.state()) || !active && !Set.of(State.VERIFIED,State.CANCELLED).contains(intent.state())) throw new IllegalArgumentException();
                if (lane.has("verifiedNativeId") || lane.has("lastVerifiedIntent")) {
                    var verified=intents.get(lane.path("lastVerifiedIntent").asText());
                    if (verified==null || verified.state()!=State.VERIFIED || !lane.path("verifiedNativeId").asText().equals(verified.verifiedNativeId())
                            || !entry.getKey().equals(TargetMutationLedger.laneId(verified.request().canonical().id(),verified.request().target().repository(),verified.request().target().kind()))) throw new IllegalArgumentException();
                }
            }
        } catch (Exception invalid) { throw new Failure(400,"ARCHIVE_FEDERATION_FENCE_CLOSURE_INVALID"); }
    }
    private static void verified(List<IntegrationArchive.Node> nodes,TargetMutationLedger.Intent intent,JsonNode data,ObjectMapper json) throws Exception {
        var request=intent.request(); var slot=request.target();
        var representation=new org.gautelis.federation.Identity.Representation(new org.gautelis.federation.Identity.Resource(slot.repository(),slot.kind(),intent.verifiedNativeId()),slot.externalType());
        var key=Neo4jFederationRegistry.key(representation.resource());
        boolean found=nodes.stream().anyMatch(node -> node.label().equals("IntegrationExternalBinding") && request.canonical().id().equals(node.properties().get("canonicalId"))
            && slot.repository().system().equals(node.properties().get("system")) && slot.repository().instance().equals(node.properties().get("targetInstanceId"))
            && key.resourceKind().name().equals(node.properties().get("resourceKind")) && key.indexId().equals(node.properties().get("resourceId"))
            && slot.externalType().equals(node.properties().get("externalType")));
        if (!found || request.knownNativeId()!=null && !request.knownNativeId().equals(intent.verifiedNativeId())) throw new IllegalArgumentException();
        var evidence=json.treeToValue(data.path("readback"),org.gautelis.federation.IdentityRegistry.Evidence.class);
        String id="identity-proof:"+IntegrationIdentity.sha256(evidence.correlation());
        String fingerprint=PublicationJson.digest(json.valueToTree(List.of("BIND",representation,request.canonical(),evidence)));
        boolean proof=false, association=false;
        for (var node:nodes) if (node.label().equals("IntegrationStateDocument") && "IDENTITY_PROOF".equals(node.properties().get("kind"))) {
            if (id.equals(node.properties().get("id"))) {
                var value=json.readTree((String)node.properties().get("dataJson")); proof=fingerprint.equals(value.path("fingerprint").asText());
            }
            if (data.path("identityProofId").asText().equals(node.properties().get("id"))) {
                var value=json.readTree((String)node.properties().get("dataJson")); association=request.canonical().id().equals(value.path("canonicalId").asText());
            }
        }
        if (!proof || !association) throw new IllegalArgumentException();
    }
}
