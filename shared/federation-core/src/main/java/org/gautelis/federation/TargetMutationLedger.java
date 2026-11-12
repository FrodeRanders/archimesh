package org.gautelis.federation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.gautelis.federation.Identity.*;

/** Trusted-owner persistence port. Digests are not approval, source verification or remote write credentials. */
public interface TargetMutationLedger {
    enum Action { CREATE, UPDATE, ADOPT }
    enum State { PREPARED, IN_FLIGHT, UNCERTAIN, VERIFIED, CANCELLED, QUARANTINED }
    record Slot(Repository repository,ResourceKind kind,String externalType) {
        public Slot { Objects.requireNonNull(repository); Objects.requireNonNull(kind); text(externalType,512); }
    }
    record Request(String id,Canonical canonical,Slot target,Action action,String knownNativeId,
                   String payloadDigest,String expectedReadbackDigest,String sourceDigest,String approvalDigest,String actor) {
        public Request {
            text(id,512); Objects.requireNonNull(canonical); Objects.requireNonNull(target); Objects.requireNonNull(action); text(actor,512);
            if ((action==Action.CREATE)!=(knownNativeId==null)) throw new IllegalArgumentException("Create requires a new target; update/adoption require an exact native ID");
            if (knownNativeId!=null) new Resource(target.repository(),target.kind(),knownNativeId);
            digest(payloadDigest); digest(expectedReadbackDigest); digest(sourceDigest); digest(approvalDigest);
        }
    }
    record Intent(Request request,long version,State state,String attempt,String verifiedNativeId) {
        public Intent {
            Objects.requireNonNull(request); Objects.requireNonNull(state); if (version<1) throw new IllegalArgumentException("Invalid intent version");
            if (attempt!=null) text(attempt,512); if (verifiedNativeId!=null) text(verifiedNativeId,8192);
            boolean dispatched=state==State.IN_FLIGHT || state==State.UNCERTAIN || state==State.VERIFIED && request.action()!=Action.ADOPT;
            if (dispatched!=(attempt!=null) || (state==State.VERIFIED)!=(verifiedNativeId!=null)
                    || request.action()==Action.ADOPT && (state==State.IN_FLIGHT || state==State.UNCERTAIN)
                    || verifiedNativeId!=null && request.knownNativeId()!=null && !verifiedNativeId.equals(request.knownNativeId())) throw new IllegalArgumentException("Inconsistent intent state");
        }
    }
    Intent reserve(Request request);
    Optional<Intent> find(String id);
    /** Commit the durable fence before any non-idempotent HTTP call. Never redispatch IN_FLIGHT/UNCERTAIN. */
    Intent begin(String id,long expectedVersion,String currentSourceDigest,String currentApprovalDigest);
    Intent uncertain(String id,long expectedVersion,String attempt);
    /** Host has independently verified identity, owned values and reviewed equivalence; commit binding and fence release atomically. */
    Intent verify(String id,long expectedVersion,String attempt,Representation actual,IdentityRegistry.Evidence readback);
    /** Only before dispatch; quarantined prepared work may be explicitly cancelled, never resumed as approval. */
    Intent cancelPrepared(String id,long expectedVersion);

    static String laneId(String canonical,Repository target,ResourceKind kind) {
        text(canonical,2048); Objects.requireNonNull(target); Objects.requireNonNull(kind);
        return "federation-lane:"+hash(canonical+"\0"+target.system()+"\0"+target.instance()+"\0"+kind.name());
    }
    static String intentId(String id) { text(id,512); return "federation-intent:"+hash(id); }
    private static void digest(String value) { if (value==null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid mutation digest"); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
