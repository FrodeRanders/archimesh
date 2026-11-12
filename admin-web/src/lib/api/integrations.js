import { fetchJson, postJson } from '$lib/api/client.js';
const root = '/admin/integrations';
export const integrationOverview = () => fetchJson(root, { action: 'Integration operations' });
export const integrationDocuments = (kind, after = '') => fetchJson(`${root}/documents/${encodeURIComponent(kind)}?after=${encodeURIComponent(after)}`, { action: 'Integration diagnostics' });
export const integrationAction = (action, body = {}) => postJson(`${root}/${action}`, body, 'Integration operation');
export const integrationModelReview = model => fetchJson(`${root}/models/${encodeURIComponent(model)}/review`, { action: 'Review model attachment' });
export const integrationModelAction = (model, action, body) => postJson(`${root}/models/${encodeURIComponent(model)}/${action}`, body, 'Model integration operation');
