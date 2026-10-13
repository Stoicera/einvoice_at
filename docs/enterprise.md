# einvoice-at im Unternehmen

Stand: 2026-10-13 · Für Geschäftsführung und IT-Leitung von Unternehmen und Softwarehäusern ·
Dieses Dokument nennt keine Preise; die stehen im Angebot. Es ist eine technische Beschreibung,
keine Rechts- oder Steuerberatung.

einvoice-at erzeugt, prüft und konvertiert österreichische E-Rechnungen — ebInterface 6.1 und
Peppol BIS Billing 3.0 — und sagt bei jedem Befund auf Deutsch, welche Regel verletzt ist und wo
im Dokument. Die Plattform ist quelloffen (Apache-2.0), läuft in der EU und speichert anonyme
Uploads nicht. Was sie **nicht** tut, steht in Abschnitt 4 — bitte zuerst lesen, wenn Sie
Versand oder Rechnungseingang suchen.

---

## 1. Drei Betriebsmodelle

Die drei Modelle schließen einander nicht aus: Die Schnittstelle (C) gibt es in A und in B.

### A — Selbst gehostet, bei Ihnen

Sie betreiben die Plattform im eigenen Netz, per Docker Compose. Rechnungsdaten verlassen Ihr Haus
nicht ([docs/privacy.md §5](privacy.md)).

| | |
|---|---|
| Sie bekommen | Das Container-Image (`ghcr.io/stoicera/einvoice_at`, jede Version zusätzlich als unveränderliches `sha-<commit>`-Tag), die Compose-Datei, den [Deployment-Leitfaden](deployment.md) mit einem Prüfbefehl je Schritt und die [Betriebsreferenz](deployment-reference.md), die Sicherungs- und Wiederherstellungsskripte |
| Sie brauchen | Docker mit Compose, einen Reverse Proxy mit TLS davor, rund 2 GB RAM über dem Grundbedarf des Hosts und 15 GB freie Platte (`docs/deployment.md` §1, Stand 2026-10-13), Keycloak als Identitätsanbieter — er ist Teil des Stacks |
| Updates | Sie ziehen neue Image-Tags, wenn Sie es entscheiden; [CHANGELOG](../CHANGELOG.md) und GitHub-Releases nennen, was sich ändert |
| Von Stoicera, nach Vereinbarung | Einrichtung, Integration, Rückfragen in den Servicezeiten (Abschnitt 2.5) |

Ein anderer OIDC-Anbieter als Keycloak ist nicht erprobt und wird nicht zugesagt.

### B — Von Stoicera betrieben, auf EU-Infrastruktur

