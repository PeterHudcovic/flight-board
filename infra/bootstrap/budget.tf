# Budget alert only sends e-mails to the billing account admins; it never stops billing.
resource "google_billing_budget" "project" {
  billing_account = var.billing_account_id
  display_name    = "flight-board-prg-2610"

  budget_filter {
    projects = ["projects/${data.google_project.this.number}"]
  }

  amount {
    specified_amount {
      currency_code = var.budget_currency
      units         = tostring(var.budget_amount)
    }
  }

  threshold_rules {
    threshold_percent = 0.5
  }

  threshold_rules {
    threshold_percent = 0.9
  }

  threshold_rules {
    threshold_percent = 1.0
  }

  # No all_updates_rule: the default e-mail goes to billing account admins and users.

  depends_on = [google_project_service.this]
}
