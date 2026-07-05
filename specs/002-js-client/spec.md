# Feature Specification: JavaScript Client for restxop Attachment Messages

**Feature Branch**: `002-js-client`

**Created**: 2026-07-05

**Status**: Draft

**Input**: User description: "Create a JavaScript/TypeScript client library ("restxop-js") that lets browser and Node front ends consume restxop attachment messages (the standard multipart/related wire format with a JSON root part defined by feature 001). A front-end developer calls a restxop endpoint and receives the typed JSON payload as soon as the root part arrives — before attachment transfer completes — with each attachment field exposed as a lazily consumable stream. Consumption is pull-based: the browser's native fetch/ReadableStream backpressure governs pacing (no eager drain, no disk spool — this is the deliberate client-side counterpart to the Java library's read model). The read/download side is the primary scope; uploads may assemble the message in memory in v1. The wire-format contract (specs/001-rest-attachment-streaming/contracts/wire-format.md) and the shared .http wire fixtures from the restxop testkit are the conformance authority — the JS library must parse them byte-exactly, including the adversarial boundary cases. Deliverables: the zero-dependency library (usable from browsers and Node), a fixture-driven conformance test suite, and a showcase: a small React demo app that calls a restxop sample server and displays a streamed PDF document alongside the collection of metadata fields from the same typed payload — demonstrating the primary use case of streaming a large binary object together with its structured data in one request."

## Overview

Feature 001 gave Java services the ability to exchange typed JSON payloads
carrying large streamed binary attachments in a single request. The consumers
that ultimately display those documents, however, are overwhelmingly web
front ends — and today a browser application has no way to consume a restxop
message: it must either fall back to separate metadata + download endpoints
(the chattiness and consistency problems feature 001 exists to remove) or
buffer entire responses in memory.

This feature closes that gap with **restxop-js**: a small client library for
browsers (and Node) that consumes standard restxop messages. The application
receives the structured payload as soon as it arrives — typically well before
the binary content finishes transferring — and reads each attachment as a
lazily consumed stream. Consumption is deliberately **pull-based**: the
platform's native download backpressure paces the transfer, and nothing is
spooled to disk — the correct counterpart, on the consuming edge, to the Java
library's eager server-side model.

A small React demo application showcases the primary use case end to end: one
request to a restxop sample server returns a document's metadata fields and
its PDF content; the metadata renders immediately and the PDF displays as it
streams in.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Display a streamed document with its metadata from one request (Priority: P1)

A front-end developer calls a restxop endpoint from a web application. The
typed payload (title, dates, status, and other metadata fields) is available
to the UI as soon as the structured part of the response arrives, while the
PDF referenced by the payload is still transferring. The developer reads the
PDF's bytes as a stream and hands them to the page's document viewer. The
user sees the metadata instantly and the document as soon as its bytes land —
all from a single request.

**Why this priority**: This is the entire reason for the feature — the
metadata-plus-binary-in-one-request model finally reaching the tier where
documents are actually displayed. It is a viable, demonstrable MVP on its own.

**Independent Test**: Run the demo application against a restxop sample
server serving a multi-megabyte PDF plus metadata: verify the metadata panel
renders before the transfer completes, the displayed PDF is byte-identical to
the source, and only one network request was made.

**Acceptance Scenarios**:

1. **Given** a restxop endpoint returning a payload with one attachment,
   **When** the browser application calls it, **Then** the structured payload
   fields are available to application code before the attachment bytes have
   fully arrived.
2. **Given** the attachment stream, **When** it is consumed to completion,
   **Then** its bytes are identical to the source (checksum match, exact
   length).
3. **Given** the attachment's wire metadata (filename, content type),
   **When** the payload is delivered, **Then** application code can read them
   from the attachment handle.
4. **Given** the demo application and a sample server, **When** a user opens
   the document page, **Then** metadata fields render first and the PDF
   renders from the same single request.

---

