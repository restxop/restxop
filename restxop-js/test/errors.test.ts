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
import {
  AttachmentUnavailableError,
  CancelledError,
  LimitExceededError,
  MalformedMessageError,
  RestxopError,
  TransferError,
} from "../src/errors.js";

describe("error hierarchy", () => {
  it("every error is a RestxopError with a stable name", () => {
    expect(new MalformedMessageError("bad")).toBeInstanceOf(RestxopError);
    expect(new MalformedMessageError("bad").name).toBe("MalformedMessageError");
    expect(new AttachmentUnavailableError("gone")).toBeInstanceOf(RestxopError);
    expect(new TransferError("sever", { cause: new Error("io") }).cause).toBeInstanceOf(Error);
  });

  it("limit errors carry the limit name and configured value", () => {
    const err = new LimitExceededError("maxParts", 1000, "too many parts");
    expect(err.limit).toBe("maxParts");
    expect(err.value).toBe(1000);
    expect(err.message).toContain("maxParts");
    expect(err.message).toContain("1000");
  });

  it("cancellation is AbortError-compatible", () => {
    const err = new CancelledError("aborted");
    expect(err.name).toBe("AbortError");
    expect(err).toBeInstanceOf(RestxopError);
  });
});
