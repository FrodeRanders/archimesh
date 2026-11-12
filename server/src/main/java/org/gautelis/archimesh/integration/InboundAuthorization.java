package org.gautelis.archimesh.integration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.gautelis.archimesh.auth.*;
import org.gautelis.archimesh.service.ArchimeshService;
import java.util.*;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.*;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;

@ApplicationScoped
public class InboundAuthorization {
    @Inject IntegrationRuntime runtime;
    @Inject ArchimeshService models;
    @Inject AuthorizationService authorization;
    public IntegrationConfiguration.Target administrator(String model,String key,AuthorizationSubject subject) {
        if (!runtime.enabled()) throw new Failure(404,"INTEGRATION_DISABLED");
        authorization.requireSubjectAllowed(subject,AuthorizationAction.ADMIN_MODEL_INBOUND,model,"HEAD");
        var target=target(key);
        if (!target.inboundAccess().enabled() || !target.inboundAccess().administrators().contains(subject.userId())) throw new Failure(403,"INBOUND_ACCESS_DENIED");
        var acl=models.findModelAccessControl(model).orElseThrow(() -> new Failure(404,"MODEL_NOT_FOUND"));
        if (!acl.adminUsers().contains(subject.userId())) throw new Failure(403,"EXPLICIT_MODEL_ADMIN_APPROVAL_REQUIRED");
        return target;
    }
    public IntegrationConfiguration.Target target(String key) {
        return runtime.settings().targets().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow(() -> new Failure(404,"TARGET_NOT_FOUND"));
    }
    public StateDocument capability(String model,String key) {
        var policy=runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.INBOUND_POLICY,policyId(model,key)).orElseThrow(() -> new Failure(403,"INBOUND_POLICY_REQUIRED"));
        var target=administrator(model,key,new AuthorizationSubject(policy.data().path("approvedBy").asText(),Set.of()));
        runtime.requireGeneration(model,policy.data().path("modelGeneration").asText(null));
        if (!policy.data().path("enabled").asBoolean()) throw new Failure(403,"INBOUND_PAUSED");
        if (!target.instanceId().equals(policy.data().path("targetInstanceId").asText()) || !target.mapping().version().equals(policy.data().path("mappingVersion").asText())) throw new Failure(409,"INBOUND_MAPPING_OR_TARGET_MIGRATION_REQUIRED");
        if (!ExternalChangeIngestService.configurationDigest(target).equals(policy.data().path("configurationDigest").asText())) throw new Failure(409,"INBOUND_CONFIGURATION_CHANGED");
        return policy;
    }
    public static String policyId(String model,String target) { return PublicationJson.key("in-policy:",model,target); }
}
