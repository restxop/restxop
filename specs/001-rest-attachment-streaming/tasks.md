# Tasks: REST Attachment Streaming Library (restxop)

**Input**: Design documents from `/specs/001-rest-attachment-streaming/`

**Prerequisites**: plan.md, spec.md, research.md (R1 = eager-chase read model), data-model.md, contracts/wire-format.md, contracts/public-api.md

**Tests**: INCLUDED and ordered before implementation — constitution Principle VI (Test-First, Wire-Level Verification) is non-negotiable for this project.

**Organization**: Grouped by user story (US1–US5 from spec.md) after Setup and Foundational phases. All paths are repository-relative under the new `restxop/` reactor.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable (different files, no dependency on an incomplete task)
- **[Story]**: US1–US5 (user-story phases only)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Maven reactor, CI matrix, quality gates

- [X] T001 Create Maven reactor: `restxop/pom.xml` (dev.restxop:restxop-parent — release 17, Apache-2.0, module list, per-module BOM imports for Boot 3.2.x and 4.0.x, JaCoCo with non-zero thresholds) plus skeleton module poms: `restxop/restxop-core/pom.xml`, `restxop/restxop-jackson2/pom.xml`, `restxop/restxop-jackson3/pom.xml`, `restxop/restxop-spring-boot-3-starter/pom.xml`, `restxop/restxop-spring-boot-4-starter/pom.xml`, `restxop/restxop-testkit/pom.xml`, `restxop/restxop-samples/pom.xml`
- [X] T002 [P] CI workflow in `.github/workflows/restxop-ci.yml`: JDK 17 + 25 matrix, `mvn -f restxop/pom.xml verify`, tagged groups (stress on schedule; perf informational)
- [X] T003 [P] Quality gates in `restxop/pom.xml`: license-header enforcement, static analysis (error-prone or SpotBugs) fail-on-new-warnings, JUnit 5 platform config for tags (failure/fidelity/legacy/load/perf/stress)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core public API, chase buffer, MIME primitives, exchange lifecycle, testkit skeleton — everything the stories build on

**⚠️ CRITICAL**: No user story work until this phase completes

- [X] T004 [P] Error hierarchy per data-model.md in `restxop/restxop-core/src/main/java/dev/restxop/` (RestxopException, MalformedMessageException, LimitExceededException, ExchangeTimeoutException, AttachmentUnavailableException, ExchangeFailedException)
- [X] T005 [P] `RestxopConfig` immutable config + validation rules (defaults per research R10) in `restxop/restxop-core/src/main/java/dev/restxop/RestxopConfig.java` with unit test `restxop/restxop-core/src/test/java/dev/restxop/RestxopConfigTest.java`
- [X] T006 [P] SPI interfaces per contracts/public-api.md in `restxop/restxop-core/src/main/java/dev/restxop/spi/` (RootPartCodec, AttachmentCollector, AttachmentResolver, SpoolStorage, OverflowStore, ExchangeListener, ExchangeInfo, AttachmentInfo)
- [X] T007 `Attachment` interface + builder + factories (Path/File/byte[]/InputStream + metadata overrides) in `restxop/restxop-core/src/main/java/dev/restxop/Attachment.java`, plus `AttachmentAdapters` (optional jakarta.activation interop) — depends on T004
- [X] T008 [P] Unit tests for Attachment factories/metadata/adapters in `restxop/restxop-core/src/test/java/dev/restxop/AttachmentTest.java`
- [X] T009 ChaseBuffer tests FIRST (write-then-chase, window wraparound, overflow spill and drain-back, reader-outruns-writer await, poison wake-up of blocked reader, read-wait deadline expiry, bulk-array IO only, per-attachment cap breach) in `restxop/restxop-core/src/test/java/dev/restxop/core/internal/buffer/ChaseBufferTest.java`
- [X] T010 ChaseBuffer implementation per research R2 (lock + condition, memory ring window, OverflowStore spill) in `restxop/restxop-core/src/main/java/dev/restxop/core/internal/buffer/ChaseBuffer.java` + default file `SpoolStorage` (owner-only perms) in `restxop/restxop-core/src/main/java/dev/restxop/core/internal/buffer/FileSpoolStorage.java`
- [X] T011 ChaseBuffer concurrency stress test (tag `stress`: randomized writer/reader pacing, checksum equality across 10k iterations) in `restxop/restxop-core/src/test/java/dev/restxop/core/internal/buffer/ChaseBufferStressTest.java`
- [X] T012 [P] MIME header-block + Content-Type parameter parser tests FIRST (case-insensitivity, quoting, folding on read, LF tolerance, `--` inside values, non-ASCII bytes, header-size bound → LimitExceededException, missing headers → MalformedMessageException) in `restxop/restxop-core/src/test/java/dev/restxop/core/internal/mime/MimeParsingTest.java`
- [X] T013 [P] Delimiter scanner tests FIRST (CRLF-owned-by-delimiter — no trailing bytes in content; boundary-like content not split; legacy adversarial repeat-boundary fixture; LF framing; closing delimiter + epilogue drain; truncation → MalformedMessageException) in `restxop/restxop-core/src/test/java/dev/restxop/core/internal/mime/DelimiterScannerTest.java`
- [X] T014 MIME header/parameter parser implementation (wire-format §3–4 incl. ID normalization) in `restxop/restxop-core/src/main/java/dev/restxop/core/internal/mime/` (PartHeaders, ContentTypeParams, IdNormalizer)
- [X] T015 Buffered delimiter scanner (KMP with cross-refill state, bulk emission) per research R3 in `restxop/restxop-core/src/main/java/dev/restxop/core/internal/mime/DelimiterScanner.java`
- [X] T016 Exchange lifecycle per data-model.md (states OPEN→COMPLETED/FAILED/RECLAIMED, drainState, resource registry with idempotent close, listener dispatch swallowing listener exceptions, shared TTL reaper scheduler) in `restxop/restxop-core/src/main/java/dev/restxop/core/internal/exchange/Exchange.java` (+ Reaper.java)
- [X] T017 Exchange unit tests (transition legality, double-close no-op, listener exception isolation, reaper reclamation) in `restxop/restxop-core/src/test/java/dev/restxop/core/internal/exchange/ExchangeTest.java`
- [X] T018 Testkit skeleton: fixture loader, canonical fixture set (single/multi/nested/null/zero-attachment `.http` files per contracts/wire-format.md), `RestxopConformanceSuite` abstract base in `restxop/restxop-testkit/src/main/java/dev/restxop/testkit/` + `restxop/restxop-testkit/src/main/resources/fixtures/`

