# Read from the parent's terraform.tfvars (just persistent passes -var-file=../terraform.tfvars): the same
# project, key, region, zone and number of workers as the VMs.

variable "gcp_project_id" {
  type = string
}

variable "gcp_credentials_file" {
  type    = string
  default = "credentials.json"
}

variable "gcp_region" {
  type    = string
  default = "europe-west4"
}

variable "gcp_zone" {
  description = "A disk can only be attached to VMs in its own zone: keep it the same as the VMs'."
  type        = string
  default     = "europe-west4-b"
}

variable "worker_count" {
  type    = number
  default = 2
}

variable "data_disk_size_gb" {
  description = "Per worker: the volumes placed on it (persistent_volumes in ansible/group_vars/all.yml)."
  type        = number
  default     = 40
}
