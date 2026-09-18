# Error catalog

Every failure is returned as [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807) problem JSON
(`Content-Type: application/problem+json`). `type` is `https://github.com/dkrmerve/kotlin-clinic-scheduler/docs/errors#<code>`,
so each anchor below is a stable, linkable identifier. `correlationId` echoes the `X-Correlation-Id` of the request.

```json
{
  "type": "https://github.com/dkrmerve/kotlin-clinic-scheduler/docs/errors#slot_taken",
  "title": "Conflict",
  "status": 409,
  "detail": "Slot overlaps appointment 6f1c... (including the 10-minute buffer)",
  "code": "slot_taken",
  "instance": "/appointments",
  "correlationId": "2c0d8a0e-..."
}
```

The mapping from exception type to HTTP status lives in one place, `src/main/kotlin/com/dkrmerve/clinic/api/Problems.kt`
(`DomainException.httpStatus()`), as an exhaustive `when` over the sealed `DomainException` hierarchy. Adding a
subtype without deciding its status is a compile error; `ProblemMappingTest` pins every row of this table.

## Catalog

| Code | HTTP | Raised by | When it happens |
|------|------|-----------|-----------------|
| <a id="validation_failed"></a>`validation_failed` | 400 | `RequestValidationException` (API edge) | One or more request fields are invalid. The `errors` map lists every field with its problem. |
| <a id="malformed_request"></a>`malformed_request` | 400 | Ktor `BadRequestException` / `SerializationException` | Body is not valid JSON, is empty where a body is required, or has wrong field types. |
| <a id="invalid_practitioner"></a>`invalid_practitioner` | 400 | `ValidationException` | Practitioner invariant violated (blank name or specialty, slot length not in {10,15,20,30}, negative buffer, capacity below 1). |
| <a id="slot_incompatible"></a>`slot_incompatible` | 400 | `ValidationException` | Slot length does not divide every appointment type duration (30/15/60 min). Only 15 does for the default types. |
| <a id="invalid_schedule"></a>`invalid_schedule` | 400 | `ValidationException` | Working window start >= end, empty schedule, or a window shorter than one slot. |
| <a id="invalid_patient"></a>`invalid_patient` | 400 | `ValidationException` | Blank name, malformed email, negative counters. |
| <a id="invalid_time_off"></a>`invalid_time_off` | 400 | `ValidationException` | Time-off block from >= to, or blank reason. |
| <a id="invalid_appointment"></a>`invalid_appointment` | 400 | `ValidationException` | Appointment end does not equal start + type duration (cannot happen through the API). |
| <a id="invalid_policy"></a>`invalid_policy` | 400 | `ValidationException` | Scheduling policy values inconsistent (startup only). |
| <a id="unauthenticated"></a>`unauthenticated` | 401 | `UnauthenticatedException` | Missing, expired, malformed or wrongly signed bearer token, or a token without `exp`, `sub` or `role`. |
| <a id="forbidden_role"></a>`forbidden_role` | 403 | `ForbiddenException` | The token role may not call this endpoint (see README, "Authentication and authorization"). |
| <a id="not_owner"></a>`not_owner` | 403 | `ForbiddenException` | A `patient` token acts on another patient record. |
| <a id="patient_blocked"></a>`patient_blocked` | 403 | `PatientBlockedException` | Patient has three no-shows in 90 days; `blockedUntil` is in the body. Booking and waitlist joins are refused. |
| <a id="practitioner_not_found"></a>`practitioner_not_found` | 404 | `NotFoundException` | Unknown or non-UUID practitioner id. |
| <a id="patient_not_found"></a>`patient_not_found` | 404 | `NotFoundException` | Unknown or non-UUID patient id. |
| <a id="appointment_not_found"></a>`appointment_not_found` | 404 | `NotFoundException` | Unknown or non-UUID appointment id. |
| <a id="waitlist_entry_not_found"></a>`waitlist_entry_not_found` | 404 | `NotFoundException` | Unknown or non-UUID waitlist entry id. |
| <a id="route_not_found"></a>`route_not_found` | 404 | Ktor status page | No route matches the path (includes `POST /auth/token` when the dev issuer is disabled). |
| <a id="method_not_allowed"></a>`method_not_allowed` | 405 | Ktor status page | Route exists, method does not. |
| <a id="invalid_transition"></a>`invalid_transition` | 409 | `InvalidTransitionException` | Rule 10: the appointment (or waitlist entry) is not in a state that allows this step, e.g. cancelling a completed or already cancelled appointment. |
| <a id="slot_taken"></a>`slot_taken` | 409 | `ConflictException` | Rule 2: overlaps an existing Booked, CheckedIn or Completed appointment of the practitioner including the buffer; also raised when the partial unique index rejects a concurrent insert. |
| <a id="patient_conflict"></a>`patient_conflict` | 409 | `ConflictException` | Rule 5: patient already has an appointment with this practitioner that day, or an overlapping appointment elsewhere. |
| <a id="daily_capacity_reached"></a>`daily_capacity_reached` | 409 | `ConflictException` | Rule 6: practitioner already has `maxAppointmentsPerDay` non-cancelled appointments that day. |
| <a id="waitlist_duplicate"></a>`waitlist_duplicate` | 409 | `ConflictException` | Patient already has a Waiting entry for that practitioner and date (checked under the patient row lock; the partial unique index `ux_waitlist_waiting` is the backstop). |
| <a id="no_show_before_start"></a>`no_show_before_start` | 409 | `ConflictException` | Rule 8: a no-show can only be recorded after the appointment start time. |
| <a id="concurrent_modification"></a>`concurrent_modification` | 409 | `ConcurrencyException`, SQLSTATE 40P01 / 40001 | Optimistic lock: the appointment row changed since it was read (`version` mismatch), or PostgreSQL reported a deadlock / serialization failure. Retry the request. |
| <a id="conflict"></a>`conflict` | 409 | `ExposedSQLException` (SQLSTATE 23505) | A unique constraint fired outside the booking path. |
| <a id="payload_too_large"></a>`payload_too_large` | 413 | Ktor `RequestBodyLimit` | Body exceeds `MAX_BODY_BYTES`. |
| <a id="unsupported_media_type"></a>`unsupported_media_type` | 415 | Ktor content negotiation | Request body is not `application/json`. |
| <a id="outside_working_hours"></a>`outside_working_hours` | 422 | `RuleViolationException` | Rule 1: no working window that weekday, start outside the window, or the appointment would end after closing. |
| <a id="slot_misaligned"></a>`slot_misaligned` | 422 | `RuleViolationException` | Rule 1: start is not on the slot grid counted from the window start (or has seconds). |
| <a id="practitioner_unavailable"></a>`practitioner_unavailable` | 422 | `RuleViolationException` | Rule 3: intersects a time-off block. |
| <a id="outside_booking_horizon"></a>`outside_booking_horizon` | 422 | `RuleViolationException` | Rule 4: start not strictly in the future, or more than 60 days ahead (also for availability and waitlist dates). |
| <a id="cancellation_window_closed"></a>`cancellation_window_closed` | 422 | `RuleViolationException` | Rule 7: a patient cancels (or reschedules) less than 2 hours before the start. |
| <a id="rate_limited"></a>`rate_limited` | 429 | Ktor `RateLimit` | More than `RATE_LIMIT_PER_MINUTE` POST requests per minute from one client address. |
| <a id="internal_error"></a>`internal_error` | 500 | anything else | Unexpected failure. The response never contains the message or a stack trace; the log line carries the correlation id. |
