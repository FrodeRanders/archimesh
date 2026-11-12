package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.gautelis.federation.*;
import org.gautelis.federation.Identity.*;
import org.neo4j.driver.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.DocumentKind;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;

/** Trusted owner primitive. The host holds its writer lease/coordination and verifies approval/source/readback. */
final class Neo4jTargetMutationLedger implements TargetMutationLedger {
    private final Driver driver; private final ObjectMapper json; private final String owner;
    private record Document(String id,long version,ObjectNode data) { }
    Neo4jTargetMutationLedger(Driver driver,ObjectMapper json,String owner) { this.driver=driver; this.json=json; this.owner=IntegrationIdentity.required(owner); }
    private String lane(Request request) { return TargetMutationLedger.laneId(request.canonical().id(),request.target().repository(),request.target().kind()); }
    private Map<String,Object> parameters(DocumentKind kind,String id) { return Map.of("owner",owner,"kind",kind.name(),"id",id); }
    private Optional<Document> load(TransactionContext tx,DocumentKind kind,String id) {
        var rows=tx.run("MATCH (d:IntegrationStateDocument {instanceId:$owner,kind:$kind,id:$id}) RETURN d.version AS version,d.dataJson AS data",parameters(kind,id));
        if (!rows.hasNext()) return Optional.empty(); var row=rows.single();
        try { return Optional.of(new Document(id,row.get("version").asLong(),(ObjectNode)json.readTree(row.get("data").asString()))); }
        catch (Exception invalid) { throw new Failure(409,"FEDERATION_INTENT_CORRUPT"); }
    }
    private Document save(TransactionContext tx,DocumentKind kind,String id,long version,ObjectNode data) {
        var p=new HashMap<>(parameters(kind,id)); p.put("version",version); p.put("data",data.toString()); p.put("state",data.path("state").asText());
        var rows=tx.run("""
            MERGE (d:IntegrationStateDocument {instanceId:$owner,kind:$kind,id:$id}) ON CREATE SET d.version=0
            SET d.fence=coalesce(d.fence,0)+1 WITH d WHERE d.version=$version
            SET d.version=d.version+1,d.dataJson=$data,d.operationState=$state,d.updatedAt=timestamp()
            RETURN d.version AS version
            """,p);
        if (!rows.hasNext()) throw new Failure(409,"FEDERATION_INTENT_STALE"); return new Document(id,rows.single().get("version").asLong(),data);
    }
    static Intent decode(String id,long version,JsonNode data,ObjectMapper json) {
        try {
            var request=json.treeToValue(data.path("request"),Request.class);
            if (!TargetMutationLedger.intentId(request.id()).equals(id) || !request.canonical().id().equals(data.path("canonicalId").asText())
                    || !PublicationJson.digest(json.valueToTree(request)).equals(data.path("fingerprint").asText())) throw new IllegalArgumentException();
            return new Intent(request,version,State.valueOf(data.path("state").asText()),data.has("attempt") ? data.path("attempt").asText() : null,
                              data.has("verifiedNativeId") ? data.path("verifiedNativeId").asText() : null);
        } catch (Exception invalid) { throw new Failure(409,"FEDERATION_INTENT_CORRUPT"); }
    }
    private Intent intent(Document document) { return decode(document.id(),document.version(),document.data(),json); }
    private Document required(TransactionContext tx,String id,long version) {
        var document=load(tx,DocumentKind.FEDERATION_INTENT,TargetMutationLedger.intentId(id)).orElseThrow(() -> new Failure(409,"FEDERATION_INTENT_REQUIRED"));
        if (document.version()!=version) throw new Failure(409,"FEDERATION_INTENT_STALE"); return document;
    }
    private void lockCanonical(TransactionContext tx,Request request) {
        var c=tx.run("MATCH (c:IntegrationCanonicalObject {canonicalId:$canonical}) SET c.fence=coalesce(c.fence,0)+1 RETURN c.registryInstanceId AS owner,c.semanticKind AS semantic",
                    Map.of("canonical",request.canonical().id()));
        if (!c.hasNext()) throw new Failure(409,"CANONICAL_IDENTITY_REQUIRED"); var value=c.single();
        if (!owner.equals(value.get("owner").asString(null)) || !request.canonical().semanticKind().equals(value.get("semantic").asString(null))) throw new Failure(409,"FEDERATION_IDENTITY_OWNER_OR_KIND");
    }
    private void running(TransactionContext tx) {
        var control=load(tx,DocumentKind.OPERATIONS,"control");
        if (control.isPresent() && control.get().data().path("paused").asBoolean()) throw new Failure(409,"INTEGRATION_PAUSED");
    }
    private void legacyAllowed(TransactionContext tx,Request request) {
        if (!request.target().repository().system().equals("turboea")) return;
        String target=request.target().repository().instance(), canonical=request.canonical().id();
        for (var item:List.of(Map.entry(DocumentKind.CENTRAL_LANE,CentralEditModel.laneId(target,canonical)),
                              Map.entry(DocumentKind.PUBLICATION_RESOURCE,PublicationJson.key("pub-resource:",target,canonical))))
            if (load(tx,item.getKey(),item.getValue()).map(document -> document.data().has("inFlight")).orElse(false)) throw new Failure(409,"LEGACY_MUTATION_OUTCOME_UNCERTAIN");
    }
    private void targetCompatible(TransactionContext tx,Request request) {
        var slot=request.target();
        var key=Neo4jFederationRegistry.key(new Resource(slot.repository(),slot.kind(),"slot"));
        var p=Map.<String,Object>of("canonical",request.canonical().id(),"system",slot.repository().system(),"target",slot.repository().instance(),"kind",key.resourceKind().name());
        var existing=tx.run("MATCH (e:IntegrationExternalBinding {canonicalId:$canonical,system:$system,targetInstanceId:$target,resourceKind:$kind}) RETURN e.nativeId AS nativeId,e.resourceId AS resourceId,e.externalType AS externalType",p);
        if (!existing.hasNext()) { if (request.action()==Action.UPDATE) throw new Failure(409,"TARGET_BINDING_REQUIRED"); return; }
        var binding=existing.single(); String nativeId=key.resourceKind()==IntegrationIdentity.ResourceKind.ENTITY ? binding.get("nativeId").asString() : binding.get("resourceId").asString();
        if (request.action()==Action.CREATE) throw new Failure(409,"TARGET_REPRESENTATION_EXISTS");
        if (!request.knownNativeId().equals(nativeId) || !slot.externalType().equals(binding.get("externalType").asString(null))) throw new Failure(409,"TARGET_IDENTITY_OR_TYPE_DRIFT");
    }
    private void active(TransactionContext tx,Request request) {
        var lane=load(tx,DocumentKind.FEDERATION_LANE,lane(request)).orElseThrow(() -> new Failure(409,"FEDERATION_LANE_REQUIRED"));
        if (!TargetMutationLedger.intentId(request.id()).equals(lane.data().path("activeIntent").asText())) throw new Failure(409,"FEDERATION_LANE_CONFLICT");
    }
    private void release(TransactionContext tx,Request request,String verifiedNativeId) {
        var lane=load(tx,DocumentKind.FEDERATION_LANE,lane(request)).orElseThrow();
        active(tx,request); var data=lane.data().deepCopy(); data.remove("activeIntent"); data.put("state","IDLE").put("lastIntent",TargetMutationLedger.intentId(request.id()));
        if (verifiedNativeId!=null) data.put("verifiedNativeId",verifiedNativeId).put("lastVerifiedIntent",TargetMutationLedger.intentId(request.id()));
        save(tx,DocumentKind.FEDERATION_LANE,lane.id(),lane.version(),data);
    }
    @Override public Optional<Intent> find(String id) {
        try (var session=driver.session()) { return session.executeRead(tx -> load(tx,DocumentKind.FEDERATION_INTENT,TargetMutationLedger.intentId(id)).map(this::intent)); }
    }
    @Override public Intent reserve(Request request) {
        Objects.requireNonNull(request);
        try (var session=driver.session()) { return session.executeWrite(tx -> {
            lockCanonical(tx,request); running(tx);
            var prior=load(tx,DocumentKind.FEDERATION_INTENT,TargetMutationLedger.intentId(request.id()));
            if (prior.isPresent()) { var known=intent(prior.get()); if (!known.request().equals(request)) throw new Failure(409,"FEDERATION_INTENT_ID_REUSED"); return known; }
            legacyAllowed(tx,request); targetCompatible(tx,request);
            var lane=load(tx,DocumentKind.FEDERATION_LANE,lane(request));
            if (lane.isPresent() && (!lane.get().data().path("state").asText().equals("IDLE") || lane.get().data().has("activeIntent"))) throw new Failure(409,"FEDERATION_TARGET_BUSY");
            if (lane.isPresent() && lane.get().data().has("verifiedNativeId") && (request.action()==Action.CREATE || !request.knownNativeId().equals(lane.get().data().path("verifiedNativeId").asText()))) throw new Failure(409,"TARGET_BINDING_LOST_OR_REPLACEMENT_REQUIRES_REVIEW");
            var data=json.createObjectNode().put("canonicalId",request.canonical().id()).put("fingerprint",PublicationJson.digest(json.valueToTree(request))).put("state",State.PREPARED.name());
            data.set("request",json.valueToTree(request));
            var saved=save(tx,DocumentKind.FEDERATION_INTENT,TargetMutationLedger.intentId(request.id()),0,data);
            var laneData=(lane.isPresent() ? lane.get().data().deepCopy() : json.createObjectNode()).put("canonicalId",request.canonical().id()).put("system",request.target().repository().system())
                .put("targetInstanceId",request.target().repository().instance()).put("resourceKind",request.target().kind().name())
                .put("activeIntent",saved.id()).put("state","ACTIVE");
            save(tx,DocumentKind.FEDERATION_LANE,lane(request),lane.map(Document::version).orElse(0L),laneData);
            return intent(saved);
        }); }
    }
    @Override public Intent begin(String id,long version,String source,String approval) {
        try (var session=driver.session()) { return session.executeWrite(tx -> {
            var document=required(tx,id,version); var value=intent(document); var request=value.request();
            lockCanonical(tx,request); running(tx); active(tx,request); legacyAllowed(tx,request); targetCompatible(tx,request);
            if (value.state()!=State.PREPARED || request.action()==Action.ADOPT) throw new Failure(409,"FEDERATION_REDISPATCH_DENIED");
            if (!request.sourceDigest().equals(source) || !request.approvalDigest().equals(approval)) throw new Failure(409,"FEDERATION_REVIEW_CHANGED");
            var data=document.data().deepCopy().put("state",State.IN_FLIGHT.name()).put("attempt",UUID.randomUUID().toString());
            return intent(save(tx,DocumentKind.FEDERATION_INTENT,document.id(),version,data));
        }); }
    }
    @Override public Intent uncertain(String id,long version,String attempt) {
        try (var session=driver.session()) { return session.executeWrite(tx -> {
            var document=required(tx,id,version); var value=intent(document); lockCanonical(tx,value.request()); active(tx,value.request());
            if (value.state()!=State.IN_FLIGHT || !Objects.equals(attempt,value.attempt())) throw new Failure(409,"FEDERATION_ATTEMPT_MISMATCH");
            var data=document.data().deepCopy().put("state",State.UNCERTAIN.name());
            return intent(save(tx,DocumentKind.FEDERATION_INTENT,document.id(),version,data));
        }); }
    }
    @Override public Intent cancelPrepared(String id,long version) {
        try (var session=driver.session()) { return session.executeWrite(tx -> {
            var document=required(tx,id,version); var value=intent(document); lockCanonical(tx,value.request()); active(tx,value.request());
            if (value.state()!=State.PREPARED && value.state()!=State.QUARANTINED) throw new Failure(409,"FEDERATION_UNKNOWN_OUTCOME_REQUIRES_REVIEW");
            var data=document.data().deepCopy().put("state",State.CANCELLED.name());
            var saved=save(tx,DocumentKind.FEDERATION_INTENT,document.id(),version,data); release(tx,value.request(),null); return intent(saved);
        }); }
    }
    @Override public Intent verify(String id,long version,String attempt,Representation actual,IdentityRegistry.Evidence evidence) {
        Objects.requireNonNull(actual); Objects.requireNonNull(evidence);
        try (var session=driver.session()) { return session.executeWrite(tx -> {
            var document=load(tx,DocumentKind.FEDERATION_INTENT,TargetMutationLedger.intentId(id)).orElseThrow(() -> new Failure(409,"FEDERATION_INTENT_REQUIRED"));
            var value=intent(document); var request=value.request(); lockCanonical(tx,request);
            if (!request.target().repository().equals(actual.resource().repository()) || request.target().kind()!=actual.resource().kind()
                    || !request.target().externalType().equals(actual.externalType()) || !request.expectedReadbackDigest().equals(evidence.contentDigest())
                    || request.knownNativeId()!=null && !request.knownNativeId().equals(actual.resource().nativeId())) throw new Failure(409,"FEDERATION_READBACK_MISMATCH");
            if (value.state()==State.VERIFIED) {
                if (!actual.resource().nativeId().equals(value.verifiedNativeId()) || !json.valueToTree(evidence).equals(document.data().path("readback"))) throw new Failure(409,"FEDERATION_READBACK_MISMATCH");
                return value;
            }
            if (document.version()!=version) throw new Failure(409,"FEDERATION_INTENT_STALE"); active(tx,request);
            if (!(value.state()==State.IN_FLIGHT || value.state()==State.UNCERTAIN || value.state()==State.PREPARED && request.action()==Action.ADOPT)
                    || !Objects.equals(attempt,value.attempt())) throw new Failure(409,"FEDERATION_ATTEMPT_MISMATCH");
            var binding=new Neo4jFederationRegistry(driver,json,owner).writeInTransaction(tx,actual,request.canonical(),evidence,true);
            var data=document.data().deepCopy().put("state",State.VERIFIED.name()).put("verifiedNativeId",actual.resource().nativeId()).put("identityProofId",binding.proofId());
            data.set("readback",json.valueToTree(evidence)); var saved=save(tx,DocumentKind.FEDERATION_INTENT,document.id(),version,data); release(tx,request,actual.resource().nativeId()); return intent(saved);
        }); }
    }
}
