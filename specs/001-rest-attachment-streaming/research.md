# Phase 0 Research: REST Attachment Streaming Library (restxop)

**Date**: 2026-07-04
**Spec**: [spec.md](spec.md)

All Technical Context items were resolved during the reverse-engineering
analysis and clarification sessions; this document records the load-bearing
technical decisions, their rationale, and rejected alternatives.

## R1. Read-path concurrency model: eager-chase (sole v1 read model)

**Decision** (revised 2026-07-04 with project owner; supersedes the
earlier pull-model draft): preserve the legacy library's design identity —
**drain the transport at full network speed, always; let consumers read
the same bytes live by chasing the drain through a bounded memory window;
spill to disk only for the overflow** — rebuilt on sound primitives.

- `read()` parses the root part synchronously on the caller's thread,
  deserializes it, wires attachment placeholders, and returns the payload
  object. Zero-attachment messages complete here.
- A **drain task** (bounded worker pool) then consumes the remainder of
  the message at wire speed, writing each attachment part into its own
  **chase buffer** (R2). The upstream connection is released the moment
  the drain reaches end-of-message — regardless of consumer pace
  (frees the sender's thread/connection; the orchestrator-relay use case).
- Consumers read attachment streams from their chase buffers, chasing the
  drain: memory-to-memory with zero disk I/O while they keep pace; from
  the overflow file transparently when they lag; in any order (FR-017).
- **Caller-runs fallback**: if the drain pool is saturated, the first
  consumer read drives the drain on its own thread — saturation degrades
  gracefully instead of deadlocking, and this leaves a seam for a future
  explicit on-demand mode (config flag, no wire/API change) if a
  disk-free relay case ever demands it. Not in v1 scope.
- A single shared scheduled reaper enforces the exchange TTL for
  abandoned exchanges (FR-023).

**Failure/timeout semantics**: drain failure poisons the exchange and
signals every consumer blocked on a chase-buffer condition immediately
(FR-021 for already-blocked readers); every consumer await carries the
read-wait deadline; the drain enforces the exchange TTL. No
`Thread.isAlive()` anywhere; all signaling is lock/condition based
(FR-022). Per-request codec state travels in exchange scope, never
ThreadLocals.

**Rationale**: the legacy defects were implementation flaws
(forked-JDK pipe code, missing notify, liveness heuristics, semaphore
handshake), not architecture flaws. The eager-drain/live-chase/
spool-overflow architecture is a deliberate, production-validated design
property: payload usable at root arrival, downstream streaming starts
immediately, upstream freed at wire speed, disk as pressure relief only.
A pull-on-demand model was evaluated and rejected because it parks the
socket during consumer think-time (LB/idle-timeout exposure) and holds
the *sender's* write path hostage to consumer pace.

**Alternatives considered**:
- *Pull/on-demand parsing (no background threads)*: rejected as v1 model
  for the reasons above; survives as the caller-runs degradation path.
- *Eager drain into spool-then-read (no live chase)*: rejected — forces
  disk/latency cost on prompt consumers; loses the memory-only fast path.
- *Dual configurable modes in v1*: rejected — doubles the streaming test
  matrix with no current use case for the second mode.

## R2. Chase buffer: bounded memory window with disk-overflow spill

**Decision**: One **chase buffer** per attachment part — a
single-writer (drain) / single-reader (consumer) structure guarded by one
lock + condition:

- Writer fills a bounded in-memory ring window
  (`memory-window-per-part`); the reader chases it memory-to-memory.
- When the reader lags and the window fills, the writer spills subsequent
  bytes to an overflow file obtained from the `SpoolStorage` SPI
  (clarification #3); the reader drains window → file → window
  transparently, in byte order. Disk is touched only on overflow.
- Reads while the writer is active await on the condition with the
  read-wait deadline; writer completion, exchange failure (poison), and
  TTL expiry all signal the condition. Bulk (array) reads and writes
  only — no per-byte locking (constitution Additional Constraints).
- Reader early-close or abandonment flips the buffer to discard mode:
  remaining bytes of that part are drained and dropped — no buffering,
  no spool-cap accrual.
- Default `SpoolStorage`: plain files, owner-only permissions
  (`Files.createTempFile` + POSIX `OWNER_READ|OWNER_WRITE`), registered
  with the exchange for unconditional deletion at close (FR-018).
- Caps enforced at the writer: `spool.max-per-attachment` and the
  per-message aggregate; breach → exchange fails (`LimitExceededException`).

**Rationale**: this is the legacy `SpoolingPipedInputStream` concept —
its purpose was sound — re-engineered to eliminate its specific defects:
missing `notifyAll` on receive (poll-latency), broken bulk `write(byte[])`,
`Thread.isAlive()` liveness heuristics, no failure signaling to blocked
readers, and no cleanup on abandonment. A standard monitor pattern with
deadline-carrying awaits covers all of it (~300 lines incl. overflow
management), with dedicated stress tests per constitution Principle VI.

**Alternatives considered**: commons-io `DeferredFileOutputStream`
(rejected: write-fully-then-read semantics — no concurrent chase);
java.util.concurrent pipes/queues per byte-chunk (workable but the
window+file state machine still has to exist; a purpose-built buffer is
simpler to verify); memory-mapped ring (complexity without requirement).

## R3. Boundary scanning: buffered chunk scanning, delimiter includes leading CRLF

**Decision**: Read the transport through a fixed-size buffer (default
8 KiB) and scan for the full MIME delimiter `CRLF + "--" + boundary`
(accepting bare `LF` framing on read per FR-007) using a KMP prefix-table
match that carries state across buffer refills. Bytes preceding a
confirmed delimiter are emitted in bulk (array writes, not per byte).
The delimiter's leading line break is framing and never emitted (FR-005,
fixing the legacy trailing-CRLF defect). After the closing delimiter
(`--` suffix), remaining epilogue is drained and discarded.

**Rationale**: legacy KMP core logic was verified correct but operated
unbuffered and byte-at-a-time with per-byte synchronized writes — the main
throughput killer (SC-006). Matching the full framed delimiter also fixes
both fidelity defects (trailing CRLF, boundary-like content splitting).

**Alternatives considered**: Boyer-Moore-Horspool (fine too; KMP retained
because streaming state carry-over is simplest and the legacy tests provide
adversarial fixtures); reusing a MIME library (see R4).

## R4. MIME parsing: purpose-built parser in core (no MIME library dependency)

**Decision**: Implement part-header parsing (bounded, case-insensitive,
RFC-822-style `Name: value` lines, CRLF/LF tolerant) and Content-Type
parameter parsing (quoted strings, case-insensitive parameter names,
angle-bracket/`cid:` tolerant identifier handling) in `restxop-core`.

**Rationale**: candidates each fail a constraint: Jakarta Mail/Angus
(pulls the whole activation stack, buffering-oriented API), Apache Mime4j
(streaming-capable but a heavyweight dependency for the subset needed),
Spring's multipart machinery (form-data oriented, servlet-coupled — the
very thing FR-028 avoids). The needed subset (delimiter scan, header
block, parameter parsing) is small, must be streaming-first and
allocation-light, and its edge cases are exactly what the wire-level test
suite (Principle VI) pins down.

