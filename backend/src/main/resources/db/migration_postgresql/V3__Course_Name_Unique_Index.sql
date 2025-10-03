-- V3 (PostgreSQL): Case-insensitive Unique Index für Kursnamen je Semester
-- Nutzt funktionalen Index mit lower(name)

CREATE UNIQUE INDEX IF NOT EXISTS ux_courses_semester_lower_name
    ON courses (semester_id, lower(name));
