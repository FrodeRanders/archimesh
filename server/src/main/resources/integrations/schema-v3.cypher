// Legacy models receive an incarnation token once; replacement creates a new token.
// The token is intentionally excluded from model exports and checkpoints.
MATCH (m:Model) WHERE m.registered=true AND m.integrationGeneration IS NULL
SET m.integrationGeneration=randomUUID();
