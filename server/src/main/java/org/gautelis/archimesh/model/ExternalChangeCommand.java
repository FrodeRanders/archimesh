package org.gautelis.archimesh.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.gautelis.archimesh.integration.IntegrationIdentity;

/** Trusted server-generated observation. No transport decodes this as a SubmitOps actor or operation. */
public record ExternalChangeCommand(String instanceId,String targetKey,String targetInstanceId,String canonicalId,String resourceId,
                                    String modelId,String ref,String elementId,String archimateType,String mappingVersion,
                                    long policyVersion,String opBatchId,long expectedRevision,JsonNode expectedLocal,JsonNode remote,String proposalId) {
    public ExternalChangeCommand(String instanceId,String targetKey,String targetInstanceId,String canonicalId,String resourceId,String modelId,String ref,String elementId,String archimateType,String mappingVersion,long policyVersion,String opBatchId,long expectedRevision,JsonNode expectedLocal,JsonNode remote) {
        this(instanceId,targetKey,targetInstanceId,canonicalId,resourceId,modelId,ref,elementId,archimateType,mappingVersion,policyVersion,opBatchId,expectedRevision,expectedLocal,remote,null);
    }
    public ExternalChangeCommand {
        if (proposalId!=null) IntegrationIdentity.required(proposalId);
        for (String value:new String[]{instanceId,targetKey,targetInstanceId,canonicalId,resourceId,modelId,ref,elementId,archimateType,mappingVersion,opBatchId}) IntegrationIdentity.required(value);
        if (expectedRevision<0 || policyVersion<1 || !elementId.startsWith("elem:") || expectedLocal==null || !expectedLocal.isObject()
                || remote==null || !remote.isObject() || remote.isEmpty() || remote.size()>32 || expectedLocal.size()>32
                || expectedLocal.toString().length()>65536 || remote.toString().length()>65536) throw new IllegalArgumentException("Invalid external change command");
        expectedLocal=expectedLocal.deepCopy(); remote=remote.deepCopy();
    }
    @Override public JsonNode expectedLocal() { return expectedLocal.deepCopy(); }
    @Override public JsonNode remote() { return remote.deepCopy(); }
}
