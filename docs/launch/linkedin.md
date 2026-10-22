# LinkedIn — Entwürfe

Status: **Entwurf** (2026-10-15). Nichts davon ist veröffentlicht. Deutsch, Sie-Form, 900–1 300
Zeichen je Post (gezählt ohne diese Hinweise), die erste Zeile trägt die ganze Aussage, der Link
steht im ersten Kommentar, höchstens drei Hashtags. Keine Bezahlwerbung, kein Boost.

## Post A — Profil Sebastian Kern (Autor)

Länge: 1 245 Zeichen.

```text
Eine vom Bund abgelehnte E-Rechnung kommt als Schematron-Ausgabe zurück. einvoice-at sagt Ihnen stattdessen auf Deutsch, welche Regel verletzt ist und wo im Dokument – kostenlos, ohne Konto, quelloffen.

Rechnungen an Bundesdienststellen müssen strukturiert sein: ebInterface oder Peppol BIS Billing 3.0. Wird eine abgelehnt, steht in der Begründung ein englischer Regeltext, ein XPath und eine Assertion-ID. Für die Person in der Buchhaltung, die die Rechnung korrigieren soll, ist das kein Hinweis.

Was der Prüfer macht:
– Format wird automatisch erkannt (ebInterface 6.1 oder Peppol BIS 3.0)
– jeder Befund mit deutscher Meldung, Regel-ID und Fundstelle
– für Peppol das offizielle OpenPeppol-Regelwerk, unverändert ausgeführt
– für ebInterface, wo es kein offizielles Schematron gibt, eigene Regeln, im Repository nachlesbar
– der Upload wird im Arbeitsspeicher geprüft und nicht gespeichert

Was er nicht ist: keine Einreichung, keine amtliche Zulassung, keine Rechts- oder Steuerberatung. Das Urteil von e-rechnung.gv.at bleibt das, das zählt.

Softwarehäuser können die Prüfung über eine REST-API in die eigene Fakturierung einbauen oder alles selbst betreiben (Apache-2.0).

Link im ersten Kommentar.

#ERechnung #Österreich #OpenSource
```

**Erster Kommentar (sofort nach dem Post):**

```text
Prüfer ohne Konto: https://einvoice.sebastiankern.net/validator
Code (Apache-2.0): https://github.com/Stoicera/einvoice_at
```

## Post B — Profil Raphael Lugmayr (Mitgründer, am selben Tag, ein bis zwei Stunden später)

Länge: 1 242 Zeichen. Raphael liest den Text vorher und ändert, was nicht nach ihm klingt.

```text
Mein Mitgründer Sebastian Kern hat einen Prüfer für österreichische E-Rechnungen gebaut, der jeden Fehler auf Deutsch erklärt – und den gesamten Code offengelegt.

Der Hintergrund: Rechnungen an den Bund müssen als ebInterface oder Peppol BIS 3.0 eingereicht werden. Wird eine abgelehnt, ist die Begründung technisch – Regeltext auf Englisch, XPath, Assertion-ID.

einvoice-at macht daraus einen Prüfbericht in Klartext. Ein Beispiel aus dem Prüfer: „Auftragsreferenz fehlt: Rechnungen an Bundesdienststellen müssen eine Auftragsreferenz enthalten." Dazu die Regel-ID und die Stelle im Dokument.

Drei Dinge, die mir an dem Projekt wichtig sind:
– Der Upload wird nicht gespeichert, nur im Arbeitsspeicher geprüft.
– Für Peppol läuft das offizielle Regelwerk unverändert; der Build schlägt fehl, sobald ein neuerer Regelsatz verbindlich wird.
– Alles ist nachprüfbar: Code, Tests und Entscheidungen liegen öffentlich.

Was es nicht ist: keine Einreichung beim Bund, keine Steuerberatung, keine amtliche Zulassung.

Wenn Sie Rechnungen an Bundesdienststellen stellen oder ein Fakturierungsprodukt betreuen: Probieren Sie es mit einer Testrechnung aus und sagen Sie uns, was fehlt.

Link im ersten Kommentar.

#ERechnung #Österreich #OpenSource
```

**Erster Kommentar:**

```text
Zum Ausprobieren: https://einvoice.sebastiankern.net/validator
Quellcode: https://github.com/Stoicera/einvoice_at
```

## Vor dem Posten prüfen

- Prüfer live: Testrechnung im Browser hochladen, Befund erscheint auf Deutsch.
- Keine Aussage über Nutzer- oder Kundenzahlen, keine Behauptung einer Zulassung, keine Rechts- oder
  Steuerberatung andeuten.
- Kommentare am Posttag innerhalb von vier Stunden beantworten, auf dem eigenen Profil jeweils
  die Person, die gepostet hat.
