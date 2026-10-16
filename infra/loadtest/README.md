# Load test (the report's Evaluation)

How throughput and latency change as the number of concurrent clients grows, on the Google Cloud deployment. [k6](https://grafana.com/docs/k6/latest/) runs in a pod on the control-plane node and sends requests through NGINX's public address, as browsers do. That node runs no application pods, so k6 doesn't take CPU from the system it measures, and it sits in the same zone, so no wide-area latency gets into the numbers.

| File | What it does |
|---|---|
| `users.py` | Adds the `movies-loadtest` client and the test users `loadtest-001…200` to Keycloak through its admin API, or deletes them (`teardown`) |
| `k6-pod.yaml` | The k6 pod (`grafana/k6:1.0.0`), on the control-plane node, in namespace `loadtest` |
| `common.js` | Shared by the scripts: addresses, test users, logging in and refreshing each user's token |
| `prepare.js` | Logs every test user in once, so each has a profile and a cached *Recommended for you* |
| `mix.js` | The measured load: N clients in a closed loop over a weighted mix of requests |
| `askai.js` | Optional: N *Ask AI* requests sent at once, and how long each waits for its answer |
| `activity.js` | Not a measurement: a few demo users acting like people for a while, so the dashboards and screenshots show real activity |
| `run.sh` | Runs `mix.js` for each number of clients, several times, and saves the results |
| `summarize.py`, `prom.py`, `plot.py` | The tables, CPU/memory per pod from Prometheus, and the report's figure |
| `cleanup.sh` | Deletes the test users' profiles and recommendations from the databases |
| `justfile` | All of the above as `just` recipes |

## What is measured

Each client (a k6 *virtual user*) logs in as its own test user, then repeats: pick a request from the mix, send it, wait 0.5 to 1.5 s (1 s on average, `THINK`), and so on.

| Share | Kind | Requests (via NGINX) |
|---:|---|---|
| 35% | Browse the catalogue | `GET /api/movies?page=<0–99>&size=24` |
| 25% | Search | `GET /api/movies/search?q=<one of 24 queries>&size=24`: words, prefixes, typos, phrases, `or`, `-word` |
| 10% | A movie | `GET /api/movies/{id}` |
| 15% | A profile | `GET /api/profiles/{id}` and `GET /api/profiles/{id}/watched` (another test user's) |
| 5% | Change the profile | `PUT /api/profiles/me/watched/{id}`, then `DELETE` the same, so the profile ends as it began |
| 10% | Recommended for you | `GET /api/recommendations/me` (cached: every test user already has a set) |

The mix leaves the model out on purpose. One recommendation takes the CPUs minutes, so on-demand recommendations are measured separately (`askai.js`, step 6), as a queue rather than as throughput.

**One run** (`run.sh`) ramps the clients up over 30 s (`RAMP`), counts the first 60 s as warm-up (`WARMUP`), then measures for 180 s (`STEADY`). Every request is tagged with its phase and endpoint, and only the steady-state ones count. Logins and token refreshes are tagged apart, so they never count either: tokens live 5 minutes, and each client refreshes its own a minute before it expires. Defaults are 1, 10, 50, 100 and 200 clients, each repeated 3 times: 15 runs of about 4.5 minutes, about 70 minutes in all.

For each number of clients you get: throughput (req/s), latency p50/p95/p99 and the error rate, overall and per endpoint, as mean ± standard deviation over the repeats. Prometheus also gives the CPU and memory of every pod and node during each run, which is how we find the bottleneck.

## Results (15/10/2026)

The run `final`, on the default VMs (master `e2-standard-2`, two workers `e2-standard-4`), image tag `v1` (built on 06/10/2026, before the custom application metrics were added; the only later change to the services' code is the commit that adds them, and the evaluation uses only the standard HTTP, container and node metrics), 3 repeats per step. The data is in [results/final/](results/final/): one `.json` summary and one `.log` per run, plus `summary.csv`, `endpoints.csv`, `resources.csv` and `windows.csv`.

| Clients | Throughput (req/s) | p50 (ms) | p95 (ms) | p99 (ms) | Errors (%) | worker1 CPU |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1.2 ± 0.0 | 12.2 | 23.3 | 66.3 | 0.00 | 11% |
| 10 | 11.6 ± 0.1 | 11.5 | 24.5 | 87.8 | 0.02 | 16% |
| 50 | 58.9 ± 0.4 | 11.1 | 31.1 | 104.5 | 0.04 | 34% |
| 100 | 117.1 ± 0.9 | 11.9 | 49.7 | 161.3 | 0.03 | 56% |
| 200 | 215.9 ± 6.8 | 27.6 | 291.1 | 890.4 | 0.01 | 97% |
| 300 | 205.6 ± 18.4 | 472.6 | 1,661.3 | 3,962.8 | 0.03 | 100% |
| 400 | 198.8 ± 2.3 | 928.2 | 3,253.9 | 5,830.7 | 0.04 | (no sample) |

Throughput grows with the number of clients up to 200, where it peaks at about 216 req/s; beyond that it stays flat and the latency grows instead, with the median reaching about 0.9 s at 400 clients. The errors stay at or below 0.04% throughout.

The limit is worker1's CPU, which reaches 97% at 200 clients and 100% at 300. The scheduler had placed every stateless pod on worker1 (see [../README.md → The VMs](../README.md#the-vms)), next to the four databases, while worker2 stayed at 8%. The largest single consumer is the movies database, at 2.3 of worker1's 4 vCPUs from 200 clients on: 70% of the mix is catalogue queries, and search runs full-text and trigram queries in PostgreSQL. movies-service itself used about 0.5 cores. The master, which ran k6, peaked at 38%, so the load generator did not limit the test. At 400 clients Prometheus has no CPU sample for worker1's node for that window, most likely because its exporter could not answer scrapes in time at 100% CPU.

Above 200 clients the 200 test users are shared, two clients per user; that changes how often each account sends requests, not what is measured.

## Procedure

All commands run in the management container, from `infra/loadtest/`, unless a step says otherwise. Plan for about 2 hours of VM time on top of the deployment itself.

### 0. Before you start

1. **Deploy** as usual (`infra/README.md`): `just mgmt-start`, `just mgmt`, then `just all` (or `just start` if the VMs are only stopped).
2. **Check the cluster is quiet:**
   ```bash
   kubectl get nodes                       # 3 nodes, Ready
   kubectl -n movies get pods              # everything Running and READY
   ```
3. **Wait until the movie embeddings are done.** Right after a new cluster, movies-service embeds about 9,000 movies through Ollama, roughly an hour, and that would load the CPUs during the test. In Grafana's *Movies* dashboard, *Movies still to embed* must be 0. Or check the log: `kubectl -n movies logs deploy/movies-service | grep -i embedded | tail -1`.
4. **No recommendation in progress:** *Requests waiting for an answer* (Grafana, *Recommendations* row) is 0.
5. Note the image tag (`image_tag` in `ansible/group_vars/all.yml`) and the date. The report states both.

### 1. Test users and the k6 pod

```bash
cd loadtest
just setup            # 200 users; or: just setup 100
```

This creates the `movies-loadtest` client and the users in Keycloak, then the k6 pod on the control-plane node. The test users' password is generated once and saved in `loadtest/.secrets/password`, which git ignores. Keycloak's admin password comes from `ansible/.secrets/keycloak-admin`. Running `just setup` again only adds what's missing.

Check: the last line shows the `k6` pod `Running` on the `master` node.

### 2. Prepare the users

```bash
just prepare
```

`loadtest-001` asks for its first *Recommended for you*, and the model makes it: **this takes a few minutes**, and the script waits. The other 199 users then get a copy at once, because their empty profiles have the same fingerprint. The last line must say `READY=199 PREPARING=0 FAILED=0 ERROR=0`.

### 3. Smoke test (1 minute)

```bash
just smoke
```

This runs 5 clients for 30 s and prints a small table. Errors must be 0.00%. If not, see [Troubleshooting](#troubleshooting).

### 4. The measured runs (~70 minutes)

```bash
just run final                     # 1 10 50 100 200 clients, 3 repeats each
```

Each run prints one line, e.g. `clients=50 req/s=47.8 p50=21.3ms p95=88.0ms p99=160.2ms failed=0.00%`. The results go to `results/final/`.

- **While it runs, take the Grafana screenshots** for the report (`https://<IP>/grafana`, the *Movies* dashboard). Take them during a 100- or 200-client run, with the time range set to *Last 15 minutes*: the *Infrastructure* row (*Requests per second, by service*, *Average latency, by service*, *CPU by pod*, *Node CPU used*), plus one of the whole dashboard. Save them as PNG.
- **If the system clearly isn't saturated at 200** (p95 still flat, CPUs well below 100%), add higher steps: `just run final 400`. The new runs land in the same folder, and the tables include them.
- **To keep it shorter**, e.g. if the VMs' time is short: `REPEATS=1 just run final`, about 25 minutes. Say so in the report.
- Don't use the application in a browser meanwhile: it would add to the measured load.

### 5. Tables and resource use

```bash
just summarize final               # prints the table; writes results/final/summary.csv and endpoints.csv
just metrics final                 # CPU/memory per pod and node in each run; writes results/final/resources.csv
```

### 6. Optional: Ask AI under load (5 to 30 minutes per run)

```bash
just askai final 1                 # one request on its own: the model's time for one recommendation
just askai final 3 1               # three at once (prompts shifted by 1, so none was asked before)
```

Each user's line says how long its answer took. The worker takes one job at a time, so with 3 at once the answers should come after about 1×, 2× and 3× the time of one. This shows why requests are asynchronous and why the background agent prepares *Recommended for you* in advance. Each run of 3 takes about 15 minutes on the default VMs.

### 7. Clean up

```bash
just teardown
```

This deletes the test users' profiles and recommendations from the databases, the test users and the `movies-loadtest` client from Keycloak, and the k6 pod. The results stay in `results/`. Do this before recording the video or the final persistence snapshot, so the test users don't show up on the *Users* page.

### 8. What to send back

- the folder `infra/loadtest/results/final/` (all `*.json`, `*.log` and `*.csv`; commit it, it's the data behind the report);
- the Grafana screenshots;
- the image tag, the date, and any setting you changed (`REPEATS`, extra steps…).

The report's figure comes from `python plot.py results/final --out ../../report/figures/evaluation.pdf`. It needs matplotlib, so run it on your computer, not in the management container.

## Demo activity, for the dashboards and screenshots

`activity.js` is not part of the evaluation. It makes a few demo users (`demo-001`, `demo-002`…, separate from the load test's) behave like people for a while: they pause 3 to 8 seconds between actions, and their changes stay. On their first action each picks two or three favourite genres and follows two other demo users. Then they browse the catalogue, search (one query has a typo, `godfater`), open movies they found, mark some as watched, save some for later, look at each other's profiles and read their *Recommended for you*. The first three each send one *Ask AI* request (`ASK_AI`). Their changed profiles make the background agent prepare new sets, so the worker and Ollama have real work too.

```bash
just demo-setup        # 8 demo users, and the k6 pod (again after a just teardown or just stop)
just demo 10           # 10 minutes of activity; just demo 20 12 for 20 minutes with 12 users
just demo-teardown     # afterwards, if the demo users shouldn't stay
```

Take the Grafana screenshots during or right after `just demo`, with the time range on *Last 30 minutes*. The *Ask AI* answers and the agent's new sets take a few minutes each, so the *Recommendations* row fills in over the following 15 to 30 minutes. Keep the demo users for the website screenshots if you want the *Users* page and the profiles to show follows and watched movies; delete them with `just demo-teardown` before recording the video if you'd rather show only real accounts.

## Settings

Set them in front of a recipe, e.g. `THINK=0.5 REPEATS=2 just run quick 10 50`.

| Variable | Default | What |
|---|---|---|
| `REPEATS` | `3` | Runs per number of clients |
| `WARMUP`, `STEADY`, `RAMP` | `60`, `180`, `30` s | Warm-up (includes the ramp-up), measured time, ramp-up |
| `THINK` | `1` s | Average pause between a client's requests (0.5× to 1.5× at random) |
| `USERS` | `200` | How many test users exist. With more clients than users, users are shared |
| `SETTLE` | `30` s | Pause between runs |
| `BASE_URL` | Terraform's `public_url` | The application's address |

The request mix is `WEIGHTS` at the top of `mix.js`.

## Running it locally

On your computer, against `just local-up`, with k6 installed (or `docker run grafana/k6:1.0.0`):

```bash
python3 users.py setup --url http://localhost:3000 --keycloak-url http://localhost:8180 --admin-password admin --users 20
k6 run -e MOVIES_API=http://localhost:8080 -e PROFILES_API=http://localhost:8081 \
  -e RECOMMENDATIONS_API=http://localhost:8082 -e KEYCLOAK_URL=http://localhost:8180 \
  -e PASSWORD="$(cat .secrets/password)" -e USERS=20 -e CLIENTS=5 -e WARMUP=20 -e STEADY=30 prepare.js   # then mix.js
python3 users.py teardown --url http://localhost:3000 --keycloak-url http://localhost:8180 --admin-password admin
```

These numbers don't belong in the report, since everything shares one machine. They are only a dry run of the scripts.

## Troubleshooting

| Problem | Fix |
|---|---|
| `users.py`: `HTTP Error 401` getting the admin token | `ansible/.secrets/keycloak-admin` doesn't match Keycloak's admin password (it only applies on Keycloak's first start). Pass the right one with `--admin-password`. |
| `just setup`: the k6 pod stays `Pending` | `kubectl -n loadtest describe pod k6`: the control-plane node needs about 250m CPU and 256 MiB free. Lower `requests` in `k6-pod.yaml`. |
| `prepare`: `loadtest-001's first set is FAILED` | The worker or Ollama is not ready: `kubectl -n movies logs deploy/recommendation-worker`. Fix it, then run `just prepare` again. |
| `prepare`: some users `PREPARING` instead of `READY` | Their profile wasn't empty (e.g. after an interrupted run). It doesn't affect `mix.js`, since `/recommendations/me` still answers at once. To start clean, `just teardown`, then `just setup` and `just prepare`. |
| `login failed with 401: invalid_grant` | The users' password in `.secrets/password` differs from Keycloak's, e.g. the folder was copied from another machine. Run `just teardown` and `just setup`. |
| `login failed with 400: unauthorized_client` | The `movies-loadtest` client is missing: run `just setup`. |
| Many `401` in the runs | Tokens expired: a client waited over 5 minutes, e.g. a stalled run. Look at the run's `.log`. |
| Errors rise only at many clients | Expected once the system saturates. Note which endpoint (`endpoints.csv`) and which pod's CPU (`resources.csv`). |
| `bash\r: No such file or directory` | The scripts got Windows line endings (`core.autocrlf=true`). This folder's `.gitattributes` keeps them LF on checkout. If it still happens, delete the folder's scripts and run `git checkout -- infra/loadtest` on your computer. |
