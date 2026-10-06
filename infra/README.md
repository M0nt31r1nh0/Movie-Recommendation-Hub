# Deployment to Google Cloud

How to run the whole system on a self-hosted Kubernetes cluster in Google Cloud, from nothing: Terraform creates the VMs and network, Ansible installs Kubernetes (kubeadm, containerd, Calico, as in lab 4) and deploys everything onto it. `compose.yaml`, in this same folder, is still the way to run it on your own machine (see the [root README](../README.md)).

```
                        Internet (HTTPS)
                              │
                 ┌────────────▼─────────────┐  worker1, static IP, ports 80/443 only
                 │ NGINX (TLS)              │
                 └─┬────┬────┬────┬────┬────┘
        /          │    │    │    │    │   /grafana/
   ┌───────────────┘    │    │    │    └──────────────────┐
   ▼                    ▼    ▼    ▼                       ▼
frontend      /api/movies  /api/profiles  /api/recommendations   /auth/ ─► Keycloak ─► keycloak-db
              /api/genres       │               │   (+ /events SSE)
                  │             │               │
             movies-service  profiles-service  recommendation-service ──► Kafka (3 brokers, Strimzi)
              │      │          │                 │                          │
          movies-db  │     profiles-db    recommendations-db                 ▼
                     └──────► Ollama ◄───────────────────────────── recommendation-worker

     Prometheus (monitoring namespace) scrapes every service, database exporter, Kafka Exporter, NGINX
     and Keycloak over the cluster network; Grafana shows them. None of those is public.
```

| Folder | What |
|---|---|
| `mgmt/` | The management container: Terraform, Ansible, kubectl and gcloud, so your computer only needs Docker |
| `terraform/` | The VPC, firewall, static IP and VMs (1 control plane + 2 workers by default), and Ansible's inventory |
| `ansible/` | The playbooks: `01-nodes`, `02-cluster` (Kubernetes), `03-platform` (storage, Strimzi, Prometheus/Grafana), `04-app` (the application) |
| `k8s/` | The Kubernetes manifests, as templates Ansible fills in, and Grafana's dashboard |
| `build-images.sh` | Builds the five application images and pushes them to a registry |

## What's public, and what isn't

Only NGINX is reachable from the Internet, on the edge node's ports 80 (redirects to HTTPS) and 443. It routes:

| Path | Goes to |
|---|---|
| `/` | frontend |
| `/api/movies`, `/api/genres` | movies-service (`/movies`, `/genres`) |
| `/api/profiles` | profiles-service (`/profiles`) |
| `/api/recommendations` | recommendation-service (`/recommendations`, including the `/events` stream, unbuffered) |
| `/auth/` | Keycloak (login, registration, admin console) |
| `/grafana/` | Grafana (its own login) |

The APIs sit under `/api` because the frontend's pages also use `/movies`, `/recommendations` and `/users`. The worker, Ollama, the databases, Kafka and Prometheus have no route: they don't need one to be monitored, because Prometheus runs inside the cluster and scrapes them on their internal Services. NetworkPolicies also enforce that each database accepts connections only from its own service (and its metrics port only from the monitoring namespace), and Ollama only from the worker and movies-service.

