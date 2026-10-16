// Realistic background activity, for the dashboards and the screenshots (not a measurement): a few users,
// each logged in as its own demo user, browse, search, open movies, mark some as watched, save some for
// later, pick favourite genres, follow each other, read their recommendations and, now and then, ask the AI.
// Unlike mix.js, their changes are kept, so profiles fill up and the background agent makes new sets.
//
//   k6 run -e BASE_URL=https://<ip> -e PASSWORD=... -e USER_PREFIX=demo- -e USERS=8 -e DURATION=10m activity.js
//   (just demo-setup, then just demo)
import http from 'k6/http';
import { sleep } from 'k6';
import { API, BASE, PREFIX, USERS, authHeaders, float, int, jsonSummary, login, userIndex } from './common.js';

const DURATION = __ENV.DURATION || '10m';
const THINK_MIN = float(__ENV.THINK_MIN, 3);
const THINK_MAX = float(__ENV.THINK_MAX, 8);
// How many users send one Ask AI request each during the run. Each takes the model minutes.
const ASK_AI = int(__ENV.ASK_AI, 3);

const SEARCHES = ['batman', 'star wars', 'godfater', 'toy story', 'amelie', 'space', 'christmas', 'zombie',
  'detective', 'incep', 'love', 'pirates', 'dark knight', 'jaws', 'alien', 'paris', 'heist', 'robot'];
const PROMPTS = ['a horror movie from the 80s', 'feel-good comedies about friendship',
  'science fiction about time travel', 'a heist movie with a clever twist', 'an animated movie for a family evening'];

export const options = {
  insecureSkipTLSVerify: true,
  setupTimeout: '120s',
  scenarios: { activity: { executor: 'constant-vus', vus: USERS, duration: DURATION, gracefulStop: '30s' } },
};

function json(headers) {
  return { ...headers, 'Content-Type': 'application/json' };
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

// Once: every demo user logs in (which creates its profile), and we collect the profile ids, some movie ids
// (popular ones, so the screenshots show recognisable titles) and the genre ids.
export function setup() {
  const profileIds = [];
  for (let i = 1; i <= USERS; i++) {
    const headers = { Authorization: `Bearer ${login(i).access}` };
    const me = http.get(`${API.profiles}/profiles/me`, { headers, tags: { name: 'setup' } });
    if (me.status !== 200) throw new Error(`profiles/me for user ${i} answered ${me.status}: ${me.body}`);
    profileIds.push(me.json('id'));
  }
  const headers = { Authorization: `Bearer ${login(1).access}` };
  const movieIds = [];
  for (const q of SEARCHES) {
    const res = http.get(`${API.movies}/movies/search?q=${encodeURIComponent(q)}&size=8`, { headers });
    if (res.status === 200) for (const m of res.json('items')) movieIds.push(m.id);
  }
  if (movieIds.length === 0) throw new Error('No movies found: is movies-service up?');
  const genreIds = http.get(`${API.movies}/genres`, { headers }).json().map((g) => g.id);
  return { profileIds, movieIds, genreIds };
}

let started = false;
let askedAi = false;

// The first actions of a user: favourite genres and a couple of follows.
function start(data, i, headers) {
  const genres = [...data.genreIds].sort(() => Math.random() - 0.5).slice(0, 2 + Math.floor(Math.random() * 2));
  http.put(`${API.profiles}/profiles/me/favorite-genres`, JSON.stringify(genres), { headers: json(headers) });
  const others = data.profileIds.filter((_, k) => k !== i - 1).sort(() => Math.random() - 0.5).slice(0, 2);
  for (const id of others) http.put(`${API.profiles}/profiles/me/following/${id}`, null, { headers });
}

export default function (data) {
  const i = userIndex();
  const headers = authHeaders(i);
  if (!started) {
    start(data, i, headers);
    started = true;
  }

  const r = Math.random() * 100;
  if (r < 22) {
    http.get(`${API.movies}/movies?page=${Math.floor(Math.random() * 10)}&size=24`, { headers });
  } else if (r < 44) {
    http.get(`${API.movies}/movies/search?q=${encodeURIComponent(pick(SEARCHES))}&size=24`, { headers });
  } else if (r < 58) {
    http.get(`${API.movies}/movies/${pick(data.movieIds)}`, { headers });
  } else if (r < 68) {
    http.put(`${API.profiles}/profiles/me/watched/${pick(data.movieIds)}`, null, { headers });
  } else if (r < 73) {
    http.put(`${API.profiles}/profiles/me/watchlist/${pick(data.movieIds)}`, null, { headers });
  } else if (r < 85) {
    const id = pick(data.profileIds);
    http.get(`${API.profiles}/profiles/${id}`, { headers });
    http.get(`${API.profiles}/profiles/${id}/watched?size=24`, { headers });
  } else if (r < 97 || askedAi || i > ASK_AI) {
    http.get(`${API.recommendations}/recommendations/me`, { headers });
  } else {
    // Users 1..ASK_AI ask once each; the answer arrives minutes later, and the dashboards show it.
    const res = http.post(`${API.recommendations}/recommendations/requests`,
      JSON.stringify({ prompt: PROMPTS[(i - 1) % PROMPTS.length] }), { headers: json(headers) });
    askedAi = res.status === 202 || res.status === 200 || res.status === 409;
  }

  sleep(THINK_MIN + Math.random() * (THINK_MAX - THINK_MIN));
}

export function handleSummary(data) {
  const n = (data.metrics.http_reqs || { values: { count: 0 } }).values.count;
  const failed = (data.metrics.http_req_failed || { values: { rate: 0 } }).values.rate;
  const line = `activity: ${USERS} users (${PREFIX}*) for ${DURATION}, ${n} requests, ` +
    `${(failed * 100).toFixed(2)}% failed, Ask AI sent by up to ${Math.min(ASK_AI, USERS)} users`;
  return jsonSummary({ script: 'activity', base: BASE, users: USERS, duration: DURATION }, data, line);
}
