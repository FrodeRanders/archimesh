// Integration metadata is independent of model projection/checkpoint/export state.
// Never add modelId properties or relationships to model projection nodes here.
CREATE CONSTRAINT integration_canonical_id IF NOT EXISTS
FOR (n:IntegrationCanonicalObject) REQUIRE n.canonicalId IS UNIQUE;

CREATE CONSTRAINT integration_external_resource IF NOT EXISTS
FOR (n:IntegrationExternalBinding) REQUIRE (n.system, n.targetInstanceId, n.resourceKind, n.resourceId) IS UNIQUE;

CREATE CONSTRAINT integration_canonical_target IF NOT EXISTS
FOR (n:IntegrationExternalBinding) REQUIRE (n.canonicalId, n.system, n.targetInstanceId, n.resourceKind) IS UNIQUE;

CREATE CONSTRAINT integration_model_entity IF NOT EXISTS
FOR (n:IntegrationModelBinding) REQUIRE (n.instanceId, n.sourceModelId, n.entityKind, n.entityId) IS UNIQUE;

CREATE CONSTRAINT integration_model_canonical IF NOT EXISTS
FOR (n:IntegrationModelBinding) REQUIRE (n.instanceId, n.sourceModelId, n.entityKind, n.canonicalId) IS UNIQUE;

CREATE CONSTRAINT integration_model_batch IF NOT EXISTS
FOR (n:IntegrationModelBinding) REQUIRE (n.instanceId, n.sourceModelId, n.opBatchId) IS UNIQUE;

CREATE CONSTRAINT integration_state_document IF NOT EXISTS
FOR (n:IntegrationStateDocument) REQUIRE (n.instanceId, n.kind, n.id) IS UNIQUE;