## R5. Serializer-driven discovery: Jackson context attributes, dual-generation adapters

**Decision**: `restxop-core` defines a `RootPartCodec` SPI:
`writeRoot(Object, OutputStream, AttachmentCollector)` and
`readRoot(InputStream, TargetType, AttachmentResolver)`.
Two adapter modules implement it:

- `restxop-jackson2` (`com.fasterxml.jackson.*`, for Boot 3.x)
- `restxop-jackson3` (`tools.jackson.*`, for Boot 4.x)

Each registers a serializer/deserializer for the library's `Attachment`
type. Per-call state (collector/resolver) travels via **Jackson context
attributes** (`ObjectWriter.withAttribute` / `DeserializationContext
.getAttribute`) — never ThreadLocals. The serializer assigns each distinct
`Attachment` instance a Content-ID on first encounter (identity map →
duplicate references share one ID, FR-012) and emits the Include stub;
the deserializer creates exchange-backed `Attachment` instances registered
with the resolver by ID (same ID → same instance, FR-012).

**Rationale**: traversal-driven discovery is constitution Principle IV;
context attributes are the Jackson-idiomatic per-call channel and remove
the legacy static-ThreadLocal leak. `canRead`/`canWrite` detection uses the
Jackson type-introspection of the target type for reachable `Attachment`
properties, cached per type; media-type match remains the primary gate.

**Alternatives considered**: marker annotation for eligible payload types
(rejected: extra ceremony for adopters; introspection cache achieves the
same with zero API surface); single Jackson module with reflection shims
across 2.x/3.x (rejected: fragile, violates Principle III's clean-adapter
intent).

## R6. Media type and servlet-resolver conflict

**Decision**: Primary media type `multipart/related` with
`type="application/json"`, `boundary`, `start` parameters (FR-001).
Server-side documentation + starter support for keeping the servlet
multipart machinery off these requests:

- Documented default: `spring.servlet.multipart.enabled=false` for
  services that don't use form uploads.
- For services that need both: configure
  `StandardServletMultipartResolver.setStrictServletCompliance(true)`
  (restricts resolution to `multipart/form-data`); the starter
  auto-configures this resolver when
  `restxop.strict-multipart-resolution=true` (default `true` when the
  starter is present, overridable).

**Rationale**: verified during analysis — Spring's resolver historically
claims any `multipart/*` request; strict-compliance mode is the supported
escape hatch. This resolves the original reason the legacy library
invented `composite/related`.

