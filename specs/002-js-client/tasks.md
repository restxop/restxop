# Tasks: JavaScript Client for restxop Attachment Messages

**Input**: Design documents from `/specs/002-js-client/`

**Prerequisites**: plan.md, spec.md, research.md (R1 = chunk-incremental scanner port), data-model.md, contracts/js-api.md; feature 001's wire-format contract and testkit fixtures (the conformance authority)

**Tests**: INCLUDED and ordered before implementation — constitution Principle VI (Test-First, Wire-Level Verification) is non-negotiable for this project.

**Organization**: Grouped by user story (US1–US5 from spec.md) after Setup and Foundational phases. Library paths are under the new `restxop-js/`; the one Java-side change is under the existing `restxop/` reactor.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable (different files, no dependency on an incomplete task)
- **[Story]**: US1–US5 (user-story phases only)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: npm workspace, CI job, quality gates

- [X] T001 Create the npm workspace: `restxop-js/package.json` (name `restxop-js`, `"type": "module"`, `exports` map with types, `engines` = active Node LTS, zero runtime deps; dev deps typescript/vitest/@vitest/browser/playwright; scripts `build`, `test`, `test:browser`, `test:memory`, `check:size`), `restxop-js/tsconfig.json` (ES2022, strict, declaration output to `dist/`), `restxop-js/vitest.config.ts` (node) and `restxop-js/vitest.browser.config.ts` (Chromium via Playwright provider), Apache-2.0 `LICENSE` + header convention matching the Java sources
- [X] T002 [P] Extend CI in `.github/workflows/restxop-ci.yml`: a `js` job running `npm ci && npm run build && npm test && npm run test:browser` in `restxop-js/` (browser step installs Playwright Chromium), plus the hygiene script in its steps
- [X] T003 [P] Quality gates: `restxop-js/scripts/check-bundle-size.mjs` (gzip of the built entry graph must be < 10 KB, SC-006; wired into `npm test`) and extend `restxop/build-config/check-hygiene.sh` to scan `restxop-js/` sources and `package.json` coordinates with the allowlist approach

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: error types, fixture plumbing, and the wire scanner/headers everything else builds on

**⚠️ CRITICAL**: No user story work until this phase completes

- [X] T004 [P] Error hierarchy per data-model.md in `restxop-js/src/errors.ts` (RestxopError, MalformedMessageError, LimitExceededError with limit/value, AttachmentUnavailableError, TransferError with cause, CancelledError with AbortError-compatible name) with unit test `restxop-js/test/errors.test.ts`
- [X] T005 [P] Fixture plumbing in `restxop-js/test/fixtures.ts`: load the shared `.http` corpus **by path** from `restxop/restxop-testkit/src/main/resources/fixtures/` (header-section + body-bytes format, body-only variant), plus `chunked(bytes, size)` → `ReadableStream<Uint8Array>` replay helper and the chunk-size matrix {1, 2, 3, 7, 64, 4096, 65536, whole}; self-test `restxop-js/test/fixtures.test.ts`
- [X] T006 Scanner tests FIRST (delimiter owns its leading CRLF — no trailing bytes in content; boundary-like content not split; the adversarial repeat-boundary fixture; bare-LF framing; preamble ignored incl. delimiter at byte 0; closing delimiter + epilogue; truncation → MalformedMessageError; every case across the full chunk matrix) in `restxop-js/test/scanner.test.ts`
- [X] T007 Scanner implementation per research R1 (chunk-incremental: LF-anchor scan with direct verification, keep-back of pattern+1 bytes across chunk edges, clean-prefix cache, tail validation with bounded padding, virtual leading CRLF, subarray bulk emission — no per-byte content path) in `restxop-js/src/scanner.ts`
- [X] T008 [P] Header/parameter tests FIRST (case-insensitive names, folding unfolded on read, LF tolerance, `--` inside values, latin-1 byte interpretation, header-size bound → LimitExceededError, Content-Type parameter parsing with quoting, §4 id normalization, RFC 6266/5987 `filename*` decoding with `filename`/`name` fallbacks) in `restxop-js/test/headers.test.ts`
- [X] T009 Header-block + parameter parser + id normalization implementation (wire-format §3–§4) in `restxop-js/src/headers.ts`

**Checkpoint**: wire primitives proven against the shared corpus in isolation — story phases can begin

---

## Phase 3: User Story 1 — Display a streamed document with its metadata from one request (P1) 🎯 MVP

**Goal**: payload-first delivery with wired attachment handles; byte-exact streaming consumption; fetch binding; conformance in Node and a real browser; the React demo showing metadata instantly while the PDF streams.

**Independent Test**: quickstart.md §5 — one request to the sample server's `/document`; metadata renders < 1 s while the PDF is still transferring; displayed PDF byte-identical (SC-001, SC-002 canonical slice).

