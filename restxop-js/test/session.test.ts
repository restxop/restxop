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
import { CancelledError } from "../src/errors.js";
import { AttachmentHandle, readMessage } from "../src/index.js";
import { chunked, concat, latin1, latin1Bytes, loadFixture } from "./fixtures.js";

interface ReportPayload {
  title: string;
  report: AttachmentHandle | null;
}

interface BundlePayload {
  name: string;
  first: AttachmentHandle;
  second: AttachmentHandle;
}

async function readAll(stream: ReadableStream<Uint8Array>): Promise<Uint8Array> {
  const reader = stream.getReader();
  const parts: Uint8Array[] = [];
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    parts.push(value);
  }
  return concat(parts);
}

/** A push-controlled source so tests decide exactly when bytes arrive. */
function gatedSource(): {
  stream: ReadableStream<Uint8Array>;
  feed: (bytes: Uint8Array) => void;
  close: () => void;
} {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const stream = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c;
    },
  });
  return {
    stream,
    feed: (bytes) => controller.enqueue(bytes),
    close: () => controller.close(),
  };
}

const SINGLE_CONTENT = latin1Bytes(
  "first line\r\n--rx-fixture-0001 almost a delimiter\r\n" +
    "binary \u0000\u0001\u0002 bytes with -- dashes",
);

