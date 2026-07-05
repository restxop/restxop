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

// SC-007 validation: uploads a ~25 MB file through the demo's upload form
// in real Chromium and asserts the server's echo (size + SHA-256) matches
// the file exactly, over a single multipart/related request body.
//
// Prerequisites: sample server on :18080, demo dev server on :5173.

import { createHash, randomBytes } from "node:crypto";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { chromium } from "playwright";

const DEMO = process.env.DEMO ?? "http://localhost:5173";
const SIZE = 25 * 1024 * 1024;

function fail(message) {
  console.error(`FAIL: ${message}`);
  process.exit(1);
}

const dir = await mkdtemp(join(tmpdir(), "restxop-sc007-"));
const filePath = join(dir, "sc007-upload.bin");
const content = randomBytes(SIZE);
await writeFile(filePath, content);
const expectedSha = createHash("sha256").update(content).digest("hex");

const browser = await chromium.launch();
const page = await browser.newPage();

const uploadRequests = [];
page.on("request", (request) => {
  if (request.url().includes("/upload")) uploadRequests.push(request);
});

await page.goto(`${DEMO}/?size=262144`);
await page.getByTestId("upload-file").setInputFiles(filePath);
await page.getByTestId("upload-go").click();

const echoSize = Number(await page.getByTestId("echo-size").textContent({ timeout: 60_000 }));
const echoSha = (await page.getByTestId("echo-sha").textContent()).trim();
const echoLabel = (await page.getByTestId("echo-label").textContent()).trim();
const match = (await page.getByTestId("echo-match").textContent()).trim();

const request = uploadRequests[0];
const requestContentType = request?.headers()["content-type"] ?? "";

await browser.close();
await rm(dir, { recursive: true, force: true });

if (uploadRequests.length !== 1)
  fail(`expected exactly 1 /upload request, saw ${uploadRequests.length}`);
if (!requestContentType.startsWith("multipart/related"))
  fail(`request content-type was ${requestContentType}`);
if (echoLabel !== "sc007-upload.bin") fail(`unexpected label: ${echoLabel}`);
if (echoSize !== SIZE) fail(`echoed size ${echoSize} != ${SIZE}`);
if (echoSha !== expectedSha) fail(`echoed sha ${echoSha} != local ${expectedSha}`);
if (match !== "true") fail("demo-side digest comparison reported a mismatch");

console.log("SC-007 PASS");
console.log(`  requests to /upload ........... ${uploadRequests.length} (${requestContentType})`);
console.log(`  echoed size ................... ${echoSize} bytes (exact)`);
console.log(`  echoed sha256 == file sha256 .. ${echoSha}`);
