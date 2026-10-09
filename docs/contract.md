# App ↔ infra contract

Decisions shared by the application (Codex, `app/`) and the infrastructure (Claude Code,
`infra/`, `k8s/`, `.github/`) that are fixed before implementation. Source: Appendix A4 and A5 of
`docs/flight-board-spec-v8-en.pdf`. A change to this file needs a PR approved by the author;
both agents follow it exactly.

Common values used below:

| Name | Value |
|---|---|
| GCP project ID | `flight-board-prg-2610` |
| Region / zone | `europe-west3` / `europe-west3-a` |
| Kubernetes namespace | `flight-board` |
| Common label | `app.kubernetes.io/part-of: flight-board` (Kubernetes), `project=flight-board` (GCP) |

## 1. Network

- One standard external GKE Ingress `flight-board` (class `gce`) in namespace `flight-board`.
- Routing:

  | Path (pathType `Prefix`) | Service | Service port |
  |---|---|---|
  | `/api` | `flight-board-backend` | 80 → container 8080 |
  | `/` | `flight-board-frontend` | 80 → container 8080 |

- Admin (`/admin/*`, port 8082) and Actuator (port 8081) are never routed publicly: port 8081
  and 8082 are not in any Service, and only `/api` reaches the backend.
- HTTP is disabled on the load balancer: annotation `kubernetes.io/ingress.allow-http: "false"`.
  Cloudflare (Full strict) connects to the origin over HTTPS only.
- TLS: Cloudflare Origin certificate in Secret `flight-board-tls` (type `kubernetes.io/tls`),
  created manually by the author; referenced in `spec.tls` of the Ingress.
- Firewall for load balancer health checks: rule `flight-board-allow-lb-hc` in VPC
  `flight-board-vpc`, source ranges `35.191.0.0/16` and `130.211.0.0/22`, allow `tcp:8081`,
  target tag `flight-board-node`. The GKE Ingress controller opens only the serving ports; port
  8081 is needed because the backend health check does not use the serving port (section 4).
- Local Docker Compose has no Ingress: the frontend nginx container proxies `/api/` to
  `http://backend:8080`. In GKE the Ingress does the routing and nginx only serves static files.

## 2. IP address

- Global static IP `flight-board-ip` (`google_compute_global_address` in `infra/terraform`),
  exported as Terraform output `ingress_ip`.
- Ingress annotation `kubernetes.io/ingress.global-static-ip-name: flight-board-ip`; Helm value
  `ingress.staticIpName`.
- The Cloudflare DNS A record points to this IP and stays proxied (orange cloud).

## 3. Ports and health

| Container | Port | Bind address | Purpose | In a Service |
|---|---|---|---|---|
| backend | 8080 | `0.0.0.0` | public API `/api/*` | yes |
| backend | 8081 | `0.0.0.0` | Actuator, only the `health` endpoint exposed | no |
| backend | 8082 | `127.0.0.1` | admin `POST /admin/pause`, `/admin/resume`, `/admin/fetch-now` | no |
| frontend (nginx, non-root) | 8080 | `0.0.0.0` | static SPA (Single Page Application) and `GET /healthz` | yes |
| MongoDB | 27017 | – | replica set | headless Service created by the operator |

Probes (backend):

| Probe | Request | Health group content |
|---|---|---|
| startup | `GET :8081/actuator/health/liveness`, `periodSeconds 5`, `failureThreshold 30` | `livenessState` |
| liveness | `GET :8081/actuator/health/liveness`, `periodSeconds 10`, `failureThreshold 3` | `livenessState` only, no MongoDB |
| readiness | `GET :8081/actuator/health/readiness`, `periodSeconds 10`, `failureThreshold 3` | `readinessState`, `mongo` |

- AeroDataBox is in no health group: a source outage never restarts or unreadies a pod.
- Frontend: liveness and readiness `GET :8080/healthz` (static 200 from nginx).
- Probes never use the admin port. Port 8081 binds to `0.0.0.0` because the kubelet and the load
  balancer cannot reach `127.0.0.1` inside the pod.
