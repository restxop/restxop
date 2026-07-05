# Contract: restxop-js Public API v1

**Status**: Normative for the package's public surface. The wire behavior it
implements is governed by feature 001's
[wire-format contract v1](../../001-rest-attachment-streaming/contracts/wire-format.md)
(standard format only; legacy compat mode out of scope). Signatures shown
indicative; published type declarations govern final form.

## Package

- npm name **`restxop-js`**, ESM-only, zero runtime dependencies, bundled
  `.d.ts`, Apache-2.0. Bundle budget: **< 10 KB compressed** for the full
  entry graph, enforced by the build (SC-006).
- Runs unmodified in current evergreen browsers and Node active LTS.

## Reading

```ts
// Transport-agnostic core (no fetch/DOM dependency):
function readMessage<T = unknown>(
    contentType: string,
    body: ReadableStream<Uint8Array>,
    options?: RestxopOptions,
): Promise<RestxopMessage<T>>;

// Fetch convenience:
function restxopFetch<T = unknown>(
    input: RequestInfo | URL,
    init?: RequestInit & { restxop?: RestxopOptions },
): Promise<RestxopMessage<T>>;

interface RestxopMessage<T> {
    payload: T;                      // Include stubs already replaced by handles
    attachments: AttachmentHandle[]; // reference order, duplicates deduped
    completed: Promise<void>;        // resolves at clean end of message
}

interface AttachmentHandle {
    readonly contentId: string;               // normalized (§4)
    readonly filename?: string;               // set once part headers arrive
    readonly contentType?: string;
    stream(): ReadableStream<Uint8Array>;     // single sequential consumption
    bytes(): Promise<Uint8Array>;             // whole-content convenience
    blob(): Promise<Blob>;
    skip(): Promise<void>;                    // discard without buffering
}

interface RestxopOptions {
    maxRootBytes?: number;        // default 16 MiB
    maxPartHeaderBytes?: number;  // default 64 KiB
    maxParts?: number;            // default 1000
    readIdleTimeoutMs?: number;   // default 60_000; bounds every pull
    signal?: AbortSignal;
}
```

**Read contract**

- `payload` is available as soon as the root part is parsed — attachment
  transfer has typically not completed (and has not even been requested
  beyond the root; pulls are demand-driven).
- Consumption is in message order; reading a later handle first retains
  earlier unconsumed parts in memory (documented memory bound; `skip()`
  avoids it).
- Payload fields that were JSON `null` stay `null`; the same `href` in two
  places yields the same handle instance; unreferenced wire parts are
  skipped silently; a referenced part absent at end of message makes that
  handle's reads reject `AttachmentUnavailableError`.
- Abort (signal) or read-idle expiry cancels the source stream promptly in
  any phase and rejects pending/subsequent operations with
  `CancelledError`.

## Writing (v1, in-memory assembly)

```ts
function attachment(
    source: Blob | File | Uint8Array,
    meta?: { filename?: string; contentType?: string },
): AttachmentSource;

function buildMessage(payload: unknown): { contentType: string; body: Blob | Uint8Array };

// Convenience: buildMessage + fetch POST
restxopFetch.post(input, payload, init?): Promise<Response>;
```

Writer output follows the canonical shapes of wire-format §1–5 (random
boundary, bracketed Content-IDs, quoted parameters, CRLF, RFC 6266 /
`filename*` for non-ASCII names); null fields emit no part; duplicate
sources emit one part.

## Errors

All rejections are `RestxopError` subtypes:
`MalformedMessageError`, `LimitExceededError` (with `limit`, `value`),
`AttachmentUnavailableError`, `TransferError` (with `cause`),
`CancelledError` (AbortError-compatible).

## Server prerequisites for browser clients (documented, FR-014)

- CORS: allow the app origin; no custom request headers are required for
  reads; `Content-Type` is CORS-safelisted for the simple GET path.
- Responses must be the standard restxop media type; the legacy compat mode
  is not supported by this client.

## Compatibility promises

- SemVer from 0.1.0; the public surface above is the compatibility unit.
- The shared fixture corpus is part of the contract: the conformance suite
  (Node + browser engine, chunk-size matrix) must pass for every release.
