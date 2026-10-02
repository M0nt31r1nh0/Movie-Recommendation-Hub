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

## What you need

- **Docker** on your computer (Docker Desktop on macOS), with `just` (`brew install just`, or `apt install just`). Without `just`, run the commands from `justfile` by hand.
- **A Google Cloud project** with billing (or education credits) and the Compute Engine API enabled.
- **A service account key** for it: IAM & Admin → Service accounts → create one with the *Compute Admin* role → Keys → Add key → JSON. Save it as `infra/terraform/credentials.json`. It's ignored by git; never commit it.
- **A container registry** the VMs can pull from: Docker Hub, GitHub's, or the group's GitLab registry (private: see `image_pull_secret` below).

## Deploy

All commands run from `infra/`.

### 1. Build and push the images (on your computer)

```bash
docker login docker.io                    # or your registry's host
just images docker.io/<user> v1           # or: ./build-images.sh docker.io/<user> v1
```

This builds `movies-service`, `profiles-service`, `recommendation-service`, `recommendation-worker` and `frontend` for `linux/amd64` (the VMs' CPU, even from an ARM Mac) from source, with no Java or Node needed locally, and pushes them. The first build takes a while; later ones reuse Docker's cache.

Then set the same registry and tag in `ansible/group_vars/all.yml`:

```yaml
image_registry: docker.io/<user>
image_tag: v1
```

For a private registry, also fill in `image_pull_secret` (server, username, and a read-only token, e.g. a GitLab deploy token with `read_registry`).

### 2. Configure

```bash
cp terraform/terraform.tfvars.example terraform/terraform.tfvars    # then set gcp_project_id
just keygen                                                         # SSH key for the VMs, in ssh/
```

`terraform/variables.tf` lists what else can change (zone, machine types, number of workers, who may SSH in). `ansible/group_vars/all.yml` lists the software versions, models and Kafka size.

### 3. Start the management container and deploy

```bash
just mgmt-start
just mgmt                 # a shell inside it, in /root/g17/infra
just all                  # = just infra cluster platform app
```

Or one step at a time, which is easier to follow (and to show in the video):

| Step | What it does | Takes |
|---|---|---|
| `just infra` | Terraform: network, firewall, static IP, VMs; writes `ansible/inventory.ini`. Asks for confirmation | ~1 min |
| `just cluster` | Prepares the VMs, `kubeadm init`, Calico, joins the workers, labels the edge node; saves the kubeconfig to `.kube/config` | ~5 min |
| `just platform` | local-path-provisioner (storage), Strimzi (Kafka operator), kube-prometheus-stack (Prometheus, Grafana) | ~5 min |
| `just app` | Databases (and the movies dump, the first time), Kafka, Ollama and its models, Keycloak, the services, the frontend, NGINX, monitoring | ~15 min the first time (the models are ~2.5 GB) |

At the end, `just app` prints the addresses:

- **Application:** `https://<static IP>`. Register an account there, as locally.
- **Grafana:** `https://<static IP>/grafana`. User `admin`, password in `ansible/.secrets/grafana-admin`. The **Movies** dashboard shows request rates and latency per API, Ollama's answer times, tokens/s, CPU and memory, Kafka's backlog for the worker, the databases' connections, and the nodes.
- **Keycloak's admin console:** `https://<static IP>/auth/admin`. User `admin`, password in `ansible/.secrets/keycloak-admin`.
- **Prometheus:** not public. On your computer (with kubectl installed), `just prometheus`, then open `http://localhost:9090`.

`ansible/.secrets/` holds every password the playbooks generated (databases, Keycloak, Grafana, the two service accounts' secrets). It's ignored by git; keep it, since the deployed system uses those values.

Inside the management container, `kubectl` already points at the cluster: `kubectl get pods -A`, `kubectl -n movies logs deploy/recommendation-worker -f`, and so on.

### Update

- **New application code:** build with a new tag (`just images docker.io/<user> v2`), set `image_tag: v2`, then `just app`. With `image_tag: latest`, every `just app` pulls the images again.
- **Changed manifests or settings:** `just app` (or `just platform`) again. The playbooks only change what differs.

### Destroy

```bash
just destroy
```

This deletes every resource Terraform created, including the disks: **all data is lost**. Check the Compute Engine console afterwards; anything left running keeps spending credits.

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

## Troubleshooting

| Problem | Fix |
|---|---|
| `terraform apply`: *Quota 'CPUS' exceeded* | Free-trial accounts allow 8 vCPUs. Use smaller machines (`worker_machine_type`) or fewer workers, or ask for more quota (IAM & Admin → Quotas). |
| `terraform apply`: *zone does not have enough resources* | Pick another zone (`gcp_zone`) in `terraform.tfvars`. |
| Ansible can't connect over SSH | The VMs need a minute after `terraform apply`. Check that `admin_cidrs` includes your address. |
| A pod is `ImagePullBackOff` | `kubectl -n movies describe pod <pod>`: check `image_registry`/`image_tag`, that the images were pushed for `linux/amd64`, and `image_pull_secret` for a private registry. |
| `just app` waits a long time for the models | Ollama is downloading ~2.5 GB: `kubectl -n movies logs job/ollama-models -f`. |
| recommendation-worker is not ready | It's ready only once Ollama has the model: `kubectl -n movies logs deploy/recommendation-worker`. |
| The browser warns about the certificate | Expected with the self-signed one: accept it once. Login fails if you don't, because Keycloak's library needs HTTPS. |
| Login redirects fail with *Invalid redirect uri* | The realm was imported with another `public_url`. Fix the `movies-frontend` client's URLs in the admin console. Or start Keycloak over, **deleting every user account**: `kubectl -n movies delete deployment/keycloak statefulset/keycloak-db pvc/data-keycloak-db-0`, then `just app`. |
| Recommendations take minutes | Expected on CPUs, as locally. Use a smaller model (`ollama_chat_model: qwen2.5:1.5b`), or bigger workers. |