- Spring properties: `server.port=8080`, `management.server.port=8081`,
  `management.endpoints.web.exposure.include=health`,
  `management.endpoint.health.probes.enabled=true`,
  `management.endpoint.health.group.readiness.include=readinessState,mongo`.
- Admin access: `kubectl port-forward -n flight-board deploy/flight-board-backend 8082:8082`
  (k9s: Shift+F), then `POST http://127.0.0.1:8082/admin/...`.
- Acceptance test (Codex, backend integration test): `POST /admin/pause` returns 404 on port 8080
  and on port 8081, and succeeds on `127.0.0.1:8082`; nothing listens on port 8082 on any
  non-loopback address.

## 4. Load balancer

Container-native load balancing (NEG) with an own BackendConfig per Service:

| BackendConfig | Request path | Port | Interval / timeout | Healthy / unhealthy threshold |
|---|---|---|---|---|
| `flight-board-backend` | `/actuator/health/readiness` | 8081 | 15 s / 5 s | 1 / 2 |
| `flight-board-frontend` | `/healthz` | 8080 | 15 s / 5 s | 1 / 2 |

- Both use `type: HTTP`.
- Service annotations: `cloud.google.com/neg: '{"ingress": true}'` and
  `cloud.google.com/backend-config: '{"default": "<service name>"}'`.
- Without a BackendConfig GCP would check `/` on the serving port, which the backend does not
  answer with 200.
- When MongoDB is down, the backend is unhealthy and the load balancer returns 502; the frontend
  then shows its last copy with "Connection lost" (Appendix A6).

## 5. Permissions

### GCP service accounts

| Account | Roles | Used by |
|---|---|---|
| `fb-tf-plan@flight-board-prg-2610.iam.gserviceaccount.com` | `roles/viewer` (project), `roles/storage.objectUser` (state bucket, for the state lock), `roles/billing.viewer` (billing account, for the budget) | `pr.yml`: `terraform plan` |
| `fb-deploy@flight-board-prg-2610.iam.gserviceaccount.com` | `roles/artifactregistry.writer` (repository `flight-board`), `roles/container.viewer` (project; includes `container.clusters.connect` for the DNS endpoint) | `deploy.yml`: push images, `helm upgrade` |
| `fb-nodes@flight-board-prg-2610.iam.gserviceaccount.com` | `roles/container.defaultNodeServiceAccount` (project), `roles/artifactregistry.reader` (repository `flight-board`) | GKE nodes |

- Nobody else gets a service account. `terraform apply` is run only by the author with the
  author's own account.
- Application pods do not call GCP APIs, so there is no Workload Identity binding for pods.

### Workload Identity Federation

- Pool `github`, provider `github-actions`, issuer `https://token.actions.githubusercontent.com`.
- Provider condition: `assertion.repository_id == "<numeric repository ID>"` (the ID does not
  change when the repository is renamed).
- `fb-tf-plan` can be impersonated from any branch of this repository.
- `fb-deploy` can be impersonated only when `assertion.ref == "refs/heads/main"`,
  `assertion.workflow == "Deploy"` and `assertion.event_name` is `push` or `workflow_dispatch`.
- No `terraform plan` for PRs from forks: the plan job runs only when
  `github.event.pull_request.head.repo.full_name == github.repository` (forks get no OIDC token
  for this provider anyway).

### Kubernetes RBAC

Role `flight-board-deployer` in namespace `flight-board`, bound with RoleBinding
`flight-board-deployer` to User `fb-deploy@flight-board-prg-2610.iam.gserviceaccount.com`:

| apiGroups | Resources | Verbs |
|---|---|---|
| `""` | `services`, `configmaps`, `secrets`, `serviceaccounts` | get, list, watch, create, update, patch, delete |
| `""` | `pods`, `events` | get, list, watch |
| `apps` | `deployments` | get, list, watch, create, update, patch, delete |
| `apps` | `replicasets` | get, list, watch |
| `policy` | `poddisruptionbudgets` | get, list, watch, create, update, patch, delete |
| `networking.k8s.io` | `ingresses` | get, list, watch, create, update, patch, delete |
| `cloud.google.com` | `backendconfigs` | get, list, watch, create, update, patch, delete |

