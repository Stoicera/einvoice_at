# Demo-Regiebuch — einvoice-at in zehn Minuten

Stand: 2026-10-13 · Zielgruppe: Geschäftsführung und IT-Leitung eines Unternehmens oder
Softwarehauses · Dauer: 10 Minuten Vorführung, danach Gespräch · Sprache: Deutsch, Sie-Form

Dieses Dokument ist öffentlich. Es enthält keine Zugangsdaten und keine Betriebsdetails, und es
verspricht nichts, was die Plattform nicht belegt. Die Zahlen in Abschnitt 3 tragen ihr Messdatum;
wer sie verwendet, verwendet auch das Datum. Die Betriebsmodelle und die Checkliste für die IT stehen
in [docs/enterprise.md](enterprise.md).

---

## 1. Vorbereitung (am Vortag, 20 Minuten)

### 1.1 Beispieldateien

Alle Dateien sind synthetisch — keine echten Firmen, keine echten Bankverbindungen
([Korpus-README](../validation/src/test/resources/corpus/README.md)). Erwartete Ergebnisse gemessen
am 2026-10-13 gegen die Live-Instanz.

| Datei im Repository | Was sie ist | Erwartetes Ergebnis |
|---|---|---|
| `samples/invoice-b2g-sample.ebinterface.xml` | Gültige ebInterface-6.1-Rechnung an eine Bundesdienststelle: Auftragsreferenz, Lieferantennummer, Biller-E-Mail, SEPA-Zahlung | „Diese Datei ist gültig." · `ebinterface-6.1` · Profil `at-b2g` · keine Befunde |
| `samples/invoice-b2g-sample.ubl.xml` | Dieselbe Rechnung als Peppol BIS Billing 3.0 (UBL 2.1) | gültig · `ubl-invoice-2.1` · Profil `peppol-bis-billing-3.0` · keine Befunde |
| `validation/src/test/resources/corpus/invalid/peppol-missing-endpoint-ids.xml` | Peppol-Rechnung ohne elektronische Adressen von Verkäufer und Erwerber — **die Kernszene** | 2 Fehler: `PEPPOL-EN16931-R020` (BT-34), `PEPPOL-EN16931-R010` (BT-49) |
| `validation/src/test/resources/corpus/invalid/at-b2g-01-missing-order-reference.xml` | ebInterface ohne Auftragsreferenz — die Pflichtangabe, die nur für den Bund gilt | 1 Fehler: `AT-B2G-01`, Fundstelle `/eb:Invoice` |
| `validation/src/test/resources/corpus/invalid/at-b2g-02-invalid-iban.xml` | IBAN mit falscher Prüfsumme (Reserve) | 1 Fehler: `AT-B2G-02`, Fundstelle `…/BeneficiaryAccount[1]/IBAN` — die IBAN selbst wird nicht wiederholt |
| `validation/src/test/resources/corpus/invalid/malformed.xml` | Kein wohlgeformtes XML (Reserve, falls jemand fragt, was mit Müll passiert) | 1 Fehler: `XML-01` |

Kopieren Sie die Dateien in einen Ordner `demo/` auf dem Vorführrechner und benennen Sie sie
sprechend, damit der Dateidialog lesbar bleibt — der Dateiname erscheint später im Bericht:

```
demo/01-gueltig-ebinterface.xml
demo/02-gueltig-peppol.xml
demo/03-peppol-ohne-adressen.xml
demo/04-ebinterface-ohne-auftragsreferenz.xml
demo/05-iban-pruefsumme.xml
demo/06-kein-xml.xml
```

### 1.2 Live-Instanz prüfen (fünf Minuten vor Beginn)

