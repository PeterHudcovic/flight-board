# CLAUDE.md – rules for Claude Code

These are the rules for Claude Code in the flight-board repository. The full specification is
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

| | Claude Code |
|---|---|
| Owns | `infra/`, `k8s/` (Helm chart), `.github/`, `docker-compose.yml`, `docs/`, root files `AGENTS.md`, `CLAUDE.md`, `README.md`, `.gitignore`, `.gitattributes` |
| Must not change | `app/` – only comments in review |
| Branch prefix | `claude/<issue>-description` |
| Worktree | `flight-board-claude` |
| Reviews | PRs from Codex (application) |

- Codex owns `app/backend`, `app/frontend`, tests and Dockerfiles in `app/`, and the files
  `app/backend/.gitignore` and `app/frontend/.gitignore`.
- The author reviews the root files; Codex comments on them.
- Work only in the own worktree. Never switch branches in another agent's directory.
- CI enforces ownership: a PR from `claude/*` fails if it changes `app/`; an unknown branch
  prefix is rejected.

## Procedure for every task

**Before the change**
- Explain the purpose, which files will change and how they relate to the rest of the project.
- Wait for the author's approval. Approval applies to the explained task within the agreed
  scope; a change of scope needs new approval.
- Update the branch from main first: `git fetch origin` and `git rebase origin/main`.

**During the work**
- Work in 1–3 steps at a time.
- Write abbreviations with the full name in parentheses, e.g. PDB (PodDisruptionBudget).
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

- No secrets in code, config files, logs or PRs (API keys, passwords, certificates, tfvars,
  tfstate). Secrets live in Kubernetes Secrets or the author's password manager.
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
- `terraform apply` (and `terraform destroy`). Allowed: `terraform fmt`, `validate`, `plan`.
  The author runs `apply` after reviewing the plan.

## Out of scope

Prometheus, Grafana, e-mail alerts, arrivals, NetworkPolicy.

## Author's environment

- Windows 10, Windows PowerShell 5.1, Windows Terminal.
- Java runs locally through the Maven wrapper (`mvnw.cmd`); Maven is not installed.
- Tools: Sublime Merge (git), k9s (cluster), GitHub web (PRs and review).
- Commands for the author must work in PowerShell 5.1 (no `&&`, no bash syntax).

## Tests and local setup

TODO: added once `docker-compose.yml` and the CI workflows exist.

- Backend: `mvnw.cmd verify` in `app/backend`.
- Frontend: `npm test`, `npx tsc --noEmit`, `npm run build` in `app/frontend`.
- Infrastructure: `terraform fmt -check`, `terraform validate`, `terraform plan`.
- Tests never use the real AeroDataBox quota.

## References

- Specification: `docs/flight-board-spec-v8-en.pdf`
- App ↔ infra contract: `docs/contract.md`
- Rules for Codex: `AGENTS.md`
