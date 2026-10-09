locals {
  node_service_account = "fb-nodes@${var.project_id}.iam.gserviceaccount.com"
  node_tag             = "flight-board-node"
}

resource "google_container_cluster" "main" {
  name     = "flight-board"
  location = var.zone

  network         = google_compute_network.vpc.id
  subnetwork      = google_compute_subnetwork.nodes.id
  networking_mode = "VPC_NATIVE"

  ip_allocation_policy {
    cluster_secondary_range_name  = "pods"
    services_secondary_range_name = "services"
  }

  # The default pool is replaced by google_container_node_pool.nodes; changing a default pool
  # in place is not possible, so it is removed right after creation.
  remove_default_node_pool = true
  initial_node_count       = 1

  node_config {
    service_account = local.node_service_account
    oauth_scopes    = ["https://www.googleapis.com/auth/cloud-platform"]
    disk_size_gb    = var.node_disk_size_gb
  }

  private_cluster_config {
    enable_private_nodes = true
  }

  # Control plane only through the DNS endpoint with IAM (container.clusters.connect);
  # the IP-based endpoint is disabled.
  control_plane_endpoints_config {
    dns_endpoint_config {
      allow_external_traffic = true
    }
    ip_endpoints_config {
      enabled = false
    }
  }

  release_channel {
    channel = "REGULAR"
  }

  # No minor or node upgrades during the demo; auto-repair stays on in the node pool.
  maintenance_policy {
    daily_maintenance_window {
      start_time = "02:00"
    }
    maintenance_exclusion {
      exclusion_name = "demo-no-upgrades"
      start_time     = var.maintenance_exclusion_start
      end_time       = var.maintenance_exclusion_end
      exclusion_options {
        scope = "NO_MINOR_OR_NODE_UPGRADES"
      }
    }
  }

  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }

  addons_config {
    http_load_balancing {
      disabled = false
    }
  }

  logging_config {
    enable_components = ["SYSTEM_COMPONENTS", "WORKLOADS"]
  }

  # Prometheus is out of scope.
  monitoring_config {
    enable_components = ["SYSTEM_COMPONENTS"]
    managed_prometheus {
      enabled = false
    }
  }

  resource_labels = {
    project = "flight-board"
  }

  # terraform destroy must be able to delete the cluster (spec chapter 15).
  deletion_protection = false

  lifecycle {
    # node_config only describes the temporary default pool.
    ignore_changes = [node_config]
  }
}

resource "google_container_node_pool" "nodes" {
  name       = "flight-board-nodes"
  cluster    = google_container_cluster.main.id
  location   = var.zone
  node_count = var.node_count

  management {
    auto_repair  = true
    auto_upgrade = true
  }

  upgrade_settings {
    max_surge       = 1
    max_unavailable = 0
  }

  node_config {
    machine_type    = var.machine_type
    image_type      = "COS_CONTAINERD"
    disk_type       = "pd-balanced"
    disk_size_gb    = var.node_disk_size_gb
    service_account = local.node_service_account
    oauth_scopes    = ["https://www.googleapis.com/auth/cloud-platform"]
    tags            = [local.node_tag]

    labels = {
      project = "flight-board"
    }

    shielded_instance_config {
      enable_secure_boot          = true
      enable_integrity_monitoring = true
    }

    workload_metadata_config {
      mode = "GKE_METADATA"
    }
  }
}