```bash
curl -fsS https://einvoice.sebastiankern.net/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}
curl -sS -o /dev/null -w '%{http_code}\n' https://einvoice.sebastiankern.net/validator        # 200
curl -sS -o /dev/null -w '%{http_code}\n' https://einvoice.sebastiankern.net/swagger-ui.html  # 200
curl -sS -X POST https://einvoice.sebastiankern.net/api/v1/validate -F file=@demo/03-peppol-ohne-adressen.xml
# {"id":null,"report":{"sourceFormat":"ubl-invoice-2.1","profile":"peppol-bis-billing-3.0",
#   "findings":[{"severity":"ERROR","ruleId":"PEPPOL-EN16931-R020", … }, … ],"valid":false}}
```

Antwortet eine Adresse nicht: zwei Minuten warten, erneut versuchen. Keine Demo vor 09:00 ansetzen.

### 1.3 Browser und Bildschirm

- Vier Tabs in dieser Reihenfolge: `/` (Startseite), `/validator`, `/swagger-ui.html`, `/app`
  (Dashboard). Im vierten Tab **vor** der Demo anmelden.
- Die Anmeldung findet nie auf dem geteilten Bildschirm statt. Zugangsdaten stehen im
  Passwortmanager und sonst nirgends — nicht in diesem Dokument, nicht in Notizen, nicht im Chat.
