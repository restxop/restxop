# restxop

Streamed binary attachments for Spring REST services — MTOM/XOP semantics
over standard `multipart/related` (RFC 2387) with a JSON root part.

Your controllers and clients work with an ordinary typed object whose
binary-valued fields travel as separate streamed MIME parts. Neither side
ever holds an attachment fully in memory: the receiver gets the typed
payload as soon as the root part arrives, while attachment bytes stream
behind it into bounded per-attachment buffers that spill to disk only when
a consumer falls behind.

```
POST /reports         Content-Type: multipart/related; type="application/json";
                                    boundary="…"; start="<root>"

--boundary
Content-ID: <root>
Content-Type: application/json

{"title":"Q3 report","document":{"Include":{"href":"cid:att-1"}}}
--boundary
Content-ID: <att-1>
Content-Type: application/pdf
Content-Disposition: attachment; filename="q3.pdf"

…streamed binary bytes…
--boundary--
```

## Requirements

- Java 17+
- Spring Boot **4.0+** (Jackson 3) or Spring Boot **3.2+** (Jackson 2)
- Servlet stack (WebFlux is out of scope for v1)

| Module | Use it when |
|---|---|
| `restxop-spring-boot-4-starter` | Spring Boot 4.x applications |
| `restxop-spring-boot-3-starter` | Spring Boot 3.2+ applications |
| `restxop-core` | framework-free protocol engine (comes transitively) |
| `restxop-jackson2` / `restxop-jackson3` | serializer adapters (come transitively) |
| `restxop-testkit` | wire-level conformance suites for your own SPI implementations (test scope) |

Both starters expose identical wire behavior, proven by one shared
byte-level conformance suite.

## Quick start

**1. Add the starter for your platform generation** (alongside your usual
web starter — `spring-boot-starter-webmvc` on Boot 4,
`spring-boot-starter-web` on Boot 3):

```xml
<dependency>
  <groupId>dev.restxop</groupId>
  <artifactId>restxop-spring-boot-4-starter</artifactId> <!-- or -3-starter -->
  <version>${restxop.version}</version>
</dependency>
```

**2. Model your payload with `Attachment` fields** (any depth, collections,
maps, inherited fields, nullable):

```java
public class Report {
    public String title;
    public Attachment document;      // dev.restxop.Attachment
}
```

**3. Serve it** — plain Spring MVC, no extra wiring:

```java
@GetMapping(value = "/report", produces = "multipart/related")
Report report() {
    return new Report("Q3 report",
            Attachment.builder(Path.of("/data/q3.pdf"))
                    .contentType("application/pdf")
                    .build());
}

@PostMapping(value = "/reports", consumes = "multipart/related")
void receive(@RequestBody Report report) throws IOException {
    try (InputStream in = report.document.contentStream()) {
        // stream it wherever it belongs; bytes arrive as they are read
    }
}
```

**4. Call it** — the auto-configured builders already carry restxop,
including connection lifecycle management:

```java
Report report = restTemplateBuilder.build()
        .getForObject("http://host/report", Report.class);
// typed fields are usable immediately, while bytes still stream:
try (InputStream in = report.document.contentStream()) {
    Files.copy(in, target);
}
```

That's the whole integration. The client's HTTP connection is held open
until all message bytes have arrived (or the exchange fails or times out),
then released by the library — attachments stay readable from library
buffers afterwards.

## Client integrations

| Client | Integration | Notes |
|---|---|---|
| `RestTemplate` | `RestTemplateBuilder` (auto-customized) | full streaming both directions |
| `RestClient` | `RestClient.Builder` bean (auto-customized) | full streaming both directions |
| OpenFeign | auto-configured `Decoder` when Feign is on the classpath | streaming downloads; Feign itself buffers request bodies, so prefer RestTemplate/RestClient for large uploads |

If you replace a client's request factory, wrap yours in
`DeferredCloseClientHttpRequestFactory` (from the starter's `client`
package) to keep deferred close working.

## The servlet multipart pitfall

Spring's default multipart resolver claims **every** `multipart/*` request
and would consume `multipart/related` bodies before restxop sees them. The
starter guards against this out of the box by installing a
strict-compliance resolver that only handles `multipart/form-data` — form
uploads keep working, attachment messages pass through.

- Keep the default (`restxop.strict-multipart-resolution=true`) when your
  service uses both form uploads and attachment messages.
- If you define your own `MultipartResolver` bean, the guard backs off —
  configure `setStrictServletCompliance(true)` yourself.
- Services with no form uploads can simply set
  `spring.servlet.multipart.enabled=false`.

## Configuration reference

All properties live under the `restxop.` prefix (defaults shown):

| Property | Default | Meaning |
|---|---|---|
| `memory-window-per-part` | `256KB` | per-attachment in-memory buffer before disk overflow |
| `drain.pool-size` | `32` | bounded workers draining incoming messages; caller-runs fallback on saturation |
| `spool.directory` | system temp | overflow file location (must exist and be writable) |
| `spool.max-per-attachment` | `1GB` | overflow cap per attachment; breach fails the exchange |
| `spool.max-per-message` | `2GB` | aggregate overflow cap per message |
| `limits.max-root-part-bytes` | `16MB` | root JSON size bound |
| `limits.max-part-header-bytes` | `64KB` | per-part header block bound |
| `limits.max-parts` | `1000` | part count bound |
| `timeouts.exchange-ttl` | `10m` | total exchange lifetime; expired exchanges are reclaimed and their resources freed |
| `timeouts.read-wait` | `60s` | max consumer wait for streaming progress |
| `read-buffer-size` | `8KB` | transport scan buffer |
| `strict-multipart-resolution` | `true` | servlet resolver guard (see above) |
| `legacy-compat.enabled` | `false` | deprecated `composite/related` interop mode |

