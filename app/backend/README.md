# Flight Board backend

This module implements sources, complete-batch validation, coordinated fetching, MongoDB
persistence, the public REST API, health probes and a separate loopback admin listener from `docs/contract.md` and specification Appendix A1. It uses Java 25, Spring Boot
3.5.16, Maven 3.9.11 through Apache Maven Wrapper 3.3.4, WireMock 3.13.2, and the Spring Boot
dependency-managed MongoDB Java driver 5.5.2 and Testcontainers 1.21.4.

The same application coordinates fetching and serves the board. Creating the real-source bean
does not make an API request; only a successfully claimed scheduled run calls a source.

## Build and verify

Java 25 must be on `PATH` (or selected by `JAVA_HOME`), and Docker Desktop must be running
with Linux containers for MongoDB integration tests. No globally installed Maven is needed.
Replace `<backend>` with the absolute path to this directory. PowerShell 5.1 commands use an explicit wrapper path and an explicit Maven project file:

```powershell
& '<backend>\mvnw.cmd' -f '<backend>\pom.xml' --batch-mode --no-transfer-progress verify
```

Expected result: `BUILD SUCCESS`, with all configuration, stub, WireMock, validation, persistence
and HTTP acceptance tests passing, including an authenticated `mongo:8.0.32` single-member replica set `rs0`. The test
application user authenticates against `admin` but has only `readWrite` on `flightboard`. The
wrapper downloads its pinned Maven version and checks its SHA-256 checksum.
The HTTP acceptance suite uses the actual contract ports 8080, 8081 and 8082; keep those ports
free while running it. Tests pause MongoDB to verify initial and later outages, recovery without
restarting, startup cleanup, isolated admin access, freshness and sanitized errors.
The first build downloads dependencies. `target/` and `.maven-user-home/` are ignored.

For a cache contained entirely in this module, set `MAVEN_USER_HOME` to the absolute
`<backend>\.maven-user-home` path and pass
`-Dmaven.repo.local=<backend>\.maven-user-home\repository` to Maven.

Set `SPRING_DATA_MONGODB_URI` in the process environment to an authenticated replica-set URI
provided by the operator Secret (or by the local MongoDB setup). Never put it in a tracked file
or a command committed to the repository. The packaged application can then be started with:

```powershell
java -jar '<backend>\target\flight-board-backend-0.1.0-SNAPSHOT.jar'
```

The HTTP listeners start even if MongoDB is unavailable. Collection/index/control initialization
runs in the background with a bounded operation timeout and retries at the configured check
interval. Readiness is DOWN until initialization and a control-document read succeed; liveness
remains independent of MongoDB. Fetching resumes when MongoDB recovers. The application does
not create a MongoDB server.

## Configuration

`application.properties` explicitly maps the contract variables; underscore-separated names
are not assumed to match Spring's kebab-case property names. Immutable configuration objects
fail fast for an unknown source mode, missing real-source key, invalid URL/header values,
nonpositive durations, a run timeout that is not shorter than the lock duration, a window longer
than 720 minutes, or an unsafe admin address/port. Configuration string representations redact
the API key.