- Den Demo-Mandanten vorab befüllen: zwei bis drei Rechnungen über den Assistenten (Dashboard →
  „Rechnung erstellen"), damit Übersicht und Listen nicht leer sind. Nur synthetische Daten.
- Nur das Browserfenster teilen, nicht den Desktop. Benachrichtigungen aus. Browser-Zoom 125 %,
  damit Regel-IDs auf einem geteilten Bildschirm lesbar sind.
- Ein Terminal nur für die optionale Szene 2b: große Schrift, Verlauf geleert, keine Hostnamen
  außer der öffentlichen Adresse.

### 1.4 Grenzen, die man vorher kennt

- Der öffentliche Prüfer ist je IP-Adresse ratenbegrenzt (Standardwert 10 Prüfungen je Minute,
  `.env.example`). Die Demo braucht vier bis fünf Uploads; nicht hektisch wiederholen. Ein 429
  trägt `Retry-After`.
- Dateien bis 2 MB.
- Die KI-Erklärung („Fehler erklären") ist in der Produktion abgeschaltet (geprüft 2026-10-13: der
  Prüfbericht der Live-Instanz zeigt den Knopf nicht; Schalter `FEATURES_AI_EXPLANATIONS`, Standard
  `false` in `.env.example`). Sie wird erwähnt, nicht vorgeführt.

---

## 2. Ablauf

### 0:00–1:00 · Einstieg: das Problem

**URL:** `/` · **Klick:** keiner.

**Sprechtext:** „Der Bund nimmt nur strukturierte E-Rechnungen an — ebInterface oder Peppol. Wird
eine abgelehnt, kommt sie mit einer Schematron-Ausgabe zurück: englischer Regeltext, XPath,
Assertion-ID. Für die Buchhaltung ist das keine Hilfe, sondern eine Wand. Die Plattform, die Sie
gleich sehen, macht aus jedem dieser Befunde eine deutsche Klartextmeldung mit Regel-ID und
Fundstelle. Sie ist quelloffen — alles, was ich zeige, können Sie im Repository nachlesen."

**Der Zuschauer sieht:** Überschrift „E-Rechnung für Österreich: erzeugen, prüfen, verstehen.",
drei Kacheln Erzeugen · Prüfen · Konvertieren, darunter der Datenschutz-Absatz. In der Fußzeile:
„Prüfung und Erklärung sind technische Hilfestellungen und keine Rechts- oder Steuerberatung."

### 1:00–4:30 · Szene 1: Der öffentliche Prüfer mit einem kaputten Peppol-Dokument

**URL:** `/validator`

**Sprechtext (vor dem Klick):** „Kein Konto, kein Login. Der Datenschutz-Hinweis hier ist keine
Formel, sondern eine Eigenschaft: Die Datei wird im Arbeitsspeicher geprüft und danach verworfen —
kein Bericht, kein Protokolleintrag, keine Datei auf einem Datenträger. Oberfläche und API laufen
durch dieselbe Methode, und ein Test prüft nach jedem anonymen Upload, dass die Zahl der
gespeicherten Berichte unverändert ist."

**Klick 1:** „Datei auswählen" → `03-peppol-ohne-adressen.xml` → „Rechnung prüfen".

**Der Zuschauer sieht (nach ein bis zwei Sekunden):** Ein roter Balken „Diese Datei ist nicht
gültig." mit „2 Fehler", daneben der Dateiname · `ubl-invoice-2.1` · Profil
`peppol-bis-billing-3.0`. Darunter der Abschnitt „Fehler — diese verhindern die Annahme" mit zwei
Karten:

- `PEPPOL-EN16931-R020` — „Peppol BIS Billing 3.0: Die elektronische Adresse des Verkäufers (BT-34)
  muss angegeben werden." Darunter, kleiner, das Original
  „[PEPPOL-EN16931-R020]-Seller electronic address MUST be provided" und die Fundstelle.
- `PEPPOL-EN16931-R010` — dasselbe für den Erwerber (BT-49).

**Sprechtext (am Befund):** „Das ist das ganze Produktversprechen in einer Zeile. Links die
Regel-ID des offiziellen OpenPeppol-Regelwerks — die können Sie direkt nachschlagen. Rechts der
deutsche Satz, den eine Sachbearbeiterin versteht. Darunter das englische Original, damit nichts
verloren geht. Das Regelwerk führen wir unverändert aus, an einer im Code festgelegten Version:
2026.5, seit 17. August 2026 verbindlich. Umgestellt haben wir am 7. August."

**Klick 2:** `01-gueltig-ebinterface.xml` → „Rechnung prüfen".

**Der Zuschauer sieht:** „Diese Datei ist gültig." · `ebinterface-6.1` · Profil `at-b2g` · „Keine
Befunde. Das Dokument erfüllt Schema und Regelwerk vollständig."

**Klick 3 (wenn Zeit bleibt):** `04-ebinterface-ohne-auftragsreferenz.xml`.

**Der Zuschauer sieht:** 1 Fehler `AT-B2G-01` — „Auftragsreferenz fehlt: Rechnungen an
Bundesdienststellen müssen eine Auftragsreferenz (OrderReference/OrderID) enthalten." Fundstelle
`/eb:Invoice`.

**Sprechtext:** „Diese Regel gilt nur für Rechnungen an den Bund. Ein System, das ‚Peppol kann',
ist damit noch nicht bund-tauglich. Für ebInterface gibt es kein offizielles Schematron — diese
Regeln haben wir geschrieben, und sie liegen offen im Repository, damit Sie sie prüfen können."

### 4:30–7:00 · Szene 2: Die API-Dokumentation — ein Endpunkt ohne Schloss

**URL:** `/swagger-ui.html`

**Der Zuschauer sieht:** Titel „einvoice-at API", im Feld *Servers* die Live-Adresse, darunter
15 Operationen auf 13 Pfaden (gezählt 2026-10-13 aus `/v3/api-docs`). Rechts an jeder Zeile ein
Schloss-Symbol — außer bei `POST /api/v1/validate`.

**Sprechtext:** „Für ein Softwarehaus ist das der eigentliche Zugang: nicht unsere Oberfläche,
sondern unsere Prüfung im eigenen Produkt. Das Schloss fehlt genau einmal — bei der anonymen
Prüfung, die nichts speichert. Alles andere braucht genau ein Credential: ein OAuth2-Token für
Menschen oder einen API-Schlüssel für Maschinen. Wer beides mitschickt, bekommt eine Fehlermeldung
statt einer stillschweigenden Auswahl."

**Klick 1:** `POST /api/v1/validate` aufklappen.

**Der Zuschauer sieht:** Beschreibung, Parameter `file`, vier dokumentierte Antworten: 200 (der
Bericht), 400, 413 (über 2 MB), 429 (Rate-Limit) — Fehler als `application/problem+json`.

**Klick 2:** „Authorize" oben rechts öffnen, kurz zeigen, schließen.

**Der Zuschauer sieht:** Zwei Verfahren, `bearerAuth` und `apiKeyAuth` (`X-Api-Key`).

**Sprechtext:** „Fehler kommen nach RFC 9457 zurück, mit einem stabilen Typ, der auf die
Dokumentation zeigt. Rechnung anlegen und als ebInterface, UBL oder PDF abrufen; zwischen den
Formaten konvertieren — mit einem Bericht darüber, was das Zielformat nicht tragen kann. Alles
über diese Schnittstelle."

**Szene 2b, optional (Terminal, 30 Sekunden):**

```bash
curl -sS -X POST https://einvoice.sebastiankern.net/api/v1/validate -F file=@demo/01-gueltig-ebinterface.xml
# {"id":null,"report":{"sourceFormat":"ebinterface-6.1","profile":"at-b2g","findings":[],"valid":true}}
```

**Sprechtext:** „`id: null` — nichts wurde gespeichert. Das ist dieselbe Antwort, die Ihr
Fakturierungssystem vor dem Versand bekäme."

### 7:00–9:00 · Szene 3: Das Dashboard nach der Anmeldung

Vorab angemeldet (Abschnitt 1.3). Der Anmeldevorgang wird nicht gezeigt, die Anmeldeadresse nicht
genannt.

**URL:** `/app`

**Der Zuschauer sieht:** Überschrift „Dashboard", Reiter Übersicht · Rechnungen · Prüfberichte ·
API-Schlüssel · Konto. Drei Kacheln: Rechnungen, Prüfberichte, aktive API-Schlüssel. Schaltflächen
„Rechnung erstellen" und „Datei prüfen". Tabellen „Letzte Rechnungen" (Nummer, Datum, Empfänger,
Betrag, Prüfung) und „Letzte Prüfberichte" (Zeitpunkt, Format, Profil, Herkunft, Ergebnis).

