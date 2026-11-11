package org.gautelis.archimesh.integration;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Restricted publication writes. No hard deletion, approval endpoint, or blind retry contract. */
public interface ExternalRepositoryWriter extends ExternalRepositoryConnector {
    ExternalObject createObject(ObjectNode payload);
    ExternalObject patchObject(String resourceId,ObjectNode payload);
    ExternalRelation createRelation(String type,String sourceId,String targetId);
}
