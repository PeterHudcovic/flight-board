terraform {
  required_version = ">= 1.16.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 8.6"
    }
  }

  # The bucket is created by infra/bootstrap and survives terraform destroy of this configuration.
  backend "gcs" {
    bucket = "flight-board-prg-2610-tfstate"
    prefix = "main"
  }
}
