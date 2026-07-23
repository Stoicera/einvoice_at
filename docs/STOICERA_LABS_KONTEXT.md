# Stoicera Labs — Kontext für alle Portfolio-Projekte

> Diese Datei liegt in `docs/` jedes Labs-Repos und wird von CLAUDE.md referenziert.
> Sie beantwortet: Wer baut hier, für wen, und warum genau so?

## Wer wir sind

**Stoicera Software Group** — Raphael Lugmayr und Sebastian Kern GesbR, Oberösterreich (Linz/Perg/Wien). Seit 2023: 50+ Projekte für 20+ Kunden. Zwei Marken:

- **Stoicera** (stoicera.com): Produkt- & Web-Engineering — SaaS, Applied AI, EU-Cloud. Moderner, kuratierter Stack (TypeScript/Next.js, Python, PostgreSQL, Docker).
- **Lugmayr-Kern** (lugmayrkern.at): Der lokale IT-Dienstleister für Werkverträge, Modernisierung und Cloud-Migration in den beiden stärksten Enterprise-Stacks Österreichs — **C#/.NET** und **Java/Spring Boot**.

**Stoicera Labs** ist das öffentliche Engineering-Schaufenster der Gruppe: production-grade Referenzsysteme, die belegen, was die Positionierungspapiere behaupten. Kein "Trust me bro" — nachprüfbare Senior-Software-Engineering-Arbeit.

## Zielmarkt der Labs-Projekte

- Österreichische KMU und Industrie in **Linz, Wien, Salzburg** (Werkverträge: Modernisierung, Integration, Schnittstellen, Testautomatisierung).
- **Universitäten, Institute, öffentliche Hand** (konkret: JKU Linz, Institut für Wirtschaftsinformatik — Software Engineering; Ansprechperson Prof. Reinhold Plösch; ausgeschrieben: Angular/Spring-Boot-Migration + Selenium-Testautomatisierung).
- Projektvermittler für Enterprise-Werkverträge.

Aktuelle Ausschreibungen in der Region nennen ausdrücklich: Wartung, Weiterentwicklung, Schnittstellenprogrammierung, bestehende Unternehmenssoftware, individuelle Kundenlösungen, Fertigungsumfelder, Produktionssysteme, APIs.

## Was jedes Labs-Projekt zeigen muss (die "Money-Glitch"-Formel)

1. **Enterprise-Sprache in Produktion:** Java/Spring Boot oder C#/.NET — idiomatisch, nicht als Tutorial-Code.
2. **Cloud & Betrieb:** Docker, CI/CD, Deployment auf EU-Infrastruktur (Hetzner VPS + Dokploy/Coolify). Bewusst **kein Azure-Deployment** in den Labs-Repos (Azure-Kompetenz wird über Kundenprojekte belegt, nicht über eigene Infrastrukturkosten).
3. **IT-Security als Architektur:** OAuth2/OIDC, Secrets-Handling, Input-Validierung, Audit-Logs, dependency scanning — sichtbar und dokumentiert.
4. **KI sinnvoll integriert:** kein ChatGPT-Wrapper, sondern KI an einer Stelle, wo sie messbaren Nutzen stiftet (z. B. Erklärung von Validierungsfehlern, Berichtsentwürfe) — via OpenRouter, austauschbar abstrahiert.
5. **Testautomatisierung als Erstklass-Bürger:** Unit + Integration (Testcontainers) + E2E (Selenium bzw. Playwright) + CI-Gates. Testbarkeit ist Teil der Architektur, nicht Nachgedanke.
6. **Österreich-Bezug:** löst ein echtes österreichisches Problem (ebInterface/Peppol B2G, ÖNORM B 1300) → suchmaschinenfähig, anschlussfähig für Leads.

## Was wir bewusst NICHT bauen

Todo-Apps, Taschenrechner, Wetter-Apps, URL-Shortener, generische E-Commerce-Clones, ChatGPT-Wrapper, zwölf Microservices ohne fachlichen Grund, Repos ohne Tests und Doku.

## Aus jedem Projekt entsteht (Phase 4, siehe 03_vermarktung/)

GitHub-Repo · technische Case Study · Architekturartikel · LinkedIn-Post · kurzes Demo-Video · spezifische Outreach-Nachricht · Kompetenzprofil für Projektvermittler.
