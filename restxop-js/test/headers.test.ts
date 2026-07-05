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

import { describe, expect, it } from "vitest";
import { MalformedMessageError } from "../src/errors.js";
import {
  findHeaderBlockEnd,
  normalizeId,
  parseContentTypeParams,
  parseHeaderBlock,
} from "../src/headers.js";
import { latin1Bytes } from "./fixtures.js";

function parse(block: string) {
  const bytes = latin1Bytes(block);
  const end = findHeaderBlockEnd(bytes);
  expect(end, "block must terminate with a blank line").toBeGreaterThan(-1);
  return parseHeaderBlock(bytes.subarray(0, end));
}

describe("header blocks (wire-format §3)", () => {
  it("parses names case-insensitively with trimmed values and stops at the blank line", () => {
    const bytes = latin1Bytes("Content-ID: <abc>\r\nContent-Type: application/octet-stream\r\n\r\nCONTENT");
    const end = findHeaderBlockEnd(bytes);
    const headers = parseHeaderBlock(bytes.subarray(0, end));
    expect(headers.contentId).toBe("abc");
    expect(headers.contentType).toBe("application/octet-stream");
    expect(headers.first("CONTENT-TYPE")).toBe("application/octet-stream");
    expect(String.fromCharCode(bytes[end]!)).toBe("C"); // content untouched
  });

  it("accepts bare-LF line endings", () => {
    const headers = parse("Content-ID: <lf-part>\nContent-Type: text/plain\n\n");
    expect(headers.contentId).toBe("lf-part");
    expect(headers.contentType).toBe("text/plain");
  });

  it("unfolds continuation lines on read", () => {
    const headers = parse('Content-Disposition: attachment;\r\n filename="folded.txt"\r\n\r\n');
    expect(headers.filename).toBe("folded.txt");
  });

  it("double dashes inside values do not terminate parsing", () => {
    const headers = parse(
      'Content-Disposition: attachment;filename="Test--123--x"\r\nContent-ID: <has--dashes>\r\n\r\n',
    );
    expect(headers.filename).toBe("Test--123--x");
    expect(headers.contentId).toBe("has--dashes");
  });

  it("interprets non-ASCII header bytes as latin-1", () => {
    const headers = parse("X-Custom: caf\xe9\r\n\r\n");
    expect(headers.first("x-custom")).toBe("café");
  });

  it("a header line without a colon is malformed", () => {
    expect(() => parse("this-line-has-no-colon\r\n\r\n")).toThrow(MalformedMessageError);
  });

  it("filename* takes precedence and percent-decodes UTF-8", () => {
    const headers = parse(
      "Content-Disposition: attachment; filename=\"fallback.txt\";" +
        " filename*=UTF-8''na%C3%AFve%20file.txt\r\n\r\n",
    );
    expect(headers.filename).toBe("naïve file.txt");
  });

  it("the legacy 'name' parameter is the last filename fallback", () => {
    expect(parse('Content-Disposition: attachment;name="Test-123"\r\n\r\n').filename).toBe(
      "Test-123",
    );
  });

  it("repeated headers expose the first value", () => {
    expect(parse("X-Dup: first\r\nX-Dup: second\r\n\r\n").first("x-dup")).toBe("first");
  });

  it("findHeaderBlockEnd returns -1 while the blank line has not arrived", () => {
    expect(findHeaderBlockEnd(latin1Bytes("Content-ID: <abc>\r\nContent-Ty"))).toBe(-1);
  });
});

describe("Content-Type parameters (wire-format §1)", () => {
  it("parses quoted parameters case-insensitively", () => {
    const parsed = parseContentTypeParams(
      'MULTIPART/Related; TYPE="application/json"; Boundary="abc-123"; START="<root>"',
    );
    expect(parsed.mediaType).toBe("multipart/related");
    expect(parsed.params.get("type")).toBe("application/json");
    expect(parsed.params.get("boundary")).toBe("abc-123");
    expect(parsed.params.get("start")).toBe("<root>");
  });

  it("parses unquoted tokens, escapes, semicolons and dashes in quotes", () => {
    const parsed = parseContentTypeParams(
      'multipart/related; boundary=ab--cd; note="semi;colon and \\"quote\\""',
    );
    expect(parsed.params.get("boundary")).toBe("ab--cd");
    expect(parsed.params.get("note")).toBe('semi;colon and "quote"');
  });

  it("missing or empty values are malformed", () => {
    expect(() => parseContentTypeParams("")).toThrow(MalformedMessageError);
    expect(() => parseContentTypeParams("   ")).toThrow(MalformedMessageError);
  });
});

describe("id normalization (wire-format §4)", () => {
  it("strips one angle-bracket pair, then one cid: prefix, case-insensitively", () => {
    expect(normalizeId("<abc>")).toBe("abc");
    expect(normalizeId("<<abc>>")).toBe("<abc>");
    expect(normalizeId("cid:abc")).toBe("abc");
    expect(normalizeId("CID:abc")).toBe("abc");
    expect(normalizeId("cid:cid:abc")).toBe("cid:abc");
    expect(normalizeId("<cid:abc>")).toBe("abc");
    expect(normalizeId("  574e6d7e ")).toBe("574e6d7e");
  });
});
