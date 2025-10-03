# Scraping-Leitfaden für das Vorlesungsverzeichnis

Dieser Leitfaden fasst erprobte Strategien zusammen, um den stateful Webauftritt [`https://evlvz.hrz.tu-freiberg.de/~vover/`](https://evlvz.hrz.tu-freiberg.de/~vover/) automatisiert auszulesen. Alle Beispiele verwenden `curl`; andere HTTP-Clients lassen sich analog einsetzen, solange sie Cookies über mehrere Requests hinweg beibehalten.

## 1. Sitzung aufbauen

Die Anwendung legt beim ersten Aufruf einen PHP-Session-Cookie (`PHPSESSID`) ab und hängt ihn sämtlichen Folgeseiten an. Ohne diesen Cookie vergisst der Server u. a. die gewählte Semesterkonfiguration.

```bash
curl -c cookies.txt -b cookies.txt -L \
     https://evlvz.hrz.tu-freiberg.de/~vover/ \
     -o /tmp/start.html
```

Der kombinierte `-c/-b`-Einsatz speichert und sendet die Session automatisch weiter. Skripte sollten deshalb konsequent mit einer gemeinsamen Cookie-Datei arbeiten.

## 2. Semester wählen

Die Startseite enthält ein `select`-Feld `sem_wahl` und ein verstecktes Feld `wechsel`. Ein POST auf `index.html` speichert das gewünschte Semester serverseitig in der Session.

```bash
curl -s -c cookies.txt -b cookies.txt \
     -d "sem_wahl=Sommersemester+2024&wechsel=4" \
     https://evlvz.hrz.tu-freiberg.de/~vover/index.html
```

* `sem_wahl` akzeptiert den sichtbaren Text (z. B. `Wintersemester 2025 / 2026`).
* `wechsel=3` (JavaScript-Auto-Submit) oder `wechsel=4` (Noscript-Variante) funktionieren beide.

Die Auswahl lässt sich mit einem nachfolgenden GET verifizieren – im HTML trägt die entsprechende `option` das Attribut `selected`.

## 3. Suchformular verstehen

Die Seite [`suche.html`](https://evlvz.hrz.tu-freiberg.de/~vover/suche.html) liefert sämtliche Auswahllisten (Räume, Wochentage, Hörergruppen, Semester etc.). Wichtige Details:

* Jedes Kriterium wird durch ein Checkbox-Feld `suche[XYZx]` aktiviert.
* Der eigentliche Wert steckt in `suche[XYZ]`.
* Die Senden-Schaltfläche trägt den Namen `senden` und den Wert `  Suchen  ` (mit führenden und abschließenden Leerzeichen). Serverseitig genügt allerdings auch `senden=Suchen`.

Beim automatisierten Ausfüllen müssen **Checkbox und Feld** übertragen werden, sonst lehnt der Server die Anfrage mit „Wählen Sie bitte ein Suchkriterium aus!“ ab.

Beispiel: Volltextsuche nach Veranstaltungen, deren Titel „Mathematik“ enthält.

```bash
curl -s -c cookies.txt -b cookies.txt \
     -d "suche%5Btitelx%5D=1&suche%5Btitel%5D=Mathematik&senden=Suchen" \
     https://evlvz.hrz.tu-freiberg.de/~vover/ergebnis.html \
     -o /tmp/ergebnis.html
```

## 4. Suchergebnisse verarbeiten

Die Antwortseite `ergebnis.html` enthält eine Tabelle mit allen Treffern. Pro Zeile tauchen u. a. folgende Elemente auf:

* `input type="checkbox" name="check[]" value="<ID>` – die ID identifiziert die Lehrveranstaltung eindeutig.
* Spalten `Art`, `Titel`, `Lehrende`, `Tag`, `Zeit`, `Raum`, `Woche` und eine Info-Schaltfläche.
* Die Info-Schaltfläche ruft `javascript:info_lv_fenster(<ID>)` auf; ohne JavaScript führt der `<noscript>`-Link direkt zu `info.html?satz=<ID>`.

Die relevanten Lehrveranstaltungsnummern können für Detailabfragen oder spätere Sammelaktionen weiterverwendet werden. Zum Parsen bieten sich HTML-Parser an, die Tabellenstrukturen robust verarbeiten können.

## 5. Detailinformationen abrufen

Für jede ID liefert [`info.html?satz=<ID>`](https://evlvz.hrz.tu-freiberg.de/~vover/info.html?satz=16009) eine eigenständige Seite mit:

* Veranstaltungstitel, Typ und Lehrende
* Terminblöcken (Tag, Uhrzeit, Raum, Turnus)
* Auflistung der zugehörigen Hörergruppen inkl. Status (Pflicht/Wahlpflicht/etc.)

Die Seite benötigt lediglich die aktive Session; weitere Parameter sind nicht nötig.

## 6. Raumpläne exportieren

Die Seite [`plaene.html`](https://evlvz.hrz.tu-freiberg.de/~vover/plaene.html) enthält ein `select`-Feld `raum` und zwei Buttons:

* `html_send=Raumplan als HTML`
* `pdf_send=Raumplan als PDF`

Beim HTML-Export führt der Server zuerst `raumplan.html`, welche einen Meta-Refresh auf `druck_html.html?art=raumplan&raum=<...>` auslöst. Der finale Inhalt (Wochenübersicht mit Verlinkung auf `info.html?satz=...`) steckt in `druck_html.html`.

```bash
curl -s -b cookies.txt \
     "https://evlvz.hrz.tu-freiberg.de/~vover/druck_html.html?art=raumplan&raum=PR%C3%9C-1103" \
     -o /tmp/raumplan.html
```

Für den PDF-Export kann man stattdessen `raumplan.html?raum=...&pdf_send=Raumplan+als+PDF` aufrufen und dem `Location`-/Meta-Refresh folgen; der Server liefert ein binäres PDF.

## 7. Studiengangspläne über `stgvrz.html`

Die Navigation „Pläne nach Studiengang“ verlinkt direkt auf `stgvrz.html`. Praktisch wichtige Punkte:

* Die Linkliste (z. B. aus `verz.html`) enthält Kennungen wie `stdg=BAI` und den Klartextnamen `stdg1=Angewandte Informatik (Bachelor)`. Die Paare lassen sich aus dem HTML auslesen, indem alle Links auf `stgvrz.html?stdg=` gesammelt und ihre Parameter ausgewertet werden.
* Der eigentliche Stundenplan steckt in einer Tabelle innerhalb von `stgvrz.html`. Der gewünschte Fachsemester-Filter (`semest`) wird per POST gesetzt.

```bash
curl -s -c cookies.txt -b cookies.txt \
     -d "stdg=BAI&stdg1=Angewandte+Informatik+(Bachelor)&semest=4.Semester" \
     https://evlvz.hrz.tu-freiberg.de/~vover/stgvrz.html \
     -o /tmp/studiengang-plan.html
```

Die Tabelle besitzt eine konsistente Struktur (Spalten „Art“, „Titel“, „Lehrende“, „Tag“, „Zeit“, „Raum“, „Woche“, „Info“) und lässt sich analog zu den Suchergebnissen auswerten. Für mehrere Fachsemester einfach Werte wie `2.Semester`, `4.Semester`, `6.Semester`, … iterieren.

Unterhalb der Tabelle verlinkt der Server zusätzliche Exporte:

* `druck_html.html?art=stundenplan&gang=<NAME>%23false%23<SEM>` liefert eine reduzierte HTML-Ansicht.
* `druck_pdf.html?…` und `druck_txt.html?…` geben jeweils PDF-Dateien zurück (trotz der Dateiendung „txt“).

Diese URLs benötigen dieselbe Session (wegen Semesterkontext) und dieselben Parameter wie `stgvrz.html`.

## 8. Weitere Endpunkte

* `samml.html` / `stundenplan.html` akzeptieren GET-Requests mit denselben `check[]`-IDs wie aus den Suchtabellen. Das Menü löst diese Aufrufe sonst via JavaScript (`funct.js`) aus.
* `druck_html.html` unterstützt weitere `art`-Parameter (z. B. `sammlung`, `stundenplan`) und erzeugt druckfertige HTML-Ausgaben.
* Die Anwendung liefert konsequent UTF-8 kodierte Seiten; Sonderzeichen wie `Ü` müssen bei GET-Parametern URL-kodiert werden (`PR%C3%9C-1103`).

## 9. Empfehlungen für produktives Scraping

1. **Session-Reuse:** Verwende eine persistente Cookie-Datei, damit alle Navigationsschritte dasselbe `PHPSESSID` nutzen.
2. **Formulare frisch auslesen:** Die Optionslisten (Räume, Hörergruppen usw.) ändern sich pro Semester. Hole `suche.html`, `plaene.html` oder `verz.html` regelmäßig, anstatt Werte hart zu codieren.
3. **JavaScript-Fallbacks nutzen:** Fast alle Aktionen bieten `<noscript>`-Links. Diese zeigen exakt die URLs an, die man automatisiert ansteuern kann.
4. **Redirects verfolgen:** Einige Exporte (Raumplan, Stundenplan) arbeiten mit Meta-Refresh. Folge daher automatisch auf `druck_html.html` oder werte das `meta http-equiv="refresh"`-Tag aus.
5. **Respectful Crawling:** Begrenze Request-Raten, da jede Aktion serverseitig Datenbankarbeit auslöst und der Dienst öffentlich für Studierende bereitsteht.
6. **Antworten prüfen:** Überwache Statuscodes und Antwortgrößen, um Session-Timeouts oder Fehlermeldungen früh zu erkennen.

---

## 10. Aktuelle Implementierung im TUBAF Planner

Der **TUBAF Planner** implementiert ein robustes Scraping-System basierend auf den oben beschriebenen Strategien. Die Implementierung nutzt **OkHttp3** als HTTP-Client und **JSoup** zum HTML-Parsing.

### 10.1 Architektur

```
TubafScrapingService
├── TubafScraperSession (Session-Management)
│   ├── Cookie-Handling (JavaNetCookieJar + CookieManager)
│   ├── fetchSemesterOptions() → Liest select[name=sem_wahl]
│   ├── selectSemester() → POST mit sem_wahl + wechsel=4
│   ├── fetchStudyPrograms() → Parst verz.html für Studiengänge
│   ├── openProgram() → GET stgvrz.html?stdg=...
│   └── openProgramSemester() → POST mit stdg, stdg1, semest
├── Discovery Mode → Findet automatisch alle verfügbaren Semester
├── Remote Scraping → Scrapt ausgewählte Remote-Semester
└── Local Scraping → Scrapt bereits erstellte lokale Semester
```

### 10.2 Implementierte Features

#### ✅ **Session-Management (Punkt 1)**
- Persistente Cookie-Verwaltung über `JavaNetCookieJar`
- `CookiePolicy.ACCEPT_ALL` für alle TUBAF-Cookies
- Automatisches Session-Reuse über alle Requests hinweg

```kotlin
private val cookieManager = CookieManager().apply {
    setCookiePolicy(CookiePolicy.ACCEPT_ALL)
}
private val httpClient = OkHttpClient.Builder()
    .cookieJar(JavaNetCookieJar(cookieManager))
    .build()
```

#### ✅ **Semester-Auswahl (Punkt 2)**
- `fetchSemesterOptions()` extrahiert alle verfügbaren Semester aus `select[name=sem_wahl]`
- `selectSemester()` sendet POST mit korrekten Parametern (`sem_wahl`, `wechsel=4`)
- Semester-Matching unterstützt verschiedene Namensformate

#### ✅ **Studiengangspläne (Punkt 7)**
- `fetchStudyPrograms()` parst `verz.html` und extrahiert:
  - Studiengangscodes (`stdg`)
  - Klartextnamen (`stdg1`)
  - Fakultätszuordnung
  - Links zu `stgvrz.html`
- `openProgramSemester()` scrapt Fachsemester-spezifische Pläne
- Tabellen werden robust mit JSoup geparst (Spalten: Art, Titel, Lehrende, Tag, Zeit, Raum, Woche)

#### ✅ **Intelligentes Entity-Management**
- **Dozenten**: Automatische Erkennung und Wiederverwendung basierend auf Name + Titel
- **Räume**: Parsing von "Gebäude/Raum"-Format (z.B. "PRÜ-1103")
- **Kurse**: Deduplizierung nach Name + Semester
- **Veranstaltungstypen**: Dynamische Erstellung (V, Ü, P, S, etc.)

#### ✅ **Change Tracking**
- Jeder Scraping-Lauf wird protokolliert (`ScrapingRun`)
- Änderungen an Entitäten werden getrackt (`ChangeLog`)
- Statistiken: Anzahl neuer/aktualisierter Einträge pro Run

#### ✅ **Progress Tracking**
- Echtzeit-Fortschrittsanzeige während des Scrapings
- Detaillierte Logs mit Emoji-Indikatoren für bessere Lesbarkeit
- Job-Management mit Abbruch-Funktionalität

### 10.3 NICHT implementierte Features

Diese Features aus der Dokumentation wurden bewusst **nicht** implementiert, da sie für den aktuellen Anwendungsfall nicht benötigt werden:

#### ❌ **Suchformular (Punkt 3-4)**
- `suche.html` mit Checkbox-Feldern `suche[XYZx]`
- `ergebnis.html` mit Suchergebnissen
- **Grund**: Wir scrapen direkt über Studiengangspläne, was vollständigere Daten liefert

#### ❌ **Detailinformationen (Punkt 5)**
- `info.html?satz=<ID>` für Veranstaltungsdetails
- **Potenzial**: Könnte für zusätzliche Infos (Hörergruppen, Turnus-Details) nützlich sein
- **Status**: Optional für zukünftige Erweiterung

#### ❌ **Raumpläne (Punkt 6)**
- `plaene.html` und `druck_html.html?art=raumplan`
- **Potenzial**: Feature-Request für Raumverwaltung
- **Status**: Nicht prioritär, da Rauminformationen bereits aus Studiengangsplänen extrahiert werden

#### ❌ **Weitere Endpunkte (Punkt 8)**
- `samml.html`, `stundenplan.html`, `druck_pdf.html`
- **Grund**: Unsere HTML-Parsing-Strategie ist bereits ausreichend

### 10.4 API-Endpunkte

Das Scraping-System bietet folgende REST-Endpunkte:

```
POST /api/scraping/discover-and-scrape
  → Findet automatisch alle verfügbaren Semester und scrapt sie

POST /api/scraping/scrape-remote
  → Scrapt ausgewählte Remote-Semester
  Body: { "semesterIdentifiers": ["Sommersemester 2024", ...] }

POST /api/scraping/semester/{semesterId}/scrape
  → Scrapt ein bereits erstelltes lokales Semester

GET /api/scraping/status
  → Gibt aktuellen Scraping-Status zurück (läuft/idle)

POST /api/scraping/cancel
  → Bricht laufendes Scraping ab
```

### 10.5 Logging & Monitoring

Das System nutzt **umfangreiches Emoji-basiertes Logging** für bessere Übersicht:

```
🚀 Starting TUBAF scraping...
🔧 Initializing browser...
📚 Processing study program [1/25]: BAI
👨‍🏫 Creating new lecturer: 'Prof. Dr. Müller'
🏫 Using existing room: 'PRÜ-1103' (ID: 42)
📚 Creating new course: 'Mathematik I' for semester 'WS24'
✅ Schedule entry created for course 'Mathematik I' at 'Mo 08:00-09:30'
🎉 FINAL RESULT: Scraping completed successfully!
```

### 10.6 Best Practices (implementiert)

1. ✅ **Session-Reuse**: Alle Requests nutzen dieselbe Session mit persistenten Cookies
2. ✅ **Dynamische Formulardaten**: Semester-Optionen werden zur Laufzeit ausgelesen
3. ✅ **Referer-Header**: Korrekte Referer für alle POST-Requests
4. ✅ **UTF-8 Encoding**: Sonderzeichen werden korrekt verarbeitet
5. ✅ **Error Handling**: Detaillierte Fehlerbehandlung mit Retry-Logik
6. ✅ **Transaction Management**: Spring `@Transactional` für Datenkonsistenz
7. ✅ **Background Jobs**: Asynchrones Scraping mit Job-Management

### 10.7 Erweiterungsmöglichkeiten

Folgende Features könnten in Zukunft implementiert werden:

1. **Detailinformationen via `info.html`**: Zusätzliche Veranstaltungsdetails (Hörergruppen, genauer Turnus)
2. **Raumplan-Integration**: Scraping von `plaene.html` für Raumverwaltung
3. **PDF-Export**: Nutzung von `druck_pdf.html` für Exportfunktionen
4. **Suchfunktion**: Integration von `suche.html` für gezielte Abfragen
5. **Incremental Updates**: Nur geänderte Daten scrapen statt kompletter Refresh

---