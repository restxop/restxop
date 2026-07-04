<!--
Sync Impact Report
==================
Version change: (template, unversioned) → 1.0.0
Rationale: Initial ratification. All template placeholders replaced with
concrete principles agreed during the reverse-engineering analysis of the
legacy internal implementation (2026-07-04).

Modified principles: n/a (initial adoption)
Added sections:
- Core Principles (I–VI)
- Additional Constraints (technology & compatibility)
- Development Workflow & Quality Gates
- Governance
Removed sections: none

Templates requiring updates:
- .specify/templates/plan-template.md — ✅ no change needed (Constitution
  Check gates are derived dynamically from this file)
- .specify/templates/spec-template.md — ✅ no change needed
- .specify/templates/tasks-template.md — ✅ no change needed

Follow-up TODOs: none
-->

# REST Attachment Library Constitution

Library for streaming binary attachments over Spring REST services, inspired by
SOAP MTOM/XOP: a JSON root part carries the typed payload, binary fields are
externalized as MIME parts and re-linked as lazy streams on receipt.

## Core Principles

### I. Streaming-First, Memory-Bounded

Attachment content MUST NOT be fully buffered in heap memory at any point on
either the write or read path. The read path MUST hand the caller a usable
object graph while attachment bytes are still in flight, with attachment
streams consumable lazily. When a consumer falls behind the producer, excess
bytes MUST spool to disk within configurable bounds (max spool size per
attachment and per message). Attachment content MUST be reproduced byte-exactly:
what the sender streamed is what the receiver reads — no added or dropped
bytes, verified by wire-level tests (Principle VI).

*Rationale: the entire reason this library exists is to move large binaries
(e.g., multi-GB documents) through typed REST interfaces without exhausting
memory; any buffering shortcut silently defeats that purpose.*

### II. Standards Alignment Over Invention

The wire format MUST use standard `multipart/related` (RFC 2387) with a JSON
root part and `Content-ID`/`cid:` part references (RFC 2392), mirroring XOP
semantics (`{"Include":{"href":"cid:..."}}`) in JSON. Deviations from MIME
conventions (delimiter handling, header folding, parameter quoting,
case-insensitivity) are defects, not features. The legacy `composite/related`
media type MUST be supported only as an explicitly configured
backward-compatibility mode, off by default, and documented as deprecated.
Framework friction (e.g., servlet multipart resolvers claiming `multipart/*`
requests) MUST be solved with documented configuration, not by inventing
non-standard media types.

*Rationale: as an open-source library, interoperability with non-Spring peers
(CXF, API gateways, test tooling) and predictable behavior under standard MIME
parsers is a core feature.*

### III. Framework-Agnostic Core, Thin Adapters

The protocol engine (MIME framing/parsing, boundary scanning, spooling pipes,
attachment registry, lifecycle/timeout management) MUST live in a core module
with no dependency on Spring MVC or any specific Jackson major version.
Spring Boot 3.x (Jackson 2) and Spring Boot 4.x (Jackson 3) support MUST be
delivered as separate thin starter modules that adapt the core; protocol
behavior MUST be identical across starters and proven by a shared wire-level
test suite run against both. Client-side support (RestTemplate/RestClient
deferred-close handling, Feign integration) is part of the deliverable, not
sample code.

*Rationale: the Jackson 2→3 and Boot 3→4 split is the main portability fault
line; isolating it keeps the hard streaming logic single-sourced and testable
once.*

### IV. Serializer-Driven Discovery

Attachment discovery MUST be performed by the serialization framework during
tree traversal — the serializer assigns Content-IDs as it encounters
attachment-typed values; the deserializer registers stream placeholders as it
materializes them. Reflection-based field scanning of declared fields is
prohibited. Consequently, attachments MUST be supported at any depth of the
object graph, inside collections and maps, in inherited fields, and MUST be
permitted to be null/absent (a null attachment field produces no MIME part and
round-trips as null).

*Rationale: the legacy top-level-only reflection scan was the root cause of an
entire class of limitations and bugs (nested/collection/inherited fields
unsupported, null NPEs, inverted type checks); traversal-driven discovery
eliminates the class rather than patching instances.*

### V. No Indefinite Blocking, Deterministic Cleanup

