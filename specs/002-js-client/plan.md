# Implementation Plan: JavaScript Client for restxop Attachment Messages

**Branch**: `main` (spec dir `002-js-client`) | **Date**: 2026-07-05 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-js-client/spec.md`

## Summary

Deliver **restxop-js**: a zero-dependency TypeScript library letting browsers
and Node consume (and, secondarily, produce) restxop attachment messages.
Technical approach: an incremental chunk parser that ports feature 001's
delimiter-scanner semantics (LF-anchor scan, keep-back across chunk edges,
tail validation, clean-prefix cache) onto `Uint8Array` chunks pulled from a
`ReadableStream`; the JSON root part is buffered (bounded), parsed, and
delivered immediately with `Include` stubs substituted by attachment handles;
parts are then consumed **pull-based and in order**, with in-memory retention
for out-of-order access and `AbortSignal` cancellation throughout. The shared
`.http` fixtures from restxop-testkit are the conformance authority, replayed
at adversarial chunk sizes in both Node and a real browser engine. Showcase:
a minimal Vite/React demo against the boot4 sample server (extended with a
PDF-document endpoint) rendering metadata instantly while the PDF streams.
Full decisions in [research.md](research.md).

## Technical Context

**Language/Version**: TypeScript 5.x compiled to ES2022; ESM-only package
with bundled type declarations

**Primary Dependencies**: none at runtime (constitutional requirement).
Dev-time: typescript, vitest (+ browser mode with the Playwright provider),
React + Vite for the demo only

**Storage**: none — in-memory retention buffers only; no disk access by
design (constitution v1.1.0, Web/JS constraints)

**Testing**: vitest under Node LTS and vitest browser mode (Chromium via
Playwright) running the same fixture-driven conformance suite; fixtures
loaded byte-exactly from `restxop/restxop-testkit/src/main/resources/fixtures/`

**Target Platform**: current evergreen browsers (Chrome, Edge, Firefox,
Safari) and Node active LTS, single codebase (WHATWG streams + fetch, both
native in Node ≥ 18)

**Project Type**: library + demo web app (npm workspace `restxop-js/` beside
the Maven reactor; demo in `restxop-js/demo/`, never published)

**Performance Goals**: SC-003 100 MB attachment consumed with bounded library
memory; parsing overhead negligible next to network (chunk-wise scan, no
per-byte content path)

**Constraints**: SC-006 < 10 KB compressed bundle (enforced by a build
check); structural bounds (root/header/part-count) configurable with
non-infinite defaults mirroring feature 001's table; every wait bounded
(read-idle timeout option + AbortSignal)

**Scale/Scope**: one package (~6 source modules), conformance suite over the
existing ~20-fixture corpus × chunk-size matrix, one demo app, one sample
server endpoint addition

## Constitution Check

*GATE: constitution v1.1.0, principles I–VI + Additional Constraints
(All-deliverables and Web/JavaScript sections). Evaluated pre-Phase-0 and
re-evaluated post-Phase-1.*

| # | Principle | Verdict | Evidence |
|---|-----------|---------|----------|
| I | Streaming-First, Memory-Bounded | **PASS** | Pull-based consumption paced by native `ReadableStream` backpressure (the v1.1.0 consuming-edge clause); payload delivered at root arrival; content flows chunk-wise with bounded working state; only in-order retention (documented, app-controlled) holds part bytes; byte-exactness pinned by the shared fixtures (SC-002/003) |
| II | Standards Alignment Over Invention | **PASS** | Consumes wire-format contract v1 unchanged (no new wire surface); case-robust parsing, CRLF/LF framing, §4 id normalization, RFC 6266/5987 filenames — all inherited requirements verified against the same fixture corpus; legacy `composite/related` explicitly out of scope (spec assumption) |
| III | Framework-Agnostic Core, Thin Adapters | **PASS** | Core parser/session modules depend only on WHATWG streams + `Uint8Array` (no fetch, no DOM, no React); a thin `restxopFetch` convenience binds to fetch; React appears only in the demo app (research R7) |
| IV | Serializer-Driven Discovery | **PASS** | Handles are wired by traversing the parsed payload tree substituting `Include` stubs (read) and by traversing the payload during message building (write) — structural traversal, never name-based field scanning; nested/collection/null supported by construction (research R3) |
| V | No Indefinite Blocking, Deterministic Cleanup | **PASS** | Every pull carries an optional read-idle deadline (default 60 s) and honors `AbortSignal`; cancellation in any phase cancels the underlying stream (releasing the connection) and rejects subsequent reads with a typed error; terminal session states release retention buffers (research R5) |
| VI | Test-First, Wire-Level Verification | **PASS** | The shared `.http` corpus drives the conformance suite byte-for-byte, replayed across a chunk-size matrix (1 B to 64 KiB) in Node AND a real browser engine before implementation lands; malformed/adversarial fixtures assert the typed-error taxonomy (research R6) |

**Additional Constraints (v1.1.0) check**: All-deliverables — hygiene rules
apply to the npm package (neutral coordinates, Apache-2.0 headers, semver);
structural bounds configurable with documented defaults ✓; shared fixtures
as conformance authority ✓. Web/JavaScript — evergreen browsers + Node LTS
single codebase ✓; zero runtime dependencies + bundle budget enforced by a
build-time size check ✓; pull-based over native streams with AbortSignal ✓;
no disk, no poll/sleep ✓; chunk-wise parsing, no whole-response buffering,
no per-byte content path ✓.

**Violations**: none → Complexity Tracking empty.

## Project Structure

### Documentation (this feature)

```text
specs/002-js-client/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions R1–R10
├── data-model.md        # Phase 1 — session/handle states, options, errors
├── quickstart.md        # Phase 1 — validation scenarios
├── contracts/
│   └── js-api.md        # Public API surface, options, error taxonomy
└── tasks.md             # Phase 2 (/speckit-tasks — not created here)
```

### Source Code (repository root)

```text
restxop-js/                      # npm package (name: restxop-js), ESM + types
├── package.json                 # zero runtime deps; vitest/typescript dev-deps
├── tsconfig.json
├── src/
│   ├── index.ts                 # public exports
│   ├── errors.ts                # typed error hierarchy
│   ├── scanner.ts               # chunk-incremental delimiter scanner (R1)
│   ├── headers.ts               # part-header block + parameter parsing, id normalization
│   ├── session.ts               # message session: root delivery, in-order pull, retention (R2, R4)
│   ├── handle.ts                # AttachmentHandle: stream/bytes/blob/skip, metadata
│   ├── fetch.ts                 # restxopFetch convenience over global fetch (R3)
│   └── write.ts                 # v1 upload: in-memory message assembly (R9)
├── test/
│   ├── fixtures.ts              # .http fixture loader (header section + body bytes)
│   ├── conformance.test.ts      # canonical + fidelity corpus × chunk-size matrix
│   ├── failure.test.ts          # malformed corpus, truncation, abort, deadlines
│   ├── session.test.ts          # ordering, retention, skip, duplicate refs
│   └── write.test.ts            # upload assembly round trip
├── scripts/
│   └── check-bundle-size.mjs    # SC-006 budget gate
└── demo/                        # Vite + React showcase (not published)
    ├── package.json
    ├── index.html
    └── src/ (App, DocumentView, metadata panel, progress, upload form)

