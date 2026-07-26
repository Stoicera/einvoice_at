# ADR-0009 — Web UI: server-rendered Thymeleaf, two security filter chains, hand-authored CSS

Status: accepted · Datum: 2026-07-26 · Milestone: M5

## Kontext

M5 fügt der Plattform ihre erste Browser-Oberfläche hinzu: die öffentliche Prüfer-Seite (der
Lead-Magnet aus PRD §2), die Report-Ansicht und ein Dashboard mit Login, Rechnungs-Wizard und
API-Key-Verwaltung (SPEC §5). Bis M4 war `app` ausschließlich eine zustandslose REST-API — OAuth2
Resource Server, keine Session, CSRF abgeschaltet, weil es keine Cookie-Session gab, die man hätte
angreifen können (ADR-0006).

Eine Browser-Oberfläche bricht genau diese Annahmen. Drei Entscheidungen waren zu treffen.

## Entscheidung 1 — Zwei Security-Filter-Chains, nicht eine erweiterte

`/api/**` bleibt **unverändert**: zustandslos, Bearer-Token oder `X-Api-Key`, CSRF aus,
`SessionCreationPolicy.STATELESS`. Die Browser-Oberfläche bekommt eine **zweite**
`SecurityFilterChain` mit Session, aktivem CSRF-Schutz und `oauth2Login` (Authorization-Code-Flow
gegen Keycloak).

**Warum nicht eine Chain für beides:** Die beiden Oberflächen haben gegensätzliche Anforderungen.
Ein API-Client soll bei fehlender Authentifizierung ein `401` mit `problem+json` bekommen, ein
Browser einen Redirect zum Login. Eine API-Anfrage darf keine Session anlegen, eine
Formular-Anfrage braucht eine. CSRF-Schutz ist für die Cookie-Session zwingend
(ENGINEERING_STANDARDS §4) und für einen Bearer-Token-Aufruf sinnlos. Eine gemeinsame Chain hätte
das über Bedingungen im Regelwerk gelöst — und jede solche Bedingung ist eine Stelle, an der eine
spätere Änderung versehentlich die falsche Hälfte trifft.

**Konsequenz, die wir bewusst tragen:** Die Reihenfolge (`@Order`) ist load-bearing. Die API-Chain
matcht zuerst und ausschließlich auf `/api/**`, `/actuator/**` und die OpenAPI-Pfade; die UI-Chain
ist die Auffang-Chain. Ein neuer API-Pfad außerhalb von `/api/**` würde in der UI-Chain landen und
dort einen Login-Redirect statt eines `401` liefern. Dagegen gibt es einen Test, keine Konvention.

**Konsequenz für die Mandantenzuordnung:** `CurrentTenant` kannte bisher zwei
Authentifizierungsarten (JWT, API-Key) und lernt eine dritte (`OAuth2AuthenticationToken` aus dem
Login). Sie bildet auf denselben `sub`-Claim und damit auf **denselben Mandanten** ab: wer sich im
Browser anmeldet und wer ein Token für die API holt, ist dieselbe Person, und ein zweiter Mandant
für dieselbe Identität wäre ein Datenleck-Generator. Die `instanceof`-Allow-List-Idiomatik aus
ADR-0006 wird beibehalten — nie `isAuthenticated()` vertrauen, weil Springs
`AnonymousAuthenticationToken` darauf `true` antwortet.

## Entscheidung 2 — Thymeleaf, kein SPA

Server-gerendertes HTML mit Thymeleaf. Das ist die Wahl, die ein Java-Haus liest und sofort
betreiben kann, und sie kostet keinen zweiten Build, keine Node-Toolchain und keine zweite Sprache
für die Validierungslogik.

