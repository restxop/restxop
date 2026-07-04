# Feature Specification: REST Attachment Streaming Library

**Feature Branch**: `001-rest-attachment-streaming`

**Created**: 2026-07-04

**Status**: Draft

**Input**: User description: "Reverse-engineer and respecify the REST attachment streaming library (the legacy internal implementation) as an open-source, modernized implementation. The library enables Spring REST services to exchange typed JSON payloads carrying large binary attachments as lazily-streamed MIME parts, replicating SOAP MTOM/XOP semantics over REST."

## Overview

Service developers routinely need to move large binary content (documents,
images, media) through REST APIs whose payloads are typed JSON objects. Today
their options are all bad at scale: base64-inline (33% bloat, full buffering),
`multipart/form-data` (loses the typed-object programming model), or separate
download endpoints (chatty, transactional-consistency problems). SOAP solved
this two decades ago with MTOM/XOP; this library brings the same model to
REST: the developer works with an ordinary typed object whose binary-valued
fields travel as separate streamed MIME parts, invisible to application code
on both ends.

This is a reverse-engineering respec of a proven internal library. The legacy
implementation validated the concept in production (single-PDF payloads) but
has known defects (hangs, byte-fidelity, resource leaks), structural
limitations (top-level fields only, non-null only), a non-standard media
type, and an end-of-life platform baseline. The respec fixes the defect
classes by design, lifts the limitations, aligns the wire format with MIME
standards, and targets current platform generations — as an open-source
project under new neutral naming.

## Clarifications

### Session 2026-07-04

- Q: What is the user-facing attachment type in payload models? → A:
  Library-native `Attachment` type only, with factory/adapter interop for
  File, Path, InputStream, byte[], and activation-framework
  DataHandler/DataSource sources.
- Q: How are message parts not referenced by the payload handled? → A:
  Lenient — skipped with a logged warning; the exchange succeeds
  (confirmed; supersedes the legacy hard-error behavior, no strictness
  toggle).
- Q: Is spooled attachment data protected at rest? → A: Not by the
  library — data protection at rest is an application concern.
  The library provides owner-only permissions, guaranteed deletion, an
  app-configurable spool directory (point it at encrypted/ephemeral
  storage), and a pluggable spool-storage extension point (plain files by
  default) for apps that need to supply their own storage strategy.
  App-level spool encryption is explicitly out of scope for v1; guidance
  goes in the documentation.
- Q: What observability does the library provide? → A: Structured logging
  via the standard logging facade plus a library-owned exchange lifecycle
  listener interface (started, payload delivered, attachment consumed,
  spool activity, failed, cleaned up) that applications can bridge to
  their own metrics. No metrics-library dependency in v1; a turnkey
  metrics module may be layered later without wire or API changes.
- Q: What concurrency scale must one service instance sustain? → A: 100
  concurrent active exchanges without failures or timeout violations,
  verified by a load scenario in the acceptance suite.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Round-trip a typed payload with a streamed attachment (Priority: P1)

A service developer defines a response class with regular fields plus an
attachment-valued field, populates the attachment from a large file, and
returns it from a REST endpoint. A client developer calls that endpoint,
receives the typed object immediately, reads the regular fields, then opens
the attachment as a stream and copies it to disk. Neither side ever holds the
attachment fully in memory; the received bytes are identical to the source.

**Why this priority**: This is the core value proposition — everything else
qualifies or hardens this flow. It is the legacy library's proven production
use case (single attachment) and constitutes a viable MVP on its own.

**Independent Test**: Stand up the sample server and client; transfer a file
larger than the configured memory buffer (e.g., 1 GB) with a capped heap;
verify checksum equality of source and received file and that the client had
access to the typed fields before the attachment finished transferring.

**Acceptance Scenarios**:

1. **Given** a server endpoint returning an object with one attachment,
   **When** the client requests it, **Then** the client receives the typed
   object with all non-attachment fields populated before the attachment
   bytes have fully arrived.
2. **Given** the attachment stream on the client, **When** it is read to
   completion, **Then** its bytes are identical to the source (checksum
   match, exact length — no added or missing bytes).
3. **Given** an attachment larger than available heap, **When** it is
   transferred, **Then** neither producer nor consumer exceeds the
   configured memory bounds.
