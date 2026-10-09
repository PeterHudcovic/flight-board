# Remote state for infra/bootstrap (prefix "bootstrap") and infra/terraform (prefix "main").
resource "google_storage_bucket" "tfstate" {
  name     = "${var.project_id}-tfstate"
  location = var.region

  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"

  versioning {
    enabled = true
  }

  # Keep the live version plus the 10 newest noncurrent versions of every state file.
  # num_newer_versions counts the live version too, so 11 newer versions = 10 kept old ones.
  lifecycle_rule {
    condition {
      num_newer_versions = 11
      with_state         = "ARCHIVED"
    }
    action {
      type = "Delete"
    }
  }

  lifecycle {
    prevent_destroy = true
  }

  depends_on = [google_project_service.this]
}

# terraform plan in GitHub Actions needs to read the state and write the lock file.
resource "google_storage_bucket_iam_member" "tf_plan_state" {
  bucket = google_storage_bucket.tfstate.name
  role   = "roles/storage.objectUser"
  member = "serviceAccount:${google_service_account.tf_plan.email}"
}
