package com.stoicera.einvoice.e2e.load;

import static io.gatling.javaapi.core.CoreDsl.RawFileBody;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

/**
 * The load scenario ENGINEERING_STANDARDS §3 and MILESTONES M5 ask for: the <strong>public
 * validator</strong> under concurrent anonymous upload.
 *
 * <p>That endpoint and no other, because it is the one the platform deliberately exposes to
 * strangers and the one whose cost per request is real: format detection, XSD, then either the
 * hand-written AT-B2G Schematron or a full Peppol XSLT run over the document. Everything else on
 * the platform is behind a credential, so its load profile is a function of how many customers
 * there are; this one is a function of how much attention the page gets.
 *
 * <h2>Run it against a running application</h2>
 *
 * <pre>
 *   docker compose up -d
 *   ./mvnw -pl e2e gatling:test -Pload -Dload.base-url=http://localhost:8080
 * </pre>
 *
 * <h2>THE RATE LIMIT HAS TO BE RAISED, OR THIS MEASURES THE RATE LIMITER</h2>
 *
 * <p>{@code POST /api/v1/validate} is limited to 10 requests per minute per IP by default ({@code
 * app.rate-limit.validate.*}), and a load generator is a single IP. Left at the default, the
 * eleventh request is a {@code 429} and every number after it describes bucket4j rather than the
 * validator. The target must therefore be started with the limit raised — the compose stack takes
 * {@code RATE_LIMIT_VALIDATE_CAPACITY} / {@code RATE_LIMIT_VALIDATE_REFILL_PER_MINUTE} from {@code
 * .env} — and this simulation asserts that <em>no</em> request was rate-limited, so a run against a
 * default-configured instance fails loudly instead of reporting a fast, meaningless p95.
 *
 * <h2>The assertions are a smoke gate, not a performance target</h2>
 *
 * <p>They are set to catch "the validator fell over under trivial concurrency", which is what a
 * portfolio load test can honestly claim. They are deliberately not tuned numbers presented as an
 * SLO: the run happens on whatever machine invoked it, against a single container sharing a host
 * with Postgres, so a p95 from a laptop and one from a CI runner are not the same measurement and
 * neither is a promise to a customer.
 */
public class ValidatorSimulation extends Simulation {

  /** Same knob the plugin passes through; defaults to the compose stack. */
  private static final String BASE_URL =
      System.getProperty("load.base-url", "http://localhost:8080");

  /**
   * The multipart body, pre-built as a raw file rather than assembled by Gatling per request.
   *
   * <p>A {@code formUpload} would have Gatling read and encode the fixture on every request, so the
   * generator would spend its own CPU on multipart encoding and the measurement would include it.
   * The body is a fixed byte sequence, so building it once is both faster and more honest.
   */
  private static final String BODY_FILE = "load/validate-multipart.bin";

  private static final String BOUNDARY = "----einvoiceatloadboundary";

  private final HttpProtocolBuilder httpProtocol =
      http.baseUrl(BASE_URL)
          .acceptHeader("application/json")
          .userAgentHeader("einvoice-at-gatling/1.0")
          // Connection reuse on, as a browser would: measuring TLS/TCP setup per request would be
          // measuring the network, and this endpoint is reached over a keep-alive connection in
          // life.
          .shareConnections();

  private final ScenarioBuilder validateAnonymously =
      scenario("Anonymer Upload auf den öffentlichen Prüfer")
          .exec(
              http("POST /api/v1/validate")
                  .post("/api/v1/validate")
                  .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                  .body(RawFileBody(BODY_FILE))
                  .check(status().is(200))
                  // The endpoint answers 200 with a report even for an invalid document — there is
                  // no
                  // error path from the validator — so a non-200 here is a genuine failure, not a
                  // finding.
                  .check(io.gatling.javaapi.core.CoreDsl.jsonPath("$.report.valid").exists()));

  public ValidatorSimulation() {
    setUp(
            // A ramp rather than a step: 50 users arriving over 30 s is enough to keep several
            // validations concurrent without turning the run into a queueing experiment.
            validateAnonymously.injectOpen(rampUsers(50).during(java.time.Duration.ofSeconds(30))))
        .protocols(httpProtocol)
        .assertions(
            // Every request answered 200 with a report. This is also what makes the rate-limit
            // warning in the class Javadoc enforceable rather than advisory: a 429 fails the
            // status check, so a run against a default-configured instance goes red here instead of
            // reporting a fast, meaningless p95.
            global().failedRequests().count().is(0L),
            global().successfulRequests().percent().is(100.0),
            // p95 inside a bound a working validator has no trouble with. Generous on purpose: the
            // first requests of a run pay for Schematron/XSLT warm-up.
            global().responseTime().percentile3().lt(5_000),
            // No single request hung. Catches a stall that a percentile would average away.
            details("POST /api/v1/validate").responseTime().max().lt(20_000));
  }
}