4. **Given** a client posting an object with an attachment to a server
   endpoint, **When** the server reads it, **Then** the same guarantees hold
   in the request direction.

---

### User Story 2 - Predictable behavior under failure and slow consumption (Priority: P2)

An operator runs services using the library under real-world conditions:
connections drop mid-transfer, consumers read attachments slowly or abandon
them, malformed messages arrive. Every such event resolves promptly into a
clear error or clean completion — never a hung thread, a leaked temp file, or
a silently corrupted payload.

**Why this priority**: The legacy library's worst defects were hangs
(zero-attachment messages, mid-stream failures) and resource leaks. For a
library embedded in other people's services, failure behavior is the
difference between adoptable and dangerous.

**Independent Test**: Run the failure-injection suite: sever the source
stream at every message phase, abandon attachments unread, close streams
early, feed malformed messages. Verify every blocked operation ends within
its configured timeout, every error carries a descriptive cause, and no
spool files or open connections remain afterward.

**Acceptance Scenarios**:

1. **Given** a transfer where the source stream fails mid-attachment,
   **When** a consumer is already blocked reading that attachment, **Then**
   the consumer is unblocked promptly with an error identifying the cause.
2. **Given** a message whose payload declares an attachment that never
   arrives (stream ends early), **When** the consumer reads that attachment,
   **Then** it receives a clear error rather than blocking indefinitely.
3. **Given** a consumer that never reads a delivered attachment, **When**
   the exchange's configured lifetime expires, **Then** all buffers, spool
   files, and the underlying connection are released.
4. **Given** malformed input (missing required headers/parameters, unknown
   part references, oversized part headers, oversized root payload),
   **When** it is read, **Then** parsing fails fast with a descriptive
   error — never a null-pointer failure, hang, or unbounded memory growth.
5. **Given** any blocking operation (awaiting the payload, awaiting
   attachment bytes), **When** its configured timeout elapses without
   progress, **Then** it fails with a timeout error.

---

### User Story 3 - Rich object graphs and attachment metadata (Priority: P3)

A developer models realistic payloads: attachments nested inside child
objects, lists of attachments, optional (null) attachments, inherited
attachment fields, several attachments in one message. Each attachment
carries its filename and content type across the wire, and consumers may
read attachments in any order, at their own pace.

**Why this priority**: These are the structural limitations that blocked
broader adoption of the legacy library. They are required for general-purpose
open-source use but the P1 flow is valuable without them.

**Independent Test**: Round-trip a payload containing a nested attachment, a
list of attachments, a null attachment field, and top-level attachments;
read them in non-wire order; verify all content, names, and content types
survive.

**Acceptance Scenarios**:

1. **Given** an object with attachments at any depth (nested objects,
   collections, maps, inherited fields), **When** it is round-tripped,
   **Then** every attachment arrives with byte-exact content.
2. **Given** an object with a null attachment field, **When** it is
   round-tripped, **Then** no attachment part is transmitted and the field
   is null on receipt; a payload with zero attachments overall is likewise
   valid and returns to the caller without delay.
3. **Given** an attachment with a filename and content type, **When** it is
   round-tripped, **Then** the receiver observes the same filename and
   content type on the attachment.
4. **Given** a message with multiple attachments, **When** the consumer
   reads them in a different order than they arrived, **Then** all reads
   succeed (deferred content is buffered/spooled within configured bounds).
5. **Given** the same attachment object referenced from two places in the
   payload, **When** it is round-tripped, **Then** its content is
   transmitted once and both references resolve to the same received
   attachment.

---

### User Story 4 - Drop-in adoption across current platform generations (Priority: P4)

A developer on either of the two supported application-platform generations
adds one starter dependency; the exchange capability activates by
convention. Client-side usage works through the platform's standard HTTP
clients (template-style, fluent-style, and declarative/Feign) without
hand-written plumbing for connection lifecycle. Runnable server and client
samples plus documentation cover setup, configuration, and the known
platform pitfall (the servlet multipart machinery claiming `multipart/*`
requests).

**Why this priority**: Adoption mechanics. Without frictionless integration
and both platform generations, the library won't be picked up — but it
presupposes the P1–P3 behavior exists.

