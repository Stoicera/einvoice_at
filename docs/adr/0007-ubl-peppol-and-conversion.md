# ADR-0007 — UBL/Peppol BIS 3.0 und verlustbehaftete Konvertierung

Status: akzeptiert · 2026-07-25 · Milestone M4

## Kontext

M4 fügt das zweite Rechnungsformat hinzu: **Peppol BIS Billing 3.0** auf Basis von **UBL 2.1**.
Damit entstehen drei Fragen, die M1–M3 nicht stellen mussten:

1. Woher kommen die Peppol-Regeln — schreiben wir sie wie bei ebInterface selbst (ADR-0004), oder
   gibt es offizielle Artefakte?
2. Wie konvertiert man zwischen zwei Syntaxen, die nicht dasselbe ausdrücken können?
3. Was passiert mit Daten, die das Zielformat nicht kennt?

Bis M3 hatte die Plattform genau ein Format. Die Antworten unten sind die, die aus dem
kanonischen Modell als Herzstück (ADR-0003) folgen — nicht die, die am schnellsten zu bauen wären.

## Entscheidung 1 — Die offiziellen OpenPeppol-Regeln werden ausgeführt, nicht nachgebaut

Für ebInterface musste M2 eigene Schematron-Regeln schreiben, weil AUSTRIAPRO schlicht keine
veröffentlicht (ADR-0004 Entscheidung 1). Für Peppol ist die Lage umgekehrt: OpenPeppol
veröffentlicht vollständige EN-16931- und BIS-Schematron-Regelsätze, und `phive-rules-peppol`
liefert sie als fertige Validation Executor Sets.

**Diese Artefakte werden unverändert ausgeführt.** Kein Nachbau, keine Teilmenge, keine
„Interpretation". Das ist genau das Versprechen, das SPEC §7 gegeben hat.

Konsequenz: Die UBL-Pipeline hat **eine** Stufe, wo die ebInterface-Pipeline drei hat. Das VES
enthält XSD, EN-16931-Schematron und Peppol-BIS-Schematron bereits in der richtigen Reihenfolge;
es aufzuteilen hieße, den Regelsatz auseinanderzunehmen — also genau das nicht zu tun, was
„unverändert ausführen" bedeutet.

Konsequenz: Findings tragen die **Regel-IDs des Regelsatzes selbst** (`BR-01`,
`PEPPOL-EN16931-R010`, `UBL-CR-412`), nicht eine flache projekteigene ID. Wer wissen will, warum
eine Rechnung abgelehnt wurde, schlägt die ID direkt in der offiziellen Dokumentation nach.
`RuleIds.PEPPOL_01` ist nur der Fallback für Diagnosen ohne eigene ID.

## Entscheidung 2 — Die Regelsatz-Version wird im Code gepinnt

`PeppolValidation.initStandard` registriert **alle** mitgelieferten Versionen (bei phive-rules
4.4.1: 2025.5, 2025.11, 2026.3, 2026.5). Die Auswahl ist also Sache des Aufrufers, nicht der
Bibliothek.

