# ADR-0011 — Aufbewahrung und Löschung: Art. 17 vollständig, Art. 5 geplant, Rechnungen unangetastet

Status: accepted · Datum: 2026-07-26 · Milestone: M5

## Kontext

`docs/privacy.md` §4 hat von M3 bis M5 zwei Lücken **namentlich** als „noch nicht implementiert"
geführt: die vollständige Löschung eines Mandanten und automatische Aufbewahrungsfristen. Das Dokument
zog daraus selbst den Schluss, dass die Plattform „keine ausreichende Grundlage für einen produktiven
Einsatz mit echten Kundendaten" sei — was ehrlich war und drei Milestones lang gestimmt hat.

Gleichzeitig verspricht die Startseite seit M5 wörtlich: „Wer angemeldet ist … kann alle eigenen Daten
dort jederzeit vollständig löschen." Eine Zusage auf der Landingpage, die im Produkt keinen Knopf hat,
ist die Art von Lücke, die man nicht stehen lässt.

Die DSGVO stellt hier zwei verschiedene Anforderungen, die gern in einen Topf geworfen werden:

- **Art. 17** — Löschung *auf Verlangen*. Ein Mensch bittet, und alles muss weg.
- **Art. 5 Abs. 1 lit. e** — Speicherbegrenzung. Daten verfallen *von selbst*, wenn ihr Zweck erfüllt
  ist, ohne dass jemand fragen muss.

Und das österreichische Steuerrecht stellt eine dritte, entgegengesetzte: **§ 132 BAO** verpflichtet
Unternehmer, Rechnungen **sieben Jahre** aufzubewahren.

## Entscheidung 1 — Zwei Operationen, nicht eine mit Parametern

`TenantErasureService` (Art. 17) und `RetentionService` (Art. 5) sind getrennte Klassen mit getrennten
Regeln. Der naheliegende Weg wäre eine „Aufräum"-Komponente mit einem Schalter gewesen; sie wäre
falsch, weil die beiden Vorgänge sich in genau dem Punkt unterscheiden, der am meisten weh tut: **was
sie anfassen dürfen** (siehe Entscheidung 3). Ein gemeinsamer Codepfad hätte diesen Unterschied zu
einem `if` gemacht, und ein `if` an dieser Stelle ist ein Kandidat dafür, eines Tages falsch gesetzt zu
werden.

## Entscheidung 2 — Löschung heißt alles, in einer Transaktion, in FK-Reihenfolge

Fünf Tabellen verweisen auf den Mandanten, zwei davon aufeinander. Die Reihenfolge ist deshalb keine
Stilfrage: `report.invoice_id` zeigt auf `invoice(id)`, also gehen Berichte vor Rechnungen, und alles
vor der Mandanten-Zeile. Eine Transaktion, weil eine **halbe** Löschung das schlechteste erreichbare
Ergebnis ist — jemandem „gelöscht" zu sagen, während sein Protokoll noch liegt.

**Auch der Audit-Trail geht.** Das ist der interessante Fall, denn ihn zu behalten ist abstrakt
begründbar: Rechenschaftspflicht (Art. 5 Abs. 2) spricht für Belege. Er geht trotzdem, aus einem
konkreten Grund — `audit_event.tenant_id` ist ein Fremdschlüssel auf genau die Zeile, die
Keycloak-Subject und Anzeigename trägt. „Audit-Trail behalten" heißt in der Praxis „Mandanten-Zeile
behalten", und das heißt: dem Verlangen nicht nachkommen. Übrig bleibt eine Log-Zeile mit der
Mandanten-**UUID** (ein Surrogat, das diese Plattform selbst erzeugt hat) und den Zeilenzahlen. Das
belegt, dass gelöscht wurde, ohne zu behalten, was gelöscht wurde.

**Der Mandant darf wiederkommen.** Das Keycloak-Konto gehört nicht dieser Plattform. Wer löscht und
sich danach neu anmeldet, bekommt über `CurrentTenant` einen **neuen, leeren** Mandanten mit demselben
Subject. Nichts wird auferweckt — dieselbe Person, keine Daten — und von außen ist dieser Zustand von
„zum ersten Mal registriert" nicht zu unterscheiden. Genau so soll es sein.

## Entscheidung 3 — Der Aufbewahrungs-Job löscht niemals eine Rechnung

Es wäre einfach, symmetrisch und falsch, Rechnungen zusammen mit Prüfberichten verfallen zu lassen.
§ 132 BAO verpflichtet den Unternehmer zu sieben Jahren. Eine Plattform, die Rechnungen nach einem Jahr
stillschweigend wegräumt, schützt nicht die Privatsphäre ihrer Nutzer, sondern **zerstört
Aufzeichnungen, zu deren Aufbewahrung sie verpflichtet sind** — ungefragt und in ihrem Namen. Der Job
fasst deshalb exakt zwei Tabellen an:

