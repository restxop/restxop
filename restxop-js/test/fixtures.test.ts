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
import { chunked, concat, latin1, loadFixture } from "./fixtures.js";

describe("fixture plumbing", () => {
  it("loads the canonical single-attachment fixture byte-exactly", async () => {
    const fixture = await loadFixture("canonical/single-attachment.http");
    expect(fixture.contentType).toContain('multipart/related');
    expect(fixture.contentType).toContain('boundary="rx-fixture-0001"');
    const text = latin1(fixture.body);
    expect(text.startsWith("\r\n--rx-fixture-0001\r\n")).toBe(true);
    expect(text.endsWith("\r\n--rx-fixture-0001--\r\n")).toBe(true);
    expect(text).toContain("Content-ID: <root>");
  });

  it("chunked replay reassembles to the identical bytes at every size", async () => {
    const fixture = await loadFixture("canonical/multi-attachment.http");
    for (const size of [1, 3, 64, 4096, Infinity]) {
      const reader = chunked(fixture.body, size).getReader();
      const parts: Uint8Array[] = [];
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        parts.push(value);
      }
      expect(concat(parts)).toEqual(fixture.body);
    }
  });
});
