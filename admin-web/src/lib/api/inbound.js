import { fetchJson, postJson } from '$lib/api/client.js';
const root = (model, target) => `/admin/models/${encodeURIComponent(model)}/inbound${target ? `/${encodeURIComponent(target)}` : ''}`;
export const inboundTargets = model => fetchJson(root(model), { action: 'Inbound targets' });
export const inboundStatus = (model, target, after = '') => fetchJson(`${root(model, target)}?after=${encodeURIComponent(after)}`, { action: 'Inbound status' });
export const approveInbound = (model, target, body) => postJson(`${root(model, target)}/policy`, body, 'Approve inbound policy');
export const refreshInbound = (model, target, element) => postJson(`${root(model, target)}/refresh/${encodeURIComponent(element)}`, {}, 'Reconcile inbound binding');
export const inspectInbound = (model, target, element) => fetchJson(`${root(model, target)}/resource/${encodeURIComponent(element)}`, { action: 'Inspect inbound values' });
export const acceptInbound = (model, target, body) => postJson(`${root(model, target)}/accept-remote`, body, 'Accept central values into HEAD');