## The `Attachment` API

Create (sending side): `Attachment.of(Path | File | byte[] | InputStream)`,
`Attachment.of(InputStream, filename, contentType)`, or
`Attachment.builder(source).filename(…).contentType(…).contentLength(…).build()`.
Jakarta Activation interop: `AttachmentAdapters.fromDataHandler(…)` /
`fromDataSource(…)` / `toDataSource(…)` (optional dependency).

Consume (receiving side):

- `contentStream()` — one lazy stream per attachment, single sequential
  consumption; the same instance on repeated calls. Reading blocks (bounded
  by `timeouts.read-wait`) until bytes arrive. Read attachments in **any
  order** at **any pace** within the configured bounds.
- Closing a stream early (or never opening it) discards the remaining
  bytes; the exchange still completes cleanly.
- `filename()` / `contentType()` — carried via part headers; on the
  receiving side these wait (bounded) for the part headers to arrive.
  Non-ASCII filenames travel RFC 6266 / `filename*` percent-encoded.
- Referencing the **same `Attachment` instance** several times in one
  payload transmits its content once; all references resolve to one shared
  instance on receipt. Null attachment fields round-trip as null.

## Errors

Everything restxop throws is a `RestxopException` carrying the exchange id:

| Exception | Meaning |
|---|---|
| `MalformedMessageException` | wire-format violation (missing parameters, truncation, bad framing) |
| `LimitExceededException` | a configured bound was exceeded — names the limit and its value |
| `ExchangeTimeoutException` | read-wait deadline or exchange TTL expired |
| `AttachmentUnavailableException` | a referenced part never arrived |
| `ExchangeFailedException` | transport/codec failure — delivered to *all* consumers, including ones already blocked |

No failure mode hangs, leaks spool files, or holds connections: every
exchange releases all its resources on success, failure, early close, and
abandonment.

## Observability

- SLF4J logging under `dev.restxop.*`: exchange started/closed (DEBUG),
  failures (ERROR with cause), spool activation and cap breaches
  (INFO/WARN), skipped unreferenced parts (WARN), timeouts (WARN). Log
  lines carry the exchange id, never content.
- Contribute an `ExchangeListener` bean to receive lifecycle events
  (started, payload delivered, attachment consumed, bytes spooled, failed,
  closed) and bridge them to your metrics. Listener exceptions are logged
  and never disturb the exchange.

## Spool security

Overflow files are created with owner-only permissions in
`restxop.spool.directory` and deleted unconditionally by the end of the
exchange. The library does not encrypt spooled data — point the spool
directory at an encrypted or ephemeral volume, or contribute a
`SpoolStorage` bean to supply your own storage strategy.

## Samples

Runnable end-to-end samples live in `restxop-samples/` for both platform
generations: `sample-server-*`, `sample-client-*` (RestTemplate), and
`sample-client-feign-*` (declarative). For example:

```bash
cd restxop-samples/sample-server-boot4
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx256m"

cd restxop-samples/sample-client-boot4
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx256m" \
    -Dspring-boot.run.arguments="--restxop.sample.size=1GB"
```

The client logs the payload's arrival (milliseconds into the transfer),
copies the attachment to disk, verifies its SHA-256, and uploads it back —
with both JVMs capped at 256 MB.

## Legacy compatibility mode (deprecated)

For migration windows against legacy `composite/related` deployments,
`restxop.legacy-compat.enabled=true` additionally:

- **reads** `composite/related` messages (unbracketed Content-IDs, bare
  hrefs, absent Content-Disposition) with byte-exact attachment delivery;
- **writes** the legacy shape (`composite/related`, `<mainpart>` root,
  bare-UUID identifiers) so legacy readers behave no worse than
  legacy-to-legacy.

In compat mode the server also emits the legacy `Response-ID` HTTP header,
and MVC mappings that must accept legacy requests should use
`consumes = "composite/related"` (or omit `consumes`).

**Migration caveats.** Known legacy-reader defects are theirs and remain:
legacy consumers append two bytes to attachment content (their
trailing-CRLF defect) and hang on zero-attachment messages — always include
at least one attachment when targeting a legacy reader, and expect the
+2-byte artifact on the legacy side of a mixed exchange. Reading
legacy-produced messages with restxop is byte-exact (the legacy *writer*
framed correctly; only its reader was defective), verified against captured
legacy wire fixtures (`restxop-testkit`, tag `legacy`).

**Live interop check.** To verify against a running legacy deployment
during a migration window: enable `restxop.legacy-compat.enabled=true` on a
restxop client, call a legacy endpoint that returns a payload with at least
one attachment, and compare the attachment checksum against the source.
The fixture-driven `legacy` test group covers the same wire shapes
offline: `mvn -pl restxop-testkit -Dgroups=legacy verify`.

*Verified 2026-07-05 against a live archived legacy sample server (Java
11):* a compat-mode restxop client (Boot 3 starter, one property flipped)
consumed the legacy `composite/related` response byte-exactly — a
13,264-byte PDF attachment with a SHA-256 identical to the raw wire
capture, the typed payload usable at 50 ms, and the legacy
`name` disposition parameter surfaced as the filename. The legacy sample
exposes no receiving endpoint, so the write direction is verified against
the captured fixtures and the §7 shape assertions rather than live.

## Testing your own extensions

`restxop-testkit` (test scope) ships the wire fixtures and abstract
conformance suites (`RestxopConformanceSuite`, `FidelitySuite`,
`FailureInjectionSuite`) that the starters themselves run. Extending them
against your own `SpoolStorage`/codec implementations is a supported use.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
