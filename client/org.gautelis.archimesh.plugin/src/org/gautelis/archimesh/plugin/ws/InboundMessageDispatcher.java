package org.gautelis.archimesh.plugin.ws;

import java.util.ArrayList;
import java.util.List;

import org.gautelis.archimesh.plugin.ArchimeshPlugin;
import org.gautelis.archimesh.plugin.emf.RemoteOpApplier;
import org.gautelis.archimesh.plugin.util.SimpleJson;

/**
 * Dispatches server messages by envelope type.
 * JSON is handled as raw text for now to keep dependencies minimal.
 */
public class InboundMessageDispatcher {
    private static final int MAX_BUFFERED_ENVELOPES = 512;

    private final RemoteOpApplier remoteOpApplier;
    private final ArchimeshSessionManager sessionManager;
    private final List<String> bufferedMutatingEnvelopes = new ArrayList<>();

    public InboundMessageDispatcher(ArchimeshSessionManager sessionManager) {
        this.sessionManager = sessionManager;
        this.remoteOpApplier = new RemoteOpApplier(sessionManager);
    }

    public void dispatch(String envelopeJson) {
        String type = SimpleJson.readStringField(envelopeJson, "type");
        if(type == null) {
            ArchimeshPlugin.logInfo("Ignoring Archimesh message without type");
            return;
        }

        switch(type) {
            case "CheckoutSnapshot":
                if(bufferUntilModelAttached(envelopeJson, type)) {
                    break;
                }
                remoteOpApplier.applySnapshotEnvelope(envelopeJson);
                ArchimeshPlugin.logInfo("Received checkout payload type=" + type);
                break;
            case "CheckoutDelta":
                if(bufferUntilModelAttached(envelopeJson, type)) {
                    break;
                }
                remoteOpApplier.applyCheckoutDeltaEnvelope(envelopeJson);
                ArchimeshPlugin.logInfo("Received checkout payload type=" + type);
                break;
            case "OpsAccepted":
                String acceptedOpBatchId = readOpBatchId(envelopeJson);
                sessionManager.onServerOpsAccepted(acceptedOpBatchId);
                ArchimeshPlugin.logInfo("Received OpsAccepted");
                break;
            case "OpsBroadcast":
                // Broadcasts are mutating; if no model is attached yet, queue and replay in-order later
                if(bufferUntilModelAttached(envelopeJson, type)) {
                    break;
                }
                ArchimeshPlugin.logTrace("Dispatching OpsBroadcast: " + summarizeOpsBroadcast(envelopeJson));
                remoteOpApplier.applyOpsEnvelope(envelopeJson);
                break;
            case "LockEvent":
            case "PresenceBroadcast":
                ArchimeshPlugin.logInfo("Received Archimesh event type=" + type);
                break;
            case "Error":
                String errorCode = readErrorCode(envelopeJson);
                String errorMessage = readErrorMessage(envelopeJson);
                sessionManager.onServerError(errorCode, errorMessage,
                        SimpleJson.readStringField(SimpleJson.asJsonObject(SimpleJson.readRawField(envelopeJson, "payload")), "opBatchId"));
                ArchimeshPlugin.logInfo("Received Archimesh error payload code=" + errorCode + " message=" + errorMessage);
                break;
            default:
                ArchimeshPlugin.logInfo("Unhandled Archimesh message type=" + type);
                break;
        }
    }

    public void recordLocalOperations(String envelope) {
        if (sessionManager.getAttachedModel() != null) remoteOpApplier.recordLocalOperations(envelope);
    }

    public synchronized void replayBufferedMutationsIfAny() {
        if(sessionManager.getAttachedModel() == null || bufferedMutatingEnvelopes.isEmpty()) {
            return;
        }
        // Replay via normal dispatch path so revision hints/conflict handling stay consistent.
        List<String> replay = new ArrayList<>(bufferedMutatingEnvelopes);
        bufferedMutatingEnvelopes.clear();
        ArchimeshPlugin.logInfo("Replaying buffered Archimesh envelopes count=" + replay.size());
        for(String envelope : replay) {
            dispatch(envelope);
        }
    }

    private boolean bufferUntilModelAttached(String envelopeJson, String type) {
        if(sessionManager.getAttachedModel() != null) {
            return false;
        }
        synchronized(this) {
            if(bufferedMutatingEnvelopes.size() >= MAX_BUFFERED_ENVELOPES) {
                // Keep memory bounded during long attach delays
                bufferedMutatingEnvelopes.remove(0);
            }
            bufferedMutatingEnvelopes.add(envelopeJson);
        }
        ArchimeshPlugin.logInfo("Buffered Archimesh envelope type=" + type + " until model is attached");
        return true;
    }

    private String summarizeOpsBroadcast(String envelopeJson) {
        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(envelopeJson, "payload"));
        String opBatch = payload == null ? null : SimpleJson.asJsonObject(SimpleJson.readRawField(payload, "opBatch"));
        if(opBatch == null && payload != null && SimpleJson.readRawField(payload, "ops") != null) {
            opBatch = payload;
        }
        if(opBatch == null) {
            String rootBatch = SimpleJson.asJsonObject(SimpleJson.readRawField(envelopeJson, "opBatch"));
            if(rootBatch != null) {
                opBatch = rootBatch;
            }
        }
        if(opBatch == null) {
            return "payload/opBatch missing";
        }
        String opBatchId = SimpleJson.readStringField(opBatch, "opBatchId");
        String modelId = SimpleJson.readStringField(opBatch, "modelId");
        int opCount = SimpleJson.readArrayObjectElements(opBatch, "ops").size();
        return "modelId=" + modelId + " opBatchId=" + opBatchId + " opCount=" + opCount;
    }

    private String readOpBatchId(String envelopeJson) {
        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(envelopeJson, "payload"));
        if(payload == null) {
            return null;
        }
        return SimpleJson.readStringField(payload, "opBatchId");
    }

    private String readErrorCode(String envelopeJson) {
        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(envelopeJson, "payload"));
        if(payload == null) {
            return null;
        }
        return SimpleJson.readStringField(payload, "code");
    }

    private String readErrorMessage(String envelopeJson) {
        String payload = SimpleJson.asJsonObject(SimpleJson.readRawField(envelopeJson, "payload"));
        if(payload == null) {
            return null;
        }
        return SimpleJson.readStringField(payload, "message");
    }
}