> **Korrektur 2026-07-26 (M5 Hostile Review, F11).** Dieser Abschnitt lautete ursprünglich
> „Thymeleaf + htmx" und beschrieb, htmx werde **im Repository mitgeliefert**
> (`app/src/main/resources/static/vendor/`), mitsamt Provenienz-Begründung. Nichts davon ist
> passiert: das Verzeichnis existiert nicht, es wurde nie eine Fremddatei eingecheckt, und
> **Entscheidung 5 weiter unten entscheidet ausdrücklich gegen htmx.** Die beiden Abschnitte
> widersprachen sich damit innerhalb derselben ADR — wer nachschlug, warum kein htmx eingesetzt
> wird, las zuerst die widerrufene Entscheidung. Der Text ist hier korrigiert statt kommentiert,
> weil eine ADR, die zwei Dinge zugleich sagt, keine Entscheidung dokumentiert.
>
> Das Prinzip, das der gestrichene Absatz vertrat, gilt unverändert und ist nur gegenstandslos
> geworden: **würde** eine Fremdbibliothek eingezogen, käme sie aus dem Repository und nicht von
> einem CDN. Heute gibt es keine.

## Entscheidung 3 — Hand geschriebenes CSS statt Tailwind-Standalone-CLI

**Das ist eine Abweichung von SPEC §1** und der Grund gehört hierher, nicht in eine Fußnote.

SPEC §1 nennt „Thymeleaf + htmx + Tailwind (standalone CLI)". Die Standalone-CLI ist eine
plattformspezifische Binärdatei von rund 100 MB, die zur Build-Zeit heruntergeladen und ausgeführt
werden muss. Für dieses Repository heißt das konkret:

- ein Maven-Plugin mehr, das eine Binärdatei je Betriebssystem und Architektur auflöst
  (linux-x64, macos-arm64, …) — der Build funktioniert dann auf genau den Plattformen, an die
  jemand gedacht hat;
- ein Netzwerk-Download in jedem CI-Lauf und in jedem Docker-Build, also eine neue externe
  Abhängigkeit für „`./mvnw verify` läuft durch";
- oder alternativ generiertes CSS im Repository, dessen Herkunft niemand prüfen kann und das
  still nicht mehr passt, sobald jemand eine Utility-Klasse in ein Template schreibt, die im
  committeten Output fehlt.

Dem gegenüber steht der Bedarf: eine Handvoll server-gerenderter Seiten mit einem ruhigen,
dunklen Layout. Das sind ein paar hundert Zeilen CSS mit Custom Properties, ohne Build-Schritt,
vollständig lesbar im Diff und gut für Lighthouse (winziges CSS, kein Render-blocking).

**Entscheidung:** eine hand geschriebene `app.css`. Kein Tailwind, kein CSS-Build-Schritt.

**Trade-off, ehrlich benannt:** Tailwind wäre bei deutlich mehr Seiten die bessere Wahl — die
Utility-Konvention skaliert besser als selbst gepflegte Klassennamen, und sie erspart die Disziplin,
die eigene CSS-Datei nicht wachsen zu lassen. Wenn die UI über das M5-Set hinaus wächst, ist der
Wechsel eine eigene, dann gut begründete Entscheidung. Diese ADR ist die Genehmigung, ihn **jetzt**
nicht zu treffen, nicht die Behauptung, dass Tailwind falsch wäre. SPEC §1 ist entsprechend
mit einem M5-Sync-Hinweis versehen, damit die beiden Dokumente sich nicht widersprechen.

## Entscheidung 4 — Die öffentliche Prüfer-Seite speichert nichts, und zwar durch dieselbe Code-Bahn

Der htmx-Upload auf `/validator` geht durch **denselben** `ReportService.validate(bytes,
Optional.empty())`, den die anonyme REST-API benutzt. Der leere `Optional` ist die Zusage „nichts
schreiben" (ADR-0006), und sie wird nicht zweimal implementiert: eine zweite, UI-eigene
Validierungsbahn wäre eine zweite Stelle, an der die DSGVO-Zusage später brechen kann.

Das Rate-Limit gilt entsprechend auch für den UI-Pfad — anonym, per IP, wie beim API-Pendant.
Andernfalls wäre die öffentliche Seite ein unlimitierter Umweg um ein limitiertes Endpoint.

## Entscheidung 5 — Beide Abweichungen bleiben. Mit benannten Auslösern für eine Neubewertung.

