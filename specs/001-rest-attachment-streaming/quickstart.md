# Quickstart & Validation Guide: restxop

**Purpose**: runnable scenarios proving the feature end-to-end. Contracts:
[wire-format](contracts/wire-format.md), [public-api](contracts/public-api.md).

## Prerequisites

- JDK 17 (matrix job also runs latest LTS)
- Maven 3.9+
- Repo root: `restxop/` reactor (created during implementation)

## 1. Full build + conformance matrix

```bash
cd restxop
mvn -T1C verify
```

**Expected**: all modules build; `restxop-testkit` conformance suite runs
inside both starter test modules with identical results (SC-005); coverage
gates pass (non-zero thresholds, constitution VI).

## 2. P1 round trip — server → client, large file, bounded heap (SC-001/SC-002)

Terminal A (either generation; boot4 shown):

```bash
cd restxop/restxop-samples/sample-server-boot4
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx256m"
```

Terminal B:

```bash
cd restxop/restxop-samples/sample-client-boot4
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx256m" \
  -Dspring-boot.run.arguments="--restxop.sample.size=1GB"
```

**Expected**: client logs payload fields within ~2 s of response start,
*before* transfer completes; prints matching SHA-256 for sent vs received;
no OutOfMemoryError on either side; `targetFile` checksum equals source.
Repeat with boot3 samples.

## 3. Failure & cleanup suite (SC-004)

```bash
cd restxop
mvn -pl restxop-testkit -Dgroups=failure verify
```

**Expected**: all failure-injection tests green (source severed at every
phase, abandonment, early close, malformed fixtures, timeout expiry);
suite asserts zero residual files in the spool directory and zero leaked
connections after each test.

## 4. Rich-graph fidelity (P3, SC-003)

```bash
mvn -pl restxop-testkit -Dgroups=fidelity verify
```

**Expected**: checksum-verified round trips for: nested attachment, list
of attachments, null field, zero-attachment payload (returns immediately),
duplicate-reference dedup, out-of-order consumption with spool crossover.

## 5. Load scenario (SC-009)

```bash
mvn -pl restxop-samples/sample-server-boot4 -Dgroups=load verify
```

**Expected**: 100 concurrent mixed exchanges (spool-triggering sizes)
complete with zero failures/timeout violations/cross-talk.

## 6. Throughput guard (SC-006, tagged perf; informational in CI)

```bash
mvn -pl restxop-testkit -Dgroups=perf verify
```

**Expected**: 100 MB loopback transfer through restxop ≥ 50% of the plain
streamed-HTTP baseline measured by the same harness.

## 7. Legacy interop (P5)

```bash
mvn -pl restxop-testkit -Dgroups=legacy verify
```

**Expected**: legacy `message*.http` fixtures parse byte-exactly with
`legacy-compat.enabled=true` and are rejected (415-equivalent) when off;
compat-mode writer output matches captured legacy-reader expectations.
Optional live check: run the archived legacy sample server
(developer-local workspace outside this repository; Java 11) against the
new client in compat mode.

## 8. Docs / adoption check (SC-007)

Follow `restxop/README.md` from a blank Boot 3.2+ or 4.x project: add one
starter, one config line if form uploads are needed, a payload class with
an `Attachment` field, a controller, and a client call. Target: working
round trip in < 30 minutes without reading anything but the README.
