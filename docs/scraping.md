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

