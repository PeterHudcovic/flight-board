output "project_number" {
  value = data.google_project.this.number
}

output "state_bucket" {
  value = google_storage_bucket.tfstate.name
}

output "artifact_registry_url" {
  value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.images.repository_id}"
}

# Value for google-github-actions/auth "workload_identity_provider".
output "workload_identity_provider" {
  value = google_iam_workload_identity_pool_provider.github_actions.name
}

output "service_accounts" {
  value = {
    tf_plan = google_service_account.tf_plan.email
    deploy  = google_service_account.deploy.email
    nodes   = google_service_account.nodes.email
  }
}
