# ADR-0009 — Web UI: server-rendered Thymeleaf + htmx, two security filter chains, hand-authored CSS

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

## Entscheidung 2 — Thymeleaf + htmx, kein SPA

Unverändert aus SPEC §1 übernommen und hier nur bestätigt: server-gerendertes HTML mit Thymeleaf,
Interaktivität über htmx. Das ist die Wahl, die ein Java-Haus liest und sofort betreiben kann, und
sie kostet keinen zweiten Build, keinen Node-Toolchain und keine zweite Sprache für die
Validierungslogik.

htmx wird **im Repository mitgeliefert** (`app/src/main/resources/static/vendor/`), nicht von einem
CDN geladen: eine öffentliche Seite, die ein Skript von einem fremden Host zieht, hängt ihre
Verfügbarkeit und ihre Integrität an diesen Host. Version und Herkunft stehen im Header der Datei —
dieselbe Provenienz-Disziplin, die CLAUDE.md für Standards-Artefakte verlangt, angewandt auf eine
JavaScript-Bibliothek.

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
sie ein Build-Schritt für nichts. (Stand M5: ~430 Zeilen, ein Bearbeiter.)

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
Nutzen darin besteht, 40 eigene, im Diff lesbare Zeilen zu ersetzen.

**Auslöser für eine Neubewertung:** die erste Interaktion, die echte Teil-Aktualisierung *braucht* —
etwa live mitlaufende Positionssummen im Wizard oder Inline-Validierung während der Eingabe. Dann wird
htmx eingezogen (das Markup-Vertrag `data-swap="#ziel"` ist bewusst eine Teilmenge von htmx' eigenem,
die Migration ist additiv) und `app.js` gelöscht. Nicht vorher, und ausdrücklich nicht „weil SPEC es
nennt": SPEC §5 ist entsprechend auf „server-gerenderter Wizard" korrigiert, damit die Dokumente sich
nicht widersprechen.

**Was diese Entscheidung nicht ist:** eine Behauptung, dass Tailwind oder htmx schlechte Werkzeuge
wären. Beide sind gut. Sie sind an dieser Stelle, in dieser Größe, für diese Interaktionen nur noch
nicht nötig — und ein Werkzeug, das noch nicht nötig ist, ist Aufwand ohne Gegenwert.

## Konsequenzen

- **Positiv:** Die API bleibt bitweise die von M4; jede Änderung dieser ADR betrifft nur die
  Browser-Hälfte. CSRF-Schutz existiert dort, wo er etwas schützt. Kein Frontend-Build, keine
  Node-Toolchain, `./mvnw verify` bleibt die einzige Build-Anweisung. Lighthouse-freundlich ohne
  Extraarbeit. Jede Seite funktioniert ohne JavaScript — was bei einer Behörden-nahen Zielgruppe kein
  Nebeneffekt, sondern ein Merkmal ist.
- **Negativ:** Zwei Chains sind mehr Konfiguration als eine, und ihre Reihenfolge muss durch Tests
  gesichert bleiben. Das hand geschriebene CSS ist eine Datei, die niemand generiert — sie muss
  klein gehalten werden, sonst wird die Entscheidung von oben rückblickend falsch.
- **Offen:** Lighthouse ≥ 95 auf den öffentlichen Seiten ist in MILESTONES als M6-Abnahme geführt
  und wird dort gemessen, nicht hier behauptet.

## Verweise

- SPEC §5 (Seiten), §1 (Stack, mit M5-Sync zur Tailwind-Abweichung)
- ADR-0006 — Auth & API-Security (die Chain, die unverändert bleibt)
- ADR-0010 — KI-Assistenz (die „Fehler erklären"-Schaltfläche dieser Report-Ansicht)
- ADR-0011 — Aufbewahrung und Löschung (die „Danger Zone" des Dashboards)
