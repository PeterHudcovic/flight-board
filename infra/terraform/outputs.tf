output "cluster_name" {
  value = google_container_cluster.main.name
}

output "cluster_zone" {
  value = google_container_cluster.main.location
}

output "cluster_dns_endpoint" {
  value = google_container_cluster.main.control_plane_endpoints_config[0].dns_endpoint_config[0].endpoint
}

# Target of the Cloudflare A record (proxied).
output "ingress_ip" {
  value = google_compute_global_address.ingress.address
}
