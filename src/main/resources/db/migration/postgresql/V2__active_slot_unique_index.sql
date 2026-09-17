-- Last line of defence against double booking: two active appointments (Booked or CheckedIn) of the same
-- practitioner can never share a start time, whatever the application layer does. Cancelled, NoShow and
-- Completed rows are excluded so a freed slot can be re-booked.
CREATE UNIQUE INDEX ux_appointments_active_slot
    ON appointments (practitioner_id, start_at)
    WHERE status IN ('Booked', 'CheckedIn');
