import { fetchJson, postJson } from '$lib/api/client.js';
const root = (model, target) => `/admin/models/${encodeURIComponent(model)}/publication${target ? `/${encodeURIComponent(target)}` : ''}`;
export const publicationTargets = (model) => fetchJson(root(model), { action: 'Publication targets' });
export const publicationStatus = (model, target) => fetchJson(root(model, target), { action: 'Publication status' });
export const previewPublication = (model, target, policy) => postJson(`${root(model, target)}/preview`, policy, 'Preview publication');
export const approvePublication = (model, target, body) => postJson(`${root(model, target)}/policy`, body, 'Approve publication policy');
export const runPublication = (model, target, body) => postJson(`${root(model, target)}/run`, body, 'Queue publication');
export const inspectPublication = (model, target, canonical, resource) => fetchJson(`${root(model, target)}/resource?canonicalId=${encodeURIComponent(canonical)}${resource ? `&resourceId=${encodeURIComponent(resource)}` : ''}`, { action: 'Inspect remote baseline' });
export const adoptPublication = (model, target, body) => postJson(`${root(model, target)}/adopt-baseline`, body, 'Approve remote baseline');