**Independent Test**: Build and run the shared behavior test suite and the
samples against both platform generations from a clean environment;
integrate a new demo service following only the README.

**Acceptance Scenarios**:

1. **Given** a fresh service on either supported platform generation,
   **When** the corresponding starter is added, **Then** endpoints can
   produce and consume attachment messages with no additional wiring beyond
   documented configuration.
2. **Given** the shared behavior suite, **When** run against both platform
   generations, **Then** results are identical (same wire bytes accepted
   and produced, same errors, same limits).
3. **Given** the declarative-client (Feign) and both standard-client
   integration modules, **When** a client fetches an attachment message,
   **Then** the response connection remains open until the message is
   fully received (or the exchange fails/expires), then is released —
   handled by the library, not user code; attachments remain readable
   from library buffers afterward.
4. **Given** a server receiving attachment messages as request bodies,
   **When** configured per the documentation, **Then** the platform's
   multipart form machinery does not intercept the messages.

---

### User Story 5 - Interoperate with legacy deployments (Priority: P5)

A team with existing services built on the legacy library enables the
compatibility mode and exchanges messages with them during a migration
window, without upgrading the legacy side.

**Why this priority**: Migration convenience for existing internal users;
irrelevant to new open-source adopters. Explicitly deprecated from day one.

**Independent Test**: Using captured legacy wire fixtures and a running
legacy sample, verify the new reader consumes legacy-produced messages and
the legacy reader consumes compat-mode-produced messages, at fidelity no
worse than legacy-to-legacy exchanges.

**Acceptance Scenarios**:

1. **Given** compatibility mode is off (default), **When** a legacy-format
   message arrives, **Then** it is rejected as an unsupported media type.
2. **Given** compatibility mode is on, **When** a legacy-produced message is
   read, **Then** payload and attachments are delivered with byte-exact
   attachment content.
3. **Given** compatibility mode is on, **When** a message is produced for a
   legacy consumer, **Then** the legacy consumer processes it with fidelity
   no worse than a legacy-to-legacy exchange.

---

### Edge Cases

- Payload declares zero attachments: caller receives the object immediately;
  no waiting on attachment machinery (legacy hung forever here).
- Attachment content that contains boundary-like byte sequences (including
  the boundary string itself without proper delimiter framing) is not split
  or corrupted.
- Part headers containing `--`, unusual casing, quoted parameters, or
  non-ASCII values parse correctly (legacy swallowed the stream on `--`).
- Line endings: readers accept both CRLF and LF framing; writers emit
  standard CRLF.
- The delimiter's leading line break belongs to the delimiter, not to part
  content (legacy appended 2 bytes to every attachment).
- A message part arrives whose identifier matches no reference in the
  payload: it is skipped with a warning (not an error, not a hang).
- The payload references an attachment identifier for which no part ever
  arrives: reading that attachment yields a clear error at end of message.
- Consumer closes an attachment stream early or never opens it: the
  remaining bytes of that part are drained and discarded — not buffered
  and not counted against spool caps; producer side completes or aborts
  cleanly; spool files are removed.
- Two exchanges running concurrently on the same client/server do not share
  or leak state (identifiers, buffers, temp files) across requests.
- Spool storage exhausted or spool cap exceeded: the affected exchange fails
  with a clear error; other exchanges are unaffected.
- Interrupted consumer thread: the read fails promptly with an interruption
  error; it does not return partial or null results silently.

## Requirements *(mandatory)*

### Functional Requirements

#### Wire format

- **FR-001**: Messages MUST use the standard related-multipart MIME format
  (RFC 2387): a `multipart/related` media type carrying `type`, `boundary`,
  and `start` parameters, where `start` identifies the root part.
- **FR-002**: The root part MUST be the first part of the message, MUST be
  identified by a Content-ID matching the `start` parameter, and MUST
  contain the JSON serialization of the payload object.
- **FR-003**: Each attachment value in the payload MUST be represented in
  the JSON as an XOP-style reference stub — an `Include` object whose
  `href` is a `cid:` URI (RFC 2392) naming the Content-ID of the MIME part
  carrying that attachment's bytes.
