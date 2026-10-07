# What must outlive the VMs: one data disk per worker (every volume of the cluster: databases, Kafka,
# Ollama's models, Prometheus) and the application's static address (Keycloak's realm, the login
# redirects and the TLS certificate name it).
#
# This is a separate Terraform stack, with its own state, so that `terraform destroy` in the parent folder
# (`just destroy`) deletes the VMs and the network but never these. The parent stack looks them up by name
# and attaches them. `just persistent` applies this stack, first adopting (importing) any of them that
# already exist in the project, so it creates them only the first time. `just destroy-data` deletes them.

terraform {
  required_version = ">= 1.7"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = ">= 6.0"
    }
  }
}

locals {
  # Run from this folder with -var-file=../terraform.tfvars, so a relative key path is relative to the parent.
  credentials_file = startswith(var.gcp_credentials_file, "/") ? var.gcp_credentials_file : "${path.module}/../${var.gcp_credentials_file}"
  workers          = [for i in range(var.worker_count) : "worker${i + 1}"]
}

provider "google" {
  credentials = file(local.credentials_file)
  project     = var.gcp_project_id
  region      = var.gcp_region
  zone        = var.gcp_zone
}

# Blank the first time: Ansible formats a disk only if it has no filesystem, and mounts it at /mnt/data.
resource "google_compute_disk" "data" {
  for_each = toset(local.workers)
  name     = "g17-data-${each.key}"
  zone     = var.gcp_zone
  type     = "pd-balanced"
  size     = var.data_disk_size_gb
}

resource "google_compute_address" "edge" {
  name   = "g17-edge"
  region = var.gcp_region
}

output "data_disks" {
  value = { for w, d in google_compute_disk.data : w => d.name }
}

output "public_ip" {
  value = google_compute_address.edge.address
}
