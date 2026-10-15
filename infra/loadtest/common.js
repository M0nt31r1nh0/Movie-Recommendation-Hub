// Shared by the k6 scripts: where the APIs are, which test user a virtual user is, and its access token.
//
// In the cluster everything is behind NGINX at BASE_URL (/api/..., /auth/...). Locally the APIs have
// their own ports, so each address can be set on its own (see README.md, "Running it locally").
import http from 'k6/http';
import exec from 'k6/execution';

export const BASE = (__ENV.BASE_URL || '').replace(/\/$/, '');
const KEYCLOAK = (__ENV.KEYCLOAK_URL || `${BASE}/auth`).replace(/\/$/, '');
export const API = {
  movies: (__ENV.MOVIES_API || `${BASE}/api`).replace(/\/$/, ''),
  profiles: (__ENV.PROFILES_API || `${BASE}/api`).replace(/\/$/, ''),
  recommendations: (__ENV.RECOMMENDATIONS_API || `${BASE}/api`).replace(/\/$/, ''),
};
if (!BASE && !__ENV.MOVIES_API) {
  throw new Error('Set BASE_URL, e.g. -e BASE_URL=https://34.90.1.2');
}

const REALM = __ENV.REALM || 'movies';
const CLIENT_ID = __ENV.CLIENT_ID || 'movies-loadtest';
const PASSWORD = __ENV.PASSWORD;
export const PREFIX = __ENV.USER_PREFIX || 'loadtest-';
export const USERS = int(__ENV.USERS, 200);

export function int(value, fallback) {
  const n = parseInt(value, 10);
  return Number.isNaN(n) ? fallback : n;
}

export function float(value, fallback) {
  const n = parseFloat(value);
  return Number.isNaN(n) ? fallback : n;
}

export function userName(i) {
  return `${PREFIX}${String(i).padStart(3, '0')}`;
}

// The test user of this virtual user: VU 1 is loadtest-001, and so on, wrapping around after USERS.
export function userIndex() {
  return ((exec.vu.idInTest - 1) % USERS) + 1;
}

// One token per virtual user (module state is per VU in k6). Token calls are tagged phase=auth, so they
// never count in the measured results.
let session = null;

function tokenRequest(form, name) {
  const res = http.post(`${KEYCLOAK}/realms/${REALM}/protocol/openid-connect/token`, form,
    { tags: { name, phase: 'auth' } });
  if (res.status !== 200) {
    throw new Error(`${name} failed with ${res.status}: ${res.body}`);
  }
  const t = res.json();
  const now = Date.now();
  return {
    user: form.username,
    access: t.access_token,
    refresh: t.refresh_token,
    expiresAt: now + t.expires_in * 1000,
    refreshExpiresAt: t.refresh_expires_in ? now + t.refresh_expires_in * 1000 : Infinity,
  };
}

export function login(i) {
  if (!PASSWORD) {
    throw new Error('Set PASSWORD, the test users\' password (written by users.py to .secrets/password)');
  }
  session = tokenRequest({ grant_type: 'password', client_id: CLIENT_ID, username: userName(i),
    password: PASSWORD, scope: 'openid' }, 'login');
  session.user = userName(i);
  return session;
}

// The Authorization header for user i: logs in the first time, and renews the token a minute before it
// expires (access tokens live 5 minutes), with the refresh token while that is still valid.
export function authHeaders(i) {
  const now = Date.now();
  if (!session || session.user !== userName(i) || now > session.refreshExpiresAt - 30000) {
    login(i);
  } else if (now > session.expiresAt - 60000) {
    try {
      const renewed = tokenRequest({ grant_type: 'refresh_token', client_id: CLIENT_ID,
        refresh_token: session.refresh }, 'refresh');
      renewed.user = session.user;
      session = renewed;
    } catch (e) {
      login(i);
    }
  }
  return { Authorization: `Bearer ${session.access}` };
}

// Prints the JSON summary between markers, so run.sh can cut it out of k6's output.
export function jsonSummary(meta, data, line) {
  const body = JSON.stringify({ meta, state: data.state, metrics: data.metrics });
  return { stdout: `\n${line}\n===K6-JSON-BEGIN===\n${body}\n===K6-JSON-END===\n` };
}