- `secrets` is needed because Helm stores its release history in Secrets.
- The deployer has no rights on `mongodbcommunity` or on the operator; MongoDB is managed only by
  the author.

## 6. Versions and storage

### Choice: MongoDB Controllers for Kubernetes (MCK)

| Option | Decision |
|---|---|
| Bitnami `mongodb` chart | Rejected. Since 28 Aug 2025 versioned images are only in the frozen `bitnamilegacy` repository (no updates) or in the paid Bitnami Secure Images. |
| MongoDB Community Operator (`community-operator` chart) | Rejected. Deprecated, support ended in November 2025. |
| Percona Operator for MongoDB | Not chosen. Works, but adds its own server distribution and backup tooling we do not need. |
| **MCK, `MongoDBCommunity` resource** | **Chosen.** Official MongoDB operator, successor of the community and enterprise operators; replica set, SCRAM users and a generated connection string Secret. |

### Versions

| Item | Value |
|---|---|
| Helm repository | `https://mongodb.github.io/helm-charts` (name `mongodb`) |
| Chart | `mongodb/mongodb-kubernetes` version `1.13.0` |
| MongoDB image | `quay.io/mongodb/mongodb-community-server:8.0.32-ubi9` (chart value `community.mongodb.imageType=ubi9`; the chart default `ubi8` is being phased out) |
| Agent image | `quay.io/mongodb/mongodb-agent:109.0.0.9285-1` (chart default) |
| `MongoDBCommunity.spec.version` | `8.0.32` |
| Local (Docker Compose, Testcontainers) | `mongo:8.0.32`, single-member replica set `rs0` |

MongoDB 8.0, not 9.0: the Java driver shipped with Spring Boot 3.5 (5.4/5.5) fully supports
MongoDB up to 8.x; full 9.0 support starts with driver 5.12.

### Resources and storage

| Item | Value |
|---|---|
| Data members | 3, required pod anti-affinity on `kubernetes.io/hostname` |
| `mongod` container | requests 250m CPU / 512Mi, limit 1Gi memory |
| WiredTiger cache | `storage.wiredTiger.engineConfig.cacheSizeGB: 0.25` |
| PVCs per member | `data-volume` 5Gi, `logs-volume` 2Gi (6 PVCs in total) |
| StorageClass | `standard-rwo` (pd-balanced, `reclaimPolicy: Delete`, `WaitForFirstConsumer`) |

PodDisruptionBudgets (all in namespace `flight-board`):

| PDB | Selector | Replicas | `minAvailable` |
|---|---|---|---|
| `flight-board-mongodb` | MongoDB pods | 3 | 2 |
| `flight-board-backend` | backend pods | 2 | 1 |
| `flight-board-frontend` | frontend pods | 2 | 1 |

### Operator lifecycle

- The operator is a Deployment that watches `MongoDBCommunity` resources. From the resource it
  creates a StatefulSet (one pod per member), a headless Service, the users and the connection
  string Secret, and it keeps them in that state.
- Changes are made in the `MongoDBCommunity` manifest (`kubectl apply`), never directly on the
  StatefulSet; the operator would overwrite them.
- `operator-values.yaml` sets `community.mongodb.imageType: ubi9` and limits
  `operator.watchedResources` to `mongodbcommunity`; the operator watches only namespace
  `flight-board`.
- Helm installs CRDs only on the first install and never upgrades or deletes them. Before an
  operator upgrade the new CRDs are applied with `kubectl apply -f` from the new chart version.
- Upgrading the operator (`helm upgrade`) does not restart MongoDB. Changing `spec.version`
  makes the operator restart the members one by one (rolling).
- Deleting the `MongoDBCommunity` resource deletes the StatefulSet and pods, but not the PVCs;
  the data disks stay until the PVCs are deleted.
- The operator and the replica set are installed once by the author, not by the application
  deploy (spec chapter 13).

### Install order

