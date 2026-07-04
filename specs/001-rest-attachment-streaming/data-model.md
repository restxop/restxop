# Data Model: REST Attachment Streaming Library (restxop)

**Date**: 2026-07-04 · **Spec**: [spec.md](spec.md) · **Research**: [research.md](research.md)

Core-module concepts (no Spring/Jackson types appear here; adapters bind
them to the platform).

## Attachment (public API, user-facing)

The single payload-model type for binary values (Clarification 2026-07-04).

| Field / accessor | Type | Notes |
|---|---|---|
| `contentStream()` | InputStream | source-backed on write; exchange-backed lazy stream on read; each call contract: single sequential consumption |
| `filename()` | Optional&lt;String&gt; | carried via part Content-Disposition (FR-004, FR-019) |
| `contentType()` | Optional&lt;String&gt; | carried via part Content-Type; default `application/octet-stream` on write when absent |
| `contentLength()` | OptionalLong | advisory; known for file/byte sources, unknown for streams |

**Creation (write side)**: static factories — `of(Path)`, `of(File)`,
`of(byte[])`, `of(InputStream)`, plus metadata-overriding variants and
adapters `fromDataHandler(...)`/`fromDataSource(...)` (activation interop,
optional module-less overload guarded by classpath presence).

**States**:
- *Source-backed* (write): wraps a user-supplied source; opened once
  during message write; failure to open aborts the exchange (FR-014).
- *Exchange-backed* (read): placeholder wired at payload deserialization
  (FR-016); stream state: `PENDING` (drain has not reached the part) →
  `CHASING` (chase buffer open; reader follows the drain through the
  memory window / overflow file) → `CONSUMED` / `FAILED` / `RECLAIMED`
  (exchange ended).

**Identity rule**: within one payload, the same instance encountered
multiple times = one part, one Content-ID (identity map, FR-012). On read,
one Content-ID = one shared instance.

## Exchange (internal, one per message read or write)

Lifecycle owner; all cleanup guarantees hang off it (FR-023).

| Attribute | Notes |
|---|---|
| `id` | correlation for logs/listener events |
| `config` | resolved `RestxopConfig` snapshot |
| `deadline` | created-at + exchange TTL; checked at every parser/writer advance |
| `partRegistry` | Content-ID → exchange-backed Attachment (read) / Attachment → Content-ID (write, identity map) |
| `drainState` | read side: QUEUED / RUNNING(worker) / CALLER-RUNS / DONE / FAILED — drain owns the parser position (before-root → in-part(cid) → epilogue → done) |
| `bufferIndex` | Content-ID → ChaseBuffer (created as the drain reaches each part) |
| `resources` | transport stream/response handle, open chase buffers + overflow files; closed exactly once, idempotent; upstream transport released as soon as the drain reaches end-of-message |
| `state` | OPEN → COMPLETED \| FAILED(cause) \| RECLAIMED (TTL reaper) |

**Transitions**:
- OPEN→COMPLETED: all consumed or exchange closed normally; resources
  released, spool files deleted.
- OPEN→FAILED: transport error, malformed input, cap/limit breach,
  deadline exceeded; all pending/live attachment streams begin throwing
  the causal error; resources released.
- OPEN→RECLAIMED: TTL reaper fires (abandonment, FR-023); as FAILED with
  timeout cause.
- Terminal states never transition; double-close is a no-op.

**Isolation**: no static/thread-local state anywhere; everything is
Exchange-scoped (edge case: concurrent exchanges share nothing).

## Message / Part descriptors (internal)

| Entity | Fields | Notes |
|---|---|---|
| `MessageDescriptor` | mediaType (STANDARD \| LEGACY), boundary, startContentId, rootType | parsed from/emitted to the outer Content-Type (FR-001) |
| `PartHeaders` | contentId (normalized: brackets/`cid:` stripped), contentType, disposition(filename), raw map (case-insensitive keys) | bounded by `max-part-header-bytes` (FR-008) |
| `IncludeStub` | `{"Include":{"href":"cid:<id>"}}` | JSON shape owned by adapters; legacy mode: bare href, media type `composite/related` (R7) |

## ChaseBuffer (internal; one per attachment part — research R2)

Single-writer (drain) / single-reader (consumer), one lock + condition.

| Attribute | Notes |
|---|---|
| `window` | bounded in-memory ring, `memory-window-per-part` bytes; reader chases writer memory-to-memory when keeping pace |
| `overflow` | file obtained from `SpoolStorage` SPI when the window fills; owner-only permissions; registered with Exchange for deletion; reader drains window → file → window in byte order |
| `bytesWritten` | checked against `spool.max-per-attachment` and message aggregate at the writer (FR-018) |
| `state` | OPEN(writer active) → COMPLETE(writer done) → DRAINED; or POISONED(cause) — poison signals the condition, waking any blocked reader immediately (FR-021); or DISCARDING — reader closed early/abandoned: remaining part bytes are drained and dropped, no buffering, no cap accrual |
| await rules | every reader await carries the read-wait deadline; bulk array reads/writes only (no per-byte locking) |
| lifecycle | overflow file deleted at exchange end on every path (success/failure/early close/abandonment) |

## SPIs (public extension points)

| SPI | Operations | Bound by |
|---|---|---|
| `RootPartCodec` | `writeRoot(payload, out, AttachmentCollector)`, `readRoot(in, targetType, AttachmentResolver)`, `canHandle(type)` | restxop-jackson2 / restxop-jackson3 (R5) |
| `AttachmentCollector` (write callback) | `register(Attachment) → contentId` | core Exchange |
| `AttachmentResolver` (read callback) | `resolve(contentId) → Attachment` (creates/returns shared instance) | core Exchange |
| `SpoolStorage` | `createOverflow(exchangeId, contentId) → overflow store for a ChaseBuffer` | default file impl; app-replaceable (Clarification #3) |
| `ExchangeListener` | `exchangeStarted/payloadDelivered/attachmentConsumed/bytesSpooled/exchangeFailed/exchangeClosed` | app metrics/auditing (FR-033); exceptions from listeners are logged, never propagated |

## Configuration (`RestxopConfig`)

Immutable snapshot per exchange; properties, defaults, and bounds per
research.md R10. Validation: positive sizes, threshold ≤ per-attachment
cap ≤ per-message cap, directory exists/writable at startup (fail fast).

## Error hierarchy (public)

`RestxopException` (unchecked, carries exchange id)
├── `MalformedMessageException` — FR-009 parse failures (what/where detail)
├── `LimitExceededException` — FR-008/FR-018 bounds (which limit, configured value)
├── `ExchangeTimeoutException` — FR-020/FR-023 deadline/TTL
├── `AttachmentUnavailableException` — referenced part never arrived (FR-009) or read after exchange end
└── `ExchangeFailedException` — wraps causal transport/codec failure propagated to consumers (FR-021)