- **Prüfberichte** — ein Validierungsurteil ist Betriebsausgabe, nicht Beleg. Es ist durch erneutes
  Prüfen reproduzierbar, und nach einem Jahr sieht es niemand mehr an.
- **Protokoll-Einträge** — die Sicherheitsspur. Monate lang nützlich, nicht für immer, und die am
  schnellsten wachsende Tabelle im Schema.

Löschung auf Verlangen darf Rechnungen löschen, weil die Person gefragt hat und ihre Aufbewahrungs-
pflicht ihre eigene ist, an einem anderen Ort zu erfüllen. Diese Asymmetrie ist der eigentliche Inhalt
dieser ADR, und sie ist von einem Test festgenagelt, der eine zehn Jahre alte Rechnung durch einen
Purge schickt und danach prüft, dass sie noch da ist.

## Entscheidung 4 — `0` ist der Ausschalter, kein zweites Flag

Fenster werden in Tagen konfiguriert (`RETENTION_REPORT_DAYS`, `RETENTION_AUDIT_DAYS`); **null oder
negativ heißt „unbegrenzt aufbewahren"**. Ein zusätzliches `app.retention.enabled` wäre die
erwartbare Lösung und hätte einen Zustand erlaubt, in dem zwei Mechanismen sich widersprechen —
Fenster gesetzt, Job aus, oder umgekehrt. Ein Mechanismus kann sich nicht widersprechen.

Der Standard ist **an**, mit großzügigen Fenstern. Ein Aufbewahrungskonzept, das erst eingeschaltet
werden muss, ist auf jeder Installation aus, die niemand konfiguriert hat — und genau diese
Installationen sind der Grund, warum Art. 5 existiert.

## Entscheidung 5 — Ein getipptes Wort, keine zweite Schaltfläche

Das Dashboard verlangt das Wort `LÖSCHEN`, exakt und groß geschrieben. Ein „Sind Sie sicher?"-Dialog
bräuchte JavaScript (ADR-0009: die Seiten funktionieren ohne) und ist mit einem versehentlichen Enter
wegzuklicken. Ein getipptes Wort entsteht nicht aus einem Fehlklick.

Die API verlangt **keinen** Bestätigungsparameter. Ein `DELETE` auf eine Ressource namens `/tenant`,
vorgelegt mit dem Credential dieses Mandanten, ist eine unmissverständliche Absichtserklärung; ein
`?confirm=yes` wäre Zeremonie, die jeder Client beim ersten Lauf fest einbaut. Was hier schützt, ist,
dass das Credential den Umfang bestimmt: es gibt keine Mandanten-ID im Request, also lässt sich dieser
Endpunkt auf niemand anderen richten.

## Konsequenzen

- **Positiv:** `docs/privacy.md` muss nicht länger davor warnen, dass diese Plattform für echte
  Kundendaten ungeeignet ist — die beiden Gründe sind weg. Das Versprechen der Startseite hat jetzt
  einen Knopf. Die Protokoll-Tabelle wächst nicht mehr unbegrenzt.
- **Negativ:** Löschung ist endgültig und es gibt kein Backup, aus dem der Betreiber sie zurückholen
  kann. Das ist der Preis dafür, dass „gelöscht" wahr ist, und die Seite sagt es zweimal.
- **Negativ:** Aufbewahrung ist standardmäßig aktiv, also verschwinden Prüfberichte nach einem Jahr
  auf Installationen, die das nie eingestellt haben. Dokumentiert in `.env.example`, auf der
  Konto-Seite und hier; `0` schaltet es ab.
- **Offen:** Der Job läuft in jeder Instanz. Bei mehreren App-Instanzen gegen eine Datenbank purgen
  alle — die Löschungen sind idempotent, also ist das korrekt, aber verschwenderisch. Eine
  Instanz-Wahl (Leader-Election oder ein externer Scheduler) gehört zu M6, wo Betrieb Thema ist.

## Verweise

- `docs/privacy.md` §4 — die beiden Lücken, die diese ADR schließt
- [ADR-0005](0005-persistence-baseline.md) — das Schema und seine Fremdschlüssel (Löschreihenfolge)
- [ADR-0006](0006-auth-and-api-security.md) — Mandantentrennung, „anonym speichert nichts"
- [ADR-0009](0009-web-ui.md) — die „Danger Zone" ist Teil dieser Browser-Oberfläche
- § 132 BAO — siebenjährige Aufbewahrungspflicht für Rechnungen
