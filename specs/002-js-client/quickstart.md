# Quickstart & Validation Guide: restxop-js

**Purpose**: runnable scenarios proving the feature end-to-end. Contracts:
[js-api](contracts/js-api.md); wire behavior per feature 001's
[wire-format](../001-rest-attachment-streaming/contracts/wire-format.md).

## Prerequisites

- Node active LTS + npm
- JDK 17 + Maven (for the sample server; see feature 001's quickstart)
- Repo root contains `restxop-js/` (created during implementation) and the
  built `restxop/` reactor

## 1. Conformance suite — Node (SC-002)

```bash
cd restxop-js
npm ci
npm test
```

**Expected**: the shared fixture corpus (canonical + fidelity + malformed,
loaded by path from `restxop/restxop-testkit/.../fixtures/`) passes
byte-exactly across the chunk-size matrix (1 B … 64 KiB … whole-body);
malformed fixtures produce the documented typed errors; the bundle-size
gate (< 10 KB compressed, SC-006) passes.

## 2. Conformance suite — real browser engine (SC-002)

```bash
cd restxop-js
npm run test:browser
```

**Expected**: the identical suite passes under Chromium (vitest browser
mode / Playwright provider).

## 3. Failure & cancellation suite (SC-004)

Included in `npm test` (`failure.test.ts`): severed sources at every phase,
truncated/malformed fixtures, aborts before payload / mid-attachment / after
delivery, and read-idle expiry.

**Expected**: every case ends promptly in the documented typed error or
clean cancellation; the source stream is cancelled (no dangling reader
locks); aborts take effect within 1 second.

## 4. Bounded-memory pass-through (SC-003)

```bash
cd restxop-js
npm run test:memory        # streams a generated 100 MB message through the parser
```

**Expected**: a 100 MB attachment streams through a pass-through consumer
with library working memory bounded (retention empty on the in-order path);
checksum matches.

## 5. Demo: streamed PDF + metadata (SC-001, the P1 showcase)

Terminal A — sample server (with the new document endpoint):

```bash
cd restxop/restxop-samples/sample-server-boot4
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=18080"
```

Terminal B — demo app:

```bash
cd restxop-js/demo
npm ci
npm run dev            # Vite dev server; open the printed URL
```

**Expected**: the document page issues **one** request to
`http://localhost:18080/document`; the metadata panel (title, author,
pages, created, status, tags) renders within ~1 s while the byte-progress
indicator shows the PDF still streaming; on completion the PDF displays in
the embedded viewer; the network tab confirms a single `multipart/related`
response.

Automated equivalent (both servers running, from `restxop-js/`):

```bash
npm run build && node scripts/validate-sc001.mjs
```

> **Validated 2026-07-05** (T017, Chromium via Playwright, network throttled
> to 4 MiB/s to make the streaming phase observable): exactly **1** request
> to `/document` (`multipart/related; type="application/json"`); metadata
> rendered at **27 ms**; streaming indicator visible while metadata was
> already on screen; 32 MiB transfer completed at **8023 ms**; browser-side
> SHA-256 of the assembled PDF identical to the wire content
> (`bd184fe1…80c4907`). Unthrottled loopback run: metadata 44 ms,
> completion 212 ms, same digest. **SC-001 PASS.**

## 6. Upload round trip (SC-007)

In the demo's upload form, choose a file (~25 MB) and submit.

**Expected**: the server's JSON echo reports the exact byte size and a
SHA-256 matching the file; the request body was a single well-formed
restxop message.

Automated equivalent (both servers running, from `restxop-js/`):

```bash
node scripts/validate-sc007.mjs
```

> **Validated 2026-07-05** (T025, Chromium via Playwright): a generated
> 26,214,400-byte file uploaded through the demo form as exactly **1**
> `multipart/related` request; the Java sample server echoed the exact
> size and a SHA-256 identical to the local digest
> (`439f9931…cdd56c3`) — a JS-writer → Java-reader round trip.
> **SC-007 PASS.**

## 7. Node consumption smoke (US5)

```bash
cd restxop-js
npm run demo:node      # small script: fetches /document, prints payload, checksums the PDF
```

**Expected**: payload printed before transfer completes; checksum matches
the server's source.

> **Validated 2026-07-05** (T026, Node 24 against the boot4 sample server):
> payload printed at 50 ms, 32 MiB transfer complete at 231 ms; SHA-256
> `bd184fe1…80c4907` — identical to the digest the Chromium demo produced
> for the same document (browser parity). **US5 PASS.**

## 8. Docs / adoption check (SC-005)

Follow `restxop-js/README.md` only, from a fresh Vite app: install the
package, call `restxopFetch`, render payload fields, stream the attachment.
Target: working in < 15 minutes.

> **Validated 2026-07-05** (T029): a fresh Vite app scaffolded from
> nothing, with `main.ts` written strictly from the README's reading
> section (only substitution: `npm install` from the local path, as the
> package is not yet published), rendered the payload title and streamed
> the full 4 MiB attachment byte-count on first run — scripted end to end
> in **21 s**, far under the 15-minute human budget. No README gaps found.
> **SC-005 PASS.**