**Gepinnt wird explizit in `PeppolValidationStage.RULE_SET_VERSION`** (SPEC §10: „Schematron rule
sets evolve → rule-set versions pinned + documented"). Ein Default à la „was die Bibliothek gerade
für aktuell hält" würde bedeuten, dass ein Dependency-Bump still ändert, nach welchen Regeln eine
Rechnung beurteilt wird — dieselbe Rechnung würde gültig oder ungültig, ohne dass sich eine Zeile
dieses Repositories ändert.

Stand 2026-07-25 ist **2025.11** die in Kraft befindliche Version. Der Nachfolger 2026.5 ist bereits
veröffentlicht und wird am **2026-08-17** verbindlich; beides ist aus den Artefakten selbst
abgelesen (`PeppolValidation2026_05.VALID_PER`), nicht von einer Webseite.

### Update-Prozedur (verbindlich)

1. `PeppolValidationStage`: `PeppolValidation2025_11` → die neue Versionsklasse (drei Stellen: die
   Konstante und die beiden VES-Koordinaten).
2. `PeppolValidationStageTest.thePinnedRuleSetIsRegistered` anpassen — der Test schlägt sonst fehl,
   und genau das ist seine Aufgabe.
3. `CorpusTest` laufen lassen. **Erwartungswerte ändern sich unter Umständen, und das ist der
   Sinn der Sache** — ein stiller Wechsel würde bedeuten, dass dieselbe Rechnung plötzlich anders
   beurteilt wird, ohne dass es jemand bemerkt. Änderungen bewusst übernehmen, nicht wegdrücken.
4. `UblEndToEndGenerationTest` laufen lassen: die Muster-Rechnung muss weiterhin ohne Findings
   durchgehen.
5. Datum und Version in diesem ADR und im Worklog nachziehen.

## Entscheidung 3 — Konvertiert wird durch das kanonische Modell, nie Syntax zu Syntax

Eine direkte ebInterface→UBL-Transformation (XSLT oder handgeschrieben) wäre ein zweites,
unabhängiges Verständnis beider Standards — und würde von dem abdriften, das der Rest der Plattform
benutzt.

**Jede Konvertierung läuft `Quelle → kanonisch → Ziel`.** Das kanonische Modell leitet jeden Betrag
ab und prüft ihn nach (ADR-0003), also kann eine Konvertierung keine Summe still verändern. Und was
das Modell nicht darstellen kann, ist sichtbar ein Verlust statt spurlos verschwunden.

Konsequenz: Es braucht **Rückwärts-Mapper** (`EbInterface61ToInvoiceMapper`,
`UblToInvoiceMapper`), und die sind keine exakten Inversen der Vorwärts-Mapper. Was sie beim Lesen
entdecken, steht in Entscheidung 4.

## Entscheidung 4 — Abweichende Summen gewinnen nicht, werden aber auch nicht verschwiegen

Ein fremdes Dokument **nennt** seine Summen; das kanonische Modell **leitet** sie ab. Nichts zwingt
ein fremdes System, so zu rechnen wie wir.

Drei denkbare Reaktionen, zwei davon falsch:

- Die Summen der Quelle übernehmen → macht ADR-0003 zunichte.
- Die Summen der Quelle stillschweigend verwerfen → verbirgt eine echte Diskrepanz.
- **Die abgeleitete Summe verwenden und die Abweichung melden** → gewählt.

Die Meldung ist `CONV-04` mit Severity `ERROR`, und `ConversionReport.isTrustworthy()` wird dadurch
`false`. Bewusst getrennt von `isLossless()`: ein Feld zu verlieren, das das Zielformat gar nicht
kennt, ist normal und macht das Ergebnis nicht unbrauchbar — ein veränderter Betrag schon.

## Entscheidung 5 — BT-34/BT-49 werden ins kanonische Modell aufgenommen, nicht erfunden

Peppol verlangt die elektronischen Adressen beider Parteien. Das kanonische Modell hatte sie nicht.
Zwei Optionen:

- Aus der UID synthetisieren (naheliegend, weil beide oft dieselbe Nummer enthalten) → **abgelehnt**.
  Eine elektronische Adresse ist ein Postfach in einem Netzwerk, eine UID ist keines. Eine Partei
  kann umsatzsteuerlich registriert und über Peppol gar nicht erreichbar sein — oder unter einer
  ganz anderen Kennung. Ein synthetisierter Wert würde ein echtes Dokument an einen falschen
  Empfänger routen. Das ist genau die Art erfundener Daten, die ADR-0003 verbietet.
- **Ins Modell aufnehmen** (EN 16931 hat BT-34/BT-49 ohnehin) → gewählt.

`ElectronicAddress` ist optional: Peppol verlangt sie, ebInterface 6.1 hat kein Element dafür, und
eine kanonische Rechnung muss beide Welten darstellen können. Fehlt sie, meldet das der
Konvertierungsbericht — und danach der Peppol-Schematron selbst.

## Entscheidung 6 — `formats-api` statt `ReadResult` in `core`

Das zweite Format-Adapter-Modul braucht denselben `ReadResult`-Umschlag wie das erste. Nach `core`
verschieben ging nicht: `FormatsEbInterfaceArchitectureTest` verbietet einem `formats-*`-Modul jede
Abhängigkeit auf `core` — ein Format-Adapter ist reine Standards-Anbindung, kanonisches Mapping
gehört nach `mapping`. Diese Regel wird nicht aufgeweicht (CLAUDE.md).

**Neues Modul `formats-api`**, ohne jede Compile-Abhängigkeit, mit `ReadResult` und
`InvoiceFormatStrategy`. Dieselbe Form, die ph-ubl selbst mit `ph-ubl-api` benutzt.

Damit ist auch **ADR-0004 Entscheidung 10 abgeschlossen**, die eine echte Polymorphie-Naht auf M4
vertagt hatte. Sie liegt an zwei Stellen: der gemeinsame Vertrag in `formats-api`, und —
wichtiger — die Dispatch-Entscheidung in `DocumentFormat`. Denn was ein Aufrufer bei einem
unbekannten Upload braucht, ist nicht „gib mir eine Strategy", sondern „sag mir, was das ist";
danach ergeben sich Validator, Reader und Mapper von selbst.

## Konsequenzen

**Gut.** Peppol-Konformität wird von der Autorität selbst bestätigt, in CI, ohne Portal-Upload —
`UblEndToEndGenerationTest` ist damit die stärkste automatisierte Abnahme, die das Repository hat.
Konvertierung kann per Konstruktion keine Beträge verändern. Verluste sind pro Dokument sichtbar
statt als Absatz in der Doku.

**Preis.** Eine Konvertierung kostet vier Schritte statt zwei (lesen, Verluste analysieren,
schreiben, validieren). Der Regelsatz-Pin ist Wartungsarbeit mit Datum. Und der
Konvertierungsbericht ist ehrlich unangenehm zu lesen: er sagt einem Nutzer, dass sein Dokument
Peppol noch nicht genügt. Genau dafür ist er da.

**Offen (bewusst).** Die Peppol-Regelmeldungen sind **englisch**. Der Regelsatz liefert nur
englischen Text, und mehrere hundert Regeln zu übersetzen wäre eine Wartungslast und eine neue
Fehlerquelle; die deutsche Meldung ist daher ein deutscher Rahmen um den offiziellen englischen
Wortlaut. Die Regeln, die österreichische Rechnungssteller wirklich treffen, gezielt zu übersetzen,
ist eigene, bewusste Arbeit — geplant für M5 zusammen mit der KI-Erklärung, die genau dieses
Problem adressiert.