1. CRDs and operator: `helm install mongodb-kubernetes mongodb/mongodb-kubernetes --version
   1.13.0 -n flight-board -f k8s/mongodb/operator-values.yaml`. Helm first installs the CRDs from
   the chart's `crds/` directory, then the operator Deployment.
2. Verify the CRD `mongodbcommunity.mongodbcommunity.mongodb.com` exists and the operator
   Deployment is ready.
3. Secret `flight-board-mongodb-app-password` (author, manually).
4. `kubectl apply -f k8s/mongodb/mongodbcommunity.yaml` and the PDB.
5. Wait until the resource reports phase `Running` and the replica set has a primary.

### Cleanup order (reverse)

1. `helm uninstall` of the application.
2. `kubectl delete mongodbcommunity flight-board-mongodb -n flight-board`; wait until the pods are
   gone.
3. Delete the 6 PVCs (`data-volume-*` and `logs-volume-*`); verify with
   `gcloud compute disks list` that the disks are gone.
4. `helm uninstall mongodb-kubernetes -n flight-board`.
5. Delete the CRDs (Helm does not remove CRDs).
6. Only then `terraform destroy` (spec chapter 15).

## 7. Configuration

### Names

| Item | Name |
|---|---|
| Helm release (application) | `flight-board` |
| Helm release (operator) | `mongodb-kubernetes` |
| Deployments and Services | `flight-board-backend`, `flight-board-frontend` |
| ConfigMap | `flight-board-config` |
| `MongoDBCommunity` resource and replica set | `flight-board-mongodb` |
| Database | `flightboard` |
| Collections | `fetch_control`, `fetch_runs`, `board_current` |
| Application DB user | `flightboard-app`, role `readWrite` on `flightboard` |
| GKE cluster | `flight-board` |
| VPC / subnet | `flight-board-vpc` / `flight-board-subnet` |
| Cloud Router / Cloud NAT | `flight-board-router` / `flight-board-nat` |
| Node network tag | `flight-board-node` |
| Artifact Registry repository | `flight-board` (Docker, `europe-west3`) |
| Images | `europe-west3-docker.pkg.dev/flight-board-prg-2610/flight-board/backend:<git-sha>`, `.../frontend:<git-sha>` |
| Terraform state bucket | `flight-board-prg-2610-tfstate`, prefixes `bootstrap` and `main` |

### Secrets

| Secret | Key | Used as | Created by |
|---|---|---|---|
| `flight-board-aerodatabox` | `api-key` | env `AERODATABOX_API_KEY` | author |
| `flight-board-mongodb-app-password` | `password` | password of `flightboard-app` | author |
| `flight-board-mongodb-admin-flightboard-app` | `connectionString.standard` | env `SPRING_DATA_MONGODB_URI` | operator (generated) |
| `flight-board-tls` | `tls.crt`, `tls.key` | Ingress TLS | author |

### ConfigMap `flight-board-config`

Environment variables with prefix `FLIGHTBOARD_` map to Spring properties `flightboard.*`.

| Variable | GKE value | Meaning |
|---|---|---|
| `FLIGHTBOARD_SOURCE` | `aerodatabox` | `stub` or `aerodatabox` (see below) |
| `FLIGHTBOARD_AERODATABOX_BASE_URL` | `https://aerodatabox.p.rapidapi.com` | source base URL |
| `FLIGHTBOARD_AERODATABOX_HOST` | `aerodatabox.p.rapidapi.com` | value of header `X-RapidAPI-Host` |
| `FLIGHTBOARD_FETCH_INTERVAL` | `PT30M` | time between fetches |
| `FLIGHTBOARD_FETCH_CHECK_INTERVAL` | `PT1M` | how often each replica checks `fetch_control` |
| `FLIGHTBOARD_FETCH_LOCK_DURATION` | `PT5M` | `lockedUntil` = now + this |
| `FLIGHTBOARD_FETCH_RUN_TIMEOUT` | `PT2M` | limit for one run |
| `FLIGHTBOARD_BOARD_STALE_AFTER` | `PT75M` | data older than this → `stale = true` |
| `FLIGHTBOARD_BOARD_MAX_AGE` | `PT12H` | older board is not returned (503 `NO_DATA`) |
| `FLIGHTBOARD_SOURCE_AIRPORT` | `PRG` | IATA code |
| `FLIGHTBOARD_SOURCE_OFFSET_MINUTES` | `-60` | window start relative to now |
| `FLIGHTBOARD_SOURCE_DURATION_MINUTES` | `720` | window length (maximum, section 8) |
| `FLIGHTBOARD_BOARD_TERMINAL` | `2` | Terminal 2 mode; empty = all departures |
| `FLIGHTBOARD_BOARD_MAX_FLIGHTS` | `36` | rows on the board |
| `FLIGHTBOARD_BOARD_TIMEZONE` | `Europe/Prague` | time zone for display and sorting |
| `FLIGHTBOARD_ADMIN_PORT` / `FLIGHTBOARD_ADMIN_ADDRESS` | `8082` / `127.0.0.1` | admin listener |
| `SERVER_PORT` / `MANAGEMENT_SERVER_PORT` | `8080` / `8081` | API / Actuator |

