# ADR-0012 — Observability: OTLP für beide Signale, Ports statt Bibliotheken in den Modulen

Status: accepted · Datum: 2026-07-27 · Milestone: M6

## Kontext

ENGINEERING_STANDARDS §5 verlangt OpenTelemetry (Traces, Metrics, Logs) „von Anfang an", einen
konfigurierbaren OTLP-Export, strukturierte Logs und ein Compose-Profil `observability`.
MILESTONES M6 wird konkreter und nennt **Traces über die Pipeline-Stufen** — also über XSD,
Schematron, Business-Rules und den Peppol-Regelsatz, nicht bloß über HTTP-Requests.

Zwei Randbedingungen aus dem eigenen Haus stehen dem im Weg:

- **SPEC §2:** `core` hängt an nichts außer dem JDK, `formats-*`, `mapping` und `validation` kennen
  kein Spring. Die Pipeline-Stufen liegen aber genau dort.
- **SecurityConfig:** alles unter `/actuator/**` außer den Health-Probes ist authentifiziert. Ein
  Scrape-Endpunkt für Prometheus wäre entweder ein Loch in dieser Regel oder ein Credential, das der
  lokale Stack halten müsste.

Bis M5 gab es Micrometer nur als Empfänger der KI-Kosten (`LlmUsageListener` → `MeterRegistry`) und
gar keine Traces.

## Entscheidung 1 — Die Stufen melden sich über einen Port, nicht über Micrometer

`validation` bekommt `ValidationObserver` — ein Interface mit einer generischen Methode, das jede
Stufe umschließt und ihren Rückgabewert unverändert durchreicht. `app` implementiert es einmal
(`MicrometerValidationObserver`) über seine `ObservationRegistry`.

Das ist exakt die Form, die `ai-assist` seit M5 für Token und Kosten benutzt, und zwar aus demselben
Grund: Die Zahlen entstehen in einem Spring-freien Modul, und die Bibliothek, die sie exportiert,
lebt in `app`. Ein `io.micrometer`-Import in `validation` wäre die kürzere Zeile und würde eine
Architekturregel aufweichen, um ein Messproblem zu lösen.

**Konsequenz, die wir bewusst tragen:** Der Observer ist ein *Dekorator*, kein Listener. Er muss den
Supplier genau einmal aufrufen und dessen Wert zurückgeben — ein Implementierungsfehler an dieser
Stelle überspringt eine Validierungsstufe oder führt einen XSLT-Lauf doppelt aus. Der Vertrag steht
im Javadoc, und `ValidationObserverTest` prüft die *exakte* Stufenfolge beider Pipelines, damit eine
später hinzugefügte Stufe ohne Verdrahtung ein Testfehler ist und keine stille Lücke im Trace.

## Entscheidung 2 — Ein Observation-Name pro Familie, die Stufe als Tag

