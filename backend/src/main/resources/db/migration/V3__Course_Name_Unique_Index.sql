-- Anpassung für H2 Kompatibilität: Funktionsbasierte Indizes mit LOWER(name) führen in H2 zu Syntaxfehlern.
-- Lösung: Generierte (persistierte) Spalte name_lower hinzufügen und darauf den Unique Index definieren.
-- Postgres: GENERATED ALWAYS AS (lower(name)) STORED
-- H2: Unterstützt ebenfalls GENERATED ALWAYS AS (...)

-- Spalte nur hinzufügen falls nicht vorhanden (Flyway führt Migration nur einmal aus; idempotente Guard für lokale Setups)
ALTER TABLE courses ADD COLUMN IF NOT EXISTS name_lower VARCHAR(200) GENERATED ALWAYS AS (lower(name)) STORED;

-- Bestehenden alten Index (falls vorheriger Versuch) defensiv entfernen
DROP INDEX IF EXISTS ux_courses_semester_lower_name;

-- Neuen Unique Index auf der generierten Spalte erstellen
CREATE UNIQUE INDEX IF NOT EXISTS ux_courses_semester_name_lower
    ON courses (semester_id, name_lower);
