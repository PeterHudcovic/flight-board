# Roles follow docs/contract.md section 5. No service account keys are created.

resource "google_service_account" "tf_plan" {
  account_id   = "fb-tf-plan"
  display_name = "Flight Board terraform plan (GitHub Actions)"

  depends_on = [google_project_service.this]
}

resource "google_service_account" "deploy" {
  account_id   = "fb-deploy"
  display_name = "Flight Board deploy (GitHub Actions)"

  depends_on = [google_project_service.this]
}

resource "google_service_account" "nodes" {
  account_id   = "fb-nodes"
  display_name = "Flight Board GKE nodes"

  depends_on = [google_project_service.this]
}

resource "google_project_iam_member" "tf_plan_viewer" {
  project = var.project_id
  role    = "roles/viewer"
  member  = "serviceAccount:${google_service_account.tf_plan.email}"
}

# Includes container.clusters.connect for the DNS endpoint; write access inside the cluster
# comes only from the namespace RBAC Role (k8s/).
resource "google_project_iam_member" "deploy_container_viewer" {
  project = var.project_id
  role    = "roles/container.viewer"
  member  = "serviceAccount:${google_service_account.deploy.email}"
}

resource "google_project_iam_member" "nodes_default" {
  project = var.project_id
  role    = "roles/container.defaultNodeServiceAccount"
  member  = "serviceAccount:${google_service_account.nodes.email}"
}
