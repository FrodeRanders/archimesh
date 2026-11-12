package org.gautelis.archimesh.integration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.gautelis.archimesh.auth.*;
import org.gautelis.archimesh.service.ArchimeshService;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;

@ApplicationScoped
public class CentralEditAuthorization {
    @Inject IntegrationRuntime runtime;
    @Inject ArchimeshService models;
    @Inject AuthorizationService authorization;
    public IntegrationConfiguration.Target require(String model,String key,AuthorizationSubject subject,boolean approving) {
        if (!runtime.enabled()) throw new Failure(404,"INTEGRATION_DISABLED");
        authorization.requireSubjectAllowed(subject,approving ? AuthorizationAction.ADMIN_MODEL_CENTRAL_EDIT : AuthorizationAction.MODEL_SUBMIT_OPS,model,"HEAD");
        var target=runtime.settings().targets().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow(() -> new Failure(404,"TARGET_NOT_FOUND"));
        var acl=models.findModelAccessControl(model).orElseThrow(() -> new Failure(404,"MODEL_NOT_FOUND"));
        if (!target.centralEditAccess().enabled() || !(approving ? target.centralEditAccess().approvers() : target.centralEditAccess().requesters()).contains(subject.userId())
                || !(acl.adminUsers().contains(subject.userId()) || !approving && acl.writerUsers().contains(subject.userId()))) throw new Failure(403,"CENTRAL_EDIT_ACCESS_DENIED");
        return target;
    }
    public IntegrationConfiguration.Target read(String model,String key,AuthorizationSubject subject) {
        try { return require(model,key,subject,false); }
        catch (Failure denied) { if (denied.status()!=403) throw denied; return require(model,key,subject,true); }
    }
    public static String digest(IntegrationConfiguration.Target target) {
        var edit=target.centralEditAccess(); var inbound=target.inboundAccess();
        return PublicationJson.digest(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(java.util.List.of(target.instanceId(),target.mapping(),
                java.util.List.of(edit.enabled(),new java.util.TreeSet<>(edit.requesters()),new java.util.TreeSet<>(edit.approvers()),new java.util.TreeSet<>(edit.cardTypes()),edit.fields(),edit.acceptUnconditionalWriteRisk(),edit.completeAttributeReadsApproved()),
                java.util.List.of(inbound.enabled(),new java.util.TreeSet<>(inbound.administrators()),new java.util.TreeSet<>(inbound.cardTypes()),new java.util.TreeSet<>(inbound.fields())))));
    }
}
