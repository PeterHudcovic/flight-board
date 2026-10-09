variable "project_id" {
  description = "GCP project ID."
  type        = string
  default     = "flight-board-prg-2610"
}

variable "region" {
  description = "Region of the network."
  type        = string
  default     = "europe-west3"
}

variable "zone" {
  description = "Zone of the zonal GKE cluster."
  type        = string
  default     = "europe-west3-a"
}

variable "node_count" {
  description = "Number of standard (non-spot) nodes."
  type        = number
  default     = 3
}

variable "machine_type" {
  description = "Node machine type."
  type        = string
  default     = "e2-standard-2"
}

variable "node_disk_size_gb" {
  description = "Node boot disk size in GB."
  type        = number
  default     = 30
}

variable "maintenance_exclusion_start" {
  description = "Start of the demo maintenance exclusion (RFC 3339, UTC)."
  type        = string
  default     = "2026-10-10T00:00:00Z"
}

variable "maintenance_exclusion_end" {
  description = "End of the demo maintenance exclusion (RFC 3339, UTC); 2026-10-15 23:59 Europe/Prague."
  type        = string
  default     = "2026-10-15T21:59:00Z"
}
