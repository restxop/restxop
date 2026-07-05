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
 * US2: every malformed/truncated/severed/aborted/timed-out path ends
 * promptly in the documented typed error, and the source stream is always
 * cancelled (SC-004). Driven by the shared malformed fixture corpus plus
 * synthetic sever/abort/stall sources.
 */

import { describe, expect, it } from "vitest";
import {
  AttachmentUnavailableError,
  CancelledError,
  LimitExceededError,
  MalformedMessageError,
  TransferError,
} from "../src/errors.js";
import { readMessage, type AttachmentHandle } from "../src/index.js";
import { chunked, latin1, latin1Bytes, loadFixture } from "./fixtures.js";

interface ReportPayload {
  title: string;
  report: AttachmentHandle | null;
  second?: AttachmentHandle;
}

/** A push-controlled source that also records cancellation for hygiene checks. */
function trackedSource(): {
  stream: ReadableStream<Uint8Array>;
  feed: (bytes: Uint8Array) => void;
  close: () => void;
  sever: (reason: Error) => void;
  cancelledAt: () => number | undefined;
} {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  let cancelledAt: number | undefined;
  const stream = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c;
    },
    cancel() {
      cancelledAt = performance.now();
    },
  });
  return {
    stream,
    feed: (bytes) => controller.enqueue(bytes),
    close: () => controller.close(),
    sever: (reason) => controller.error(reason),
    cancelledAt: () => cancelledAt,
  };
}

/** Chunked replay of fixed bytes, instrumented for cancellation hygiene. */
function trackedReplay(
  bytes: Uint8Array,
  size: number,
): { stream: ReadableStream<Uint8Array>; cancelledAt: () => number | undefined } {
  let offset = 0;
  let cancelledAt: number | undefined;
  const stream = new ReadableStream<Uint8Array>({
    pull(controller) {
      if (offset >= bytes.length) {
        controller.close();
        return;
      }
      const end = Math.min(offset + size, bytes.length);
      controller.enqueue(bytes.slice(offset, end));
      offset = end;
    },
    cancel() {
      cancelledAt = performance.now();
    },
  });
  return { stream, cancelledAt: () => cancelledAt };
}

async function settled(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 20));
}

/** Builds a wire message from parts (canonical framing, boundary rx-t). */
function wire(...parts: { id: string; type: string; content: string }[]): {
  contentType: string;
  bytes: Uint8Array;
} {
  const rendered = parts
    .map(
      (p) =>
        `--rx-t\r\nContent-ID: <${p.id}>\r\nContent-Type: ${p.type}\r\n\r\n${p.content}`,
    )
    .join("\r\n");
  return {
    contentType: 'multipart/related; type="application/json"; boundary="rx-t"; start="<root>"',
    bytes: latin1Bytes(`${rendered}\r\n--rx-t--\r\n`),
  };
}

const ROOT_ONE_REF = { id: "root", type: "application/json", content: '{"title":"t","report":{"Include":{"href":"cid:att-1"}}}' };

