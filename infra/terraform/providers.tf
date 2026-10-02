# The Google Cloud resources for the self-hosted Kubernetes cluster: one control-plane VM and
# var.worker_count worker VMs, on their own network. Terraform authenticates with a service account key
# (var.gcp_credentials_file), as in the labs.

terraform {
  required_version = ">= 1.6"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = ">= 6.0"
    }
    local = {
      source  = "hashicorp/local"
      version = ">= 2.5"
    }
  }
}

provider "google" {
  credentials = file(var.gcp_credentials_file)
  project     = var.gcp_project_id
  region      = var.gcp_region
  zone        = var.gcp_zone
}
