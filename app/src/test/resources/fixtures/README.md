# Fixtures — provenance

Copies of repo-canonical artefacts, kept here so `app` module ITs never reach out via `../..`
relative paths (Maven runs each module from its own directory, and the artefacts these tests need
live in other modules/`samples`). Regenerate a file by re-copying from its source path when the
base changes; do not hand-edit a copy in place.

| File | Source path | Used by |
|---|---|---|
| `invoice-b2g-sample.ebinterface.xml` | `samples/invoice-b2g-sample.ebinterface.xml` | `Fixtures.validFileBytes()` (`ValidateApiIT`, `ReportApiIT`, `AuthMatrixIT`) — a valid ebInterface 6.1 document (`POST /api/v1/validate` → `valid: true`). |
| `at-b2g-01-missing-order-reference.xml` | `validation/src/test/resources/corpus/invalid/at-b2g-01-missing-order-reference.xml` | `Fixtures.invalidFileBytes()` (`ValidateApiIT`, `ReportApiIT`) — a well-formed, schema-valid ebInterface 6.1 document that fails the AT-B2G Schematron (`AT-B2G-01`, missing Auftragsreferenz) → `valid: false` with a finding. |
| `invoice-b2g-sample.ubl.xml` | `samples/invoice-b2g-sample.ubl.xml` | `ObservabilityIT` (M6) — the Peppol BIS Billing 3.0 twin of the ebInterface sample. `app` needed a UBL document on its own test classpath for the first time here: the validator takes a *different pipeline* for UBL (one Peppol stage, no XSD/Schematron/business-rule stages), so proving both pipelines are traced needs a document of each format. |
