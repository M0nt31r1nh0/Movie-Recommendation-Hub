# Set at least gcp_project_id in terraform.tfvars (see terraform.tfvars.example).

variable "gcp_project_id" {
  description = "The project's ID (not its name): Google Cloud console, project picker at the top."
  type        = string
}

variable "gcp_credentials_file" {
  description = "The service account's JSON key, downloaded from IAM > Service accounts > Keys."
  type        = string
  default     = "credentials.json"
}

variable "gcp_region" {
  type    = string
  default = "europe-west4"
}

variable "gcp_zone" {
  description = "If GCP answers that the zone lacks resources, try another one in the same region."
  type        = string
  default     = "europe-west4-b"
}

variable "master_machine_type" {
  description = "kubeadm needs at least 2 vCPUs and 2 GB on the control plane, which runs no application pods."
  type        = string
  default     = "e2-standard-2"
}

variable "worker_machine_type" {
  description = "The workers run everything: Ollama alone wants 2 vCPUs and ~3 GB, Kafka ~1 GB per broker."
  type        = string
  default     = "e2-standard-4"
}

variable "worker_count" {
  description = "Free-trial accounts are limited to 8 vCPUs in all: 1 x e2-standard-2 + 2 x e2-standard-4 fits exactly."
  type        = number
  default     = 2
}

variable "disk_size_gb" {
  description = "Per VM: container images (Ollama's is ~3 GB), the models (~2.5 GB) and the databases."
  type        = number
  default     = 50
}

variable "image" {
  type    = string
  default = "ubuntu-os-cloud/ubuntu-2404-lts-amd64"
}

variable "ssh_user" {
  type    = string
  default = "ubuntu"
}

variable "ssh_public_key_file" {
  description = "Created by `just keygen`."
  type        = string
  default     = "../ssh/id_ed25519.pub"
}

variable "admin_cidrs" {
  description = "Who may reach SSH (22) and the Kubernetes API (6443). Narrow it to your own IP (x.x.x.x/32) if you can."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "subnet_cidr" {
  description = "The VMs' private addresses. Must not overlap the pod network (192.168.0.0/16, Calico's default)."
  type        = string
  default     = "10.10.0.0/24"
}
