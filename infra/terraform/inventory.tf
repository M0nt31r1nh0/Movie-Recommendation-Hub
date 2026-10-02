# Ansible's inventory, from what was just created: no IP addresses to copy by hand.
resource "local_file" "inventory" {
  filename        = "${path.module}/../ansible/inventory.ini"
  file_permission = "0644"
  content = templatefile("${path.module}/inventory.ini.tftpl", {
    ssh_user    = var.ssh_user
    public_host = google_compute_address.edge.address
    master = {
      name    = google_compute_instance.master.name
      public  = google_compute_instance.master.network_interface[0].access_config[0].nat_ip
      private = google_compute_instance.master.network_interface[0].network_ip
    }
    workers = [for w in google_compute_instance.worker : {
      name    = w.name
      public  = w.network_interface[0].access_config[0].nat_ip
      private = w.network_interface[0].network_ip
    }]
  })
}
