# App ↔ infra contract

Decisions shared by the application (Codex, `app/`) and the infrastructure (Claude Code,
`infra/`, `k8s/`, `.github/`) that must be fixed before implementation. Source: Appendix A4 and
A5 of `docs/flight-board-spec-v8-en.pdf`.

Status: skeleton. Every section is TODO until it is agreed in a PR and approved by the author.

## 1. Network

To define: standard external GKE Ingress; `/api/*` → backend, `/` → frontend. Admin and Actuator
are not routed publicly.

TODO

## 2. IP address

To define: global static IP for the Ingress, its name in the configuration.

TODO

## 3. Ports and health

To define: exact ports of the application, admin and probes. Readiness checks MongoDB, liveness
does not. Probes must not use the admin port (127.0.0.1).

TODO

## 4. Load balancer

To define: own health checks for backend and frontend via BackendConfig.

TODO

## 5. Permissions

To define: separate accounts for terraform plan, application deploy and nodes (reading from
Artifact Registry). Federation restricted to the repository and allowed workflows / branches.

TODO

## 6. Versions and storage

To define: specific MongoDB chart, version, image; 3 data members, CPU/RAM requests, disk size,
reclaim policy.

TODO

## 7. Configuration

To define: names of variables, secrets, images, database and replica set. When the interval
changes: the stored `nextRunAt` is not recomputed; the new interval applies from the next run.

TODO

## 8. Source data

To define: request with `withLeg=false`. For departures, the data in `movement` describes the
departure from Prague, but `movement.airport` is the destination airport. Exact paths to time,
terminal and destination. Terminal 2 mode shows only flights with a confirmed terminal 2; flights
without a terminal are not guessed. The source's codeshare filter is partly estimated;
duplicates cannot be fully ruled out.

TODO
