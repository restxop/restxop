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
 * US4: v1 in-memory message assembly. `buildMessage` output must follow the
 * canonical writer shapes of wire-format §1–5 and round-trip byte-exactly
 * through `readMessage`.
 */

import { describe, expect, it } from "vitest";
import { attachment, buildMessage, readMessage, type AttachmentHandle } from "../src/index.js";
import { chunked, latin1, latin1Bytes } from "./fixtures.js";

async function bodyBytes(body: Blob | Uint8Array): Promise<Uint8Array> {
  return body instanceof Uint8Array ? body : new Uint8Array(await body.arrayBuffer());
}

async function roundTrip<T>(payload: unknown): Promise<{ payload: T; completed: Promise<void> }> {
  const { contentType, body } = buildMessage(payload);
  const message = await readMessage<T>(contentType, chunked(await bodyBytes(body), 64));
  return { payload: message.payload, completed: message.completed };
}

describe("buildMessage canonical shapes (US4)", () => {
  it("emits quoted outer parameters and a bracketed start matching the root part", async () => {
    const { contentType, body } = buildMessage({ note: "no attachments" });
    const match =
      /^multipart\/related; type="application\/json"; boundary="([^"]+)"; start="<([^>]+)>"$/.exec(
        contentType,
      );
    expect(match).not.toBeNull();
    const [, boundary, startId] = match!;
    const text = latin1(await bodyBytes(body));
    expect(text.startsWith(`\r\n--${boundary}\r\n`)).toBe(true);
    expect(text).toContain(`Content-ID: <${startId}>\r\n`);
    expect(text).toContain("Content-Type: application/json\r\n");
    expect(text).toContain("Content-Transfer-Encoding: binary\r\n");
    expect(text.endsWith(`\r\n--${boundary}--\r\n`)).toBe(true);
    // CRLF framing exclusively: no bare LF anywhere in the framing/headers
    expect(text.replace(/\r\n/g, "")).not.toContain("\n");
  });

  it("uses a fresh random boundary per message", () => {
    const first = buildMessage({ a: 1 }).contentType;
    const second = buildMessage({ a: 1 }).contentType;
    expect(first).not.toBe(second);
  });

  it("attachment parts carry bracketed ids, disposition, and binary transfer encoding", async () => {
    const { contentType, body } = buildMessage({
      title: "t",
      report: attachment(latin1Bytes("report-bytes"), {
        filename: "report.bin",
        contentType: "application/x-thing",
      }),
    });
    const text = latin1(await bodyBytes(body));
    const href = /"href":"cid:([^"]+)"/.exec(text);
    expect(href).not.toBeNull();
    expect(text).toContain(`Content-ID: <${href![1]}>\r\n`);
    expect(text).toContain("Content-Type: application/x-thing\r\n");
    expect(text).toContain('Content-Disposition: attachment; filename="report.bin"\r\n');
    expect(contentType).toContain('type="application/json"');
    // Reference shape is exactly {"Include":{"href":"cid:..."}}
    expect(text).toContain(`"report":{"Include":{"href":"cid:${href![1]}"}}`);
  });

  it("defaults the content type to application/octet-stream", async () => {
    const { body } = buildMessage({ data: attachment(latin1Bytes("x")) });
    const text = latin1(await bodyBytes(body));
    expect(text).toContain("Content-Type: application/octet-stream\r\n");
  });

  it("non-ASCII filenames use RFC 5987 filename*", async () => {
    const { body } = buildMessage({
      data: attachment(latin1Bytes("x"), { filename: "naïve – 文件.pdf" }),
    });
    const text = latin1(await bodyBytes(body));
    expect(text).toContain(
      "Content-Disposition: attachment; filename*=UTF-8''na%C3%AFve%20%E2%80%93%20%E6%96%87%E4%BB%B6.pdf\r\n",
    );
  });

  it("null attachment fields stay null and emit no part", async () => {
    const { contentType, body } = buildMessage({ title: "t", report: null });
    const bytes = await bodyBytes(body);
    const boundary = /boundary="([^"]+)"/.exec(contentType)![1]!;
    // Exactly one part: opening delimiter + closing delimiter
    expect(latin1(bytes).split(`--${boundary}`)).toHaveLength(3);
    expect(latin1(bytes)).toContain('"report":null');
  });

  it("a duplicated source emits one part with a shared href", async () => {
    const shared = attachment(latin1Bytes("same bytes"), { filename: "one.bin" });
    const { contentType, body } = buildMessage({ left: shared, right: shared });
    const text = latin1(await bodyBytes(body));
    const boundary = /boundary="([^"]+)"/.exec(contentType)![1]!;
    expect(text.split(`--${boundary}`)).toHaveLength(4); // root + one part
    const hrefs = [...text.matchAll(/"href":"cid:([^"]+)"/g)].map((m) => m[1]);
    expect(hrefs).toHaveLength(2);
    expect(hrefs[0]).toBe(hrefs[1]);
  });
});