**Checkpoint**: Core primitives verified in isolation — story phases can begin

---

## Phase 3: User Story 1 — Round-trip a typed payload with a streamed attachment (P1) 🎯 MVP

**Goal**: Server returns typed object + streamed attachment; client gets payload before transfer completes; byte-exact content; bounded memory; both directions; both platform generations.

**Independent Test**: quickstart.md §2 — 1 GB file, 256 MB heaps, checksum match, payload readable mid-transfer (SC-001/SC-002/SC-003 single-attachment slice).

### Tests for User Story 1 (write FIRST, must fail before implementation)

- [X] T019 [P] [US1] Writer wire tests: single-attachment message byte-compared to canonical fixture (boundary injected), quoting/Content-ID/Disposition per wire-format §1–5, streamed source never fully buffered in `restxop/restxop-core/src/test/java/dev/restxop/core/internal/write/MessageWriterTest.java`
- [X] T020 [P] [US1] Reader/drain tests: payload returned at root completion while drain still running; attachment checksum-exact via chase; upstream stream fully consumed (drain-complete) independent of consumer pace in `restxop/restxop-core/src/test/java/dev/restxop/core/internal/read/MessageReaderTest.java`

### Implementation for User Story 1

- [X] T021 [US1] `MessageWriter` (boundary generation, root part emission via RootPartCodec + AttachmentCollector, streamed attachment parts, closing delimiter) in `restxop/restxop-core/src/main/java/dev/restxop/core/internal/write/MessageWriter.java`
- [X] T022 [US1] `MessageReader` + `DrainTask` (sync root parse on caller thread, RootPartCodec + AttachmentResolver wiring, drain pool with caller-runs fallback, chase-buffer creation per part, upstream release at end-of-message) per research R1 in `restxop/restxop-core/src/main/java/dev/restxop/core/internal/read/`
- [X] T023 [P] [US1] Jackson 2 codec: Attachment serializer/deserializer with context attributes, Include-stub shape per wire-format §5, `canHandle` type-introspection cache, module registration in `restxop/restxop-jackson2/src/main/java/dev/restxop/jackson2/` + tests in `restxop/restxop-jackson2/src/test/java/`
- [X] T024 [P] [US1] Jackson 3 codec equivalent (tools.jackson APIs) in `restxop/restxop-jackson3/src/main/java/dev/restxop/jackson3/` + tests
- [X] T025 [P] [US1] Boot 3 starter: `RestxopHttpMessageConverter`, auto-configuration, `@ConfigurationProperties` binding (`restxop.*` per contracts/public-api.md), `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` in `restxop/restxop-spring-boot-3-starter/src/main/java/dev/restxop/boot3/`
- [X] T026 [P] [US1] Boot 4 starter equivalent in `restxop/restxop-spring-boot-4-starter/src/main/java/dev/restxop/boot4/`
- [X] T027 [US1] RestTemplate deferred-close customizer (response held until drain completes per contracts/public-api.md) in both starters (`client/RestxopRestTemplateCustomizer.java` in each)
- [X] T028 [US1] Wire US1 conformance cases into both starter test modules extending `RestxopConformanceSuite` (`restxop/restxop-spring-boot-3-starter/src/test/java/.../Boot3ConformanceTest.java`, boot4 equivalent)
- [X] T029 [P] [US1] Samples: `restxop/restxop-samples/sample-server-boot4/` + `restxop/restxop-samples/sample-client-boot4/` (payload + attachment endpoint, client copy with checksum print, `restxop.sample.size` knob)
- [X] T030 [P] [US1] Samples: `restxop/restxop-samples/sample-server-boot3/` + `restxop/restxop-samples/sample-client-boot3/`
- [X] T031 [US1] SC-001/SC-002 acceptance test (1 GB, 256 MB heap, payload-before-completion assertion), tagged `fidelity`, in `restxop/restxop-samples/sample-client-boot4/src/test/java/`

