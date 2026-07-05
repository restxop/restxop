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

import { createServer, type Server } from "node:http";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { TransferError } from "../src/errors.js";
import { restxopFetch, type AttachmentHandle } from "../src/index.js";
import { latin1Bytes, loadFixture } from "./fixtures.js";

let server: Server;
let base: string;

beforeAll(async () => {
  const fixture = await loadFixture("canonical/single-attachment.http");
  server = createServer((req, res) => {
    if (req.url === "/report") {
      res.writeHead(200, { "content-type": fixture.contentType });
      res.end(Buffer.from(fixture.body));
      return;
    }
    res.writeHead(404).end("not found");
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  base = `http://127.0.0.1:${typeof address === "object" && address ? address.port : 0}`;
});

afterAll(async () => {
  await new Promise((resolve) => server.close(resolve));
});

describe("restxopFetch over real HTTP", () => {
  it("fetches a message end to end", async () => {
    const message = await restxopFetch<{ title: string; report: AttachmentHandle }>(
      `${base}/report`,
    );
    expect(message.payload.title).toBe("Quarterly report");
    const bytes = await message.payload.report.bytes();
    expect(bytes).toEqual(
      latin1Bytes(
        "first line\r\n--rx-fixture-0001 almost a delimiter\r\n" +
          "binary \u0000\u0001\u0002 bytes with -- dashes",
      ),
    );
    expect(message.payload.report.filename).toBe("data.bin");
    await message.completed;
  });

  it("non-2xx responses fail typed before any payload", async () => {
    await expect(restxopFetch(`${base}/missing`)).rejects.toThrow(TransferError);
  });

  it("an AbortSignal in the init cancels consumption", async () => {
    const controller = new AbortController();
    controller.abort();
    await expect(restxopFetch(`${base}/report`, { signal: controller.signal })).rejects.toThrow();
  });
});
