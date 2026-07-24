# Fixtures — provenance

Copies of repo-canonical artefacts, kept here so `app` module ITs never reach out via `../..`
relative paths (Maven runs each module from its own directory, and the artefacts these tests need
live in other modules/`samples`). Regenerate a file by re-copying from its source path when the
base changes; do not hand-edit a copy in place.

| File | Source path | Used by |
|---|---|---|
| `invoice-b2g-sample.ebinterface.xml` | `samples/invoice-b2g-sample.ebinterface.xml` | `ValidateApiIT` — a valid ebInterface 6.1 document (`POST /api/v1/validate` → `valid: true`). |
| `at-b2g-01-missing-order-reference.xml` | `validation/src/test/resources/corpus/invalid/at-b2g-01-missing-order-reference.xml` | `ValidateApiIT` — a well-formed, schema-valid ebInterface 6.1 document that fails the AT-B2G Schematron (`AT-B2G-01`, missing Auftragsreferenz) → `valid: false` with a finding. |
