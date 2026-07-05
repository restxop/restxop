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

import { MalformedMessageError } from "./errors.js";

/**
 * Chunk-incremental MIME delimiter scanner: the sans-io port of the proven
 * feature-001 algorithm. Feed it transport chunks; it emits part boundaries
 * and raw part-region bytes (headers + blank line + content per part).
 *
 * Semantics pinned by the shared wire fixtures:
 * - the delimiter is `CRLF + "--" + boundary` (bare-LF framing accepted on
 *   read) and OWNS its leading line break — framing bytes never leak into
 *   content;
 * - a boundary-like sequence without correct framing (line-break prefix and
 *   a valid tail) is content;
 * - matching is an LF-anchor scan with direct verification plus a
 *   clean-prefix cache; the trailing pattern-length+1 bytes are retained
 *   across chunk edges so no delimiter (including its optional leading CR)
 *   can be split by chunk boundaries;
 * - after the closing delimiter the epilogue is ignored.
 */

export type ScanEvent =
  | { kind: "part-open" }
  | { kind: "bytes"; bytes: Uint8Array }
  | { kind: "part-close" }
  | { kind: "done" };

const CR = 0x0d;
const LF = 0x0a;
const DASH = 0x2d;
const SP = 0x20;
const HT = 0x09;

/** Longest accepted run of transport padding (WSP) after the boundary. */
const MAX_PADDING = 64;

type State = "preamble" | "part" | "done";

export class MessageScanner {
  /** LF-anchored pattern: `\n--boundary`. */
  private readonly pattern: Uint8Array;
  private buf: Uint8Array;
  private len = 0;
  private pos = 0;
  /** Buffer index below which no delimiter candidate can start (scan cache). */
  private cleanBefore = 0;
  private eof = false;
  private state: State = "preamble";

  constructor(boundary: string) {
    if (!boundary) throw new MalformedMessageError("boundary parameter is empty");
    this.pattern = new Uint8Array(3 + boundary.length);
    this.pattern[0] = LF;
    this.pattern[1] = DASH;
    this.pattern[2] = DASH;
    for (let i = 0; i < boundary.length; i++) this.pattern[3 + i] = boundary.charCodeAt(i) & 0xff;
    this.buf = new Uint8Array(Math.max(8192, this.pattern.length * 4));
    // Virtual CRLF ahead of the stream so an opening delimiter at byte 0
    // ("--boundary" without a preceding line break) matches uniformly
    this.buf[0] = CR;
    this.buf[1] = LF;
    this.len = 2;
  }

  push(chunk: Uint8Array): ScanEvent[] {
    if (this.state === "done") return []; // epilogue: ignored
    this.append(chunk);
    return this.process();
  }

  end(): ScanEvent[] {
    if (this.state === "done") return [];
    this.eof = true;
    const events = this.process();
    if (!this.isDone()) {
      throw new MalformedMessageError(
        this.state === "preamble"
          ? "truncated message: end of stream before the opening delimiter"
          : "truncated message: end of stream before the closing delimiter",
      );
    }
    return events;
  }

  // ------------------------------------------------------------------

  /** Opaque to control-flow narrowing: process() mutates the state. */
  private isDone(): boolean {
    return this.state === "done";
  }

  private append(chunk: Uint8Array): void {
    // Compact consumed prefix, then grow if needed
    if (this.pos > 0) {
      this.buf.copyWithin(0, this.pos, this.len);
      this.len -= this.pos;
      this.cleanBefore = Math.max(0, this.cleanBefore - this.pos);
      this.pos = 0;
    }
    const needed = this.len + chunk.length;
    if (needed > this.buf.length) {
      const grown = new Uint8Array(Math.max(needed, this.buf.length * 2));
      grown.set(this.buf.subarray(0, this.len));
      this.buf = grown;
    }
    this.buf.set(chunk, this.len);
    this.len = needed;
  }

