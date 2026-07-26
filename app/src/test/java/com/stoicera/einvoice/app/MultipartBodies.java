package com.stoicera.einvoice.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Hand-rolls a minimal {@code multipart/form-data} body for the validator ITs — {@link
 * java.net.http.HttpClient} has no built-in multipart support, and pulling in a client library for
 * one request shape is not worth the dependency.
 *
 * <p>Moved up from {@code ..app.security} to the test root in M5: the browser-surface ITs post the
 * same shapes as the API ITs (a file upload, and now a set of form fields), and a second copy of
 * this would be a second place for the boundary handling to drift.
 */
public final class MultipartBodies {

  private MultipartBodies() {}

  /** A ready-to-send multipart request body and the {@code Content-Type} header it needs. */
  public record Multipart(String contentType, byte[] body) {}

  /** One file part named {@code partName}, carrying {@code bytes} under {@code filename}. */
  public static Multipart singleFilePart(String partName, String filename, byte[] bytes) {
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

  /**
   * Plain form fields plus an optional file part — the shape a browser sends when a {@code <form
   * enctype="multipart/form-data">} carries both hidden inputs (the CSRF token, a finding's fields)
   * and a file chooser.
   *
   * @param fields field name to value, in insertion order
   * @param fileField the file part's field name, or {@code null} for a fields-only body
   * @param filename the file part's filename
   * @param bytes the file part's content
   */
  public static Multipart form(
      Map<String, String> fields, String fileField, String filename, byte[] bytes) {
    String boundary = "----einvoice-it-" + UUID.randomUUID();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Map.Entry<String, String> field : fields.entrySet()) {
      writeAscii(out, "--" + boundary + "\r\n");
      writeAscii(out, "Content-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n");
      // UTF-8 field values: a German finding message is full of umlauts, and a browser sends them
      // as
      // UTF-8 bytes without any per-part charset header.
      writeAscii(out, "\r\n");
      out.writeBytes(field.getValue().getBytes(StandardCharsets.UTF_8));
      writeAscii(out, "\r\n");
    }
    if (fileField != null) {
      writeAscii(out, "--" + boundary + "\r\n");
      writeAscii(
          out,
          "Content-Disposition: form-data; name=\""
              + fileField
              + "\"; filename=\""
              + filename
              + "\"\r\n");
      writeAscii(out, "Content-Type: application/octet-stream\r\n\r\n");
      out.writeBytes(bytes);
      writeAscii(out, "\r\n");
    }
    writeAscii(out, "--" + boundary + "--\r\n");
    return new Multipart("multipart/form-data; boundary=" + boundary, out.toByteArray());
  }

  private static void writeAscii(ByteArrayOutputStream out, String s) {
    out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
  }
}
