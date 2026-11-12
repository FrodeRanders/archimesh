package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.gautelis.archimesh.model.ExternalChangeCommand;
import java.util.*;
import static org.gautelis.archimesh.integration.IntegrationIdentity.*;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;

/** Database-only policy check before normal LWW merge. Actor/client strings never grant a bypass. */
@ApplicationScoped
public class ExternalFieldGuard {
    @Inject IntegrationRuntime runtime;
    @Inject InboundAuthorization access;
    @Inject CentralProposalGrant grants;

    public Optional<String> rejectLocal(String model,JsonNode ops) {
        if (!runtime.enabled() || runtime.settings().targets().stream().noneMatch(t -> t.inboundAccess().enabled())) return Optional.empty();
        try {
            var targets=new HashMap<String,IntegrationConfiguration.Target>();
            runtime.settings().targets().forEach(t -> { if (t.inboundAccess().enabled()) targets.put(t.instanceId(),t); });
            var bindings=new HashMap<String,Optional<ReservedImport>>();
            for (var op:ops) {
                String type=op.path("type").asText();
                String id=switch(type) {
                    case "CreateElement" -> op.path("element").path("id").asText();
                    case "UpdateElement" -> op.path("elementId").asText();
                    case "SetProperty","UnsetProperty","AddPropertySetMember","RemovePropertySetMember" -> op.path("targetId").asText();
                    default -> "";
                };
                if (!id.startsWith("elem:")) continue;
                var binding=bindings.computeIfAbsent(id,k -> runtime.metadata().findBinding(new ModelElementKey(runtime.settings().instanceId(),model,EntityKind.ELEMENT,k)));
                if (binding.isEmpty()) continue;
                var target=targets.get(binding.get().external().targetInstanceId());
                if (target==null) continue;
                var protectedKeys=new HashSet<String>(); protectedKeys.add(ExternalObjectCatalogService.CANONICAL_PROPERTY);
                target.mapping().fields().getOrDefault(binding.get().canonical().kind(),Map.of()).forEach((local,field) -> {
                    if (field.authority()==InformationAuthorityPolicy.Authority.TURBO_EA_MASTER || field.authority()==InformationAuthorityPolicy.Authority.SHARED_PROPOSAL)
                        protectedKeys.add(local.startsWith("property:") ? local.substring(9) : local);
                });
                if (type.equals("CreateElement") || type.equals("UpdateElement") && (op.path("patch").has("archimateType")
                        || protectedKeys.stream().anyMatch(op.path("patch")::has)) || protectedKeys.contains(op.path("key").asText())) return Optional.of("EXTERNAL_FIELD_OWNED");
            }
            return Optional.empty();
        } catch (RuntimeException unavailable) { return Optional.of("EXTERNAL_POLICY_UNAVAILABLE"); }
    }

    public boolean currentProposal(String id) { return grants.current(id); }

    /** Validate the exact model/entity/field capability; no generic system-admin credential is created. */
    public record Capability(IntegrationStateRepository.StateDocument policy,ReservedImport binding) { }
    public Capability authorize(ExternalChangeCommand command) {
        if (!runtime.enabled() || !runtime.settings().instanceId().equals(command.instanceId()) || !"HEAD".equals(command.ref())) throw new Failure(403,"EXTERNAL_CHANGE_SCOPE_DENIED");
        runtime.requireGeneration(command.modelId(),command.modelGeneration());
        var target=access.target(command.targetKey());
        if (!target.instanceId().equals(command.targetInstanceId()) || !target.mapping().version().equals(command.mappingVersion())) throw new Failure(409,"EXTERNAL_CHANGE_MAPPING_CHANGED");
        var binding=runtime.metadata().findBinding(new ModelElementKey(command.instanceId(),command.modelId(),EntityKind.ELEMENT,command.elementId())).orElseThrow(() -> new Failure(409,"BINDING_NOT_FOUND"));
        if (binding.state()!=BindingState.ACTIVE || !binding.canonical().canonicalId().equals(command.canonicalId()) || !binding.external().resourceId().equals(command.resourceId())
                || !binding.external().targetInstanceId().equals(command.targetInstanceId()) || !binding.archimateType().equals(command.archimateType())
                || !binding.mappingVersion().equals(command.mappingVersion())) throw new Failure(409,"EXTERNAL_CHANGE_BINDING_DRIFT");
        var fields=command.proposalId()==null ? InboundFields.mappings(target,binding.canonical().kind()).keySet() : grants.commandFields(command,target,binding.canonical().kind());
        var expected=new HashSet<String>(); command.expectedLocal().fieldNames().forEachRemaining(expected::add);
        var remote=new HashSet<String>(); command.remote().fieldNames().forEachRemaining(remote::add);
        if (!fields.equals(expected) || !fields.equals(remote)) throw new Failure(403,"EXTERNAL_CHANGE_FIELDS_DENIED");
        for (String field:fields) {
            var value=command.remote().path(field);
            if (!value.isTextual() && !(field.startsWith("property:") && value.isNull()) || value.isTextual() && (value.asText().length()>(field.equals("name") ? 512 : 16384) || value.asText().indexOf('\0')>=0)
                    || field.equals("name") && value.asText().isBlank()) throw new Failure(409,"EXTERNAL_CHANGE_VALUE_INVALID");
        }
        return new Capability(access.capability(command.modelId(),command.targetKey()),binding);
    }
}