Nachtrag 2026-07-26, nachdem die öffentlichen Seiten fertig waren und die Frage anstand, ob vor dem
Dashboard doch noch Tailwind bzw. htmx eingezogen werden soll. Antwort: **nein, beide bleiben** — aber
nicht „auf Dauer", sondern bis zu einem Punkt, der hier konkret benannt wird, damit die Entscheidung
später nicht aus Trägheit weitergilt.

**CSS: hand geschrieben bleibt es.** Der Auslöser für eine Neubewertung ist keine Meinung, sondern eine
Zahl: **sobald `app.css` 700 Zeilen übersteigt oder ein zweiter Mensch regelmäßig daran arbeitet.**
Beides sind die Bedingungen, unter denen eine Utility-Konvention anfängt sich zu bezahlen — vorher ist
sie ein Build-Schritt für nichts. (Stand nach dem M5-Review: **625 Zeilen**, ein Bearbeiter. Die
ursprünglich hier genannten „~430 Zeilen" waren schon beim Schreiben falsch — F12 — und eine Zahl
neben einem Auslöser, die niemand nachmisst, macht den Auslöser wertlos. Der Abstand ist damit
kleiner, als dieser Abschnitt behauptete: 625 von 700.)

**htmx: nein — und der Grund hat sich beim Durchdenken des Dashboards *verstärkt*, nicht abgeschwächt.**
Die Erwartung war, dass das Dashboard mehr Teil-Aktualisierungen braucht und die 40 Zeilen dann wachsen.
Beim Durchgehen der tatsächlichen Interaktionen trifft das nicht zu:

| Dashboard-Interaktion | Braucht Teil-Aktualisierung? |
|---|---|
| Rechnungs-Wizard, Schritt für Schritt | **Nein** — ein server-gerenderter mehrstufiger Formularfluss (POST → nächster Schritt) ist einfacher und funktioniert ohne JavaScript |
| API-Key anlegen / widerrufen | **Nein** — POST, Redirect, neue Seite |
| Mandantendaten löschen (Danger Zone) | **Nein** — POST mit Tipp-Bestätigung, Redirect |
| Listen und Detailseiten | **Nein** — normale Navigation |

Es bleiben genau die **zwei** Interaktionen, die es heute schon gibt: der Prüfer-Upload und
„Fehler erklären". Beide sind fertig und getestet. htmx würde also für Politur eingezogen, nicht für
Bedarf — und dann wäre eine minifizierte Fremddatei zu pflegen und zu verifizieren, deren einziger
Nutzen darin besteht, **rund 20 eigene Zeilen Logik** (66 Zeilen Datei, überwiegend Begründung) im
Diff durch etwas Unlesbares zu ersetzen. Auch diese Zahl stand hier als „40" und war nicht
nachgemessen (F12).

**Auslöser für eine Neubewertung:** die erste Interaktion, die echte Teil-Aktualisierung *braucht* —
etwa live mitlaufende Positionssummen im Wizard oder Inline-Validierung während der Eingabe. Dann wird
htmx eingezogen (das Markup-Vertrag `data-swap="#ziel"` ist bewusst eine Teilmenge von htmx' eigenem,
die Migration ist additiv) und `app.js` gelöscht. Nicht vorher, und ausdrücklich nicht „weil SPEC es
nennt": SPEC §5 ist entsprechend auf „server-gerenderter Wizard" korrigiert, damit die Dokumente sich
nicht widersprechen.

**Was diese Entscheidung nicht ist:** eine Behauptung, dass Tailwind oder htmx schlechte Werkzeuge
wären. Beide sind gut. Sie sind an dieser Stelle, in dieser Größe, für diese Interaktionen nur noch
nicht nötig — und ein Werkzeug, das noch nicht nötig ist, ist Aufwand ohne Gegenwert.

## Entscheidung 6 — Der Browser-Login wird ohne OIDC-Discovery verdrahtet, und Keycloaks Issuer wird festgenagelt

_Nachgetragen 2026-07-26 (M5 Teil 2), nachdem der erste Compose-Rauchtest ergab, dass die Anwendung
überhaupt nicht startet._