| Environment variable | Default | Application property |
|---|---|---|
| `FLIGHTBOARD_SOURCE` | `stub` | `flightboard.source` |
| `FLIGHTBOARD_MONGODB_DATABASE` | `flightboard` | `spring.data.mongodb.database` |
| `FLIGHTBOARD_AERODATABOX_BASE_URL` | `https://aerodatabox.p.rapidapi.com` | `flightboard.aerodatabox.base-url` |
| `FLIGHTBOARD_AERODATABOX_HOST` | `aerodatabox.p.rapidapi.com` | `flightboard.aerodatabox.host` |
| `AERODATABOX_API_KEY` | empty | `flightboard.aerodatabox.api-key` |
| `FLIGHTBOARD_FETCH_INTERVAL` | `PT30M` | `flightboard.fetch.interval` |
| `FLIGHTBOARD_FETCH_CHECK_INTERVAL` | `PT1M` | `flightboard.fetch.check-interval` |
| `FLIGHTBOARD_FETCH_LOCK_DURATION` | `PT5M` | `flightboard.fetch.lock-duration` |
| `FLIGHTBOARD_FETCH_RUN_TIMEOUT` | `PT2M` | `flightboard.fetch.run-timeout` |
| `FLIGHTBOARD_SOURCE_AIRPORT` | `PRG` | `flightboard.request.airport` |
| `FLIGHTBOARD_SOURCE_OFFSET_MINUTES` | `-60` | `flightboard.request.offset-minutes` |
| `FLIGHTBOARD_SOURCE_DURATION_MINUTES` | `720` | `flightboard.request.duration-minutes` |
| `FLIGHTBOARD_BOARD_STALE_AFTER` | `PT75M` | `flightboard.board.stale-after` |
| `FLIGHTBOARD_BOARD_MAX_AGE` | `PT12H` | `flightboard.board.max-age` |
| `FLIGHTBOARD_BOARD_TERMINAL` | `2` | `flightboard.board.terminal` |
| `FLIGHTBOARD_BOARD_MAX_FLIGHTS` | `36` | `flightboard.board.max-flights` |
| `FLIGHTBOARD_BOARD_TIMEZONE` | `Europe/Prague` | `flightboard.board.timezone` |
| `FLIGHTBOARD_ADMIN_PORT` | `8082` | `flightboard.admin.port` |
| `FLIGHTBOARD_ADMIN_ADDRESS` | `127.0.0.1` | `flightboard.admin.address` |
| `FLIGHTBOARD_VERSION` | `development` | `flightboard.version` |
| `SERVER_PORT` | `8080` | `server.port` |
| `MANAGEMENT_SERVER_PORT` | `8081` | `management.server.port` |

`SPRING_DATA_MONGODB_URI` is the native Spring environment variable used by the MongoDB client,
provided by the operator-generated Secret in GKE. Selecting `spring.data.mongodb.database=flightboard`
does not change authentication against `admin` in that URI. API and management listen on
`0.0.0.0`; the separate admin server enforces exactly `127.0.0.1:8082`.

## Source boundary

`FlightSource.fetch()` returns one complete JSON response or a safe `SourceException` containing
a failure category and, when available, an HTTP status. It does not persist or publish data.

- Stub is the default when `FLIGHTBOARD_SOURCE` is missing. It generates invented departures
  within the configured time window using an injectable clock, makes no HTTP calls and needs
  no key. Its generated dates move with the clock instead of expiring as static fixtures.
- AeroDataBox mode must be selected explicitly. It builds the airport path and window from the
  configuration, sets `direction=Departure`, `withLeg=false`, `withCodeshared=false` and
  `withCargo=false`, and sends the RapidAPI key and host headers. Only HTTP 200 is accepted.
- Apache HttpClient has automatic retries and redirects explicitly disabled. The response is limited to 2 MiB;
  the remaining whole-run budget bounds both headers and body download. Interruption cancels the
  in-flight request, and invalid UTF-8 is rejected. Exceptions do not echo provider bodies,
  headers or keys.
- Tests configure the actual HTTP adapter with a loopback WireMock URL and a dummy key. They
  never request the provider URL. Tests cover request parameters/headers, 200, 429, 500,
  malformed JSON, delayed headers/body, redirects, oversized responses and invalid encoding.

## Validation and mapping

`BatchValidator.validate(json, runId)` returns an immutable `ValidatedBatch` for publication.
A valid empty `departures` array succeeds. A malformed document, invalid root structure,
non-object departure, blank/non-string number or invalid required scheduled timestamp rejects
the entire batch with a safe reason and the first invalid departure's index. Trailing JSON and
duplicate object keys are also rejected.

Every departure is validated before filtering, including cargo, departed flights and flights
outside the selected terminal. Optional missing fields do not reject the batch. A malformed
optional revised time stays empty and produces a diagnostic containing only run ID and flight
number; its raw value is not logged.

Mapping follows contract section 8:

- Source timestamps use the explicit space-separated offset format and strict calendar parsing.
  Display uses the configured timezone; sorting uses full instants and flight number, including
  midnight and the repeated autumn daylight-saving hour.
- Revised time is displayed only when its instant differs from the scheduled instant.
- Known destination IATA codes use the small immutable city-name map in `DestinationNames.java`
  (the approved visual-polish override of the original source-name rule). Unlisted airports keep
  their uppercased source name; missing/blank/unknown names fall back to the IATA code.
  There is no geocoding or extra provider request, and the API shape is unchanged.
  Overrides affect the next published batch; already persisted boards keep their original names.
