# Datenschutz und Datenflüsse

Stand: 2026-07-26 (Milestone M5) · Verantwortlich für dieses Dokument: Sebastian Kern

Dieses Dokument beschreibt, welche Daten die Plattform verarbeitet, wohin sie fließen und was
**nicht** passiert. SPEC §6 verlangt es ausdrücklich für das KI-Feature; es deckt hier den ganzen
Datenfluss ab, weil ein Datenschutzhinweis, der nur einen Teil beschreibt, irreführender ist als
keiner.

Es ist eine technische Beschreibung, keine Rechtsberatung und kein AV-Vertrag.

---

## 1. Der öffentliche Prüfer: es wird nichts gespeichert

Ein anonymer Upload auf `/validator` (und auf `POST /api/v1/validate`) wird **im Arbeitsspeicher
geprüft und danach verworfen**. Es entsteht:

- keine Datei auf einem Datenträger,
- keine `report`-Zeile,
- kein `audit_event`,
- kein Protokolleintrag mit Dokumentinhalt.

**Warum das belastbar ist und nicht nur behauptet:** UI und API laufen durch **dieselbe** Methode,
`ReportService.validate(bytes, Optional.empty())`. Der leere `Optional` *ist* die Zusage — er bedeutet
„nichts schreiben" — und es gibt keinen zweiten Validierungspfad, in dem die Zusage später
auseinanderlaufen könnte. `PublicWebIT` prüft nach jedem anonymen Upload, dass die Zeilenzahl in
`report` unverändert ist.

Was in Protokollen landet, ist das, was jeder Webserver protokolliert: Zeitpunkt, Pfad, Statuscode und
die IP-Adresse des Aufrufers. Letztere wird zusätzlich für das Rate-Limit im Arbeitsspeicher gehalten
(Token-Bucket, max. 10.000 Adressen, älteste werden verdrängt) und nie persistiert.

Wer **angemeldet** ist, bekommt bewusst das Gegenteil: Prüfbericht und Audit-Eintrag werden
gespeichert, damit sie im Dashboard wiederauffindbar sind. Das ist der Unterschied zwischen einem
Werkzeug und einem Archiv, und er hängt allein daran, ob ein Credential mitgeschickt wurde.

## 2. Was gespeichert wird, wenn man ein Konto hat

| Tabelle | Inhalt | Zweck |
|---|---|---|
| `tenant` | Keycloak-`sub` (opake Kennung), Anzeigename | Mandantentrennung |
| `invoice` | die kanonische Rechnung als JSONB, plus extrahierte Spalten (Nummer, Datum, Beträge, Partei-Namen) | die vom Kunden selbst erzeugten Rechnungen |
| `report` | Prüfbefunde als JSONB, Format, Profil, Gültigkeit | Prüfhistorie |
| `api_key` | **nur der SHA-256-Hash** des Schlüssels, Präfix, Zeitstempel | Maschinenzugriff |
| `audit_event` | Mandant, Aktion, Zeitpunkt, **SHA-256 des Payloads** — nie der Payload selbst | Nachvollziehbarkeit (ENGINEERING_STANDARDS §4) |

Rechnungsdaten enthalten personenbezogene Daten (Namen, Anschriften, IBAN, UID) — es sind Rechnungen.
Sie liegen dort, weil der Kunde sie selbst angelegt hat, und nur in seinem Mandanten: jede Abfrage ist
mandantengebunden, und ein Fremdzugriff endet als identisches `404`, damit die Existenz einer Zeile
nicht durch den Statuscode verraten wird.

**XML wird nie gespeichert.** ebInterface- und UBL-Dokumente werden bei jedem Abruf aus dem
kanonischen JSON neu erzeugt.

## 3. Der KI-Assistent: was den Server verlässt

Standardmäßig **abgeschaltet** (`FEATURES_AI_EXPLANATIONS=false`). Ohne diesen Schalter existiert kein
Provider-Client im Prozess, es werden keine Schaltflächen angezeigt, und es verlässt nichts die
Plattform.

Ist er aktiviert und klickt jemand „Fehler erklären", geht **ein einzelner Prüfbefund** an den
konfigurierten Anbieter (Standard: OpenRouter, Modell konfigurierbar):

| Übermittelt | Nicht übermittelt |
|---|---|
| Regel-ID (z. B. `PEPPOL-EN16931-R010`) | **das geprüfte Dokument** — kein Auszug, keine Zeile |
| Schweregrad | Rechnungsbeträge, Positionen, Zahlungsdaten |
| Fundstelle (XPath) | Ihr Kontoname, Ihre Mandanten-ID, Ihre IP |
| Meldungstext (deutsch und englisch), **maskiert** | Ihr API-Schlüssel oder Token |

