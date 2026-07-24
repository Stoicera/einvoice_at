# Glossary / Glossar

German domain terms stay German in code, docs and messages where they are legally precise. English explanation for each.

| Begriff (DE) | English explanation |
|---|---|
| **ebInterface** | Austrian national XML e-invoice standard, maintained by AUSTRIAPRO. Version 6.1 is current; 7.0 announced for Q4 2026. |
| **Auftragsreferenz** | Order reference. Mandatory for invoices to the Austrian federal government (B2G via e-rechnung.gv.at). |
| **Lieferantennummer** | Supplier number assigned by the contracting authority; required in federal B2G invoices. Canonical `Invoice.supplierNumber`, mapped to `Biller/InvoiceRecipientsBillerID` in ebInterface 6.1 (module `mapping`). |
| **USt** | Umsatzsteuer — Austrian VAT. Standard rates: 20 %, reduced 13 % / 10 %, plus 0 % and reverse charge. |
| **UID(-Nummer)** | Umsatzsteuer-Identifikationsnummer — VAT identification number (ATU…). |
| **e-rechnung.gv.at** | The Austrian federal portal/web service that receives B2G e-invoices (ebInterface or Peppol BIS). |
| **Prüfbericht** | Validation report. The structured, German-first output of the validation pipeline. |
| **kaufmännisches Runden** | Commercial rounding: round half away from zero (`HALF_UP`). The rounding mode used throughout `Money`. |
| **Kennzahl (KZ)** | Figure/total field referenced in Austrian tax contexts, abbreviated KZ (see SPEC §7: KZ totals); used in total-consistency business rules. |
| **Kleinunternehmer** | Small-business owner exempt from USt per § 6 Abs 1 Z 27 UStG. Modelled as VAT category E (exempt) without a UID. |
| **Schematron** | XML rule-validation language: business rules expressed as XPath `assert`/`report` statements, layered on top of a schema for checks XSD alone cannot express (e.g. "if category X then field Y is required"). ebInterface ships no official Schematron, so the `validation` module's `AT-B2G-01` rule runs a project-own `.sch` file (see ADR-0004); Peppol BIS 3.0's official Schematron rule sets (via phive-rules) arrive with M4. |
| **SVRL** | Schematron Validation Report Language — the XML output a Schematron engine produces, one `successful-report`/`failed-assert` element per rule per node checked. `SchematronStage` maps each `failed-assert` to a bilingual `Finding` via `SchematronRuleCatalog`. |
| **Storno** | Cancellation/reversal of an invoice. Some senders express a Storno as a standard type-380 commercial invoice with a negative payable amount instead of a proper type-381 credit note; the canonical model's non-negative `payableAmount` invariant rejects that shape as constructed, so import mapping (M4) must normalize a negative-total 380 to a 381 before it reaches `core` (ADR-0003). |
| **Prüfsumme** | Checksum. Used here for the IBAN mod-97 checksum (`AT-B2G-02`, module `validation`): the IBAN's last two check digits encode a remainder-97 computation over the rest of the number, catching a transposed or mistyped digit that a plain length/shape check (the XSD) would miss. |
