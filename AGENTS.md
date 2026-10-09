# AGENTS.md – rules for Codex

These are the rules for Codex in the flight-board repository. The full specification is
`docs/flight-board-spec-v8-en.pdf`. Where a brief description in a main chapter differs from an
implementation rule in its Appendix A, Appendix A prevails.

## Project goal

1. Flight Board is a mini airport departure board (FIDS – Flight Information Display System) with
   real departure data from Prague airport (PRG), fetched from AeroDataBox.
2. One Spring Boot application (Java 25, Spring Boot 3.5) coordinates the fetch, validates each
   batch and serves an API; the frontend is React + TypeScript served by nginx.
3. Data is stored in a MongoDB replica set with 3 members; the board never calls AeroDataBox
   directly, it only reads from the project's own API.
4. Infrastructure is created with Terraform on GCP (zonal GKE in europe-west3-a), the application
   is deployed to GKE with GitHub Actions and Helm, and Cloudflare sits in front of the Ingress.
5. The focus is a reliable data flow: the board never shows corrupted data and always says
   clearly when data may not be up to date.

## Language

- Communication with the author is in Slovak. Established English IT terms are not translated
  (secrets, deployment, pod, branch, merge).
- All files in the repository are in English: code, comments, docs, commit messages, PR
  descriptions and review comments.
- Files never contain the author's name; write "author".

## Ownership

| | Codex |
|---|---|
| Owns | `app/backend` (Spring Boot), `app/frontend` (React + TS), tests, Dockerfiles in `app/`, `app/backend/.gitignore`, `app/frontend/.gitignore` |
| Must not change | `infra/`, `k8s/`, `.github/`, `docker-compose.yml`, root files (`AGENTS.md`, `CLAUDE.md`, `README.md`, `.gitignore`, `.gitattributes`) |
| Branch prefix | `codex/<issue>-description` |
| Worktree | `flight-board-codex` |
| Reviews | PRs from Claude Code (infra, CI/CD) |

- Codex may create and maintain `app/backend/.gitignore` and `app/frontend/.gitignore`; it does
  not change the root `.gitignore`.
- Root files are created and maintained by Claude Code; the author reviews them, Codex comments
  on them in review.
- Changes needed outside `app/` (ports, env variables, image names, health endpoints) are agreed
  in `docs/contract.md` through a Claude Code PR.
- Work only in the own worktree. Never switch branches in another agent's directory.
- CI enforces ownership: a PR from `codex/*` fails if it changes `infra/`, `k8s/`, `.github/` or
  `docker-compose.yml`; an unknown branch prefix is rejected.

## Procedure for every task

**Before the change**
- Explain the purpose, which files will change and how they relate to the rest of the project.
- Wait for the author's approval. Approval applies to the explained task within the agreed
  scope; a change of scope needs new approval.
- Update the branch from main first: `git fetch origin` and `git rebase origin/main`.

**During the work**
- Work in 1–3 steps at a time.
- Write abbreviations with the full name in parentheses, e.g. TTL (Time To Live).
- Commands on one line with absolute paths.
- When showing an edited file to the author, give the complete content, not fragments.

**After the change**
- Show the result and how to verify it (command, k9s, URL).
- Explain the essential parts of the change.
- The author confirms understanding; only then the author merges.

## Communication rules

- Short sections, one thing at a time, ending with a question whether it is clear.
- Explain first, change afterwards.
- Links (URLs) in a separate code block so they can be copied.

## Rules

- No secrets in code, config files, logs or PRs (API keys, passwords, certificates). Secrets
  come from environment variables backed by Kubernetes Secrets.
- Only synthetic test data goes into the repository.
- One task = one GitHub issue = one branch = one PR.
- Every PR uses `.github/pull_request_template.md`: What, Why, How to test and
  Explanation for the author.
- Review looks for bugs and risks, not style. The review result is a comment on the PR.
- The author always merges.
- `.ps1` files contain only ASCII characters (Windows PowerShell 5.1 reads files without a BOM as
  ANSI).

### Never

- `git push --force` (including `--force-with-lease`).
- `gh pr merge`.
- Direct push to `main`.
- `terraform apply` (and `terraform destroy`).

## Out of scope

Prometheus, Grafana, e-mail alerts, arrivals, NetworkPolicy.

## Author's environment

- Windows 10, Windows PowerShell 5.1, Windows Terminal.
- Java runs locally through the Maven wrapper (`mvnw.cmd`); Maven is not installed.
- Tools: Sublime Merge (git), k9s (cluster), GitHub web (PRs and review).
- Commands for the author must work in PowerShell 5.1 (no `&&`, no bash syntax).

## Tests and local setup

TODO: local setup with Docker Compose is added once `docker-compose.yml` exists.

- Backend: `mvnw.cmd verify` in `app/backend` (unit and integration tests, MongoDB via
  Testcontainers).
- Frontend: `npm test`, `npx tsc --noEmit`, `npm run build` in `app/frontend`.
- Tests never use the real AeroDataBox quota; use a test source with synthetic responses
  (200, 429, 500, timeout, invalid JSON).

## References

- Specification: `docs/flight-board-spec-v8-en.pdf`
- App ↔ infra contract: `docs/contract.md`
- Rules for Claude Code: `CLAUDE.md`
