locals {
  ssh_keys = "${var.ssh_user}:${file(var.ssh_public_key_file)}"
}

resource "google_compute_instance" "master" {
  name         = "master"
  machine_type = var.master_machine_type
  zone         = var.gcp_zone
  tags         = ["cluster", "master"]

  desired_status = var.vm_state

  boot_disk {
    initialize_params {
      image = var.image
      size  = 30
      type  = "pd-balanced"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.cluster.id
    access_config {}
  }

  metadata = {
    ssh-keys = local.ssh_keys
  }
}

# worker1 is the edge node: NGINX runs there (on ports 80 and 443 of the VM) with the static address.
resource "google_compute_instance" "worker" {
  count        = var.worker_count
  name         = "worker${count.index + 1}"
  machine_type = var.worker_machine_type
  zone         = var.gcp_zone
  tags         = count.index == 0 ? ["cluster", "worker", "edge"] : ["cluster", "worker"]

  desired_status = var.vm_state

  boot_disk {
    initialize_params {
      image = var.image
      size  = var.disk_size_gb
      type  = "pd-balanced"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.cluster.id
    access_config {
      nat_ip = count.index == 0 ? google_compute_address.edge.address : null
    }
  }

  metadata = {
    ssh-keys = local.ssh_keys
  }
}
