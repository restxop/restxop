# Phase 0 Research: JavaScript Client for restxop Attachment Messages

**Date**: 2026-07-05
**Spec**: [spec.md](spec.md)

## R1. Parsing model: chunk-incremental port of the feature-001 scanner

**Decision**: A single incremental parser consumes `Uint8Array` chunks (from
any source; in practice a `ReadableStream` reader) and exposes events/pulls
for: root part complete, part headers complete, part content chunk, part
end, message end. It ports the proven feature-001 semantics directly:

- Delimiter = `CRLF/LF + "--" + boundary` with the leading line break owned
  by the delimiter; a virtual leading CRLF handles an opening delimiter at
  byte 0; tail validation accepts `--` (closing), bounded transport padding,
  then CRLF/LF (or EOF after a closing boundary).
- **LF-anchor scan with direct verification** (the SC-006-tuned algorithm
  from feature 001) rather than a per-byte automaton, plus the
  **clean-prefix cache** so per-byte header reads never re-scan.
- **Keep-back of pattern-length+1 bytes** across chunk edges so a delimiter
  (including its optional leading CR) can never be split by chunk
  boundaries — the exact cross-refill discipline of the Java scanner, with
  network chunks taking the role of refills.
- Content is emitted as **subarray views** of an internal contiguous buffer
  (copied once into handle retention or straight to the consumer), never
  per byte.

**Rationale**: the Java scanner's semantics are already pinned by the
adversarial fixture corpus; porting the same algorithm means the same
corpus proves the same guarantees, byte for byte. Chunk-size variance
replaces refill-size variance as the thing to fuzz.

**Alternatives considered**: regex/string-split over decoded text (rejected:
corrupts binary, unbounded memory); existing npm multipart parsers
(rejected: form-data oriented, runtime dependency, none honor the
CRLF-owned-by-delimiter contract byte-exactly).

## R2. Session model: payload-first, pull-based, in-order with retention

**Decision**: One **message session** per response.

- The root part is buffered up to `maxRootBytes`, `JSON.parse`d, stubs
  substituted (R3), and the payload promise resolves — the transfer of later
  parts has typically not begun; nothing further is pulled until the
  application asks.
- Attachment consumption is **pull-based and in message order**: reading
  handle N pulls the source just enough to serve N; parts encountered
  before N that were not yet consumed are retained **in memory** inside
  their handles (bounded only by application behavior, as specified);
  `skip()` (and consuming to end) drops retention immediately.
- Nothing is pulled ahead of demand except the minimal keep-back state, so
  native backpressure paces the socket (constitution I, consuming-edge
  clause).
- End of message resolves availability: referenced handles never seen →
  reads reject with the unavailable error; unreferenced parts encountered
  while pulling are skipped silently (lenient posture, wire contract §5).

**Rationale**: matches the spec's contract (pull acceptable, in-order
primary path) and the browser reality (no disk, native backpressure); keeps
the memory story honest and documentable in one sentence.

**Alternatives considered**: eager background drain into memory (rejected:
unbounded memory on large messages, fights backpressure); strict
in-order-only with no retention (rejected: makes multi-attachment payloads
gratuitously hard to use).

## R3. Public API shape and stub substitution

**Decision**:

```ts
const msg = await restxopFetch<DocPayload>(url, { signal });   // fetch binding
// or, transport-agnostic:
const msg = await readMessage<DocPayload>(contentType, stream, options);

msg.payload            // typed payload; Include stubs replaced by handles
msg.attachments        // handles in wire order (incl. duplicates deduped)

handle.stream(): ReadableStream<Uint8Array>   // single sequential consumption
handle.bytes(): Promise<Uint8Array>           // whole-content convenience
handle.blob(): Promise<Blob>                  //   (browser-friendly variant)
handle.skip(): Promise<void>
handle.filename / handle.contentType          // wire metadata (may be undefined)
```

Stub substitution walks the parsed JSON tree; every
`{"Include":{"href":"cid:x"}}` node is replaced by the (deduplicated) handle
for the normalized id — structural traversal of the value tree, satisfying
constitution IV on the read side. `buildMessage` (R9) mirrors it for writes.

**Rationale**: `readMessage(contentType, stream)` keeps the core free of
fetch/DOM (constitution III) and makes Node/undici and test harnesses
first-class; `restxopFetch` is the one-liner the demo and docs lead with.

**Alternatives considered**: callback/event-emitter API (rejected: fights
async/await ergonomics); returning raw part iterators without payload
substitution (rejected: pushes the defining feature — typed payload with
wired handles — onto every caller).

## R4. Bounds, ordering hazards, and memory posture

**Decision**: configurable options with non-infinite defaults mirroring
feature 001's table where meaningful client-side: `maxRootBytes` (16 MiB),
`maxPartHeaderBytes` (64 KiB), `maxParts` (1000), `readIdleTimeoutMs`
(60 000). Violations reject with `LimitExceededError` naming the bound and
value. Out-of-order access is served via retention (R2) and called out in
docs as the memory-bound path; there are no spool caps because there is no
spool.

**Rationale**: same memory-exhaustion posture as the server read path
(constitution, All-deliverables structural bounds), minus concepts that
don't exist client-side.

