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

import { RestxopError } from "./errors.js";

/** The session-side driver an attachment handle pulls from. */
export interface HandleDriver {
  /** Advance the wire until this handle receives bytes, ends, or fails. */
  driveFor(handle: AttachmentHandle): Promise<void>;
  /** One attachment fully consumed or skipped. */
  handleSettled(): void;
}

/**
 * The client-side representation of one referenced part (data-model.md):
 * lazily consumable content (single logical consumption), whole-content
 * convenience, wire metadata bound when the part's headers arrive, and
 * retention for bytes that arrived before the application read them.
 */
export class AttachmentHandle {
  /** Filename from the part's Content-Disposition, once the part arrived. */
  filename?: string;
  /** Content type from the part's Content-Type, once the part arrived. */
  contentType?: string;

  private readonly retained: Uint8Array[] = [];
  private ended = false;
  private failure?: RestxopError;
  private skipped = false;
  private consumed = false;
  private settled = false;
  private streamInstance?: ReadableStream<Uint8Array>;

  constructor(
    readonly contentId: string,
    private readonly driver: HandleDriver,
  ) {}

  // ---- session-facing (package-internal) ----

  /** @internal */
  pushBytes(bytes: Uint8Array): void {
    if (!this.skipped && !this.consumedToEnd()) this.retained.push(bytes);
  }

  /** @internal */
  endPart(): void {
    this.ended = true;
  }

  /** @internal */
  fail(error: RestxopError): void {
    if (this.ended) return; // the part fully arrived; its bytes remain readable
    this.failure ??= error;
    this.retained.length = 0;
    this.settle();
  }

  /** @internal */
  get isFinished(): boolean {
    return this.settled || (this.ended && this.retained.length === 0);
  }

  /** @internal */
  get isSkipped(): boolean {
    return this.skipped;
  }

  /** @internal */
  get hasArrived(): boolean {
    return this.ended || this.retained.length > 0;
  }

  // ---- application-facing ----

  /**
   * The part's content for single sequential consumption. Reading pulls the
   * wire on demand; bytes that arrived early are served from retention.
   */
  stream(): ReadableStream<Uint8Array> {
    if (this.consumed && !this.streamInstance) {
      return errored(new RestxopError(`attachment '${this.contentId}' was already consumed`));
    }
    if (this.skipped) {
      return errored(new RestxopError(`attachment '${this.contentId}' was skipped`));
    }
    this.streamInstance ??= this.createStream();
    return this.streamInstance;
  }

  /** Whole-content convenience (same single consumption as {@link stream}). */
  async bytes(): Promise<Uint8Array> {
    if (this.consumed) {
      throw new RestxopError(`attachment '${this.contentId}' was already consumed`);
    }
    const reader = this.stream().getReader();
    const parts: Uint8Array[] = [];
    let total = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      parts.push(value);
      total += value.length;
    }
    const out = new Uint8Array(total);
    let offset = 0;
    for (const part of parts) {
      out.set(part, offset);
      offset += part.length;
    }
    return out;
  }

  /** Whole-content convenience as a Blob. */
  async blob(): Promise<Blob> {
    const bytes = await this.bytes();
    const type = this.contentType ?? "application/octet-stream";
    return new Blob([bytes as BlobPart], { type });
  }

  /** Discards this attachment: retention is freed, future bytes dropped. */
  async skip(): Promise<void> {
    if (this.consumed) return;
    this.skipped = true;
    this.retained.length = 0;
    this.settle();
  }

  // ---- internals ----

  private consumedToEnd(): boolean {
    return this.consumed && this.ended;
  }

  private createStream(): ReadableStream<Uint8Array> {
    this.consumed = true;
    return new ReadableStream<Uint8Array>({
      pull: async (controller) => {
        for (;;) {
          if (this.retained.length > 0) {
            controller.enqueue(this.retained.shift()!);
            return;
          }
          if (this.failure) {
            const failure = this.failure;
            this.settle();
            controller.error(failure);
            return;
          }
          if (this.ended) {
            this.settle();
            controller.close();
            return;
          }
          await this.driver.driveFor(this); // may throw typed errors
        }
      },
      cancel: () => {
        void this.skip();
      },
    });
  }

  private settle(): void {
    if (this.settled) return;
    this.settled = true;
    this.driver.handleSettled();
  }
}

function errored(error: RestxopError): ReadableStream<Uint8Array> {
  return new ReadableStream<Uint8Array>({
    start(controller) {
      controller.error(error);
    },
  });
}