- `FLIGHTBOARD_VERSION` is set by Helm to the git SHA (same as the image tag) and returned by
  `/api/status`; the deploy verification compares it (Appendix A7).
- The pod template has a checksum annotation of the ConfigMap, so a changed value replaces the
  pods on `helm upgrade`.
- When the interval changes, the stored `nextRunAt` is not recomputed; the new interval applies
  from the next run.

### Source modes and quota

- `FLIGHTBOARD_SOURCE=stub` is the default (also when the variable is missing). The stub returns
  invented flights in the format of section 8 and uses no quota. Local Docker Compose and all
  tests use the stub. Codex implements the stub.
- `FLIGHTBOARD_SOURCE=aerodatabox` must be set explicitly. Only the GKE deployment sets it, so
  only GKE calls the real API.
- Quota (RapidAPI Basic plan, 400 units per month): the environment runs about 90 hours and is
  then deleted. 90 h × 2 fetches per hour = 180 calls × 2 units = 360 units, leaving 40 units
  (about 20 manual Fetch now). A longer run or a shorter interval needs a bigger plan.

## 8. Source data

Based on a real AeroDataBox response for PRG departures. All example values below are invented.

### Request

```
GET {FLIGHTBOARD_AERODATABOX_BASE_URL}/flights/airports/iata/PRG
    ?offsetMinutes=-60&durationMinutes=720&direction=Departure
    &withLeg=false&withCodeshared=false&withCargo=false
Headers:
  X-RapidAPI-Key:  {AERODATABOX_API_KEY}
  X-RapidAPI-Host: {FLIGHTBOARD_AERODATABOX_HOST}
```

- FIDS with relative time is a Tier 2 endpoint: 2 units per call.
- Maximum window of one FIDS call on the Basic plan: 12 hours (same as Pro), so
  `durationMinutes=720` is the maximum. Do not request more.
- Rate limit: 1 request per second. The application makes one call per run, no paging.

### Response structure

Root object `{"departures": [ ... ]}`. Fields used by the board:

| Path (per departure) | Type | Notes |
|---|---|---|
| `movement.scheduledTime.local` | string | always present |
| `movement.revisedTime.local` | string | usually present, often equal to scheduled |
| `movement.runwayTime.local` | string | only for departed flights; not displayed |
| `movement.airport.iata` | string | destination airport; may be missing |
| `movement.airport.name` | string | destination name; always present |
| `movement.terminal` | string | `"1"` or `"2"`; may be missing |
| `movement.checkInDesk` | string | free text; may be missing or invalid |
| `number` | string | contains a space |
| `status` | string | see the mapping below |
| `codeshareStatus` | string | e.g. `IsOperator` |
| `isCargo` | boolean | |

Present but not used: `movement.scheduledTime.utc`, `movement.revisedTime.utc`,
`movement.quality`, `movement.runway`, `movement.airport.icao`, `.countryCode`, `.timeZone`,
`.location`, `airline`, `aircraft`, `callSign`. There is **no gate field** in the source.

