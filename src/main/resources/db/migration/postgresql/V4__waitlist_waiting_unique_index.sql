-- One Waiting entry per patient, practitioner and date, whatever the application does concurrently.
-- Fulfilled and Expired rows are excluded so a patient can join again after being served.
CREATE UNIQUE INDEX ux_waitlist_waiting
    ON waitlist_entries (practitioner_id, patient_id, entry_date)
    WHERE status = 'Waiting';
