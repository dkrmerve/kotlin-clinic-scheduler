-- A patient's no-shows are the appointments in status NoShow. Storing them a second time let the two copies
-- drift (a retroactively recorded no-show could be lost), so the copy is dropped. Nothing needs migrating:
-- every row in it corresponds to an appointment row with status 'NoShow'.
DROP TABLE patient_no_shows;
