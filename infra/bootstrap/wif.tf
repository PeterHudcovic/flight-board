# Workload Identity Federation: GitHub Actions signs in to GCP without a JSON key.
resource "google_iam_workload_identity_pool" "github" {
  workload_identity_pool_id = "github"
  display_name              = "GitHub Actions"

  depends_on = [google_project_service.this]
}

resource "google_iam_workload_identity_pool_provider" "github_actions" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "github-actions"
  display_name                       = "GitHub Actions OIDC"

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }

  # Only tokens from this repository are accepted at all.
  attribute_condition = "assertion.repository_id == \"${var.github_repository_id}\""

  attribute_mapping = {
    "google.subject"          = "assertion.sub"
    "attribute.repository_id" = "assertion.repository_id"
    "attribute.ref"           = "assertion.ref"
    "attribute.workflow"      = "assertion.workflow"
    "attribute.event_name"    = "assertion.event_name"
    # "true" only for the Deploy workflow on main, triggered by push or manual dispatch.
    "attribute.deploy" = "(assertion.ref == 'refs/heads/main' && assertion.workflow == 'Deploy' && (assertion.event_name == 'push' || assertion.event_name == 'workflow_dispatch')) ? 'true' : 'false'"
  }
}

locals {
  wif_pool = google_iam_workload_identity_pool.github.name
}

# fb-tf-plan: any workflow run of this repository.
resource "google_service_account_iam_member" "tf_plan_wif" {
  service_account_id = google_service_account.tf_plan.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${local.wif_pool}/attribute.repository_id/${var.github_repository_id}"
}

# fb-deploy: only the Deploy workflow on main.
resource "google_service_account_iam_member" "deploy_wif" {
  service_account_id = google_service_account.deploy.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${local.wif_pool}/attribute.deploy/true"
}