- Whitespace is removed from flight numbers. Check-in follows the specified numeric pattern;
  equal endpoints collapse to one value. Bag Drop remains empty.
- Known statuses follow the specified text/colour mapping. Unknown or missing status stays empty
  and is logged with run ID and flight number. `CanceledUncertain` never becomes `Cancelled`.
- Cargo, codeshares and departed flights are filtered out. Terminal mode requires an exact
  terminal match; an empty terminal configuration includes all terminals. The sorted result is
  limited by `FLIGHTBOARD_BOARD_MAX_FLIGHTS` (default 36).

The batch retains `sourceFlightCount` separately from the selected board count. The coordinated
publish operation assigns `publishedAt` and records the run result.

## Coordinated fetching and persistence

- Background startup initialization creates `fetch_control`, `fetch_runs` and `board_current`, then inserts the fixed
  control document only if absent. Concurrent startup and restart preserve pause state,
  schedule, lock ownership and the monotonically increasing counter.
- Every check (default one minute, also once at startup) makes one conditional
  `findOneAndUpdate` against the fixed control ID `control`. It requires an unpaused, due
  schedule and a free or expired lock. Server time sets the next schedule, a unique run ID,
  lock expiry and run deadline, and increments `fetchSeq`. The claim and its `RUNNING` run
  record commit together, before the single source request.
- The default two-minute budget begins before the claim and includes MongoDB operations,
  HTTP headers/body, complete-batch validation, publication and all transaction retries.
  A monotonic deadline and caller watchdog cancel overdue work. HTTP receives only the
  remaining budget; MongoDB uses CSOT (Client-Side Operation Timeout). Deadline checks
  between stages and server-side publication fences prevent a late worker from publishing.
- The source is called outside transactions, exactly once for each successfully claimed
  run. Publication retries reuse the downloaded, validated batch. A driver-managed
  transaction shares one remaining timeout across its body and every commit retry;
  transient body retries stop after three attempts. Unknown commit-result retries stop
  when that same remaining budget expires and never repeat the source request.
- Publication conditionally releases the matching unexpired owner and writes the whole
  board at fixed ID `current`. An absent board is inserted; an existing board is replaced
  only by a higher sequence. Any failed conditional write explicitly aborts the transaction.
  The `SUCCESS` run record commits atomically with the board and lock release. A valid empty
  batch replaces the board with an empty flight list.
- Failed runs preserve the last board, record a safe reason/status/index and release only
  their own lock. After the work deadline, failure bookkeeping has a separate two-second
  best-effort budget which cannot publish data. If the process crashes or MongoDB is
  unavailable, recovery waits for both lock expiry and the next scheduled time. A crashed
  run retains its `RUNNING` diagnostic record until expiration. A committed `SUCCESS` is
  never overwritten by ambiguous-commit failure bookkeeping.
- `fetch_runs` has a seven-day TTL (Time To Live) index on `startedAt`. Records contain
  run ID, sequence, timestamps, result, available HTTP status, source and board counts on
  success, and a sanitized error on failure. Raw source JSON and credentials are not stored.
- Reads omit a board older than the configured maximum age (default 12 hours), even before
  physical cleanup. Cleanup runs immediately after successful initialization (including delayed
  database recovery), once after one minute, and then daily. Publishing after deletion works.
- The separate admin listener uses the persistence methods for pause, resume and fetch-now.
  Pause blocks new claims and lets an active run finish; resume preserves `nextRunAt`.
  Fetch-now atomically sets `nextRunAt` to server time only when not paused or locked, and
  returns a rejection reason otherwise. The next scheduler check performs the fetch.

Integration tests use invented data and ephemeral credentials, a restricted application user,
concurrent clients and MongoDB failpoints. They cover initial and repeated publication, empty
batches, startup races, pause/resume, crash recovery, stale workers, sequence fencing, explicit
transaction abort, transient/ambiguous-commit retry, database delays, whole-run deadlines and
a complete Spring application startup with the default stub source. WireMock tests cover the
HTTP adapter without consuming real provider quota.

## HTTP interface

Every response under `/api`, including errors and unknown routes, sends
`Cache-Control: no-store`. Timestamps are ISO 8601 UTC strings. Age is a nonnegative number
of seconds since the original publication, never since the most recent API request.

