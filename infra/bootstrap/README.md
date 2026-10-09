# infra/bootstrap

One-time Terraform for project `flight-board-prg-2610`. It creates the persistent resources that
must exist before the main infrastructure (`infra/terraform`) and GitHub Actions:

| Resource | Name |
|---|---|
| Google APIs | see `apis.tf` (never disabled on destroy) |
| State bucket | `flight-board-prg-2610-tfstate` (versioning; the current version plus 10 older versions are kept) |
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

## Steps (first setup)

These steps describe the first setup with local state. The state now lives in the bucket
(`backend.tf`); for later changes only `init`, `plan` and `apply` against the bucket are needed.

0. Enable the two APIs that Terraform itself needs before it can enable the others. Without
   them the first apply fails with `SERVICE_DISABLED` (the provider sends the project as quota
   project because of `user_project_override`):

   ```
   gcloud services enable cloudresourcemanager.googleapis.com serviceusage.googleapis.com --project flight-board-prg-2610
   ```

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

The `gcs` backend cannot point to a bucket that does not exist yet, so the first apply used local
state. `backend.tf` (bucket `flight-board-prg-2610-tfstate`, prefix `bootstrap`) was added after
the first apply; the author then runs these steps once:

1. Migrate the state (answer `yes` when Terraform asks to copy the existing state):

   ```
   terraform -chdir=<repo>\infra\bootstrap init -migrate-state
   ```

2. Verify that Terraform now reads the state from the bucket. The list must contain the
   resources from the apply (bucket, repository, pool, provider, service accounts, budget):

   ```
   terraform -chdir=<repo>\infra\bootstrap state list
   ```

3. Verify that the state object exists in the bucket (expected: `default.tfstate`):

   ```
   gcloud storage ls gs://flight-board-prg-2610-tfstate/bootstrap/
   ```

4. Only when steps 2 and 3 succeeded, delete the local state files. Terraform does not delete
   them, and the backup contains the full previous state in plain text:

   ```
   Remove-Item -LiteralPath '<repo>\infra\bootstrap\terraform.tfstate', '<repo>\infra\bootstrap\terraform.tfstate.backup' -ErrorAction SilentlyContinue
   ```

5. Check that both files are gone (expected: `False` twice):

   ```
   Test-Path -LiteralPath '<repo>\infra\bootstrap\terraform.tfstate', '<repo>\infra\bootstrap\terraform.tfstate.backup'
   ```

A setup in a new project (new project ID, new bucket name) starts with `backend.tf` removed
locally, runs the first apply with local state, then restores `backend.tf` with the new bucket
name and migrates as above.

## Final cleanup after the demo

Delete the whole project. This stops all billing; `prevent_destroy` only protects against
`terraform destroy` and does not block a project deletion:

```
gcloud projects delete flight-board-prg-2610
```

The project is shut down immediately and can be restored for 30 days
(`gcloud projects undelete flight-board-prg-2610`); after that it is deleted permanently. The
project ID cannot be reused.
