# PRD — einvoice-at (Arbeitstitel)

**E-Invoicing-Plattform für Österreich: ebInterface & Peppol erzeugen, validieren, verstehen.**

Version 1.0 · 23.07.2026 · Owner: Sebastian Kern · Umsetzung: Claude Code, milestone-basiert

---

## 1. Problem

In Österreich verlangt der Bund für B2G-Rechnungen elektronische Rechnungen über e-rechnung.gv.at — akzeptiert werden **ebInterface** (aktuell 6.1, empfohlen 4.3/5.0/6.0/6.1) und **Peppol BIS Billing 3.0 (UBL)**. Ab 2026 steigt der Druck Richtung strukturierter E-Rechnung auch im B2B (EU: ViDA; DE: E-Rechnungs-Pflicht als Vorbild).

Gleichzeitig: Internationale Tools (Zoho, viele ERPs) unterstützen ZUGFeRD/XRechnung, aber **kein natives ebInterface** und keine österreichische Peppol-Anbindung. KMU und deren Softwarehäuser brauchen Drittanbieter-Konnektoren oder Eigenbau. Fehlerhafte XML-Rechnungen werden vom Bund abgelehnt — und die Fehlermeldungen (Schematron-Ausgaben) sind für Nicht-Techniker unlesbar.

## 2. Lösung

Eine self-hostbare Java/Spring-Boot-Plattform mit drei Kernfähigkeiten:

1. **Erzeugen:** Aus strukturierten Rechnungsdaten (REST/JSON oder Web-Formular) valides ebInterface-6.1-XML und Peppol-BIS-3.0-UBL erzeugen — inkl. PDF-Ansicht der Rechnung.
2. **Validieren:** Hochgeladene ebInterface-/UBL-Dateien gegen XSD + Schematron + Geschäftsregeln prüfen — mit einem **menschenlesbaren, deutschen Prüfbericht**. Optional erklärt ein KI-Assistent jeden Fehler in einfacher Sprache und schlägt die Korrektur vor (degradierbar, Opt-in).
3. **Konvertieren:** ebInterface ↔ Peppol UBL (EN-16931-Mapping) in beide Richtungen, mit dokumentierten Mapping-Grenzen.

**Öffentlicher Lead-Magnet:** Die Validierung gibt es als frei zugängliche Web-Seite ("Österreichischer E-Rechnungs-Prüfer") — SEO-Content, der genau die Suchanfragen österreichischer Buchhalter und Entwickler trifft.

## 3. Warum wir / warum jetzt (Portfolio-Zweck)

- Belegt **Java/Spring-Boot-Seniorität** (Zielmarkt Wien/Linz, öffentliche Hand, JKU-Ausschreibung).
- Belegt **ERP-/Integrations- und Schnittstellenkompetenz** (der meistgenannte Bedarf regionaler Ausschreibungen).
- Österreichspezifisch, suchmaschinenfähig, anschlussfähig: Aus dem freien Validator können Anfragen für Konnektor-Werkverträge (Zoho/Odoo/individuelle ERPs) entstehen.
- KI + Security + Cloud sichtbar integriert → die "Money-Glitch"-Formel komplett.

## 4. Zielgruppen & Personas

- **Entwickler:in in KMU-Softwarehaus** (primär): braucht API, um aus dem eigenen System B2G-konforme Rechnungen zu erzeugen. Bewertet uns nach Code- und Doku-Qualität.
- **Buchhalter:in / Office-Kraft in KMU:** lädt eine abgelehnte XML-Rechnung hoch und will wissen: "Was ist falsch und was muss ich tun?"
- **Prüfer bei Institution/Uni (sekundär):** liest Repo als Beleg unserer Arbeitsweise.

## 5. Scope (MVP = Milestones 1–5)