Every blocking operation — awaiting the root part, awaiting attachment bytes,
pipe reads/writes, thread-pool submission — MUST have a configurable timeout
with a sane default; no code path may wait forever. A failure on the producer
side MUST promptly unblock and propagate to consumers already blocked in
read/wait (not merely to subsequent calls), and vice versa. All resources
(spool files, file handles, pooled threads, underlying HTTP connections) MUST
be released deterministically on success, failure, early close, and
abandonment; spool files MUST NOT outlive their message exchange. Liveness
checks MUST NOT rely on `Thread.isAlive()` heuristics, which are meaningless
with pooled threads.

*Rationale: the legacy implementation could hang callers forever on
zero-attachment messages and mid-stream producer failures, and leaked spool
files on early close; for a library embedded in other people's services,
hangs and leaks are worse than errors.*

### VI. Test-First, Wire-Level Verification (NON-NEGOTIABLE)

Protocol behavior MUST be specified and verified at the wire level: canonical
`.http` fixtures and generated payloads asserted byte-for-byte, including
attachment fidelity (checksums, not just prefixes). Required coverage classes:
zero attachments, single, multiple, nested/collection/null attachments,
out-of-order consumption, spool-to-disk crossover, producer/consumer failure
at every phase, malformed input (missing headers, bad boundaries, boundary-like
content, oversized headers), and timeout expiry. Every bug fix MUST land with a
failing test first. Concurrency behavior (pipes, registries, timeouts) MUST
have dedicated stress tests. Coverage gates MUST NOT be set to zero.

*Rationale: this is concurrent, byte-level protocol code — the category of code
where untested edge cases ship as data corruption and deadlocks; the legacy
trailing-CRLF defect went unnoticed precisely because no test checked byte
fidelity.*

## Additional Constraints

- **Language/runtime**: Java 17 baseline (Boot 3.x and 4.x common ground);
  build MUST also pass on the latest LTS.
- **Namespaces**: `jakarta.activation` only; no `javax.*` EE imports.
- **Auto-configuration**: registered via
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  (per-starter); legacy `spring.factories` registration prohibited.
- **Performance**: I/O MUST be buffered/bulk (no unbuffered per-byte reads of
  the transport stream; no per-byte lock acquisition on the hot path).
  Producer/consumer signaling MUST be event-driven (notify on data
  availability), not poll/sleep based.
- **Security**: spool files MUST be created with restrictive permissions in a
  configurable directory, deleted on close/failure; header and root-part sizes
  MUST be bounded to prevent memory-exhaustion attacks on the server read path.
- **Open source hygiene**: no originating-organization internal coordinates,
  hosts, or identifiers in published artifacts; Apache-2.0 licensing with header compliance; semantic
  versioning of released artifacts; README, javadoc on public API, and runnable
  server/client/Feign samples maintained per release.

## Development Workflow & Quality Gates

- All work flows through Spec Kit: constitution → specify → clarify → plan →
  tasks → implement; the spec is the source of truth for protocol behavior,
  and wire-format changes require a spec amendment before code.
- Every PR MUST pass: full test suite on both starters (Boot 3.x and 4.x
  matrices), coverage thresholds, and static analysis with no new warnings.
- Public API changes require javadoc and README/sample updates in the same
  change.
- Milestone check-ins with the project owner are required at each Spec Kit
  phase boundary before proceeding to the next phase.

## Governance

This constitution supersedes ad-hoc practice for this repository. Amendments
are made by editing this file with: (a) a version bump per the policy below,
(b) a Sync Impact Report comment at the top of the file, and (c) propagation
of any affected gates into the Spec Kit templates in the same change.

Versioning policy: MAJOR for removing or redefining a principle in a
backward-incompatible way; MINOR for adding a principle or materially
expanding guidance; PATCH for clarifications and wording fixes.

Compliance review: the plan-phase Constitution Check MUST cite each principle
(I–VI) with a pass/violation verdict; violations require a written
justification in the plan's Complexity Tracking section or the plan is
rejected. Reviews of implementation PRs verify the gates in Development
Workflow & Quality Gates.

**Version**: 1.0.0 | **Ratified**: 2026-07-04 | **Last Amended**: 2026-07-04