describe("malformed fixture corpus (US2)", () => {
  it.each([
    ["malformed/missing-boundary.http", /boundary/],
    ["malformed/missing-start.http", /start/],
    ["malformed/missing-type-param.http", /type/],
  ])("%s rejects before any payload", async (name, pattern) => {
    const fixture = await loadFixture(name);
    const attempt = readMessage(fixture.contentType, chunked(fixture.body, 64));
    await expect(attempt).rejects.toThrow(MalformedMessageError);
    await expect(attempt).rejects.toThrow(pattern);
  });

  it("wrong-first-part rejects at the root with the mismatched id named", async () => {
    const fixture = await loadFixture("malformed/wrong-first-part.http");
    const attempt = readMessage(fixture.contentType, chunked(fixture.body, 64));
    await expect(attempt).rejects.toThrow(MalformedMessageError);
    await expect(attempt).rejects.toThrow(/not-the-root/);
  });

  it("missing-part-content-id fails the referenced handle and the message", async () => {
    const fixture = await loadFixture("malformed/missing-part-content-id.http");
    const message = await readMessage<ReportPayload>(fixture.contentType, chunked(fixture.body, 64));
    await expect(message.payload.report!.bytes()).rejects.toThrow(MalformedMessageError);
    await expect(message.payload.report!.bytes()).rejects.toThrow(/Content-ID/);
    await expect(message.completed).rejects.toThrow(MalformedMessageError);
  });

  it("oversized-header trips maxPartHeaderBytes with limit name and value", async () => {
    const fixture = await loadFixture("malformed/oversized-header.http");
    const message = await readMessage<ReportPayload>(fixture.contentType, chunked(fixture.body, 4096));
    const failure = await message.payload.report!.bytes().then(
      () => {
        throw new Error("read must not succeed");
      },
      (error: unknown) => error,
    );
    expect(failure).toBeInstanceOf(LimitExceededError);
    expect((failure as LimitExceededError).limit).toBe("maxPartHeaderBytes");
    expect((failure as LimitExceededError).value).toBe(64 * 1024);
    await expect(message.completed).rejects.toThrow(LimitExceededError);
  });

  it("oversized-root trips a configured maxRootBytes before delivering any payload", async () => {
    const fixture = await loadFixture("malformed/oversized-root.http");
    const attempt = readMessage(fixture.contentType, chunked(fixture.body, 4096), {
      maxRootBytes: 64 * 1024,
    });
    const failure = await attempt.then(
      () => {
        throw new Error("readMessage must not resolve");
      },
      (error: unknown) => error,
    );
    expect(failure).toBeInstanceOf(LimitExceededError);
    expect((failure as LimitExceededError).limit).toBe("maxRootBytes");
    expect((failure as LimitExceededError).value).toBe(64 * 1024);
  });

  it("too-many-parts trips a configured maxParts", async () => {
    const fixture = await loadFixture("malformed/too-many-parts.http");
    const message = await readMessage(fixture.contentType, chunked(fixture.body, 64), {
      maxParts: 3,
    });
    const failure = await message.completed.then(
      () => {
        throw new Error("completed must not resolve");
      },
      (error: unknown) => error,
    );
    expect(failure).toBeInstanceOf(LimitExceededError);
    expect((failure as LimitExceededError).limit).toBe("maxParts");
    expect((failure as LimitExceededError).value).toBe(3);
  });

  it.each([
    ["malformed/truncated-mid-content.http"],
    ["malformed/truncated-mid-headers.http"],
  ])("%s fails reads and completion as truncated", async (name) => {
    const fixture = await loadFixture(name);
    const message = await readMessage<ReportPayload>(fixture.contentType, chunked(fixture.body, 64));
    await expect(message.payload.report!.bytes()).rejects.toThrow(MalformedMessageError);
    await expect(message.payload.report!.bytes()).rejects.toThrow(/truncated/);
    await expect(message.completed).rejects.toThrow(/truncated/);
  });
});

describe("synthetic truncation (US2)", () => {
  it("end of stream before the opening delimiter", async () => {
    const fixture = await loadFixture("canonical/single-attachment.http");
    await expect(
      readMessage(fixture.contentType, chunked(fixture.body.slice(0, 4), 64)),
    ).rejects.toThrow(/before the opening delimiter/);
  });

  it("end of stream inside the root part's headers", async () => {
    const fixture = await loadFixture("canonical/single-attachment.http");
    const cut = latin1(fixture.body).indexOf("Content-Type: application/json") + 10;
    await expect(
      readMessage(fixture.contentType, chunked(fixture.body.slice(0, cut), 64)),
    ).rejects.toThrow(MalformedMessageError);
  });

  it("end of stream inside the root part's content", async () => {
    const fixture = await loadFixture("canonical/single-attachment.http");
    const cut = latin1(fixture.body).indexOf('"Quarterly report"');
    await expect(
      readMessage(fixture.contentType, chunked(fixture.body.slice(0, cut), 64)),
    ).rejects.toThrow(/truncated/);
  });
});

describe("severed source (US2)", () => {
  it("delivers TransferError with the cause to blocked and subsequent reads", async () => {
    const boom = new Error("connection reset");
    const message = wire(
      {
        id: "root",
        type: "application/json",
        content:
          '{"title":"t","report":{"Include":{"href":"cid:att-1"}},"second":{"Include":{"href":"cid:att-2"}}}',
      },
      { id: "att-1", type: "application/octet-stream", content: "partial-" },
    );
    // Cut the wire mid-way through att-1's content, then sever
    const cut = latin1(message.bytes).indexOf("partial-") + "partial-".length;
    const source = trackedSource();
    source.feed(message.bytes.slice(0, cut));

    const consumed = await readMessage<ReportPayload>(message.contentType, source.stream);
    const blocked = consumed.payload.report!.bytes();
    blocked.catch(() => undefined);
    await new Promise((resolve) => setTimeout(resolve, 10)); // let the pull block
    source.sever(boom);

    const failure = await blocked.then(
      () => {
        throw new Error("blocked read must not succeed");
      },
      (error: unknown) => error,
    );
    expect(failure).toBeInstanceOf(TransferError);
    expect((failure as TransferError).cause).toBe(boom);

    // A read issued after the failure gets the same typed error
    await expect(consumed.payload.second!.bytes()).rejects.toThrow(TransferError);
    await expect(consumed.completed).rejects.toThrow(TransferError);
  });
});

