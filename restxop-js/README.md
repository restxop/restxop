# restxop-js

Consume [restxop](../restxop) attachment messages — `multipart/related`
with a JSON root part — from browsers and Node. One request delivers your
typed payload **and** its binary attachments: the payload resolves the
moment the root part arrives, while each attachment field is a lazily
consumable stream behind it.

- **Zero runtime dependencies**, ESM, TypeScript declarations, < 10 KB
  compressed.
- **Pull-based**: the platform's native `fetch`/`ReadableStream`
  backpressure paces the transfer. Nothing is drained eagerly; nothing is
  spooled to disk.
- **Byte-exact**: conformance-tested against the shared wire-fixture
  corpus (the same fixtures the Java library pins), in Node and in a real
  browser engine, across a chunk-size matrix from 1 byte to whole-body.

## Install

```bash
npm install restxop-js
```

Requires Node ≥ 20 or any modern browser (native `fetch` + Web Streams).

## Reading: payload first, attachments on demand

```ts
import { restxopFetch, type AttachmentHandle } from "restxop-js";

interface DocumentPayload {
  title: string;
  pages: number;
  data: AttachmentHandle; // was {"Include":{"href":"cid:…"}} on the wire
}

const message = await restxopFetch<DocumentPayload>("https://api.example.com/document");

// Resolves as soon as the JSON root part arrives — the attachment has
// typically not even been requested from the network yet
console.log(message.payload.title, message.payload.pages);
```

Attachment fields in the payload are `AttachmentHandle`s. Consume each one
**once**, in whichever style fits:

```ts
// Streaming (backpressure-paced):
const reader = message.payload.data.stream().getReader();
for (;;) {
  const { done, value } = await reader.read();
  if (done) break;
  progress(value.length);
}

// Whole-content convenience:
const bytes = await message.payload.data.bytes();   // Uint8Array
const blob  = await message.payload.data.blob();    // Blob typed by the part

// Not interested: free it (otherwise an unread earlier part is retained
// in memory while you read later ones)
await message.payload.data.skip();

// Wire metadata, available once the part arrives:
message.payload.data.filename;     // from Content-Disposition (RFC 6266/5987)
message.payload.data.contentType;  // from the part's Content-Type

// Clean end of message:
await message.completed;
```

Consumption is in message order. Reading a later attachment first is fine —
earlier unconsumed parts are retained in memory until you read or `skip()`
them. `null` payload fields stay `null`; duplicate references yield the
same handle instance.

## Cancellation

Pass a standard `AbortSignal`; it cancels any phase — before the payload,
mid-attachment, or while idle — and the underlying stream is released
promptly:

```ts
const controller = new AbortController();
const message = await restxopFetch(url, { signal: controller.signal });
// … later:
controller.abort(); // pending/subsequent reads reject CancelledError
```

Every pull is also bounded by `readIdleTimeoutMs` (default 60 s), so a
stalled server can never hang your app.

## Error handling

Every failure is a typed `RestxopError` subtype — never a hang, never
silent corruption:

| Error | When |
| --- | --- |
| `MalformedMessageError` | wire-format violation: missing parameters, bad framing, truncation |
| `LimitExceededError` | a configured bound exceeded — carries `limit` (name) and `value` |
| `AttachmentUnavailableError` | a referenced part never arrived by end of message |
| `TransferError` | network/source failure mid-message (`cause` attached) |
| `CancelledError` | abort or read-idle expiry (`name === "AbortError"`, platform-idiomatic) |

A failure mid-message rejects blocked *and* subsequent reads with the same
typed error. Parts that had already fully arrived stay readable.

## Options

Pass per call via `init.restxop` (or as the third argument to
`readMessage`):

| Option | Default | Meaning |
| --- | --- | --- |
| `maxRootBytes` | 16 MiB | root (JSON) part size bound |
| `maxPartHeaderBytes` | 64 KiB | per-part header block bound |
| `maxParts` | 1000 | part-count bound |
| `readIdleTimeoutMs` | 60 000 | max wait for source progress per pull |
| `signal` | — | standard platform cancellation |

```ts
await restxopFetch(url, { restxop: { maxParts: 10, readIdleTimeoutMs: 10_000 } });
```

## Uploads (v1)

`buildMessage` assembles a payload whose attachment fields are wrapped
with `attachment()`; `restxopFetch.post` is the one-liner:

```ts
import { attachment, restxopFetch } from "restxop-js";

const response = await restxopFetch.post("https://api.example.com/upload", {
  label: file.name,
  data: attachment(file), // Blob | File | Uint8Array (+ optional {filename, contentType})
});
```

**v1 caveat**: upload assembly is in-memory — the whole message body is
buffered before sending (`Uint8Array` for byte-array sources, `Blob`
otherwise, so `File`-backed uploads defer to the platform's file
handling). Fine for the tens-of-megabytes range; a streaming writer is a
possible v2.

Writer output follows the canonical wire shapes: random UUID boundary,
quoted parameters, bracketed Content-IDs, CRLF framing, `filename*` for
non-ASCII names, `null` fields emit no part, duplicated sources are sent
once.

## Using plain `readMessage`

`restxopFetch` is a thin binding over global `fetch`. Any
`ReadableStream<Uint8Array>` plus its Content-Type works:

```ts
import { readMessage } from "restxop-js";

const response = await fetch(url);
const message = await readMessage<MyPayload>(
  response.headers.get("content-type"),
  response.body!,
);
```

The same API runs unchanged under Node ≥ 20 (undici fetch).

## Server prerequisites for browser apps

- **CORS**: the restxop service must allow your app's origin (e.g. Spring
  `@CrossOrigin`). Reads need no custom request headers; uploads send
  `Content-Type: multipart/related`, which requires a CORS preflight —
  the standard annotation handles it.
- Responses must use the standard restxop media type
  (`multipart/related; type="application/json"; boundary; start`). The
  deprecated legacy compat mode is not supported by this client.

## Demo

A small React app showcasing the primary use case — a streamed PDF
rendered alongside its metadata from one request, plus an upload form:

```bash
# Terminal A: sample server
cd ../restxop/restxop-samples/sample-server-boot4
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=18080"

# Terminal B: demo app
cd demo
npm ci
npm run dev     # open the printed URL
```

## Development

```bash
npm ci
npm test              # build + Node suite + bundle-size gate
npm run test:browser  # the same suite in Chromium (Playwright)
npm run test:memory   # SC-003: 100 MiB pass-through, bounded heap
```

Licensed under the Apache License 2.0.