Eine eigene Instanz je Kunde — eigener Hostname, eigene Datenbank, eigener Keycloak-Realm — auf
Hetzner Cloud in der EU, so wie die öffentliche Instanz unter
[einvoice.sebastiankern.net](https://einvoice.sebastiankern.net) seit 2026-08-06 läuft.

| | |
|---|---|
| Enthalten | TLS und Sicherheits-Header (Abschnitt 2.1), Updates und Rollback (2.4), nächtliche Sicherung mit Rücklesung (2.3), Überwachung des Health-Endpunkts, Servicezeiten (2.5) |
| Vertraglich | Ein Vertrag zur Auftragsverarbeitung (Art. 28 DSGVO) gehört zu diesem Modell, weil Rechnungsdaten personenbezogene Daten enthalten |
| Bewusst nicht enthalten | Hochverfügbarkeit, 24/7-Bereitschaft, ein zweiter Standort für die Sicherungen (2.3), Versand der Rechnungen (Abschnitt 4) |

### C — Integration über die REST-API in Ihre Fakturierung oder Ihr ERP

Ihr System ruft die Prüfung — und auf Wunsch Erzeugung und Konvertierung — über HTTP auf; die
Oberfläche von einvoice-at sehen Ihre Mitarbeiterinnen und Mitarbeiter nie. Beschreibung:
OpenAPI 3.1 unter `/v3/api-docs`, bedienbar unter `/swagger-ui.html` (auf der öffentlichen
Instanz offen; abschaltbar).

| Zweck | Endpunkt | Credential |
|---|---|---|
| Prüfen | `POST /api/v1/validate` (multipart `file`, Format wird erkannt) | keines nötig — anonym wird nichts gespeichert; mit Credential wird der Bericht im Mandanten abgelegt |
| Rechnung anlegen und ausgeben | `POST /api/v1/invoices`, dann `GET /api/v1/invoices/{id}/ebinterface` · `/ubl` · `/pdf` | API-Schlüssel oder OAuth2-Token |
| Konvertieren mit Verlustbericht | `POST /api/v1/convert?from=&to=` | API-Schlüssel oder OAuth2-Token |
| Berichte abrufen | `GET /api/v1/reports`, `GET /api/v1/reports/{id}` | API-Schlüssel oder OAuth2-Token |
| Schlüssel verwalten, Mandant löschen | `/api/v1/api-keys`, `DELETE /api/v1/tenant` | nur OAuth2-Token — ein Maschinenschlüssel darf das nicht |

Der Vertrag ist stabil beschrieben: Jede Antwort auf eine Prüfung enthält je Befund `ruleId`,
`severity`, `messageDe`, `messageEn` und `location`. Fehler kommen als `application/problem+json`
(RFC 9457) mit einem festen `type`, dessen Slug in [docs/problems.md](problems.md) steht. Genau ein
Credential je Anfrage; zwei zugleich werden mit 400 abgelehnt. Anfragen sind auf 2 MB begrenzt
(413), die Konvertierung ist je Credential ratenbegrenzt (429 mit `Retry-After`). Ein Mandant hält
höchstens 25 aktive Schlüssel; widerrufene bleiben für die Nachvollziehbarkeit erhalten.

---

## 2. Was dazugehört

Hier steht nur, was belegt ist — durch einen Test, eine Messung oder den Code. Was nicht belegt
ist, wird nicht behauptet.

### 2.1 Sicherheit

Gemessen an der öffentlichen Instanz am 2026-10-13, sofern nicht anders angegeben. Die Zuordnung
Bedrohung → Kontrolle → Test steht in [SECURITY.md](../SECURITY.md).

- **Transport und Header.** HTTPS mit HSTS (ein Jahr, inkl. Subdomains), Content-Security-Policy
  (`default-src 'none'`, nur eigene Skripte und Stile, `frame-ancestors 'none'`),
  `X-Content-Type-Options: nosniff`, `X-Frame-Options`, `Referrer-Policy`, `Permissions-Policy`
  (Kamera, Mikrofon, Standort aus).
- **Anmeldung.** Keycloak als Identitätsanbieter, OIDC Authorization Code mit PKCE für den Browser;
  die Anwendung speichert keine Passwörter. Für die API ein signiertes JWT (Signatur, Aussteller und
  optional Audience werden geprüft; `alg=none`, abgelaufene Token und fremde Schlüssel werden je mit
  eigenem Test abgewiesen) oder ein API-Schlüssel.
- **API-Schlüssel.** Aus `SecureRandom`, gespeichert nur als SHA-256-Hash, Klartext genau einmal
  sichtbar. Ein Schlüssel kann keine Schlüssel verwalten und den Mandanten nicht löschen.
- **Rate-Limits.** Anonyme Prüfung je IP-Adresse, Konvertierung und KI-Erklärung je Credential; alle
  antworten 429 mit `Retry-After`. Hinter einem Reverse Proxy liest die Anwendung die
  Weiterleitungskette vom vertrauenswürdigen Ende, sodass eine gefälschte Adresse keinen zweiten
  Zähler kauft (drei Tests, je Richtung).
- **Upload nie auf Platte.** Der anonyme Upload wird im Arbeitsspeicher geprüft und verworfen —
  kein Bericht, kein Protokolleintrag, keine temporäre Datei. Ein Test prüft nach jedem anonymen
  Upload, dass die Berichtstabelle unverändert ist; die Schwelle für temporäre Dateien liegt über
  der Maximalgröße ([CHANGELOG 0.2.0](../CHANGELOG.md)).
- **Parsen.** Jedes Dokument wird genau einmal durch einen XXE-gehärteten Parser gelesen, der ein
  Dokument schon für die Deklaration eines `DOCTYPE` abweist. 2 MB Grenze je Anfrage, 20 MB
  Schutzgrenze im Prüfmodul selbst.
- **Mandantentrennung.** Jede Abfrage ist mandantengebunden; eine fremde Ressource ist von einer
  nicht existierenden nicht zu unterscheiden (404). Getestet für Rechnungen, Berichte und Schlüssel.
- **Nachvollziehbarkeit.** Append-only-Protokoll je Erzeugung, Prüfung und Konvertierung sowie je
  Anlage und Widerruf eines API-Schlüssels — mit dem SHA-256 des Payloads, nie dem Payload.
- **Lieferkette.** OWASP Dependency-Check als Build-Gate ab CVSS 7,0 (auf `main` grün, 2026-10-08),
  Dependabot für Maven, Actions und Docker, CodeQL; Container-Images per Tag und Digest, Actions per
  Commit-SHA festgelegt.

**Bewusst nicht behauptet:** kein Penetrationstest durch Dritte, keine Zertifizierung, keine
Aussage zu NIS2. Der Rate-Limiter ist je Instanz, nicht verteilt. Eine Löschung nach Art. 17 nimmt
das Protokoll des Mandanten mit — die Alternative wäre, die Löschung nicht zu erfüllen.

### 2.2 Datenschutz

Vollständig in [docs/privacy.md](privacy.md). Die Kurzfassung:

- Der öffentliche Prüfer speichert nichts. Angemeldete Mandanten haben fünf Tabellen; XML wird nie
  gespeichert, sondern bei jedem Abruf aus den strukturierten Daten neu erzeugt.
- Die KI-Erklärung ist standardmäßig aus. Eingeschaltet geht ein einzelner Befund, maskiert, an den
  konfigurierten Anbieter — nie das Dokument. Der Endpunkt ist frei wählbar, auch ein eigener.
- Telemetrie ist standardmäßig aus; eingeschaltet enthält sie keine Dokumentwerte, keine
  Mandanten-ID, kein Credential.
- Auskunft (Art. 15) über API und Dashboard; vollständige Löschung (Art. 17) in einer Transaktion,
  vom Mandanten selbst auslösbar. Der nächtliche Aufbewahrungs-Job löscht Berichte und Protokolle
  nach Frist — **nie Rechnungen**, denn § 132 BAO verpflichtet zu sieben Jahren Aufbewahrung.
- Im Modell A verlassen Rechnungsdaten Ihr Haus nicht, solange die KI aus ist — und auch dann nur
  der maskierte Befundtext.

### 2.3 Sicherung und Wiederherstellung (Modell B; im Modell A dieselben Skripte bei Ihnen)

Jede Nacht ein komprimierter Dump, der mit `pg_restore --list` zurückgelesen wird, bevor er sich
erfolgreich nennt, mit SHA-256-Prüfsumme daneben. Das Wiederherstellungsskript prüft die Prüfsumme,
nennt die Zieldatenbank und verlangt eine ausdrückliche Bestätigung. Die Wiederherstellung ist
nicht nur dokumentiert, sondern geprobt: `BackupRestoreDrillIT` führt sie in jedem Build durch —
Dump, Wiederherstellung in eine zweite Datenbank, Abgleich der Zeilenzahl jeder Tabelle und
Rücklesen einer Rechnung mit Inhalt.

Die Sicherungen werden jede Nacht auf einen zweiten Server kopiert und von dort zurückgelesen,
bevor der Lauf als erfolgreich gilt. Ein leeres Quellverzeichnis ist dabei ein harter Fehler, kein
stiller Erfolg; das Ziel verweigert jedes Löschen. Neun von neun Läufen vom 23.09. bis 01.10.2026
endeten mit übereinstimmender Prüfsumme (gemessen 2026-10-01). Beide Server liegen im selben
Konto und derselben Region — die Kopie schützt vor dem Verlust eines Servers, nicht vor dem Verlust
des Kontos oder der Region. Ein zweiter Standort ist nicht Teil dieses Modells.

### 2.4 Updates und der Regelsatz-Wächter

Zwei Dinge bewegen sich unter einer E-Rechnungs-Plattform: die Regelwerke und die Bibliotheken.

- **Peppol-Regelsatz.** Die Version ist im Code festgelegt (2026.5, übernommen 2026-08-07, von
  OpenPeppol seit 2026-08-17 verbindlich). Ein Test liest die datierten Regelsätze, die die
  Bibliothek ausliefert, und lässt den Build an dem Tag fehlschlagen, an dem ein neuer Satz den
  festgelegten überholt — mit Versionsnummer und Stichtag in der Fehlermeldung. Ein zweiter Test
  prüft den deutschen Übersetzungskatalog gegen die ausgelieferten Regelwerks-Dateien, nicht gegen
  eine gepflegte Liste; so fiel beim Wechsel auf 2026.5 eine gestrichene Regel auf, die der Katalog
  noch übersetzte. Ein Termin, an den man denken muss, ist kein Termin, sondern ein Risiko.
- **ebInterface.** Version 6.1. Die Architektur ist auf eine weitere Version vorbereitet;
  ebInterface 7.0 wird beobachtet und ist nicht zugesagt.
- **Bibliotheken.** Dependabot öffnet die Updates, der Abhängigkeits-Scan blockiert den Build ab CVSS 7,0. Jede
  Unterdrückung trägt eine Begründung und ein Ablaufdatum.
- **Ausrollen (Modell B).** Jeder Merge auf `main` baut das Image und rollt es aus; ein
  fehlgeschlagener Rollout nimmt sich selbst zurück; ein Rollback ist das vorherige
  `sha-<commit>`-Tag. Die laufende Version ist über `/actuator/info` (authentifiziert) ablesbar.
- **Ausrollen (Modell A).** Sie entscheiden, wann; das CHANGELOG nennt API-sichtbare Änderungen
  ausdrücklich.

### 2.5 Servicezeiten

Werktags 09–17 Uhr (Wien). Eine substanzielle Antwort innerhalb von vier Stunden in diesen Zeiten.
Kein 24/7, kein Bereitschaftsdienst, keine garantierte Verfügbarkeit in Prozent. Störungen und
Fragen an office@stoicera.com; Sicherheitslücken auf dem Weg in [SECURITY.md](../SECURITY.md),
nicht als öffentliches Issue.

---

## 3. Integrationspfad in fünf Schritten

1. **Stand ermitteln, ohne Konto.** Drei Rechnungen Ihres Hauses durch den öffentlichen Prüfer —
   im Browser unter `/validator` oder per `curl -F file=@rechnung.xml …/api/v1/validate`. Nichts
   davon wird gespeichert. Sie wissen danach, welche Regeln Ihre Dokumente verletzen, und ob es
   Bund-spezifische sind (Auftragsreferenz, Lieferantennummer) oder allgemeine.
2. **Betriebsmodell wählen und Mandanten einrichten.** A oder B (Abschnitt 1). Im Dashboard unter
   „API-Schlüssel" einen Schlüssel erzeugen; der Klartext wird genau einmal gezeigt und gehört in
   Ihren Geheimnisspeicher, nicht in Code oder Konfigurationsdateien.
3. **Prüfung vor den Versand hängen.** Ihr Rechnungsausgang ruft `POST /api/v1/validate` mit
   `X-Api-Key` auf, bevor das Dokument das Haus verlässt. `valid: false` blockiert den Versand;
   `ruleId`, `messageDe` und `location` landen in Ihrem System, wo die Sachbearbeitung sie sieht.
   Mit Credential wird jeder Bericht im Mandanten abgelegt und ist im Dashboard wiederauffindbar.
4. **Optional: erzeugen oder konvertieren.** Wenn Ihr System noch kein ebInterface oder UBL
   schreibt: strukturierte Rechnungsdaten als JSON an `POST /api/v1/invoices` (das Format ist in
   [`samples/invoice-b2g-sample.json`](../samples/invoice-b2g-sample.json) vollständig belegt),
   danach ebInterface, UBL und PDF abrufen. Wenn es eines der beiden Formate schreibt und das andere
   braucht: `POST /api/v1/convert` — mit dem Bericht darüber, was das Zielformat nicht tragen kann.
5. **Betrieb festlegen.** `/actuator/health` in Ihr Monitoring; wer Befunde fachlich beantwortet;
   wer Updates freigibt (A) oder über ausgerollte Versionen informiert wird (B); der Rückfragekanal
   in den Servicezeiten.

Die Schritte 1 bis 3 sind ohne Codeänderung auf unserer Seite möglich. Für Schritt 4 reicht in
den meisten Fällen das vorhandene JSON-Schema; wo Ihr Fall etwas braucht, das es nicht abbildet,
sagen wir das vorher.

---

## 4. Grenzen und Nicht-Ziele

Aus [docs/PRD.md §5](PRD.md), bewusst entschieden und dokumentiert:

- **Kein Peppol Access Point.** Die Plattform ist nicht am Peppol-Netz angeschlossen und sendet
  keine Rechnungen. Die Architektur sieht einen Übergabepunkt vor; der Versand läuft über Ihren
  Access-Point-Anbieter.
- **Kein automatischer Upload zu e-rechnung.gv.at.** Das Dokument wird erzeugt und geprüft; den
  Upload erledigen Sie oder Ihr System auf dem bestehenden Weg.
- **Kein Rechnungseingang.** Nur Ausgang und die Prüfung beliebiger Dateien.
- **Keine Buchhaltung, keine Steuer.** Belege werden erzeugt und geprüft, nicht verbucht.
- **Keine Rechts- oder Steuerberatung.** Prüfung und Erklärung sind technische Hilfestellungen.

Dazu die bekannten technischen Grenzen, die [SECURITY.md](../SECURITY.md) und das
[CHANGELOG](../CHANGELOG.md) selbst nennen: Rate-Limiter je Instanz; Abmeldung beendet die Sitzung
der Anwendung, nicht die von Keycloak; Peppol-Meldungen sind dort englisch, wo das Regelwerk nur
Englisch veröffentlicht — die 79 Regeln, die ein österreichischer Einreicher realistisch trifft,
sind übersetzt (Stand 2026-10-01); kein ZUGFeRD/Factur-X-Hybrid-PDF; kein anderer OIDC-Anbieter als
Keycloak erprobt.

---

## 5. Checkliste für die IT

### Modell A — vor dem ersten Start

- [ ] Docker Engine mit Compose v2; Host mit rund 2 GB RAM über dem Grundbedarf und 15 GB freier
      Platte (`docs/deployment.md` §1).
- [ ] Reverse Proxy mit TLS vor der Anwendung; nur 80/443 (und Ihr Administrationszugang) von außen
      erreichbar. Die Anwendung selbst spricht intern HTTP auf 8080.
- [ ] `.env` vollständig: jedes Passwort generiert, keines der Entwicklungswerte
      (`openssl rand -base64 24`). `.env.example` ist die vollständige Liste der Anwendungsvariablen.
- [ ] `SERVER_FORWARD_HEADERS_STRATEGY=native` hinter dem Proxy — und **nicht** `framework`
      (`docs/deployment-reference.md` §2 erklärt, warum). Ohne Proxy: nicht setzen.
- [ ] Keycloak im Produktionsmodus mit eigener Datenbank und echtem Hostname, nicht `start-dev`;
      Bootstrap-Zugangsdaten nach der Einrichtung entfernen (`docs/deployment.md` §7).
- [ ] `OAUTH2_AUDIENCE` gesetzt, sobald der Realm mehr als einen Client hat.
- [ ] `SPRING_PROFILES_ACTIVE=prod` für JSON-Logs (`docs/deployment.md` §8); Log-Senke festgelegt.
- [ ] `API_DOCS_ENABLED` entschieden: `false`, wenn die API-Beschreibung nicht öffentlich sein soll.
- [ ] `FEATURES_AI_EXPLANATIONS` bleibt `false`, bis die Entscheidung über den Anbieter gefallen ist.
- [ ] `OTEL_ENABLED` bleibt `false`, solange kein eigener Collector steht. Das
      `observability`-Profil (Grafana ohne Anmeldung) nie nach außen veröffentlichen.
- [ ] Ausgehende Verbindungen: Im Betrieb braucht die Anwendung keine, solange KI und Telemetrie aus
      sind — nur das Ziehen der Images.
- [ ] Sicherung: `scripts/backup.sh` nächtlich per Cron in einem `postgres:17`-Container am
      Compose-Netz (`docs/deployment.md` §10); einmal `scripts/restore.sh` in eine Wegwerf-Datenbank
      geprobt, bevor die Instanz produktiv geht. Keycloak-Datenbank gleich mitsichern.
- [ ] Monitoring: `GET /actuator/health` (anonym) im Prüfintervall; alles andere unter `/actuator`
      ist authentifiziert.
- [ ] Prüfbefehl nach dem Start: `curl -fsS https://<ihr-host>/actuator/health` → `"status":"UP"`,
      dann eine der Beispieldateien aus `samples/` anonym prüfen → `"valid":true`.

### Modell B — was wir von Ihnen brauchen

- [ ] Gewünschter Hostname und wer die DNS-Zone verwaltet.
- [ ] Ansprechperson für den Auftragsverarbeitungsvertrag.
- [ ] Wer Benutzerkonten anlegt (wir im Realm, oder Sie über eine Einladung).
- [ ] Ob die API-Beschreibung öffentlich sein darf und ob die KI-Erklärung gewünscht ist.

### Modell C — für die Integration

- [ ] Verwahrung des API-Schlüssels im Geheimnisspeicher; Rotation = neuen Schlüssel erzeugen,
      alten widerrufen (Dashboard oder `/api/v1/api-keys` mit OAuth2-Token).
- [ ] TLS-Verbindung; genau ein Credential je Anfrage.
- [ ] Behandlung von 413 (Dokument über 2 MB), 429 (`Retry-After` beachten) und von
      `application/problem+json` nach dem Slug in `type`, nicht nach dem Text.
- [ ] Zuordnung der Befundfelder in Ihr System: `ruleId`, `severity`, `messageDe`, `location`.
- [ ] Entscheidung, ob Prüfungen mit Credential (Bericht wird abgelegt) oder anonym (nichts wird
      abgelegt) laufen.
- [ ] Ein Durchlauf gegen `samples/invoice-b2g-sample.ebinterface.xml` (gültig) und gegen
      `validation/src/test/resources/corpus/invalid/at-b2g-01-missing-order-reference.xml`
      (ein Befund `AT-B2G-01`), bevor echte Dokumente fließen.

---

Weiterführend: [README](../README.md) · [Demo-Regiebuch](demo.md) · [SECURITY.md](../SECURITY.md) ·
[docs/privacy.md](privacy.md) · [docs/deployment.md](deployment.md) ·
[docs/problems.md](problems.md) · [Architekturentscheidungen](adr/)