describe("buildMessage round trips (US4)", () => {
  it("Uint8Array source: body is a Uint8Array and content round-trips byte-exactly", async () => {
    const content = new Uint8Array(1024).map((_, i) => i & 0xff);
    const built = buildMessage({
      label: "bytes",
      data: attachment(content, { filename: "data.bin" }),
    });
    expect(built.body).toBeInstanceOf(Uint8Array);
    const { payload, completed } = await roundTrip<{ label: string; data: AttachmentHandle }>({
      label: "bytes",
      data: attachment(content, { filename: "data.bin" }),
    });
    expect(payload.label).toBe("bytes");
    expect(await payload.data.bytes()).toEqual(content);
    expect(payload.data.filename).toBe("data.bin");
    await completed;
  });

  it("Blob source: body is a Blob and content round-trips byte-exactly", async () => {
    const content = latin1Bytes("blob content with \r\n--tricky\r\n framing bytes");
    const source = new Blob([content as BlobPart], { type: "application/pdf" });
    const built = buildMessage({ label: "blob", data: attachment(source) });
    expect(built.body).toBeInstanceOf(Blob);
    const message = await readMessage<{ label: string; data: AttachmentHandle }>(
      built.contentType,
      chunked(await bodyBytes(built.body), 7),
    );
    expect(await message.payload.data.bytes()).toEqual(content);
    expect(message.payload.data.contentType).toBe("application/pdf");
    await message.completed;
  });

  it("File source: filename and type flow from the file itself", async () => {
    const content = latin1Bytes("file content");
    const source = new File([content as BlobPart], "upload.txt", { type: "text/plain" });
    const built = buildMessage({ label: "file", data: attachment(source) });
    const message = await readMessage<{ label: string; data: AttachmentHandle }>(
      built.contentType,
      chunked(await bodyBytes(built.body), 64),
    );
    expect(await message.payload.data.bytes()).toEqual(content);
    expect(message.payload.data.filename).toBe("upload.txt");
    expect(message.payload.data.contentType).toBe("text/plain");
    await message.completed;
  });

  it("meta overrides beat File-provided name and type", async () => {
    const source = new File([latin1Bytes("x") as BlobPart], "original.bin", {
      type: "application/octet-stream",
    });
    const built = buildMessage({
      data: attachment(source, { filename: "renamed.txt", contentType: "text/plain" }),
    });
    const text = latin1(await bodyBytes(built.body));
    expect(text).toContain('filename="renamed.txt"');
    expect(text).toContain("Content-Type: text/plain\r\n");
  });

  it("nested and array references round-trip", async () => {
    const a = latin1Bytes("aaa");
    const b = latin1Bytes("bbb");
    const { payload, completed } = await roundTrip<{
      outer: { inner: AttachmentHandle };
      list: Array<AttachmentHandle | string>;
    }>({
      outer: { inner: attachment(a) },
      list: [attachment(b), "plain"],
    });
    expect(await payload.outer.inner.bytes()).toEqual(a);
    expect(await (payload.list[0] as AttachmentHandle).bytes()).toEqual(b);
    expect(payload.list[1]).toBe("plain");
    await completed;
  });
});