### Tests for User Story 1 (write FIRST, must fail before implementation)

- [X] T010 [P] [US1] Session tests: payload resolves at root completion while the source is gated mid-attachment; `Include` stubs substituted with handles (incl. duplicate href → same handle instance); single-attachment stream and `bytes()` byte-exact against the canonical fixture; filename/contentType exposed; `completed` resolves at clean end; read-idle option honored — in `restxop-js/test/session.test.ts`
- [X] T011 [P] [US1] Conformance suite (canonical corpus × chunk matrix, checksums + metadata per fixture) in `restxop-js/test/conformance.test.ts`

### Implementation for User Story 1

- [X] T012 [US1] `MessageSession` + `AttachmentHandle` per data-model.md (readMessage core: outer Content-Type validation, bounded root buffering + JSON.parse + tree-walk substitution, in-order demand-driven pulls, retention primitives, states, read-idle deadline) in `restxop-js/src/session.ts` and `restxop-js/src/handle.ts`
- [X] T013 [US1] Public entry points: `restxop-js/src/index.ts` exports and `restxop-js/src/fetch.ts` (`restxopFetch` over global fetch, content-type extraction, signal passthrough) with a real-HTTP smoke test against a tiny Node `http` server serving fixture bytes in `restxop-js/test/fetch.test.ts`
- [X] T014 [US1] Browser-engine run: make `npm run test:browser` execute the conformance + session suites under Chromium (fixtures delivered via Vite asset handling in `vitest.browser.config.ts`); fix any platform deltas
- [X] T015 [US1] Sample-server endpoint: `GET /document` returning a metadata payload (title, author, pages, created, status, tags) plus a genuine PDF `Attachment`, with CORS for the demo origin, in `restxop/restxop-samples/sample-server-boot4/src/main/java/dev/restxop/samples/server/DocumentController.java` (+ a small bundled PDF resource) and an integration test in the same module
- [X] T016 [US1] Demo app: `restxop-js/demo/` (Vite + React, private workspace member) — `DocumentView` calling `restxopFetch("/document")`, metadata panel rendered on payload resolution, live byte-progress from the handle stream, PDF displayed via object URL on completion, in `restxop-js/demo/src/`
- [X] T017 [US1] SC-001 validation run: demo against the sample server; verify a single `multipart/related` request, metadata visible < 1 s while streaming, PDF byte-identical; record results in quickstart notes

**Checkpoint**: MVP — quickstart §5 demonstrable; conformance green in Node + Chromium

---

## Phase 4: User Story 2 — Predictable behavior on failure and cancellation (P2)

**Goal**: every malformed/truncated/severed/aborted/timed-out path ends promptly in the documented typed error; the underlying stream is always cancelled.

**Independent Test**: quickstart.md §3 (SC-004).

### Tests for User Story 2 (write FIRST)

- [X] T018 [US2] Failure suite: the malformed fixture corpus → typed errors (limit names + values asserted); truncation before/inside root, inside part headers, mid-content; source stream erroring mid-message → TransferError with cause delivered to blocked and subsequent reads; AbortSignal fired before payload / mid-attachment / after delivery → source cancelled promptly (< 1 s) and reads reject CancelledError; read-idle expiry on a stalled source; referenced-part-absent → AttachmentUnavailableError at end of message — in `restxop-js/test/failure.test.ts`

### Implementation for User Story 2

- [X] T019 [US2] Harden session/scanner failure paths to green: typed-error mapping at every parse site, cancellation plumbing (reader.cancel + state transitions + retention release), idle-deadline racing on every pull, unavailable resolution at end-of-message, in `restxop-js/src/session.ts` + `restxop-js/src/scanner.ts`
- [X] T020 [US2] SC-004 timing + hygiene assertions: aborts observed to stop the source within 1 s in each phase; no dangling reader locks (source stream state checked after every failure case) — extend `restxop-js/test/failure.test.ts`

**Checkpoint**: quickstart §3 passes; SC-004 demonstrated

---

## Phase 5: User Story 3 — Rich payloads and practical consumption patterns (P3)

**Goal**: multi/nested/null/zero-attachment payloads; skip; out-of-order retention; whole-content equality; configurable bounds.

**Independent Test**: quickstart.md §1 fidelity rows (SC-002 full matrix).

### Tests for User Story 3 (write FIRST)

- [X] T021 [US3] Rich-payload suite: fidelity + multi/nested/null/zero fixtures through the session (every attachment checksum-exact with its own metadata; null stays null; zero-attachment completes immediately); duplicate references share one handle and one consumption; `skip()` frees retention and later parts stay readable; out-of-order access (read the second attachment first — the first is retained then still byte-exact); `bytes()`/`blob()` equal streamed content; `maxParts`/`maxRootBytes`/`maxPartHeaderBytes` option overrides enforced — extending `restxop-js/test/session.test.ts` and `restxop-js/test/conformance.test.ts`

