package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.gautelis.archimesh.auth.AuthorizationSubject;
import org.gautelis.archimesh.model.ExternalChangeCommand;
import java.util.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.*;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;

/** A verified central proposal grants only its exact fields/values on bindings of the same canonical resource. */
@ApplicationScoped
public class CentralProposalGrant {
    @Inject IntegrationRuntime runtime;
    @Inject CentralEditAuthorization authorization;
    public StateDocument proof(String id,String target,String canonical,String resource) {
        var job=runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.CENTRAL_EDIT,id).orElseThrow(() -> new Failure(403,"CENTRAL_PROPOSAL_PROOF_REQUIRED"));
        var data=job.data(); runtime.requireGeneration(data.path("sourceModelId").asText(),data.path("modelGeneration").asText(null)); var settings=runtime.settings().targets().stream().filter(t -> t.key().equals(target)).findFirst().orElseThrow();
        if (!data.path("remoteVerified").asBoolean() || !data.path("targetKey").asText().equals(target) || !data.path("canonicalId").asText().equals(canonical)
                || !data.path("resourceId").asText().equals(resource) || !data.path("scopeDigest").asText().equals(CentralEditAuthorization.digest(settings))) throw new Failure(403,"CENTRAL_PROPOSAL_SCOPE_DENIED");
        if (!CentralFields.shared(settings,data.path("type").asText(),CentralFields.keys(data.path("proposed"))).isEmpty())
            authorization.require(data.path("sourceModelId").asText(),target,new AuthorizationSubject(data.path("approvedBy").asText(),Set.of()),true);
        return job;
    }
    public Map<String,org.gautelis.archimesh.integration.turboea.TurboEaMapping.FieldMapping> fields(IntegrationConfiguration.Target target,String type,StateDocument job) {
        var result=new TreeMap<>(InboundFields.normalMappings(target,type));
        var editable=CentralFields.mappings(target,type);
        for (String field:CentralFields.shared(target,type,CentralFields.keys(job.data().path("proposed")))) result.put(field,editable.get(field));
        if (result.isEmpty() || result.size()>32) throw new Failure(403,"CENTRAL_PROPOSAL_FIELDS_DENIED");
        return result;
    }
    public Set<String> commandFields(ExternalChangeCommand command,IntegrationConfiguration.Target target,String type) {
        var job=proof(command.proposalId(),command.targetKey(),command.canonicalId(),command.resourceId());
        var local=CentralFields.localRepresentation(new com.fasterxml.jackson.databind.ObjectMapper(),job.data().path("proposed"));
        for (String field:CentralFields.keys(local)) if (!PublicationJson.equal(local.path(field),command.remote().path(field))) throw new Failure(403,"CENTRAL_PROPOSAL_VALUE_DENIED");
        return fields(target,type,job).keySet();
    }
    public boolean current(String id) {
        var job=runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.CENTRAL_EDIT,id);
        if (job.isEmpty() || !job.get().data().path("remoteVerified").asBoolean() || !Set.of("REMOTE_VERIFIED","SYNCING","PARTIAL","SYNCED","RETRY").contains(job.get().data().path("state").asText())) return false;
        var lane=runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.CENTRAL_LANE,CentralEditModel.laneId(job.get().data().path("targetInstanceId").asText(),job.get().data().path("canonicalId").asText()));
        return lane.isPresent() && lane.get().data().path("jobId").asText().equals(id) && !lane.get().data().has("inFlight");
    }
}