| Request | Response |
|---|---|
| `GET :8080/api/departures` | 200: `flights`, `publishedAt`, `dataAgeSeconds`, `stale`, `runId` |
| `GET :8080/api/status` | 200: `version`, `paused`, `nextRunAt`, `publishedAt`, `dataAgeSeconds`, `stale`, `lastSuccessfulRun`, `lastError` |
| `GET :8080/readyz` | 200 UP / 503 DOWN: readiness state and initialized MongoDB availability |
| `GET :8081/actuator/health/liveness` | Only liveness state, no MongoDB or provider dependency |
| `GET :8081/actuator/health/readiness` | Same health group as `/readyz` |

A valid empty board is 200 with `flights: []`. An absent or expired board returns
503 with `code: NO_DATA`. Until initialization completes, or during a database outage,
the API returns 503 with `code: DATABASE_UNAVAILABLE`. Errors contain only a safe `code`
and `message`. Status still succeeds when the board is absent: `publishedAt` and
`dataAgeSeconds` are null and `stale` is true. The default stale threshold is 75 minutes;
boards older than 12 hours are excluded before physical cleanup.

`lastSuccessfulRun` is null or contains `runId`, `startedAt`, `finishedAt`, `httpStatus`,
`sourceFlightCount` and `flightCount`. `lastError` is null or contains `runId`, `finishedAt`,
`code`, a fixed sanitized `message`, `httpStatus` and `departureIndex`. Optional values are
null. Persisted exception text and provider bodies are never returned. A source failure affects
these diagnostics and board freshness only; it does not affect health probes.

## Admin access

Admin routes run on a separate JDK HTTP server, bound only to `127.0.0.1:8082`. They are
not registered in either Spring MVC listener: all three return 404 on 8080 and 8081.
Acceptance tests also attempt connections to every available non-loopback IPv4 interface and
verify that port 8082 refuses them.

| POST request | Response |
|---|---|
| `/admin/pause` | 200 `{ "paused": true }`; new claims stop, active work may finish |
| `/admin/resume` | 200 `{ "paused": false }`; existing `nextRunAt` is preserved |
| `/admin/fetch-now` | 202 `{ "accepted": true, "message": "Fetch scheduled for the next check" }` |

Fetch-now sets the shared schedule atomically; it does not run an independent provider request.
It returns 409 with `code: PAUSED` or `code: LOCKED` when rejected. Database unavailability
returns 503 `DATABASE_UNAVAILABLE`. GET on an admin operation returns 405; unknown paths
return 404. Admin responses also use `no-store`.

In GKE, use the contract's port-forward to the backend deployment, then call the loopback URL:

```powershell
kubectl port-forward -n flight-board deploy/flight-board-backend 8082:8082
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8082/admin/pause'
```

## Container

The Dockerfile builds the application with Java 25 and the pinned Maven wrapper, then runs
only the packaged jar on the Java 25 runtime as UID/GID `10001:10001`. Installing `unzip`
in the builder keeps the wrapper on the ZIP archive whose SHA-256 checksum is pinned for
Windows too. The build context allowlist excludes tests, caches, build output and local
environment files. No credentials are build arguments or image layers.
Replace `<backend>` with the absolute module path:

```powershell
docker build --tag flight-board-backend:local '<backend>'
docker run --rm --name flight-board-backend-local --publish 127.0.0.1:8080:8080 --publish 127.0.0.1:8081:8081 --env SPRING_DATA_MONGODB_URI --env FLIGHTBOARD_VERSION=local flight-board-backend:local
```

Set `SPRING_DATA_MONGODB_URI` in the host process environment before running the container;
its MongoDB address must be reachable from inside the container. The default source is stub.
Without a reachable replica set the image still starts, liveness is UP and readiness is DOWN.
Compose wiring belongs to the separate infrastructure task.

Do not publish port 8082 through Docker: a published port connects to the container's network
interface, while admin listens only on its loopback interface. Kubernetes port-forward can
reach pod loopback. Ports 8081 and 8082 must not be added to a Kubernetes Service or Ingress.

## Data and ownership

All test data is invented. Real responses are not copied into this module. This module changes
only `app/backend`; frontend, Compose, Kubernetes, Terraform and workflows are separate tasks.
