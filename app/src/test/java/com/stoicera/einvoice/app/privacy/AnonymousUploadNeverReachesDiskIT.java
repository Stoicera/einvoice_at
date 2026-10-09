package com.stoicera.einvoice.app.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.stoicera.einvoice.app.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The public validator page makes a specific promise, in bold, in German: <em>"Der Upload wird
 * nicht gespeichert. Ihre Datei wird im Arbeitsspeicher geprüft und danach verworfen — kein
 * Prüfbericht, kein Protokolleintrag, <strong>keine Datei auf einem Datenträger</strong>."</em>
 *
 * <p>That last clause was <strong>false</strong>. Spring Boot's {@code
 * spring.servlet.multipart.file-size-threshold} defaults to {@code DataSize.ofBytes(0)} — verified
 * by disassembling {@code MultipartProperties} in spring-boot-servlet 4.1.0, not recalled — and the
 * application set only {@code max-file-size} and {@code max-request-size}. A threshold of zero
 * means <em>every</em> uploaded part is streamed straight to a temporary file. Confirmed against
 * the running stack: during an anonymous upload, an {@code upload_….tmp} file exists on disk under
 * Tomcat's work directory ({@code /tmp/tomcat.<port>.<id>} then {@code
 * work/Tomcat/localhost/ROOT}).
 *
 * <p>Tomcat deletes that file when the request completes, so the invoice does not linger — but an
 * invoice's payload had still been written to a disk, which is exactly what the sentence promised
 * would not happen. For a platform that makes data protection a headline feature, the gap between
 * the claim and the mechanism is the defect, not the retention window.
 *
 * <h2>Why this test asserts a bound and not an absence</h2>
 *
 * <p>The spooled file exists only <em>during</em> the request and is removed before any assertion
 * from outside could run, so "assert the directory is empty afterwards" would pass just as happily
 * with the bug present — a test that cannot fail. What actually makes the promise true is the
 * relationship between two settings: no upload the application is willing to accept may exceed the
 * size it keeps in memory. That is the invariant pinned here, and it is the one a future change
 * would break — raising {@code max-file-size} without raising the threshold silently reintroduces
 * disk spooling for everything in the new range.
 */
@SpringBootTest
class AnonymousUploadNeverReachesDiskIT extends AbstractPostgresIT {

  @Autowired private MultipartProperties multipart;

  @Test
  void noAcceptableUploadIsLargeEnoughToBeSpooledToDisk() {
    long threshold = multipart.getFileSizeThreshold().toBytes();
    long maxFile = multipart.getMaxFileSize().toBytes();

    assertThat(threshold)
        .as(
            "spring.servlet.multipart.file-size-threshold is %d B while max-file-size is %d B, so"
                + " any upload above the threshold is written to a temporary file. The public"
                + " validator page promises 'keine Datei auf einem Datenträger'. Raise the"
                + " threshold to at least max-file-size, or change the promise.",
            threshold, maxFile)
        .isGreaterThanOrEqualTo(maxFile);
  }
}
