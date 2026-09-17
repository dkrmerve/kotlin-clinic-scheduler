-- H2 (fast test suite only) has no partial indexes; the PostgreSQL migration of the same version creates
-- the partial unique index. Here a plain index keeps the version history identical between vendors.
CREATE INDEX ix_appointments_active_slot ON appointments (practitioner_id, start_at, status);
