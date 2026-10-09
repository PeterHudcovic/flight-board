terraform {
  required_version = ">= 1.16.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 8.6"
    }
  }

  # Local state on the first apply. After the state bucket exists, the state is migrated to
  # gs://flight-board-prg-2610-tfstate/bootstrap (follow-up PR, see README.md).
}