**Sprechtext:** „Wer angemeldet ist, bekommt bewusst das Gegenteil des öffentlichen Prüfers:
Berichte bleiben, damit man sie wiederfindet. Das ist der Unterschied zwischen Werkzeug und
Archiv, und er hängt allein daran, ob ein Credential mitgeschickt wurde."

**Klick 1:** eine Rechnung unter „Letzte Rechnungen" öffnen.

**Der Zuschauer sieht:** Prüfergebnis, Berichtshistorie, die strukturierten Rechnungsdaten, drei
Downloads: ebInterface · UBL · PDF.

**Klick 2:** „PDF" — mit Strg+Klick in einem neuen Tab öffnen, damit das Dashboard offen bleibt.

**Der Zuschauer sieht:** Eine deutsche A4-Druckansicht — Absender, Empfänger, Positionen, die
USt-Aufschlüsselung je Satz als eigene Tabelle, Summen, Zahlungsdaten.

**Sprechtext:** „PDF, ebInterface und UBL sind drei Ansichten desselben Modells. Sie können sich
nicht widersprechen, weil es nur eine Quelle gibt. XML speichern wir nie — es wird bei jedem Abruf
neu erzeugt."

**Klick 3:** Reiter „API-Schlüssel" — nur zeigen, keinen Schlüssel erzeugen.

**Sprechtext:** „Hier entstehen die Schlüssel für die Integration. Der Klartext wird genau einmal
angezeigt, gespeichert wird nur ein Hash. Ein Schlüssel kann weder andere Schlüssel verwalten noch
das Konto löschen."

**Klick 4:** Reiter „Konto" — nur zeigen, die Löschung nicht ausführen.

**Der Zuschauer sieht:** „Gespeicherte Daten" mit der Anzahl je Kategorie; darunter „Konto und
alle Daten löschen" mit Bestätigungsfeld.

**Sprechtext:** „Auskunft und Löschung nach DSGVO ohne Umweg über uns: Ein Vorgang löscht
Rechnungen, Berichte, Schlüssel und Protokoll in einer Transaktion. Das Gegenteil ist genauso
Absicht: Der nächtliche Aufbewahrungs-Job räumt Berichte und Protokolle nach Frist weg, aber nie
Rechnungen — § 132 BAO verpflichtet Sie zu sieben Jahren."

