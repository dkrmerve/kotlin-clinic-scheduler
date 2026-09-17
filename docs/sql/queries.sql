-- Handy queries for inspecting the compose PostgreSQL in DBeaver or psql.
-- Connection (docker compose): host localhost, port 5432, database clinic, user clinic, password clinic.
-- All timestamps are stored as timestamptz in UTC; the casts below render them in the clinic zone.

-- 1. Day schedule per practitioner (what the front desk sees)
SELECT pr.name                                            AS practitioner,
       a.start_at AT TIME ZONE 'Europe/Amsterdam'         AS starts_local,
       a.end_at   AT TIME ZONE 'Europe/Amsterdam'         AS ends_local,
       a.type,
       a.status,
       pa.name                                            AS patient,
       a.promoted_from_waitlist
FROM appointments a
JOIN practitioners pr ON pr.id = a.practitioner_id
JOIN patients      pa ON pa.id = a.patient_id
WHERE (a.start_at AT TIME ZONE 'Europe/Amsterdam')::date = CURRENT_DATE + 1   -- change the date as needed
ORDER BY pr.name, a.start_at;

-- 2. No-show counts per patient in the rolling 90-day window, with the derived "blocked" flag
SELECT pa.name,
       pa.email,
       COUNT(ns.occurred_at) FILTER (WHERE ns.occurred_at >= now() - INTERVAL '90 days') AS no_shows_90d,
       pa.late_cancellations,
       pa.blocked_until,
       (pa.blocked_until IS NOT NULL AND pa.blocked_until > now())                        AS blocked_now
FROM patients pa
LEFT JOIN patient_no_shows ns ON ns.patient_id = pa.id
GROUP BY pa.id
ORDER BY no_shows_90d DESC, pa.name;

-- 3. Waitlist in promotion (FIFO) order for a practitioner and date
SELECT w.entry_date,
       pr.name       AS practitioner,
       pa.name       AS patient,
       w.type,
       w.status,
       w.created_at,
       w.fulfilled_by
FROM waitlist_entries w
JOIN practitioners pr ON pr.id = w.practitioner_id
JOIN patients      pa ON pa.id = w.patient_id
WHERE w.entry_date = CURRENT_DATE + 1                              -- change as needed
ORDER BY pr.name, w.created_at;

-- 4. Full history of one appointment (rule 11)
SELECT h.occurred_at AT TIME ZONE 'Europe/Amsterdam' AS at_local,
       h.actor,
       h.from_status,
       h.to_status,
       h.to_cancelled_by,
       h.to_late,
       h.note
FROM appointment_history h
WHERE h.appointment_id = '00000000-0000-0000-0000-000000000000'   -- paste an appointment id
ORDER BY h.id;

-- 5. Utilisation per practitioner per day (active appointments and booked minutes)
SELECT pr.name,
       (a.start_at AT TIME ZONE 'Europe/Amsterdam')::date                            AS day,
       COUNT(*) FILTER (WHERE a.status IN ('Booked', 'CheckedIn', 'Completed'))      AS active_appointments,
       SUM(EXTRACT(EPOCH FROM (a.end_at - a.start_at)) / 60)
         FILTER (WHERE a.status IN ('Booked', 'CheckedIn', 'Completed'))             AS booked_minutes,
       pr.max_appointments_per_day
FROM appointments a
JOIN practitioners pr ON pr.id = a.practitioner_id
GROUP BY pr.id, day
ORDER BY day, pr.name;

-- 6. The last line of defence: the partial unique index on active appointments
SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'appointments';

-- 7. Applied migrations
SELECT installed_rank, version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank;