- **FR-004**: Each attachment MUST travel as exactly one MIME part with a
  unique Content-ID, binary content transfer encoding, the attachment's
  content type as the part's Content-Type (defaulting to a generic binary
  type when unknown), and the attachment's filename conveyed via the part's
  Content-Disposition when present.
- **FR-005**: Attachment content MUST be byte-exact in both directions: the
  delimiter's preceding line break is framing, not content; no bytes are
  added, dropped, or transformed.
- **FR-006**: Boundary strings MUST be generated per message such that
  accidental collision with content is statistically negligible, and
  readers MUST only recognize delimiters with correct framing so that
  boundary-like sequences inside part content do not split parts.
- **FR-007**: Readers MUST parse MIME robustly: header names and parameter
  names case-insensitively; quoted and unquoted parameter values; optional
  angle-bracket and `cid:` decorations on identifiers; both CRLF and LF
  line endings. Writers MUST emit canonical form (CRLF, quoted parameters,
  angle-bracketed Content-IDs).
- **FR-008**: Readers MUST enforce configurable size bounds on part headers
  and on the root part, and configurable count bounds on parts, failing
  with a descriptive error when exceeded.
- **FR-009**: Malformed input (missing Content-Type, missing `boundary` or
  `start`, missing part Content-ID, truncated message, unknown references)
  MUST produce descriptive, typed errors — never null-pointer failures,
  hangs, or unbounded resource growth. A part not referenced by the payload
  MUST be skipped with a logged warning.

#### Producing messages (write path)

- **FR-010**: Attachment values MUST be discovered during serialization of
  the object graph itself, wherever they occur: any nesting depth,
  collections and maps, inherited fields. Reflection-based scanning of
  declared fields is prohibited (constitution Principle IV).
- **FR-011**: Null attachment values MUST serialize as JSON null, produce no
  MIME part, and deserialize back to null. An object with zero attachment
  values MUST still be writable and readable as a valid message.
- **FR-012**: The same attachment object referenced multiple times within
  one payload MUST be transmitted as a single part, with all references
  carrying the same identifier; on read, all such references MUST resolve
  to the same attachment object.
- **FR-013**: Attachment content MUST be streamed from its source to the
  output; the write path MUST NOT buffer an entire attachment in memory.
- **FR-014**: A failure while writing (inaccessible attachment source,
  serialization failure, output failure) MUST abort the message with a
  descriptive error and release all per-message state, including state
  bound to pooled threads.

#### Consuming messages (read path)

- **FR-015**: The typed payload object MUST be returned to the caller as
  soon as the root part has been received and deserialized — without
  waiting for any attachment bytes, and immediately for zero-attachment
  messages.
- **FR-016**: Every attachment reference in the payload MUST be usable as a
  lazy stream from the moment the payload object is returned. Reading an
  attachment whose bytes have not yet arrived blocks (subject to timeout)
  until they do; references are wired at payload deserialization time, not
  when their part arrives.
- **FR-017**: Consumers MUST be able to read attachments in any order and
  at any pace within the exchange's configured limits. Incoming bytes for
  not-yet-consumed attachments MUST be buffered in a bounded memory buffer
  that overflows to disk spooling.
- **FR-018**: Spooling MUST honor: configurable directory, configurable
  per-attachment and per-message size caps (failing the exchange with a
  clear error when exceeded, never exhausting memory), restrictive file
  permissions (owner-only), and unconditional deletion of spool files by
  the end of the exchange on every path — success, failure, early close,
  and abandonment. The spool storage mechanism MUST be a pluggable
  extension point (plain files as the default implementation) so
  applications can substitute their own storage strategy; protection of
  spooled data at rest (e.g., encryption) is an application
  responsibility, addressed in documentation (encrypted volume, ephemeral
  mount, or custom spool storage), not by the library.
- **FR-019**: Attachment metadata (filename, content type) from part
  headers MUST be exposed on the received attachment objects.

#### Lifecycle, timeouts, and failure propagation

- **FR-020**: Every blocking operation — awaiting the root payload,
  awaiting attachment bytes, buffer hand-off between producer and consumer
  — MUST have a configurable timeout with a documented non-infinite
  default, and MUST fail with a descriptive timeout error when exceeded.
