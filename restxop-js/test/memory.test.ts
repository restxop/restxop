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
 * SC-003: a 100 MB attachment streams through a pass-through consumer with
 * the library's working memory bounded — the in-order pull path retains
 * nothing, so heap growth stays far below the transferred volume.
 *
 * Node-only (excluded from the browser config and from `npm test`; run
 * via `npm run test:memory`).
 */

import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { readMessage, type AttachmentHandle } from "../src/index.js";
import { latin1Bytes } from "./fixtures.js";

const CHUNK = 64 * 1024;
const CHUNKS = 1600; // 100 MiB
const TOTAL = CHUNK * CHUNKS;

/** Generates the message on the fly — the wire is never materialized. */
function generatedMessage(): { contentType: string; stream: ReadableStream<Uint8Array> } {
  const boundary = "mem-boundary-0001";
  const head = latin1Bytes(
    `\r\n--${boundary}\r\n` +
      "Content-ID: <root>\r\nContent-Type: application/json\r\n\r\n" +
      `{"title":"bulk","data":{"Include":{"href":"cid:att-1"}}}` +
      `\r\n--${boundary}\r\n` +
      "Content-ID: <att-1>\r\nContent-Type: application/octet-stream\r\n\r\n",
  );
  const tail = latin1Bytes(`\r\n--${boundary}--\r\n`);
  const block = new Uint8Array(CHUNK).map((_, i) => i & 0xff);
  let stage: "head" | "content" | "tail" | "done" = "head";
  let sent = 0;
  const stream = new ReadableStream<Uint8Array>({
    pull(controller) {
      if (stage === "head") {
        controller.enqueue(head);
        stage = "content";
        return;
      }
      if (stage === "content") {
        controller.enqueue(block.slice());
        if (++sent === CHUNKS) stage = "tail";
        return;
      }
      if (stage === "tail") {
        controller.enqueue(tail);
        stage = "done";
        return;
      }
      controller.close();
    },
  });
  return {
    contentType: `multipart/related; type="application/json"; boundary="${boundary}"; start="<root>"`,
    stream,
  };
}

describe("bounded-memory pass-through (SC-003)", () => {
  it("streams 100 MiB with heap growth far below the transferred volume", async () => {
    const source = generatedMessage();
    const expected = (() => {
      const digest = createHash("sha256");
      const block = new Uint8Array(CHUNK).map((_, i) => i & 0xff);
      for (let i = 0; i < CHUNKS; i++) digest.update(block);
      return digest.digest("hex");
    })();

    globalThis.gc?.();
    const baseline = process.memoryUsage().heapUsed;
    let peak = baseline;

    const message = await readMessage<{ title: string; data: AttachmentHandle }>(
      source.contentType,
      source.stream,
    );
    expect(message.payload.title).toBe("bulk");

    const digest = createHash("sha256");
    let received = 0;
    let reads = 0;
    const reader = message.payload.data.stream().getReader();
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      digest.update(value);
      received += value.length;
      if (++reads % 64 === 0) {
        peak = Math.max(peak, process.memoryUsage().heapUsed);
      }
    }
    await message.completed;
    peak = Math.max(peak, process.memoryUsage().heapUsed);

    expect(received).toBe(TOTAL);
    expect(digest.digest("hex")).toBe(expected);
    // Pass-through must not accumulate the transfer: allow generous
    // allocator noise, but far below the 100 MiB that full retention
    // (or a spool) would show
    const growth = peak - baseline;
    expect(growth).toBeLessThan(48 * 1024 * 1024);
  }, 120_000);
});