### 9:00–10:00 · Abschluss und nächster Schritt

**Sprechtext:** „Drei Wege, das einzusetzen: selbst betreiben — Docker Compose, Apache-2.0, Ihre
Daten bleiben im Haus. Von uns betrieben, auf EU-Infrastruktur. Oder nur die Schnittstelle in
Ihrer Fakturierung. Die Details stehen in einem Dokument, das ich Ihnen nach dem Gespräch
schicke. Mein Vorschlag für den nächsten Schritt: Sie schicken mir nichts. Sie prüfen drei
Rechnungen Ihres Hauses selbst, heute, mit dem öffentlichen Prüfer — nichts davon wird
gespeichert. Danach reden wir über das, was der Bericht zeigt."

**Frage zum Schluss:** „Welche drei Rechnungen würden Sie als Erstes prüfen?"

**Nach der Demo:** Link auf `/validator` und auf [docs/enterprise.md](enterprise.md) schicken.
Offene Fragen werktags innerhalb von vier Stunden beantworten.

---

## 3. Zahlen-Spickzettel

Jede Zahl mit Messdatum. Was nicht gemessen ist, wird nicht gesagt.

| Aussage | Wert | Gemessen | Quelle |
|---|---|---|---|
| Automatisierte Tests | 1 095, 0 Fehler (881 Unit, 214 Integration gegen echte PostgreSQL und Keycloak); dazu 5 Browser-E2E-Tests | 2026-10-13 CI (Lauf 38353291491, Code identisch mit `main`) | Summe der `Tests run:`-Zeilen je Modul |
| Antwortzeit der Live-Instanz, `POST /api/v1/validate` von außen | 0,2–1,8 s je Anfrage, drei Messungen inkl. Verbindungsaufbau | 2026-10-13 | `curl -w '%{time_total}'` gegen die Live-Instanz |
| Peppol-Regelsatz | 2026.5 — umgestellt 2026-08-07, verbindlich seit 2026-08-17 | 2026-10-01 | `PeppolValidationStage.RULE_SET_VERSION` |
| Deutsche Übersetzungen der Peppol-Regeln | 79 | 2026-10-01 | `PeppolMessagesDe`, per Test festgenagelt |
| Golden-File-Korpus | 6 gültige, 10 gezielt kaputte Dokumente | 2026-10-01 | `validation/src/test/resources/corpus/` |
| Zeilenabdeckung `core` | 99,60 % Zeilen, 98,33 % Zweige (Build-Gate 95/90) | 2026-10-01 | JaCoCo-CSV eines vollständigen `./mvnw verify` |
| Lighthouse `/` und `/validator` | gemessen 100/100/100/100; der Build lässt nichts unter 95 durch | 2026-09-30 CI | Lighthouse 12.8.2, Desktop |
| Antwortzeit `POST /api/v1/validate` | p95 30 ms je Prüfanfrage, 50 Anfragen, alle erfolgreich — im Lasttest des Builds auf einem GitHub-Runner, keine Aussage über die Produktion | 2026-09-30 CI | Gatling `ValidatorSimulation` |
| Quickstart | 239 s von `docker compose build --no-cache` bis `"status":"UP"` | 2026-10-01 | frischer Klon |
| OpenAPI | 15 Operationen auf 13 Pfaden, OpenAPI 3.1.0 | 2026-10-13 | `GET /v3/api-docs` der Live-Instanz |
| Sicherheits-Header der Live-Instanz | Content-Security-Policy, HSTS (ein Jahr, inkl. Subdomains), `nosniff`, `X-Frame-Options`, Referrer-Policy, Permissions-Policy | 2026-10-13 | `curl -I /validator` |
| Sicherungen (von Stoicera betriebene Instanz) | Jede Nacht ein Dump, der zurückgelesen wird, bevor er als erfolgreich gilt; die Kopie auf einen zweiten Server wird von dort zurückgelesen; 9 von 9 Läufen 23.09.–01.10.2026 erfolgreich | 2026-10-01 | Backup-Protokoll des Produktionsservers |
| Abhängigkeits-Scan | OWASP Dependency-Check als Build-Gate ab CVSS 7,0; auf `main` grün | 2026-10-08 CI (Lauf 37805734730) | `.github/workflows/ci.yml` |
| Releases | v0.1.0 (2026-08-06), v0.2.0 (2026-10-09); Live-Instanz seit 2026-08-06 | 2026-10-13 | GitHub Releases |
| Architekturentscheidungen, Module | 12 ADRs, 10 Maven-Module | 2026-10-01 | `docs/adr/`, `pom.xml` |