## R5. Cancellation, timeouts, and the error taxonomy

**Decision**: every entry point accepts an `AbortSignal`; abort cancels the
underlying stream reader (releasing the connection promptly in any phase),
transitions the session to CANCELLED, drops retention, and makes all
pending/subsequent reads reject with an `AbortError`-compatible typed error.
Each pull additionally races `readIdleTimeoutMs` (constitution V: no
unbounded wait even when the app supplied no signal). Error hierarchy
(mirroring feature 001's taxonomy, TS-idiomatic):

`RestxopError` (base) → `MalformedMessageError`, `LimitExceededError`
(limit name + configured value), `AttachmentUnavailableError`,
`TransferError` (network/severed, cause attached), `CancelledError`
(also matching `err.name === "AbortError"` conventions).

**Rationale**: AbortSignal is the platform's standard mechanism (spec FR-008)
and browsers already wire it to navigation/component teardown; the idle
deadline preserves principle V's "no wait without a bound".

## R6. Conformance strategy: shared fixtures × chunk matrix, two engines

**Decision**: the test fixture loader reads the **same files** from
`restxop/restxop-testkit/src/main/resources/fixtures/` (canonical, fidelity,
malformed) — never copies them. Every fixture is replayed through the parser
at chunk sizes {1, 2, 3, 7, 64, 4096, 65536 and whole-body} to prove
chunk-edge independence, asserting byte-exact content (checksums), metadata,
and the typed error for the malformed set. The identical suite runs under
Node (vitest) and a real browser engine (vitest browser mode, Chromium via
Playwright); fixtures reach the browser through Vite's asset handling.
Legacy-format fixtures are excluded (out of scope per the spec assumption).

**Rationale**: constitution VI and the All-deliverables conformance-authority
constraint; the 1-byte-chunk row is the strongest possible cross-edge proof
and directly replaces the Java suite's refill fuzzing.

## R7. Toolchain and packaging

**Decision**: TypeScript 5.x, `tsc`-only build to ESM (`type: "module"`,
`exports` map, `.d.ts` shipped) — no bundler needed for a zero-dependency
library. Tests via vitest 3.x; browser runs via `@vitest/browser` with the
Playwright provider. Bundle budget (SC-006) enforced by
`scripts/check-bundle-size.mjs` (gzip of the built entry graph, fails > 10
KB) wired into `npm test`/CI. Node engine floor: current active LTS.
The npm workspace root is `restxop-js/`; the demo is a private workspace
member. CI gains one job: `npm ci && npm test` (which includes the size
gate) plus the browser-mode run.

**Rationale**: fewest moving parts that still satisfy the Web/JS
constitutional constraints (single codebase, budget enforced by the build,
type definitions published).

**Alternatives considered**: tsup/rollup bundling (unnecessary for zero-dep
ESM); jest (heavier, weaker browser story); separate repos (rejected: the
fixture corpus must stay shared by path, and the constitution governs one
repository).

## R8. Demo app and sample-server endpoint

**Decision**: `restxop-js/demo` is a minimal Vite + React app. The boot4
sample server gains `GET /document`: a payload of realistic metadata fields
(title, author, pages, created, status, tags) plus a genuine PDF attachment
(a small static PDF resource served through the normal `Attachment` API,
optionally size-inflated for demo effect), with CORS configured
(`Access-Control-Allow-Origin` for the dev origin). Demo UX: metadata panel
renders the moment the payload resolves (with a visible "attachment still
streaming" indicator and byte-progress driven by the handle's stream);
the PDF is accumulated to a `Blob` and displayed in the browser's native
viewer via an object URL when complete. The upload form (US4) posts a
user-selected file plus fields via `buildMessage` and shows the server's
checksum echo.

**Rationale**: shows the two beats that matter — payload-first and
single-request streaming — without dragging in a PDF-rendering dependency;
the native viewer needs the complete bytes anyway, and the progress bar
makes the streaming visible. (Incremental page-by-page rendering via a PDF
library is a possible follow-on, deliberately out of scope.)

## R9. Upload (v1): in-memory assembly

**Decision**: `buildMessage(payload, options)` walks the payload, replaces
attachment sources (`Blob`/`File`/`Uint8Array`) with `Include` stubs
(identity-deduplicated; null → JSON null, no part), and assembles body parts
into a `Blob` (browser) / `Uint8Array` (Node) with a random boundary and the
canonical writer shapes from wire-format §1–5 (bracketed Content-IDs,
quoted parameters, RFC 6266/`filename*`, CRLF). Returned as
`{ contentType, body }` for the app to POST; `restxopFetch.post` convenience
included.

**Rationale**: matches the spec's v1 scope (memory-assembled, tens of MB);
`Blob` parts reference `File` data without copying until send, so browser
memory cost is near the payload size, not 2×. Streaming request bodies
remain a documented future enhancement (platform support still uneven).

## R10. Hygiene, naming, and versioning

**Decision**: npm package name **`restxop-js`**, version starting 0.1.0,
Apache-2.0 with the same header block as the Java sources; the feature-001
allowlist hygiene script extends to `restxop-js/` (import roots: none beyond
platform globals; package.json fields checked). No non-neutral identifiers
anywhere (All-deliverables constraint); README for the package plus a demo
README section.
