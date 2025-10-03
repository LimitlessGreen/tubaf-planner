Dieses Verzeichnis (und seine Unterverzeichnisse) enthält Flyway Migrationen.

Historie & wichtige Entscheidungen:
1. Doppelte Version 1: Es existierte eine leere Datei mit abweichender Großschreibung (V1__Initial_schema.sql). Sie wurde gelöscht, da Flyway Groß/Kleinschreibung ignoriert und beide als Version 1 wertete.
2. Case-insensitive Kursnamen-Eindeutigkeit: Für PostgreSQL wird eine funktionale Eindeutigkeit (semester_id, lower(name)) verlangt. H2 unterstützt funktionale Indizes in dieser Form nicht zuverlässig und Tests sollen leichtgewichtig bleiben.
3. Strategie: Vendor-spezifische Migrationen über separate Unterordner und unterschiedliche Versionen (3 für PostgreSQL, 3.1 nur für H2) anstatt zwei konkurrierender V3 Dateien.
 4. Baseline-Neuschreibung (Entwicklungsphase): Die ursprüngliche prototypische V1 wurde durch eine neue V1__Initial_Schema.sql ersetzt, die das aktuelle Entity-Modell widerspiegelt. Nur auf frischen Umgebungen verwenden. Bestehende Datenbanken müssten andernfalls manuell migriert oder gedumpt + neu aufgebaut werden.

Aktuelle Struktur (bereinigt – keine Legacy/Platzhalter-Dateien mehr):
  db/migration/
    V1__Initial_Schema.sql
    V2__Room_Plan_Support.sql
  db/migration_postgresql/
    V3__Course_Name_Unique_Index.sql  (UNIQUE INDEX auf (semester_id, lower(name)))
  db/migration_h2/
    V3_1__H2_Course_Name_Unique_Index.sql (einfacher UNIQUE auf (semester_id, name))

Flyway Konfiguration:
- Default Profil: locations = classpath:db/migration,classpath:db/migration_postgresql
- Test Profil:    locations = classpath:db/migration,classpath:db/migration_h2

Warum Version 3.1 für H2?
- Flyway Versionsordnung: 3 < 3.1 < 4 ...
- So bleibt die semantische Aussage erhalten: Postgres erhält zuerst seine funktionale Eindeutigkeit. Tests (H2) erhalten eine eigene nachgelagerte Version ohne kollidierende Nummer.
- Verhindert "Found more than one migration with version 3" Fehler.

Legacy Dateien: Entfernt (Aufräum-Commit). Historie kann über Git nachvollzogen werden.

Namenskonventionen:
- Versionierte Migrationen: V<Version>__Beschreibung.sql
- Wiederholbare Migrationen: R__Beschreibung.sql
- Vendor-spezifische Migrationen: In vendor-Unterordnern + Aufnahme des Ordners in spring.flyway.locations.

Richtlinien für zukünftige vendor-spezifische Änderungen:
1. Wenn möglich gleiche Version + unterschiedliche SQL vermeiden (führt zu Konflikten).
2. Bevorzugt: Eine Version für Haupt-Datenbank, nachfolgende Minor-Version (z.B. 3.1, 3.2) für Test-/In-Memory-Anpassungen.
3. Alternativ: Nutzung von Flyway Java Callback Migrations oder Repeatables – hier (noch) nicht nötig.

Wiederherstellung älterer Branches:
- Falls eine alte generische V3 oder LEGACY__ Datei rebasen/mergen auftaucht: löschen oder in die aktuelle Struktur überführen.

Hinweis Deployment:
- Staging/Prod (PostgreSQL) MIGRATION ORDER: V1, V2, V3
- CI Tests (H2) MIGRATION ORDER: V1, V2, V3.1

Damit ist die geforderte case-insensitive Eindeutigkeit in Produktionsumgebungen gewährleistet, während Tests weiterhin leichtgewichtig bleiben.

Wichtiger Hinweis zur Baseline-Neuschreibung (Risiko / Betrieb):
- Die neue V1 hat eine andere Checksumme und inhaltlich ein völlig anderes Schema als die frühere prototypische Version. Existierende Datenbanken, die bereits Flyway-Historie mit der alten V1 besitzen, werden beim Start einen ChecksumMismatch ("Validate failed") melden.
- Optionen für bestehende Instanzen:
  1. Datenbank verwerfen und frisch migrieren (nur sinnvoll in nicht-produktiven Umgebungen).
  2. Einmaliges manuelles Anpassen: Flyway History Tabelle (flyway_schema_history) Eintrag für Version 1 löschen und Neu-Migration erzwingen (nur wenn Daten verworfen werden dürfen).
  3. Sauberer (empfohlen für Produktiv): Statt Baseline-Rewrite im Nachhinein ein Migrations-Upgrade (Vx -> ALTER …) verwenden. Da wir uns noch in der frühen Entwicklungsphase befinden, wurde hier der pragmatische Weg gewählt.
- Bitte vor dem ersten Produktiv-Datenimport eindeutig sicherstellen, dass keine Alt-Datenbank mit der alten V1 verwendet wird.

Empfehlungen für zukünftige Breaking Schema Changes:
1. Keine weitere Änderung an bestehenden Versionen vornehmen – immer neue Version hinzufügen.
2. Nur in sehr frühen, noch nicht veröffentlichten Entwicklungsphasen darf ein Baseline-Rewrite stattfinden.
3. Bei Bedarf an komplexer Vendor-Logik können Java-basierte Migrations (Flyway Callback oder JavaMigration) hinzugefügt werden.

Testcontainers / Integrations-Teststrategie:
- Ein dedizierter Postgres-Integrationstest verifiziert die funktionale Unique-Constraint (Index-Name: ux_courses_semester_lower_name).
- H2 Tests validieren weiterhin Geschäftslogik ohne funktionale Indizes.

Stand: Aktualisiert nach Einführung des funktionalen Unique Index und Baseline-Neuschreibung.
