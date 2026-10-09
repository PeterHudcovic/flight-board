# Credentials: Application Default Credentials (ADC) of the author.
# user_project_override + billing_project are required for the Billing Budget API with user
# credentials; quota is charged to this project.
provider "google" {
  project               = var.project_id
  region                = var.region
  user_project_override = true
  billing_project       = var.project_id

  default_labels = {
    project = "flight-board"
  }
}
