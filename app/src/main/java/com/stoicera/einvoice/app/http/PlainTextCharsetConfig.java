package com.stoicera.einvoice.app.http;

import java.util.Map;
import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Serves {@code .txt} static files as {@code text/plain;charset=utf-8} instead of Boot's bare
 * {@code text/plain}.
 *
 * <p>RFC 9116 §3 requires {@code /.well-known/security.txt} to carry the charset parameter {@code
 * utf-8}; without it RFC 2046 makes the default US-ASCII, and a non-ASCII byte in the file would be
 * mis-declared. The static resource handler takes the media type from the servlet container's mime
 * mappings before anything else, so the mapping is set there rather than per file. {@code
 * robots.txt} gets the same parameter, which is harmless. {@code PublicWebIT} asserts it.
 */
@Configuration
public class PlainTextCharsetConfig {

  @Bean
  WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> plainTextUtf8MimeMapping() {
    return factory ->
        factory.addMimeMappings(new MimeMappings(Map.of("txt", "text/plain;charset=utf-8")));
  }
}
