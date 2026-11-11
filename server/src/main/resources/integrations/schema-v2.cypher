// Publication-issued canonical identities are independent of model projection and external resource creation.
CREATE CONSTRAINT integration_publication_entity IF NOT EXISTS
FOR (n:IntegrationPublicationIdentity) REQUIRE (n.instanceId,n.sourceModelId,n.entityKind,n.entityId) IS UNIQUE;

CREATE CONSTRAINT integration_entity_identity_claim IF NOT EXISTS
FOR (n:IntegrationIdentityClaim) REQUIRE (n.instanceId,n.sourceModelId,n.entityKind,n.entityId) IS UNIQUE;

CREATE INDEX integration_documents_scan IF NOT EXISTS
FOR (n:IntegrationStateDocument) ON (n.instanceId,n.kind,n.id);
