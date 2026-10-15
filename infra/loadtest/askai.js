// On-demand recommendations: CLIENTS test users each send one Ask AI request at the same moment, and wait
// for its answer. The worker takes one job at a time, so this measures how the wait grows with the
// number of requests queued at once (about one model run per request ahead in the queue).
//
//   k6 run -e BASE_URL=https://<ip> -e PASSWORD=... -e CLIENTS=3 askai.js      (just askai <label> 3)
//
// Each user asks a different question, and the same question with an unchanged profile is answered from
// the earlier answer (200 at once). So repeat a run with another PROMPT_OFFSET, or the second run measures
// nothing: the summary counts those as "reused".
import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { API, BASE, authHeaders, int, jsonSummary } from './common.js';

const CLIENTS = int(__ENV.CLIENTS, 1);
const OFFSET = int(__ENV.PROMPT_OFFSET, 0);
const WAIT_MINUTES = int(__ENV.WAIT_MINUTES, 90);

const PROMPTS = [
  'a horror movie from the 80s', 'feel-good comedies about friendship', 'a heist movie with a clever twist',
  'science fiction about time travel', 'an animated movie for a family evening', 'a war drama based on a true story',
  'a romantic comedy set in New York', 'a detective mystery from the 1970s', 'movies about artificial intelligence',
  'a western with a great soundtrack', 'a sports movie about an underdog team', 'a thriller set on a train',
];

const turnaround = new Trend('askai_turnaround', true);
const outcomes = new Counter('askai');

export const options = {
  insecureSkipTLSVerify: true,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)'],
  thresholds: {
    askai_turnaround: ['max>=0'],
    'askai{outcome:done}': ['count>=0'],
    'askai{outcome:failed}': ['count>=0'],
    'askai{outcome:reused}': ['count>=0'],
    'askai{outcome:timeout}': ['count>=0'],
  },
  scenarios: {
    askai: { executor: 'per-vu-iterations', vus: CLIENTS, iterations: 1, maxDuration: `${WAIT_MINUTES + 5}m` },
  },
};

export default function () {
  const i = exec.vu.idInTest; // loadtest-001 ... loadtest-<CLIENTS>
  const prompt = PROMPTS[(i - 1 + OFFSET) % PROMPTS.length];
  const json = { 'Content-Type': 'application/json' };
  const started = Date.now();
  const res = http.post(`${API.recommendations}/recommendations/requests`, JSON.stringify({ prompt }),
    { headers: { ...authHeaders(i), ...json }, tags: { name: 'ask' } });
  if (res.status === 200) {
    outcomes.add(1, { outcome: 'reused' });
    console.log(`user ${i}: "${prompt}" was answered before (200): not measured`);
    return;
  }
  if (res.status !== 202) {
    outcomes.add(1, { outcome: 'failed' });
    console.error(`user ${i}: POST answered ${res.status}: ${res.body}`);
    return;
  }
  const id = res.json('id');
  const deadline = started + WAIT_MINUTES * 60000;
  while (Date.now() < deadline) {
    sleep(10);
    const r = http.get(`${API.recommendations}/recommendations/requests/${id}`,
      { headers: authHeaders(i), tags: { name: 'poll' } });
    const status = r.status === 200 ? r.json('status') : 'PENDING';
    if (status === 'DONE' || status === 'FAILED') {
      const seconds = (Date.now() - started) / 1000;
      turnaround.add(Date.now() - started);
      outcomes.add(1, { outcome: status === 'DONE' ? 'done' : 'failed' });
      console.log(`user ${i}: "${prompt}" ${status} after ${seconds.toFixed(0)} s ${r.json('error') || ''}`);
      return;
    }
  }
  outcomes.add(1, { outcome: 'timeout' });
  console.error(`user ${i}: "${prompt}" still pending after ${WAIT_MINUTES} minutes`);
}

export function handleSummary(data) {
  const t = (data.metrics.askai_turnaround || { values: {} }).values;
  const s = (x) => (x === undefined ? '-' : (x / 1000).toFixed(0));
  const line = `askai clients=${CLIENTS} turnaround min=${s(t.min)}s avg=${s(t.avg)}s max=${s(t.max)}s`;
  return jsonSummary({ script: 'askai', base: BASE, clients: CLIENTS, offset: OFFSET,
    label: __ENV.LABEL || '' }, data, line);
}