  private process(): ScanEvent[] {
    const events: ScanEvent[] = [];
    for (;;) {
      if (this.pos >= this.len) break;
      const candidate = this.findCandidate(this.pos);
      if (candidate < 0) {
        // Keep back pattern-length+1 bytes (a delimiter with its leading CR
        // may straddle the next chunk edge); at EOF everything is content of
        // a truncated message — end() raises
        const safeEnd = this.eof ? this.len : this.len - (this.pattern.length + 1);
        if (safeEnd > this.pos) {
          this.emit(events, this.pos, safeEnd);
          this.pos = safeEnd;
        }
        break;
      }
      // Bytes before the candidate's line break (minus a preceding CR, which
      // belongs to the delimiter if the match confirms)
      const contentEnd =
        candidate > this.pos && this.buf[candidate - 1] === CR ? candidate - 1 : candidate;
      if (contentEnd > this.pos) {
        this.emit(events, this.pos, contentEnd);
        this.pos = contentEnd;
        continue;
      }
      const resolution = this.validateTail(candidate);
      if (resolution === -2) break; // need more bytes to decide
      if (resolution === -1) {
        // Not a delimiter: the line-break byte (and any preceding CR held
        // back) are content after all — and definitively so
        this.cleanBefore = Math.max(this.cleanBefore, candidate + 1);
        this.emit(events, this.pos, candidate + 1);
        this.pos = candidate + 1;
        continue;
      }
      const closing = resolution < 0 ? false : (resolution & CLOSING_FLAG) !== 0;
      this.pos = resolution & ~CLOSING_FLAG;
      if (this.state === "part") events.push({ kind: "part-close" });
      if (closing) {
        this.state = "done";
        events.push({ kind: "done" });
        // Epilogue: drop everything already buffered
        this.pos = this.len;
        break;
      }
      this.state = "part";
      events.push({ kind: "part-open" });
    }
    return events;
  }

  private emit(events: ScanEvent[], from: number, to: number): void {
    if (this.state === "part") {
      events.push({ kind: "bytes", bytes: this.buf.slice(from, to) });
    }
    // preamble bytes are discarded
  }

  /**
   * LF-anchor scan with direct verification — equivalent to the automaton
   * (the anchor byte cannot occur inside a boundary token) but fast per
   * transported byte; the clean-prefix cache keeps per-byte consumers from
   * re-scanning.
   */
  private findCandidate(from: number): number {
    const last = this.len - this.pattern.length;
    const buf = this.buf;
    const pattern = this.pattern;
    for (let i = Math.max(from, this.cleanBefore); i <= last; i++) {
      if (buf[i] !== LF) continue;
      let match = true;
      for (let j = 1; j < pattern.length; j++) {
        if (buf[i + j] !== pattern[j]) {
          match = false;
          break;
        }
      }
      if (match) return i;
    }
    this.cleanBefore = Math.max(this.cleanBefore, last + 1);
    return -1;
  }

  /**
   * Validates the bytes after a candidate `LF--boundary` match: optional
   * `--` (closing), bounded transport padding, then a line break (or EOF
   * for a closing delimiter).
   *
   * @returns position past the delimiter (CLOSING_FLAG set when closing),
   *          -1 if the candidate is content, -2 if more bytes are needed
   */
  private validateTail(candidate: number): number {
    let t = candidate + this.pattern.length;
    let closing = false;
    if (t + 1 < this.len && this.buf[t] === DASH && this.buf[t + 1] === DASH) {
      closing = true;
      t += 2;
    } else if (t + 1 >= this.len && !this.eof) {
      return -2;
    }
    let padding = 0;
    while (t < this.len && (this.buf[t] === SP || this.buf[t] === HT)) {
      if (++padding > MAX_PADDING) return -1;
      t++;
    }
    if (t >= this.len) {
      if (!this.eof) return -2;
      // EOF directly after the closing boundary is acceptable
      return closing ? t | CLOSING_FLAG : -1;
    }
    if (this.buf[t] === CR) {
      if (t + 1 < this.len && this.buf[t + 1] === LF) {
        return closing ? (t + 2) | CLOSING_FLAG : t + 2;
      }
      return t + 1 >= this.len && !this.eof ? -2 : -1;
    }
    if (this.buf[t] === LF) {
      return closing ? (t + 1) | CLOSING_FLAG : t + 1;
    }
    return -1;
  }
}

/** High bit tags a closing-delimiter resolution (positions stay < 2^30). */
const CLOSING_FLAG = 0x40000000;
