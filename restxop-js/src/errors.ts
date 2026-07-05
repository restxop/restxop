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
 * Root of the restxop-js error hierarchy: every failure the library raises
 * is a subtype, descriptive and typed (never a hang, never silent
 * corruption).
 */
export class RestxopError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = new.target.name;
  }
}

/** Wire-format violation: missing parameters, bad framing, truncation. */
export class MalformedMessageError extends RestxopError {}

/** A configured structural bound was exceeded; names the limit and value. */
export class LimitExceededError extends RestxopError {
  constructor(
    readonly limit: string,
    readonly value: number,
    message: string,
  ) {
    super(`${message} (limit '${limit}' = ${value})`);
  }
}

/** A referenced part never arrived, or was read after the message ended. */
export class AttachmentUnavailableError extends RestxopError {}

/** Network/source failure mid-message; the cause is attached. */
export class TransferError extends RestxopError {}

/**
 * The consumption was aborted (signal) or a read-idle deadline expired.
 * Carries the AbortError-compatible name so platform idioms
 * (err.name === "AbortError") keep working.
 */
export class CancelledError extends RestxopError {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "AbortError";
  }
}
