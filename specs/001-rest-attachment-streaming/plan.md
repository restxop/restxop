# Implementation Plan: REST Attachment Streaming Library (restxop)

**Branch**: `development` (spec dir `001-rest-attachment-streaming`) | **Date**: 2026-07-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-rest-attachment-streaming/spec.md`

## Summary

Regenerate the legacy internal attachment-streaming library as **restxop**
(`dev.restxop`): MTOM/XOP-style streamed binary attachments for Spring
REST, on a standard `multipart/related` wire format (RFC 2387/2392) with a
JSON root part and XOP-style `Include` stubs. Technical approach: a
framework-agnostic core protocol engine using the **eager-chase read
model** (sole v1 mode; owner decision 2026-07-04): a bounded drain-worker
pool consumes the transport at wire speed — freeing the upstream
immediately — while consumers chase the drain live through per-attachment
bounded memory windows that spill to disk only on overflow (pluggable
`SpoolStorage` SPI); lock/condition signaling with deadlines everywhere,
poison-on-failure propagation to blocked readers, caller-runs fallback on
pool saturation. Attachment discovery is serializer-driven via Jackson
context attributes; thin Boot 3.x (Jackson 2) / Boot 4.x (Jackson 3)
starters are proven identical by a shared wire-level conformance testkit.
Full decisions in [research.md](research.md).

## Technical Context

**Language/Version**: Java 17 (compiler release 17; CI also on latest LTS 25)

**Primary Dependencies**: core → slf4j-api only; adapters → Jackson 2.x /
Jackson 3.x (tools.jackson); starters → Spring Boot 3.2+ / 4.0+,
spring-web/MVC, optional spring-cloud-openfeign, optional
jakarta.activation (interop adapters)

**Storage**: local disk spool files (temp/configured directory) via
`SpoolStorage` SPI; no database

**Testing**: JUnit 5 (Jupiter); `restxop-testkit` shared wire-fixture
conformance suite run against both starters; tagged groups: failure,
fidelity, legacy, load, perf

**Target Platform**: JVM libraries for servlet-stack Spring Boot 3.x/4.x
services (server and client side); WebFlux out of scope v1

**Project Type**: multi-module library (Maven reactor + samples)

**Performance Goals**: SC-006 ≤2x overhead vs plain streamed HTTP for
100 MB loopback; SC-002 payload available <2 s into a large transfer

**Constraints**: SC-001 1 GB attachment round trip at 256 MB heap per JVM;
byte-exact fidelity (SC-003); zero residual spool files/connections after
any outcome (SC-004); all bounds/timeouts configurable with non-infinite
defaults (research R10)

**Scale/Scope**: SC-009 100 concurrent active exchanges per instance;
~7 library modules + samples; wire format v1 frozen at 1.0.0

## Constitution Check

*GATE: constitution v1.0.0, principles I–VI. Evaluated pre-Phase-0 and
re-evaluated post-Phase-1.*

| # | Principle | Verdict | Evidence |
|---|-----------|---------|----------|
| I | Streaming-First, Memory-Bounded | **PASS** | Eager drain + live chase through bounded memory windows; disk only on overflow, with per-attachment/per-message caps (R1/R2, FR-017/018); byte-exact framing contract (wire-format §2); SC-001 heap-capped acceptance test |
| II | Standards Alignment Over Invention | **PASS** | `multipart/related` + `cid:` per RFC 2387/2392 (wire-format §1–5); CRLF-owned-by-delimiter framing; case-insensitive robust parsing; `composite/related` only behind off-by-default deprecated toggle (R7); resolver conflict solved by documented config (R6) |
| III | Framework-Agnostic Core, Thin Adapters | **PASS** | core depends on slf4j-api only; Jackson 2/3 isolated in `restxop-jackson2/3`; Boot 3/4 starters thin (R8); client integrations (RestTemplate/RestClient/Feign) are starter deliverables; one conformance suite runs against both (R9) |
| IV | Serializer-Driven Discovery | **PASS** | Discovery via Attachment (de)serializers + Jackson context attributes (R5); no reflection field scanning anywhere; nested/collection/inherited/null supported by construction; identity-map dedup (FR-012) |
| V | No Indefinite Blocking, Deterministic Cleanup | **PASS** | Every chase-buffer await carries the read-wait deadline; exchange TTL reaper; drain failure poisons the exchange and signals already-blocked readers (R1/R2, FR-021); caller-runs fallback prevents pool-saturation deadlock; Exchange owns idempotent resource release incl. overflow-file deletion on all paths; no Thread.isAlive anywhere |
| VI | Test-First, Wire-Level Verification | **PASS** | Testkit fixtures byte-for-byte incl. legacy captures; coverage classes mapped to tagged groups (R9, quickstart §3–7); non-zero coverage gates; conformance suite is public contract |

**Additional Constraints check**: Java 17 ✓, jakarta-only ✓ (activation
optional, interop only), `AutoConfiguration.imports` ✓, buffered/bulk I/O
✓ (R3), event-driven signaling ✓ (lock/condition with deadlines, R2 —
no poll/sleep), spool permissions/deletion ✓ (R2), OSS hygiene ✓
(dev.restxop, Apache-2.0, samples/README planned).

**Violations**: none → Complexity Tracking empty.

## Project Structure

### Documentation (this feature)

```text
specs/001-rest-attachment-streaming/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions R1–R10
├── data-model.md        # Phase 1 — entities, states, SPIs, errors
├── quickstart.md        # Phase 1 — validation scenarios
├── contracts/
│   ├── wire-format.md   # Normative wire protocol v1
│   └── public-api.md    # Module/API/config surface
└── tasks.md             # Phase 2 (/speckit-tasks — not created here)
```

### Source Code (repository root)

```text
restxop/                              # new Maven reactor (dev.restxop:restxop-parent)
├── pom.xml
├── restxop-core/
│   └── src/main/java/dev/restxop/
│       ├── Attachment.java, AttachmentAdapters.java, errors, RestxopConfig
│       ├── spi/            # RootPartCodec, SpoolStorage, ExchangeListener
│       └── core/internal/  # exchange, parser (delimiter scanner, headers), writer, spool
├── restxop-jackson2/       # Jackson 2 codec + Attachment module
├── restxop-jackson3/       # Jackson 3 codec + Attachment module
├── restxop-spring-boot-3-starter/
│   └── …/autoconfigure, web (converter), client (resttemplate, restclient), feign (conditional)
├── restxop-spring-boot-4-starter/    # same layout on Framework 7
├── restxop-testkit/
│   └── src/main/resources/fixtures/  # canonical + legacy message*.http
│   └── src/main/java/…/RestxopConformanceSuite.java (+ tagged suites)
└── restxop-samples/
    ├── sample-server-boot3/  sample-client-boot3/  sample-client-feign-boot3/
    └── sample-server-boot4/  sample-client-boot4/  sample-client-feign-boot4/