- **FR-021**: A producer-side failure MUST promptly unblock consumers
  already blocked in a read or wait — not merely fail subsequent calls —
  and MUST carry the causal error. A consumer-side close or failure MUST
  release producer-side resources for that exchange.
- **FR-022**: Correctness MUST NOT depend on thread-liveness checks or
  poll/sleep loops; producer/consumer signaling MUST be event-driven.
  (Pooled threads never terminate; the legacy liveness heuristics are
  prohibited.)
- **FR-023**: Each exchange MUST bound its total lifetime; when the bound
  is reached, the exchange is failed and all resources (buffers, spool
  files, the underlying HTTP connection/response) are released. Exchanges
  MUST be fully isolated from one another.
- **FR-024**: On the client side, the HTTP response MUST be kept open past
  payload return until all message bytes have been received (drain
  complete) or the exchange fails or expires, and MUST then be released
  promptly — managed by the library's client integrations, never by user
  code. Attachment consumption continues from library buffers after
  release.

#### Platform integration and packaging

- **FR-025**: The protocol engine (framing, parsing, buffering/spooling,
  exchange lifecycle) MUST be a core module independent of the web
  framework and of any specific JSON-library major version, per
  constitution Principle III.
- **FR-026**: The library MUST ship starters for both supported platform
  generations — Spring Boot 3.x (Jackson 2) and Spring Boot 4.x
  (Jackson 3) — with behavior proven identical by one shared wire-level
  test suite executed against both. Auto-configuration MUST use the
  platforms' current registration mechanism.
- **FR-027**: Client integrations for the platform's standard HTTP clients
  (RestTemplate, RestClient) and for OpenFeign MUST be shipped as library
  modules (not sample code), providing the deferred-close behavior of
  FR-024.
- **FR-028**: Server-side reception MUST work with documented configuration
  that prevents the servlet multipart form machinery from intercepting
  related-multipart requests; the documentation MUST cover this pitfall
  explicitly.
- **FR-029**: All tunables — buffer size, spool directory and caps,
  timeouts, header/root-part/part-count bounds, worker pool sizing, legacy
  compatibility mode — MUST be settable through the platform's standard
  externalized configuration, with documented defaults.
- **FR-030**: Runnable samples (server, standard client, declarative
  client) and user documentation (setup, configuration reference,
  migration notes from the legacy library) MUST be part of the
  deliverable.

#### Observability

- **FR-033**: The library MUST emit structured log events (via the
  standard logging facade, at conventional levels) for exchange lifecycle
  milestones and anomalies — exchange started/completed/failed, spool
  activation and cap breaches, skipped unreferenced parts, timeout
  expirations — and MUST expose a lifecycle listener extension point
  through which applications receive these exchange events (started,
  payload delivered, attachment consumed, bytes spooled, failed, cleaned
  up) to drive their own metrics or auditing. The library itself MUST NOT
  depend on any metrics framework.

#### Legacy compatibility

- **FR-031**: A legacy compatibility mode, off by default and documented as
  deprecated, MUST allow reading messages produced by the legacy library
  (its non-standard media type, unbracketed identifiers, bare-identifier
  references) with byte-exact attachment delivery, and producing messages
  that legacy consumers process with fidelity no worse than
  legacy-to-legacy exchanges.
- **FR-032**: Published artifacts MUST carry neutral open-source
  coordinates and contain no references to the originating organization's
  internal coordinates, hosts, or identifiers. The project is named
  **restxop**, with group coordinates **`dev.restxop`** and artifact names
  prefixed accordingly (e.g., `restxop-core`,
  `restxop-spring-boot-3-starter`, `restxop-spring-boot-4-starter`).
  The `restxop.dev` domain is registered to the project owner
  (acquired 2026-07-04), satisfying Maven Central group verification for
  `dev.restxop`.

### Key Entities

- **Attachment**: A binary content value carried by a payload object —
  content stream plus metadata (filename, content type). A single
  library-native type (per Clarifications 2026-07-04): source-backed when
  producing (creatable from File, Path, InputStream, byte[], or
  activation-framework sources), exchange-backed (lazy) when consuming.
