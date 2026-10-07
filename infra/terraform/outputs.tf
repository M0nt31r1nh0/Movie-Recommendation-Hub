output "public_url" {
  description = "The application (Grafana at /grafana)."
  value       = "https://${data.google_compute_address.edge.address}"
}

output "master_ip" {
  value = google_compute_instance.master.network_interface[0].access_config[0].nat_ip
}

output "worker_ips" {
  value = { for w in google_compute_instance.worker : w.name => w.network_interface[0].access_config[0].nat_ip }
}