### User Story 2 - Predictable behavior on failure and cancellation (Priority: P2)

A front-end developer relies on the library behaving like a good web citizen
when things go wrong: truncated or malformed responses produce clear, typed
errors (never hangs or silently corrupted documents); the user navigating
away or the application aborting the request promptly cancels the transfer
and frees resources; a payload referencing an attachment that never arrives
yields a clear error when read.

**Why this priority**: Error behavior is what separates an adoptable library
from a dangerous one — the same rationale as feature 001's failure story, on
the consuming side.

**Independent Test**: Drive the library with the malformed and truncated wire
fixtures plus injected network failures and application-initiated aborts;
verify every case ends promptly in a descriptive typed error or clean
cancellation, with the underlying network activity stopped.

**Acceptance Scenarios**:

1. **Given** a response that is severed mid-attachment, **When** the
   application reads that attachment stream, **Then** the read fails with a
   descriptive error identifying the failure (never an indefinite wait).
2. **Given** malformed input (missing required parameters, missing part
   identifiers, truncation), **When** it is consumed, **Then** the library
   fails fast with a descriptive, typed error.
3. **Given** an in-flight transfer, **When** the application aborts it (or
   the page navigates away), **Then** the network transfer stops promptly and
   subsequent reads fail with a cancellation error.
4. **Given** a payload referencing an attachment for which no part arrives,
   **When** that attachment is read, **Then** a clear "attachment
   unavailable" error is raised at end of message.

---

### User Story 3 - Rich payloads and practical consumption patterns (Priority: P3)

A developer consumes realistic messages: several attachments in one message,
attachments nested in child objects or collections, null attachment fields,
and zero-attachment payloads. Attachments are consumed in message order
(pull-based); the library also offers whole-content convenience (bytes/blob)
for attachments the application prefers not to stream, and skipping an
attachment the application does not need.

**Why this priority**: Needed for general-purpose use, but the single
PDF-plus-metadata flow (P1) is valuable without it.

**Independent Test**: Consume the canonical multi/nested/null/zero-attachment
wire fixtures; verify every attachment is byte-exact, nulls round-trip as
absent values, skipped attachments do not block later ones, and convenience
whole-content access matches streamed access.

**Acceptance Scenarios**:

1. **Given** a message with multiple attachments, **When** they are consumed
   in message order, **Then** each is byte-exact and carries its own
   filename/content type.
2. **Given** a payload with a null attachment field, **When** it is
   delivered, **Then** the field is null/absent and no error occurs; a
   zero-attachment payload completes immediately.
3. **Given** an attachment the application does not need, **When** it is
   skipped, **Then** later attachments remain fully readable.
4. **Given** an attachment consumed via whole-content convenience access,
   **When** compared with streamed consumption, **Then** the bytes are
   identical.

---

### User Story 4 - Send attachments from the browser (Priority: P4)

A developer submits a typed payload with attachments (for example, an
uploaded file plus its metadata) to a restxop endpoint from the browser. In
this version, the outgoing message may be assembled in memory before sending;
sizes typical of user uploads must work reliably.

**Why this priority**: Completes the round trip, but front-end consumption is
the dominant use case and the Java clients already cover
service-to-service sending.

**Independent Test**: Build a payload with a file attachment in the demo app,
send it to the sample server's upload endpoint, and verify the server reports
a byte-identical attachment and correct payload fields.

**Acceptance Scenarios**:

1. **Given** a payload object with an attachment created from a user's file,
   **When** it is sent, **Then** the server receives a well-formed message
   with byte-identical attachment content and correct metadata.
2. **Given** a payload with a null attachment field or duplicate references
   to one attachment, **When** sent, **Then** the wire message follows the
   contract (no part for null; one part for duplicates).

---

### User Story 5 - Same library in Node (Priority: P5)

A developer uses the identical library from Node (scripts, tests,
server-side rendering, lightweight services) to consume restxop messages
with the same behavior as in the browser.

