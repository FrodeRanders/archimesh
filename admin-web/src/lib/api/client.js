import { get } from 'svelte/store';
import { authRoles, authToken, authUiConfig, authUser } from '$lib/stores/auth.js';

export const safe = (value) => (value === undefined || value === null ? '' : String(value));

const currentHeaders = (extra = {}) => {
  const config = get(authUiConfig);
  const token = get(authToken).trim();
  const user = get(authUser).trim();
  const roles = get(authRoles).trim();
  const headers = { ...extra };
  if (!config.authorizationEnabled) {
    return headers;
  }
  if (config.identityMode === 'oidc' && token) {
    headers.Authorization = `Bearer ${token}`;
  } else if (config.identityMode === 'bootstrap' && user) {
    headers['X-Archimesh-User'] = user;
  }
  if (config.identityMode === 'bootstrap' && roles) {
    headers['X-Archimesh-Roles'] = roles;
  }
  return headers;
};

const usingBearerToken = () => {
  const config = get(authUiConfig);
  return config.identityMode === 'oidc' && Boolean(get(authToken).trim());
};

export const describeHttpFailure = (action, status, detail) => {
  const suffix = detail ? `: ${detail}` : '';
  if (status === 401) {
    return usingBearerToken()
      ? `${action} failed: HTTP 401 (bearer token missing, invalid, or expired)${suffix}`
      : `${action} failed: HTTP 401 (authentication required)${suffix}`;
  }
  if (status === 403) {
    return usingBearerToken()
      ? `${action} failed: HTTP 403 (token accepted but missing required roles or model access)${suffix}`
      : `${action} failed: HTTP 403 (request forbidden)${suffix}`;
  }
  return `${action} failed: HTTP ${status}${suffix}`;
};

export const fetchJson = async (url, options = {}) => {
  const response = await fetch(url, {
    ...options,
    headers: currentHeaders(options.headers || {})
  });
  if (response.status === 404 && options.allowNotFound) return null;
  if (!response.ok) {
    const detail = (await response.text()).trim();
    throw new Error(describeHttpFailure(options.action || 'Request', response.status, detail || response.statusText));
  }
  if (response.status === 204) {
    return null;
  }
  return response.json();
};

export const postJson = async (url, body, action) => fetchJson(url, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
  action
});
