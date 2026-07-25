# ADR-0008 — PDF-Druckansicht mit Apache PDFBox

Status: akzeptiert · 2026-07-25 · Milestone M4

## Kontext

MILESTONES M4 verlangt eine PDF-Druckansicht, und die Abnahmezeile ist ungewöhnlich konkret:
*„PDF sieht nach Rechnung aus, nicht nach Debug-Ausgabe."* SPEC §1 nennt als Kandidaten OpenPDF,
Apache PDFBox und HTML→PDF via openhtmltopdf, ohne zu entscheiden.

## Entscheidung 1 — Apache PDFBox

| Kandidat | Lizenz | Bewertung |
|---|---|---|
| **Apache PDFBox 3.0.8** | Apache-2.0 | **gewählt** |
| OpenPDF 2.x | LGPL-2.1 / MPL-2.0 | abgelehnt |
| openhtmltopdf | LGPL / Apache | abgelehnt |

**Lizenz gibt den Ausschlag.** Dieses Repository ist Apache-2.0 und ein öffentliches Portfoliostück:
Ein Prüfer, der die Abhängigkeiten durchsieht, soll auf keine Lizenzfrage stoßen, die er sich
beantworten muss. OpenPDF ist als Abhängigkeit rechtlich unproblematisch — aber „unproblematisch,
wenn man es sich überlegt" ist schlechter als „gar keine Frage". PDFBox trägt dieselbe Lizenz wie
das Repository.

**Gegen HTML→PDF** spricht mehr als die Lizenz. Der Weg wäre bequem (Template statt Koordinaten),
zieht aber eine ganze HTML/CSS-Rendering-Schicht plus Template-Engine in ein Modul, dessen einzige
Aufgabe eine Seite ist; er koppelt die Druckansicht an eine zweite Beschreibungssprache; und
openhtmltopdf hängt seinerseits an PDFBox 2.x, also an einer älteren Version derselben Bibliothek,
die hier direkt benutzt wird. Der Vorteil — CSS-Layout — wiegt das für **eine** fest umrissene
Seite nicht auf.

**Preis:** PDFBox ist eine PDF-Bibliothek, keine Layout-Engine. Sie setzt Glyphen auf Koordinaten,
mehr nicht. Deshalb gibt es `PdfCanvas`: Cursor, Seitenumbruch, Zeilenumbruch an einer Stelle, damit
`InvoicePdfRenderer` wie die Beschreibung einer Rechnung lesbar bleibt und nicht wie eine Folge von
`newLineAtOffset`-Aufrufen.

## Entscheidung 2 — Gerendert wird das kanonische Modell, nie ein Format-Baum

Die Druckansicht nimmt `core.invoice.Invoice`. Eine ebInterface-Rechnung und die daraus
konvertierte UBL-Rechnung **sind dieselbe Rechnung** und müssen identisch aussehen; über `core` zu
gehen macht das per Konstruktion wahr, statt darauf zu hoffen, dass zwei Renderer sich einig sind.

ArchUnit hält das fest: `rendering` darf weder `formats-*` noch `mapping` noch `validation` sehen.

## Entscheidung 3 — Standard-14-Schriften, unrepräsentierbare Zeichen werden ersetzt

PDFBox **wirft**, sobald ein Zeichen in der Encoding der Schrift fehlt. Rechnungstext kommt von
außen: Parteiname, Positionsbezeichnung, Zahlungsbedingungen. Ein Renderer, der diesen Text
ungeprüft weiterreicht, macht aus „ein Kunde heißt Ωμέγα" einen HTTP 500 — genau die Klasse
erreichbarer Abstürze, die die M2-Hostile-Review für die Findings-Texte des Validators geschlossen
hat.

Die Standard-14-Schriften benutzen WinAnsiEncoding. Deutsch ist vollständig abgedeckt (Umlaute, ß,
Eurozeichen — durch Test festgehalten, nicht angenommen); Griechisch oder Chinesisch nicht.

**Nicht darstellbare Zeichen werden durch `?` ersetzt, nicht entfernt.** Ein sichtbarer Platzhalter
sagt dem Leser, dass dort etwas stand; stilles Entfernen würde einen Namen still verändern.

**Verworfen: eine vollständige Unicode-TrueType-Schrift einbetten.** Das brächte eine Schriftdatei
samt ihrer Lizenz ins Repository, um Schriftsysteme zu bedienen, die auf einer österreichischen
Rechnung nicht vorkommen — und die Standard-14-Schriften sind in jedem PDF-Betrachter vorhanden,
ganz ohne Einbettung.

## Entscheidung 4 — Die Steueraufstellung ist eine eigene Tabelle

§ 11 UStG verlangt den Steuerbetrag **je Steuersatz**, nicht nur als Summe. Die Aufstellung ist
damit eine rechtliche Eigenschaft der Druckansicht, keine gestalterische Zutat — und sie ist der
Ort, an dem der Befreiungsgrund (BT-120/BT-121) steht, den die Kategorien AE und E führen müssen.

## Entscheidung 5 — Druckansicht, kein ZUGFeRD/Factur-X-Hybrid

Es wird **kein XML ins PDF eingebettet**. Ein Hybriddokument ist ein anderes Artefakt mit eigenen
Konformitätsregeln (PDF/A-3 unter anderem). Eines zu behaupten, ohne sie zu erfüllen, wäre
schlechter, als keines anzubieten. Wenn Hybrid-Rechnungen einmal Ziel sind, sind sie eine eigene
Entscheidung mit eigenem ADR.

## Konsequenzen

**Gut.** Eine Abhängigkeit, gleiche Lizenz wie das Repository, keine Schriftdatei im Repo. Der
Renderer kann an fremdem Text nicht abstürzen. PDF, ebInterface-XML und UBL-XML können nicht
auseinanderlaufen, weil alle drei aus derselben gespeicherten kanonischen Rechnung entstehen.

**Preis.** Layout ist handgeschrieben: Spaltengeometrie, Umbruch und Seitenwechsel sind Code, kein
Stylesheet. Eine Designänderung ist damit eine Codeänderung. Für eine Seite mit festem Aufbau ist
das der richtige Tausch; für ein konfigurierbares Rechnungsdesign wäre es der falsche, und dann
gehört diese Entscheidung neu getroffen.

**Grenze, bewusst.** Kein Logo, keine Farbe, kein Briefkopf. Das kanonische Modell führt nichts
davon, und Branding für eine Rechnung zu erfinden hieße, Inhalt zu erfinden.
