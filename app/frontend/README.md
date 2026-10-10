# Flight Board frontend

React and TypeScript implement the departure board from specification section 9 and Appendix A6.
The frontend reads only the project's `/api/departures`; it never calls AeroDataBox.
The production bundle is served by non-root nginx on port 8080. Files in this module are owned
by the application task; Compose, Kubernetes, infrastructure and workflows are maintained separately.

## Build and verify

Use Node.js 24 LTS and npm. The lockfile pins dependencies. Replace `<frontend>` with the
absolute path to this directory. PowerShell 5.1 commands work from any current directory:

```powershell
npm.cmd --prefix '<frontend>' ci
npm.cmd --prefix '<frontend>' test
npm.cmd --prefix '<frontend>' run typecheck
npm.cmd --prefix '<frontend>' run build
```

`test` runs deterministic unit tests of the API/cache model and data lifecycle. `typecheck`
runs `tsc --noEmit`, including test sources. `build` type-checks and builds the production
assets in `dist/`. No tests use real source data, API keys or provider quota.

For browser acceptance tests, first install Chromium into this module's ignored cache:

```powershell
$env:PLAYWRIGHT_BROWSERS_PATH = '<frontend>\.playwright'
& '<frontend>\node_modules\.bin\playwright.cmd' install chromium
npm.cmd --prefix '<frontend>' run test:e2e
```

Playwright starts and stops its own loopback development server on port 4173; keep it free.
Browser tests simulate the API, check all board states, the one-second clock, cache expiration
and recovery, 36 row slots, colours, complete time/identifier columns at 1280 x 720 and block
wrapping at a smaller width. Screenshots are written to ignored `test-results/`.

For local development, start a backend on 8080 with the stub source, then run:

```powershell
npm.cmd --prefix '<frontend>' run dev
```

The Vite server binds only to loopback on port 5173 and proxies `/api` to `127.0.0.1:8080`.
Development uses the same relative API URL as production.

## Board and API

The board has three blocks of twelve rows. Remaining slots stay empty. Below 1280 x 720
the blocks wrap vertically. The clock uses Europe/Prague regardless of the browser timezone.
Flights are ordered by full `scheduledAt`, then flight number, and limited to 36. Display
fields are already mapped and validated by the backend; missing values remain empty.
The title says `DEPARTURES FROM TERMINAL 2` only when all displayed flights confirm terminal
2; otherwise it says `DEPARTURES`. No terminal is guessed from absent data.

Each row reads these backend fields:

| Column | Field |
|---|---|
| Sched. | `scheduled` |
| Exp. | `expected` |
| Destination | `destination` |
| Flight | `number` |
| Bag Drop | `bagDrop` (always empty) |
| Check-in | `checkIn` |
| Remark | `remark`, `remarkColor` |

Other required response fields are `publishedAt` (ISO 8601), `dataAgeSeconds`, `stale`,
`runId` and the `flights` array. Each flight also contains `scheduledAt` and `terminal`.
The backend's `stale` flag controls the stale-data message; its configured threshold is not
duplicated in the frontend. All received and persisted payloads are validated before use.
Source strings are rendered through React as text, never as HTML.

## Refresh and cache

- Request immediately on startup and every 30 seconds, with `cache: no-store`. An in-flight
  request is cancelled before its replacement; requests time out after ten seconds.
- Cache the complete last valid board under `flight-board:board:v1` in localStorage, retaining
  its original `publishedAt`. Age never resets on reload or another API read.
- Check cache on page open and every second while running. Delete and hide copies older
  than twelve hours. A closed browser cannot run cleanup; it checks again on next open.
- An older publication or superseded request cannot replace a newer board. This also protects
  a newer persisted copy written by another tab. A valid empty response replaces old flights.
- Disabled storage, quota errors and malformed cache do not prevent the live board from working.
- No source JSON, response error text or credentials are stored.

| State | Display |
|---|---|
| Valid populated board | Flights |
| Valid empty board | No departures in the next hours |
| API reports `stale: true` | Information may not be up to date |
| 503 `NO_DATA` | Flight information is temporarily unavailable; old cache is removed |
| Network/API failure with usable copy | Last copy and Connection lost |
| Network/API failure without usable copy | Flight information is temporarily unavailable |

The age footer measures time since publication, not a guarantee that the source information
is current. Publication date and time use Europe/Prague. Data attribution links to AeroDataBox.

## nginx and container

Build and run the same image used in GKE:

```powershell
docker build --tag flight-board-frontend:issue-17 '<frontend>'
docker run --rm --name flight-board-frontend-local --publish 127.0.0.1:8080:8080 flight-board-frontend:issue-17
```

nginx runs as UID/GID 101:101 and listens on `0.0.0.0:8080` (also IPv6). `GET /healthz`
is a static 200 with `ok`, independent of backend availability. The default GKE configuration
serves the SPA; the Ingress routes `/api` to the backend. Direct `/api` requests to this
frontend configuration return a safe 503 JSON response, never SPA HTML.

For local Docker networking, select `nginx/local.conf` as
`/etc/nginx/conf.d/default.conf` with a read-only bind mount. The example assumes an existing
Docker network named `flight-board-local` and a backend available as `backend:8080`:

```powershell
docker run --rm --name flight-board-frontend-local --network flight-board-local --publish 127.0.0.1:8080:8080 --mount 'type=bind,source=<frontend>\nginx\local.conf,target=/etc/nginx/conf.d/default.conf,readonly' flight-board-frontend:issue-17
```

The local proxy preserves the `/api` path and query string, resolves `backend` through
Docker DNS on requests, and starts even before the backend exists. Both its successful
and error responses send `Cache-Control: no-store`; proxy caching is off. HTML is also
no-store, while filename-hashed assets are immutable. Compose wiring is a separate task.

After building the image, run the automated container smoke checks:

```powershell
npm.cmd --prefix '<frontend>' run test:container
```

The nginx checks also use a read-only root filesystem, a writable /tmp, dropped Linux
capabilities and no-new-privileges, matching deployment restrictions.
The script creates uniquely named, labelled test containers and a Docker network, then
removes only those resources. A synthetic Node HTTP server tests proxy path/query forwarding,
missing-backend health and later DNS recovery. It also checks the non-root UID, SPA assets,
cache headers, font license and GKE API isolation. Docker Desktop with Linux containers
must be running. The tests do not require a real backend, MongoDB or an AeroDataBox key.

## Fonts

Barlow Condensed is bundled locally with the application through Fontsource; no external
font requests are made. Its SIL Open Font License is included in
`public/barlow-condensed-OFL.txt` and in the served production bundle.
