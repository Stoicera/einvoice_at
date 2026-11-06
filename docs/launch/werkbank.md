---
titel: Was ein grüner Build nicht sagt
untertitel: Vier Befunde aus einem offenen Prüfer für österreichische E-Rechnungen
status: entwurf
stand: 2026-11-06
autor: Sebastian Kern
kanal: Werkbank (Ghost, werkbank.stoicera.com) — Ausgabe am Launchtag von einvoice-at
betreff: Was ein grüner Build nicht sagt
ghost_entwurf: nicht angelegt — Paket J liefert die Ausgabe nur als Datei; der Orchestrator legt den Ghost-Entwurf an (ai/prompts/content-from-shipped-work.md, werkbank-site/docs/mcp.md)
quelle: Repository Stoicera/einvoice_at (PRs #26, #27, #29, #38; CI-Lauf 41430102198; docs/demo.md Zahlen-Spickzettel), Messungen 2026-11-06 in docs/launch/README.md
canonical: diese Ausgabe; Dev.to (docs/launch/devto.md) verweist hierher
woerter: ca. 1 150
---

# Was ein grüner Build nicht sagt

*Vier Befunde aus einem offenen Prüfer für österreichische E-Rechnungen*

**Vorwort.** Heute geht einvoice-at an die Öffentlichkeit: auf Hacker News, in zwei
Fachforen und auf LinkedIn. Das Projekt ist ein Prüfer für E-Rechnungen an den Bund, offen unter
Apache-2.0, und es ist zugleich ein Referenzsystem dafür, wie wir bauen. Diese Ausgabe erzählt
nicht, was das Werkzeug kann — das steht im Repository. Sie erzählt vier Momente, in denen alle
Tests grün waren und trotzdem etwas falsch war. Ich finde, das sind die lehrreicheren Geschichten.

## Worum es geht

Rechnungen an österreichische Bundesdienststellen müssen strukturiert sein, als ebInterface oder
als Peppol BIS Billing 3.0. Wird eine abgelehnt, kommt die Begründung als Schematron-Ausgabe
zurück: ein englischer Regeltext, ein XPath, eine Assertion-ID. Für mich ist das lesbar. Für die
Person in der Buchhaltung, die die Rechnung korrigieren soll, ist es eine Wand.

einvoice-at prüft, erzeugt und konvertiert beide Formate und antwortet auf jeden Befund mit einer
deutschen Meldung, der Regel-ID und der Fundstelle im Dokument. Der öffentliche Prüfer braucht kein
Konto; der Upload wird im Arbeitsspeicher geprüft und nicht gespeichert. Für Peppol läuft das
offizielle Regelwerk von OpenPeppol unverändert, an einer im Code festgelegten Version. Für
ebInterface gibt es kein offizielles Schematron — die österreichischen Geschäftsregeln sind deshalb
im Repository geschrieben und mit einem Korpus aus gültigen und absichtlich kaputten Dokumenten
belegt, ein Fehler je Datei.

Was es nicht ist: keine Einreichung, kein Peppol-Zugangspunkt, keine Buchhaltung, keine Rechts-
oder Steuerberatung, von niemandem zertifiziert. Das Urteil, das zählt, fällt bei e-rechnung.gv.at.

## 1. Eine gelöschte Regel, die wir weiter übersetzt haben

OpenPeppol hat den Regelsatz 2026.5 am 17. August 2026 verbindlich gemacht. Wir haben am
7. August umgestellt, zehn Tage vorher, weil ein „gültig" von uns nach dem Stichtag gegen Regeln
geprüft hätte, die der Empfänger nicht mehr anwendet. Der Korpus blieb grün, und das war richtig:
Die geänderten Regeln betreffen niederländische und dänische Schemata.

Aber die Regel BR-CO-25 war ohne Nachfolger gelöscht worden, und unser deutscher
Meldungskatalog hat sie weiter „übersetzt". Kein Test schlug an, weil der Abdeckungstest gegen eine
Liste verglich, die jemand in die Testdatei getippt hatte. Zwei deutsche Meldungen waren dabei
nicht nur ungenau, sondern falsch geworden.

Seither liest ein Test die zulässigen Regel-IDs aus den mitgelieferten Regeldateien. Ein zweiter
liest die Veröffentlichungsdaten, die OpenPeppol in das Artefakt schreibt, und lässt den Build an
dem Tag scheitern, an dem ein neuerer Regelsatz den festgelegten ablöst — mit Version und Datum in
der Fehlermeldung. Vorher stand dieser Termin in einer Checkliste und in meinem Kopf. Es ist der
eine Stolperdraht im Repository, der auslöst, ohne dass jemand vorher etwas ändert.

## 2. Ein grünes Repository wurde rot, ohne dass eine Zeile geändert wurde

Ein Build fixiert Abhängigkeiten. Er fixiert nicht das Urteil der Welt über sie. Der
OWASP-Dependency-Check in unserer Pipeline wurde spätestens am 24. September 2026 rot, wegen
Schwachstellen, die gegen Bibliotheken veröffentlicht wurden, die wir nicht angefasst hatten. Die
Antwort war ein Upgrade, keine Ausnahme: Spring Boot 4.1.0 auf 4.1.1, Tomcat und Netty mit, nur die
pom.xml. Seit 8. Oktober grün.

Am 2. November ist es wieder passiert, in einem geplanten Lauf, der nur den Cache wärmen soll: Die
Swagger-Oberfläche bündelt eine JavaScript-Bibliothek, DOMPurify 3.4.12, gegen die zwei Advisories
mit der höchsten Einstufung veröffentlicht wurden; behoben ab 3.4.17. Die Bibliothek, die uns die
Oberfläche liefert, verwaltet eine Version, die das alte DOMPurify enthält — und ihre neueste
Fassung enthält immer noch 3.4.16. Das haben wir nicht in einem Changelog nachgelesen, sondern
durch Entpacken der Jars geprüft. Also ist das Webjar jetzt getrennt auf 5.34.1 festgelegt. Eine
Stunde hat gekostet, dass die Bibliothek den Pfad der Oberfläche aus ihrer eigenen Versionsangabe
baut; ein Integrationstest, der die Seite aufruft, hat den 404 gefunden.

Die Regel für Ausnahmen bleibt: Eine Suppression ist die Behauptung, dass eine Schwachstelle hier
nicht gilt. Sie nennt ihren Grund und, wo möglich, ein Ablaufdatum, damit sie neu begründet werden
muss.

## 3. Ein toter Knopf, den kein Test sehen konnte

Die Startseite hatte einen Knopf „REST-API ansehen", jede Seite einen Menüpunkt „API". In der
Produktion, wo die API-Dokumentation damals abgeschaltet war, führten beide auf eine 404-Seite.

Es gab einen Test für den abgeschalteten Zustand. Er prüfte, dass die Dokumentation nicht mehr
ausgeliefert wird. Sein eigener Kommentar sagte, die Dokumentation sei „wirklich weg, nicht nur
nicht verlinkt" — die zweite Hälfte wurde nie geprüft und war nie wahr. Gefunden haben wir es durch
Klicken auf der echten Seite in einem echten Browser. Behoben testgetrieben in beide Richtungen:
Die Links verschwinden, wenn die Dokumentation aus ist, und erscheinen, wenn sie an ist.

Eine grüne Testsuite beweist, dass der Code tut, was die Tests sagen. Sie kann nicht bemerken, dass
eine Seite auf etwas zeigt, das es nicht gibt.

## 4. Unser eigenes Datenschutzversprechen stimmte nicht ganz

Der öffentliche Prüfer sagt, der Upload werde nicht gespeichert: kein Bericht, kein Logeintrag,
keine Datei auf einer Platte. Der letzte Teil war falsch. Spring Boot schreibt Uploads ab null
Bytes in eine temporäre Datei, wenn man die Schwelle nicht setzt. Wir hatten die Größenlimits
gesetzt, nicht die Schwelle. Beobachtet auf der laufenden Instanz bei einem Upload von 1,9 MB; die
Datei wurde am Ende der Anfrage gelöscht.

Für eine Seite, die Datenschutz als Eigenschaft nennt, ist die Lücke zwischen Versprechen und
Mechanismus der Fehler — nicht, wie lange die Datei gelebt hat. Die Schwelle liegt jetzt über dem
größten akzeptierten Upload; alles, was die Anwendung annimmt, bleibt im Arbeitsspeicher. Nachgemessen
auf dieselbe Weise: vorher eine temporäre Datei, nachher keine.

## Was jedes Mal geholfen hat

Das Muster ist in allen vier Fällen dasselbe: Die Tests prüften, was jemand aufgeschrieben hatte,
nicht, was außerhalb des Codes wahr ist — einen Regelsatz, eine Schwachstellendatenbank, eine
Seite im Browser, eine Platte.

Geholfen hat, die Prüfung aus der Quelle abzuleiten statt aus einer Liste; zeitgebundene Fakten
den Build brechen zu lassen statt sie in ein Dokument zu schreiben; das Produkt im Browser zu
benutzen; ein Versprechen am laufenden System zu messen. Und eine gebündelte Bibliothek wie jede
andere Abhängigkeit zu behandeln: auspacken und nachsehen.

Vor dem heutigen Tag haben wir den Schnellstart aus einem frischen Klon noch einmal durchlaufen
lassen, auf einem Rechner, der das Projekt nie gebaut hatte: 312 Sekunden bis zur gesunden
Instanz, Image-Build inklusive. Das Einzige, was brach, war eine Datenbank, die auf diesem
Rechner schon auf Port 5432 lauschte. Steht jetzt in der Anleitung.

---

Der Code liegt offen unter https://github.com/Stoicera/einvoice_at, der Prüfer ohne Konto unter
https://einvoice.sebastiankern.net/validator. Wenn Sie Rechnungen an Bundesdienststellen stellen
oder ein Fakturierungsprodukt betreuen und wissen wollen, ob das in Ihre Abläufe passt: Erstgespräch,
20 Minuten, online — https://termin.stoicera.com/sebastian. Anfragen auch an office@stoicera.com.