```

**Structure Decision**: new `restxop/` reactor inside this repository.
This repository was initialized fresh (no legacy history, no legacy
remote). The original implementation is archived in a separate
developer-local workspace outside this repository and serves only as an
interop target; its wire fixtures were captured into
`specs/001-rest-attachment-streaming/legacy-fixtures/`, which is the
in-repo authority for compat mode. Rationale in research R8 (incl.
per-generation client code living inside starters and Feign via
`@ConditionalOnClass`).

## Complexity Tracking

No constitution violations to justify — table intentionally empty.

## Post-Implementation Constitution Review (T060, 2026-07-05)

*Re-evaluation of the Constitution Check gate table against the completed
implementation (commits 5bf8f50..HEAD, all 60 tasks done). Verdicts recorded
per the constitution's compliance-review requirement.*

| # | Principle | Verdict | Post-implementation evidence |
|---|-----------|---------|------------------------------|
| I | Streaming-First, Memory-Bounded | **PASS** | 1 GB round-trips live in both directions on both generations with 256 MB heaps (quickstart §2 executed; SC-001/SC-002 acceptance test, payload at ~90 ms); write path proven never-fully-buffered (bounded in-flight assertion); chase-buffer spill/caps enforced with tests; byte-exactness checksum-verified across every suite (SC-003) |
| II | Standards Alignment Over Invention | **PASS** | Wire behavior pinned byte-for-byte by canonical fixtures; CRLF-owned-by-delimiter and boundary-like-content cases green incl. the adversarial legacy capture; RFC 6266/5987 filename*; `composite/related` only behind the off-by-default deprecated toggle; resolver conflict solved by the strict-compliance guard (T047), coexistence proven with servlet multipart enabled |
| III | Framework-Agnostic Core, Thin Adapters | **PASS** | core depends on slf4j-api only (hygiene scan verifies import roots); Jackson 2/3 isolated in adapter modules; one conformance suite runs identically in both starters; CrossGenerationSuite proves byte-identical output and identical error surfaces (SC-005); RestTemplate/RestClient/Feign integrations shipped as library code with deferred close verified under failure/TTL |
| IV | Serializer-Driven Discovery | **PASS** | Discovery via Attachment (de)serializers + context attributes; rich-graph fidelity suite covers nested/collection/map/inherited/null through real Jackson traversal on both generations; identity-map dedup and shared-instance resolution verified end-to-end; no reflection field scanning exists |
| V | No Indefinite Blocking, Deterministic Cleanup | **PASS** | 26-case failure-injection suite: every blocked operation ends within its deadline (+10% margin asserted), already-blocked readers woken by poison/TTL, zero residual spool files and released connections asserted per case; live TTL release of an abandoned client response proven in both starters; no Thread.isAlive anywhere |
| VI | Test-First, Wire-Level Verification | **PASS** | Every wire-behavior task landed tests-first (red confirmed before implementation); 200+ tests incl. tagged failure/fidelity/legacy/load/stress/perf groups; 10k-iteration chase-buffer stress; coverage gates non-zero and passing (core 82%, codecs 91%, starters ~82%, testkit 52%); the tuning-pass header regression was caught by the failure suite — the gate working as intended |

**Additional Constraints**: Java 17 release ✓ (CI 17/25); jakarta-only ✓;
`AutoConfiguration.imports` ✓ (two auto-configs per starter); buffered/bulk
I/O ✓ (anchor-scan + clean-prefix cache + BulkTransfer drain path, SC-006
ratio ~1.6 vs the 2.0 bound); event-driven signaling ✓ (waiter-aware
lock/condition, no poll/sleep); spool permissions/deletion ✓ (owner-only,
hygiene-asserted deletion on every outcome); OSS hygiene ✓ (allowlist scan
in CI, private denylist zero hits, Apache-2.0 headers enforced, README +
javadoc gate + runnable samples for server/client/Feign × both generations).

**Deviations**: none. Wire format v1 unchanged from contracts/wire-format.md;
the single tuned default (`read-buffer-size` 8 KiB → 64 KiB, T055) is
recorded in the contract table and README.
