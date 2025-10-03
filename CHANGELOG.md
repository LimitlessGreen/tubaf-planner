# Changelog

Alle nennenswerten Änderungen dieses Projekts werden in dieser Datei dokumentiert.
Das Format orientiert sich an [Keep a Changelog](https://keepachangelog.com/de/1.1.0/),
und die Versionierung folgt (perspektivisch) [SemVer](https://semver.org/lang/de/).

## [Unreleased]
### Hinzugefügt
- PostgreSQL: Funktionaler Unique Index `ux_courses_semester_lower_name` auf `(semester_id, lower(name))` für fallunabhängig eindeutige Kursnamen je Semester.
- H2 (Tests): Separater einfacher Unique Index (Migration `V3_1__H2_Course_Name_Unique_Index.sql`).
- Testcontainers Integrationstest, der die Constraint-Verletzung bei gleicher Schreibweise unterschiedlicher Groß/Kleinschreibung prüft.
- Erweiterte Baseline (`V1__Initial_Schema.sql`) vollständig an das aktuelle Entity-Modell angepasst.
- README im Migrationsordner mit Vendor-Strategie und Risiken der Baseline-Neuschreibung.

### Geändert
- `CourseType` Entity: Code-Länge von 1 auf bis zu 10 Zeichen erweitert.
- Join-Tabelle `course_study_programs`: Jetzt mit Surrogat-ID, Audit-Feldern und UNIQUE(course_id, study_program_id).
- `schedule_entries`: Struktur an Entity (Enums als String, week_pattern, active) angepasst.

### Entfernt
- Legacy/Prototyp-Migrationsdateien und fehlerhafte doppelte V1-Datei.

### Wichtige Hinweise
- BREAKING: Die alte V1 wurde ersetzt. Bereits migrierte Datenbanken mit ursprünglicher V1 benötigen frischen Aufbau oder manuelle Anpassung.

## [0.0.1-SNAPSHOT] - Initial
- Projektgrundlage erstellt.

---

## Release-Prozess (Kurzform)
1. Offene Punkte unter [Unreleased] prüfen / bereinigen.
2. Version in `backend/build.gradle.kts` anheben (z.B. 0.1.0) falls ein konsistentes Feature-Set erreicht ist.
3. Abschnitt [Unreleased] in neue Versionssektion kopieren und Datum setzen.
4. Leeren [Unreleased]-Block vorbereiten.
5. Tag setzen und veröffentlichen.

Beispiel für neue Version:
```
## [0.1.0] - 2025-10-03
### Hinzugefügt
...
```
