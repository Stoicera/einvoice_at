# Problem types

Every API error is an RFC 9457 `application/problem+json` document. Its `type` is
`<base><slug>`, where the base defaults to `https://einvoice.sebastiankern.net/problems/` and a
self-hosted instance can set its own with `PROBLEM_TYPE_BASE_URI` (see `.env.example`). Following a
`type` URI on the demo host redirects to the matching entry below.

Clients should branch on the slug (the last path segment), not on `title` or `detail`: the slug is
the stable contract, the text is for people. The slugs are emitted by `ApiExceptionHandler` and by
the servlet filters through `Problems` (`app/src/main/java/com/stoicera/einvoice/app/problem/`).

| Slug | Status | Meaning |
|---|---|---|
| [invalid-json](#invalid-json) | 400 | The request body is not valid canonical invoice JSON. |
| [invalid-invoice](#invalid-invoice) | 422 | The JSON parsed, but the invoice violates a domain rule. |
| [unsupported-conversion](#unsupported-conversion) | 400 | The requested source/target format pair cannot be converted. |
| [invalid-finding-index](#invalid-finding-index) | 400 | The finding index does not exist in that report. |
| [multiple-credentials](#multiple-credentials) | 400 | Both `X-Api-Key` and `Authorization: Bearer` were sent. |
| [invoice-not-found](#invoice-not-found) | 404 | No invoice with that id exists for this tenant. |
| [report-not-found](#report-not-found) | 404 | No report with that id exists for this tenant. |
| [api-key-not-found](#api-key-not-found) | 404 | No API key with that id exists for this tenant. |
| [duplicate-invoice](#duplicate-invoice) | 409 | An invoice with the same invoice number already exists for this tenant. |
| [api-key-limit-reached](#api-key-limit-reached) | 409 | The tenant already holds the maximum number of active API keys. |
| [content-too-large](#content-too-large) | 413 | The request body exceeds the configured limit (2 MB by default). |
| [rate-limited](#rate-limited) | 429 | Too many requests from this client; `Retry-After` says when to try again. |
| [internal-error](#internal-error) | 500 | An unexpected error. The response carries no internal detail; the server log does. |
| [ai-explanations-disabled](#ai-explanations-disabled) | 503 | This deployment has AI explanations switched off. Everything else works. |
| [ai-explanation-unavailable](#ai-explanation-unavailable) | 503 | The AI provider produced no explanation. The report itself is unaffected. |

### invalid-json

The body could not be read as the canonical invoice JSON (`POST /api/v1/invoices`). `detail` names
the field or position. Fix the request; retrying it unchanged will fail again.

### invalid-invoice

The JSON is well-formed, but the invoice breaks a domain invariant of the canonical model. `detail`
names the rule.

### unsupported-conversion

`POST /api/v1/convert` was asked for a format pair the platform does not convert.

### invalid-finding-index

`POST /api/v1/reports/{id}/explain` named a finding index outside the report's findings.

### multiple-credentials

A request presented an API key and a bearer token at once. Send exactly one.

### invoice-not-found

The invoice id does not exist for the calling tenant. Another tenant's invoice answers the same way,
on purpose: the API does not reveal whether an id exists elsewhere.

### report-not-found

As `invoice-not-found`, for validation reports.

### api-key-not-found

As `invoice-not-found`, for API keys.

### duplicate-invoice

The tenant already has an invoice with this invoice number. Invoice numbers are unique per tenant.

### api-key-limit-reached

The tenant holds the maximum number of active API keys (`API_KEYS_MAX_ACTIVE_PER_TENANT`, 25 by
default). Revoke one to make room.

### content-too-large

The request body is larger than the configured cap (`MAX_REQUEST_BODY_SIZE`, multipart uploads
included).

### rate-limited

The per-client token bucket for this endpoint is empty. Wait for the number of seconds in
`Retry-After`.

### internal-error

Something failed that the API has no specific answer for. Nothing about the cause is returned; report
it with the time of the request.

### ai-explanations-disabled

The deployment runs with `FEATURES_AI_EXPLANATIONS=false` (the default). Validation, conversion and
reports are unaffected.

### ai-explanation-unavailable

The AI provider did not return an explanation (outage, timeout or refusal). Retry later; the report
is unaffected.

## Framework errors

Errors Spring MVC raises itself (405, 406, 415, a missing multipart part → 400, …) carry the same
base with the HTTP status name as the slug, in lower case with hyphens — for example
`method-not-allowed` or `unsupported-media-type`. A status without a name uses `error`.
