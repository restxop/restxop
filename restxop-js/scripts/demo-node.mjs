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

// US5: the same library over real HTTP under Node — fetches /document,
// prints the payload fields the moment the root part arrives (before the
// attachment transfer completes), then checksums the streamed PDF.
//
// CI-friendly: exits non-zero on any violated expectation. Set EXPECT_SHA
// to assert parity with a digest recorded from the browser run.
//
// Prerequisite: sample server on :18080 (or SERVER env).

import { createHash } from "node:crypto";
import { restxopFetch } from "../dist/index.js";

const SERVER = process.env.SERVER ?? "http://localhost:18080";
const SIZE = Number(process.env.SIZE ?? 33554432);

function fail(message) {
  console.error(`FAIL: ${message}`);
  process.exit(1);
}

const start = performance.now();
const message = await restxopFetch(`${SERVER}/document?size=${SIZE}`);
const payloadMs = Math.round(performance.now() - start);

const { data, ...fields } = message.payload;
console.log(`payload after ${payloadMs} ms (attachment not yet transferred):`);
console.log(JSON.stringify(fields, null, 2));

const digest = createHash("sha256");
let received = 0;
let firstBytes = null;
let lastBytes = null;
const reader = data.stream().getReader();
for (;;) {
  const { done, value } = await reader.read();
  if (done) break;
  digest.update(value);
  received += value.length;
  firstBytes ??= value.subarray(0, 8);
  lastBytes = value.subarray(-32);
}
await message.completed;
const completedMs = Math.round(performance.now() - start);
const sha = digest.digest("hex");

console.log(`transfer complete after ${completedMs} ms: ${received} bytes`);
console.log(`sha256 ${sha}`);

if (!(payloadMs < completedMs)) fail("payload was not available before transfer completion");
if (received !== fields.sizeBytes) fail(`received ${received} != sizeBytes ${fields.sizeBytes}`);
if (received !== SIZE) fail(`received ${received} != requested ${SIZE}`);
const head = Buffer.from(firstBytes ?? []).toString("latin1");
if (!head.startsWith("%PDF-1.")) fail(`not a PDF header: ${head}`);
if (!Buffer.from(lastBytes ?? []).toString("latin1").includes("%%EOF"))
  fail("missing %%EOF trailer");
if (process.env.EXPECT_SHA && sha !== process.env.EXPECT_SHA)
  fail(`sha ${sha} != EXPECT_SHA ${process.env.EXPECT_SHA} (browser parity)`);

console.log(`US5 PASS${process.env.EXPECT_SHA ? " (browser-parity digest confirmed)" : ""}`);
