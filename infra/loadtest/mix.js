// The measured load: CLIENTS virtual users in a closed loop, each logged in as its own test user, sending
// one request (or two) from a weighted mix and then thinking for THINK seconds on average.
//
//   k6 run -e BASE_URL=https://<ip> -e PASSWORD=... -e CLIENTS=50 mix.js      (run.sh does this)
//
// Timeline: the clients ramp up over RAMP seconds, the first WARMUP seconds are warm-up, the next STEADY
// seconds are measured. Every request is tagged phase=warmup|steady|drain and name=<endpoint>, and the
// summary keeps the steady-state numbers apart (the thresholds below exist only to make k6 report them).
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { API, BASE, PREFIX, authHeaders, float, int, jsonSummary, login, userIndex } from './common.js';

const CLIENTS = int(__ENV.CLIENTS, 10);
const WARMUP = int(__ENV.WARMUP, 60);
const STEADY = int(__ENV.STEADY, 180);
const RAMP = Math.min(int(__ENV.RAMP, 30), WARMUP);
const THINK = float(__ENV.THINK, 1.0);

// Percent of iterations of each kind. "profile" reads a profile and its watched movies (2 requests),
// "write" marks a movie as watched and unmarks it (2 requests, so the profile ends as it began and the
// background agent has nothing to recompute), "recommendations" reads the cached Recommended for you.
const WEIGHTS = { browse: 35, search: 25, movie: 10, profile: 15, write: 5, recommendations: 10 };
const ENDPOINTS = ['browse', 'search', 'movie', 'profile', 'watched', 'mark', 'unmark', 'recommendations'];

const SEARCHES = ['batman', 'star wa', 'godfater', 'love', 'dark knight', 'amelie', 'space', 'war',
  'christmas', 'zombie', 'detective', 'incep', 'toy story', '"new york"', 'alien or predator',
  'heist -casino', 'pirates', 'high school', 'vampire', 'robot', 'paris', 'dog', 'the', 'jaws'];

const notReady = new Counter('recommendations_not_ready');

const thresholds = {
  'http_req_duration{phase:steady}': ['max>=0'],
  'http_reqs{phase:steady}': ['count>=0'],
  'http_req_failed{phase:steady}': ['rate>=0'],
  'iteration_duration{phase:steady}': ['max>=0'],
};
for (const e of ENDPOINTS) {
  thresholds[`http_req_duration{phase:steady,name:${e}}`] = ['max>=0'];
  thresholds[`http_reqs{phase:steady,name:${e}}`] = ['count>=0'];
  thresholds[`http_req_failed{phase:steady,name:${e}}`] = ['rate>=0'];
}

export const options = {
  insecureSkipTLSVerify: true, // the deployment's certificate is self-signed
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(95)', 'p(99)'],
  setupTimeout: '120s',
  thresholds,
  scenarios: {
    mix: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: `${RAMP}s`, target: CLIENTS },
        { duration: `${WARMUP - RAMP + STEADY}s`, target: CLIENTS },
      ],
      gracefulRampDown: '0s',
      gracefulStop: '30s',
    },
  },
};

// Once, before the clients start: the movie ids and test profiles the clients pick from.
export function setup() {
  const headers = { Authorization: `Bearer ${login(1).access}` };
  const params = { headers, tags: { name: 'setup', phase: 'setup' } };
  const movieIds = [];
  for (let page = 0; page < 10; page++) {
    const res = http.get(`${API.movies}/movies?page=${page}&size=100`, params);
    if (res.status !== 200) throw new Error(`GET /movies answered ${res.status}: ${res.body}`);
    for (const m of res.json('items')) movieIds.push(m.id);
  }
  const profileIds = [];
  for (let page = 0; page < 20; page++) {
    const res = http.get(`${API.profiles}/profiles?q=${encodeURIComponent(PREFIX)}&page=${page}&size=100`, params);
    if (res.status !== 200) throw new Error(`GET /profiles answered ${res.status}: ${res.body}`);
    const items = res.json('items');
    for (const p of items) profileIds.push(p.id);
    if (items.length < 100) break;
  }
  if (profileIds.length === 0) throw new Error('No test profiles: run `just prepare` first');
  return { movieIds, profileIds };
}

function phase() {
  const t = (Date.now() - exec.scenario.startTime) / 1000;
  if (t < WARMUP) return 'warmup';
  return t <= WARMUP + STEADY ? 'steady' : 'drain';
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

function ok(res) {
  return check(res, { '2xx': (r) => r.status >= 200 && r.status < 300 });
}

export default function (data) {
  const headers = authHeaders(userIndex());
  const p = phase();
  const tagged = (name) => ({ headers, tags: { name, phase: p } });

  let r = Math.random() * 100;
  const kind = Object.keys(WEIGHTS).find((k) => (r -= WEIGHTS[k]) < 0) || 'browse';

  if (kind === 'browse') {
    ok(http.get(`${API.movies}/movies?page=${Math.floor(Math.random() * 100)}&size=24`, tagged('browse')));
  } else if (kind === 'search') {
    ok(http.get(`${API.movies}/movies/search?q=${encodeURIComponent(pick(SEARCHES))}&size=24`, tagged('search')));
  } else if (kind === 'movie') {
    ok(http.get(`${API.movies}/movies/${pick(data.movieIds)}`, tagged('movie')));
  } else if (kind === 'profile') {
    const id = pick(data.profileIds);
    ok(http.get(`${API.profiles}/profiles/${id}`, tagged('profile')));
    ok(http.get(`${API.profiles}/profiles/${id}/watched?size=24`, tagged('watched')));
  } else if (kind === 'write') {
    const movie = pick(data.movieIds);
    ok(http.put(`${API.profiles}/profiles/me/watched/${movie}`, null, tagged('mark')));
    ok(http.del(`${API.profiles}/profiles/me/watched/${movie}`, null, tagged('unmark')));
  } else {
    const res = http.get(`${API.recommendations}/recommendations/me`, tagged('recommendations'));
    if (ok(res) && res.json('status') !== 'READY') notReady.add(1);
  }

  sleep(THINK * (0.5 + Math.random()));
}

export function handleSummary(data) {
  const m = data.metrics;
  const d = (m['http_req_duration{phase:steady}'] || { values: {} }).values;
  const n = (m['http_reqs{phase:steady}'] || { values: { count: 0 } }).values.count;
  const failed = (m['http_req_failed{phase:steady}'] || { values: { rate: 0 } }).values.rate;
  const f = (x) => (x === undefined ? '-' : x.toFixed(1));
  const line = `clients=${CLIENTS} req/s=${(n / STEADY).toFixed(1)} p50=${f(d['p(50)'])}ms ` +
    `p95=${f(d['p(95)'])}ms p99=${f(d['p(99)'])}ms failed=${(failed * 100).toFixed(2)}%`;
  const meta = { script: 'mix', base: BASE, clients: CLIENTS, warmup: WARMUP, steady: STEADY, ramp: RAMP,
    think: THINK, weights: WEIGHTS, label: __ENV.LABEL || '', repeat: int(__ENV.REPEAT, 1) };
  return jsonSummary(meta, data, line);
}
