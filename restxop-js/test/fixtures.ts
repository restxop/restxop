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
 * Loads the shared wire-fixture corpus from restxop-testkit BY PATH — the
 * same bytes the Java conformance suites pin — in both Node (fs) and the
 * browser runner (Vite-served assets).
 */

export interface WireFixture {
  name: string;
  contentType: string;
  body: Uint8Array;
}

/** Chunk sizes every fixture is replayed at (Infinity = whole body). */
export const CHUNK_MATRIX = [1, 2, 3, 7, 64, 4096, 65536, Infinity] as const;

const FIXTURE_GLOB = import.meta.glob(
  "../../restxop/restxop-testkit/src/main/resources/fixtures/**/*.http",
  { query: "?url", import: "default", eager: true },
) as Record<string, string>;

const isNode =
  typeof process !== "undefined" && !!process.versions?.node && typeof window === "undefined";

/** Raw fixture file bytes, e.g. name = "canonical/single-attachment.http". */
export async function fixtureFileBytes(name: string): Promise<Uint8Array> {
  if (isNode) {
    const { readFile } = await import("node:fs/promises");
    const url = new URL(
      `../../restxop/restxop-testkit/src/main/resources/fixtures/${name}`,
      import.meta.url,
    );
    return new Uint8Array(await readFile(url));
  }
  const key = Object.keys(FIXTURE_GLOB).find((k) => k.endsWith(`/fixtures/${name}`));
  if (!key) throw new Error(`no fixture asset for ${name}`);
  const response = await fetch(FIXTURE_GLOB[key]!);
  if (!response.ok) throw new Error(`fixture fetch failed: ${name}`);
  return new Uint8Array(await response.arrayBuffer());
}

/** Loads a fixture with an embedded header section (Content-Type + blank line + body). */
export async function loadFixture(name: string): Promise<WireFixture> {
  const raw = await fixtureFileBytes(name);
  let bodyStart = -1;
  for (let i = 0; i < raw.length - 1; i++) {
    if (raw[i] === 0x0a) {
      if (raw[i + 1] === 0x0a) {
        bodyStart = i + 2;
        break;
      }
      if (i + 2 < raw.length && raw[i + 1] === 0x0d && raw[i + 2] === 0x0a) {
        bodyStart = i + 3;
        break;
      }
    }
  }
  if (bodyStart < 0) throw new Error(`fixture '${name}' has no blank line after headers`);
  const headerText = latin1(raw.subarray(0, bodyStart));
  const match = /^content-type:\s*(.+?)\s*$/im.exec(headerText);
  if (!match) throw new Error(`fixture '${name}' declares no Content-Type header`);
  return { name, contentType: match[1]!, body: raw.slice(bodyStart) };
}

/** Loads a body-only fixture (the captured legacy format) with a supplied Content-Type. */
export async function loadBodyOnly(name: string, contentType: string): Promise<WireFixture> {
  return { name, contentType, body: await fixtureFileBytes(name) };
}

export function latin1(bytes: Uint8Array): string {
  let out = "";
  for (let i = 0; i < bytes.length; i++) out += String.fromCharCode(bytes[i]!);
  return out;
}

export function latin1Bytes(text: string): Uint8Array {
  const out = new Uint8Array(text.length);
  for (let i = 0; i < text.length; i++) out[i] = text.charCodeAt(i) & 0xff;
  return out;
}

/** Replays bytes as a ReadableStream in fixed-size chunks (backpressure-honoring). */
export function chunked(bytes: Uint8Array, size: number): ReadableStream<Uint8Array> {
  let offset = 0;
  const step = size === Infinity ? bytes.length || 1 : size;
  return new ReadableStream<Uint8Array>({
    pull(controller) {
      if (offset >= bytes.length) {
        controller.close();
        return;
      }
      const end = Math.min(offset + step, bytes.length);
      controller.enqueue(bytes.slice(offset, end));
      offset = end;
    },
  });
}

export function concat(parts: Uint8Array[]): Uint8Array {
  const total = parts.reduce((n, p) => n + p.length, 0);
  const out = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

export async function sha256(bytes: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", bytes as BufferSource);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
