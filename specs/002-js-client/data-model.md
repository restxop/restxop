# Data Model: JavaScript Client for restxop Attachment Messages

**Date**: 2026-07-05 · **Spec**: [spec.md](spec.md) · **Research**: [research.md](research.md)

Client-tier concepts only; the wire entities themselves are owned by feature
001's wire-format contract v1, which this library consumes unchanged.

## MessageSession (one per consumed response)

| Attribute | Notes |
|---|---|
| `contentType` | outer Content-Type; must be `multipart/related` with `type="application/json"`, `boundary`, `start` (else `MalformedMessageError` before payload delivery) |
| `source` | the byte source (stream reader); pulled only on demand |
| `payload` | delivered once the root part is parsed; `Include` stubs replaced by handles |
| `handles` | normalized Content-ID → AttachmentHandle (duplicates share one handle) |
| `cursor` | wire position: which part the parser is currently at; consumption is in message order |
| `options` | bounds + read-idle timeout snapshot (see Options) |
| `state` | READING-ROOT → DELIVERED → (COMPLETED \| FAILED(cause) \| CANCELLED) |

**Transitions**
- READING-ROOT → DELIVERED: root parsed, payload promise resolved.
- READING-ROOT → FAILED: malformed outer type, root bound breach, transfer
  failure, or abort before delivery (payload promise rejects typed).
- DELIVERED → COMPLETED: closing delimiter reached and every referenced
  handle consumed, skipped, or resolved unavailable.
- DELIVERED → FAILED: transfer failure or structural violation while
  pulling; all pending and future handle reads reject with the causal error.
- any → CANCELLED: abort signal fired; source reader cancelled (connection
  released), retention dropped, reads reject with `CancelledError`.
- Terminal states are final; entering one releases retention buffers.

## AttachmentHandle (one per referenced Content-ID)

| Attribute | Notes |
|---|---|
| `contentId` | normalized per wire-format §4 |
| `filename` / `contentType` | from part headers when the part is reached; `undefined` before arrival and for absent headers |
| `retention` | chunks buffered when the wire moved past/through this part before the app consumed it; freed on consumption, skip, or session end |
| `state` | PENDING → STREAMING \| RETAINED → CONSUMED \| SKIPPED \| UNAVAILABLE \| FAILED \| CANCELLED |

**Semantics**
- `stream()` / `bytes()` / `blob()`: single logical consumption (subsequent
  calls after consumption reject; repeated calls before consumption return
  the same underlying consumption).
- Reading a PENDING handle drives the session cursor forward; earlier
  unconsumed parts encountered on the way are RETAINED in memory.
- UNAVAILABLE: message ended without this part → reads reject with
  `AttachmentUnavailableError`.
- Duplicate `href`s resolve to the same handle instance.

## Options (per call, all optional)

| Option | Default | Constraint |
|---|---|---|
| `maxRootBytes` | 16 MiB | > 0; breach → `LimitExceededError` |
| `maxPartHeaderBytes` | 64 KiB | > 0 |
| `maxParts` | 1000 | > 0 |
| `readIdleTimeoutMs` | 60 000 | > 0; bounds every pull even without a signal |
| `signal` | none | standard `AbortSignal` |

## Error hierarchy

`RestxopError` (base; `name`, `message`)
├── `MalformedMessageError` — wire-format violations, truncation
├── `LimitExceededError` — carries `limit` name and configured `value`
├── `AttachmentUnavailableError` — referenced part never arrived / read after end
├── `TransferError` — network/source failure mid-message (cause attached)
└── `CancelledError` — abort/timeout; `name === "AbortError"`-compatible

## Write model (v1)

`buildMessage(payload)` walks the payload value tree: attachment sources
(`Blob | File | Uint8Array`, with optional filename/contentType overrides)
become `Include` stubs with generated bracketed Content-IDs
(identity-deduplicated); null stays JSON null with no part. Output:
`{ contentType, body }` where body is a `Blob` (browser) / `Uint8Array`
(Node) assembled per wire-format §1–5 canonical writer shapes.
