# k8s

Kubernetes part of Flight Board per `docs/contract.md`. Everything runs in namespace
`flight-board` of the GKE cluster `flight-board`.

| Path | Content | Applied by |
|---|---|---|
| `namespace.yaml` | namespace `flight-board` | author, once |
| `mongodb/operator-values.yaml` | values for chart `mongodb/mongodb-kubernetes` 1.13.0 (CRDs + operator) | author, once |
| `mongodb/mongodbcommunity.yaml` | replica set `flight-board-mongodb`: 3 members, MongoDB 8.0.32, user `flightboard-app` | author |
| `mongodb/pdb.yaml` | PDB `flight-board-mongodb`, `minAvailable: 2` | author |
| `rbac/deployer.yaml` | Role/RoleBinding `flight-board-deployer` for `fb-deploy` | author, once |
| `flight-board/` | Helm chart: ConfigMap, backend, frontend, Services, BackendConfigs, Ingress, PDBs | GitHub Actions (`deploy.yml`) |

Agents only render and validate these files; they never change the cluster.

`<repo>` below is the absolute path of the author's main checkout and `<secrets-dir>` a folder
outside the repository (never committed).

## Prerequisites

`infra/terraform` is applied and kubectl is connected through the DNS endpoint:

```
gcloud container clusters get-credentials flight-board --zone europe-west3-a --project flight-board-prg-2610 --dns-endpoint
```

## Install order

### 1. Namespace

```
kubectl apply -f <repo>\k8s\namespace.yaml
```

### 2. CRDs and operator

```
helm repo add mongodb https://mongodb.github.io/helm-charts
```

```
helm repo update
```

```
helm install mongodb-kubernetes mongodb/mongodb-kubernetes --version 1.13.0 -n flight-board -f <repo>\k8s\mongodb\operator-values.yaml
```

Helm installs the CRDs from the chart's `crds/` directory first, then the operator. Verify:

```
kubectl get crd mongodbcommunity.mongodbcommunity.mongodb.com
```

```
kubectl rollout status deployment/mongodb-kubernetes-operator -n flight-board
```

### 3. Password of the application user

Write a long random password into `<secrets-dir>\mongodb-app-password.txt` (one line, no line
break at the end), then:

```
kubectl create secret generic flight-board-mongodb-app-password -n flight-board --from-file=password=<secrets-dir>\mongodb-app-password.txt
```

```
Remove-Item -LiteralPath '<secrets-dir>\mongodb-app-password.txt'
```

Keep the password in the password manager.

### 4. MongoDB replica set

```
kubectl apply -f <repo>\k8s\mongodb\mongodbcommunity.yaml -f <repo>\k8s\mongodb\pdb.yaml
```

Wait until the phase is `Running` (a few minutes):

```
kubectl get mongodbcommunity flight-board-mongodb -n flight-board
```

```
kubectl get pods -n flight-board -o wide
```

Expected: `flight-board-mongodb-0`, `-1`, `-2`, each on a different node; 6 PVCs
(`data-volume-*`, `logs-volume-*`) `Bound`; Secret `flight-board-mongodb-admin-flightboard-app`
exists (created by the operator, used by the backend).

### 5. AeroDataBox key

Write the RapidAPI key into `<secrets-dir>\aerodatabox-api-key.txt` (one line, no line break),
then:

```
kubectl create secret generic flight-board-aerodatabox -n flight-board --from-file=api-key=<secrets-dir>\aerodatabox-api-key.txt
```

```
Remove-Item -LiteralPath '<secrets-dir>\aerodatabox-api-key.txt'
```

### 6. TLS certificate

Create a Cloudflare Origin certificate for `flights.peterhudcovic.tech` and save the certificate and the
private key as `<secrets-dir>\origin.crt` and `<secrets-dir>\origin.key`, then:

```
kubectl create secret tls flight-board-tls -n flight-board --cert=<secrets-dir>\origin.crt --key=<secrets-dir>\origin.key
```

```
Remove-Item -LiteralPath '<secrets-dir>\origin.crt', '<secrets-dir>\origin.key'
```

The Origin certificate is trusted only by Cloudflare, so the DNS record must stay proxied.

### 7. Deploy rights for GitHub Actions

```
kubectl apply -f <repo>\k8s\rbac\deployer.yaml
```

### 8. Application

Installed by the deploy workflow (`helm upgrade --install flight-board k8s/flight-board -n
flight-board --set image.tag=<git-sha>`) once the images exist in Artifact Registry. A manual
install uses the same command. The domain `flights.peterhudcovic.tech` is the chart default.

### 9. DNS

Cloudflare A record `flights.peterhudcovic.tech` → `terraform output ingress_ip` of
`infra/terraform`, proxied (orange cloud), SSL mode Full (strict). The Google load balancer needs several minutes after the
first install.

## Secrets overview

| Secret | Keys | Created by |
|---|---|---|
| `flight-board-mongodb-app-password` | `password` | author (step 3) |
| `flight-board-mongodb-admin-flightboard-app` | `connectionString.standard`, … | operator |
| `flight-board-aerodatabox` | `api-key` | author (step 5) |
| `flight-board-tls` | `tls.crt`, `tls.key` | author (step 6) |

No secret value is ever stored in this repository; `--from-file` keeps values out of the
PowerShell history.

## Cleanup (reverse order)

See `docs/contract.md` section 6 and spec chapter 15:

1. `helm uninstall flight-board -n flight-board`; wait until the load balancer is gone.
2. `kubectl delete mongodbcommunity flight-board-mongodb -n flight-board`; wait for the pods.
3. Delete the 6 PVCs (`kubectl delete pvc -n flight-board -l app.kubernetes.io/part-of=flight-board`)
   and check `gcloud compute disks list` until the disks are gone.
4. `helm uninstall mongodb-kubernetes -n flight-board`.
5. Delete the MongoDB CRDs (Helm does not remove CRDs).
6. Then `terraform destroy` of `infra/terraform`.
