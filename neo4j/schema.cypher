/*
  Neo4j schema for Archi Cooperative Modelling (materialized state + op-log)
*/

CREATE CONSTRAINT model_id IF NOT EXISTS
FOR (m:Model) REQUIRE m.modelId IS UNIQUE;

CREATE CONSTRAINT archimesh_runtime_id IF NOT EXISTS
FOR (r:ArchimeshRuntime) REQUIRE r.id IS UNIQUE;

CREATE CONSTRAINT element_model_id IF NOT EXISTS
FOR (e:Element) REQUIRE (e.modelId, e.id) IS UNIQUE;

CREATE CONSTRAINT rel_model_id IF NOT EXISTS
FOR (r:Relationship) REQUIRE (r.modelId, r.id) IS UNIQUE;

CREATE CONSTRAINT view_model_id IF NOT EXISTS
FOR (v:View) REQUIRE (v.modelId, v.id) IS UNIQUE;

CREATE CONSTRAINT folder_model_id IF NOT EXISTS
FOR (f:Folder) REQUIRE (f.modelId, f.id) IS UNIQUE;

CREATE CONSTRAINT vo_model_id IF NOT EXISTS
FOR (o:ViewObject) REQUIRE (o.modelId, o.id) IS UNIQUE;

CREATE CONSTRAINT conn_model_id IF NOT EXISTS
FOR (c:Connection) REQUIRE (c.modelId, c.id) IS UNIQUE;

CREATE CONSTRAINT commit_model_batch IF NOT EXISTS
FOR (c:Commit) REQUIRE (c.modelId, c.opBatchId) IS UNIQUE;

CREATE CONSTRAINT model_tag_name IF NOT EXISTS
FOR (t:ModelTag) REQUIRE (t.modelId, t.tagName) IS UNIQUE;

CREATE CONSTRAINT model_checkpoint_revision IF NOT EXISTS
FOR (c:ModelCheckpoint) REQUIRE (c.modelId, c.revision) IS UNIQUE;

CREATE INDEX commit_model_rev IF NOT EXISTS
FOR (c:Commit) ON (c.modelId, c.revisionFrom, c.revisionTo);

/*
Suggested materialized relationships:
(:Model)-[:HAS_ELEMENT]->(:Element)
(:Model)-[:HAS_REL]->(:Relationship)
(:Relationship)-[:SOURCE]->(:Element)
(:Relationship)-[:TARGET]->(:Element)
(:Model)-[:HAS_FOLDER]->(:Folder)
(:Folder)-[:HAS_FOLDER]->(:Folder)
(:Folder)-[:CONTAINS_ELEMENT]->(:Element)
(:Folder)-[:CONTAINS_REL]->(:Relationship)
(:Folder)-[:CONTAINS_VIEW]->(:View)
(:Model)-[:HAS_VIEW]->(:View)
(:View)-[:CONTAINS]->(:ViewObject)
(:ViewObject)-[:REPRESENTS]->(:Element)
(:View)-[:CONTAINS]->(:Connection)
(:Connection)-[:REPRESENTS]->(:Relationship)
(:Connection)-[:FROM]->(:ViewObject)
(:Connection)-[:TO]->(:ViewObject)

Op-log:
(:Commit {modelId, revisionFrom, revisionTo, opBatchId, userId, sessionId, ts})
(:Commit)-[:HAS_OP]->(:Op {seq, type, targetId, payloadJson})
(:Commit)-[:NEXT]->(:Commit)
*/