describe("message session (US1)", () => {
  it("delivers the payload at root completion while the attachment is still in flight", async () => {
    const fixture = await loadFixture("canonical/single-attachment.http");
    const gateAt = latin1(fixture.body).indexOf("Content-ID: <att-1>");
    const source = gatedSource();

    // Serve only through the root part + the start of the attachment headers
    source.feed(fixture.body.slice(0, gateAt));
    const message = await readMessage<ReportPayload>(fixture.contentType, source.stream);

    expect(message.payload.title).toBe("Quarterly report");
    expect(message.payload.report).toBeInstanceOf(AttachmentHandle);
    expect(message.attachments).toHaveLength(1);

    // Now let the rest of the wire arrive and consume the attachment
    source.feed(fixture.body.slice(gateAt));
    source.close();
    const received = await message.payload.report!.bytes();
    expect(received).toEqual(SINGLE_CONTENT);
    await message.completed;
  });

  it("exposes wire metadata on the handle", async () => {
    const fixture = await loadFixture("canonical/single-attachment.http");
    const message = await readMessage<ReportPayload>(
      fixture.contentType,
      chunked(fixture.body, 4096),
    );
    const handle = message.payload.report!;
    await handle.bytes();
    expect(handle.filename).toBe("data.bin");
    expect(handle.contentType).toBe("application/octet-stream");
  });

  it("streams byte-exactly and stream()/bytes() are one consumption", async () => {
    const fixture = await loadFixture("canonical/single-attachment.http");
    const message = await readMessage<ReportPayload>(
      fixture.contentType,
      chunked(fixture.body, 7),
    );
    const streamed = await readAll(message.payload.report!.stream());
    expect(streamed).toEqual(SINGLE_CONTENT);
    await expect(message.payload.report!.bytes()).rejects.toThrow(/consum/i);
  });

  it("duplicate hrefs resolve to the same handle instance", async () => {
    const boundary = "dup-boundary-01";
    const body = latin1Bytes(
      `\r\n--${boundary}\r\n` +
        "Content-ID: <root>\r\nContent-Type: application/json\r\n\r\n" +
        '{"left":{"Include":{"href":"cid:one"}},"right":{"Include":{"href":"cid:one"}}}' +
        `\r\n--${boundary}\r\n` +
        "Content-ID: <one>\r\nContent-Type: application/octet-stream\r\n\r\n" +
        "shared bytes" +
        `\r\n--${boundary}--\r\n`,
    );
    const contentType = `multipart/related; type="application/json"; boundary="${boundary}"; start="<root>"`;

    const message = await readMessage<{ left: AttachmentHandle; right: AttachmentHandle }>(
      contentType,
      chunked(body, 3),
    );

    expect(message.payload.left).toBe(message.payload.right);
    expect(message.attachments).toHaveLength(1);
    expect(latin1(await message.payload.left.bytes())).toBe("shared bytes");
  });

  it("null fields stay null and zero-attachment messages complete immediately", async () => {
    const nullFixture = await loadFixture("canonical/null-attachment.http");
    const nullMessage = await readMessage<ReportPayload>(
      nullFixture.contentType,
      chunked(nullFixture.body, 64),
    );
    expect(nullMessage.payload.title).toBe("no report");
    expect(nullMessage.payload.report).toBeNull();
    await nullMessage.completed; // no pulls required

    const zeroFixture = await loadFixture("canonical/zero-attachment.http");
    const zeroMessage = await readMessage<{ message: string; number: number }>(
      zeroFixture.contentType,
      chunked(zeroFixture.body, 64),
    );
    expect(zeroMessage.payload).toEqual({ message: "plain", number: 42 });
    await zeroMessage.completed;
  });

  it("skip() frees the attachment and later parts stay readable", async () => {
    const fixture = await loadFixture("canonical/multi-attachment.http");
    const message = await readMessage<BundlePayload>(fixture.contentType, chunked(fixture.body, 7));

    await message.payload.first.skip();
    expect(latin1(await message.payload.second.bytes())).toBe("%PDF-1.7 fake minimal content");
    await message.completed;
    // A skipped handle's reads say so
    await expect(readAll(message.payload.first.stream())).rejects.toThrow(/skip/);
  });

  it("out-of-order access: the second attachment first, the first from retention", async () => {
    const fixture = await loadFixture("canonical/multi-attachment.http");
    const message = await readMessage<BundlePayload>(fixture.contentType, chunked(fixture.body, 3));

    // Reading the later part drives the wire past the first, which is
    // retained in memory for later byte-exact consumption
    expect(latin1(await message.payload.second.bytes())).toBe("%PDF-1.7 fake minimal content");
    expect(latin1(await message.payload.first.bytes())).toBe("alpha content");
    expect(message.payload.first.filename).toBe("alpha.txt");
    await message.completed;
  });

  it("blob() carries the part's content type and the streamed bytes", async () => {
    const fixture = await loadFixture("canonical/single-attachment.http");
    const message = await readMessage<ReportPayload>(
      fixture.contentType,
      chunked(fixture.body, 64),
    );
    const blob = await message.payload.report!.blob();
    expect(blob.type).toBe("application/octet-stream");
    expect(new Uint8Array(await blob.arrayBuffer())).toEqual(SINGLE_CONTENT);
  });

  it("duplicate references share one consumption", async () => {
    const boundary = "dup-boundary-02";
    const body = latin1Bytes(
      `\r\n--${boundary}\r\n` +
        "Content-ID: <root>\r\nContent-Type: application/json\r\n\r\n" +
        '{"left":{"Include":{"href":"cid:one"}},"right":{"Include":{"href":"cid:one"}}}' +
        `\r\n--${boundary}\r\n` +
        "Content-ID: <one>\r\nContent-Type: application/octet-stream\r\n\r\n" +
        "shared bytes" +
        `\r\n--${boundary}--\r\n`,
    );
    const contentType = `multipart/related; type="application/json"; boundary="${boundary}"; start="<root>"`;
    const message = await readMessage<{ left: AttachmentHandle; right: AttachmentHandle }>(
      contentType,
      chunked(body, 16),
    );
    expect(latin1(await message.payload.left.bytes())).toBe("shared bytes");
    await expect(message.payload.right.bytes()).rejects.toThrow(/consum/i);
  });

  it("unreferenced wire parts are skipped silently", async () => {
    const boundary = "extra-part-01";
    const body = latin1Bytes(
      `\r\n--${boundary}\r\n` +
        "Content-ID: <root>\r\nContent-Type: application/json\r\n\r\n" +
        '{"title":"t","report":{"Include":{"href":"cid:wanted"}}}' +
        `\r\n--${boundary}\r\n` +
        "Content-ID: <noise>\r\nContent-Type: application/octet-stream\r\n\r\n" +
        "never referenced" +
        `\r\n--${boundary}\r\n` +
        "Content-ID: <wanted>\r\nContent-Type: application/octet-stream\r\n\r\n" +
        "the good part" +
        `\r\n--${boundary}--\r\n`,
    );
    const contentType = `multipart/related; type="application/json"; boundary="${boundary}"; start="<root>"`;
    const message = await readMessage<ReportPayload>(contentType, chunked(body, 5));
    expect(message.attachments).toHaveLength(1);
    expect(latin1(await message.payload.report!.bytes())).toBe("the good part");
    await message.completed;
  });

  it("bounds that exactly accommodate the message pass", async () => {
    const fixture = await loadFixture("canonical/multi-attachment.http");
    const message = await readMessage<BundlePayload>(fixture.contentType, chunked(fixture.body, 64), {
      maxParts: 3, // root + two attachments, exactly
      maxRootBytes: 4096, // tight but sufficient for the fixture's root
      maxPartHeaderBytes: 512,
    });
    expect(latin1(await message.payload.first.bytes())).toBe("alpha content");
    expect(latin1(await message.payload.second.bytes())).toBe("%PDF-1.7 fake minimal content");
    await message.completed;
  });

  it("the read-idle deadline bounds waits on a stalled source", async () => {
    const fixture = await loadFixture("canonical/single-attachment.http");
    const gateAt = latin1(fixture.body).indexOf("Content-ID: <att-1>");
    const source = gatedSource();
    source.feed(fixture.body.slice(0, gateAt));

    const message = await readMessage<ReportPayload>(fixture.contentType, source.stream, {
      readIdleTimeoutMs: 200,
    });
    const start = Date.now();
    await expect(message.payload.report!.bytes()).rejects.toThrow(CancelledError);
    const waited = Date.now() - start;
    expect(waited).toBeGreaterThanOrEqual(150);
    expect(waited).toBeLessThan(2000);
  });
});
