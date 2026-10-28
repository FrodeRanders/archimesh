import { fetchJson } from '$lib/api/client.js';

export const fetchOverview = (limit = 25) => fetchJson(`/admin/overview?limit=${limit}`, { action: 'Overview' });
export const fetchModelWindow = (modelId, limit = 25) => fetchJson(`/admin/models/${encodeURIComponent(modelId)}/window?limit=${limit}`, { action: 'Model window' });
export const fetchModelAcl = (modelId) => fetchJson(`/admin/models/${encodeURIComponent(modelId)}/acl`, { action: 'Model ACL' });
export const saveModelAcl = (modelId, payload) => fetchJson(`/admin/models/${encodeURIComponent(modelId)}/acl`, {
  method: 'PUT',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(payload),
  action: 'Save ACL'
});
export const fetchModelTags = (modelId) => fetchJson(`/admin/models/${encodeURIComponent(modelId)}/tags`, { action: 'Model tags' });
export const createModelTag = (modelId, tagName, description) => fetchJson(
  `/admin/models/${encodeURIComponent(modelId)}/tags?tagName=${encodeURIComponent(tagName)}&description=${encodeURIComponent(description || '')}`,
  { method: 'POST', action: 'Create tag' }
);
export const deleteModelTag = (modelId, tagName) => fetchJson(
  `/admin/models/${encodeURIComponent(modelId)}/tags/${encodeURIComponent(tagName)}`,
  { method: 'DELETE', action: 'Delete tag' }
);
export const exportModelPackage = (modelId) => fetchJson(`/admin/models/${encodeURIComponent(modelId)}/export`, { action: 'Export model' });
export const importModelPackage = (payload, overwrite) => fetchJson(`/admin/models/import?overwrite=${overwrite ? 'true' : 'false'}`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(payload),
  action: 'Import model'
});
export const fetchAuditConfig = () => fetchJson('/admin/audit/config', { action: 'Audit config' });
export const createModel = (modelId, modelName) => fetchJson(
  `/admin/models/${encodeURIComponent(modelId)}?modelName=${encodeURIComponent(modelName || '')}`,
  { method: 'POST', action: 'Create model' }
);
export const renameModel = (modelId, modelName) => fetchJson(
  `/admin/models/${encodeURIComponent(modelId)}?modelName=${encodeURIComponent(modelName || '')}`,
  { method: 'PUT', action: 'Rename model' }
);
export const rebuildModel = (modelId) => fetchJson(
  `/admin/models/${encodeURIComponent(modelId)}/rebuild-and-status`,
  { method: 'POST', action: 'Rebuild model' }
);
export const compactModel = (modelId, retainRevisions, dryRun = false) => fetchJson(
  `/admin/models/${encodeURIComponent(modelId)}/compact?retainRevisions=${encodeURIComponent(retainRevisions)}&dryRun=${dryRun}`,
  { method: 'POST', action: 'Compact model' }
);
export const deleteModel = (modelId, force) => fetchJson(
  `/admin/models/${encodeURIComponent(modelId)}?force=${force ? 'true' : 'false'}`,
  { method: 'DELETE', action: 'Delete model' }
);

export const fetchModelCheckpoint = (modelId) => fetchJson(
  `/admin/models/${encodeURIComponent(modelId)}/checkpoints/latest`,
  { action: 'Checkpoint status', allowNotFound: true }
);
export const createModelCheckpoint = (modelId) => fetchJson(
  `/admin/models/${encodeURIComponent(modelId)}/checkpoints`,
  { method: 'POST', action: 'Create checkpoint' }
);
