package com.stoicera.einvoice.app.security;

/**
 * Shared fixture-byte helpers for the {@code app} module's HTTP integration tests — a valid and an
 * AT-B2G-01-triggering ebInterface 6.1 upload, read once per call from the test classpath.
 *
 * <p>Pulled out of {@code ValidateApiIT} (which originally owned {@code validFileBytes()} / {@code
 * invalidFileBytes()} as package-private statics) so that sibling test classes needing the same
 * bytes — {@code AuthMatrixIT}, {@code ReportApiIT} — depend on a neutral utility instead of on
 * another test class; see {@code fixtures/README.md} for the fixtures' provenance.
 */
final class Fixtures {

  private Fixtures() {}

  static byte[] validFileBytes() throws Exception {
    return readFixture("invoice-b2g-sample.ebinterface.xml");
  }

  static byte[] invalidFileBytes() throws Exception {
    return readFixture("at-b2g-01-missing-order-reference.xml");
  }

  private static byte[] readFixture(String name) throws Exception {
    try (var in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
      if (in == null) {
        throw new IllegalStateException("Fixture not found on classpath: " + name);
      }
      return in.readAllBytes();
    }
  }
}
