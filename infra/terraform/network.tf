resource "google_compute_network" "cluster" {
  name                    = "g17-cluster"
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "cluster" {
  name          = "g17-cluster"
  network       = google_compute_network.cluster.id
  region        = var.gcp_region
  ip_cidr_range = var.subnet_cidr
}

# Between the VMs, everything: the Kubernetes API, kubelets, Calico's BGP (TCP 179) and its IP-in-IP
# tunnels (IP protocol 4), which carry all traffic between pods on different nodes.
resource "google_compute_firewall" "internal" {
  name          = "g17-internal"
  network       = google_compute_network.cluster.name
  source_ranges = [var.subnet_cidr]
  allow {
    protocol = "all"
  }
}

resource "google_compute_firewall" "admin" {
  name          = "g17-admin"
  network       = google_compute_network.cluster.name
  source_ranges = var.admin_cidrs
  target_tags   = ["cluster"]
  allow {
    protocol = "tcp"
    ports    = ["22", "6443"]
  }
  allow {
    protocol = "icmp"
  }
}

# Only NGINX, on the edge node, is public. Everything else (APIs' metrics, databases, Kafka, Ollama,
# Prometheus) is reachable only from inside the cluster.
resource "google_compute_firewall" "web" {
  name          = "g17-web"
  network       = google_compute_network.cluster.name
  source_ranges = ["0.0.0.0/0"]
  target_tags   = ["edge"]
  allow {
    protocol = "tcp"
    ports    = ["80", "443"]
  }
}

# The application's public address, kept when VMs are stopped, recreated or destroyed: Keycloak's tokens,
# the login redirects and the TLS certificate all name it. It belongs to the persistent stack
# (persistent/main.tf), which `just persistent` applies first; here it is only looked up.
data "google_compute_address" "edge" {
  name   = "g17-edge"
  region = var.gcp_region
}

# It used to be created here: on a deployment made before the persistent stack, apply forgets it from this
# state without deleting it (the persistent stack imports it). destroy ignores this block: `just destroy`
# forgets it first.
removed {
  from = google_compute_address.edge
  lifecycle {
    destroy = false
  }
}
