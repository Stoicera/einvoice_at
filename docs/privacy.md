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
| IBAN (Groß- und Kleinschreibung) | `[IBAN]` |
| E-Mail-Adresse | `[E-MAIL]` |
| EU-USt-Identifikationsnummer (inkl. `ATU…`, Groß- und Kleinschreibung) | `[UID]` |
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
verdrängt), damit derselbe Befund keinen zweiten bezahlten Aufruf auslöst. Zusätzlich sind alle
Erklärungs-Routen **ratenbegrenzt** — die öffentliche über dasselbe anonyme Kontingent wie der
Prüfer-Upload, die angemeldeten pro Credential (`RATE_LIMIT_EXPLAIN_*`). Das begrenzt nicht nur
Kosten, sondern auch, wie viel Text pro Zeiteinheit überhaupt an den Anbieter gehen kann. Der Schlüssel ist die
Regel-ID plus ein SHA-256 des **bereits maskierten** Textes. Es wird nichts persistiert; ein Neustart
leert den Cache.

## 4. Ihre Rechte, und der Stand ihrer Umsetzung

- **Auskunft (Art. 15):** alle eigenen Daten sind über die API abrufbar (`GET /api/v1/invoices`,
  `GET /api/v1/reports`) bzw. im Dashboard. Die Seite **Konto** nennt zusätzlich die Anzahl der
  gespeicherten Datensätze je Kategorie — bevor sie das Löschen anbietet.
- **Löschung einzelner Datensätze:** API-Schlüssel sind widerrufbar (die Zeile bleibt, mit
  Widerrufs-Zeitstempel, zur Nachvollziehbarkeit).
- **Vollständige Löschung des Mandanten (Art. 17):** **implementiert.** Im Dashboard unter
  **Konto → „Konto und alle Daten löschen"** (Bestätigung durch Eintippen des Wortes `LÖSCHEN`), über
  die API als `DELETE /api/v1/tenant` — **dort nur mit einem Login (OAuth2/JWT), nicht mit einem
  API-Schlüssel** (ADR-0011 Entscheidung 6): ein langlebiger Maschinenschlüssel soll die
  unwiderruflichste Aktion der Plattform nicht auslösen können. Gelöscht werden in einer Transaktion: Rechnungen, Prüfberichte,
  API-Schlüssel (auch widerrufene), **sämtliche Protokoll-Einträge** und die Mandanten-Zeile selbst mit
  Keycloak-Subject und Anzeigename. Es bleibt genau eine Log-Zeile mit der Mandanten-UUID — einem von
  dieser Plattform erzeugten Surrogat, keinem Personenbezug — und den gelöschten Zeilenzahlen.
  Idempotent; ein verwendeter API-Schlüssel wird durch den Aufruf selbst ungültig. Kein Backup, aus
  dem sich das zurückholen ließe.
- **Aufbewahrungsfristen / automatische Löschung (Art. 5 Abs. 1 lit. e):** **implementiert.** Ein
  nächtlicher Job löscht Prüfberichte nach `RETENTION_REPORT_DAYS` (Standard 365) und Protokoll-Einträge
  nach `RETENTION_AUDIT_DAYS` (Standard 730). `0` oder negativ heißt „unbegrenzt aufbewahren" — das ist
  der Ausschalter.

**Rechnungen werden vom Aufbewahrungs-Job niemals gelöscht, und das ist Absicht.** § 132 BAO
verpflichtet Sie, Rechnungen **sieben Jahre** aufzubewahren. Eine Plattform, die sie nach einem Jahr
automatisch wegräumt, schützt nicht Ihre Privatsphäre, sondern zerstört Aufzeichnungen, zu deren
Aufbewahrung Sie verpflichtet sind — ungefragt und in Ihrem Namen. Rechnungen verschwinden deshalb nur
auf ausdrückliches Verlangen (Art. 17, oben). Löschung auf Verlangen und Ablauf einer Frist sind zwei
verschiedene Vorgänge mit verschiedenen Regeln, und nur der erste darf diese Tabelle anfassen.

Beide Lücken, die dieses Dokument von M3 bis M5 ausdrücklich als offen benannt hat — statt sie hinter
einer Formulierung wie „Löschkonzept vorhanden" zu verstecken —, sind damit geschlossen. Der Satz, dass
diese Plattform keine ausreichende Grundlage für echte Kundendaten sei, steht hier nicht mehr.

## 4a. Telemetrie: was die Plattform über sich selbst hinausschickt (M6)

Seit M6 kann die Plattform **Traces und Metriken** an ein Observability-Backend schicken. Das ist ein
Datenfluss nach außen und gehört deshalb in dieses Dokument, auch wenn er keine Rechnungsdaten
enthält.

- **Standardmäßig aus.** `OTEL_ENABLED` ist `false`; ohne diesen Schalter verlässt kein Byte
  Telemetrie den Prozess. Das ist kein Nebeneffekt, sondern korrigiert Spring Boots eigene Defaults,
  die beide Exporter eingeschaltet hätten.
- **Was in einem Span steht:** der Name der Operation (`einvoice.validation.stage.xsd`,
  `http post /api/v1/invoices`), die Dauer, und bei einem Fehler dessen Typ. Die Route erscheint als
  **Muster** (`/api/v1/invoices/{id}`), nicht mit eingesetzter Id.
- **Was in einer Metrik steht:** Anzahl und Dauer je Stufe bzw. Schritt. Die Tag-Werte stammen
  ausschließlich aus Konstanten und einem Enum dieses Repositories — genau damit dort kein
  Dokumentwert, keine Mandanten-Id und keine Eingabe eines Aufrufers landen kann.
- **Was ausdrücklich nicht mitgeht:** kein Rechnungsinhalt, kein Befundtext, keine IBAN, keine UID,
  keine E-Mail-Adresse, keine Mandanten-Id, kein Credential.
- **Wohin:** an das OTLP-Ziel, das *Sie* konfigurieren. Das mitgelieferte Compose-Profil hält alles
  auf dem eigenen Rechner (Prometheus, Tempo, Grafana, alle nur an `127.0.0.1`); eine Installation,
  die ein externes Backend wählt, trifft diese Entscheidung selbst — und sollte prüfen, wo dessen
  Server stehen.
- **Protokollzeilen** tragen unter dem Profil `prod` zusätzlich `traceId`/`spanId`. Der Inhalt der
  Logzeilen ändert sich dadurch nicht; es gilt weiter, dass kein Credential jemals vollständig
  protokolliert wird.

## 5. Selbst hosten ist die eigentliche Antwort

Die Plattform ist self-hostbar. Wer sie im eigenen Netz betreibt, verlässt keine Rechnungsdaten das
eigene Haus — außer, der KI-Schalter ist an, und auch dann nur der maskierte Befundtext aus
Abschnitt 3.

## 6. Verweise

- [ADR-0006](adr/0006-auth-and-api-security.md) — Auth, Mandantentrennung, „anonym speichert nichts"
- [ADR-0009](adr/0009-web-ui.md) — die Browser-Oberfläche und ihre Filterketten
- [ADR-0010](adr/0010-ai-assist.md) — KI-Feature: Port, Maskierung, Degradation, Cache
- SPEC §6, §8 — KI-Modul und Persistenz