**Checkpoint**: MVP — quickstart §2 passes on both generations

---

## Phase 4: User Story 2 — Predictable behavior under failure and slow consumption (P2)

**Goal**: Every failure/timeout/abandonment path ends promptly with a descriptive error; zero leaked spool files or connections; 100-exchange concurrency.

**Independent Test**: quickstart.md §3 failure-injection suite + §5 load scenario (SC-004, SC-009).

### Tests for User Story 2 (write FIRST)

- [X] T032 [US2] Failure-injection suite (tag `failure`): source severed during preamble/root/headers/content/epilogue; malformed fixture set (missing Content-Type/boundary/start/Content-ID, truncated, oversized header/root, part-count breach); consumer abandonment; early stream close (asserting drain-and-discard: no buffering, no spool-cap accrual); read-wait and TTL expiry; **write-path injection** (inaccessible attachment source, serialization failure mid-root, output failure mid-part → abort with typed error + per-message state release incl. pooled-thread state, FR-014); each case asserts typed error + zero residual spool files + released connections, in `restxop/restxop-testkit/src/main/java/dev/restxop/testkit/FailureInjectionSuite.java` + malformed fixtures in `restxop/restxop-testkit/src/main/resources/fixtures/malformed/`

### Implementation for User Story 2

- [X] T033 [US2] Complete malformed-input handling at every parse site (typed errors per wire-format §6, never NPE/hang) across `restxop/restxop-core/src/main/java/dev/restxop/core/internal/mime/` and `read/`
- [X] T034 [US2] Limits enforcement wiring (max-root-part-bytes, max-part-header-bytes, max-parts, spool caps per-attachment/per-message) with configured-value-bearing LimitExceededException messages in core read path
- [X] T035 [US2] End-to-end poison propagation + deadlines: drain failure wakes blocked readers with cause; read-wait deadline on every await; TTL reaper reclaims abandoned exchanges and deletes overflow files; ChaseBuffer discard mode for early-closed/abandoned attachments; emit and assert the full FR-033 event/log set (started, payload delivered, attachment consumed, bytes spooled, spool-cap breach, skipped unreferenced part, timeout, failed, closed) via the testkit listener capture, in `restxop/restxop-core/src/main/java/dev/restxop/core/internal/exchange/` + `buffer/`
- [X] T036 [US2] Client deferred-close release on failure/TTL paths in both starters' client integrations
- [X] T037 [P] [US2] Testkit spool-hygiene assertions (spool-directory watcher, open-connection probe helpers) in `restxop/restxop-testkit/src/main/java/dev/restxop/testkit/SpoolHygiene.java` + `ListenerCapture.java` (records ExchangeListener events for FR-033 assertions)
- [X] T038 [US2] SC-009 load test (100 concurrent mixed exchanges, spool-triggering sizes, zero failures/timeout violations/cross-talk), tagged `load`, in `restxop/restxop-samples/sample-server-boot4/src/test/java/LoadScenarioTest.java`

