resource "google_compute_network" "vpc" {
  name                    = "flight-board-vpc"
  auto_create_subnetworks = false
  routing_mode            = "REGIONAL"
}

# VPC-native: nodes use the primary range, pods and services the secondary ranges.
resource "google_compute_subnetwork" "nodes" {
  name          = "flight-board-subnet"
  region        = var.region
  network       = google_compute_network.vpc.id
  ip_cidr_range = "10.10.0.0/24"

  # Private nodes reach Google APIs (Artifact Registry, logging) without public IPs.
  private_ip_google_access = true

  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = "10.20.0.0/16"
  }

  secondary_ip_range {
    range_name    = "services"
    ip_cidr_range = "10.30.0.0/20"
  }
}

# Egress for private nodes: quay.io images and the AeroDataBox API.
resource "google_compute_router" "router" {
  name    = "flight-board-router"
  region  = var.region
  network = google_compute_network.vpc.id
}

resource "google_compute_router_nat" "nat" {
  name                               = "flight-board-nat"
  router                             = google_compute_router.router.name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"

  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}

# Load balancer health checks (docs/contract.md sections 1 and 4): both use port 8080.
resource "google_compute_firewall" "lb_health_checks" {
  name          = "flight-board-allow-lb-hc"
  network       = google_compute_network.vpc.name
  direction     = "INGRESS"
  source_ranges = ["35.191.0.0/16", "130.211.0.0/22"]
  target_tags   = ["flight-board-node"]

  allow {
    protocol = "tcp"
    ports    = ["8080"]
  }
}
