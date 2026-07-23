# Milestones — einvoice-at

Regel: **Ein Milestone = ein abgeschlossenes, demonstrierbares Inkrement.** Jeder Milestone endet mit: Tests grün, CI grün, README-Update, Commit/PR, kurzem Demo-GIF oder Screenshot für die Case Study. Nicht zum nächsten Milestone springen, solange die Definition of Done (ENGINEERING_STANDARDS.md) nicht erfüllt ist.

Geschätzter Gesamtaufwand: ~5–6 Wochen fokussierte Arbeit mit Claude Code.

---

## M0 — Fundament (½–1 Tag)
Repo-Skeleton: Maven-Multi-Module, Spotless/google-java-format, EditorConfig, GitHub Actions (build+test+lint), Dockerfile (Multi-Stage, distroless o. ä.), docker-compose (app+postgres), README-Gerüst (EN + deutsche Kurzfassung), LICENSE (Apache-2.0), ADR-0001 (Stack), ADR-0002 (Modular Monolith), Issue-/PR-Templates.
**Abnahme:** `docker compose up` startet leere App mit Health-Endpoint; CI grün.

## M1 — Kanonisches Rechnungsmodell (2–3 Tage)
`core`-Modul: EN-16931-Kernmodell, Geldarithmetik (BigDecimal, Rundungsregeln), österreichische USt-Logik (20/13/10/0, Reverse Charge), Invarianten + Builder. Property-based Tests (jqwik) für Summen-/Steuerarithmetik, ~100 % Coverage auf Domänenlogik. ArchUnit-Regeln aktiv.
**Abnahme:** Testbericht; ADR-0003 (kanonisches Modell als Herzstück).

## M2 — ebInterface 6.1 erzeugen + validieren (3–5 Tage)
`formats-ebinterface` (ph-ebinterface), `mapping` (kanonisch → ebInterface), `validation` Stufe XSD + Schematron (phive) + erste Business-Rules (Auftragsreferenz, IBAN). Golden-File-Korpus anlegen (valide + gezielt kaputte Beispiele je Regel). CLI-Einstieg (`ValidationRunner`) für schnelle Korpus-Läufe.
**Abnahme:** Beispiel-JSON → valides ebInterface-XML, vom eigenen Validator und (manuell, einmalig) vom offiziellen ebInterface-Portal-Check akzeptiert; Korpus-Tests grün.

## M3 — REST-API + Persistenz + Security (4–5 Tage)
`app`-Modul: `POST /invoices`, `POST /validate`, Formatausgaben (XML), OpenAPI, problem+json, Postgres+Flyway, Audit-Log, Keycloak im Compose (Realm-Import), OAuth2 Resource Server + API-Keys, Rate Limiting auf `/validate`. Integrationstests mit Testcontainers (Postgres + Keycloak).
**Abnahme:** OpenAPI-UI nutzbar; Auth-Matrix getestet (anonym/API-Key/OAuth2); Audit-Einträge nachweisbar.

## M4 — UBL BIS 3.0 + Konvertierung + PDF (3–4 Tage)
`formats-ubl`, Mapping beidseitig inkl. Lossy-Report, `POST /convert`, `rendering` (PDF-Druckansicht). Golden-Files für Roundtrips (ebInterface→UBL→ebInterface, dokumentierte Abweichungen).
**Abnahme:** Konvertierung mit Verlust-Report; PDF sieht nach Rechnung aus, nicht nach Debug-Ausgabe.

## M5 — Web-UI + öffentlicher Validator + KI-Erklärungen (4–5 Tage)
Thymeleaf+htmx: öffentliche Prüfer-Seite (DE, SEO-Meta, DSGVO-Hinweis "Upload wird nicht gespeichert"), Report-Ansicht, Dashboard (Login, Rechnungserstellung als Wizard, API-Keys). `ai-assist`: OpenRouter-Adapter, FindingExplainer, Feature-Flag, PII-Scrubbing, Kosten-Metriken. Selenium-E2E: Upload→Report→Erklären-Flow; Gatling-Szenario Validator.
**Abnahme:** E2E grün in CI (main); Validator-Demo-Video-tauglich; KI abschaltbar ohne Funktionsverlust.

## M6 — Betrieb + Politur (2–3 Tage)
OTel vollständig (Traces über Pipeline-Stufen), Observability-Compose-Profil, Deployment auf Hetzner+Dokploy inkl. `docs/deployment.md`, Backup/Restore-Probe, SECURITY.md (STRIDE-light), Dependency-Scans in CI, Lighthouse ≥ 95 public, README final (Screenshots, Quickstart < 5 min), GitHub Release v0.1.0.
**Abnahme:** Live-Instanz erreichbar; ein Fremder kann dem Quickstart in 5 Minuten folgen.

## M7 (Stretch) — e-rechnung.gv.at-Anbindung
Webservice-Upload (ER>B) hinter Feature-Flag, Sandbox-Test; alternativ dokumentierter Peppol-Übergabepunkt. Nur nach M6 und nur, wenn Zeit übrig — der Werkvertrags-Case ist auch ohne M7 komplett.

---

### Reihenfolge-Prinzipien für Claude Code
1. Kein UI vor stabiler Domäne (M1–M2 zuerst — das Modell ist das Meisterstück).
2. Golden-File-Korpus wächst bei jedem Bug: erst Testfall, dann Fix.
3. Jede Session beginnt mit `docs/` lesen + Teststatus prüfen, endet mit grüner CI.
