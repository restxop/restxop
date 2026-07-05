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

import {
  AttachmentUnavailableError,
  CancelledError,
  LimitExceededError,
  MalformedMessageError,
  RestxopError,
  TransferError,
} from "./errors.js";
import { AttachmentHandle, type HandleDriver } from "./handle.js";
import { findHeaderBlockEnd, normalizeId, parseContentTypeParams, parseHeaderBlock } from "./headers.js";
import { MessageScanner, type ScanEvent } from "./scanner.js";

/** Per-call options; every bound has a documented, non-infinite default. */
export interface RestxopOptions {
  /** Root-part size bound (default 16 MiB). */
  maxRootBytes?: number;
  /** Per-part header block bound (default 64 KiB). */
  maxPartHeaderBytes?: number;
  /** Part-count bound (default 1000). */
  maxParts?: number;
  /** Maximum wait for source progress per pull (default 60 000 ms). */
  readIdleTimeoutMs?: number;
  /** Standard platform cancellation. */
  signal?: AbortSignal;
}

/** One consumed message: the payload plus its wired attachment handles. */
export interface RestxopMessage<T> {
  /** The typed payload; Include stubs are already replaced by handles. */
  payload: T;
  /** Handles in first-reference order (duplicates deduplicated). */
  attachments: AttachmentHandle[];
  /** Resolves at clean end of message (rejects on failure/cancellation). */
  completed: Promise<void>;
}

const DEFAULTS = {
  maxRootBytes: 16 * 1024 * 1024,
  maxPartHeaderBytes: 64 * 1024,
  maxParts: 1000,
  readIdleTimeoutMs: 60_000,
};

/**
 * Consumes one restxop message from a byte stream (transport-agnostic; see
 * `restxopFetch` for the fetch binding). Resolves as soon as the root part
 * is parsed — attachment transfer is pull-based from there.
 */
export async function readMessage<T = unknown>(
  contentType: string | null | undefined,
  body: ReadableStream<Uint8Array>,
  options?: RestxopOptions,
): Promise<RestxopMessage<T>> {
  const session = new MessageSession(contentType, body, options);
  return session.start<T>();
}

type PartTarget =
  | { kind: "root" }
  | { kind: "handle"; handle: AttachmentHandle }
  | { kind: "discard" };

class MessageSession implements HandleDriver {
  private readonly opts: Required<Omit<RestxopOptions, "signal">> & { signal?: AbortSignal };
  private readonly reader: ReadableStreamDefaultReader<Uint8Array>;
  private readonly scanner: MessageScanner;
  private readonly startId: string;
  private readonly handles = new Map<string, AttachmentHandle>();
  private readonly order: AttachmentHandle[] = [];

  private rootChunks: Uint8Array[] = [];
  private rootSize = 0;
  private rootDone = false;
  private partCount = 0;
  private headerBuf: Uint8Array | null = null;
  private target: PartTarget | null = null;

  private done = false;
  private failure?: RestxopError;
  private wired = false;
  private readonly queue: ScanEvent[] = [];
  private advanceChain: Promise<void> = Promise.resolve();
  private resolveCompleted!: () => void;
  private rejectCompleted!: (error: RestxopError) => void;
  readonly completed: Promise<void>;

  constructor(
    contentType: string | null | undefined,
    body: ReadableStream<Uint8Array>,
    options?: RestxopOptions,
  ) {
    this.opts = { ...DEFAULTS, ...options };
    if (!contentType) throw new MalformedMessageError("response has no Content-Type header");
    const outer = parseContentTypeParams(contentType);
    if (outer.mediaType !== "multipart/related") {
      throw new MalformedMessageError(`unsupported media type '${outer.mediaType}'`);
    }
    const typeParam = outer.params.get("type");
    if (!typeParam || typeParam.trim().toLowerCase() !== "application/json") {
      throw new MalformedMessageError(
        `type parameter must be application/json, was '${typeParam ?? "<absent>"}'`,
      );
    }
    const boundary = outer.params.get("boundary");
    if (!boundary) throw new MalformedMessageError("required parameter 'boundary' is missing");
    const start = outer.params.get("start");
    if (!start) throw new MalformedMessageError("required parameter 'start' is missing");
    this.startId = normalizeId(start);
    this.scanner = new MessageScanner(boundary);
    this.reader = body.getReader();
    this.completed = new Promise<void>((resolve, reject) => {
      this.resolveCompleted = resolve;
      this.rejectCompleted = reject;
    });
    this.completed.catch(() => undefined); // observed via handles too
    this.opts.signal?.addEventListener("abort", () => {
      this.fail(new CancelledError("consumption aborted"));
    });
  }