Invented example of one departure (structure only):

```json
{
  "movement": {
    "airport": { "icao": "XXAA", "iata": "AAA", "name": "Exampleville" },
    "scheduledTime": { "utc": "2030-01-15 21:40Z", "local": "2030-01-15 22:40+01:00" },
    "revisedTime":   { "utc": "2030-01-15 21:55Z", "local": "2030-01-15 22:55+01:00" },
    "terminal": "2",
    "checkInDesk": "100-102",
    "quality": ["Basic", "Live"]
  },
  "number": "ZZ 1234",
  "callSign": "ZZZ1234",
  "status": "Expected",
  "codeshareStatus": "IsOperator",
  "isCargo": false,
  "airline": { "name": "Example Air", "iata": "ZZ", "icao": "ZZZ" }
}
```

### Rules

**Times**
- Format is `yyyy-MM-dd HH:mmXXX` with a **space** between date and time (not ISO 8601 `T`), e.g.
  `2030-01-15 22:40+01:00`; UTC values end with `Z`. Parse with an explicit pattern.
- The offset is that of Europe/Prague (`+01:00` or `+02:00`). Convert to `Europe/Prague` and sort
  by the full timestamp, never by `HH:MM` only: the 12-hour window crosses midnight.
- Sched. = `scheduledTime.local` as `HH:MM`.
- Exp. = `revisedTime.local` as `HH:MM` only when it differs from `scheduledTime.local`;
  otherwise empty. Missing `revisedTime` → empty.

**Destination**
- `movement.airport.name` in upper case.
- If `movement.airport.iata` is missing (e.g. name `Unknown`), the destination is empty.

**Flight**
- `number` with all spaces removed: `"ZZ 1234"` → `"ZZ1234"`.

**Terminal**
- Terminal 2 mode shows only flights with `movement.terminal == "2"`.
- A missing terminal is not guessed; the flight is not shown on the Terminal 2 board.

**Check-in**
- Show `movement.checkInDesk` only when it matches `^\d{1,3}([,-]\d{1,3})?$`.
- If both parts are equal (`"100,100"` or `"100-100"`), show one value: `"100"`.
- Any other value is shown as empty. The source also returns 4-digit values that look like times
  (e.g. `"0430"`); they do not match and are empty.

**Bag Drop**
- Not in the source; always empty.

**Remark**

| Source `status` | Remark | Log entry |
|---|---|---|
| `Expected` | empty | no (known status) |
| `CheckIn` | empty | no (known status) |
| `Boarding` | `Boarding` (yellow) | no |
| `GateClosed` | `Gate closed` | no |
| `Delayed` | `Delayed` | no |
| `Canceled` | `Cancelled` (red) | no |
| `CanceledUncertain` | empty (an uncertain state is never shown as cancelled) | no |
| `Departed` | flight is hidden | no |
| any other / missing | empty | yes, with run ID and flight number |

**Filtering and order**
1. Drop `isCargo == true` and `codeshareStatus == "IsCodeshared"` as a second line of defence
   (the source filter is partly estimated; duplicates cannot be fully ruled out).
2. Drop `Departed`.
3. Terminal 2 mode: keep only `movement.terminal == "2"`.
4. Sort by `scheduledTime` (full timestamp), then by flight number.
5. Take the first 36.

**Batch validation**
- `{"departures": []}` is a valid empty batch (success, replaces the board).
- An invalid flight is a departure without `number` or without a parsable
  `movement.scheduledTime.local`.
- An invalid flight is skipped and logged (run ID, reason); the valid flights are published.
- The whole batch is rejected only when:
  - the JSON is invalid, or
  - `departures` is missing, or
  - more than half of the departures are invalid (`skipped * 2 > total`).

  The last good board then stays and the run is recorded as failed.
- Each run records the number of skipped flights in `fetch_runs` (field `skippedCount`, next to the
  flight count).
- Optional fields (`revisedTime`, `terminal`, `checkInDesk`, `airport.iata`) may be missing; this
  never makes a flight invalid.

**Data handling**
- Real responses are never committed. Tests use only invented data like the example above.