**Checkpoint**: quickstart §3 + §5 pass; SC-004/SC-009 demonstrated

---

## Phase 5: User Story 3 — Rich object graphs and attachment metadata (P3)

**Goal**: Attachments at any depth/collections/inherited; null and zero-attachment payloads; duplicate-reference dedup; out-of-order consumption; filename/content-type round-trip.

**Independent Test**: quickstart.md §4 fidelity group (SC-003 full matrix).

### Tests for User Story 3 (write FIRST)

- [X] T039 [US3] Fidelity suite (tag `fidelity`): nested object, List/Map of attachments, inherited field, null field (no part emitted, null on read), zero-attachment immediate return, duplicate-reference single-part/shared-instance, out-of-order reads with window overflow crossover, filename (incl. RFC 6266 non-ASCII) + content-type round-trip — fixtures + suite in `restxop/restxop-testkit/src/main/java/dev/restxop/testkit/FidelitySuite.java` and `fixtures/fidelity/`

### Implementation for User Story 3

- [X] T040 [US3] Identity-map dedup on write and shared-instance resolution on read in both codecs (`restxop/restxop-jackson2/.../AttachmentSerializer.java|Deserializer.java`, jackson3 equivalents)
- [X] T041 [US3] Metadata carriage: Content-Disposition (RFC 6266 quoting/`filename*`) and Content-Type emission in `MessageWriter`, exposure on exchange-backed Attachment via PartHeaders in `MessageReader`
- [X] T042 [US3] Out-of-order consumption hardening (bufferIndex retention, chase buffers readable after drain completion, per-message aggregate cap accounting excluding discarded parts) in core read path
- [X] T043 [US3] Run US3 conformance additions through both starters (extend Boot3/Boot4 conformance tests)

**Checkpoint**: quickstart §4 passes on both generations

---

## Phase 6: User Story 4 — Drop-in adoption across platform generations (P4)

**Goal**: Full client surface (RestClient, Feign), resolver-conflict guard, docs and samples that make SC-007 (< 30 min adoption) real, cross-generation wire identity.

**Independent Test**: quickstart.md §1 (matrix), §8 (README walkthrough); SC-005.

### Tests for User Story 4 (write FIRST where applicable)

- [X] T044 [US4] Cross-generation wire-identity test: identical payloads produce byte-identical messages (modulo boundary) and identical error surfaces across Boot 3/Boot 4 starters (SC-005), in `restxop/restxop-testkit/src/main/java/dev/restxop/testkit/CrossGenerationSuite.java`

### Implementation for User Story 4

- [X] T045 [P] [US4] `RestClient` deferred-close customizer + tests in both starters (`client/RestxopRestClientCustomizer.java`)
- [X] T046 [P] [US4] OpenFeign support (`@ConditionalOnClass(feign.Feign.class)` decoder with deferred close) + tests in both starters (`feign/RestxopFeignDecoder.java`)
- [X] T047 [US4] Multipart-resolver guard: `restxop.strict-multipart-resolution` auto-config (strict-compliance resolver bean) per research R6 in both starters + integration test posting `multipart/related` alongside an active form-upload endpoint
- [X] T048 [P] [US4] Feign sample clients: `restxop/restxop-samples/sample-client-feign-boot3/` and `sample-client-feign-boot4/`
- [X] T049 [P] [US4] `restxop/README.md`: setup per generation, configuration reference (contracts/public-api.md table), resolver pitfall, spool-security guidance (encrypted volume / SpoolStorage SPI), migration notes from legacy library
- [X] T050 [US4] SC-007 validation: follow README only, from blank Boot 3 and Boot 4 apps, to working round trip; record timings and fix doc gaps found

**Checkpoint**: quickstart §1 + §8 pass; all four client paths (RestTemplate/RestClient/Feign × 2 gens) demonstrated

---

## Phase 7: User Story 5 — Interoperate with legacy deployments (P5)

**Goal**: `composite/related` compat mode (off by default): read legacy messages byte-exactly; produce messages legacy readers accept at legacy-to-legacy fidelity.

**Independent Test**: quickstart.md §7 legacy group.

### Tests for User Story 5 (write FIRST)

- [X] T051 [US5] Import captured legacy-format fixtures from `specs/001-rest-attachment-streaming/legacy-fixtures/message*.http` into `restxop/restxop-testkit/src/main/resources/fixtures/legacy/`; legacy suite (tag `legacy`): rejected as unsupported media type when compat off; byte-exact payload+attachment read when on, in `restxop/restxop-testkit/src/main/java/dev/restxop/testkit/LegacyCompatSuite.java`

