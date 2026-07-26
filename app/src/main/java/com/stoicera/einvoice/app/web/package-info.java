/**
 * The server-rendered browser surface (SPEC §5): the public validator, the report view and the
 * authenticated dashboard.
 *
 * <p><strong>Boundary contract.</strong> This package holds {@code @Controller}s that return view
 * names, not {@code @RestController}s that return bodies — the {@code /api/v1} contract lives in
 * {@code ..app.api..} and nothing here changes it. Both surfaces reach the same application
 * services ({@code InvoiceService}, {@code ReportService}, {@code ApiKeyService}), which is the
 * point: a second validation or creation path is a second place for a promise like "an anonymous
 * upload is never stored" to break. See ADR-0009.
 */
package com.stoicera.einvoice.app.web;
