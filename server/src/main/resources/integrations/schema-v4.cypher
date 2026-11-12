// Neutral identity ownership is metadata only. This index does not classify or rewrite existing records.
CREATE INDEX integration_registry_owner IF NOT EXISTS
FOR (n:IntegrationCanonicalObject) ON (n.registryInstanceId);
