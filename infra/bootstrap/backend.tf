# State of the bootstrap configuration, in the bucket it created itself. The first apply used local
# state; it was migrated with terraform init -migrate-state (see README.md).
terraform {
  backend "gcs" {
    bucket = "flight-board-prg-2610-tfstate"
    prefix = "bootstrap"
  }
}
