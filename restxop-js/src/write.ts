/*
 * Copyright 2026 the restxop contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Message writing, v1: in-memory assembly following the canonical writer
 * shapes of wire-format §1–5 — random UUID boundary, bracketed
 * Content-IDs, quoted outer parameters, CRLF framing, RFC 6266/5987
 * filenames, binary transfer encoding.
 */

/** One attachment to send: the source plus optional name/type overrides. */
export class AttachmentSource {
  /** @internal */
  constructor(
    readonly source: Blob | Uint8Array,
    readonly filename?: string,
    readonly contentType?: string,
  ) {}
}

/** Wraps a source for use as an attachment field in a payload. */
export function attachment(
  source: Blob | Uint8Array,
  meta?: { filename?: string; contentType?: string },
): AttachmentSource {
  const fileName = meta?.filename ?? (source instanceof File ? source.name : undefined);
  const type =
    meta?.contentType ?? (source instanceof Blob && source.type ? source.type : undefined);
  return new AttachmentSource(source, fileName, type);
}

/** The assembled message: pass both to your HTTP client as-is. */
export interface BuiltMessage {
  /** The outer multipart/related Content-Type (with boundary and start). */
  contentType: string;
  /** `Uint8Array` when all sources are byte arrays, `Blob` otherwise. */
  body: Blob | Uint8Array;
}

/**
 * Assembles one message from a payload whose attachment fields are
 * {@link AttachmentSource} values (see {@link attachment}). Null fields
 * stay null and emit no part; a source referenced from several fields is
 * transmitted once with a shared href.
 */
export function buildMessage(payload: unknown): BuiltMessage {
  const boundary = crypto.randomUUID();
  const rootId = crypto.randomUUID();
  const parts: { id: string; attachment: AttachmentSource }[] = [];
  const ids = new Map<AttachmentSource, string>();

  const substituted = substitute(payload, (source) => {
    let id = ids.get(source);
    if (!id) {
      id = crypto.randomUUID();
      ids.set(source, id);
      parts.push({ id, attachment: source });
    }
    return { Include: { href: `cid:${id}` } };
  });

  const pieces: (Uint8Array | Blob)[] = [];
  pieces.push(
    ascii(
      `\r\n--${boundary}\r\n` +
        `Content-ID: <${rootId}>\r\n` +
        "Content-Type: application/json\r\n" +
        "Content-Transfer-Encoding: binary\r\n\r\n",
    ),
    new TextEncoder().encode(JSON.stringify(substituted)),
  );
  for (const part of parts) {
    const type = part.attachment.contentType ?? "application/octet-stream";
    const disposition =
      part.attachment.filename === undefined
        ? ""
        : `Content-Disposition: attachment; ${dispositionFilename(part.attachment.filename)}\r\n`;
    pieces.push(
      ascii(
        `\r\n--${boundary}\r\n` +
          `Content-ID: <${part.id}>\r\n` +
          `Content-Type: ${type}\r\n` +
          disposition +
          "Content-Transfer-Encoding: binary\r\n\r\n",
      ),
      part.attachment.source,
    );
  }
  pieces.push(ascii(`\r\n--${boundary}--\r\n`));

  const contentType =
    `multipart/related; type="application/json"; ` +
    `boundary="${boundary}"; start="<${rootId}>"`;
  if (pieces.every((piece) => piece instanceof Uint8Array)) {
    const total = pieces.reduce((n, piece) => n + piece.length, 0);
    const body = new Uint8Array(total);
    let offset = 0;
    for (const piece of pieces) {
      body.set(piece, offset);
      offset += piece.length;
    }
    return { contentType, body };
  }
  return { contentType, body: new Blob(pieces as BlobPart[]) };
}

/** Deep-copies the payload replacing AttachmentSource values with refs. */
function substitute(
  value: unknown,
  reference: (source: AttachmentSource) => unknown,
): unknown {
  if (value instanceof AttachmentSource) return reference(value);
  if (Array.isArray(value)) return value.map((item) => substitute(item, reference));
  if (value !== null && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [key, item] of Object.entries(value)) out[key] = substitute(item, reference);
    return out;
  }
  return value;
}

/** RFC 6266: quoted filename for ASCII, RFC 5987 filename* otherwise. */
function dispositionFilename(filename: string): string {
  // eslint-disable-next-line no-control-regex
  if (/^[\x20-\x7e]*$/.test(filename)) {
    return `filename="${filename.replace(/([\\"])/g, String.raw`\$1`)}"`;
  }
  return `filename*=UTF-8''${rfc5987(filename)}`;
}

function rfc5987(value: string): string {
  // encodeURIComponent leaves !'()* unencoded; RFC 5987 attr-char excludes
  // '()* (! is allowed)
  return encodeURIComponent(value).replace(
    /['()*]/g,
    (ch) => `%${ch.charCodeAt(0).toString(16).toUpperCase()}`,
  );
}

function ascii(text: string): Uint8Array {
  const out = new Uint8Array(text.length);
  for (let i = 0; i < text.length; i++) out[i] = text.charCodeAt(i) & 0xff;
  return out;
}