### Implementation for User Story 3

- [X] T022 [US3] Retention/skip/out-of-order hardening and unreferenced-part lenient skip to green in `restxop-js/src/session.ts` + `restxop-js/src/handle.ts`

**Checkpoint**: SC-002 full corpus green in both engines

---

## Phase 6: User Story 4 — Send attachments from the browser (P4)

**Goal**: v1 in-memory message assembly producing canonical wire shapes; demo upload round trip.

**Independent Test**: quickstart.md §6 (SC-007).

### Tests for User Story 4 (write FIRST)

- [ ] T023 [US4] Write suite: `buildMessage` output asserts canonical shapes (bracketed Content-IDs, quoted parameters, CRLF framing, `filename*` for non-ASCII names, default octet-stream type, null field → no part, duplicate source → one part with shared href); round trip through `readMessage` byte-exact from `Uint8Array`, `Blob`, and `File` sources — in `restxop-js/test/write.test.ts`

### Implementation for User Story 4

- [ ] T024 [US4] Writer implementation: `attachment()` source wrapper, payload tree walk with identity dedup, message assembly (`Blob` in browser / `Uint8Array` in Node), `restxopFetch.post` convenience, in `restxop-js/src/write.ts` (+ exports in `restxop-js/src/index.ts`)
- [ ] T025 [US4] Demo upload form posting a user-selected file + metadata via `buildMessage` to the sample server's `/upload`, displaying the echoed size/SHA-256, in `restxop-js/demo/src/`; SC-007 validation with a ~25 MB file recorded

**Checkpoint**: quickstart §6 passes

---

## Phase 7: User Story 5 — Same library in Node (P5)

**Goal**: identical behavior under Node LTS over real HTTP.

**Independent Test**: quickstart.md §7.

- [ ] T026 [US5] Node consumption script `restxop-js/scripts/demo-node.mjs` (`npm run demo:node`): fetch `/document` from the sample server, print payload fields before transfer completes, checksum the PDF; plus a CI-friendly variant asserting parity with the browser conformance results (suites already run under Node — this closes the real-HTTP loop)

**Checkpoint**: quickstart §7 passes

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T027 [P] SC-003 bounded-memory test: `npm run test:memory` streams a generated 100 MB message through a pass-through consumer asserting empty retention and bounded working state, in `restxop-js/test/memory.test.ts`
- [ ] T028 [P] Package README (`restxop-js/README.md`): install, payload-first consumption, streaming/whole-content/skip, cancellation, error handling, options table, CORS prerequisites for restxop services, upload notes and v1 buffering caveat, demo instructions
- [ ] T029 SC-005 adoption walkthrough: from a fresh Vite app following only the README to a rendered payload + streamed attachment in < 15 minutes; fix documentation gaps found
- [ ] T030 Final gates: SC-006 size budget green in CI, hygiene scan over `restxop-js/`, full quickstart §1–§8 executed, and the post-implementation constitution review recorded in `specs/002-js-client/plan.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)** → nothing
- **Foundational (Phase 2)** → Setup; BLOCKS all stories. Internal order: T004/T005 parallel → T006 → T007; T008 → T009 (T008 parallel with T006)
- **US1 (Phase 3)** → Foundational complete. T010/T011 parallel first; T012 → T013 → T014; T015 (Java side) parallel with T012–T014; T016 needs T013 + T015; T017 last
- **US2 (Phase 4)** → US1 core (T012); T018 may be authored in parallel with late US1
- **US3 (Phase 5)** → US1 core (T012)
- **US4 (Phase 6)** → Foundational only (writer is independent of the read session); demo task T025 needs T016
- **US5 (Phase 7)** → US1 (T013, T015)
- **Polish (Phase 8)** → all desired stories complete

### Parallel Opportunities

- Phase 2: {T004, T005, T008} ∥; then T006→T007 beside T008→T009
- Phase 3: {T010, T011} ∥; T015 ∥ {T012, T013, T014}
- After US1: US2, US3, US4 tracks in parallel
- Phase 8: {T027, T028} ∥

## Implementation Strategy

**MVP first**: Phases 1–3 (T001–T017) deliver the P1 story — the showcase
the feature exists for. Stop, run quickstart §5, validate with the project
owner (constitution: milestone check-in at phase boundaries).

**Incremental delivery**: then US2 (failure posture — recommended immediately
after MVP), US3 (rich payloads), US4 (uploads), US5 (Node loop), Polish.
Every story phase ends at a quickstart-verifiable checkpoint; the conformance
suite is cumulative and must be green in both engines at every checkpoint.

## Notes

- Every wire-behavior task cites the shared fixtures as its authority
  (constitution VI + All-deliverables conformance constraint)
- Tests within each story precede implementation and must fail first
- Commit per task or coherent group; `npm test` + `npm run test:browser`
  green at every checkpoint