Ein Browser-Login in Docker hat zwei Adressen für denselben Identity Provider: der **Browser** muss auf
eine URL geschickt werden, die er erreicht (`localhost:8081`), während die **Anwendung** den Code über
das Compose-Netz einlöst (`keycloak:8080`). Das ist bekannt. Nicht bekannt genug ist, was passiert,
wenn man zusätzlich `spring.security.oauth2.client.provider.<id>.issuer-uri` setzt:

> Spring Boot behandelt einen Provider-`issuer-uri` als **Auftrag zur Discovery beim Start**
> (`ClientRegistrations.fromIssuerLocation`) und ruft `<issuer>/.well-known/openid-configuration` ab —
> unabhängig davon, dass die vier Endpunkte daneben explizit angegeben sind.

M5 Teil 1 hatte genau das getan, mit dem browserseitigen `localhost:8081` als Issuer. Im Container ist
das der eigene Loopback: Connection refused, `clientRegistrationRepository` scheitert, **die gesamte
Anwendung startet nicht** — nicht „der Login geht nicht", sondern alles, öffentlicher Prüfer
inklusive. Der Kommentar in `docker-compose.yml` beschrieb dabei die richtige Lösung („die vier URLs
werden explizit angegeben") und die `issuer-uri`-Zeile darunter hob sie auf.

**Entschieden:** kein Provider-`issuer-uri`. Die vier Endpunkte werden explizit gesetzt — Authorization
browserseitig, Token/JWKS/Userinfo app-seitig — und Keycloak bekommt `KC_HOSTNAME` auf die
browserseitige URL, mit `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`.

Das `KC_HOSTNAME` ist der Teil, der leicht übersehen wird und einen zweiten, stilleren Fehler behebt:
`start-dev` leitet **jede** URL aus dem `Host`-Header der Anfrage ab, **einschließlich des `iss`-Claims
in den Tokens**. Dieselbe Realm nannte sich dem Browser also `localhost:8081` und der Anwendung
`keycloak:8080` — womit das id_token eines Browser-Logins (über den Back-Channel geholt)
`keycloak:8080` getragen hätte, während `OAUTH2_ISSUER_URI` `localhost:8081` erwartet. Nachgemessen,
nicht vermutet: vor dem Pin meldeten die beiden Discovery-Dokumente zwei verschiedene Issuer, danach
denselben, bei weiterhin app-seitigem Token-Endpunkt.

**Preis, offen benannt:** Ohne Provider-`issuer-uri` vergleicht Spring Security den `iss` des
id_tokens nicht gegen einen konfigurierten Wert (`OidcIdTokenValidator` prüft nur, wenn er einen hat).
Der Vertrauensanker bleibt die Signaturprüfung gegen Keycloaks JWKS über einen app-seitigen Back-Channel
— ein Angreifer kann kein Token einschmuggeln, ohne diesen Kanal zu kontrollieren. Es gibt keine
Boot-Eigenschaft für „Issuer setzen, aber nicht auflösen"; wer die Prüfung will, muss eine
`ClientRegistrationRepository`-Bean selbst beisteuern, und Deployment-Verdrahtung gehört nicht in
Produktionscode.

**Festgenagelt:** `OAuth2ClientWiringIT` konfiguriert einen Client mit explizit gesetzten, bewusst
**unerreichbaren** Endpunkten (`*.invalid`, RFC 2606) und behauptet zweierlei — der Kontext startet
(was er nicht könnte, wenn Discovery liefe) und `/app` leitet in den Authorization-Endpunkt um. Der
Testlauf mit wieder eingesetzter `issuer-uri`-Zeile reproduziert die Compose-Kette Fehler für Fehler.
Zusätzlich prüft ein CI-Schritt, dass jede in `.env.example` dokumentierte Variable in
`docker-compose.yml` auch durchgereicht wird — sieben taten das nicht, darunter die komplette
`RATE_LIMIT_*`-Familie, und das war nicht zu sehen, weil eine nicht durchgereichte Variable einfach
nichts tut.

## Entscheidung 7 — Die Browser-Chain bekommt eine eigene Content-Security-Policy

Nachtrag 2026-07-26 (M5 Hostile Review, F8). Springs Standard-Header — `X-Content-Type-Options:
nosniff`, `X-Frame-Options: DENY`, `Cache-Control: no-store` — bleiben auf **beiden** Chains und
genügen einer JSON-API vollständig. Für die Browser-Hälfte genügen sie nicht, und der Grund ist
konkret statt allgemein: diese Seiten rendern **Modell-Ausgabe** und schieben server-gerendertes
Markup per `innerHTML` in die Seite. Genau dort ist eine CSP die Kontrolle, die noch greift, wenn
ein Escaping-Fehler durchkommt.

Die Richtlinie ist so schmal wie diese Oberfläche tatsächlich ist:

```
default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:;
font-src 'self'; connect-src 'self'; form-action 'self'; base-uri 'self'; frame-ancestors 'none'
```

**`default-src 'none'` statt `'self'`**, damit jede Quellenart einzeln freigeschaltet werden muss:
ein künftiges `<img src>` von einem fremden Host ist dann ein Konsolenfehler und keine stille neue
Abhängigkeit. **Kein `'unsafe-inline'`, nirgends** — dafür wurden die zehn `style="…"`-Attribute in
den Templates durch fünf Regeln im Stylesheet ersetzt, denn ein Inline-Style-Attribut ist genau das,
was `style-src 'unsafe-inline'` erzwingt, und eine Richtlinie mit `'unsafe-inline'` ist kaum eine
Richtlinie. Dass das ohne Weiteres ging, ist eine Folge von Entscheidung 2 und 3: es gibt kein
Inline-Skript, keine CDN-Quelle und keine generierte Utility-Klasse.

`Referrer-Policy: no-referrer` kommt aus einem anderen Grund dazu: die Prüfer-Seite ist eine
deutsche SEO-Landingpage mit ausgehenden Links, und ohne diesen Header reist ihre vollständige URL
zu jedem Ziel mit, das jemand anklickt.

**Die API-Chain bekommt beides ausdrücklich nicht.** Eine CSP auf einer JSON-Antwort ist Rauschen,
und Swagger UI lädt eigene Assets, die unter der Seiten-Richtlinie brechen würden. `SecurityHeadersIT`
prüft beide Seiten dieses Kontrasts, nicht nur die Anwesenheit der Header.

## Konsequenzen

- **Positiv:** Die API bleibt bitweise die von M4; jede Änderung dieser ADR betrifft nur die
  Browser-Hälfte. CSRF-Schutz existiert dort, wo er etwas schützt. Kein Frontend-Build, keine
  Node-Toolchain, `./mvnw verify` bleibt die einzige Build-Anweisung. Lighthouse-freundlich ohne
  Extraarbeit. Jede Seite funktioniert ohne JavaScript — was bei einer Behörden-nahen Zielgruppe kein
  Nebeneffekt, sondern ein Merkmal ist.
- **Negativ:** Zwei Chains sind mehr Konfiguration als eine, und ihre Reihenfolge muss durch Tests
  gesichert bleiben — `SecurityChainRoutingIT` tut das seit dem M5-Review; davor stand diese Zusage
  hier und im Javadoc, ohne dass ein Test sie einlöste (F2). Das hand geschriebene CSS ist eine Datei, die niemand generiert — sie muss
  klein gehalten werden, sonst wird die Entscheidung von oben rückblickend falsch.
- **Offen:** Lighthouse ≥ 95 auf den öffentlichen Seiten ist in MILESTONES als M6-Abnahme geführt
  und wird dort gemessen, nicht hier behauptet.

## Verweise

- SPEC §5 (Seiten), §1 (Stack, mit M5-Sync zur Tailwind-Abweichung)
- ADR-0006 — Auth & API-Security (die Chain, die unverändert bleibt)
- ADR-0010 — KI-Assistenz (die „Fehler erklären"-Schaltfläche dieser Report-Ansicht)
- ADR-0011 — Aufbewahrung und Löschung (die „Danger Zone" des Dashboards)