**Why this priority**: Comes almost for free from a platform-neutral design
and widens the audience, but browsers are the target tier.

**Independent Test**: Run the conformance suite and a sample-server round
trip under Node's current LTS; results identical to the browser runs.

**Acceptance Scenarios**:

1. **Given** the conformance fixtures, **When** parsed under Node, **Then**
   results are identical to browser parsing.
2. **Given** a restxop sample server, **When** a Node script fetches a
   payload with an attachment, **Then** payload-before-completion and
   byte-exactness hold as in the browser.

---

### Edge Cases

- The response is not a restxop message (wrong media type): a clear typed
  error before any payload is delivered.
- Boundary-like byte sequences inside attachment content must not split or
  corrupt parts (the adversarial fixtures are the authority).
- A part arrives whose identifier matches no payload reference: skipped
  silently (consistent with the wire contract's lenient-read posture).
- Accessing a later attachment before consuming an earlier one: the earlier
  parts' bytes must be retained in memory until read or skipped — acceptable
  within browser memory expectations, and documented so applications consume
  in order when sizes are large.
- Very large attachments: the application controls pacing by consuming
  slowly; the library itself holds only bounded working state beyond any
  unconsumed earlier parts.
- The root payload is oversized or the message exceeds sane structural bounds
  (part count, header sizes): typed error rather than unbounded memory
  growth.
- Cancellation during each phase (before payload, mid-attachment, after
  payload delivered): always prompt, always frees the connection.
- Cross-origin usage: works under standard browser cross-origin rules;
  required server-side response headers are documented.

## Requirements *(mandatory)*

### Functional Requirements

#### Consuming messages (primary scope)

- **FR-001**: The library MUST consume messages conforming to the restxop
  wire contract (feature 001's wire-format contract v1): standard
  related-multipart framing, JSON root part first, `Include`/`href`
  reference stubs, case-robust parsing, CRLF and bare-LF framing.
- **FR-002**: The structured payload MUST be delivered to application code as
  soon as the root part has arrived and parsed, without waiting for
  attachment bytes; attachment references in the payload MUST be replaced by
  attachment handles at delivery time.
- **FR-003**: Each attachment handle MUST expose the part's content as a
  stream for single sequential consumption, plus convenience whole-content
  access, and MUST expose the wire metadata (filename including
  international names, content type) carried by the part.
- **FR-004**: Attachment content MUST be byte-exact: the delimiter's framing
  bytes never leak into content; no bytes added, dropped, or transformed —
  verified against the shared wire fixtures byte-for-byte, including the
  adversarial boundary-collision cases.
- **FR-005**: Consumption MUST be pull-based: the platform's native response
  backpressure paces the transfer; the library MUST NOT eagerly buffer the
  remainder of the message beyond bounded working state and any unconsumed
  earlier parts awaiting in-order consumption; nothing is ever written to
  disk by the library.
- **FR-006**: Null attachment references MUST deliver as null/absent values;
  duplicate references to one part MUST resolve to the same attachment
  handle; parts not referenced by the payload MUST be skipped without error;
  a referenced part that never arrives MUST produce a clear "unavailable"
  error when read.
- **FR-007**: All failure modes — malformed input, truncation, network
  failure, oversized structural elements, unsupported media type — MUST
  surface as descriptive, typed errors, never hangs, silent corruption, or
  unbounded memory growth. Structural bounds (root size, header size, part
  count) MUST be enforced with documented defaults and be configurable.
- **FR-008**: Application-initiated cancellation MUST be supported through
  the platform's standard cancellation mechanism, MUST stop the underlying
  transfer promptly in every phase, and MUST cause subsequent reads to fail
  with a cancellation error.

#### Producing messages (secondary scope)

- **FR-009**: The library MUST be able to produce a well-formed restxop
  message from a payload object whose attachment fields are created from the
  platform's standard binary sources (files, blobs, byte arrays); in this
  version the outgoing message MAY be assembled in memory before sending.
  Null fields produce no part; duplicate references produce one part.

#### Packaging and platform

- **FR-010**: The library MUST run in current evergreen browsers and under
  Node's active LTS releases from a single codebase, with no runtime
  dependencies, and MUST be published with type definitions.
- **FR-011**: The library MUST add no more than a small, documented size to
  an application bundle (see SC-006).
- **FR-012**: The shared wire fixtures from the restxop testkit MUST drive a
  conformance test suite for the library, covering the canonical set, the
  fidelity set, and the malformed/adversarial set; the suite MUST run in
  both a browser engine and Node.

#### Showcase and documentation

- **FR-013**: A small React demo application MUST be delivered that calls a
  restxop sample server and, from a single request, renders a collection of
  document metadata fields immediately and displays the streamed PDF
  document — the primary use case made visible.
- **FR-014**: User documentation MUST cover installation, consuming a
  message (payload-first, streaming, whole-content, skipping), sending,
  cancellation, error handling, configuration of structural bounds, and the
  cross-origin server headers a restxop service must expose for browser
  clients.

### Key Entities

- **Payload**: the application's structured object delivered when the root
  part arrives; attachment-valued fields hold attachment handles (or null).
- **Attachment handle**: the client-side representation of one referenced
  part — content stream (single consumption), whole-content convenience,
  filename, content type, and availability state.
- **Message consumption session**: the lifecycle of one response being
  consumed — delivery of the payload, in-order part consumption, retained
  unconsumed parts, termination (completed, failed, cancelled).
- **Wire fixtures**: the shared byte-exact `.http` conformance corpus from
  feature 001, reused as this library's conformance authority.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In the demo application, document metadata is visible to the
  user within 1 second of the response starting while a multi-megabyte PDF
  is still transferring, and the complete PDF renders from the same single
  request.
- **SC-002**: 100% of the shared canonical and fidelity wire fixtures parse
  byte-exactly (checksum-verified), and 100% of the malformed/truncated
  fixtures produce the documented typed error, in both a browser engine and
  Node.
- **SC-003**: A 100 MB attachment can be consumed in the browser with the
  library's own working memory staying bounded (excluding content the
  application itself retains), demonstrated by a streamed pass-through
  consumer.
- **SC-004**: Cancellation at any phase stops network transfer within 1
  second and never leaks a connection, verified by the failure/cancellation
  suite.
- **SC-005**: A front-end developer new to the library, following only the
  documentation, renders a payload-plus-attachment response in a fresh
  application in under 15 minutes.
- **SC-006**: The library adds less than 10 KB (compressed) to an
  application bundle.
- **SC-007**: An upload of a 25 MB file with metadata from the demo
  application round-trips byte-identically to the sample server.

## Assumptions

- The standard wire format only: the deprecated legacy `composite/related`
  compatibility mode is a service-migration concern and is out of scope for
  the JS client.
- Pull-based, in-order consumption is the contract (per the feature
  request); out-of-order access works via in-memory retention of earlier
  unconsumed parts and is documented as memory-bound. No disk spooling
  exists in the client tier.
- Uploads in this version assemble the outgoing message in memory; sizes
  typical of user uploads (tens of megabytes) must work. True streaming
  uploads are a possible later enhancement where platform support allows.
- "Current evergreen browsers" means the latest stable Chrome, Edge,
  Firefox, and Safari at release time; Node support means active LTS lines.
- The demo application uses the existing restxop sample server (feature
  001) as its backend, extended if needed with a PDF-serving endpoint; the
  demo's visual design is minimal — its purpose is the streaming showcase.
- The library is versioned and published under the project's neutral
  open-source identity, consistent with feature 001's hygiene rules
  (feature 001's SC-008 applies to this deliverable too).
- Authentication, caching, retries, and service discovery remain the
  application's concern; the library operates on a response the application
  knows how to obtain.
