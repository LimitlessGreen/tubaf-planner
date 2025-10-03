-- V3 (H2): Vereinfachter Unique Index (ohne lower()) – H2 unterstützt funktionale Indizes hier nicht identisch
-- Näherung: Unique je (semester_id, name). Falls echte Case-Insensitivität relevant wird, Testprofil wieder ohne Flyway betreiben.

CREATE UNIQUE INDEX IF NOT EXISTS ux_courses_semester_name
    ON courses (semester_id, name);
