package com.stoicera.einvoice.validation.stage;

import java.util.Map;
import java.util.Optional;

/**
 * German translations of the OpenPeppol assertion texts, keyed by the rule set's own assertion id.
 *
 * <h2>Why this exists</h2>
 *
 * <p>M4 shipped an honest gap: the OpenPeppol rule sets ship <strong>English message text
 * only</strong>, so a Peppol finding's {@code messageDe} was a German lead-in wrapped around
 * English wording. That violates the spirit of this project's own policy that every finding carries
 * a German message (CLAUDE.md), and it is worst exactly where it matters most — a Buchhalterin
 * whose invoice the Bund rejected. M4's worklog named translating "the rules that actually affect
 * Austrian filers" as deliberate M5 work; this catalog is that work.
 *
 * <h2>Provenance, and what "translated" means here</h2>
 *
 * <p>Each entry is a translation of the assertion text as published in the pinned rule set — {@code
 * CEN-EN16931-UBL.xslt} and {@code PEPPOL-EN16931-UBL.xslt} from {@code phive-rules-peppol 4.4.1},
 * OpenPeppol release {@link PeppolValidationStage#RULE_SET_VERSION} — read off those artefacts, not
 * from memory or a third-party summary. Field names follow the German EN 16931 business-term
 * vocabulary ({@code BT-}/{@code BG-} references are kept verbatim so a reader can look the term
 * up), and Austrian usage where the two differ: {@code USt}, not {@code MwSt}.
 *
 * <p><strong>The English message is never touched.</strong> {@link PeppolValidationStage} keeps the
 * rule set's own wording as {@code messageEn}. This project executes those rules unmodified
 * (ADR-0007) and that principle extends to their text: the translation is an addition for the
 * reader, not a replacement of the official record.
 *
 * <h2>Coverage, stated honestly</h2>
 *
 * <p>The pinned rule set contains a few hundred assertions; this catalog translates {@value
 * #SIZE_NOTE} — every {@code PEPPOL-EN16931-R*} rule (the Peppol-specific layer, which is small and
 * entirely relevant), plus the EN 16931 rules an Austrian filer realistically trips: the
 * mandatory-field and line rules, the total-arithmetic rules, and the four VAT-category families
 * that cover Austria's 20/13/10/0 % rates, {@code Übergang der Steuerschuld} and {@code
 * Steuerbefreiung}. Everything not listed falls back to M4's German-frame-around-English behaviour,
 * which is a worse message but never a wrong one. Translating all several hundred would be a
 * maintenance liability and a fresh source of error, which is the same reason M4 declined to do it
 * in bulk — the difference is that the rules people actually hit are now covered rather than none
 * of them.
 *
 * <p>Adding a translation is safe: {@link PeppolValidationStage} looks up by id and falls back when
 * absent, so no entry can break a document's validity — only how its finding reads.
 */
final class PeppolMessagesDe {

  /** Documentation-only: the entry count named in the class Javadoc, asserted by the stage test. */
  static final String SIZE_NOTE = "78 of them";

  private PeppolMessagesDe() {}

