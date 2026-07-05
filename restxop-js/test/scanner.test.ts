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
import { MessageScanner, type ScanEvent } from "../src/scanner.js";
import { CHUNK_MATRIX, concat, latin1, latin1Bytes, loadBodyOnly } from "./fixtures.js";

const BOUNDARY = "39783eb8-26f4-4d49-bc54-f44ca1dff15c";

/** Assembles a CRLF-framed message body around raw part-region bytes. */
function message(boundary: string, ...parts: string[]): Uint8Array {
  let out = "";
  for (const part of parts) out += `\r\n--${boundary}\r\n${part}`;
  out += `\r\n--${boundary}--\r\n`;
  return latin1Bytes(out);
}

/** Feeds bytes at a chunk size; returns per-part concatenated regions. */
function scan(boundary: string, bytes: Uint8Array, chunkSize: number): Uint8Array[] {
  const scanner = new MessageScanner(boundary);
  const events: ScanEvent[] = [];
  const step = chunkSize === Infinity ? bytes.length || 1 : chunkSize;
  for (let offset = 0; offset < bytes.length; offset += step) {
    events.push(...scanner.push(bytes.slice(offset, Math.min(offset + step, bytes.length))));
  }
  events.push(...scanner.end());

  const parts: Uint8Array[][] = [];
  let current: Uint8Array[] | null = null;
  let done = false;
  for (const event of events) {
    expect(done, "no events after done").toBe(false);
    switch (event.kind) {
      case "part-open":
        expect(current, "part-open only between parts").toBeNull();
        current = [];
        break;
      case "bytes":
        expect(current, "bytes only inside a part").not.toBeNull();
        current!.push(event.bytes);
        break;
      case "part-close":
        expect(current).not.toBeNull();
        parts.push(current!);
        current = null;
        break;
      case "done":
        expect(current, "done only between parts").toBeNull();
        done = true;
        break;
    }
  }
  expect(done, "message must complete").toBe(true);
  return parts.map(concat);
}

describe.each(CHUNK_MATRIX.map((size) => [size] as const))("chunk size %s", (size) => {
  it("delimiter owns its leading CRLF — content is byte-exact", () => {
    const parts = scan(BOUNDARY, message(BOUNDARY, "This is a test"), size);
    expect(parts).toHaveLength(1);
    expect(latin1(parts[0]!)).toBe("This is a test");
  });

  it("multiple parts split exactly, including an empty part", () => {
    const body = message(BOUNDARY, "first part", "second\r\nwith internal CRLF\r\n", "");
    const parts = scan(BOUNDARY, body, size);
    expect(parts.map(latin1)).toEqual(["first part", "second\r\nwith internal CRLF\r\n", ""]);
  });

  it("boundary-like content is not split", () => {
    const content =
      `data --${BOUNDARY} inline\r\n` + `--${BOUNDARY}X wrong tail\r\n` + "tail text";
    const parts = scan(BOUNDARY, message(BOUNDARY, content), size);
    expect(parts.map(latin1)).toEqual([content]);
  });

  it("adversarial self-similar boundary parses", () => {
    const boundary = "3978--3978--3968--3958--3948";
    const root = '{"field1":"--3978--3978--3978 ---String Value 2"}';
    const parts = scan(boundary, message(boundary, root, "This is a test"), size);
    expect(parts.map(latin1)).toEqual([root, "This is a test"]);
  });

  it("bare-LF framing is accepted", () => {
    const body = latin1Bytes(
      `\n--${BOUNDARY}\n` + "part one lf" + `\n--${BOUNDARY}\n` + "part two lf" + `\n--${BOUNDARY}--\n`,
    );
    const parts = scan(BOUNDARY, body, size);
    expect(parts.map(latin1)).toEqual(["part one lf", "part two lf"]);
  });

  it("preamble is ignored and the first delimiter may lack its leading CRLF", () => {
    const noPreamble = latin1Bytes(
      `--${BOUNDARY}\r\n` + "no preamble at all" + `\r\n--${BOUNDARY}--\r\n`,
    );
    expect(scan(BOUNDARY, noPreamble, size).map(latin1)).toEqual(["no preamble at all"]);

    const withPreamble = latin1Bytes(
      `this is preamble to ignore\r\n--${BOUNDARY}\r\n` + "real content" + `\r\n--${BOUNDARY}--\r\n`,
    );
    expect(scan(BOUNDARY, withPreamble, size).map(latin1)).toEqual(["real content"]);
  });

  it("transport padding after the boundary is tolerated", () => {
    const body = latin1Bytes(
      `\r\n--${BOUNDARY}  \t \r\n` + "padded part" + `\r\n--${BOUNDARY}--  \r\n`,
    );
    expect(scan(BOUNDARY, body, size).map(latin1)).toEqual(["padded part"]);
  });

  it("closing delimiter at EOF without a final line break is accepted, epilogue ignored", () => {
    const noBreak = latin1Bytes(`\r\n--${BOUNDARY}\r\n` + "content" + `\r\n--${BOUNDARY}--`);
    expect(scan(BOUNDARY, noBreak, size).map(latin1)).toEqual(["content"]);

    const withEpilogue = concat([
      message(BOUNDARY, "payload"),
      latin1Bytes("trailing epilogue garbage that must be ignored"),
    ]);
    expect(scan(BOUNDARY, withEpilogue, size).map(latin1)).toEqual(["payload"]);
  });

  it("truncation mid-part is malformed", () => {
    const full = message(BOUNDARY, "this content will be cut off before the closing delimiter");
    const cut = full.slice(0, full.length - 45);
    expect(() => scan(BOUNDARY, cut, size)).toThrow(MalformedMessageError);
  });

  it("a stream with no delimiter at all is malformed", () => {
    expect(() => scan(BOUNDARY, latin1Bytes("no delimiter anywhere in this stream"), size)).toThrow(
      MalformedMessageError,
    );
  });
});

describe("captured legacy adversarial fixture", () => {
  it("splits the repeat-boundary capture exactly", async () => {
    const boundary = "3978--3978--3968--3958--3948";
    const fixture = await loadBodyOnly(
      "legacy/message-repeats.http",
      `composite/related; boundary="${boundary}"`,
    );
    for (const size of [1, 7, 4096, Infinity]) {
      const parts = scan(boundary, fixture.body, size);
      expect(parts).toHaveLength(2);
      const attachment = latin1(parts[1]!);
      expect(attachment.endsWith("\r\n\r\nThis is a test")).toBe(true);
      expect(attachment.endsWith("This is a test\r\n")).toBe(false);
    }
  });
});