## R7. Legacy compatibility mode scope

**Decision**: A single toggle (`restxop.legacy-compat.enabled=false`
default) that, when on:

- **Read**: additionally accepts `composite/related`; tolerates
  unbracketed Content-IDs, hrefs without `cid:` prefix, absent
  Content-Disposition; delimiter matching per R3 already reads legacy
  writer output byte-exactly (the legacy *writer* framed correctly; only
  its reader was defective).
- **Write**: emits `composite/related` media type and legacy stub/header
  shapes (bare-UUID href, `Response-ID` header, root `Content-ID:
  <mainpart>`) so legacy readers behave no worse than legacy-to-legacy
  (their +2-byte trailing-CRLF defect and zero-attachment hang are theirs
  and are documented).

Authority for the format: captured legacy-format wire fixtures
(`message*.http`, preserved at
`specs/001-rest-attachment-streaming/legacy-fixtures/`), imported into
the testkit.

## R8. Module and build structure

**Decision**: Maven multi-module reactor, group `dev.restxop`, developed
under a `restxop/` directory in this repository, which was initialized
fresh (no legacy history or remote). The original implementation is
archived in a separate developer-local workspace outside this
repository; its wire fixtures were captured into the spec's
`legacy-fixtures/` directory beforehand and are the in-repo authority.

```
restxop-core                  # protocol engine, Attachment API, SPIs; deps: slf4j-api only
restxop-jackson2              # RootPartCodec on Jackson 2.x
restxop-jackson3              # RootPartCodec on Jackson 3.x
restxop-spring-boot-3-starter # Boot 3.x: autoconfig, MVC converter, RestTemplate/RestClient
                              #   integration, optional OpenFeign support (@ConditionalOnClass)
restxop-spring-boot-4-starter # Boot 4.x: same surface on Framework 7 / Jackson 3
restxop-testkit               # shared wire fixtures + abstract behavior/conformance suite
restxop-samples/*             # sample-server / sample-client / sample-client-feign × both gens
```

Client integrations live inside each starter (Spring version coupling
makes a shared client module impractical); Feign support is
conditional-on-classpath within the starter rather than a separate module
(Boot idiom, keeps module count down).

**Build/toolchain**: Maven; `maven.compiler.release=17`; CI matrix builds
on JDK 17 and the latest LTS (25) and runs the testkit conformance suite
against both starters (Principle III/VI). Boot 3 baseline: 3.2+ (first
`RestClient` release); Boot 4 baseline: 4.0+.

**Alternatives considered**: Gradle multi-version variants (rejected:
Maven is the incumbent ecosystem norm for such libraries and the team's
tooling); separate Feign modules (rejected: conditional support inside
starters is the established Boot pattern).

## R9. Testing approach

**Decision**: JUnit 5 (Jupiter) everywhere — Boot 4 has dropped JUnit 4;
one framework across the matrix. The legacy Groovy/Spock suite is not
ported; its *fixtures* (adversarial boundary cases, `message*.http`) are
imported into `restxop-testkit` and extended per Principle VI's coverage
classes. The testkit ships the conformance suite as abstract test classes
each starter's test module extends; wire-output tests assert
byte-identical fixtures across generations (SC-005). The SC-009 load
scenario (100 concurrent exchanges) and SC-001 (1 GB / 256 MB heap) run
as tagged integration tests in the samples build; SC-006 throughput check
is a comparative loopback harness in the testkit, tagged `perf`.

## R10. Default tunables (documented, all overridable per FR-029)

| Property (prefix `restxop.`) | Default | Notes |
|---|---|---|
| `memory-window-per-part` | 256 KiB | chase-buffer memory window before file overflow (R2) |
| `drain.pool-size` | 32 | bounded drain workers; caller-runs fallback on saturation (R1) |
| `spool.directory` | system temp | app-configurable; encrypted-volume guidance in docs |
| `spool.max-per-attachment` | 1 GiB | exceed → exchange fails (FR-018) |
| `spool.max-per-message` | 2 GiB | exceed → exchange fails |
| `limits.max-root-part-bytes` | 16 MiB | FR-008 |
| `limits.max-part-header-bytes` | 64 KiB | FR-008 |
| `limits.max-parts` | 1,000 | FR-008 |
| `timeouts.exchange-ttl` | 10 min | reaper-enforced total exchange lifetime (FR-023) |
| `timeouts.read-wait` | 60 s | max consumer wait for drain progress on a chase buffer (FR-020) |
| `read-buffer-size` | 8 KiB | transport scan buffer (R3) |
| `strict-multipart-resolution` | true | R6 |
| `legacy-compat.enabled` | false | R7 |

Note (FR-020 mapping): awaiting the root part is bounded by
`timeouts.exchange-ttl` plus the transport's own read timeouts;
`timeouts.read-wait` governs chase-buffer awaits. Both are non-infinite
by default.