### Implementation for User Story 5

- [X] T052 [US5] Compat read tolerances (`composite/related` media type acceptance, unbracketed/bare identifiers — §4 normalization already covers, absent Disposition) gated by `legacy-compat.enabled` in core + starters' media-type registration
- [X] T053 [US5] Compat write mode per wire-format §7 (composite/related params, `<mainpart>` root, bare-UUID Content-ID/href, `Response-ID` header, legacy Disposition shape) in `MessageWriter` + codecs
- [X] T054 [US5] Live interop verification (tagged `legacy`, manual-friendly): new client in compat mode against the archived legacy sample server (developer-local workspace outside this repository); document results + migration caveats (legacy zero-attachment hang, +2-byte reader defect) in README migration section

**Checkpoint**: quickstart §7 passes; migration story documented

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T055 [P] SC-006 throughput harness (restxop vs plain streamed HTTP, 100 MB loopback, tag `perf`) in `restxop/restxop-testkit/src/main/java/dev/restxop/testkit/ThroughputHarness.java` + tuning pass (read-buffer/window sizes, bulk paths) if below 50%
- [ ] T056 [P] Javadoc for all public packages (`dev.restxop`, `dev.restxop.spi`, starter public types); exclude `core.internal`; enforce via javadoc plugin in `restxop/pom.xml`
- [ ] T057 OSS hygiene sweep: no originating-organization internal coordinates/hosts/identifiers anywhere under `restxop/` (scripted denylist maintained outside the published repository); Apache-2.0 headers everywhere; SC-008 check scripted in CI
- [ ] T058 Coverage-gate verification (non-zero thresholds enforced per module) and static-analysis-clean confirmation in `restxop/pom.xml` + CI
- [ ] T059 Execute full quickstart.md §1–§8 end-to-end on both generations; fix anything found
- [ ] T060 Final constitution compliance review against plan.md gate table (I–VI + Additional Constraints); record verdicts in `specs/001-rest-attachment-streaming/plan.md` post-implementation note

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)** → nothing
- **Foundational (Phase 2)** → Setup; BLOCKS all stories. Internal order: T004–T006 parallel → T007/T008; T009 → T010 → T011; T012/T013 parallel → T014/T015; T016 → T017; T018 anytime after T004
- **US1 (Phase 3)** → Foundational complete
- **US2 (Phase 4)** → US1 core paths (T021, T022) exist; test suite T032 can be authored in parallel with late US1
- **US3 (Phase 5)** → US1 codecs (T023, T024) and reader (T022)
- **US4 (Phase 6)** → US1 starters (T025–T027); T044 also needs US3 fixtures for full identity coverage
- **US5 (Phase 7)** → US1 reader/writer; independent of US2–US4
- **Polish (Phase 8)** → all desired stories complete

### Story Dependency Notes

US2, US3, US5 depend only on US1's core (not on each other) — after US1, they can proceed in parallel. US4's client breadth depends only on US1's starter skeletons.

### Parallel Opportunities

- Phase 2: {T004, T005, T006} ∥; {T009-chain, T012→T014, T13→T015} are three independent tracks; T018 alongside
- Phase 3: {T023, T024} ∥; {T025, T026} ∥; {T029, T030} ∥
- After US1: US2, US3, US5 tracks in parallel; within US4 {T045, T046, T048, T049} ∥

## Parallel Example: Foundational

```text
Track A: T009 ChaseBuffer tests → T010 impl → T011 stress
Track B: T012 MIME parse tests → T014 impl
Track C: T013 scanner tests   → T015 impl
Track D: T004/T005/T006 API types → T007/T008 Attachment
Track E: T018 testkit skeleton
```

## Implementation Strategy

**MVP first**: Phases 1–3 (T001–T031) deliver the P1 story — the legacy library's proven production capability, modernized, on both generations. Stop, run quickstart §2, validate with project owner (constitution: milestone check-in at phase boundaries).

**Incremental delivery**: then US2 (production hardening — recommended immediately after MVP), US3 (graph richness), US4 (adoption surface), US5 (migration), Polish. Each story phase ends at a quickstart-verifiable checkpoint and never breaks earlier stories (conformance suite is cumulative).

## Notes

- Every wire-behavior task cites its contract section; fixtures are the authority (constitution VI)
- Tests within each story precede implementation and must fail first
- Commit per task or coherent group; conformance suite must be green at every checkpoint
