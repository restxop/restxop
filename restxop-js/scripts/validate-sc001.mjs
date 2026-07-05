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

// SC-001 validation: drives the React demo in a real Chromium against the
// boot4 sample server and asserts (1) exactly one multipart/related request,
// (2) metadata rendered < 1s while the attachment still streams, and
// (3) the PDF the browser assembled is byte-identical to the wire content.
//
// Prerequisites: sample server on :18080, demo dev server on :5173.

import { createHash } from "node:crypto";
import { chromium } from "playwright";
import { restxopFetch } from "../dist/index.js";

const SERVER = process.env.SERVER ?? "http://localhost:18080";
const DEMO = process.env.DEMO ?? "http://localhost:5173";
const SIZE = 33554432; // 32 MiB keeps the streaming phase observable

function fail(message) {
  console.error(`FAIL: ${message}`);
  process.exit(1);
}

// Reference digest: pull the same document over the validated Node path
const reference = await restxopFetch(`${SERVER}/document?size=${SIZE}`);
const referenceBytes = await reference.payload.data.bytes();
await reference.completed;
if (referenceBytes.length !== SIZE) fail(`reference size ${referenceBytes.length} != ${SIZE}`);
const referenceSha = createHash("sha256").update(referenceBytes).digest("hex");

const browser = await chromium.launch();
const page = await browser.newPage();

// Throttle the network so the attachment streams for several seconds —
// on loopback the whole message would land in ~200 ms and the overlap
// between "metadata visible" and "attachment still streaming" would be
// unobservable. 4 MiB/s puts the 32 MiB PDF at ~8 s.
const cdp = await page.context().newCDPSession(page);
await cdp.send("Network.emulateNetworkConditions", {
  offline: false,
  latency: 20,
  downloadThroughput: 4 * 1024 * 1024,
  uploadThroughput: 4 * 1024 * 1024,
});

const documentRequests = [];
page.on("request", (request) => {
  if (request.url().includes("/document")) documentRequests.push(request);
});
let responseContentType = null;
page.on("response", (response) => {
  if (response.url().includes("/document")) {
    responseContentType = response.headers()["content-type"] ?? null;
  }
});

await page.goto(`${DEMO}/?size=${SIZE}`);

const payloadAt = await page.getByTestId("payload-at").textContent({ timeout: 15_000 });
const payloadMs = Number(/(\d+) ms/.exec(payloadAt)?.[1]);
const streamingVisible = await page
  .getByTestId("streaming")
  .isVisible()
  .catch(() => false);

if (!streamingVisible) fail("streaming indicator not visible while metadata already rendered");

const sha = (await page.getByTestId("sha256").textContent({ timeout: 60_000 })).trim();
const completedAt = await page.getByTestId("completed-at").textContent();
const completedMs = Number(/(\d+) ms/.exec(completedAt)?.[1]);
const title = (await page.getByTestId("title").textContent()).trim();
const frameVisible = await page.getByTestId("pdf-frame").isVisible();

await browser.close();

if (documentRequests.length !== 1)
  fail(`expected exactly 1 /document request, saw ${documentRequests.length}`);
if (!responseContentType?.startsWith("multipart/related"))
  fail(`response content-type was ${responseContentType}`);
if (!(payloadMs < 1000)) fail(`metadata took ${payloadMs} ms (SC-001 requires < 1000 ms)`);
if (!(payloadMs < completedMs)) fail(`payload at ${payloadMs} ms not before completion ${completedMs} ms`);
if (title !== "Quarterly Compliance Report") fail(`unexpected title: ${title}`);
if (sha !== referenceSha) fail(`browser sha ${sha} != reference ${referenceSha}`);
if (!frameVisible) fail("PDF viewer frame not visible");

console.log("SC-001 PASS");
console.log(`  requests to /document ......... ${documentRequests.length} (${responseContentType})`);
console.log(`  metadata rendered at .......... ${payloadMs} ms (< 1000 ms)`);
console.log(`  streaming state observed ...... ${streamingVisible}`);
console.log(`  transfer completed at ......... ${completedMs} ms (${SIZE} bytes)`);
console.log(`  browser sha256 == wire sha256 . ${sha}`);
