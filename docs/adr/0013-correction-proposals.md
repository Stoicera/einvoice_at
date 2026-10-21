# ADR-0013 — Korrekturvorschlag mit Nachweis: Regelwerk entscheidet, nichts wird erfunden

Status: accepted · Datum: 2026-10-22

## Kontext

Der Prüfbericht sagt, *was* fehlt. Für drei häufige Befunde ist auch klar, *wie* es zu beheben ist:
`AT-B2G-01` (Auftragsreferenz, BT-13), `PEPPOL-EN16931-R020` und `R010` (elektronische Adresse von
Verkäufer, BT-34, und Erwerber, BT-49). Ein Vorschlag, der die Datei korrigiert, ist nur dann etwas
wert, wenn ein Prüfer in der Buchhaltung oder IT jede Änderung nachvollziehen kann und wenn nicht der
Vorschlag selbst behauptet, er sei richtig.

## Entscheidung 1 — Drei Herkünfte, sonst nichts

Jede Änderung trägt Regel-ID, BT-Feld und Herkunft des Werts (`ValueSource`):

- **aus dem Dokument** — kopiert aus einem anderen Element derselben Datei, das benannt wird. Die
  Adresse BT-34/BT-49 kommt aus der USt-IdNr. derselben Partei (BT-31/BT-48), wenn sie österreichisch
  (`ATU` + 8 Ziffern → EAS `9914`) oder deutsch (`DE` + 9 Ziffern → EAS `9930`) ist, sonst aus einer
  GLN, die die Partei bereits als Kennung trägt (BT-29/BT-46, `schemeID="0088"`). Andere Länder
  werden nicht geraten.
- **Ihre Eingabe** — die Auftragsreferenz kennt nur die bestellende Dienststelle; sie kommt aus der
  Eingabe (Formular oder `orderReference` an der API).
- **Platzhalter** — der Wert ist weder im Dokument noch eingegeben. Die Änderung wird gelistet, aber
  **nicht** in die Datei geschrieben. Ein eingefügter Platzhalter würde `AT-B2G-01` bestehen (die
  Regel prüft nur das Vorhandensein) und eine Datei erzeugen, die gültig aussieht und falsch ist.

Was das Regelwerk an einem Wert *nicht* prüft, steht an der Änderung: dass die Partei unter der
abgeleiteten Adresse im Peppol-Netz erreichbar ist, dass die Referenz zur Bestellung passt.

## Entscheidung 2 — Das Regelwerk entscheidet

Die korrigierte Datei läuft sofort durch denselben `InvoiceValidator`; dessen Bericht ist Teil des
Vorschlags und wird angezeigt, auch wenn er rot ist. Der Vorschlag behauptet nie selbst, dass er
etwas behebt. Die KI ist nicht beteiligt — der Vorschlag funktioniert mit abgeschaltetem Schalter,
und die vorhandene Erklärung je Befund bleibt eine Erklärung.

## Entscheidung 3 — Änderungen am Text, nicht am Baum

Ein umgeschriebener DOM ändert Einrückung, Attributreihenfolge und Präfixe; der Diff wäre die ganze
Datei. Stattdessen wird an exakten Zeichenpositionen eingefügt (`XmlText`: DOM aus dem gehärteten
Parser für die Frage „welches Element", ein kleiner Scanner für „wo genau"; beide müssen
übereinstimmen, sonst kein Vorschlag). Einrückung und Zeilenende werden aus der Datei übernommen,
Elemente kommen an die Stelle, die das Schema verlangt. Der Unified Diff wird aus den Änderungen
selbst erzeugt. Beleg: die korrigierte `at-b2g-01-missing-order-reference.xml` ist Byte für Byte
`valid/b2g-full.xml`, aus der der Defekt geschnitten wurde.

## Entscheidung 4 — Nichts wird gespeichert, auch nicht für die Eingabe

Der anonyme Prüfer speichert nichts (ADR-0006). Für die Eingabe der Auftragsreferenz schickt der
Bericht die hochgeladene Datei Base64-kodiert im Formular zurück; der Server hält sie nur für die
Dauer der Anfrage. Die Alternative, die Datei in der Sitzung zu halten, wäre Speicherung unter
anderem Namen. Kosten: Dateien über etwa 1,5 MB passen kodiert nicht mehr unter die 2-MB-Grenze; der
zweite Schritt antwortet dann 413. Die Route zählt gegen dasselbe Kontingent wie der Upload.

## Konsequenzen

- Neue Regeln mit eindeutigem Fix kommen als Fall in `RepairProposer` dazu, mit Golden Files in
  `corpus/repaired/` und dem Test, dass die Korrektur grün geprüft wird.
- Ein Fix, dessen Wert nicht eindeutig aus Dokument oder Eingabe folgt, wird nicht gebaut.
