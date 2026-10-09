variable "project_id" {
  description = "GCP project ID."
  type        = string
  default     = "flight-board-prg-2610"
}

variable "region" {
  description = "Region for the state bucket and Artifact Registry."
  type        = string
  default     = "europe-west3"
}

variable "github_repository_id" {
  description = "Numeric GitHub repository ID (does not change when the repository is renamed)."
  type        = string
  default     = "1412231446"
}

variable "billing_account_id" {
  description = "Billing account ID (XXXXXX-XXXXXX-XXXXXX). Passed with -var, never committed."
  type        = string

  validation {
    condition     = can(regex("^[0-9A-F]{6}-[0-9A-F]{6}-[0-9A-F]{6}$", var.billing_account_id))
    error_message = "billing_account_id must look like XXXXXX-XXXXXX-XXXXXX."
  }
}

variable "budget_amount" {
  description = "Monthly budget amount in budget_currency (whole units)."
  type        = number
  default     = 1000
}

variable "budget_currency" {
  description = "Currency of the billing account. Must match the billing account currency."
  type        = string
  default     = "CZK"
}
