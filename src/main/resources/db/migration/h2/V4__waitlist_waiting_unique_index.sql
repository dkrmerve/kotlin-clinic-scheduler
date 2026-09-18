-- H2 (fast test suite only) has no partial indexes; the PostgreSQL migration of the same version is the unique one.
CREATE INDEX ix_waitlist_waiting ON waitlist_entries (practitioner_id, patient_id, entry_date, status);
