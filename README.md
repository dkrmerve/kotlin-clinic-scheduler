# Clinic Scheduler

[![CI](https://github.com/dkrmerve/kotlin-clinic-scheduler/actions/workflows/ci.yml/badge.svg)](https://github.com/dkrmerve/kotlin-clinic-scheduler/actions/workflows/ci.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)
![Ktor](https://img.shields.io/badge/Ktor-3.6-087CFA?logo=ktor&logoColor=white)
![JVM](https://img.shields.io/badge/JVM-21-ED8B00?logo=openjdk&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)
![Coverage](https://img.shields.io/badge/coverage-gated%20%E2%89%A5%2090%25-brightgreen)
![License](https://img.shields.io/badge/license-MIT-blue)

An appointment scheduling service for a small clinic, written in Kotlin on Ktor and PostgreSQL. It books, cancels,
reschedules and checks in appointments while enforcing eleven business rules (working hours, buffers, capacity,
cancellation windows, no-show blocking, a waitlist with automatic promotion), with real JWT authentication,
RFC 7807 error responses, structured logging, health endpoints and metrics. Every rule and every edge case
(including both DST switches in `Europe/Amsterdam`) is covered by a test, and the Docker image is only produced
when the whole suite is green.

## Contents

- [What this project demonstrates](#what-this-project-demonstrates)
- [Business flow](#business-flow)
- [Business rules](#business-rules)
- [Architecture](#architecture)
- [Quick start (Docker)](#quick-start-docker)
- [Local build](#local-build)
- [Authentication and authorization](#authentication-and-authorization)
- [API](#api)
- [curl walkthrough](#curl-walkthrough)
- [Error catalog](#error-catalog)
- [Configuration](#configuration)
- [Database and transactions](#database-and-transactions)
- [Concurrency](#concurrency)
- [Test strategy](#test-strategy)
- [Edge cases we handle](#edge-cases-we-handle)
- [Operations](#operations)
- [Demo and inspecting the database](#demo-and-inspecting-the-database)
- [Project layout](#project-layout)
- [Assumptions and design decisions](#assumptions-and-design-decisions)
- [Trade-offs](#trade-offs)
- [What is not tested](#what-is-not-tested)
- [Before going to production](#before-going-to-production)
- [Roadmap / TODO](#roadmap--todo)

## What this project demonstrates

**For engineers**

- A layered Kotlin service: `domain` (pure Kotlin, no framework imports), `application` (use cases and ports),
  `infrastructure` (Exposed, HikariCP, Flyway) and `api` (Ktor routes, DTOs, problem mapping), wired by hand
  in one `Dependencies` class. No DI framework.
- Kotlin idioms used for a reason: value classes for ids, sealed hierarchies for appointment status and for
  the exception model (exhaustive `when` turns "forgot to map a new error" into a compile error), data classes
  with `invariant` checks, extension functions for clinic-zone time handling.
- Time handled correctly: instants in the database (`timestamptz`), rules evaluated in the clinic zone,
  every time-dependent rule takes an injected `Clock`, and both DST transitions have dedicated tests.
- Concurrency handled at the right layers: practitioner and patient row locks (`SELECT ... FOR UPDATE`) inside
  the booking transaction, an optimistic `version` column on appointments, and a partial unique index in
  PostgreSQL as the last line of defence. Proven with parallel HTTP requests against a real PostgreSQL.
- Two-layer validation: the API edge collects every field error into one 400; aggregates throw a typed,
  sealed `DomainException` hierarchy mapped to HTTP in exactly one place.
- A test pyramid that is measured: Kotest unit tests (data-driven boundary tables and property tests) for the
  domain, Ktor `testApplication` suites on H2 for speed, the same API and repository suites again on
  PostgreSQL via Testcontainers, and Kover gates that fail the build below 95/90/85/85 % per layer.
- Production plumbing: JWT (HS256 dev mode or RS256 via an OIDC issuer's JWKS), JSON logs with correlation
  ids, `/health/live`, `/health/ready`, `/metrics` (Prometheus), rate limiting, body limits, graceful shutdown,
  Flyway with startup retry, a non-root multi-stage Docker image, CI with Trivy.

**For non-engineers**

Think of the front desk of a doctor's practice. This service is the part that knows the rules: when each
doctor works, how long each kind of visit takes, that two visits must not overlap, that a patient who cancels
at the last minute is noted, that someone who misses three appointments is paused for a month, and that if a
slot frees up the first person on the waiting list gets it automatically. Everything it does is checked by
hundreds of automated tests before a new version can even be packaged.

## Business flow

```
  admin                clinic staff                    patient
    |                       |                             |
    | POST /practitioners   |                             |
    |---------------------->|                             |
    |                       | POST /patients              |
    |                       |---------------------------->|  (patient gets a login with sub = patient id)
    |                       |                             |
    |                       |        GET /practitioners/{id}/availability?date=..&type=Consultation
    |                       |<----------------------------|
    |                       |        POST /appointments  (rules 1-6, 8)        -> 201 Booked
    |                       |<----------------------------|
    |                       |        POST /appointments/{id}/cancel (rule 7)   -> Cancelled(late?)
    |                       |<----------------------------|      \
    |                       |                             |       '-> waitlist scanned FIFO (rule 9),
    |                       |                             |           first fitting entry auto-booked
    |                       | POST /appointments/{id}/check-in  (rule 10)      Booked -> CheckedIn
    |                       | POST /appointments/{id}/complete                 CheckedIn -> Completed
    |                       | POST /appointments/{id}/no-show   (rule 8)       Booked -> NoShow
    |                       |      3 no-shows in 90 days -> patient.blockedUntil = now + 30 days
    |                       |                             |
    |                       |        POST /waitlist  (when slot_taken / daily_capacity_reached)
    |                       |<----------------------------|
```

Every transition writes a history entry `(at, actor, from, to, note)` (rule 11); `GET /appointments/{id}`
returns it.

## Business rules

The rules live in `domain/SchedulingRules.kt` (booking, availability, cancellation, no-show timing),
`domain/Patient.kt` (no-show blocking), `domain/AppointmentStatus.kt` (state machine) and
`application/WaitlistService.kt` (promotion). Each has unit tests with a fixed clock; the HTTP result column is
what the API returns.

| # | Rule | HTTP result when violated |
|---|------|---------------------------|
| 1 | **Slot alignment.** Start must lie inside the practitioner's working window for that weekday (clinic zone), on a slot boundary counted from the window start (`slotMinutes` in {10, 15, 20, 30}), and `start + duration` must not pass closing time. Durations: Consultation 30, FollowUp 15, Procedure 60 min. Every duration must be a multiple of `slotMinutes`, checked when the practitioner is created. | 422 `outside_working_hours`, 422 `slot_misaligned`; 400 `slot_incompatible` on creation |
| 2 | **No overlap incl. buffer.** Must not intersect any Booked/CheckedIn/Completed appointment of the practitioner, each padded by `bufferMinutes` on both sides. Cancelled and NoShow rows free the slot. | 409 `slot_taken` |
| 3 | **Time off.** Must not intersect a time-off block. | 422 `practitioner_unavailable` |
| 4 | **Booking horizon.** Strictly in the future, at most 60 days ahead (inclusive). | 422 `outside_booking_horizon` |
| 5 | **Patient constraints.** At most one appointment per practitioner per clinic day; no overlapping appointments across practitioners. | 409 `patient_conflict` |
| 6 | **Daily capacity.** Non-cancelled appointments that day (no-shows count) must stay below `maxAppointmentsPerDay`. | 409 `daily_capacity_reached` |
| 7 | **Cancellation policy.** Patient: >= 24 h before is free; 24 h to 2 h before is allowed but `late=true` and the patient's `lateCancellations` counter increments; < 2 h is refused. Clinic: always allowed, never penalised. | 422 `cancellation_window_closed` |
| 8 | **No-show and blocking.** Clinic may mark NoShow only after the start time. Three no-shows within a rolling 90 days (inclusive) block the patient until `now + 30 days`. Blocked is derived from `blockedUntil > now`, never stored as a flag; a blocked patient cannot book or join the waitlist, but existing appointments stay valid. | 409 `no_show_before_start`; 403 `patient_blocked` with `blockedUntil` |
| 9 | **Waitlist with auto-promotion.** On `slot_taken` or `daily_capacity_reached` a patient may join the waitlist for practitioner + date + type. When an appointment on that practitioner and day becomes Cancelled, waiting entries are scanned FIFO (`createdAt`); blocked patients, patients violating rule 5 and types that do not fit the freed slot are skipped; the first fitting entry is booked with `promotedFromWaitlist=true` and becomes Fulfilled. Entries for past dates are Expired lazily on read. Duplicate joins are refused. | 409 `waitlist_duplicate` |
| 10 | **State machine.** `Booked -> CheckedIn -> Completed`, `Booked -> Cancelled`, `Booked -> NoShow`, `CheckedIn -> Cancelled` (clinic only). Reschedule = cancel (rule 7 applies to patients) + book in one transaction; if the new booking fails nothing changes. Rescheduling to the identical slot is a no-op that returns 200. | 409 `invalid_transition` |
| 11 | **History.** Every transition appends `(at, actor, from, to, note)`; `GET /appointments/{id}` includes it. | - |

## Architecture

```
                        +------------------------------------------------------------------+
   HTTPS / JSON         |  api          Ktor 3 (Netty)                                      |
  ------------------->  |  Routes.kt    Authentication(jwt)  RateLimit  RequestBodyLimit    |
   Authorization:       |  Dtos.kt      Validator (collects all field errors)  CallId/MDC   |
   Bearer <jwt>         |  Problems.kt  StatusPages -> RFC 7807 problem+json                |
                        +-----------------------------+------------------------------------+
                                                      | commands / queries
                        +-----------------------------v------------------------------------+
                        |  application   use cases, one transaction each                    |
                        |  PractitionerService  PatientService  AppointmentService          |
                        |  WaitlistService (FIFO promotion, savepoint)  BookingEngine       |
                        |  Ports.kt: UnitOfWork, *Repository interfaces                     |
                        +-----------------------------+------------------------------------+
                                                      | pure Kotlin, no framework imports
                        +-----------------------------v------------------------------------+
                        |  domain        SchedulingRules(policy, clock)  SchedulingPolicy   |
                        |  Practitioner  Patient  Appointment  AppointmentStatus (sealed)   |
                        |  WaitlistEntry TimeOff  DomainException hierarchy (sealed)        |
                        +-----------------------------+------------------------------------+
                                                      | implemented by
                        +-----------------------------v------------------------------------+
                        |  infrastructure  Exposed DSL repositories  ExposedUnitOfWork      |
                        |  HikariCP pool   Flyway (common + vendor migrations, retry)       |
                        +-----------------------------+------------------------------------+
                                                      |
                                        +-------------v-------------+
                                        |  PostgreSQL 16 (timestamptz, partial unique index)
                                        |  H2 in PostgreSQL mode for the fast test suite
                                        +---------------------------+
```

Dependencies point inwards only: `api -> application -> domain <- infrastructure`. `Dependencies.kt` builds the
graph by hand; tests build the same graph with a `MutableClock` and, where needed, a failing repository.

## Quick start (Docker)

Requirements: Docker with Compose. The image build runs ktlint, the full fast test suite (H2) and the coverage
gates: **a red test means no image.** (The PostgreSQL suite needs a Docker daemon, so it runs in CI and locally, not
inside the image build.)

```bash
cp .env.example .env            # local defaults; edit AUTH_DEV_SIGNING_KEY if you like
docker compose up --build       # PostgreSQL 16 + the app on http://localhost:8080
curl -s localhost:8080/health/ready
# {"status":"UP","database":"UP"}
```

Then mint a token and follow the [curl walkthrough](#curl-walkthrough), run `./scripts/seed.sh`, or import the
Postman collection under `postman/`. Stop with `docker compose down` (add `-v` to drop the database volume).

The runtime image is `eclipse-temurin:21-jre-alpine` with a non-root user; it weighs 385 MB (Alpine JRE ~190 MB, application jars ~120 MB, wget for the health check). The build stage caches the dependency layer, so source-only changes rebuild in about
a minute on a warm cache.

## Local build

Requirements: JDK 21 (Temurin). Gradle comes with the wrapper. Docker is needed for `integrationTest` only.

```bash
./gradlew build            # ktlint + fast suite on H2 + coverage gates + integrationTest (PostgreSQL via Testcontainers)
./gradlew build -x integrationTest   # without Docker
./gradlew test             # fast suite only (~30 s)
./gradlew integrationTest  # API + repository suites on PostgreSQL 16 (Testcontainers)
./gradlew koverHtmlReport  # build/reports/kover/html/index.html
./gradlew run              # needs DATABASE_URL and AUTH_DEV_SIGNING_KEY in the environment
```

On Windows use `gradlew.bat`. Running against the compose database from the IDE:
`DATABASE_URL=jdbc:postgresql://localhost:5432/clinic AUTH_DEV_SIGNING_KEY=<32+ chars> AUTH_DEV_ISSUER_ENABLED=true`.

## Authentication and authorization

Every endpoint except `/health/*`, `/metrics` and (in dev mode) `/auth/token` requires `Authorization: Bearer <JWT>`
with two claims: `sub` (who) and `role` (one of `patient`, `clinic_staff`, `admin`). Tokens are verified by Ktor's
`Authentication` plugin with the `jwt` provider; the mode is chosen at startup:

| Mode | Selected when | Verification |
|------|---------------|--------------|
| **Production (OIDC)** | `AUTH_JWKS_URL`, `AUTH_ISSUER` and `AUTH_AUDIENCE` are all set | RS256 signatures checked against the issuer's JWKS (cached, rate-limited), `iss` and `aud` enforced, 5 s leeway. |
| **Development (HMAC)** | otherwise | HS256 with `AUTH_DEV_SIGNING_KEY` (min 32 characters, no default). `exp` is mandatory in both modes: a token without it is rejected, it does not live forever. With `AUTH_DEV_ISSUER_ENABLED=true` the service also exposes `POST /auth/token {"subject","role"}` that mints 1-hour tokens. That route does not exist unless enabled. |

Plugging in an identity provider: configure a client/application in Keycloak, Auth0 or Microsoft Entra ID with a
custom `role` claim (Keycloak: a "User Attribute" or "Hardcoded claim" mapper; Auth0: an Action that adds the claim;
Entra: an app role and a claims mapping policy), set `AUTH_JWKS_URL` to its `jwks_uri`, `AUTH_ISSUER` to its `iss`
value and `AUTH_AUDIENCE` to the client id / API identifier. Patients' `sub` must equal their patient id in this
service; in practice the provider stores that id as a user attribute mapped into `sub`, or you add a lookup table.

Authorization is declared per route with two helpers (`Caller.requireRole`, `Caller.requireOwnerOrStaff`):

| Endpoint | patient | clinic_staff | admin |
|----------|---------|--------------|-------|
| `POST /practitioners` | - | - | yes |
| `GET /practitioners/{id}`, `GET .../availability` | yes | yes | yes |
| `POST /practitioners/{id}/time-off` | - | yes | yes |
| `POST /patients` | - | yes | yes |
| `GET /patients/{id}`, `GET /patients/{id}/appointments` | own record only | yes | yes |
| `POST /appointments`, `GET /appointments/{id}`, `.../cancel`, `.../reschedule` | own appointments only | yes | yes |
| `.../check-in`, `.../complete`, `.../no-show` | - | yes | yes |
| `POST /waitlist`, `GET /waitlist/{id}` | own entries only | yes | yes |
| `GET /waitlist?practitionerId&date` | - | yes | yes |

The actor recorded in history and used by the cancellation policy is derived from the role: `patient` acts as the
patient, staff and admin act as the clinic. Failures: 401 `unauthenticated`, 403 `forbidden_role`, 403 `not_owner`.

## API

Times in JSON are ISO-8601 with an offset, rendered in the clinic zone (`2027-01-12T10:00:00+01:00`) and accepted
with any offset. Strings without an offset are rejected (400). `201 Created` responses carry a `Location` header.

| Method | Path | Role | Purpose |
|--------|------|------|---------|
| GET | `/health/live` | anonymous | Process is up |
| GET | `/health/ready` (alias `/health`) | anonymous | `SELECT 1` through the pool; 503 when the database is down |
| GET | `/metrics` | anonymous | Prometheus exposition (JVM, HTTP server, HikariCP) |
| POST | `/auth/token` | anonymous (dev mode only) | Mint a development token |
| POST | `/practitioners` | admin | Create practitioner with weekly schedule, slot, buffer, capacity |
| GET | `/practitioners/{id}` | any | Practitioner |
| POST | `/practitioners/{id}/time-off` | staff | Add a time-off block |
| GET | `/practitioners/{id}/availability?date=YYYY-MM-DD&type=Consultation` | any | Free aligned starts after rules 1-4 and 6 |
| POST | `/patients` | staff | Create patient |
| GET | `/patients/{id}` | owner/staff | Patient incl. no-shows, late cancellations, `blocked`, `blockedUntil` |
| GET | `/patients/{id}/appointments?page=1&pageSize=20` | owner/staff | Appointments, newest first, `pageSize` <= 100 |
| POST | `/appointments` | owner/staff | Book |
| GET | `/appointments/{id}` | owner/staff | Appointment with full history |
| POST | `/appointments/{id}/cancel` | owner/staff | Cancel (optional `{"note"}`); returns the cancellation and any waitlist promotion |
| POST | `/appointments/{id}/reschedule` | owner/staff | Atomic cancel + book (`newStart`, optional `practitionerId`, `type`) |
| POST | `/appointments/{id}/check-in` | staff | Booked -> CheckedIn |
| POST | `/appointments/{id}/complete` | staff | CheckedIn -> Completed |
| POST | `/appointments/{id}/no-show` | staff | Booked -> NoShow (after start); may block the patient |
| POST | `/waitlist` | owner/staff | Join the waitlist for practitioner + date + type |
| GET | `/waitlist?practitionerId=&date=&page=&pageSize=` | staff | Entries in FIFO order |
| GET | `/waitlist/{id}` | owner/staff | One entry |

## curl walkthrough

Against `docker compose up` (dev token issuer enabled). `jq` is used to pull ids out of responses.

```bash
BASE=http://localhost:8080
ADMIN=$(curl -s -X POST $BASE/auth/token -H 'Content-Type: application/json' -d '{"subject":"admin-1","role":"admin"}' | jq -r .accessToken)
STAFF=$(curl -s -X POST $BASE/auth/token -H 'Content-Type: application/json' -d '{"subject":"desk-1","role":"clinic_staff"}' | jq -r .accessToken)

# 1. practitioner (admin): 15-minute grid, 5-minute buffer, 12 per day, Mon-Fri 09:00-17:00
DR=$(curl -s -X POST $BASE/practitioners -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{
  "name":"Dr. Ada Lovelace","specialty":"General practice","slotMinutes":15,"bufferMinutes":5,"maxAppointmentsPerDay":12,
  "schedule":{"MONDAY":{"start":"09:00","end":"17:00"},"TUESDAY":{"start":"09:00","end":"17:00"},
              "WEDNESDAY":{"start":"09:00","end":"17:00"},"THURSDAY":{"start":"09:00","end":"17:00"},"FRIDAY":{"start":"09:00","end":"13:00"}}}' | jq -r .id)

# 2. two patients (staff)
GRACE=$(curl -s -X POST $BASE/patients -H "Authorization: Bearer $STAFF" -H 'Content-Type: application/json' -d '{"name":"Grace Hopper","email":"grace@example.test"}' | jq -r .id)
LINUS=$(curl -s -X POST $BASE/patients -H "Authorization: Bearer $STAFF" -H 'Content-Type: application/json' -d '{"name":"Linus Torvalds","email":"linus@example.test"}' | jq -r .id)
GRACE_TOKEN=$(curl -s -X POST $BASE/auth/token -H 'Content-Type: application/json' -d "{\"subject\":\"$GRACE\",\"role\":\"patient\"}" | jq -r .accessToken)

# 3. availability (pick a weekday within 60 days)
DATE=$(date -d "next tuesday" +%F 2>/dev/null || date -v+tue +%F)
curl -s "$BASE/practitioners/$DR/availability?date=$DATE&type=Consultation" -H "Authorization: Bearer $GRACE_TOKEN" | jq '.slots[:3]'
SLOT=$(curl -s "$BASE/practitioners/$DR/availability?date=$DATE&type=Consultation" -H "Authorization: Bearer $GRACE_TOKEN" | jq -r '.slots[4]')

# 4. book (patient books her own appointment) -> 201 + Location
APPT=$(curl -s -X POST $BASE/appointments -H "Authorization: Bearer $GRACE_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"practitionerId\":\"$DR\",\"patientId\":\"$GRACE\",\"type\":\"Consultation\",\"start\":\"$SLOT\"}" | jq -r .id)

# 5. the same slot for Linus -> 409 slot_taken (problem+json)
curl -s -X POST $BASE/appointments -H "Authorization: Bearer $STAFF" -H 'Content-Type: application/json' \
  -d "{\"practitionerId\":\"$DR\",\"patientId\":\"$LINUS\",\"type\":\"Consultation\",\"start\":\"$SLOT\"}" | jq '{status,code,detail}'

# 6. so Linus joins the waitlist for that day
curl -s -X POST $BASE/waitlist -H "Authorization: Bearer $STAFF" -H 'Content-Type: application/json' \
  -d "{\"practitionerId\":\"$DR\",\"patientId\":\"$LINUS\",\"date\":\"$DATE\",\"type\":\"Consultation\"}" | jq '{id,status}'

# 7. Grace cancels (>= 24 h before: free). Linus is promoted into the freed slot in the same transaction.
curl -s -X POST $BASE/appointments/$APPT/cancel -H "Authorization: Bearer $GRACE_TOKEN" -H 'Content-Type: application/json' \
  -d '{"note":"Feeling better"}' | jq '{cancelled: .appointment.status, late: .appointment.lateCancellation, promoted: .waitlistPromotion.patientId, fromWaitlist: .waitlistPromotion.promotedFromWaitlist}'

# 8. history of the cancelled appointment (rule 11)
curl -s $BASE/appointments/$APPT -H "Authorization: Bearer $STAFF" | jq '.history'
```

## Error catalog

All errors are RFC 7807 problem JSON (`application/problem+json`) with a stable snake_case `code`, a human `detail`,
`type = https://github.com/dkrmerve/kotlin-clinic-scheduler/docs/errors#<code>` and the request's `correlationId`.
Validation failures add an `errors` map keyed by field; blocks add `blockedUntil`. The full table with the
"when it happens" column is in [docs/errors.md](docs/errors.md); the short version:

| HTTP | Codes |
|------|-------|
| 400 | `validation_failed` (with `errors`), `malformed_request`, `invalid_practitioner`, `slot_incompatible`, `invalid_schedule`, `invalid_patient`, `invalid_time_off` |
| 401 | `unauthenticated` |
| 403 | `forbidden_role`, `not_owner`, `patient_blocked` (with `blockedUntil`) |
| 404 | `practitioner_not_found`, `patient_not_found`, `appointment_not_found`, `waitlist_entry_not_found`, `route_not_found` |
| 405 | `method_not_allowed` |
| 409 | `invalid_transition`, `slot_taken`, `patient_conflict`, `daily_capacity_reached`, `waitlist_duplicate`, `no_show_before_start`, `concurrent_modification`, `conflict` |
| 413 | `payload_too_large` |
| 415 | `unsupported_media_type` |
| 422 | `outside_working_hours`, `slot_misaligned`, `practitioner_unavailable`, `outside_booking_horizon`, `cancellation_window_closed` |
| 429 | `rate_limited` |
| 500 | `internal_error` (never a stack trace; the log line carries the correlation id) |

The exception-to-status mapping is the exhaustive `when` in `api/Problems.kt`; `ProblemMappingTest` pins one case per
subtype. Domain code never lets `IllegalArgumentException`/`IllegalStateException` escape: aggregates use `invariant(...)`,
which throws `ValidationException` with a code.

## Configuration

Everything comes from environment variables, is parsed once at startup by `AppConfig`, and **all** problems are
reported together before the process exits with status 1 (`ConfigException`). Only `DATABASE_URL` and, in dev mode,
`AUTH_DEV_SIGNING_KEY` have no default.

| Variable | Default | Meaning |
|----------|---------|---------|
| `PORT` | `8080` | HTTP port |
| `DATABASE_URL` | required | `jdbc:postgresql://host:5432/db` (validated: must be a PostgreSQL JDBC URL) |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `clinic` / `clinic` | Local compose defaults only |
| `DB_POOL_MAX` / `DB_POOL_MIN_IDLE` | `10` / `2` | HikariCP `maximumPoolSize` / `minimumIdle` |
| `DB_CONNECTION_TIMEOUT_MS` | `10000` | HikariCP `connectionTimeout` |
| `DB_VALIDATION_TIMEOUT_MS` | `5000` | HikariCP `validationTimeout` |
| `DB_MAX_LIFETIME_MS` | `1800000` | HikariCP `maxLifetime` (30 min) |
| `DB_LEAK_DETECTION_MS` | `20000` | HikariCP `leakDetectionThreshold` (0 disables) |
| `DB_STARTUP_RETRY_SECONDS` | `60` | How long startup retries connecting + migrating (1 s, 2 s, 4 s ... 10 s backoff) |
| `CLINIC_ZONE` | `Europe/Amsterdam` | Zone for all calendar rules |
| `BOOKING_HORIZON_DAYS` | `60` | Rule 4 |
| `FREE_CANCEL_HOURS` / `MIN_CANCEL_HOURS` | `24` / `2` | Rule 7 (`MIN` must not exceed `FREE`) |
| `NO_SHOW_LIMIT` / `NO_SHOW_WINDOW_DAYS` / `BLOCK_DAYS` | `3` / `90` / `30` | Rule 8 |
| `AUTH_JWKS_URL`, `AUTH_ISSUER`, `AUTH_AUDIENCE` | unset | Set all three for OIDC mode (`https://` JWKS URL required) |
| `AUTH_DEV_SIGNING_KEY` | required in dev mode | HS256 secret, >= 32 characters |
| `AUTH_DEV_ISSUER` | `clinic-scheduler-dev` | `iss` claim in dev mode |
| `AUTH_DEV_ISSUER_ENABLED` | `false` | Exposes `POST /auth/token`; refused in OIDC mode |
| `RATE_LIMIT_PER_MINUTE` | `120` | POST requests per minute per authenticated subject (per client address for anonymous calls) |
| `TRUST_PROXY_HEADERS` | `false` | Honour `X-Forwarded-For`/`-Proto`/`-Host` (Ktor `XForwardedHeaders`). Enable only behind a trusted proxy: otherwise any client can spoof its address and escape the per-address rate limit. |
| `MAX_BODY_BYTES` | `65536` | Request body limit |
| `SHUTDOWN_GRACE_MS` / `SHUTDOWN_TIMEOUT_MS` | `2000` / `10000` | Ktor graceful shutdown |
| `LOG_LEVEL` | `INFO` | Root log level |

## Database and transactions

- **Schema** is owned by Flyway. `db/migration/common` holds SQL that both PostgreSQL 16 and H2 (PostgreSQL mode)
  accept; `db/migration/postgresql` and `db/migration/h2` hold the vendor-specific parts of the same version
  (`DatabaseFactory` picks the folder from the JDBC driver metadata). All instants are `timestamptz` written in UTC.
  Foreign keys are `ON DELETE RESTRICT`. Indexes: `appointments(practitioner_id, start_at)`,
  `appointments(patient_id, start_at)`, `waitlist_entries(practitioner_id, entry_date, created_at)`,
  `time_off(practitioner_id, from_at, to_at)`.
- **Last line of defence:** `CREATE UNIQUE INDEX ux_appointments_active_slot ON appointments (practitioner_id, start_at)
  WHERE status IN ('Booked','CheckedIn')`. Two active appointments can never share a start, whatever the application does.
  A violation surfaces as 409 `slot_taken`. Likewise `ux_waitlist_waiting (practitioner_id, patient_id, entry_date)
  WHERE status = 'Waiting'` guarantees one waiting entry per patient and day (409 `waitlist_duplicate`).
- **No duplicated state:** a patient's no-shows are the appointments in status `NoShow` (migration V3 dropped the
  earlier copy). The block (`blocked_until`) and the late-cancellation counter are the only derived values stored.
- **Optimistic locking:** `appointments.version` is checked on every update; a stale version is 409 `concurrent_modification`.
- **Connections:** HikariCP configured explicitly (see table above), `autoCommit=false`, READ COMMITTED. Startup opens the
  pool, then retries migration with exponential backoff until `DB_STARTUP_RETRY_SECONDS` is spent, so
  `docker compose up` works even when PostgreSQL is still starting; the failure message names the URL and the attempts.
- **Transactions:** exactly one per use case (`ExposedUnitOfWork.transaction`, Exposed `transaction {}` on `Dispatchers.IO`).
  Repositories never open their own. Waitlist promotion runs inside the cancellation's transaction under a savepoint
  (`useNestedTransactions = true`): a failure while promoting rolls back the promotion only and is logged; the cancellation
  commits. Reschedule is cancel + book in one transaction, so a rejected new slot leaves the old appointment untouched.
  `DatabaseFactoryTest` proves rollback ("a failure after the first insert leaves nothing persisted") and savepoint semantics.
- **Startup validation:** a missing or non-PostgreSQL `DATABASE_URL` fails fast with a clear message before any connection
  attempt.

## Concurrency

| Operation | Mechanism | Proven by |
|-----------|-----------|-----------|
| `POST /appointments` (also reschedule and waitlist promotion) | `SELECT ... FOR UPDATE` on the practitioner row, then on the patient row (always that order, so no deadlock cycle); every rule then reads committed state. Partial unique index as backstop. | `ConcurrencyTest`: 10 parallel bookings of one slot -> exactly one 201; 6 parallel bookings on a 1/day practitioner -> exactly one 201; one patient booking 4 practitioners at once -> exactly one 201. `ExposedRepositoriesTest`: a raw insert bypassing the domain is rejected by the index (SQLSTATE 23505). |
| `cancel`, `no-show`, `reschedule` | Same row locks as booking, taken first (practitioners sorted by id, then the patient) before any write, so a cancel or reschedule can never deadlock with a concurrent booking; the late-cancellation counter and the no-show block are computed under the patient lock. Optimistic `version` column on top: `UPDATE ... WHERE id = ? AND version = ?`; zero rows -> 409 `concurrent_modification`. PostgreSQL deadlock/serialization SQLSTATEs (40P01, 40001) are also mapped to 409 `concurrent_modification` (retryable), never 500. | `ConcurrencyTest`: 20 rounds of a late-window reschedule racing a booking for the same patient and practitioner -> never a 5xx, exactly one winner; two parallel no-shows -> both recorded; two parallel cancels -> one 200 + one 409. `ProblemMappingTest`: 40P01/40001 -> 409. |
| `check-in`, `complete` | Optimistic `version` column; the state machine catches the case where the second request reads the already-committed row. | `ConcurrencyTest`: parallel check-in vs clinic cancel -> one loses with 409 or they serialise into Booked -> CheckedIn -> Cancelled, never a 5xx. `ExposedRepositoriesTest`: stale version -> `ConcurrencyException`. |
| `POST /waitlist` | Patient row lock inside the join transaction, plus partial unique index `ux_waitlist_waiting (practitioner_id, patient_id, entry_date) WHERE status = 'Waiting'` mapped to 409 `waitlist_duplicate`. | `ConcurrencyTest`: 5 parallel joins -> exactly one 201. `PostgresSchemaTest`: the index exists. |
| Waitlist promotion after cancel | Runs inside the cancel transaction, holding the same row locks through `BookingEngine`. | `WaitlistApiTest`: cancellation and promoted booking visible together; promotion failure keeps the cancellation. |
| `POST /practitioners`, `POST /patients`, time-off | Plain inserts with fresh UUIDs; no contention. | - |

The concurrency suite uses `Dispatchers.IO` coroutines released by a shared gate, real HTTP through `testApplication`,
and only runs under `integrationTest` (real PostgreSQL). On H2 it is skipped, because H2 has no partial indexes and
different lock semantics.

## Test strategy

```
                       ./gradlew test                 ./gradlew integrationTest        docker build
  domain (Kotest)      176 tests, fixed Clock         -                                x
  api (Ktor testApp)   112 tests on H2 (8 PG-only skipped)   120 tests on PostgreSQL 16  x  (H2)
  infrastructure       20 tests on H2 (1 PG-only skipped)    21 tests on PostgreSQL       x  (H2)
  config               5 tests                        -                                x
```

- **Domain**: `FunSpec` suites with `withData` boundary tables (alignment, buffer, horizon, cancellation windows,
  no-show window, state matrix), `kotest-property` checks for slot alignment and interval intersection, and dedicated
  DST suites. A fixed `Clock` makes "24 h minus one second" a plain value.
- **API**: the real Ktor module (`clinicModule`) in `testApplication` with the real repositories, a `MutableClock`, and
  a typed client. Every error path asserts status **and** `code` **and** a non-empty `detail`
  (`shouldBeProblem`). Tests create their own practitioners and patients with fresh UUIDs, never share rows and never
  depend on order; the suite has been run twice in a row and with `--tests` on a single class.
- **PostgreSQL is the source of truth**: `integrationTest` re-runs the API and repository suites against
  `postgres:16-alpine` from Testcontainers (one container per run, started by a Kotest project listener) and adds the
  concurrency and partial-index tests. CI runs both.
- **Coverage is gated** with Kover: overall >= 90 % lines; `domain` >= 95 % lines and branches; `application` >= 90 %;
  `api` and `infrastructure` >= 85 %. The only exclusion is `ApplicationKt` (the `main` bootstrap: env parsing, Netty
  start, shutdown hook). Numbers from the last local run of `./gradlew build` (H2 + PostgreSQL merged):

| Layer | Lines | Branches | Gate |
|-------|-------|----------|------|
| overall | 97.9 % | 75.0 % | 90 % lines |
| domain | 97.3 % | 96.2 % | 95 % lines, 95 % branches |
| application | 98.1 % | 95.5 % | 90 % lines |
| api | 97.9 % | 62.7 % | 85 % lines |
| infrastructure | 98.7 % | 89.1 % | 85 % lines |

One row per test, with the scenario and rule it covers, is in [docs/TEST-CATALOG.md](docs/TEST-CATALOG.md).

## Edge cases we handle

Each bullet names the test that pins it (class > test).

- **DST spring-forward 2027-03-28** (02:00 does not exist): 31 slots, all 15 real minutes apart, first at 09:00+02:00;
  an overnight window skips the missing hour with no duplicate instants; a 30-minute appointment at 01:45+01:00 ends at
  03:15+02:00 and lasts exactly 30 minutes; closing time is compared in instants. `DstTest > spring-forward day`.
- **DST fall-back 2027-10-31** (02:00 happens twice): both occurrences of 02:00 and 02:30 are distinct, bookable, non-overlapping
  slots; a 02:45+02:00 appointment ends at 02:15+01:00 after 30 real minutes; the clinic day is 25 hours. `DstTest > fall-back day`.
- **Window boundaries**: ending exactly at 17:00 allowed, 17:01 not; starting exactly at 09:00 allowed; new start at
  `existing.end + buffer` allowed, one minute earlier not; back-to-back with buffer 0 touch without overlap; slot grid is
  counted from the window start (09:10 opens -> 09:10, 09:25 aligned, 09:15 not). `SchedulingRulesTest > Rule 1`, `Rule 2`.
- **Horizon boundaries**: `start == now` rejected, `now + 60 days` allowed, `+1 minute` rejected; Sunday -> `outside_working_hours`.
  `SchedulingRulesTest > Rule 4`, `PractitionerApiTest > availability` (past date 422, > 60 days 422, exactly 60 allowed).
- **Cancellation windows**: exactly 24 h free, 24 h - 1 s late, exactly 2 h late, 2 h - 1 s closed; clinic at 1 minute before
  allowed; cancelling twice -> 409. `CancellationPolicyTest`, `AppointmentApiTest > cancellation policy`.
- **No-show blocking**: third no-show exactly 90 days after the first still counts, 91 days drops the first; at `blockedUntil`
  exactly the patient is free; existing appointments of a blocked patient can be checked in; a retroactively recorded older
  no-show still produces the block; no-show before start -> 409; on Cancelled/Completed -> 409. `NoShowPolicyTest`,
  `AppointmentApiTest > no-show and blocking`.
- **Waitlist**: FIFO with three entries; blocked first entry skipped; first entry with a same-day appointment skipped;
  FollowUp freed while a Procedure waits -> skipped, next fitting promoted; expired entries never promoted; duplicate join
  -> 409; promotion inside the cancel transaction; promotion failure keeps the cancellation. `WaitlistApiTest`.
- **Reschedule atomicity**: invalid new slot -> old stays Booked with no history entry and version 0; late-window reschedule
  applies the late penalty; closed window refused; identical slot is a 200 no-op. `AppointmentApiTest > reschedule`.
- **Concurrency**: see the table above, including the reschedule-vs-booking deadlock race (20 rounds, never a 5xx),
  parallel no-shows on one patient and parallel waitlist joins. `ConcurrencyTest`, `ExposedRepositoriesTest`.
- **Review regressions**: a retroactively recorded (older) no-show is stored once and still triggers the block;
  rescheduling a Cancelled/Completed appointment to its own slot is 409, not a silent 200; a JWT without `exp` is 401;
  rate limiting is per subject so one busy client cannot starve another. `AppointmentApiTest > review regressions`,
  `AuthTest > token lifetime`, `OperationsTest > rate limiting keys`.
- **Availability**: empty on a time-off day, filters ends past closing, respects buffer and capacity, past/too-far dates 422,
  invalid date/type 400 with allowed values. `PractitionerApiTest > availability`.
- **Input hardening**: unknown type -> 400 listing allowed values; malformed/empty JSON -> 400 `malformed_request`; wrong field
  types -> 400; non-JSON content type -> 415; non-UUID path id -> 404; missing/expired/foreign-key token -> 401; time strings
  without offset -> 400; slotMinutes not in the set, window start >= end, malformed email, blank names (trimmed) -> 400 with
  all errors collected; body over the limit -> 413. `ProblemMappingTest`, `PractitionerApiTest`, `PatientApiTest`, `AuthTest`.
- **Pagination**: `page`/`pageSize` defaults 1/20, `pageSize` <= 100, invalid values -> 400 per field. `PatientApiTest`, `WaitlistApiTest`.

## Operations

- **Logs**: one JSON object per line on stdout (logstash-logback-encoder): `@timestamp`, `level`, `logger_name`,
  `message`, `correlationId` (MDC), `service`, and `stack_trace` as a single field. `docker compose logs -f app`.
  Request logging (`CallLogging`) skips `/health/*` and `/metrics`.
- **Correlation**: `X-Correlation-Id` is taken from the request or generated, echoed in the response header, put in the MDC
  and included in every problem response, so a user-reported error can be found in the logs in one grep.
- **Health**: `/health/live` (process), `/health/ready` (real `SELECT 1` through the pool; 503 + `database: DOWN` when it
  fails). Docker `HEALTHCHECK` and the compose healthcheck use `/health/ready`; compose starts the app only after
  `pg_isready` succeeds.
- **Metrics**: `/metrics` in Prometheus format via Micrometer: `ktor_http_server_requests_seconds` (by route, method,
  status), JVM memory/GC/threads, HikariCP pool (`hikaricp_connections_*`). Suggested alerts: readiness failing for
  > 1 min; 5xx rate > 1 %; p95 latency of `POST /appointments` > 500 ms; `hikaricp_connections_pending` > 0 for > 30 s;
  JVM heap after GC > 80 %.
- **Limits**: `RATE_LIMIT_PER_MINUTE` POSTs per authenticated subject, or per client address for anonymous calls
  (429 `rate_limited`); behind a proxy set `TRUST_PROXY_HEADERS=true` so the address comes from `X-Forwarded-For`.
  `MAX_BODY_BYTES` (413), Netty header/line limits, 30 s read/write timeouts.
- **Shutdown**: SIGTERM -> Ktor stops accepting, drains in-flight requests for `SHUTDOWN_GRACE_MS`, hard stop at
  `SHUTDOWN_TIMEOUT_MS`, pool closed. The JVM runs with `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75
  -XX:+ExitOnOutOfMemoryError`; compose caps the container at 768 MB.

## Demo and inspecting the database

- `./scripts/seed.sh` (needs `curl` and `jq`): idempotently creates 2 practitioners, 4 patients, appointments in Booked,
  CheckedIn and Cancelled states, fills one practitioner's day to capacity and puts a patient on the waitlist. Ids are kept
  in `.seed-state.json` so re-running updates nothing twice.
- **Postman**: import `postman/ClinicScheduler.postman_collection.json` and `postman/local.postman_environment.json`,
  run the collection top to bottom; each request stores the ids the next one needs and asserts the expected statuses
  and codes (including the 409, 403 and 401 cases and the waitlist promotion).
- **DBeaver** (or psql) against the compose database: driver PostgreSQL, host `localhost`, port `5432`, database `clinic`,
  user `clinic`, password `clinic`. Ready-made queries in [docs/sql/queries.sql](docs/sql/queries.sql): day schedule per
  practitioner, no-shows per patient with the derived block flag, waitlist in FIFO order, appointment history, utilisation,
  the partial index definition and the Flyway history.
- **Where to see logs**: `docker compose logs -f app` (JSON lines; pipe through `jq` for readability), `docker compose logs postgres`.

## Project layout

```
kotlin-clinic-scheduler/
|-- build.gradle.kts, settings.gradle.kts, gradle/libs.versions.toml   Gradle 9.3, Kotlin 2.4, version catalog, Kover gates, ktlint
|-- Dockerfile, docker-compose.yml, .dockerignore, .env.example        multi-stage image (tests gate it), compose with healthchecks
|-- .github/workflows/ci.yml, .github/dependabot.yml                   build + PostgreSQL suite + artifacts, docker build + Trivy
|-- docs/errors.md, docs/TEST-CATALOG.md, docs/sql/queries.sql         error catalog, test catalog, DBeaver queries
|-- postman/, scripts/seed.sh                                          demo kit
|-- src/main/kotlin/com/dkrmerve/clinic/
|   |-- Application.kt            main: config -> database -> dependencies -> Netty (excluded from coverage)
|   |-- AppConfig.kt              env parsing, all problems reported together
|   |-- Dependencies.kt           hand-wired object graph
|   |-- domain/                   Ids, Practitioner, Patient, Appointment, AppointmentStatus, WaitlistEntry, TimeOff,
|   |                             SchedulingPolicy, SchedulingRules, Exceptions (sealed DomainException), TimeExtensions
|   |-- application/              Ports (UnitOfWork, repositories, Page), BookingEngine, *Service
|   |-- infrastructure/           Tables (Exposed DSL), ExposedRepositories, DatabaseFactory (Hikari, Flyway, UnitOfWork)
|   '-- api/                      Server (plugins), Routes, Dtos (+ Validator), Auth (JWT), Problems (RFC 7807), ApiTime
|-- src/main/resources/db/migration/{common,postgresql,h2}, logback.xml
'-- src/test/kotlin/com/dkrmerve/clinic/
    |-- domain/                   SchedulingRulesTest, DstTest, CancellationPolicyTest, NoShowPolicyTest,
    |                             AppointmentStateMachineTest, EntityInvariantsTest, Fixtures
    |-- api/                      ClinicApp (test harness), AuthTest, PractitionerApiTest, PatientApiTest, AppointmentApiTest,
    |                             WaitlistApiTest, ProblemMappingTest, OperationsTest, ConcurrencyTest
    |-- infrastructure/           ExposedRepositoriesTest, DatabaseFactoryTest (+ PostgresSchemaTest)
    '-- AppConfigTest, TestDatabases (H2 / Testcontainers switch), MutableClock
```

## Assumptions and design decisions

- **Slot grid of 15 minutes is the only one that fits all three default durations** (30/15/60). 10, 20 and 30 are
  accepted by the API contract but rejected as `slot_incompatible` until the appointment types change; the demo uses 15.
- **Availability also drops slots in the past** (rule 4) on top of rules 1-3 and 6, because offering a slot nobody can
  book is misleading. Blocked status (rule 8) is not applied to availability, since availability is not patient-specific.
- **No-show counts towards daily capacity** (rule 6 says "non-cancelled") but **does not block the slot** (rule 2 says
  "non-cancelled, non-no-show"). Completed appointments still block their slot; they are in the past anyway.
- **The no-show window is inclusive on both ends** and anchored at the most recent no-show; the block starts at the time
  the no-show is recorded (`now`), not at the missed appointment.
- **Waitlist promotion failure does not undo the cancellation.** The patient asked to cancel; a broken waitlist must not
  turn that into a 500 and a still-booked appointment. Promotion runs under a savepoint and the failure is logged at ERROR
  with the correlation id. The freed slot simply stays free.
- **Promotion books the waitlisted entry's own type at the freed start** and re-runs every rule, so a longer type that does
  not fit is skipped rather than shortened.
- **Rescheduling to the identical slot is a no-op (200)** rather than 409: the client's intent is satisfied.
- **A blocked patient's existing appointments remain valid**; the block only prevents new bookings and waitlist joins.
- **Patient identity**: a `patient` token's `sub` must equal the patient id. Creating patients is a staff task, mirroring a
  front desk registering someone; self-registration would need an identity-provider hook and is out of scope.
- **Actor** for history and the cancellation policy is derived from the role, not sent by the client.
- **Time strings must carry an offset.** A naive `2027-01-12T10:00` is ambiguous across zones and is rejected.
- **Path ids that are not UUIDs are 404**, not 400: such an id cannot name anything.
- **Lazy expiry** of waitlist entries and derived blocking avoid a scheduler; see the roadmap for the job that would
  make reporting queries simpler.
- **No-shows are never stored twice**: `Patient.noShows` is read from the `NoShow` appointments, so a no-show recorded
  late for an older appointment cannot be lost or duplicated.
- **Row locks are taken in one global order** (practitioners by id, then the patient) by booking, cancel, reschedule,
  no-show and waitlist join alike; a deadlock is therefore not expected, and if PostgreSQL ever reports one it becomes a
  retryable 409 rather than a 500.

## Trade-offs

- **Exposed DSL, no DAO/ORM**: explicit SQL-shaped code and no lazy-loading surprises, at the cost of hand-written mapping.
- **Manual DI**: one file shows the whole graph; a Koin module would save nothing at this size.
- **H2 for the fast suite**: seconds instead of a container start, but H2 is not PostgreSQL (no partial indexes, different
  locks), so the same suites run again on PostgreSQL and that run is the source of truth.
- **Row locks instead of SERIALIZABLE**: predictable, no retry loop needed; the price is that all bookings of one practitioner
  are serialised (fine at clinic scale).
- **In-memory rate limit keyed by subject / client address**: simple and dependency-free; behind a proxy it needs
  `TRUST_PROXY_HEADERS=true`, and a multi-instance deployment needs a shared store.
- **Kotest 6 + ktlint_official style**: strict formatting is enforced by the build; some wrapped signatures are more
  vertical than hand-written code would be.

## What is not tested

- The `main` function (`Application.kt`): config-to-Netty wiring and the shutdown hook are exercised only by the Docker
  compose end-to-end run, not by automated tests (excluded from coverage on purpose).
- The OIDC/JWKS verification path against a live identity provider. The configuration parsing is tested; the JWKS fetch,
  caching and RS256 verification are Ktor/auth0 library behaviour and would need a mock JWKS server (roadmap).
- Rate limiting keyed by real client addresses behind a proxy, and the Netty timeouts/limits (framework configuration).
- Long-running behaviour: pool leak detection, `maxLifetime` rotation, memory under sustained load.
- Deadlock scenarios other than reschedule-vs-booking (which has a 20-round parallel test); the global lock order is
  enforced by code (`AppointmentService.lockRows`, `BookingEngine.book`), not by a static check.
- Waitlist promotion racing with a concurrent direct booking of the same freed slot is covered indirectly (same row locks),
  but there is no dedicated parallel test for it.
- `XForwardedHeaders` is tested for the rate-limit key only, not for scheme/host rewriting.
- Trivy findings themselves: the scan runs in CI but the base image has not been pinned to a digest yet.

## Before going to production

- [ ] Put the service behind TLS termination (ingress / load balancer); the app speaks plain HTTP on 8080.
- [ ] Switch to OIDC mode (`AUTH_JWKS_URL`, `AUTH_ISSUER`, `AUTH_AUDIENCE`) and keep `AUTH_DEV_ISSUER_ENABLED=false`.
- [ ] Inject `DATABASE_PASSWORD` and any signing material from a secrets manager (Vault, AWS Secrets Manager, Kubernetes
      secrets), never from `.env`.
- [ ] Lock down CORS (no CORS plugin is installed: browsers on other origins are blocked by default; add an allow-list if a SPA needs it).
- [ ] Review request limits for your traffic: `RATE_LIMIT_PER_MINUTE`, `MAX_BODY_BYTES`, pool size vs. PostgreSQL `max_connections`.
- [ ] Set container CPU/memory requests and limits and match `DB_POOL_MAX` to them.
- [ ] Scrape `/metrics`, ship the JSON logs, and configure the alerts listed under Operations.
- [ ] Pin base images by digest (`eclipse-temurin@sha256:...`, `postgres@sha256:...`). CI already fails on CRITICAL/HIGH
      Trivy findings (`exit-code: 1`, unfixed ones ignored); keep it that way and rebuild when the base image is patched.
- [ ] Set `TRUST_PROXY_HEADERS=true` only once the service sits behind a proxy that overwrites `X-Forwarded-For`.
- [ ] Automate PostgreSQL backups and test a restore; enable point-in-time recovery for the appointments data.
- [ ] Decide the retention of `appointment_history` and of NoShow appointments (personal data) and document it.
- [ ] Run the migrations from a CI/CD step or an init container in multi-replica deployments so only one process migrates.
- [ ] Use a shared rate-limit store (Redis) if more than one replica runs; the bucket is in-memory per instance.

## Roadmap / TODO

- A scheduled job (or `pg_cron`) that marks past waitlist entries Expired and clears `blocked_until` once passed, so
  reporting queries do not need to re-derive state.
- Notify promoted waitlist patients (email/SMS) through an outbox table, so the notification is as transactional as the booking.
- Mock-JWKS test for the OIDC path; contract test with a real Keycloak container.
- Practitioner-side calendar endpoints (`GET /practitioners/{id}/appointments?date=`) and an admin endpoint to lift a block manually.
- Recurring time-off and per-day capacity overrides.
- OpenAPI document generated from the routes.
- Micrometer timers around the scheduling rules and the waitlist scan for finer-grained latency.