describe("abort and read-idle (US2, SC-004)", () => {
  it("abort before the payload rejects readMessage with CancelledError and cancels the source", async () => {
    const source = trackedSource();
    const controller = new AbortController();
    const attempt = readMessage(
      'multipart/related; type="application/json"; boundary="rx-t"; start="<root>"',
      source.stream,
      { signal: controller.signal },
    );
    attempt.catch(() => undefined);
    await new Promise((resolve) => setTimeout(resolve, 10));
    const abortedAt = performance.now();
    controller.abort();

    const failure = await attempt.then(
      () => {
        throw new Error("readMessage must not resolve");
      },
      (error: unknown) => error,
    );
    expect(failure).toBeInstanceOf(CancelledError);
    expect((failure as Error).name).toBe("AbortError");
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(source.cancelledAt()).toBeDefined();
    expect(source.cancelledAt()! - abortedAt).toBeLessThan(1000);
  });

  it("abort mid-attachment rejects the blocked read with CancelledError and cancels the source", async () => {
    const message = wire(ROOT_ONE_REF, {
      id: "att-1",
      type: "application/octet-stream",
      content: "some content",
    });
    const cut = latin1(message.bytes).indexOf("some content") + 4;
    const source = trackedSource();
    const controller = new AbortController();
    source.feed(message.bytes.slice(0, cut));

    const consumed = await readMessage<ReportPayload>(message.contentType, source.stream, {
      signal: controller.signal,
    });
    const blocked = consumed.payload.report!.bytes();
    blocked.catch(() => undefined);
    await new Promise((resolve) => setTimeout(resolve, 10));
    const abortedAt = performance.now();
    controller.abort();

    const failure = await blocked.then(
      () => {
        throw new Error("blocked read must not succeed");
      },
      (error: unknown) => error,
    );
    expect(failure).toBeInstanceOf(CancelledError);
    expect((failure as Error).name).toBe("AbortError");
    await expect(consumed.completed).rejects.toThrow(CancelledError);
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(source.cancelledAt()).toBeDefined();
    expect(source.cancelledAt()! - abortedAt).toBeLessThan(1000);
  });

  it("abort after clean completion is a no-op: delivered bytes stay readable", async () => {
    const message = wire(ROOT_ONE_REF, {
      id: "att-1",
      type: "application/octet-stream",
      content: "kept content",
    });
    const controller = new AbortController();
    const consumed = await readMessage<ReportPayload>(
      message.contentType,
      chunked(message.bytes, Infinity),
      { signal: controller.signal },
    );
    await consumed.completed;
    controller.abort();
    expect(latin1(await consumed.payload.report!.bytes())).toBe("kept content");
  });

  it("read-idle expiry on a stalled source rejects with CancelledError naming the option", async () => {
    const message = wire(ROOT_ONE_REF, {
      id: "att-1",
      type: "application/octet-stream",
      content: "never fully arrives",
    });
    const cut = latin1(message.bytes).indexOf("never");
    const source = trackedSource();
    source.feed(message.bytes.slice(0, cut));

    const consumed = await readMessage<ReportPayload>(message.contentType, source.stream, {
      readIdleTimeoutMs: 50,
    });
    const failure = await consumed.payload.report!.bytes().then(
      () => {
        throw new Error("stalled read must not succeed");
      },
      (error: unknown) => error,
    );
    expect(failure).toBeInstanceOf(CancelledError);
    expect((failure as Error).message).toMatch(/readIdleTimeoutMs=50/);
    await expect(consumed.completed).rejects.toThrow(CancelledError);
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(source.cancelledAt()).toBeDefined();
  });
});

