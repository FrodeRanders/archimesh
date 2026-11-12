package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public final class CentralEditModel {
    private CentralEditModel() { }
    public record PreviewRequest(String ref,String elementId,Map<String,JsonNode> proposed) {
        public PreviewRequest { if (!"HEAD".equals(ref)) throw new IllegalArgumentException("Central edits require HEAD"); IntegrationIdentity.required(elementId); if (!elementId.startsWith("elem:") || proposed==null || proposed.isEmpty() || proposed.size()>8) throw new IllegalArgumentException("Choose one to eight central fields"); proposed=Map.copyOf(proposed); }
    }
    public record Request(String requestId,String ref,String elementId,Map<String,JsonNode> proposed,String previewDigest) { }
    public record Decision(long expectedVersion,boolean approve,String reviewDigest,boolean resolveDrift) { }
    public record Outcome(long expectedVersion) { }
    public record Field(String field,String remoteField,String valueType,String authority,String mode,JsonNode current,JsonNode local,JsonNode baseline,String currentText) { }
    public record Context(String targetKey,String elementId,String canonicalId,String resourceId,String type,String mappingVersion,List<Field> fields) { }
    public record Preview(Context context,Map<String,JsonNode> proposed,Map<String,String> proposedText,String mode,List<String> driftFields,String digest) { }
    public record Review(String id,long version,Preview preview,String state,boolean baseChanged,String digest) { }
    public record Job(String id,long version,String state,String code,String elementId,String canonicalId,String resourceId,String requestedBy,String approvedBy,String mode,boolean remoteVerified,JsonNode proposed,Map<String,String> proposedText,int synced,int pending,int conflicts,String sourceState) { }
    public record BindingPage(List<Map<String,String>> items,String nextCursor) { }
    public record Page(List<Job> jobs,String nextCursor) { }
    public static String laneId(String target,String canonical) { return PublicationJson.key("central-lane:",target,canonical); }
    public static String prefix(String model,String target) { return PublicationJson.key("central:",model,target)+":"; }
}
