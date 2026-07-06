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

import { MalformedMessageError } from "./errors.js";

/**
 * Part header-block and structured-value parsing per wire-format §3–§4:
 * RFC-822-style `Name: value` lines, case-insensitive names, trimmed
 * values, folding unfolded on read, CRLF/LF tolerated, bytes interpreted as
 * latin-1, `--` legal inside values.
 */

export interface PartHeaders {
  first(name: string): string | undefined;
  /** Normalized Content-ID (§4), when present. */
  contentId?: string;
  /** Raw Content-Type value, when present. */
  contentType?: string;
  /** Filename: RFC 6266 `filename*` > `filename` > legacy `name`. */
  filename?: string;
}

/**
 * Index just past the blank line terminating a header block starting at
 * {@code from}, or -1 if it has not arrived yet. Accepts CRLF and LF lines.
 */
export function findHeaderBlockEnd(bytes: Uint8Array, from = 0): number {
  for (let i = from; i < bytes.length; i++) {
    if (bytes[i] !== 0x0a) continue;
    // line ended at i; blank line = next line ends immediately
    if (bytes[i + 1] === 0x0a) return i + 2;
    if (bytes[i + 1] === 0x0d && bytes[i + 2] === 0x0a) return i + 3;
  }
  return -1;
}

/** Parses a complete header block (bytes up to and including its blank line). */
export function parseHeaderBlock(block: Uint8Array): PartHeaders {
  const text = latin1(block);
  const rawLines = text.split(/\r?\n/);
  const logical: string[] = [];
  for (const line of rawLines) {
    if (line === "") continue;
    if ((line.startsWith(" ") || line.startsWith("\t")) && logical.length > 0) {
      logical[logical.length - 1] += line; // unfold: drop the line break
    } else {
      logical.push(line);
    }
  }
  const values = new Map<string, string[]>();
  for (const line of logical) {
    const colon = line.indexOf(":");
    if (colon <= 0) {
      throw new MalformedMessageError(
        `part header line has no name-colon-value shape: '${abbreviate(line)}'`,
      );
    }
    const name = line.slice(0, colon).trim().toLowerCase();
    const value = line.slice(colon + 1).trim();
    const list = values.get(name);
    if (list) list.push(value);
    else values.set(name, [value]);
  }

  const first = (name: string) => values.get(name.toLowerCase())?.[0];
  const rawId = first("content-id");
  const disposition = first("content-disposition");
  let filename: string | undefined;
  if (disposition !== undefined) {
    const params = parseContentTypeParams(disposition).params;
    const extended = params.get("filename*");
    filename =
      extended === undefined
        ? (params.get("filename") ?? params.get("name"))
        : decodeExtValue(extended);
  }
  return {
    first,
    contentId: rawId === undefined ? undefined : normalizeId(rawId),
    contentType: first("content-type"),
    filename,
  };
}

/** Parses `type/subtype; name=value; name="quoted"` structured values. */
export function parseContentTypeParams(headerValue: string): {
  mediaType: string;
  params: Map<string, string>;
} {
  const value = headerValue.trim();
  if (!value) throw new MalformedMessageError("missing Content-Type header value");
  const semicolon = value.indexOf(";");
  const mediaType = (semicolon < 0 ? value : value.slice(0, semicolon)).trim().toLowerCase();
  if (!mediaType) throw new MalformedMessageError(`empty media type in: ${headerValue}`);
  const params = new Map<string, string>();
  let i = semicolon < 0 ? value.length : semicolon + 1;
  while (i < value.length) {
    while (i < value.length && (value[i] === " " || value[i] === "\t" || value[i] === ";")) i++;
    if (i >= value.length) break;
    const equals = value.indexOf("=", i);
    if (equals < 0) break; // trailing garbage: lenient
    const name = value.slice(i, equals).trim().toLowerCase();
    i = equals + 1;
    while (i < value.length && (value[i] === " " || value[i] === "\t")) i++;
    let paramValue: string;
    if (value[i] === '"') {
      let out = "";
      i++;
      while (i < value.length && value[i] !== '"') {
        let c = value[i]!;
        if (c === "\\" && i + 1 < value.length) {
          i++;
          c = value[i]!;
        }
        out += c;
        i++;
      }
      i++; // past closing quote
      paramValue = out;
    } else {
      let end = value.indexOf(";", i);
      if (end < 0) end = value.length;
      paramValue = value.slice(i, end).trim();
      i = end;
    }
    if (name && !params.has(name)) params.set(name, paramValue);
  }
  return { mediaType, params };
}

/** Normalization per wire-format §4: one `<>` pair, then one `cid:` prefix. */
export function normalizeId(raw: string): string {
  let id = raw.trim();
  if (id.length >= 2 && id.startsWith("<") && id.endsWith(">")) id = id.slice(1, -1);
  if (id.toLowerCase().startsWith("cid:")) id = id.slice(4);
  return id;
}

/** RFC 5987 ext-value: charset'language'percent-encoded. */
function decodeExtValue(extValue: string): string {
  const firstQuote = extValue.indexOf("'");
  const secondQuote = firstQuote < 0 ? -1 : extValue.indexOf("'", firstQuote + 1);
  if (secondQuote < 0) return extValue; // not ext-value shaped: expose as-is
  const charset = extValue.slice(0, firstQuote) || "utf-8";
  const encoded = extValue.slice(secondQuote + 1);
  const bytes: number[] = [];
  for (let i = 0; i < encoded.length; i++) {
    if (encoded[i] === "%" && i + 2 < encoded.length + 1) {
      const hex = Number.parseInt(encoded.slice(i + 1, i + 3), 16);
      if (!Number.isNaN(hex)) {
        bytes.push(hex);
        i += 2;
        continue;
      }
    }
    bytes.push(encoded.charCodeAt(i) & 0xff);
  }
  try {
    return new TextDecoder(charset).decode(new Uint8Array(bytes));
  } catch {
    return new TextDecoder("utf-8").decode(new Uint8Array(bytes));
  }
}

function latin1(bytes: Uint8Array): string {
  let out = "";
  for (const byte of bytes) out += String.fromCharCode(byte);
  return out;
}

function abbreviate(line: string): string {
  return line.length <= 60 ? line : `${line.slice(0, 57)}...`;
}