  /**
   * Assertion id to German text. Ordered by rule family rather than alphabetically, so a reader
   * comparing this against the published rule set reads them in the same order the rule set does.
   */
  private static final Map<String, String> CATALOG =
      Map.ofEntries(
          // ---- Peppol BIS Billing 3.0's own layer (PEPPOL-EN16931-UBL.xslt) ----
          Map.entry(
              "PEPPOL-EN16931-R001", "Der Geschäftsprozess muss angegeben werden (ProfileID)."),
          Map.entry(
              "PEPPOL-EN16931-R002",
              "Auf Dokumentenebene ist nur ein Hinweis (BT-22) erlaubt — außer wenn sowohl"
                  + " Erwerber als auch Verkäufer deutsche Organisationen sind."),
          Map.entry(
              "PEPPOL-EN16931-R003",
              "Es muss eine Referenz des Erwerbers (BT-10) oder eine Auftragsreferenz (BT-13)"
                  + " angegeben werden."),
          Map.entry(
              "PEPPOL-EN16931-R004",
              "Die Spezifikationskennung (BT-24) muss den Wert"
                  + " 'urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0'"
                  + " haben."),
          Map.entry(
              "PEPPOL-EN16931-R005",
              "Der Code der USt-Abrechnungswährung (BT-6) muss sich vom Code der"
                  + " Rechnungswährung (BT-5) unterscheiden, wenn er angegeben ist."),
          Map.entry(
              "PEPPOL-EN16931-R007",
              "Der Geschäftsprozess muss das Format"
                  + " 'urn:fdc:peppol.eu:2017:poacc:billing:NN:1.0' haben, wobei NN die"
                  + " Prozessnummer ist."),
          Map.entry("PEPPOL-EN16931-R008", "Das Dokument darf keine leeren Elemente enthalten."),
          Map.entry(
              "PEPPOL-EN16931-R010",
              "Die elektronische Adresse des Erwerbers (BT-49) muss angegeben werden."),
          Map.entry(
              "PEPPOL-EN16931-R020",
              "Die elektronische Adresse des Verkäufers (BT-34) muss angegeben werden."),
          Map.entry(
              "PEPPOL-EN16931-R040",
              "Der Nachlass- bzw. Zuschlagsbetrag muss dem Grundbetrag multipliziert mit dem"
                  + " Prozentsatz geteilt durch 100 entsprechen, wenn Grundbetrag und Prozentsatz"
                  + " vorhanden sind."),
          Map.entry(
              "PEPPOL-EN16931-R041",
              "Der Grundbetrag des Nachlasses bzw. Zuschlags muss angegeben werden, wenn dessen"
                  + " Prozentsatz angegeben ist."),
          Map.entry(
              "PEPPOL-EN16931-R042",
              "Der Prozentsatz des Nachlasses bzw. Zuschlags muss angegeben werden, wenn dessen"
                  + " Grundbetrag angegeben ist."),
          Map.entry(
              "PEPPOL-EN16931-R043",
              "Der Wert von ChargeIndicator (Nachlass/Zuschlag) muss 'true' oder 'false' sein."),
          Map.entry(
              "PEPPOL-EN16931-R044",
              "Ein Zuschlag auf Preisebene ist nicht erlaubt; nur der Wert 'false' ist zulässig."),
          Map.entry(
              "PEPPOL-EN16931-R046",
              "Der Artikel-Nettopreis (BT-146) muss dem Bruttopreis abzüglich des"
                  + " Nachlassbetrags entsprechen, wenn ein Bruttopreis angegeben ist."),
          Map.entry(
              "PEPPOL-EN16931-R051",
              "Alle currencyID-Attribute müssen denselben Wert wie der Code der Rechnungswährung"
                  + " (BT-5) haben — mit Ausnahme des Gesamtbetrags der USt in"
                  + " Abrechnungswährung (BT-111)."),
          Map.entry(
              "PEPPOL-EN16931-R053",
              "Es darf nur eine USt-Gesamtsumme mit Unterpositionen (TaxTotal mit TaxSubtotal)"
                  + " angegeben werden."),
          Map.entry(
              "PEPPOL-EN16931-R054",
              "Wenn ein Code der USt-Abrechnungswährung angegeben ist, darf nur eine"
                  + " USt-Gesamtsumme ohne Unterpositionen angegeben werden."),
          Map.entry(
              "PEPPOL-EN16931-R055",
              "Der Gesamtbetrag der USt (BT-110) und der Gesamtbetrag der USt in"
                  + " Abrechnungswährung (BT-111) müssen dasselbe Vorzeichen haben."),
          Map.entry(
              "PEPPOL-EN16931-R061",
              "Bei Einzugsermächtigung (Direct Debit) muss eine Mandatsreferenz (BT-89)"
                  + " angegeben werden."),
          Map.entry(
              "PEPPOL-EN16931-R080",
              "Auf Dokumentenebene ist nur eine Projektreferenz (BT-11) erlaubt."),
          Map.entry(
              "PEPPOL-EN16931-R100",
              "Je Rechnungsposition ist nur ein in Rechnung gestellter Gegenstand erlaubt."),
          Map.entry(
              "PEPPOL-EN16931-R101",
              "Das Element Dokumentenreferenz darf nur für den Gegenstand einer"
                  + " Rechnungsposition verwendet werden."),
          Map.entry(
              "PEPPOL-EN16931-R110",
              "Das Startdatum des Positionszeitraums muss innerhalb des Rechnungszeitraums"
                  + " liegen."),
          Map.entry(
              "PEPPOL-EN16931-R111",
              "Das Enddatum des Positionszeitraums muss innerhalb des Rechnungszeitraums"
                  + " liegen."),
          Map.entry(
              "PEPPOL-EN16931-R120",
              "Der Nettobetrag der Rechnungsposition (BT-131) muss der in Rechnung gestellten"
                  + " Menge multipliziert mit dem Artikel-Nettopreis (bezogen auf die"
                  + " Preisbasismenge), zuzüglich der Zuschläge und abzüglich der Nachlässe der"
                  + " Position entsprechen."),
          Map.entry(
              "PEPPOL-EN16931-R121",
              "Die Preisbasismenge (BT-149) muss eine positive Zahl größer als Null sein."),
          Map.entry(
              "PEPPOL-EN16931-R130",
              "Der Einheitscode der Preisbasismenge muss dem Einheitscode der in Rechnung"
                  + " gestellten Menge entsprechen."),

          // ---- EN 16931: mandatory document fields (CEN-EN16931-UBL.xslt) ----
          Map.entry("BR-01", "Die Rechnung muss eine Spezifikationskennung (BT-24) enthalten."),
          Map.entry("BR-02", "Die Rechnung muss eine Rechnungsnummer (BT-1) enthalten."),
          Map.entry("BR-03", "Die Rechnung muss ein Rechnungsdatum (BT-2) enthalten."),
          Map.entry("BR-04", "Die Rechnung muss einen Code für die Rechnungsart (BT-3) enthalten."),
          Map.entry(
              "BR-05", "Die Rechnung muss einen Code für die Rechnungswährung (BT-5) enthalten."),
          Map.entry("BR-06", "Die Rechnung muss den Namen des Verkäufers (BT-27) enthalten."),
          Map.entry("BR-07", "Die Rechnung muss den Namen des Erwerbers (BT-44) enthalten."),
          Map.entry("BR-08", "Die Rechnung muss die Postanschrift des Verkäufers enthalten."),
          Map.entry(
              "BR-09",
              "Die Postanschrift des Verkäufers (BG-5) muss einen Ländercode des Verkäufers"
                  + " (BT-40) enthalten."),
          Map.entry("BR-10", "Die Rechnung muss die Postanschrift des Erwerbers (BG-8) enthalten."),
          Map.entry(
              "BR-11",
              "Die Postanschrift des Erwerbers muss einen Ländercode des Erwerbers (BT-55)"
                  + " enthalten."),
          Map.entry(
              "BR-12",
              "Die Rechnung muss die Summe der Nettobeträge der Rechnungspositionen (BT-106)"
                  + " enthalten."),
          Map.entry("BR-13", "Die Rechnung muss die Gesamtsumme ohne USt (BT-109) enthalten."),
          Map.entry("BR-14", "Die Rechnung muss die Gesamtsumme mit USt (BT-112) enthalten."),
          Map.entry("BR-15", "Die Rechnung muss den fälligen Zahlungsbetrag (BT-115) enthalten."),
          Map.entry(
              "BR-16", "Die Rechnung muss mindestens eine Rechnungsposition (BG-25) enthalten."),

          // ---- EN 16931: invoice lines ----
          Map.entry(
              "BR-21",
              "Jede Rechnungsposition (BG-25) muss eine Kennung der Rechnungsposition (BT-126)"
                  + " haben."),
          Map.entry(
              "BR-22",
              "Jede Rechnungsposition (BG-25) muss eine in Rechnung gestellte Menge (BT-129)"
                  + " haben."),
          Map.entry(
              "BR-23",
              "Jede Rechnungsposition (BG-25) muss einen Einheitscode der in Rechnung gestellten"
                  + " Menge (BT-130) haben."),
          Map.entry(
              "BR-24",
              "Jede Rechnungsposition (BG-25) muss einen Nettobetrag der Rechnungsposition"
                  + " (BT-131) haben."),
          Map.entry(
              "BR-25",
              "Jede Rechnungsposition (BG-25) muss einen Artikelnamen (BT-153) enthalten."),
          Map.entry(
              "BR-26",
              "Jede Rechnungsposition (BG-25) muss einen Artikel-Nettopreis (BT-146) enthalten."),
          Map.entry("BR-27", "Der Artikel-Nettopreis (BT-146) darf nicht negativ sein."),
          Map.entry("BR-28", "Der Artikel-Bruttopreis (BT-148) darf nicht negativ sein."),

          // ---- EN 16931: consistency and totals ----
          Map.entry(
              "BR-CO-03",
              "Der Steuerstichtag (BT-7) und der Code für den Steuerstichtag (BT-8) schließen"
                  + " sich gegenseitig aus."),
          Map.entry(
              "BR-CO-04",
              "Jede Rechnungsposition (BG-25) muss einem USt-Kategoriecode (BT-151) zugeordnet"
                  + " sein."),
          Map.entry(
              "BR-CO-09",
              "Die USt-Identifikationsnummern des Verkäufers (BT-31), seines Steuervertreters"
                  + " (BT-63) und des Erwerbers (BT-48) müssen ein Länderpräfix nach ISO 3166-1"
                  + " alpha-2 tragen, an dem das ausstellende Land erkennbar ist (Griechenland"
                  + " darf 'EL' verwenden). Für Österreich lautet das Präfix 'AT'."),
          Map.entry(
              "BR-CO-10",
              "Die Summe der Nettobeträge der Rechnungspositionen (BT-106) muss der Summe aller"
                  + " Positions-Nettobeträge (BT-131) entsprechen."),
          Map.entry(
              "BR-CO-13",
              "Die Gesamtsumme ohne USt (BT-109) muss der Summe der Positions-Nettobeträge"
                  + " (BT-131) abzüglich der Nachlässe auf Dokumentenebene (BT-107) zuzüglich der"
                  + " Zuschläge auf Dokumentenebene (BT-108) entsprechen."),
          Map.entry(
              "BR-CO-15",
              "Die Gesamtsumme mit USt (BT-112) muss der Gesamtsumme ohne USt (BT-109) zuzüglich"
                  + " des Gesamtbetrags der USt (BT-110) entsprechen."),
          Map.entry(
              "BR-CO-16",
              "Der fällige Zahlungsbetrag (BT-115) muss der Gesamtsumme mit USt (BT-112)"
                  + " abzüglich des bereits bezahlten Betrags (BT-113) zuzüglich des"
                  + " Rundungsbetrags (BT-114) entsprechen."),
          Map.entry(
              "BR-CO-25",
              "Ist der fällige Zahlungsbetrag (BT-115) positiv, muss entweder ein"
                  + " Fälligkeitsdatum (BT-9) oder es müssen Zahlungsbedingungen (BT-20)"
                  + " angegeben sein."),
          Map.entry(
              "BR-DEC-14",
              "Die Gesamtsumme mit USt (BT-112) darf höchstens zwei Dezimalstellen haben."),
          Map.entry(
              "BR-DEC-15",
              "Der Gesamtbetrag der USt in Abrechnungswährung (BT-111) darf höchstens zwei"
                  + " Dezimalstellen haben."),
          Map.entry(
              "BR-DEC-16",
              "Der bereits bezahlte Betrag (BT-113) darf höchstens zwei Dezimalstellen haben."),
          Map.entry(
              "BR-DEC-17", "Der Rundungsbetrag (BT-114) darf höchstens zwei Dezimalstellen haben."),

          // ---- EN 16931: VAT category "Standard rated" (Austria: 20 / 13 / 10 %) ----
          Map.entry(
              "BR-S-01",
              "Enthält die Rechnung eine Position, einen Nachlass oder einen Zuschlag mit dem"
                  + " USt-Kategoriecode 'Regelbesteuerung' (S), muss die USt-Aufschlüsselung"
                  + " (BG-23) mindestens einen USt-Kategoriecode (BT-118) 'S' enthalten."),
          Map.entry(
              "BR-S-02",
              "Enthält die Rechnung eine Position mit dem USt-Kategoriecode 'Regelbesteuerung'"
                  + " (S), muss die USt-Identifikationsnummer des Verkäufers (BT-31), seine"
                  + " Steuernummer (BT-32) und/oder die USt-Identifikationsnummer seines"
                  + " Steuervertreters (BT-63) angegeben sein."),
          Map.entry(
              "BR-S-08",
              "Für jeden USt-Satz (BT-119) mit dem Kategoriecode 'Regelbesteuerung' muss die"
                  + " Bemessungsgrundlage (BT-116) der Summe der Positions-Nettobeträge zuzüglich"
                  + " der Zuschläge und abzüglich der Nachlässe auf Dokumentenebene mit demselben"
                  + " Satz entsprechen."),
          Map.entry(
              "BR-S-09",
              "Der USt-Betrag der Kategorie (BT-117) muss bei 'Regelbesteuerung' der"
                  + " Bemessungsgrundlage (BT-116) multipliziert mit dem USt-Satz (BT-119)"
                  + " entsprechen."),
          Map.entry(
              "BR-S-10",
              "Eine USt-Aufschlüsselung (BG-23) mit dem Kategoriecode 'Regelbesteuerung' darf"
                  + " keinen Grund für eine Steuerbefreiung (BT-120/BT-121) enthalten."),

          // ---- EN 16931: VAT category "Reverse charge" (Übergang der Steuerschuld) ----
          Map.entry(
              "BR-AE-01",
              "Enthält die Rechnung eine Position, einen Nachlass oder einen Zuschlag mit dem"
                  + " USt-Kategoriecode 'Übergang der Steuerschuld' (AE), muss die"
                  + " USt-Aufschlüsselung (BG-23) genau einen USt-Kategoriecode (BT-118) 'AE'"
                  + " enthalten."),
          Map.entry(
              "BR-AE-02",
              "Enthält die Rechnung eine Position mit dem USt-Kategoriecode 'Übergang der"
                  + " Steuerschuld' (AE), müssen die USt-Identifikationsnummer des Verkäufers"
                  + " (BT-31), seine Steuernummer (BT-32) und/oder die seines Steuervertreters"
                  + " (BT-63) sowie die USt-Identifikationsnummer des Erwerbers (BT-48)"
                  + " und/oder dessen Registernummer (BT-47) angegeben sein."),
          Map.entry(
              "BR-AE-08",
              "Bei 'Übergang der Steuerschuld' muss die Bemessungsgrundlage (BT-116) der Summe"
                  + " der Positions-Nettobeträge abzüglich der Nachlässe und zuzüglich der"
                  + " Zuschläge auf Dokumentenebene mit dieser Kategorie entsprechen."),
          Map.entry(
              "BR-AE-09",
              "Der USt-Betrag der Kategorie (BT-117) muss bei 'Übergang der Steuerschuld' Null"
                  + " sein."),
          Map.entry(
              "BR-AE-10",
              "Eine USt-Aufschlüsselung (BG-23) mit dem Kategoriecode 'Übergang der"
                  + " Steuerschuld' muss einen Befreiungsgrund-Code (BT-121) mit der Bedeutung"
                  + " 'Reverse charge' oder einen entsprechenden Befreiungsgrund-Text (BT-120)"
                  + " enthalten."),

          // ---- EN 16931: VAT category "Exempt from VAT" (Steuerbefreiung) ----
          Map.entry(
              "BR-E-01",
              "Enthält die Rechnung eine Position, einen Nachlass oder einen Zuschlag mit dem"
                  + " USt-Kategoriecode 'steuerbefreit' (E), muss die Rechnung genau eine"
                  + " USt-Aufschlüsselung (BG-23) mit dem Kategoriecode (BT-118) 'E' enthalten."),
          Map.entry(
              "BR-E-02",
              "Enthält die Rechnung eine Position mit dem USt-Kategoriecode 'steuerbefreit' (E),"
                  + " muss die USt-Identifikationsnummer des Verkäufers (BT-31), seine"
                  + " Steuernummer (BT-32) und/oder die seines Steuervertreters (BT-63)"
                  + " angegeben sein."),
          Map.entry(
              "BR-E-08",
              "Bei 'steuerbefreit' muss die Bemessungsgrundlage (BT-116) der Summe der"
                  + " Positions-Nettobeträge abzüglich der Nachlässe und zuzüglich der Zuschläge"
                  + " auf Dokumentenebene mit dieser Kategorie entsprechen."),
          Map.entry(
              "BR-E-09",
              "Der USt-Betrag der Kategorie (BT-117) muss bei 'steuerbefreit' Null sein."),
          Map.entry(
              "BR-E-10",
              "Eine USt-Aufschlüsselung (BG-23) mit dem Kategoriecode 'steuerbefreit' muss einen"
                  + " Befreiungsgrund-Code (BT-121) oder einen Befreiungsgrund-Text (BT-120)"
                  + " enthalten."),

          // ---- EN 16931: VAT category "Zero rated" (Austria: 0 %) ----
          Map.entry(
              "BR-Z-01",
              "Enthält die Rechnung eine Position, einen Nachlass oder einen Zuschlag mit dem"
                  + " USt-Kategoriecode 'Nullsatz' (Z), muss die USt-Aufschlüsselung (BG-23) genau"
                  + " einen USt-Kategoriecode (BT-118) 'Z' enthalten."));

  /**
   * The German text for {@code assertionId}, or empty when this catalog does not cover that rule.
   */
  static Optional<String> forRule(String assertionId) {
    return Optional.ofNullable(CATALOG.get(assertionId));
  }

  /** Entries held — for the stage test's coverage and non-blank assertions. */
  static Map<String, String> all() {
    return CATALOG;
  }
}
