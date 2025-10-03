Dieses Verzeichnis enthält Flyway Migrationen.

Problemhistorie:
- Es existierten zwei Dateien mit Version 1:
  - V1__Initial_Schema.sql (korrekt, enthält DDL)
  - V1__Initial_schema.sql (leer, unterschied nur im Groß/Kleinschreiben von "Schema")

Flyway betrachtet beide als Version 1 und bricht mit "Found more than one migration with version 1" ab.

Lösung:
- Die leere Datei wurde entfernt. Bitte keine zweite V1 Datei wieder hinzufügen.

Hinweis zu Namenskonventionen:
- Versionierte Migrationen: V<Version>__Beschreibung.sql
- Wiederholbare Migrationen: R__Beschreibung.sql
