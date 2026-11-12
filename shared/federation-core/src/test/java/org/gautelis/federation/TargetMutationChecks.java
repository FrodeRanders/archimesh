package org.gautelis.federation;

import static org.gautelis.federation.Identity.*;
import static org.gautelis.federation.TargetMutationLedger.*;

final class TargetMutationChecks {
    static void run() {
        var canonical=new Canonical("urn:test:dataset","catalog.dataset"); var repository=new Repository("turboea","test");
        var slot=new Slot(repository,ResourceKind.OBJECT,"DataObject"); String digest="a".repeat(64);
        var request=new Request("request",canonical,slot,Action.CREATE,null,digest,digest,digest,digest,"operator");
        if (!request.target().equals(slot) || laneId(canonical.id(),repository,ResourceKind.OBJECT).equals(laneId(canonical.id(),new Repository("datahub","test"),ResourceKind.OBJECT))) throw new AssertionError();
        rejects(() -> new Request("request",canonical,slot,Action.CREATE,"existing",digest,digest,digest,digest,"operator"));
        rejects(() -> new Request("request",canonical,slot,Action.UPDATE,null,digest,digest,digest,digest,"operator"));
        rejects(() -> new Request("request",canonical,slot,Action.ADOPT,"existing","invalid",digest,digest,digest,"operator"));
        rejects(() -> new Intent(request,0,State.PREPARED,null,null));
        rejects(() -> new Intent(request,1,State.IN_FLIGHT,null,null));
        rejects(() -> new Intent(request,1,State.VERIFIED,"attempt",null));
    }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected rejection"); }
}
