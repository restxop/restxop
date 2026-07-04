# Contract: restxop Wire Format v1

**Status**: Normative. Changes require a spec amendment (constitution,
Development Workflow). Verified by `restxop-testkit` fixtures byte-for-byte.

## 1. Outer message

- HTTP body is a MIME multipart entity per RFC 2046/2387.
- `Content-Type: multipart/related; type="application/json";
  boundary="<boundary>"; start="<root-cid>"`
  - Parameter names case-insensitive; values MAY be quoted (writers MUST
    quote). `type` MUST be `application/json`. `start` MUST equal the root
    part's Content-ID (angle-bracketed form).
  - `boundary`: writer generates one random UUID-derived token per message
    (1–70 boundary-legal chars). Readers MUST NOT assume any shape.
- Missing Content-Type, `boundary`, or `start` → `MalformedMessageException`.

## 2. Framing (normative for readers and writers)

```
preamble (ignored)
CRLF "--" boundary CRLF        ← opening delimiter
part-headers CRLF CRLF? …      ← header block, blank line, then content
part-content
CRLF "--" boundary CRLF        ← delimiter between parts
…
CRLF "--" boundary "--" CRLF?  ← closing delimiter
epilogue (drained, ignored)
```

- The delimiter is `CRLF + "--" + boundary`. **The leading CRLF belongs to
  the delimiter, never to part content** (byte-exactness, FR-005).
- Writers MUST emit CRLF line endings exclusively. Readers MUST also accept
  bare-LF framing (delimiter `LF + "--" + boundary`, LF header line
  endings) — FR-007.
- A `--boundary` byte sequence inside part content that is not preceded by
  a line break is content, not a delimiter (FR-006).
- Transfer encoding of parts is always `binary`; no base64/QP support.

## 3. Part header block

- RFC-822-style `Name: value` lines; names case-insensitive; values
  trimmed. Header folding (continuation lines) MUST be accepted on read;
  writers MUST NOT fold. Bytes are ISO-8859-1 interpreted (no charset
  decoding of header values beyond that).
- Total header block size per part ≤ `max-part-header-bytes` →
  `LimitExceededException`.
- `--` sequences inside header values are legal and MUST NOT terminate
  parsing (legacy defect, fixed).

### Root part (MUST be first part)

| Header | Requirement |
|---|---|
| `Content-ID` | MUST match `start` (comparison after normalization §4) |
| `Content-Type` | MUST be `application/json` (parameters ignored) |
| `Content-Transfer-Encoding` | writers emit `binary`; readers ignore |

Root content: UTF-8 JSON of the payload; size ≤ `max-root-part-bytes`.
If the first part's Content-ID does not match `start` →
`MalformedMessageException` (readers do not search later parts).

### Attachment parts (zero or more, any order after root)

| Header | Requirement |
|---|---|
| `Content-ID` | REQUIRED, unique per message; writers emit `<uuid>` (bracketed) |
| `Content-Type` | writers emit the attachment's content type, else `application/octet-stream`; readers expose it (FR-019) |
| `Content-Disposition` | writers emit `attachment; filename="<name>"` (RFC 6266 quoting; filename percent-encoded via `filename*` when non-ASCII) when a filename exists; readers expose it |
| `Content-Transfer-Encoding` | writers emit `binary`; readers ignore |

Total part count ≤ `max-parts`.

## 4. Content-ID / reference normalization

Normalized ID = raw value, minus one leading `<` and trailing `>` pair if
present, minus one leading `cid:` prefix if present (case-insensitive).
All matching (start↔root, href↔part) uses normalized IDs.

## 5. Attachment references in the root JSON

Every attachment value serializes as exactly:

```json
{ "Include": { "href": "cid:<normalized-content-id>" } }
```

- `Include` key: exact case. `href` MUST be a `cid:` URI (RFC 2392).
- Null attachment values serialize as JSON `null`; no part is emitted;
  they deserialize to null (FR-011).
- Duplicate references to one attachment instance carry the same href;
  exactly one part is transmitted (FR-012).
- A reference with no matching part in the message: reading that
  attachment raises `AttachmentUnavailableException` when the message ends
  without the part (FR-009).
- A part whose Content-ID matches no reference: skipped, logged at WARN
  (Clarification #2).

## 6. Error taxonomy (reader)

| Condition | Error |
|---|---|
| missing/invalid outer Content-Type, boundary, start | MalformedMessageException |
| first part ≠ start, missing part Content-ID, truncated message (EOF before closing delimiter) | MalformedMessageException |
| root/header/part-count/spool bounds exceeded | LimitExceededException |
| exchange TTL or advance deadline exceeded | ExchangeTimeoutException |
| referenced part absent at message end | AttachmentUnavailableException |
| transport failure mid-message | ExchangeFailedException (cause attached; delivered to all pending consumers) |

No condition may surface as NullPointerException, an indefinite block, or
unbounded memory growth (FR-009).

## 7. Legacy compatibility mode (off by default; deprecated)

When `legacy-compat.enabled=true`, additionally:

**Read**: accept outer media type `composite/related` (same parameters);
accept unbracketed / non-`cid:` identifiers (§4 normalization already
does); accept absent Content-Disposition. Legacy writer framing is
standard-conformant, so fidelity is byte-exact.

**Write**: emit `composite/related; type="application/json";
boundary="<uuid>"; start="<mainpart>"`; root `Content-ID: <mainpart>`;
attachment `Content-ID` bare UUID (unbracketed); href = bare UUID (no
`cid:`); include a `Response-ID` header (UUID) on the HTTP message;
`Content-Disposition: attachment;name="<name>"` (legacy shape).
Known, documented legacy-reader defects (its +2-byte trailing CRLF on
attachments; its hang on zero-attachment messages) are out of scope:
compat mode messages MUST contain ≥1 attachment when targeting legacy
readers (documented; not enforceable by the writer).

**Fixtures**: the captured legacy-format `message*.http` fixtures at
`specs/001-rest-attachment-streaming/legacy-fixtures/` are the
conformance authority for this mode.
