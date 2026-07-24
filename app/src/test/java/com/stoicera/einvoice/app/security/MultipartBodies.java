package com.stoicera.einvoice.app.security;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Hand-rolls a minimal {@code multipart/form-data} body for the validator ITs — {@link
 * java.net.http.HttpClient} has no built-in multipart support, and pulling in a client library for
 * one request shape is not worth the dependency.
 */
final class MultipartBodies {

  private MultipartBodies() {}

  /** A ready-to-send multipart request body and the {@code Content-Type} header it needs. */
  record Multipart(String contentType, byte[] body) {}

  /** One file part named {@code partName}, carrying {@code bytes} under {@code filename}. */
  static Multipart singleFilePart(String partName, String filename, byte[] bytes) {
    String boundary = "----einvoice-it-" + UUID.randomUUID();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeAscii(out, "--" + boundary + "\r\n");
    writeAscii(
        out,
        "Content-Disposition: form-data; name=\""
            + partName
            + "\"; filename=\""
            + filename
            + "\"\r\n");
    writeAscii(out, "Content-Type: application/octet-stream\r\n\r\n");
    out.writeBytes(bytes);
    writeAscii(out, "\r\n--" + boundary + "--\r\n");
    return new Multipart("multipart/form-data; boundary=" + boundary, out.toByteArray());
  }

  private static void writeAscii(ByteArrayOutputStream out, String s) {
    out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
  }
}
