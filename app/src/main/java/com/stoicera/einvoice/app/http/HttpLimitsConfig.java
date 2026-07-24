package com.stoicera.einvoice.app.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.unit.DataSize;

/**
 * Registers {@link RequestBodySizeLimitFilter} in the servlet container's chain.
 *
 * <p>Registered as a plain {@link FilterRegistrationBean} rather than inside the Spring Security
 * chain, at {@link Ordered#HIGHEST_PRECEDENCE} — ahead of {@code springSecurityFilterChain} (order
 * {@code -100}) — so an oversized body is refused before authentication rather than after the
 * server has already buffered it. The filter itself is not a {@code @Component}: a bare filter bean
 * would be auto-registered a second time by Boot, and the explicit registration is what pins the
 * ordering that matters here.
 */
@Configuration
public class HttpLimitsConfig {

  @Bean
  FilterRegistrationBean<RequestBodySizeLimitFilter> requestBodySizeLimitFilter(
      @Value("${app.limits.max-request-body-size}") DataSize maxRequestBodySize) {
    FilterRegistrationBean<RequestBodySizeLimitFilter> registration =
        new FilterRegistrationBean<>();
    registration.setFilter(new RequestBodySizeLimitFilter(maxRequestBodySize.toBytes()));
    registration.addUrlPatterns("/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }
}