  async start<T>(): Promise<RestxopMessage<T>> {
    if (this.opts.signal?.aborted) {
      this.fail(new CancelledError("consumption aborted"));
    }
    while (!this.rootDone) {
      if (this.failure) throw this.failure;
      await this.advance();
    }
    const rootBytes = concat(this.rootChunks, this.rootSize);
    this.rootChunks = [];
    let parsed: unknown;
    try {
      parsed = JSON.parse(new TextDecoder("utf-8").decode(rootBytes));
    } catch (cause) {
      const error = new MalformedMessageError("root part is not valid JSON", { cause });
      this.fail(error);
      throw error;
    }
    const payload = this.substitute(parsed) as T;
    // Handles now exist for every reference: release any parts that arrived
    // in the same chunk batch as the root
    this.wired = true;
    try {
      this.drainQueue();
    } catch (cause) {
      throw this.failWith(cause, "parse");
    }

    if (this.order.length === 0) {
      // Zero references: finish the (tiny) remainder so the source is freed
      await this.driveToEnd();
    }
    return { payload, attachments: [...this.order], completed: this.completed };
  }

  // ---- HandleDriver ----

  async driveFor(handle: AttachmentHandle): Promise<void> {
    if (this.failure) throw this.failure;
    if (handle.hasArrived) return;
    if (this.done) {
      handle.fail(
        new AttachmentUnavailableError(
          `message ended without a part for referenced attachment '${handle.contentId}'`,
        ),
      );
      return;
    }
    await this.advance();
    if (this.failure) throw this.failure;
  }

  handleSettled(): void {
    if (this.done || this.failure) return;
    if (this.order.every((handle) => handle.isFinished || handle.isSkipped)) {
      // Every referenced attachment is settled: finish the message so the
      // source (and its connection) is released without app involvement
      void this.driveToEnd();
    }
  }

  // ---- wire driving ----

  /** Serialized: one source pull + event dispatch per settled promise. */
  private advance(): Promise<void> {
    const step = this.advanceChain.then(() => this.advanceOnce());
    // Keep the chain alive past failures; errors surface via this.failure
    this.advanceChain = step.catch(() => undefined);
    return step;
  }

  private async advanceOnce(): Promise<void> {
    if (this.done || this.failure) return;
    try {
      // Serve queued events first (parts held back until wiring)
      if (this.drainQueue()) return;
    } catch (cause) {
      throw this.failWith(cause, "parse");
    }
    let result: ReadableStreamReadResult<Uint8Array>;
    try {
      result = await this.readWithDeadline();
    } catch (cause) {
      throw this.failWith(cause, "transfer");
    }
    try {
      const events = result.done
        ? this.scanner.end()
        : this.scanner.push(result.value ?? new Uint8Array(0));
      this.queue.push(...events);
      this.drainQueue();
    } catch (cause) {
      throw this.failWith(cause, "parse");
    }
  }

  /**
   * Records a failure and returns the session's authoritative one: the
   * FIRST failure wins everywhere — a cancelled reader also produces a
   * truncation from the scanner, which must never mask the abort.
   */
  private failWith(cause: unknown, site: "parse" | "transfer"): RestxopError {
    const mapped =
      cause instanceof RestxopError
        ? cause
        : site === "transfer"
          ? new TransferError("source failed mid-message", { cause })
          : new MalformedMessageError("message parsing failed", { cause });
    this.fail(mapped);
    return this.failure ?? mapped;
  }

  /**
   * Dispatches queued events, holding at the root boundary until the
   * payload tree has been wired to handles (so parts arriving in the same
   * chunk batch as the root are never mistaken for unreferenced parts).
   *
   * @returns true when dispatching made observable progress
   */
  private drainQueue(): boolean {
    let progressed = false;
    while (this.queue.length > 0) {
      if (this.rootDone && !this.wired) break;
      this.dispatch(this.queue.shift()!);
      progressed = true;
    }
    return progressed;
  }

  private async readWithDeadline(): Promise<ReadableStreamReadResult<Uint8Array>> {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const deadline = new Promise<never>((_, reject) => {
      timer = setTimeout(
        () =>
          reject(
            new CancelledError(
              `no source progress within readIdleTimeoutMs=${this.opts.readIdleTimeoutMs}`,
            ),
          ),
        this.opts.readIdleTimeoutMs,
      );
    });
    try {
      return await Promise.race([this.reader.read(), deadline]);
    } finally {
      clearTimeout(timer);
    }
  }

