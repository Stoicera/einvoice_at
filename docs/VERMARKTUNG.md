# Vermarktung — Phase 4 aus dem Brainstorming, konkretisiert

Regel: **Vermarktungs-Artefakte entstehen milestone-begleitend, nicht am Ende.** Screenshots, Zahlen und Entscheidungen aus dem Worklog sind das Rohmaterial — nach Projektende ist alles doppelt so mühsam.

Aus jedem Projekt entstehen (Checkliste je Projekt):

- [ ] GitHub-Repo (public, README-Qualität = Visitenkarte)
- [ ] Technische Case Study (Website / austrianbusiness.at)
- [ ] Architekturartikel
- [ ] LinkedIn-Post (persönliches Profil + Stoicera-Seite)
- [ ] Demo-Video (2–4 min, Loom-Stil reicht)
- [ ] Spezifische Outreach-Nachricht
- [ ] Kompetenzprofil-Absatz für Projektvermittler

---

## Projekt 01 — einvoice-at (Sebastian)

**Kernbotschaft:** "Wir beherrschen Java/Spring Boot, Schnittstellen und österreichische Standards — nachprüfbar, mit Tests und Betrieb."

- **Case Study-Winkel:** "Warum internationale ERPs an der österreichischen E-Rechnung scheitern — und wie wir ebInterface + Peppol in einer self-hosted Plattform gelöst haben." Zweitartikel (SEO): "ebInterface 6.1 validieren: die häufigsten Fehler und was sie bedeuten" (aus dem Golden-File-Korpus!).
- **Lead-Magnet:** öffentliche Validator-Seite; Ziel-Keywords: *ebInterface validieren, e-Rechnung Bund prüfen, ebInterface Zoho/Odoo, Peppol Österreich KMU*.
- **Outreach-Ziele:** (a) österreichische Zoho-/Odoo-Partner (Konnektor-Werkverträge), (b) Softwarehäuser mit Fakturierungsmodulen, (c) Steuerberater-Netzwerke OÖ.
- **JKU/Plösch-Anker:** Testautomatisierungs-Story (Testpyramide, Selenium-E2E, Golden-File-Korpus, KI-gestützte Fehlererklärung) deckt sich mit seinen Forschungsthemen (LLMs im Testing, Requirements-Qualität) — im Gespräch konkret referenzieren, nicht namedroppen. Wert-Priorisierung: Wir haben bewusst M7 (Upload-Anbindung) hinter den Kern gestellt — das ist Value-Based Prioritization in Aktion.
- **Timing:** LinkedIn-Post 1 nach M2 ("wir bauen öffentlich"), Post 2 + Video nach M6 (Launch), Artikel nach M6.
- **ebInterface 7.0 (Q4 2026):** Follow-up-Post, sobald wir 7.0 unterstützen — zweiter Newszyklus gratis.

## Projekt 02 — dotnet-enterprise-starter + objektsicher (Raphael)

**Kernbotschaft Teil A:** "So sieht jedes Projekt aus, das ihr bei uns beauftragt — vom ersten Commit an." (Starter = Beweis der Engineering-Standards.)
**Kernbotschaft Teil B:** ".NET + Angular in einer echten österreichischen Haftungs-Domäne — von der Domänenmodellierung bis zum revisionssicheren PDF."

- **Case Study-Winkel Teil A:** "Unser .NET-Starter: die Entscheidungen hinter einem produktionsreifen ASP.NET-Core-Template (und was wir bewusst weglassen)." — Vertical Slice vs. Schichten, warum Postgres, warum Testcontainers.
- **Case Study-Winkel Teil B:** "Objektsicherheitsprüfungen digitalisieren: Immutability, lückenlose Berichtsnummern und DSGVO-konforme Fotos." Ziel-Keywords: *ÖNORM B 1300 Software, Objektsicherheitsprüfung digital, Begehung App Hausverwaltung*.
- **Outreach-Ziele:** (a) Hausverwaltungen OÖ, (b) **Gemeinden aus der MealTime-Pipeline** (Gallneukirchen, Perg, Engerwitzdorf — bestehende Beziehung!), (c) FM-Dienstleister, Sachverständige.
- **Wichtig im Wording:** "angelehnt an ÖNORM B 1300", kein Zertifizierungsanspruch, Norm-Text nicht enthalten.
- **Timing:** Teil A ist nach RM3 sofort einzeln vermarktbar (Starter-Post performt in Dev-Kreisen gut, GitHub-Template teilen). Teil B: Demo-Video mit Tablet-Begehung + Beispiel-PDF nach RB4.

## Projekt 04 — migration-lab (Sebastian, nach einvoice-at)

**Kernbotschaft:** "Wir migrieren Ihre Legacy-Anwendung ohne Blindflug — hier haben wir es öffentlich vorgemacht, mit Sicherheitsnetz, Zahlen und Playbook."

- **Case Study-Winkel:** "Spring Boot 1.5 → 4, AngularJS → Angular 20: eine Migration, öffentlich durchgeführt." Zweitartikel (Forschungs-/JKU-Anschluss): "LLM-generierte Unit-Tests für Legacy-Code: unser Mutation-Testing-Ergebnis" — der PIT-Report ist das differenzierende Artefakt, das sonst niemand veröffentlicht.
- **Playbook als eigenständiges Asset:** PDF-Export als Anhang für Direktvergaben und Erstgespräche; optional Serie auf austrianbusiness.at.
- **Outreach-Ziele:** (a) KMU/Industrie OÖ+Wien mit sichtbar alten Webanwendungen, (b) JKU/Unis (die Ausschreibung IST eine solche Migration), (c) Vermittler mit Modernisierungs-Mandaten.
- **JKU/Plösch-Anker (der stärkste von allen):** Die Migrationsstrecke entspricht wörtlich der ausgeschriebenen Leistung; das KI-Testgen-Experiment mit vorregistriertem Protokoll und Mutation-Score spricht exakt seine Forschungssprache (LLMs im Testing, empirisch statt hypig). Im Gespräch: Report zeigen, nicht erzählen.
- **Timing:** Post 1 nach G2 ("Das Sicherheitsnetz steht — ab jetzt bricht nichts mehr unbemerkt"), Post 2 nach G5 (Headline: gleiche Selenium-Suite grün auf alter UND neuer UI), Post 3 + Artikel nach G6 (KI-Report), Launch nach G7.

## Gemeinsames Kompetenzprofil (für Vermittler, nach beiden Launches aktualisieren)

Ein Absatz je Stack mit Repo-Links: Java/Spring Boot 4 (einvoice-at: Standards-Integration, Schematron-Validierung, Keycloak/OAuth2, Testcontainers, Selenium) · C#/.NET 10 + Angular (starter + objektsicher: Clean/Vertical-Slice, EF Core, Playwright, QuestPDF) · beide: Docker, CI/CD, OpenTelemetry, EU-Hosting, KI-Integration mit Degradierbarkeit. → ersetzt "Trust me bro" durch klickbare Nachweise.

## Direktvergabe-Hinweis

stoicera.com hat bereits eine Direktvergabe-Seite — beide Projekte dort als Referenz verlinken, sobald live (öffentliche Auftraggeber dürfen bis zur Schwelle direkt vergeben; genau unsere Gemeinde-/Uni-Zielgruppe).