**Was nicht gesagt wird:** Nutzer- oder Kundenzahlen — es gibt keine Kundeninstallation, das
Projekt ist ein öffentliches Referenzprojekt, und das sagen wir, bevor jemand fragt. Keine
Aufwände, keine Tagsätze, kein „zertifiziert", keine Namen anderer Anbieter.

---

## 4. Fragen und Antworten

Zwölf Einwände, die in Gesprächen kommen, und die Antwort, die hält.

**1. „Ist das zertifiziert oder vom Bund zugelassen?"**
Nein, und wir sagen das so. Für Peppol führen wir das offizielle OpenPeppol-Regelwerk unverändert
aus, an einer im Code festgelegten Version — das ist belegbar und ist die ganze Aussage. Für
ebInterface gibt es kein offizielles Schematron; unsere AT-B2G-Regeln sind im Repository offen.
Unsere ebInterface-Beispielrechnung haben wir zusätzlich durch den öffentlichen
ebInterface-Prüfdienst laufen lassen, Ergebnis „gültig" (2026-07-25,
[samples/README.md](../samples/README.md)). Das ist eine Stichprobe, keine Zulassung.

**2. „Können Sie die Rechnung auch an den Bund oder ins Peppol-Netz senden?"**
Nein. Die Plattform ist kein Peppol Access Point und lädt nichts auf e-rechnung.gv.at hoch. Sie
erzeugt und prüft; der Versand läuft über Ihren bestehenden Weg — das Upload-Portal oder Ihren
Access-Point-Anbieter. Die Architektur sieht einen Übergabepunkt vor, mehr nicht.

**3. „Wo liegen unsere Daten?"**
Selbst gehostet: bei Ihnen, und sie verlassen Ihr Haus nicht. Von uns betrieben: auf
EU-Infrastruktur bei Hetzner. Der öffentliche Prüfer speichert nichts. Angemeldete Mandanten haben
fünf Tabellen, und keine davon enthält XML. Details in [docs/privacy.md](privacy.md).

**4. „Gehen Rechnungsdaten an ein KI-Modell?"**
Standardmäßig nicht — der Schalter ist aus, und ohne ihn existiert kein Anbieter-Client im Prozess.
Ist er an, geht ein einzelner Befund, maskiert (IBAN, UID, E-Mail, lange Ziffernfolgen), nie das
Dokument. Wer das nicht will, lässt den Schalter aus oder richtet einen selbst betriebenen
Endpunkt ein.

**5. „Wir haben ein eigenes ERP- oder Fakturierungssystem. Passt das?"**
Dafür ist die Schnittstelle da: OpenAPI 3.1, API-Schlüssel, ein HTTP-Aufruf je Prüfung. Die
Antwort enthält je Befund Regel-ID, deutsche Meldung und Fundstelle — das lässt sich direkt in Ihr
System übernehmen. Der Weg steht in [docs/enterprise.md](enterprise.md), Abschnitt
„Integrationspfad".

