package org.gautelis.archimesh.integration;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.locks.ReentrantLock;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.DocumentKind;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;

/** One server writer lease, one shared remote-write lock, plus durable uncertainty fences across restarts. */
@ApplicationScoped
public class RemoteMutationCoordinator {
    public interface Maintenance extends AutoCloseable { @Override void close(); }
    private final ReentrantLock lock=new ReentrantLock();
    public void lock() { lock.lock(); }
    public boolean tryLock() { return lock.tryLock(); }
    public void unlock() { lock.unlock(); }
    public void requirePublicationAllowed(IntegrationRuntime runtime,String target,String canonical) {
        requirePublicationAllowed(runtime,target,canonical,org.gautelis.federation.Identity.ResourceKind.OBJECT);
    }
    public void requirePublicationAllowed(IntegrationRuntime runtime,String target,String canonical,org.gautelis.federation.Identity.ResourceKind kind) {
        requireFederationAllowed(runtime,target,canonical,kind);
        var lane=runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.CENTRAL_LANE,CentralEditModel.laneId(target,canonical));
        if (lane.isPresent() && lane.get().data().has("inFlight")) throw new Failure(409,"CENTRAL_EDIT_OUTCOME_UNCERTAIN");
    }
    public void requireCentralAllowed(IntegrationRuntime runtime,String target,String canonical) {
        requireFederationAllowed(runtime,target,canonical,org.gautelis.federation.Identity.ResourceKind.OBJECT);
        var publication=runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.PUBLICATION_RESOURCE,PublicationJson.key("pub-resource:",target,canonical));
        if (publication.isPresent() && publication.get().data().has("inFlight")) throw new Failure(409,"PUBLICATION_OUTCOME_UNCERTAIN");
    }
    private void requireFederationAllowed(IntegrationRuntime runtime,String target,String canonical,org.gautelis.federation.Identity.ResourceKind kind) {
        String id=org.gautelis.federation.TargetMutationLedger.laneId(canonical,new org.gautelis.federation.Identity.Repository("turboea",target),kind);
        var lane=runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.FEDERATION_LANE,id);
        if (lane.isPresent() && (!lane.get().data().path("state").asText().equals("IDLE") || lane.get().data().has("activeIntent"))) throw new Failure(409,"FEDERATION_TARGET_BUSY");
        if (lane.isPresent() && lane.get().data().has("verifiedNativeId")) {
            var nativeKind=kind==org.gautelis.federation.Identity.ResourceKind.OBJECT ? IntegrationIdentity.ResourceKind.CARD : IntegrationIdentity.ResourceKind.RELATION;
            var binding=runtime.metadata().findExternal(canonical,"turboea",target,nativeKind);
            if (binding.isEmpty() || !binding.get().resourceId().equals(lane.get().data().path("verifiedNativeId").asText())) throw new Failure(409,"FEDERATION_TARGET_BINDING_DRIFT");
        }
    }
}
