# infra/bootstrap

One-time Terraform for project `flight-board-prg-2610`. It creates the persistent resources that
must exist before the main infrastructure (`infra/terraform`) and GitHub Actions:

| Resource | Name |
|---|---|
| Google APIs | see `apis.tf` (never disabled on destroy) |
| State bucket | `flight-board-prg-2610-tfstate` (versioning, 10 old versions kept) |
| Artifact Registry | `flight-board` (Docker, `europe-west3`; keeps the 10 newest versions, deletes untagged after 7 days) |
| Workload Identity Federation | pool `github`, provider `github-actions` (only this repository, by numeric ID) |
| Service accounts | `fb-tf-plan`, `fb-deploy`, `fb-nodes` (roles: `docs/contract.md` section 5) |
| Billing budget | 1000 CZK per month, alerts at 50/90/100 % to billing account admins |

The state bucket and the Artifact Registry have `prevent_destroy`. The main `terraform destroy`
does not touch anything here.

## Who runs it

The author, once, from their own computer, with Application Default Credentials (ADC) of an
account that is project Owner and Billing Account Administrator. Agents never run `apply`.

`<repo>` below is the absolute path of the author's main checkout.

## Steps

1. Check the Terraform version (minimum 1.16.0):

   ```
   terraform version
   ```

2. Download the provider (creates nothing in GCP):

   ```
   terraform -chdir=<repo>\infra\bootstrap init
   ```

3. Create the plan. The billing account ID is in the GCP console under Billing; it is passed on
   the command line and never committed. If the billing account currency is not CZK, add
   `-var="budget_currency=EUR" -var="budget_amount=40"` (or the equivalent).

   ```
   terraform -chdir=<repo>\infra\bootstrap plan -var="billing_account_id=XXXXXX-XXXXXX-XXXXXX" -out=<repo>\infra\bootstrap\bootstrap.tfplan
   ```

4. Review the plan, then apply exactly that plan:

   ```
   terraform -chdir=<repo>\infra\bootstrap apply <repo>\infra\bootstrap\bootstrap.tfplan
   ```

5. Note the outputs (`terraform -chdir=<repo>\infra\bootstrap output`): the Workload Identity
   provider name and the service account e-mails are needed by the GitHub Actions workflows.

`*.tfplan`, `*.tfstate` and `*.tfvars` are ignored by git. A plan file can contain secrets; delete
it after the apply.

## Migrate the state to the bucket

The `gcs` backend cannot point to a bucket that does not exist yet, so the first apply uses local
state. A follow-up PR adds `backend.tf` (bucket `flight-board-prg-2610-tfstate`, prefix
`bootstrap`); the author then runs:

```
terraform -chdir=<repo>\infra\bootstrap init -migrate-state
```

After a successful migration the local `terraform.tfstate` and `terraform.tfstate.backup` are
deleted from disk.

## Final cleanup after the demo

Delete the whole project. This stops all billing; `prevent_destroy` only protects against
`terraform destroy` and does not block a project deletion:

```
gcloud projects delete flight-board-prg-2610
```

The project is shut down immediately and can be restored for 30 days
(`gcloud projects undelete flight-board-prg-2610`); after that it is deleted permanently. The
project ID cannot be reused.