**6. „Was passiert, wenn sich das Regelwerk ändert?"**
Dann meldet sich der Build, nicht ein Kalender. Ein Test liest die datierten Regelsätze, die
OpenPeppol veröffentlicht, und schlägt an dem Tag fehl, an dem ein neuer den festgelegten überholt —
mit Versionsnummer und Stichtag in der Fehlermeldung. Auf 2026.5 haben wir zehn Tage vor dem
Stichtag umgestellt. ebInterface 7.0 beobachten wir; versprochen ist dazu nichts.

**7. „Garantieren Sie, dass der Bund die Rechnung annimmt?"**
Nein. Das ist eine technische Prüfung gegen veröffentlichte Regeln, keine Rechts- oder
Steuerberatung — die Fußzeile der Plattform sagt das selbst. Über die Annahme entscheidet der
Empfänger. Was wir sagen können: Ein Befund hier benennt eine Regel, die OpenPeppol veröffentlicht
hat, oder eine Pflichtangabe, die der Bund für seine Rechnungen nennt — Sie wissen vorher, wo das
Dokument angreifbar ist, und warum.

**8. „Wer betreibt das, und wie schnell antworten Sie?"**
Die Stoicera Software Group, zwei Gründer in Oberösterreich. Servicezeiten werktags 09–17 Uhr,
Antwort innerhalb von vier Stunden. Kein 24/7, kein Bereitschaftsdienst — das sagen wir vorher,
nicht hinterher.

**9. „Was ist mit Sicherung und Ausfall?"**
Bei der von uns betriebenen Instanz: jede Nacht ein Dump, der zurückgelesen wird, bevor er als
erfolgreich gilt; die Wiederherstellung ist geprobt und läuft als Test in jedem Build. Die
Sicherungen werden jede Nacht auf einen zweiten Server kopiert und von dort zurückgelesen. Es ist
eine Einzelinstanz — Hochverfügbarkeit versprechen wir nicht.

**10. „Es ist Open Source. Was hindert uns, es selbst zu betreiben?"**
Nichts. Apache-2.0, Docker Compose, ein Deployment-Leitfaden mit Prüfbefehl je Schritt. Das ist
Betriebsmodell A. Wir verkaufen keine Lizenz, sondern Betrieb, Integration und Antwortzeit.

**11. „Wer nutzt das schon?"**
Niemand außer uns. Es ist ein öffentliches Referenzprojekt, live seit 6. August 2026. Wir sagen das,
bevor Sie es herausfinden — und genau deshalb ist alles einsehbar: Code, Tests, Entscheidungen, auch
die Befundlisten unserer eigenen Reviews.

**12. „Was kostet das?"**
Den Preis nennt das Angebot nach dem Erstgespräch, nicht die Demo. In der Demo wird keine Zahl
genannt.

---

## 5. Was wir nicht zeigen und nicht versprechen

**Nicht auf dem Bildschirm:**

- Den Anmeldevorgang, die Anmeldeadresse, Zugangsdaten, Deployment-Panel, Admin-Oberflächen, ein
  Terminal mit Hostnamen oder Pfaden.
- Einen API-Schlüssel im Klartext.
- Die Konto-Löschung — sie wird gezeigt, nicht ausgeführt.
- Die KI-Erklärung — in der Produktion abgeschaltet; wird erwähnt, nicht vorgeführt.
- Grafana und Traces — das Observability-Profil ist nur für den eigenen Rechner gedacht.
- Echte Rechnungen des Kunden auf unserem Bildschirm. Der Kunde prüft sie selbst, auf seinem.

**Nicht in Worten:**

- Versand, Peppol Access Point, Anbindung an e-rechnung.gv.at, Rechnungseingang, Buchhaltung,
  Steuer ([PRD §5](PRD.md)).
- ebInterface 7.0 als Zusage. Es wird beobachtet.
- „Zertifiziert", „offiziell zugelassen", „vom Bund geprüft", „NIS2-konform", „24/7",
  Hochverfügbarkeit.
- Nutzerzahlen, Kunden, Aufwände, Tagsätze, Preise.
- Namen anderer Anbieter oder Vergleiche, die sie herabsetzen.
- Rechts- oder Steuerberatung in jeder Form.