describe("source hygiene after failure (US2, SC-004)", () => {
  // Every failure family: the source must be cancelled promptly (< 1 s
  // from the failure) and the reader lock released — no dangling locks
  // that would pin a connection.
  // expectCancel is false where the source already reached EOF before the
  // failure (small fixtures fit one chunk and pull-ahead closes the stream;
  // a closed stream has nothing left to cancel); the lock-release
  // requirement holds everywhere.
  it.each([
    ["malformed part header", "malformed/missing-part-content-id.http", false],
    ["truncated mid-content", "malformed/truncated-mid-content.http", false],
    ["truncated mid-headers", "malformed/truncated-mid-headers.http", false],
    ["oversized part header", "malformed/oversized-header.http", true],
  ] as const)("%s: source cancelled and unlocked", async (_label, fixtureName, expectCancel) => {
    const fixture = await loadFixture(fixtureName);
    const source = trackedReplay(fixture.body, 4096);
    const failedAt = performance.now();
    // Depending on chunking the typed error surfaces at readMessage itself
    // (failure known when the root is wired) or on the first handle read
    await expect(
      (async () => {
        const message = await readMessage<ReportPayload>(fixture.contentType, source.stream);
        await message.payload.report!.bytes();
      })(),
    ).rejects.toThrow();
    await settled();
    if (expectCancel) {
      expect(source.cancelledAt()).toBeDefined();
      expect(source.cancelledAt()! - failedAt).toBeLessThan(1000);
    }
    expect(source.stream.locked).toBe(false);
  });

  it("abort mid-attachment: reader lock released within 1 s", async () => {
    const message = wire(ROOT_ONE_REF, {
      id: "att-1",
      type: "application/octet-stream",
      content: "some content",
    });
    const cut = latin1(message.bytes).indexOf("some content") + 4;
    const source = trackedSource();
    const controller = new AbortController();
    source.feed(message.bytes.slice(0, cut));
    const consumed = await readMessage<ReportPayload>(message.contentType, source.stream, {
      signal: controller.signal,
    });
    const blocked = consumed.payload.report!.bytes();
    blocked.catch(() => undefined);
    await settled();
    controller.abort();
    await expect(blocked).rejects.toThrow(CancelledError);
    await settled();
    expect(source.cancelledAt()).toBeDefined();
    expect(source.stream.locked).toBe(false);
  });

  it("read-idle expiry: reader lock released", async () => {
    const message = wire(ROOT_ONE_REF, {
      id: "att-1",
      type: "application/octet-stream",
      content: "stalls forever",
    });
    const cut = latin1(message.bytes).indexOf("stalls");
    const source = trackedSource();
    source.feed(message.bytes.slice(0, cut));
    const consumed = await readMessage<ReportPayload>(message.contentType, source.stream, {
      readIdleTimeoutMs: 50,
    });
    await expect(consumed.payload.report!.bytes()).rejects.toThrow(CancelledError);
    await settled();
    expect(source.cancelledAt()).toBeDefined();
    expect(source.stream.locked).toBe(false);
  });

  it("severed source: reader lock released even though cancel cannot run", async () => {
    const message = wire(ROOT_ONE_REF, {
      id: "att-1",
      type: "application/octet-stream",
      content: "cut off",
    });
    const cut = latin1(message.bytes).indexOf("cut off") + 3;
    const source = trackedSource();
    source.feed(message.bytes.slice(0, cut));
    const consumed = await readMessage<ReportPayload>(message.contentType, source.stream);
    const blocked = consumed.payload.report!.bytes();
    blocked.catch(() => undefined);
    await settled();
    source.sever(new Error("boom"));
    await expect(blocked).rejects.toThrow(TransferError);
    await settled();
    expect(source.stream.locked).toBe(false);
  });

  it("clean completion also releases the source lock", async () => {
    const message = wire(ROOT_ONE_REF, {
      id: "att-1",
      type: "application/octet-stream",
      content: "all here",
    });
    const source = trackedReplay(message.bytes, 64);
    const consumed = await readMessage<ReportPayload>(message.contentType, source.stream);
    expect(latin1(await consumed.payload.report!.bytes())).toBe("all here");
    await consumed.completed;
    await settled();
    expect(source.stream.locked).toBe(false);
  });
});

describe("referenced part absent (US2)", () => {
  it("reads reject AttachmentUnavailableError at end of message; completion is clean", async () => {
    const message = wire({
      id: "root",
      type: "application/json",
      content: '{"title":"t","report":{"Include":{"href":"cid:never-sent"}}}',
    });
    const consumed = await readMessage<ReportPayload>(
      message.contentType,
      chunked(message.bytes, 64),
    );
    await expect(consumed.payload.report!.bytes()).rejects.toThrow(AttachmentUnavailableError);
    await expect(consumed.payload.report!.bytes()).rejects.toThrow(/never-sent/);
    // The wire itself ended cleanly — unavailability is per-handle
    await consumed.completed;
  });
});
