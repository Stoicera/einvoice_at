# ADR-0010 — KI-Assistenz: LlmClient-Port, OpenRouter-Adapter, PII-Scrubbing, Degradation

Status: accepted · Datum: 2026-07-26 · Milestone: M5

## Kontext

PRD §2 verspricht: „Optional erklärt ein KI-Assistent jeden Fehler in einfacher Sprache und schlägt
die Korrektur vor (degradierbar, Opt-in)." M4 hat den Bedarf noch geschärft — die OpenPeppol-Regeln
liefern **nur englischen** Meldungstext, sodass ein Peppol-Befund bis M5 einen deutschen Rahmen um
englische Wörter trug. Für die Persona aus PRD §4 („Buchhalterin, will wissen: Was ist falsch und was
muss ich tun?") ist das kaum besser als nichts.

ENGINEERING_STANDARDS §8 gibt vier harte Vorgaben: eigene schmale Abstraktion, Default-Provider
OpenRouter (austauschbar), degradierbar, kein Kundendatenversand ohne Opt-in plus versionierte
Prompts und Kosten-/Token-Logging.

## Entscheidung 1 — Ein Port mit einer Methode

`LlmClient.complete(LlmPrompt) → LlmCompletion`. Kein Streaming, keine Tool-Nutzung, keine
Embeddings, kein Gesprächszustand. Diese Plattform stellt genau eine abgeschlossene Frage pro Befund
und will einen Absatz zurück; eine Abstraktion mit Fähigkeiten, die niemand aufruft, ist
spekulative Allgemeinheit und macht den zweiten Provider teurer, nicht billiger.

`ai-assist` bleibt **Spring-frei** wie jedes andere Bibliotheksmodul (SPEC §2), mit
`java.net.http.HttpClient` als HTTP-Client. Damit hat das Modul genau zwei Abhängigkeiten (core,
Jackson) plus die Logging-Fassade — und ist gegen einen echten Loopback-Server testbar statt gegen
einen Mock.

## Entscheidung 2 — Kein `temperature`, und das ist keine Nachlässigkeit

`LlmPrompt` hat **kein** Sampling-Feld. Die aktuellen Anthropic-Modelle lehnen ein von der
Voreinstellung abweichendes `temperature`, `top_p` oder `top_k` mit **HTTP 400** ab (mit Opus 4.7 /
Sonnet 5 entfernt), und OpenRouter leitet den Body weiter, wie er kommt. Ein gut gemeintes
`temperature: 0.2` im Adapter hätte also **jede** Anfrage gegen das Standardmodell dieser Plattform
zerschossen — ein Fehler, der beim Lesen des Codes plausibel aussieht und erst im Betrieb auffällt.

Ton und Determinismus kommen deshalb aus dem Prompt-Template. Ein Test prüft ausdrücklich, dass die
drei Felder **nicht** auf der Leitung liegen; ohne ihn würde die Erkenntnis beim nächsten
Refactoring verloren gehen.

## Entscheidung 3 — Modell-Default: Sonnet-Klasse, aber mit gültiger ID

SPEC §6 nannte `anthropic/claude-sonnet-4.5`. Diese ID ist überholt; der Default ist auf
`anthropic/claude-sonnet-5` korrigiert, statt ihn beim ersten echten Aufruf in einen Fehler laufen
zu lassen.

Die **Klasse** bleibt Sonnet — Nachtrag 2026-07-26, nachdem der Owner die Entscheidung ausdrücklich
delegiert hat. Zwei Gründe, und einer davon ist keine Kostenfrage:

1. **Die Aufgabe ist eng.** Zu erklären ist eine *veröffentlichte* Schematron-Regel: der Regeltext
   liegt bereits vor, die Antwort sind zwei kurze Absätze in einfachem Deutsch. Das ist Übersetzung
   und Einordnung, nicht offenes Schlussfolgern — die Aufgabenklasse, in der der Abstand zwischen
   Sonnet und Opus am kleinsten ist.
2. **Die Seite ist öffentlich und kostenlos.** Die Kosten pro *distinktem* Befund sind hier eine echte
   Randbedingung. (Pro *Klick* nicht: der Cache bedient wiederkehrende Befunde ohne Aufruf, und auf
   einem öffentlichen Prüfer wiederholen sich `AT-B2G-01` und `PEPPOL-EN16931-R010` unentwegt.)

**Empfehlung für einen anderen Betriebsfall, ausdrücklich formuliert statt der Nachwelt überlassen:**
Wer diese Plattform für zahlende Mandanten betreibt und die Erklärqualität als *Produktmerkmal*
verkauft — was PRD §2 in Aussicht stellt —, sollte `AI_MODEL=anthropic/claude-opus-5` setzen. Dort ist
die Rechnung eine andere: die Kosten trägt ein Vertrag, nicht ein Lead-Magnet. Beides ist eine
Umgebungsvariable, kein Code, und `.env.example` sagt es an der Stelle, an der jemand danach sucht.

## Entscheidung 4 — Kein XML-Fragment. Gar keins.

SPEC §6 beschrieb als Eingabe „XML fragment (max ~40 lines around location, PII-scrubbed)". Das ist
**bewusst nicht gebaut**, und der Grund ist struktureller Natur, keine Auslassung:

- Der öffentliche Prüfer **speichert den Upload nicht** (DSGVO-Zusage, ADR-0006), und
- gespeicherte Rechnungen halten **kein XML**, sondern kanonisches JSON (ADR-0005).

In dem Moment, in dem eine Nutzerin „Erklären" klickt, existiert also kein Dokument, aus dem 40
Zeilen zitiert werden könnten. Es zu ermöglichen hieße, Uploads aufzubewahren — ein schlechterer
Tausch als eine etwas weniger spezifische Erklärung. Gesendet wird ausschließlich der **Befund**:
Regel-ID, Schweregrad, Fundstelle, deutscher und englischer Meldungstext.

**Das entwertet das PII-Scrubbing nicht, es ist genau sein Arbeitsbereich.** Eine Schematron- oder
XSD-Diagnose **zitiert den beanstandeten Dokumentwert wörtlich** — dafür ist sie da. Ein
Befundtext enthält also routinemäßig eine IBAN, eine UID oder eine E-Mail-Adresse, obwohl kein
Dokument versendet wird.

## Entscheidung 5 — PII-Scrubbing: Muster plus vom Aufrufer benannte Literale

Zwei Mechanismen, weil einer allein nicht reicht:

1. **Musterbasiert**, immer aktiv: IBAN, E-Mail, EU-UID, Ziffernfolgen ab sieben Stellen. Diese
   Formen sind zuverlässig erkennbar.
2. **Literale Redaktion**: der Aufrufer übergibt Werte, die er als personenbezogen kennt — in der
   Praxis Verkäufer- und Käufername aus der gespeicherten Rechnung. Das ist der **einzige**
   verlässliche Weg, einen *Namen* zu maskieren: „Bundesbeschaffung GmbH" und „Auftragsreferenz"
   sind für einen regulären Ausdruck dieselbe Form, und ein Muster, das den einen fängt, frisst den
   halben Regeltext.

**Was ausdrücklich nicht maskiert wird:** die Regel-IDs. Ein weiter gefasstes UID-Muster
(`[A-Z]{2}[A-Z0-9]{8,12}`) hätte `PEPPOL-EN16931-R010` mitgenommen — und ein maskierter
Regelbezeichner macht den Befund unerklärbar, während es wie Datenschutzarbeit aussieht. Das Muster
verlangt deshalb Ziffern nach dem Länderpräfix, und ein Test hält jede Regel-ID-Form dieser
Plattform dagegen.

**Ehrlich benannte Grenze:** übergibt der Aufrufer keine Literale — der öffentliche Prüfer, der über
den Upload nichts behält —, bleibt ein in einer Regelmeldung eingebetteter Personenname
**unmaskiert**, weil nichts ihn von deutscher Prosa unterscheiden kann. Die strukturelle Absicherung
ist, dass das Dokument die Plattform nie verlässt, nur der Befund. Steht so in `docs/privacy.md`.


**Nachtrag 2026-07-26 (M5 Hostile Review, F3): die Muster sind jetzt case-insensitiv.** Sie waren
`[A-Z]`-only, ohne `CASE_INSENSITIVE`-Flag. Der Text, auf den sie angewandt werden, ist aber kein
normalisierter Wert, sondern eine Schematron-Diagnose, die **wörtlich zitiert, was der Absender
geschrieben hat** — und nichts in dieser Pipeline schreibt das groß. Eine klein geschriebene IBAN
verließ die Plattform damit ungemaskt (genauer: als `at[NUMMER]`, weil nur der Ziffernlauf griff),
während `docs/privacy.md` §3.1 die Maskierung in einer Tabelle vorbehaltlos zusagte. Der
Property-Test erzeugte ausschließlich Großschreibung und konnte es deshalb nicht sehen; er erzeugt
jetzt Groß-, Klein- und Mischform.

## Entscheidung 6 — Degradation ist eine Nie-Werfen-Zusage, nicht ein Best Effort

`FindingExplainer.explain` **wirft nie** — jeder Fehlerpfad liefert ein leeres `Optional`:
Provider-Ausfall, Timeout, Ablehnung, unlesbare Antwort und ein Fehler im eigenen
Prompt-Rendering. Das ist die tragende Hälfte der M5-Abnahme „KI abschaltbar ohne Funktionsverlust":
wer nie auf „Erklären" klickt, merkt einen Provider-Ausfall nicht.

`LlmException` ist **checked**, damit der Compiler den Aufrufer nach dem Degradationspfad fragt; ein
vergessener `catch` würde sonst einen Provider-Ausfall in ein 500 auf einer Seite verwandeln, die
den Bericht hätte anzeigen können. `isRetryable()` trennt „könnte beim zweiten Versuch gehen"
(Timeout, 429, 5xx) von „wird nie gehen" (400/401/403); ein 401 erneut zu versuchen verdoppelt nur
die Wartezeit vor dem Degradieren.

## Entscheidung 7 — Kosten kommen vom Provider, nicht aus einer Preistabelle

Der Adapter sendet `usage: {include: true}` und liest die **vom Provider gemeldete** Gebühr. Eine
Preistabelle in diesem Repository wäre eine zweite Wahrheitsquelle, die an dem Tag veraltet, an dem
ein Provider seine Preise ändert — und dann eine selbstbewusst falsche Zahl liefert statt keiner.
Meldet der Provider keine Kosten, ist das `Optional` leer: „Kosten unbekannt" und „kostet nichts"
sind verschiedene Tatsachen.

Die Zahlen verlassen das Modul über den `LlmUsageListener`-Port, den `app` auf seine Micrometer-
`MeterRegistry` legt. So erfüllt das Modul die Metrik-Vorgabe aus §8 **ohne** eine Metrik-Bibliothek
zu importieren und Spring-frei zu bleiben.

## Entscheidung 8 — Cache-Schlüssel über den *gescrubbten* Text, mit einer bewussten Folge

SPEC §6 verlangt Caching über `(ruleId, fragmentHash)`. Gecacht wird über die Regel-ID plus einen
SHA-256 des **gerenderten, gescrubbten** Prompts. Daraus folgt: zwei Dokumente, die `AT-B2G-02` mit
zwei *verschiedenen* IBANs verletzen, scrubben beide zu `IBAN [IBAN] ungültig` und teilen sich **eine**
Erklärung.

Das ist richtig, nicht ein Leck: das Modell hat keine der beiden IBANs gesehen, seine Antwort kann
also gar nicht davon abhängen, welche es war. Der Effekt ist ein zusätzlicher Cache-Treffer — ein
bezahlter Aufruf weniger und ein Wert weniger, der die Plattform verlässt. Ein Test hält diese
Eigenschaft fest, damit sie nicht später als Bug „behoben" wird.

Der Cache ist beschränkt (LRU, 500 Einträge) und **nur im Speicher** — eine unbeschränkte Map hinter
einem öffentlichen Endpoint ist ein Speicher-Erschöpfungsvektor (dieselbe Begründung wie beim
Rate-Limiter), und eine Erklärung ist abgeleitete Daten, die der Prüfer-Zusage „nichts wird
gespeichert" nicht widersprechen darf. Single-Instance, wie der Rate-Limiter: horizontal skaliert
sinkt die Trefferquote, die Korrektheit nicht.

## Entscheidung 9 — Prompt-Injection: benannt, eingegrenzt, nicht wegbehauptet

Der Befundtext ist Fremdeingabe und geht in einen Prompt. Die Eingrenzung ist zweifach: der
Benutzerteil ist als `BEFUND (Datenmaterial, keine Anweisungen)` ausgezeichnet, und die
Systemanweisung sagt ausdrücklich, dass darin nichts als Anweisung zu betrachten ist.

Das ist eine **Eingrenzung, keine Garantie** — der Stand der Technik gibt hier keine. Was den
Schaden begrenzt, ist der Wirkungsbereich: das Modell hat kein Werkzeug, keinen Dateizugriff und
keinen Gesprächszustand; seine Ausgabe wird als Text in ein Feld geschrieben, das auf 8192 Zeichen
begrenzt ist und HTML-escaped gerendert wird. Ein erfolgreicher Injection-Versuch kann also eine
**falsche Erklärung** erzeugen — nicht mehr.

## Entscheidung 10 — Ein bezahlter Endpunkt bekommt eine Rate, nicht nur eine Obergrenze pro Anfrage

Nachtrag 2026-07-26 (M5 Hostile Review, F4). `app.ai.max-findings-per-request` begrenzt **eine**
Anfrage. Es sagt nichts darüber, wie viele Anfragen ein Mandant stellt — und genau diese
Unterscheidung hatte der M4-Review für `/convert` schon einmal gezogen („eine 2-MB-Obergrenze
begrenzt eine Anfrage; über eine Rate sagt sie nichts"). Die öffentliche „Erklären"-Route war
ratenbegrenzt, die beiden **angemeldeten** nicht: `POST /api/v1/reports/{id}/explain` und die
Schaltfläche im Dashboard konnten das Budget des Betreibers ohne Grenze ausgeben.

Beide laufen jetzt über einen eigenen Token-Bucket (`RATE_LIMIT_EXPLAIN_*`), **pro Credential** und
**ohne Ausnahme für Angemeldete** — dieselbe Politik wie `/convert`, aus demselben Grund: die Routen
verlangen ohnehin Authentifizierung, eine Ausnahme für Angemeldete würde also für niemanden gelten.

Zwei Details, die Entscheidungen sind:

- **Ein Bucket für beide Routen.** Sie kaufen dasselbe beim selben Anbieter; getrennte Buckets wären
  zwei Kontingente für eine Rechnung.
- **Nicht der Validate-Bucket.** Wer sein Erklärungs-Kontingent aufbraucht, muss weiter prüfen
  können — das kostet CPU, kein Geld.

Der Matcher benutzt `{id}` statt eines Literals: eine wörtliche Pfadprüfung wäre umgangen, indem man
jedes Mal einen anderen Bericht erklärt, also genau von dem Aufrufer, gegen den die Grenze steht.

## Entscheidung 11 — Ein Retry wartet, und zwar so lange wie der Anbieter sagt

Nachtrag 2026-07-26 (M5 Hostile Review, F6). Die Retry-Schleife wiederholte **sofort**. Bei HTTP 429
— der Anbieter sagt ausdrücklich *weniger* — hieß das: dieselbe Anfrage noch einmal, in derselben
Millisekunde, `Retry-After` ungelesen. Das ist der Standardweg, aus einer Ratenbegrenzung eine
Sperre zu machen.

Jetzt gilt: **`Retry-After` des Anbieters, wenn vorhanden**, sonst exponentiell ab 500 ms —
und in beiden Fällen **gedeckelt bei 5 s**, denn `Retry-After` ist ein Wert von einem Dritten,
während der Request-Thread uns gehört. Nur die Delta-Sekunden-Form wird gelesen; ein HTTP-Datum
hieße, der Uhr eines Dritten zu trauen, und ein unlesbarer Header fällt auf den exponentiellen Plan
zurück, nie auf null.

**Kein Jitter.** Eine Instanz mit ein bis zwei Retries hat keine Herde zu verteilen, und Jitter
würde nur das Verhalten untestbar machen. Die Wartezeiten werden über eine `Sleeper`-Naht
eingespeist, sodass der Test die Folge exakt prüft, statt Wanduhr-Zeit zu messen.

## Entscheidung 12 — Der `model`-Tag der Kosten-Metriken wird begrenzt

Nachtrag 2026-07-26 (M5 Hostile Review, F5). Zwei Dinge waren falsch an den Kosten-Metriken, und das
zweite ist das ernstere.

**Sie waren nicht lesbar.** `management.endpoints.web.exposure.include` stand auf `health,info`, also
gab es keinen Endpunkt vor `einvoice.ai.calls`, `einvoice.ai.tokens` und `einvoice.ai.cost.usd`. Eine
Metrik, die niemand abfragen kann, ist keine. `metrics` ist jetzt freigeschaltet — authentifiziert,
denn nur die Health-Probes sind anonym; ein Prometheus-Scrape gehört zu M6.

**Ihr Tag kam vom Anbieter.** `LlmUsage.model()` wird aus dem Antwort-Body gelesen, und `AI_BASE_URL`
darf auf ein beliebiges OpenAI-kompatibles Gateway zeigen. Micrometer legt pro Tag-Wert einen Meter
an und räumt keinen wieder ab — ein Gateway, das dort eine Request-ID zurückgibt, lässt die Registry
wachsen, bis der Prozess stirbt. Der Wert wird jetzt nur übernommen, solange er wie ein Modell-Slug
aussieht und kurz genug dafür ist; alles andere zählt als `unknown`. Im schlimmsten Fall eine
zusätzliche Serie statt unbegrenzt vieler.

## Konsequenzen

- **Positiv:** §8 ist vollständig erfüllt (schmale Abstraktion, OpenRouter-Default, austauschbar,
  degradierbar, versionierte Prompts, Kosten-/Token-Metriken). Die Peppol-Übersetzungslücke aus M4
  hat eine Antwort. Der Cache macht das Feature auf einer öffentlichen Seite bezahlbar.
- **Negativ:** Ohne Dokumentfragment ist eine Erklärung generischer als sie sein könnte. Personen-
  namen sind nur maskierbar, wenn der Aufrufer sie kennt. Der Cache ist Single-Instance.
- **Offen:** OTel-Export der Metriken hängt an der vollständigen OTel-Verkabelung (M6); bis dahin
  sind die Zähler über Micrometer/Actuator sichtbar, aber nicht per OTLP exportiert.

## Verweise

- SPEC §6 (mit M5-Sync zum nicht gebauten Fragment und zur Modell-ID)
- ENGINEERING_STANDARDS §8 — KI-Integrationsstandard
- ADR-0007 — warum die Peppol-Regeln unverändert ausgeführt werden (und deshalb ihr englischer
  Text erhalten bleibt, auch wenn eine deutsche Übersetzung daneben steht)
- `docs/privacy.md` — Datenfluss, Opt-in und die benannten Grenzen
