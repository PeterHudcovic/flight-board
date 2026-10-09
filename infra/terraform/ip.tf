# Global static IP for the GKE Ingress (docs/contract.md section 2).
resource "google_compute_global_address" "ingress" {
  name = "flight-board-ip"
}