**In Scope:**
- Rechnungsdatenmodell nach EN-16931-Kernrechnung (Verkäufer, Käufer, Positionen, USt-Sätze inkl. österreichischer Sätze 20/13/10/0 %, Zahlungsdaten inkl. IBAN, Auftragsreferenz/Lieferantennummer wie vom Bund gefordert).
- Erzeugung ebInterface 6.1 (Architektur versionsfähig für 7.0, angekündigt Q4 2026) + UBL BIS 3.0.
- Validierung: XSD + Schematron (ebInterface- und Peppol-Regelwerke via etablierter Open-Source-Bibliotheken, z. B. ph-ebinterface/phive von Philip Helger) + eigene Business-Rules (z. B. Bund verlangt Auftragsreferenz).
- Deutscher Prüfbericht (strukturiert, je Fehler: Regel, Fundstelle, Klartext) + optionale KI-Erklärung.
- PDF-Rendering der Rechnung (Druckansicht).
- REST-API (OpenAPI), API-Keys/OAuth2 (Keycloak), Multi-Mandant light (ein Account = ein Mandant).
- Öffentliche Validator-Seite ohne Login (rate-limited).
- Audit-Log, OpenTelemetry, Docker, CI/CD, Deployment Hetzner+Dokploy.

**Out of Scope (bewusst, dokumentiert):**
- Direkte Peppol-Netzwerk-Teilnahme als Access Point (Zertifizierungsprozess — als "Ausblick" dokumentiert; Architektur sieht Übergabepunkt vor).
- Automatischer Upload zu e-rechnung.gv.at im MVP (Webservice-Anbindung als Milestone 6 / Stretch).
- Rechnungs-Eingangsverarbeitung (nur Ausgang + Validierung beliebiger Dateien).
- Buchhaltung/Steuer — wir erzeugen und prüfen Belege, wir verbuchen nicht.

## 6. User Stories (Auszug, MVP)

1. Als Entwickler poste ich JSON an `/api/v1/invoices` und erhalte valides ebInterface-6.1-XML zurück (oder 422 mit strukturierten Fehlern).
2. Als Entwicklerin fordere ich dieselbe Rechnung als UBL oder PDF an (`Accept`-Header bzw. `/render`).
3. Als Buchhalter lade ich auf der öffentlichen Seite eine XML hoch und bekomme in < 5 s einen deutschen Prüfbericht.
4. Als Buchhalterin klicke ich "Fehler erklären" und bekomme je Fehler eine KI-Erklärung mit Korrekturvorschlag.
5. Als API-Kunde sehe ich alle meine erzeugten Rechnungen und Prüfberichte in meinem Dashboard (Login via Keycloak).
6. Als Betreiber sehe ich Traces/Metriken jeder Validierung und ein Audit-Log je Mandant.

## 7. Nicht-funktionale Anforderungen

- Validierung < 5 s p95 für Dateien ≤ 1 MB; API-Erzeugung < 1 s p95.
- Öffentlicher Validator: Rate Limit (z. B. 10/min/IP), Datei max. 2 MB, Uploads werden nach Prüfung **nicht** dauerhaft gespeichert (Datenschutz als Feature, DSGVO-Hinweis).
- Verfügbarkeit: Single-VPS-Betrieb, Neustart-fest (stateless App, Migrationen automatisch).
- DSGVO: Datenminimierung, Löschkonzept, AV-tauglich dokumentiert.

## 8. Erfolgs-Metriken (Portfolio + Produkt)

- Repo: vollständige CI grün, Quickstart < 5 min, ≥ 1 externer Stern/Fork organisch 😉 — sekundär.
- Case Study + Architekturartikel veröffentlicht; Validator-Seite indexiert für "ebInterface validieren", "e-Rechnung Bund prüfen".
- ≥ 1 qualifizierte Anfrage (Konnektor/Werkvertrag) innerhalb von 3 Monaten nach Launch.

## 9. Offene Punkte / Entscheidungen an den Owner

- **Produktname & Domain** (Arbeitstitel einvoice-at; Kandidaten sammeln, Domain-Check vor Launch).
- Hosting-Subdomain: `labs.stoicera.com/einvoice` vs. eigene Domain (SEO-Entscheidung).
- ebInterface 7.0: sobald final (Q4 2026) als Version nachziehen — Architektur ist darauf ausgelegt.
