-- V3: Unique Index für Kursnamen je Semester (case-insensitive)
-- Stellt sicher, dass pro Semester kein Kurs mit identischem Namen (unabhängig von Groß-/Kleinschreibung) doppelt angelegt wird.
-- Verwendet LOWER(name) für Datenbanken ohne CITEXT.

CREATE UNIQUE INDEX IF NOT EXISTS ux_courses_semester_lower_name
    ON courses (semester_id, LOWER(name));