  private dispatch(event: ScanEvent): void {
    switch (event.kind) {
      case "part-open": {
        if (++this.partCount > this.opts.maxParts) {
          throw new LimitExceededError("maxParts", this.opts.maxParts, "too many message parts");
        }
        this.headerBuf = new Uint8Array(0);
        this.target = null;
        break;
      }
      case "bytes": {
        if (this.target) {
          this.route(event.bytes);
          break;
        }
        // Still inside the header block
        const merged = concat2(this.headerBuf ?? new Uint8Array(0), event.bytes);
        const end = findHeaderBlockEnd(merged);
        if (end < 0) {
          if (merged.length > this.opts.maxPartHeaderBytes) {
            throw new LimitExceededError(
              "maxPartHeaderBytes",
              this.opts.maxPartHeaderBytes,
              "part header block exceeds the configured bound",
            );
          }
          this.headerBuf = merged;
          break;
        }
        if (end > this.opts.maxPartHeaderBytes) {
          throw new LimitExceededError(
            "maxPartHeaderBytes",
            this.opts.maxPartHeaderBytes,
            "part header block exceeds the configured bound",
          );
        }
        this.beginPart(merged.subarray(0, end));
        if (end < merged.length) this.route(merged.subarray(end));
        this.headerBuf = null;
        break;
      }
      case "part-close": {
        if (!this.target) {
          throw new MalformedMessageError("part ended inside its header block");
        }
        if (this.target.kind === "root") {
          this.rootDone = true;
        } else if (this.target.kind === "handle") {
          this.target.handle.endPart();
        }
        this.target = null;
        break;
      }
      case "done": {
        this.done = true;
        for (const handle of this.order) {
          if (!handle.hasArrived && !handle.isSkipped) {
            handle.fail(
              new AttachmentUnavailableError(
                `message ended without a part for referenced attachment '${handle.contentId}'`,
              ),
            );
          }
        }
        this.resolveCompleted();
        this.releaseSource();
        break;
      }
    }
  }

  private beginPart(headerBlock: Uint8Array): void {
    const headers = parseHeaderBlock(headerBlock);
    const contentId = headers.contentId;
    if (contentId === undefined) {
      throw new MalformedMessageError("part has no Content-ID header");
    }
    if (!this.rootDone && this.partCount === 1) {
      if (contentId !== this.startId) {
        throw new MalformedMessageError(
          `first part Content-ID '${contentId}' does not match start '${this.startId}'`,
        );
      }
      const rootType = headers.contentType;
      if (!rootType || parseContentTypeParams(rootType).mediaType !== "application/json") {
        throw new MalformedMessageError(
          `root part Content-Type must be application/json, was '${rootType ?? "<absent>"}'`,
        );
      }
      this.target = { kind: "root" };
      return;
    }
    const handle = this.handles.get(contentId);
    if (!handle) {
      this.target = { kind: "discard" }; // unreferenced part: lenient skip
      return;
    }
    if (handle.isFinished && !handle.isSkipped) {
      throw new MalformedMessageError(`duplicate Content-ID '${contentId}' on the wire`);
    }
    handle.filename = headers.filename;
    handle.contentType = headers.contentType;
    this.target = { kind: "handle", handle };
  }

  private route(bytes: Uint8Array): void {
    if (!this.target || bytes.length === 0) return;
    if (this.target.kind === "root") {
      this.rootSize += bytes.length;
      if (this.rootSize > this.opts.maxRootBytes) {
        throw new LimitExceededError(
          "maxRootBytes",
          this.opts.maxRootBytes,
          "root part exceeds the configured bound",
        );
      }
      this.rootChunks.push(bytes.slice());
    } else if (this.target.kind === "handle") {
      this.target.handle.pushBytes(bytes.slice());
    }
    // discard: dropped
  }

  private async driveToEnd(): Promise<void> {
    try {
      while (!this.done && !this.failure) {
        await this.advance();
      }
    } catch {
      // surfaced via this.failure / completed
    }
  }

  private fail(error: RestxopError): void {
    // First failure wins; post-completion failures (e.g. late aborts) are
    // no-ops — delivered bytes stay readable
    if (this.failure || this.done) return;
    this.failure = error;
    for (const handle of this.order) handle.fail(error);
    this.rejectCompleted(error);
    this.releaseSource();
  }

  /** Cancels the source and releases the reader lock — no dangling locks. */
  private releaseSource(): void {
    void this.reader
      .cancel()
      .catch(() => undefined)
      .then(() => {
        try {
          this.reader.releaseLock();
        } catch {
          // already released
        }
      });
  }

  // ---- payload substitution (constitution IV: tree traversal) ----

  private substitute(value: unknown): unknown {
    if (Array.isArray(value)) {
      for (let i = 0; i < value.length; i++) value[i] = this.substitute(value[i]);
      return value;
    }
    if (value !== null && typeof value === "object") {
      const record = value as Record<string, unknown>;
      const include = record["Include"];
      if (
        include !== null &&
        typeof include === "object" &&
        !Array.isArray(include) &&
        typeof (include as Record<string, unknown>)["href"] === "string" &&
        Object.keys(record).length === 1
      ) {
        return this.resolveHandle(normalizeId((include as Record<string, string>)["href"]!));
      }
      for (const key of Object.keys(record)) {
        record[key] = this.substitute(record[key]);
      }
      return value;
    }
    return value;
  }

  private resolveHandle(contentId: string): AttachmentHandle {
    let handle = this.handles.get(contentId);
    if (!handle) {
      handle = new AttachmentHandle(contentId, this);
      this.handles.set(contentId, handle);
      this.order.push(handle);
    }
    return handle;
  }
}

function concat(parts: Uint8Array[], total: number): Uint8Array {
  const out = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

function concat2(a: Uint8Array, b: Uint8Array): Uint8Array {
  if (a.length === 0) return b.slice();
  const out = new Uint8Array(a.length + b.length);
  out.set(a);
  out.set(b, a.length);
  return out;
}
