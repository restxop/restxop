# restxop

Streamed binary attachments for REST services — MTOM/XOP semantics over
standard `multipart/related` (RFC 2387) with a JSON root part.

One request (or response) carries an ordinary typed JSON payload **and**
its binary attachments: the receiver gets the typed object as soon as the
root part arrives, while each attachment field streams behind it. No
base64 inflation, no whole-file buffering, no second round trip.

```
--boundary
Content-ID: <root>
Content-Type: application/json

{"title":"Q3 report","document":{"Include":{"href":"cid:att-1"}}}
--boundary
Content-ID: <att-1>
Content-Type: application/pdf
Content-Disposition: attachment; filename="q3.pdf"

…streamed binary bytes…
--boundary--
```

## Deliverables

| Directory | What | For |
|---|---|---|
| [`restxop/`](restxop/) | Java library (Maven reactor) | Spring Boot 3.2+ / 4.0+ services — server and client, eager-drain read model with bounded buffers and disk spill |
| [`restxop-js/`](restxop-js/) | JavaScript/TypeScript client | Browsers and Node ≥ 20 — zero dependencies, < 10 KB, pull-based over native `fetch`/`ReadableStream`, payload-first |

Both implementations are conformance-tested byte-for-byte against a shared
wire-fixture corpus ([`restxop/restxop-testkit`](restxop/restxop-testkit/)),
including live cross-implementation round trips in both directions.

## Specification

The project is developed spec-first. The normative wire format, design
contracts, decision records, and validated acceptance runs live in
[`specs/`](specs/), governed by the project constitution in
[`.specify/memory/constitution.md`](.specify/memory/constitution.md):

- [Wire format v1](specs/001-rest-attachment-streaming/contracts/wire-format.md) — normative for readers and writers
- [Feature 001](specs/001-rest-attachment-streaming/) — the Java library
- [Feature 002](specs/002-js-client/) — the JavaScript client

## License

[Apache License 2.0](LICENSE)