restxop/restxop-samples/sample-server-boot4/   # extended, not restructured
└── … /DocumentController.java   # GET /document → metadata payload + PDF attachment (+ CORS config)
```

**Structure Decision**: a sibling `restxop-js/` workspace keeps the npm
toolchain fully out of the Maven reactor while living in the same repository
so the fixture corpus is shared by relative path (test-time only; nothing is
copied or published twice). The demo nests inside the package as a private
workspace member. The only Java-side change is one additive sample-server
endpoint plus documented CORS headers (FR-013/FR-014).

## Complexity Tracking

No constitution violations to justify — table intentionally empty.

## Post-Implementation Constitution Review (2026-07-05)

*Recorded at feature completion (T030); all 30 tasks done, all gates green.*

| # | Principle | Verdict | Verification evidence |
|---|-----------|---------|----------------------|
| I | Streaming-First, Memory-Bounded | **PASS** | SC-003 measured: 100 MiB generated message through a pass-through consumer with heap growth < 48 MiB bound (actual well below); payload delivered at root arrival (27 ms browser / 50 ms Node, transfer completing seconds later); in-order path retains nothing (`memory.test.ts`) |
| II | Standards Alignment Over Invention | **PASS** | Full corpus (canonical + fidelity + malformed) byte-exact at 8 chunk sizes in Node (202 tests) and Chromium (199); RFC 6266/5987 `filename*` decoded and emitted; §4 id normalization; CRLF and bare-LF framing accepted, CRLF emitted |
| III | Framework-Agnostic Core, Thin Adapters | **PASS** | `scanner`/`headers`/`session`/`handle`/`write` import only WHATWG streams + `Uint8Array`; fetch appears solely in `fetch.ts` (~40 lines); React only in the unpublished demo |
| IV | Serializer-Driven Discovery | **PASS** | Read: tree traversal substituting single-key `Include` stubs (nested/array/null/duplicate all fixture-verified). Write: identity-dedup tree walk emitting one part per source |
| V | No Indefinite Blocking, Deterministic Cleanup | **PASS** | SC-004 measured: aborts observed to cancel the source < 1 s in every phase; read-idle expiry typed and prompt; first-failure-wins at every error site; reader lock released after cancellation (no dangling locks, asserted per failure family) |
| VI | Test-First, Wire-Level Verification | **PASS** | Every phase wrote its suite first (T018 red before T019, 5 failures driving the hardening); 402 assertions across engines; live cross-implementation round trips: Java writer → JS reader (SC-001) and JS writer → Java reader (SC-007, 25 MiB, digest-exact) |

**Additional Constraints (v1.1.0)**: zero runtime dependencies (enforced by
hygiene scan) ✓; bundle 9,200 / 10,240 bytes gzip (SC-006 gate in `npm
test` and CI) ✓; single codebase Node 20+/evergreen (identical suites both
engines) ✓; pull-based, no disk, no poll/sleep ✓; structural bounds
configurable with documented defaults (README options table) ✓; Apache-2.0
headers + neutral coordinates (SC-008 hygiene) ✓.

**Success criteria**: SC-001 (quickstart §5), SC-002 (§1/§2), SC-003 (§4),
SC-004 (§3), SC-005 (§8: fresh-app walkthrough from README only, scripted
in 21 s), SC-006 (size gate), SC-007 (§6) — all validated and recorded in
quickstart.md. **Violations: none.**
