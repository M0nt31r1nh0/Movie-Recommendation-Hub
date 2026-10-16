// Gets the test users ready for mix.js, once after `just setup`:
//   1. loadtest-001 logs in, which creates its profile, and asks for Recommended for you. The model makes
//      that first set (a few minutes on the CPUs); this waits until it is READY.
//   2. Every other test user logs in once and asks too. Their profiles are empty like loadtest-001's, so
//      they have the same fingerprint and get a copy of its set at once, without the model.
// Afterwards every test user has a profile and a cached set, so the measured run never waits on the model.
//
//   k6 run -e BASE_URL=https://<ip> -e PASSWORD=... -e USERS=200 prepare.js      (just prepare)
import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { API, BASE, USERS, int, jsonSummary, login } from './common.js';

const WAIT_MINUTES = int(__ENV.WAIT_MINUTES, 40);
const statuses = new Counter('prepared');

export const options = {
  insecureSkipTLSVerify: true,
  setupTimeout: `${WAIT_MINUTES + 2}m`,
  thresholds: {
    'prepared{status:READY}': ['count>=0'],
    'prepared{status:PREPARING}': ['count>=0'],
    'prepared{status:FAILED}': ['count>=0'],
    'prepared{status:ERROR}': ['count>=0'],
  },
  scenarios: {
    rest: { executor: 'shared-iterations', vus: 10, iterations: Math.max(USERS - 1, 1), maxDuration: '30m' },
  },
};

function prepare(i) {
  const headers = { Authorization: `Bearer ${login(i).access}` };
  const params = { headers, tags: { name: 'prepare' } };
  const profile = http.get(`${API.profiles}/profiles/me`, params);
  if (profile.status !== 200) return { status: 'ERROR', detail: `profiles/me ${profile.status}` };
  const recs = http.get(`${API.recommendations}/recommendations/me`, params);
  if (recs.status !== 200) return { status: 'ERROR', detail: `recommendations/me ${recs.status}` };
  return { status: recs.json('status'), detail: recs.json('error') || '' };
}

export function setup() {
  const deadline = Date.now() + WAIT_MINUTES * 60000;
  let r = prepare(1);
  console.log(`loadtest-001: ${r.status} ${r.detail}`);
  while (r.status === 'PREPARING' && Date.now() < deadline) {
    sleep(15);
    r = prepare(1);
  }
  console.log(`loadtest-001: ${r.status} ${r.detail}`);
  if (r.status !== 'READY') {
    throw new Error(`loadtest-001's first set is ${r.status} (${r.detail}): check the worker and Ollama`);
  }
}

export default function () {
  if (USERS < 2) return;
  const i = exec.scenario.iterationInTest + 2;
  const r = prepare(i);
  statuses.add(1, { status: r.status });
  if (r.status !== 'READY') console.warn(`user ${i}: ${r.status} ${r.detail}`);
}

export function handleSummary(data) {
  const count = (s) => ((data.metrics[`prepared{status:${s}}`] || { values: { count: 0 } }).values.count);
  const line = `prepared ${USERS} users: loadtest-001 READY, then READY=${count('READY')} ` +
    `PREPARING=${count('PREPARING')} FAILED=${count('FAILED')} ERROR=${count('ERROR')}`;
  return jsonSummary({ script: 'prepare', base: BASE, users: USERS }, data, line);
}
