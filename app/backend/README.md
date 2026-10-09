# Flight Board backend - part 1 of 3

This module implements the application foundation, the two source modes and complete-batch
validation from `docs/contract.md`. It uses Java 25, Spring Boot 3.5.16, Maven 3.9.11 through
Apache Maven Wrapper 3.3.4, and WireMock 3.13.2 for HTTP tests.

Part 2 adds MongoDB persistence, coordinated fetching and transactional publication. Part 3 adds
the public API, Actuator, the isolated admin listener and the Dockerfile. The part 1 application
creates and validates its configuration and source beans; it does not schedule fetches or open
HTTP listeners. Creating the real-source bean does not make an API request.

## Build and verify

Java 25 must be on `PATH` (or selected by `JAVA_HOME`). No globally installed Maven or Docker
is needed for part 1. Replace `<backend>` with the absolute path to this directory. PowerShell
5.1 commands use an explicit wrapper path and an explicit Maven project file:

```powershell
& '<backend>\mvnw.cmd' -f '<backend>\pom.xml' --batch-mode --no-transfer-progress verify
```

Expected result: `BUILD SUCCESS`, with all configuration, stub, WireMock and validation tests
passing. The wrapper downloads its pinned Maven version and checks its SHA-256 checksum.
The first build downloads dependencies. `target/` and `.maven-user-home/` are ignored.

For a cache contained entirely in this module, set `MAVEN_USER_HOME` to the absolute
`<backend>\.maven-user-home` path and pass
`-Dmaven.repo.local=<backend>\.maven-user-home\repository` to Maven.

The packaged foundation can be started with:

```powershell
java -jar '<backend>\target\flight-board-backend-0.1.0-SNAPSHOT.jar'
```

At this stage it starts the non-web application context and exits normally. Runtime endpoint
acceptance tests will be added with part 3.

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

`SPRING_DATA_MONGODB_URI` is the native Spring environment variable reserved for part 2, provided
by the operator-generated Secret in GKE. Selecting `spring.data.mongodb.database=flightboard`
does not change authentication against `admin` in that URI. The port properties reserve the
contract values; listener implementation is part 3.

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
  the configured run timeout bounds both headers and body download. Interruption cancels the
  in-flight request, and invalid UTF-8 is rejected. Exceptions do not echo provider bodies,
  headers or keys.
- Tests configure the actual HTTP adapter with a loopback WireMock URL and a dummy key. They
  never request the provider URL. Tests cover request parameters/headers, 200, 429, 500,
  malformed JSON, delayed headers/body, redirects, oversized responses and invalid encoding.

## Validation and mapping

`BatchValidator.validate(json, runId)` returns an immutable `ValidatedBatch` for publication in
part 2. A valid empty `departures` array succeeds. A malformed document, invalid root structure,
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
- A meaningful destination name is uppercased independently of IATA; missing/blank/unknown names
  fall back to the IATA code. No values are invented for absent destinations.
- Whitespace is removed from flight numbers. Check-in follows the specified numeric pattern;
  equal endpoints collapse to one value. Bag Drop remains empty.
- Known statuses follow the specified text/colour mapping. Unknown or missing status stays empty
  and is logged with run ID and flight number. `CanceledUncertain` never becomes `Cancelled`.
- Cargo, codeshares and departed flights are filtered out. Terminal mode requires an exact
  terminal match; an empty terminal configuration includes all terminals. The sorted result is
  limited by `FLIGHTBOARD_BOARD_MAX_FLIGHTS` (default 36).

The batch retains `sourceFlightCount` separately from the selected board count. It does not yet
set `publishedAt`, freshness or run records; those belong to the coordinated publish operation.

## Data and ownership

All test data is invented. Real responses are not copied into this module. This part changes
only `app/backend`; frontend, Compose, Kubernetes, Terraform and workflows are separate tasks.
