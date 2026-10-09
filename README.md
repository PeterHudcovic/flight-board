# flight-board

Airport departure board on GKE with Terraform.

Flight Board is a mini airport departure board (FIDS – Flight Information Display System) with
real departure data from Prague airport, fetched from AeroDataBox. A Spring Boot application
fetches and validates the data and serves an API; a React + TypeScript frontend shows the board.
Data is stored in a MongoDB replica set on GKE; infrastructure is created with Terraform on GCP
and the application is deployed with GitHub Actions. The board never shows corrupted data and
always says clearly when data may not be up to date.

## Documentation

- Specification: [docs/flight-board-spec-v8-en.pdf](docs/flight-board-spec-v8-en.pdf) –
  where a main chapter differs from Appendix A, Appendix A prevails.
- App ↔ infra contract: [docs/contract.md](docs/contract.md)

## Repository structure

```
flight-board/
├── AGENTS.md            rules for Codex
├── CLAUDE.md            rules for Claude Code
├── docker-compose.yml   local setup
├── app/
│   ├── backend/         Spring Boot (fetch, validation, API, admin)
│   └── frontend/        React + TypeScript, nginx
├── infra/bootstrap/     state bucket, WIF, Artifact Registry (persistent)
├── infra/terraform/     VPC, GKE, NAT, DNS endpoint, IP, budget
├── k8s/flight-board/    Helm chart (app, frontend, values, PDB)
├── .github/workflows/   pr.yml (tests + plan), deploy.yml (build + helm)
└── docs/                specification, contract.md, runbook, cleanup
```

## How the work is done

The code is written by two AI agents using pull requests and mutual review:

- **Codex** owns `app/` (rules in [AGENTS.md](AGENTS.md)).
- **Claude Code** owns `infra/`, `k8s/`, `.github/`, `docker-compose.yml` and `docs/`
  (rules in [CLAUDE.md](CLAUDE.md)).

The author assigns tasks as GitHub issues and does every merge. Nobody pushes directly to `main`.