- **Payload object**: The user's typed object graph, serialized as JSON in
  the root part; may contain zero or more attachment values anywhere in the
  graph.
- **Attachment reference**: The in-payload stub linking a JSON location to
  an attachment part by identifier (`Include`/`href`/`cid:`).
- **Message**: One related-multipart body — root part plus zero or more
  attachment parts, each part with headers (identifier, content type,
  disposition) and content.
- **Exchange**: The lifecycle scope of one message being produced or
  consumed: configuration, timeouts, buffers, spool files, and (client
  side) the held HTTP response. Owns cleanup guarantees; isolated from
  other exchanges.
- **Chase buffer / spool storage**: per-attachment bounded transfer
  buffer — memory window first, disk overflow within caps (pluggable
  storage), with security and deletion guarantees.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A 1 GB attachment round-trips between sample client and
  sample server with each JVM's heap capped at 256 MB, with source and
  destination checksums identical, on both supported platform generations.
- **SC-002**: The typed payload is available to the consumer before
  attachment transfer completes: for a multi-hundred-megabyte attachment,
  payload fields are readable within 2 seconds of response start while the
  attachment is still streaming.
- **SC-003**: 100% of attachment transfers in the verification suite are
  byte-exact (checksum-verified), including multi-attachment, nested,
  out-of-order-consumption, spool-crossover, and legacy-read cases.
- **SC-004**: In the failure-injection suite (source severed at every
  phase, abandonment, early close, malformed inputs, timeout expiry), 100%
  of blocked operations terminate within their configured timeout plus 10%
  margin, and zero spool files or open connections remain after each test.
- **SC-005**: The shared behavior suite passes 100% on both supported
  platform generations, with byte-identical wire output fixtures.
- **SC-006**: End-to-end throughput for a 100 MB attachment over loopback
  is within 50% of copying the same bytes over a plain streamed HTTP
  response on the same stack (i.e., the library adds at most 2x overhead;
  the legacy per-byte design is orders of magnitude off this mark).
- **SC-007**: A developer new to the library, following only the README,
  gets a working producing endpoint and consuming client in under 30
  minutes on either platform generation.
- **SC-008**: Zero references to the originating organization's internal
  naming remain in published artifacts, and licensing/headers pass
  standard open-source compliance checks.
- **SC-009**: A single service instance sustains 100 concurrent active
  exchanges (mixed produce/consume, attachments large enough to trigger
  spooling) with zero failures, zero timeout violations, and zero
  cross-exchange interference, demonstrated by a load scenario in the
  acceptance suite.

## Assumptions

- The root payload is JSON; XML or other root formats are out of scope.
- Synchronous (servlet-stack) request/response is the scope for this
  feature; reactive/WebFlux support is out of scope for v1.
- The root part is always transmitted first; the `start` parameter is
  honored on read for validation, but producers always emit root-first, and
  attachment parts follow in the order encountered during serialization.
- Attachment values in payload models use a single library-native
  attachment type (content stream + filename + content type) owned by this
  project; it is the only type the serialization machinery recognizes.
  Factories/adapters MUST make it easy to create from File, Path,
  InputStream, byte[], and activation-framework DataHandler/DataSource
  sources (migration path from the legacy API). Additional recognized
  payload types could be layered later without wire changes.
- Parts arriving that are not referenced by the payload are skipped with a
  warning rather than failing the exchange (lenient-read posture; the
  legacy library treated this as an error).
- Duplicate references to the same attachment instance are legal and
  deduplicated (FR-012); distinct attachment instances with identical
  content are transmitted as distinct parts.
- Compatibility mode is a read-and-write toggle scoped to the legacy
  format as actually produced/consumed by the legacy implementation (the
  captured legacy-format fixtures preserved with this spec are the
  authority), not to any broader interpretation of its media type.
- The legacy consumer's known 2-byte-trailing defect is its own behavior;
  "fidelity no worse than legacy-to-legacy" (FR-031) accepts it when the
  legacy side is the reader.
- Java 17 is the language/runtime baseline (constitution: Boot 3.x/4.x
  common ground); the build must also pass on the latest LTS.
- Maven Central (or equivalent public repository) is the publication
  target; release versioning follows semantic versioning per the
  constitution.
