# Glossary / Glossar

German domain terms stay German in code, docs and messages where they are legally precise. English explanation for each.

| Begriff (DE) | English explanation |
|---|---|
| **ebInterface** | Austrian national XML e-invoice standard, maintained by AUSTRIAPRO. Version 6.1 is current; 7.0 announced for Q4 2026. |
| **Auftragsreferenz** | Order reference. Mandatory for invoices to the Austrian federal government (B2G via e-rechnung.gv.at). |
| **Lieferantennummer** | Supplier number assigned by the contracting authority; required in federal B2G invoices. |
| **USt** | Umsatzsteuer — Austrian VAT. Standard rates: 20 %, reduced 13 % / 10 %, plus 0 % and reverse charge. |
| **UID(-Nummer)** | Umsatzsteuer-Identifikationsnummer — VAT identification number (ATU…). |
| **e-rechnung.gv.at** | The Austrian federal portal/web service that receives B2G e-invoices (ebInterface or Peppol BIS). |
| **Prüfbericht** | Validation report. The structured, German-first output of the validation pipeline. |
| **kaufmännisches Runden** | Commercial rounding: round half away from zero (`HALF_UP`). The rounding mode used throughout `Money`. |
| **Kennzahl (KZ)** | Figure/total field referenced in Austrian tax contexts, abbreviated KZ (see SPEC §7: KZ totals); used in total-consistency business rules. |
| **Kleinunternehmer** | Small-business owner exempt from USt per § 6 Abs 1 Z 27 UStG. Modelled as VAT category E (exempt) without a UID. |
