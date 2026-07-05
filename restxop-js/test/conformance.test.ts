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
import { AttachmentHandle, readMessage } from "../src/index.js";
import { CHUNK_MATRIX, chunked, latin1Bytes, loadFixture } from "./fixtures.js";

/**
 * SC-002 (canonical slice): the shared corpus parses byte-exactly at every
 * chunk size — the same fixture files the Java conformance suites pin.
 */

interface Expectation {
  fixture: string;
  attachments: Array<{
    path: (payload: never) => AttachmentHandle;
    content: Uint8Array;
    filename?: string;
    contentType?: string;
  }>;
  payloadCheck: (payload: never) => void;
}

/* eslint-disable @typescript-eslint/no-explicit-any */
const EXPECTATIONS: Expectation[] = [
  {
    fixture: "canonical/single-attachment.http",
    attachments: [
      {
        path: (p: any) => p.report,
        content: latin1Bytes(
          "first line\r\n--rx-fixture-0001 almost a delimiter\r\n" +
            "binary \u0000\u0001\u0002 bytes with -- dashes",
        ),
        filename: "data.bin",
        contentType: "application/octet-stream",
      },
    ],
    payloadCheck: (p: any) => expect(p.title).toBe("Quarterly report"),
  },
  {
    fixture: "canonical/multi-attachment.http",
    attachments: [
      {
        path: (p: any) => p.first,
        content: latin1Bytes("alpha content"),
        filename: "alpha.txt",
        contentType: "text/plain",
      },
      {
        path: (p: any) => p.second,
        content: latin1Bytes("%PDF-1.7 fake minimal content"),
        filename: "report.pdf",
        contentType: "application/pdf",
      },
    ],
    payloadCheck: (p: any) => expect(p.name).toBe("bundle"),
  },
  {
    fixture: "canonical/nested-attachment.http",
    attachments: [
      {
        path: (p: any) => p.inner.data,
        content: latin1Bytes("nested-bytes"),
        contentType: "application/octet-stream",
      },
    ],
    payloadCheck: (p: any) => expect(p.label).toBe("nested"),
  },
  {
    fixture: "canonical/null-attachment.http",
    attachments: [],
    payloadCheck: (p: any) => {
      expect(p.title).toBe("no report");
      expect(p.report).toBeNull();
    },
  },
  {
    fixture: "canonical/zero-attachment.http",
    attachments: [],
    payloadCheck: (p: any) => expect(p).toEqual({ message: "plain", number: 42 }),
  },
];

describe.each(EXPECTATIONS.map((e) => [e.fixture, e] as const))("%s", (_name, expectation) => {
  it.each(CHUNK_MATRIX.map((size) => [size] as const))(
    "parses byte-exactly at chunk size %s",
    async (size) => {
      const fixture = await loadFixture(expectation.fixture);
      const message = await readMessage(fixture.contentType, chunked(fixture.body, size));

      expectation.payloadCheck(message.payload as never);
      expect(message.attachments).toHaveLength(expectation.attachments.length);

      for (const expected of expectation.attachments) {
        const handle = expected.path(message.payload as never);
        expect(handle).toBeInstanceOf(AttachmentHandle);
        const received = await handle.bytes();
        expect(received).toEqual(expected.content);
        if (expected.filename) expect(handle.filename).toBe(expected.filename);
        if (expected.contentType) expect(handle.contentType).toBe(expected.contentType);
      }
      await message.completed;
    },
  );
});