**Kein Dokumentauszug — und das ist eine Konsequenz, keine Auslassung.** SPEC §6 hatte
ursprünglich beschrieben, 40 Zeilen XML um die Fundstelle mitzusenden. Das ist nicht gebaut, weil es
nicht gebaut werden *kann*, ohne die Zusage aus Abschnitt 1 zu brechen: der öffentliche Prüfer behält
den Upload nicht, und gespeicherte Rechnungen halten kein XML. In dem Moment, in dem geklickt wird,
existiert kein Dokument, aus dem zitiert werden könnte. Es zu ermöglichen hieße, Uploads
aufzubewahren — ein schlechterer Tausch als eine etwas weniger spezifische Erklärung.

### 3.1 Was maskiert wird

Ein Schematron- oder XSD-Befund **zitiert den beanstandeten Wert wörtlich** — dafür ist er da. Der
Meldungstext enthält also routinemäßig personenbezogene Daten, auch ohne Dokumentversand. Vor jeder
Übermittlung werden ersetzt:

| Muster | Ersetzt durch |
|---|---|
| IBAN | `[IBAN]` |
| E-Mail-Adresse | `[E-MAIL]` |
| EU-USt-Identifikationsnummer (inkl. `ATU…`) | `[UID]` |
| Ziffernfolgen ab 7 Stellen (Kontonummern, Lieferantennummern, Telefonnummern) | `[NUMMER]` |
| Namen, die der Aufrufer als personenbezogen benennt (Verkäufer/Käufer einer gespeicherten Rechnung) | `[NAME]` |

Regel-IDs werden **nicht** maskiert — ein maskierter Regelbezeichner machte den Befund unerklärbar,
während es wie Datenschutzarbeit aussähe.

### 3.2 Zwei ehrlich benannte Grenzen

1. **Ein Personenname im Meldungstext bleibt unmaskiert, wenn der Aufrufer ihn nicht kennt.** Auf der
   öffentlichen Prüfer-Seite wird über den Upload nichts behalten, es gibt also keine Namensliste zum
   Abgleich — und kein regulärer Ausdruck kann „Bundesbeschaffung GmbH" von deutscher Prosa
   unterscheiden. Die strukturelle Absicherung ist, dass das Dokument die Plattform nie verlässt.
2. **Der Anbieter ist ein Dritter.** Was OpenRouter (bzw. das dahinterliegende Modell) mit einer
   Anfrage tut, unterliegt deren Bedingungen, nicht diesen. Wer das nicht will, lässt den Schalter aus
   oder richtet `AI_BASE_URL` auf einen selbst betriebenen, OpenAI-kompatiblen Endpunkt.

### 3.3 Zwischenspeicher

Erklärungen werden im Arbeitsspeicher zwischengespeichert (max. 500 Einträge, älteste werden
verdrängt), damit derselbe Befund keinen zweiten bezahlten Aufruf auslöst. Der Schlüssel ist die
Regel-ID plus ein SHA-256 des **bereits maskierten** Textes. Es wird nichts persistiert; ein Neustart
leert den Cache.

## 4. Ihre Rechte, und der Stand ihrer Umsetzung

- **Auskunft:** alle eigenen Daten sind über die API abrufbar (`GET /api/v1/invoices`,
  `GET /api/v1/reports`) bzw. im Dashboard.
- **Löschung einzelner Datensätze:** API-Schlüssel sind widerrufbar.
- **Vollständige Löschung des Mandanten:** **noch nicht implementiert.** Geplant als
  `DELETE /api/v1/tenant` mit einer „Danger Zone" im Dashboard; siehe `docs/worklog.md` (M5) für den
  offenen Punkt. Bis dahin ist die Löschung eine manuelle Betreiber-Aufgabe.
- **Aufbewahrungsfristen / automatische Löschung:** **noch nicht implementiert.** Prüfberichte und
  Audit-Einträge bleiben derzeit unbegrenzt liegen. Ebenfalls offen, ebenfalls im Worklog vermerkt.

Diese beiden Lücken werden hier ausdrücklich genannt, statt sie in einer Formulierung wie
„Löschkonzept vorhanden" zu verstecken. Solange sie offen sind, ist dieses Dokument keine ausreichende
Grundlage für einen produktiven Einsatz mit echten Kundendaten.

## 5. Selbst hosten ist die eigentliche Antwort

Die Plattform ist self-hostbar. Wer sie im eigenen Netz betreibt, verlässt keine Rechnungsdaten das
eigene Haus — außer, der KI-Schalter ist an, und auch dann nur der maskierte Befundtext aus
Abschnitt 3.

## 6. Verweise

- [ADR-0006](adr/0006-auth-and-api-security.md) — Auth, Mandantentrennung, „anonym speichert nichts"
- [ADR-0009](adr/0009-web-ui.md) — die Browser-Oberfläche und ihre Filterketten
- [ADR-0010](adr/0010-ai-assist.md) — KI-Feature: Port, Maskierung, Degradation, Cache
- SPEC §6, §8 — KI-Modul und Persistenz
