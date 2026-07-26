# `validate-multipart.bin` — provenance

A pre-encoded `multipart/form-data` body for `POST /api/v1/validate`, used by
`ValidatorSimulation`.

## Why it is a file and not built at request time

Gatling's `formUpload` re-reads and re-encodes the file for every request, so the load generator
would spend its own CPU on multipart encoding and the measured response time would include it. The
body is a fixed byte sequence, so it is encoded once, here.

## What is in it

One part, named `file`, carrying
`app/src/test/resources/fixtures/at-b2g-01-missing-order-reference.xml` — the same fixture the
integration tests use, chosen because it exercises the full validator path (format detection → XSD →
AT-B2G Schematron) and produces a finding rather than an early exit. Its own provenance is documented
in `app/src/test/resources/fixtures/README.md`.

The boundary is `----einvoiceatloadboundary` and **must** match the `Content-Type` header
`ValidatorSimulation` sends. Line endings inside the multipart framing are CRLF, as RFC 7578
requires — a bare LF makes Tomcat reject the part, which surfaces as a 400 for every request in the
run.

## Regenerating it

Run from the repository root after changing the fixture or the boundary:

```bash
python3 - <<'EOF'
import pathlib
boundary = b"----einvoiceatloadboundary"
fixture = pathlib.Path(
    "app/src/test/resources/fixtures/at-b2g-01-missing-order-reference.xml").read_bytes()
pathlib.Path("e2e/src/test/resources/load/validate-multipart.bin").write_bytes(b"".join([
    b"--", boundary, b"\r\n",
    b'Content-Disposition: form-data; name="file";'
    b' filename="at-b2g-01-missing-order-reference.xml"\r\n',
    b"Content-Type: application/xml\r\n\r\n",
    fixture, b"\r\n",
    b"--", boundary, b"--\r\n",
]))
EOF
```