`einvoice.validation.stage{stage=…}` und `einvoice.pipeline{step=…}` statt zehn einzelner Namen.
Auf der Metrik-Seite ergibt das *einen* Timer, den man gruppieren, summieren und alarmieren kann
(„wie lange dauert die Pipeline?" ist sonst nicht formulierbar); auf der Trace-Seite liefert
Micrometers `contextualName` trotzdem einen lesbaren Span-Namen pro Schritt.

Die Tag-Werte kommen aus einer **geschlossenen Menge**: Konstanten auf `ValidationObserver`, ein Enum
`PipelineStep` in `app`. Das ist die direkte Lehre aus dem M5-Review, das den `model`-Tag der
KI-Metriken aus dem Antwort-Body eines Dritten gelesen fand, während `AI_BASE_URL` überallhin zeigen
darf — ein unbegrenzter Tag-Wert ist eine unbegrenzte Zahl von Zeitreihen. `PipelineStep` ist ein
Enum, damit das auch beim nächsten hastig ergänzten Schritt noch gilt.

## Entscheidung 3 — Beide Signale über OTLP, kein `/actuator/prometheus`

Traces **und** Metriken werden gepusht. Prometheus 3 nimmt OTLP direkt entgegen
(`--web.enable-otlp-receiver`), Tempo ohnehin.

Die Alternative wäre der klassische Scrape gewesen: `micrometer-registry-prometheus`, ein
`/actuator/prometheus`-Endpunkt, ein `scrape_config`. Sie kostet mehr, als sie aussieht — der
Endpunkt bräuchte ein Credential für Prometheus oder eine Ausnahme in einer Security-Regel, die
sonst genau eine Zeile lang ist. Und sie wäre ein **zweiter** Mechanismus neben dem Trace-Export,
mit einem zweiten Satz Konfiguration, der mit dem ersten uneins sein kann. ENGINEERING_STANDARDS §5
nennt den OTLP-Export ausdrücklich als zulässige Form.

**Konsequenz:** Wer Prometheus scrapen *will* (viele Betriebsmannschaften wollen das), muss diese
Entscheidung umdrehen — eine Dependency, ein Property, eine Security-Regel. Das ist dokumentiert,
nicht versteckt.

**Kein OpenTelemetry Collector.** Der Stack besteht aus drei Containern, weil die Anwendung mit
beiden Backends direkt spricht; ein Collector wäre ein vierter Prozess, dessen einzige Aufgabe
Weiterleiten ist. Eine echte Installation, die Tail-Sampling, Redaction oder Fan-out braucht, stellt
einen davor — `docs/deployment.md` sagt das.

## Entscheidung 4 — Aus, solange niemand danach fragt

`OTEL_ENABLED` (Default `false`) schaltet beide Exporter und die Histogramm-Berechnung.

Das ist keine Vorsicht, sondern eine Korrektur an Boots Defaults: `management.tracing.enabled` und
`management.otlp.metrics.export.enabled` stehen beide auf `true` mit Ziel `localhost:4318`. Ohne
diesen Schalter würde **jede** Installation, die nie um Observability gebeten hat, dauerhaft Spans
und Metriken in einen Socket schreiben, an dem niemand lauscht, und die Fehlschläge protokollieren.

**Was ausdrücklich *nicht* geschaltet wird:** die `ObservationRegistry` und damit jede Instrumentierung
im Code. Ist Tracing aus, fehlt der Registry nur der Tracing-Handler; eine Observation ist dann ein
paar Feldzuweisungen und kein Span. Die Instrumentierung hat damit in jeder Installation *eine*
Gestalt, und Observability einzuschalten ist eine Konfigurationsänderung, kein anderer Objektgraph.
Ein `@ConditionalOnProperty` an den Beans hätte die instrumentierte und die nicht-instrumentierte
Variante zu zwei verschiedenen Programmen gemacht — und das merkt man erfahrungsgemäß in Produktion.

Das Compose-Profil startet die drei Container, **setzt die Variable aber nicht**: ein Profil
entscheidet, welche *Services* laufen, nicht welche Umgebung ein Service hat. Der Befehl lautet
deshalb `OTEL_ENABLED=true docker compose --profile observability up -d`, und der Kommentar in
`docker-compose.yml` sagt genau das — die stille Wirkungslosigkeit einer gesetzten Variable war in
M5 schon einmal ein Befund (die `RATE_LIMIT_*`-Familie).

## Entscheidung 5 — Perzentil-Histogramme, mit Ober- und Untergrenze

Ein Micrometer-Timer veröffentlicht per Default `count`, `sum` und `max` — **keine Bucket-Grenzen**.
Prometheus bekommt dann einen einzigen `+Inf`-Bucket, und `histogram_quantile` antwortet auf jedes
Quantil mit `NaN`. Für immer.

Das ist nicht theoretisch: Das Grafana-Dashboard wurde zuerst geschrieben und zeigte genau das —
sechs korrekt benannte Serien, jeder Wert `NaN`. Aufgefallen ist es, weil das Dashboard gegen den
laufenden Stack geprüft und nicht bloß abgeliefert wurde.

`management.metrics.distribution.percentiles-histogram` steht deshalb für die drei Meter an, aus
denen je ein Latenz-Panel gezeichnet wird, **und** mit `minimum-expected-value` 1 ms /
`maximum-expected-value` 10 s. Ohne diese Grenzen erzeugt Micrometer rund 70 Buckets pro Meter, und
diese Meter sind nach Stufe bzw. Schritt getaggt — 70 Serien *pro Tag-Wert*. Gemessen nach der
Korrektur: 61 Buckets, p95 peppol 29 ms, parse 1,7 ms, xsd 1,4 ms.

## Entscheidung 6 — Log-zu-Trace-Korrelation fällt ab, wird aber benannt

Micrometer Tracing schreibt `traceId` und `spanId` für die Dauer jedes Spans in die SLF4J-MDC, und
Boots ECS-Format (Profil `prod`) schreibt die MDC als Top-Level-Felder heraus. Damit trägt jede
Logzeile die Id ihres Traces, und Grafana springt von der Zeile in den Wasserfall.

Konfiguriert wird dafür nichts — es ist eine Folge davon, dass beide Funktionen gleichzeitig an sind.
Genau deshalb steht es als Kommentar in `application.yml`: Eigenschaften, die niemand eingeschaltet
hat, schaltet auch niemand bewusst wieder aus.

## Entscheidung 7 — Das Compose-Profil ist ein Entwicklungswerkzeug und wird nicht deployt

Prometheus, Tempo und Grafana hängen alle an `127.0.0.1`, Grafana läuft mit anonymem Admin-Zugang
und ohne Login-Formular. Das ist für einen Stack, der nur die eigenen Zahlen dieses Laptops enthält,
die richtige Reibung — und für eine öffentlich erreichbare Instanz die falsche.
`docs/deployment.md` deployt die drei deshalb ausdrücklich **nicht**, sondern zeigt den OTLP-Export
auf ein Ziel, das der Betreiber wählt (Grafana Cloud, eigener Collector, verwaltetes Tempo).

## Konsequenzen

- Ein Create-Trace liest sich jetzt als: `http post /api/v1/invoices` → `read-canonical-json` →
  `map-ebinterface` → `write-ebinterface` → `validation.stage.parse` → `…xsd` → `…schematron` →
  `…business-rules` → `persist-invoice`. Genau das verlangt M6, und es ist gegen den laufenden Stack
  abgelesen.
- `validation` hat einen zusätzlichen öffentlichen Typ und einen zweiten Konstruktor. Der bestehende
  ist unverändert und bedeutet „nicht messen".
- Wer den Scrape-Ansatz will, dreht Entscheidung 3 um; wer einen Collector will, stellt ihn davor.
  Beides ist eine Konfigurations-, keine Codeänderung — mit Ausnahme der Prometheus-Registry-Dependency.
- Der lokale Stack hält gemessene ~2 600 Zeitreihen. Für einen Portfolio-Stack ist das unkritisch und
  wird hier genannt, damit niemand die Bucket-Grenzen aus Entscheidung 5 später „aufräumt".
