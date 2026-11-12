package org.gautelis.archimesh.integration;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.locks.ReentrantLock;
import static org.gautelis.archimesh.integration.IntegrationStateRepository.DocumentKind;
import static org.gautelis.archimesh.integration.PublicationModel.Failure;

/** One server writer lease, one shared remote-write lock, plus durable uncertainty fences across restarts. */
@ApplicationScoped
public class RemoteMutationCoordinator {
    private final ReentrantLock lock=new ReentrantLock();
    public boolean tryLock() { return lock.tryLock(); }
    public void unlock() { lock.unlock(); }
    public void requirePublicationAllowed(IntegrationRuntime runtime,String target,String canonical) {
        var lane=runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.CENTRAL_LANE,CentralEditModel.laneId(target,canonical));
        if (lane.isPresent() && lane.get().data().has("inFlight")) throw new Failure(409,"CENTRAL_EDIT_OUTCOME_UNCERTAIN");
    }
    public void requireCentralAllowed(IntegrationRuntime runtime,String target,String canonical) {
        var publication=runtime.metadata().load(runtime.settings().instanceId(),DocumentKind.PUBLICATION_RESOURCE,PublicationJson.key("pub-resource:",target,canonical));
        if (publication.isPresent() && publication.get().data().has("inFlight")) throw new Failure(409,"PUBLICATION_OUTCOME_UNCERTAIN");
    }
}
