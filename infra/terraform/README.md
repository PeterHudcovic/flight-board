# infra/terraform

Main infrastructure of Flight Board in project `flight-board-prg-2610`. Everything here can be
deleted with `terraform destroy` and created again; the persistent parts (state bucket, Workload
Identity Federation, Artifact Registry, service accounts, budget) are in `infra/bootstrap`.

| Resource | Name |
|---|---|
| VPC | `flight-board-vpc` |
| Subnet | `flight-board-subnet`: nodes `10.10.0.0/24`, pods `10.20.0.0/16`, services `10.30.0.0/20` |
| Cloud Router / Cloud NAT | `flight-board-router` / `flight-board-nat` (egress of private nodes) |
| Firewall | `flight-board-allow-lb-hc`: `35.191.0.0/16`, `130.211.0.0/22` → `tcp:8080`, tag `flight-board-node` |
| GKE cluster | `flight-board`, zonal `europe-west3-a`, release channel `REGULAR` |
| Node pool | `flight-board-nodes`: 3× `e2-standard-2` (standard, not spot), 30 GB `pd-balanced`, SA `fb-nodes` |
| Static IP | `flight-board-ip` (global, for the Ingress) |

State: `gs://flight-board-prg-2610-tfstate/main`.

## Access to the cluster

- Nodes are private (no public IPs); egress goes through Cloud NAT.
- The control plane is reachable only through the DNS endpoint with IAM
  (`container.clusters.connect`); the IP-based endpoint is disabled.

## Demo maintenance exclusion

`demo-no-upgrades` with scope `NO_MINOR_OR_NODE_UPGRADES` from 2026-10-10 until
2026-10-15 23:59 Europe/Prague (`maintenance_exclusion_start` / `_end` in `variables.tf`):

- No automatic minor upgrades and no node upgrades, so nodes are not drained or restarted by an
  upgrade during the demo.
- Control plane patch upgrades are still allowed (short Kubernetes API interruption; running pods
  are not affected).
- Node auto-repair stays on: a broken node can still be recreated.
- If the apply rejects the start time, pass the current UTC time, e.g.
  `-var="maintenance_exclusion_start=2026-10-11T08:00:00Z"`.
- After the end date normal maintenance (daily window from 02:00 UTC) applies again.

## Steps (author)

`<repo>` is the absolute path of the author's main checkout. Prerequisite: `infra/bootstrap` is
applied (bucket, `fb-nodes`, APIs).

1. Connect to the state in the bucket:

   ```
   terraform -chdir=<repo>\infra\terraform init
   ```

2. Create and review the plan:

   ```
   terraform -chdir=<repo>\infra\terraform plan -out=<repo>\infra\terraform\main.tfplan
   ```

3. Apply exactly that plan (the cluster takes 10–15 minutes; billing starts now):

   ```
   terraform -chdir=<repo>\infra\terraform apply <repo>\infra\terraform\main.tfplan
   ```

4. Connect kubectl and k9s through the DNS endpoint and check 3 nodes in `Ready`:

   ```
   gcloud container clusters get-credentials flight-board --zone europe-west3-a --project flight-board-prg-2610 --dns-endpoint
   ```

   ```
   kubectl get nodes
   ```

Delete the plan file after the apply.

## Cost (estimate, europe-west3)

| Item | Per hour |
|---|---|
| 3× `e2-standard-2` | ≈ 0.26 USD |
| Boot disks 3 × 30 GB `pd-balanced` | ≈ 0.016 USD |
| GKE cluster fee | 0 (one zonal cluster is covered by the GKE free tier) |
| Cloud NAT and processed data | ≈ 0.005 USD |
| Static IP | ≈ 0.01 USD |
| **Total** | **≈ 0.29 USD** |

The Ingress load balancer and the MongoDB disks are created later by Kubernetes and add about
0.03 USD per hour.

## Destroy

Follow the cleanup order of spec chapter 15 and `docs/contract.md` section 6 first (application,
MongoDB, PVCs, load balancer resources), then:

```
terraform -chdir=<repo>\infra\terraform destroy
```