Keycloak's browser library refuses to work over plain HTTP (it needs the browser's Web Crypto API, which only exists on HTTPS pages), so NGINX always serves HTTPS. Without a certificate of your own, the playbook makes a self-signed one for the static IP: the browser warns once, and works after you accept it. See [Using a real certificate](#using-a-real-certificate).

## One-time setup

Everything here is done once per person (or per Google Cloud project). The commands are for macOS with [Homebrew](https://brew.sh); on Linux, install the same tools with your package manager.

### Tools on your computer

- **Docker Desktop**, started (the whale icon in the menu bar). It builds the images and runs the management container; nothing else is installed locally.
- **just**, which runs the commands in `infra/justfile`: `brew install just`. Without it, copy the commands from `justfile` by hand.
- **Google Cloud CLI**, only for the setup below: `brew install --cask gcloud-cli`. Homebrew puts it in `/opt/homebrew/share/google-cloud-sdk/bin`: add that to your `PATH`, or call it by that path.

### A Google Cloud project for Terraform

Log in with the Google account that has the credits (this opens the browser):

```bash
gcloud auth login
```

Create a project for the deployment, with billing, and the APIs it uses. Project IDs are global: pick a unique one, e.g. with a random suffix.

```bash
PROJECT=psia26-g17-$(LC_ALL=C tr -dc 'a-z0-9' </dev/urandom | head -c 6)
gcloud projects create $PROJECT --name=psia26-g17
gcloud billing accounts list                                     # the ACCOUNT_ID of the billing account with the credits
gcloud billing projects link $PROJECT --billing-account=<ACCOUNT_ID>
gcloud services enable compute.googleapis.com --project $PROJECT
```

Create the service account Terraform uses, and its key, in `infra/terraform/credentials.json` (ignored by git: **never commit it**):

```bash
gcloud iam service-accounts create terraform --display-name="Terraform (g17 deployment)" --project $PROJECT
gcloud projects add-iam-policy-binding $PROJECT --condition=None \
  --member=serviceAccount:terraform@$PROJECT.iam.gserviceaccount.com --role=roles/compute.admin
gcloud iam service-accounts keys create terraform/credentials.json \
  --iam-account=terraform@$PROJECT.iam.gserviceaccount.com
```

(The same in the console: IAM & Admin → Service accounts → Create, role *Compute Admin*, then Keys → Add key → JSON.) If `add-iam-policy-binding` says the service account doesn't exist, wait a few seconds and run it again: new accounts take a moment to appear.

Tell Terraform which project, and make the SSH key it installs on the VMs:

```bash
echo "gcp_project_id = \"$PROJECT\"" > terraform/terraform.tfvars    # see terraform.tfvars.example for the other settings
just keygen                                                            # ssh/id_ed25519, ignored by git
```

### A registry for the images

The cluster pulls the five application images from a registry. The project uses **Docker Hub**, with public repositories, so the VMs need no credentials to pull them. Pushing needs an account: create one at [hub.docker.com](https://hub.docker.com), then once:

```bash
docker login -u <user>
```

Docker Hub creates each repository on its first push, public by default. Then set the same place in `ansible/group_vars/all.yml`:

```yaml
image_registry: docker.io/<user>
image_tag: v1
```

Any other registry works as well (GitHub's, the group's GitLab registry, Google's Artifact Registry). For a private one, also fill in `image_pull_secret` (server, username, and a read-only token, e.g. a GitLab deploy token with `read_registry`).

## Deploy

All commands run from `infra/`.

### 1. Build and push the images (on your computer)

Needed the first time, and after every change to the application's code:

```bash
just images docker.io/<user> v1           # or: ./build-images.sh docker.io/<user> v1
```

This builds `movies-service`, `profiles-service`, `recommendation-service`, `recommendation-worker` and `frontend` for `linux/amd64` (the VMs' CPU, even from an ARM Mac) from source, with no Java or Node needed locally, and pushes them. The first build takes several minutes (the frontend is built under emulation on an ARM Mac); later ones reuse Docker's cache. For a new version, use a new tag (`v2`) here and in `image_tag`.

### 2. Start the management container

```bash
just mgmt-start           # builds it the first time (a few minutes)
just mgmt                 # a shell inside it, in /root/g17/infra
```

It has Terraform, Ansible, kubectl and gcloud, with the repository mounted at `/root/g17`: files it writes (the inventory, the kubeconfig, the passwords) land in your copy of the repository.

### 3. Deploy

Inside the management container:

```bash
just all                  # = just infra cluster platform app
```

Or one step at a time, which is easier to follow (and to show in the video):

| Step | What it does | Took (2026-10-02) |
|---|---|---|
| `just infra` | Terraform: network, firewall, static IP, VMs; writes `ansible/inventory.ini`. Shows the plan and asks for `yes` | ~1 min |
| `just cluster` | Prepares the VMs (containerd, kubelet, kubeadm), `kubeadm init`, Calico, joins the workers, labels the edge node; saves the kubeconfig to `.kube/config` | ~5 min |
| `just platform` | local-path-provisioner (storage), Strimzi (Kafka operator), kube-prometheus-stack (Prometheus, Grafana) | ~4 min |
| `just app` | Databases (and the movies dump, the first time), Kafka, Ollama and its models, Keycloak, the services, the frontend, NGINX, monitoring | ~12 min the first time (the models are ~2.5 GB) |

Every step can run again: it only changes what differs (a second `just app` restarts nothing, and doesn't load the dump again).

At the end, `just app` prints the addresses:

- **Application:** `https://<static IP>`.
- **Grafana:** `https://<static IP>/grafana`, user `admin`, password from `cat ansible/.secrets/grafana-admin`. The **Movies** dashboard shows request rates and latency per API, Ollama's answer times, tokens/s, CPU and memory, Kafka's backlog for the worker, the databases' connections, and the nodes.
- **Keycloak's admin console:** `https://<static IP>/auth/admin`, user `admin`, password from `cat ansible/.secrets/keycloak-admin`.
- **Prometheus:** not public. On your computer (with kubectl installed), `just prometheus`, then open `http://localhost:9090`.

`ansible/.secrets/` holds every password the playbooks generated (databases, Keycloak, Grafana, the two service accounts' secrets). It's ignored by git; **keep it**, since the next `just app` must use the same values the databases and Keycloak were created with.

### 4. Check that it works

1. **Open `https://<static IP>`.** The browser warns that the connection isn't private (`NET::ERR_CERT_AUTHORITY_INVALID`): that's the self-signed certificate, and expected. Click **Advanced** / **Help me understand**, then **Proceed to <IP> (unsafe)**. In Chrome-based browsers without that link, click on the page and type `thisisunsafe`. See [Using a real certificate](#using-a-real-certificate) to avoid it.
2. **Register an account and log in**, as locally. Movies appear on `/movies`.
3. **Ask AI** on `/recommendations`: the answer takes minutes (see [Why recommendations are slow](#why-recommendations-are-slow)), and a notification says when it's ready.
4. **Grafana**: the Movies dashboard fills in as you use the application.

From the management container, `kubectl` already points at the cluster:

```bash
kubectl get nodes                                         # 3 nodes, Ready
kubectl -n movies get pods                                # everything Running and READY
kubectl -n kafka get kafka                                # movies-kafka READY True
kubectl -n movies logs deploy/recommendation-worker -f    # each recommendation, and how long Ollama took
```

### Update

- **New application code:** build with a new tag (`just images docker.io/<user> v2`), set `image_tag: v2`, then `just app`. With `image_tag: latest`, every `just app` pulls the images again.
- **Changed manifests or settings:** `just app` (or `just platform`) again.

### Stop and start the VMs

To pause the deployment without losing anything (overnight, say), stop the VMs instead of destroying them. In the management container:

```bash
just stop     # all three VMs stopped
just start    # all three VMs started again, then waits until the application answers
```

- **`just stop`** runs `terraform apply -auto-approve -var vm_state=TERMINATED`. The variable `vm_state` (`terraform/variables.tf`, default `RUNNING`) is passed to every VM as `desired_status` (`terraform/servers.tf`). A change of that argument makes the Google provider call the Compute Engine API's `instances.stop` for each VM, and GCE shuts the guest OS down cleanly. It takes about 1.5 minutes.
- **`just start`** (recipe `start` in the `justfile`):
  1. `terraform apply ... -var vm_state=RUNNING -target=google_compute_instance.master -target=google_compute_instance.worker` starts the VMs, and only them.
  2. A second, full `terraform apply` rewrites `ansible/inventory.ini`. GCE releases the VMs' ephemeral public addresses while they are stopped, so Terraform's state has none for the master and worker2 until they are running again, and after a start they can be different (in our runs they swapped). The first apply is limited to the VMs because the inventory file would otherwise change in the middle of the same apply, which Terraform rejects. The second apply reads the new addresses, and the recipe stops with an error if the master has none.
  3. `kubectl config set-cluster kubernetes ... --server=https://<master>:6443 --tls-server-name=kubernetes` points `.kube/config` at the master's current address. The API server's certificate lists the master's first address and also the name `kubernetes`, so `kubectl` accepts the new address when it checks the certificate against that name.
  4. It waits for the Kubernetes API, every node `Ready`, the Kafka pods, every StatefulSet and Deployment (`kubectl rollout status`), and for the application to answer at `/auth/realms/movies` and `/`. The last line it prints is `Everything answers, N seconds after the start`.
  <!-- TODO: add the measured time once the final test is done. -->

Stopped VMs aren't billed for CPU. Only their disks and the static address are, and the disks are what keeps the data. The application's address (the static IP) never changes.

**What survives a stop and start:** every database (movies, profiles, recommendations, Keycloak), Kafka's topics and queued messages, Ollama's models and Prometheus's history, because they live on the VMs' disks.

**What doesn't:**

- `just destroy` deletes the disks, so everything is lost.
- A recommendation being computed when the VMs stop fails with *Connection was closed*. A failed typed request stays visible to its user for an hour, and a failed profile request is deleted, leaving the user's previous set; the background agent then tries again after its back-off (30 minutes).
- The first minute after a start is noisy: pods restart and log *OIDC Server is not available* or Kafka *Node may not be available*, because the services start before Keycloak and Kafka are reachable. They recover on their own.

To check this yourself, see [Testing persistence](#testing-persistence).

### Destroy

**When you're done, destroy everything**, since the VMs cost about 0.40 USD an hour (~10 USD a day) while they exist:

```bash
just destroy
```

This deletes every resource Terraform created, including the disks: **all data is lost**, including user accounts (to pause without losing data, [stop the VMs](#stop-and-start-the-vms) instead). Then check that nothing is left, in the console or with:

```bash
gcloud compute instances list --project $PROJECT     # and: disks list, addresses list
```

The project, the service account and the images on Docker Hub stay, and cost nothing. The next deploy starts again from step 3 (and step 1 only if the code changed).

## Testing persistence

[`evidence/`](evidence/) holds two scripts that show nothing is lost across a stop and start. Run them in the management container, from `infra/`. Their output goes to `evidence/*.txt`, which git ignores.

```bash
just snapshot before       # record the state (see below)
just stop
just start
just snapshot after
just compare before after  # prints "IDENTICAL: nothing lost", or the lines that differ
```

`just snapshot <label>` runs `evidence/snapshot.sh`, which writes `evidence/<label>.txt` with: the recommendation requests by status, the row counts of the profiles database, the number of movies, the finished recommendation requests and their items, the Keycloak usernames, Kafka's topics, Ollama's models and the volumes' status. It also writes `<label>-pods.txt`, the pod list, for reference. `just compare` runs `evidence/compare.sh`, which ignores the "N minutes ago" text of the models.

**Before the first snapshot, wait until no recommendation request is `PENDING`.** A request in flight when the VMs stop fails by design (see [Stop and start the VMs](#stop-and-start-the-vms)), so the two snapshots would differ for a legitimate reason: a failed profile request is deleted.

**What the result means:** identical files mean that every database, topic, model and volume came back unchanged, which is what persistent state requires. Check by hand in the browser too: log in as an existing user, and see their follows, watched movies and recommendations.

**Requests in flight.** A user can have only one typed request at a time (the API answers 409 otherwise), so use three users, in separate browser windows, each sending one question. The worker handles one job at a time, so one runs and two wait in Kafka. Stop the VMs about 30 seconds later, start them, and look at the requests:

```bash
kubectl -n movies exec recommendations-db-0 -c postgres -- psql -U recommendations -d recommendations -c "select request_id, left(prompt,30) as prompt, status, error from recommendations.request where kind = 'PROMPT' order by created_at desc"
```

In our test, the request that was running failed with *Connection was closed* and its user was told so, while the two that were waiting in Kafka were processed once the worker was back. A request that stays `PENDING` for more than an hour is marked `FAILED` by the sweeper (`recommendation.timeout`), so nothing stays lost for good.

## How the application is configured here

The services read the same environment variables their `application.properties` list for production; the manifests in `k8s/` set them:

- **Databases:** one PostgreSQL StatefulSet each (`movies-db` with pgvector, `profiles-db`, `recommendations-db`) and one for Keycloak (`keycloak-db`; locally it shares the movies server). Each has a 2–10 GB volume and a `postgres_exporter` sidecar. The first `just app` restores `data/movies_dump.sql` into `movies-db` before movies-service ever starts.
- **Kafka:** a Strimzi `Kafka` with 3 nodes in KRaft mode (each broker and controller), topics replicated 3 times with 6 partitions, at `movies-kafka-kafka-bootstrap.kafka.svc:9092`. Set `kafka_replicas: 1` to save memory.
- **Keycloak:** production mode behind NGINX at `/auth`. The realm is imported from `keycloak/movies-realm.json` on the first start, with the public URL in place of `http://localhost:3000` and generated secrets in place of the service accounts' dev secrets. As locally, later changes to the file don't reach an existing realm.
- **Tokens:** their issuer is the public URL (`https://<IP>/auth/realms/movies`). The services fetch Keycloak's keys over the cluster network (`http://keycloak.movies.svc:8080/auth`), and still check that issuer.
- **Frontend:** built with `/api` and `/auth` as its API and Keycloak addresses (relative, so the image works at any address), and served by Next.js' standalone server.
- **Metrics:** each Quarkus service exposes Micrometer's Prometheus metrics at `/q/metrics` (`quarkus-micrometer-registry-prometheus`). The worker also measures each Ollama answer (`recommendation_llm_requests_seconds`, `recommendation_llm_tokens_total`), because Ollama has no metrics endpoint of its own.

## Using a real certificate

With a domain name pointing at the static IP (the `public_url` output of Terraform), get a certificate for it, e.g. with [certbot](https://certbot.eff.org/), and set these in `ansible/group_vars/all.yml`:

```yaml
public_url: "https://movies.example.com"
tls_certificate_file: /root/g17/infra/ansible/tls/fullchain.pem     # ansible/tls/ is ignored by git
tls_key_file: /root/g17/infra/ansible/tls/privkey.pem
```

Change `public_url` before the first `just app`. Keycloak's realm stores it at import, so changing it later also needs the `movies-frontend` client's URLs updated in the admin console.

## Why recommendations are slow

Ollama runs the model on the workers' CPUs, with no GPU. Measured on this deployment (2 × `e2-standard-4`), one Ask AI request took 4.5 minutes in all:

| Step | Tokens read → written | Time |
|---|---|---|
| The model reads the request ("a horror movie from the 80s") | 98 → 25 | 27 s (includes loading the model into memory) |
| It reads ~30 candidate movies with their descriptions, and picks 10 | 1,985 → 58 | 4 min |

- **Reading the prompt is the slow part.** On a CPU the model must process every input token before it writes anything, and the second prompt is 20 times longer than the first.
- **An `e2-standard-4` has 2 physical cores** (4 vCPUs with hyper-threading), and Ollama uses one thread per physical core.
- **Right after a deploy, movies-service is embedding the movies** for search by meaning, through the same Ollama, which handles one request at a time (`OLLAMA_NUM_PARALLEL=1`). Until it finishes (about 9,000 movies, roughly an hour), recommendations wait behind its batches. The embeddings stay in the database, so `just app` again doesn't redo them; a new cluster, after `just destroy`, does.

To make them faster: fewer candidates (add `RECOMMENDATION_CANDIDATES`, default 30, to the worker's `env` in `k8s/recommendation-worker.yaml.j2`), a smaller model (`ollama_chat_model: qwen2.5:1.5b`, about twice as fast, choosing less well), or bigger workers (`worker_machine_type` in `terraform.tfvars`). A GPU VM would answer in seconds, but needs GPU quota, NVIDIA drivers and the Kubernetes device plugin. The application expects the wait anyway: Ask AI answers at once and notifies the user later, and "Recommended for you" is prepared in the background.

## Troubleshooting

| Problem | Fix |
|---|---|
| `Cannot connect to the Docker daemon`, or containers stay in *Created* | Start Docker Desktop and wait until it says it's running (right after it starts, the first containers can hang: stop them and try again). |
| `gcloud: command not found` after `brew install` | Add `/opt/homebrew/share/google-cloud-sdk/bin` to your `PATH`, or call `gcloud` by that path. |
| `docker push`: *denied: requested access to the resource is denied* | Run `docker login -u <user>`, and check that `<user>` in the image names is your Docker Hub username. |
| `terraform apply`: *Quota 'CPUS' exceeded* | Free-trial accounts allow 8 vCPUs. Use smaller machines (`worker_machine_type`) or fewer workers, or ask for more quota (IAM & Admin → Quotas). |
| `terraform apply`: *zone does not have enough resources* | Pick another zone (`gcp_zone`) in `terraform.tfvars`. |
| Ansible can't connect over SSH | The VMs need a minute after `terraform apply`. Check that `admin_cidrs` includes your address. |
| A pod is `ImagePullBackOff` | `kubectl -n movies describe pod <pod>`: check `image_registry`/`image_tag`, that the images were pushed for `linux/amd64`, and `image_pull_secret` for a private registry. |
| `just app` waits a long time for the models | Ollama is downloading ~2.5 GB: `kubectl -n movies logs job/ollama-models -f`. |
| recommendation-worker is not ready | It's ready only once Ollama has the model: `kubectl -n movies logs deploy/recommendation-worker`. |
| The browser says *Your connection is not private* | Expected with the self-signed certificate: see [Check that it works](#4-check-that-it-works) for how to proceed, or [use a real certificate](#using-a-real-certificate). |
| Login redirects fail with *Invalid redirect uri* | The realm was imported with another `public_url`. Fix the `movies-frontend` client's URLs in the admin console. Or start Keycloak over, **deleting every user account**: `kubectl -n movies delete deployment/keycloak statefulset/keycloak-db pvc/data-keycloak-db-0`, then `just app`. |
| Recommendations take minutes | Expected on CPUs: see [Why recommendations are slow](#why-recommendations-are-slow). |
| `just start` stops with *Terraform has no address for the master* | The VMs did not start (check the console: a zone can lack capacity). Run `just start` again after a few minutes. |
| After `just start`, pods restart and the logs say *OIDC Server is not available* or *Node may not be available* | Expected for about a minute: the services start before Keycloak and Kafka are reachable, and recover on their own. |
| Grafana's panels show a red triangle and *origin not allowed* | Grafana refuses a request whose `Origin` differs from the `Host` it received. NGINX drops every `proxy_set_header` of the server for a `location` that sets its own, so `/grafana/` must repeat `Host` and the `X-Forwarded-*` headers (`k8s/nginx.yaml.j2`). Apply with `just app`. |
