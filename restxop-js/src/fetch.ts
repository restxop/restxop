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

import { TransferError } from "./errors.js";
import { readMessage, type RestxopMessage, type RestxopOptions } from "./session.js";
import { buildMessage } from "./write.js";

/**
 * The one-liner binding over global fetch: issues the request and consumes
 * the response as a restxop message. The typed payload resolves as soon as
 * the root part arrives; attachments stream on demand from there. Pass a
 * standard `signal` in the init (or in `init.restxop`) to cancel any phase.
 */
export async function restxopFetch<T = unknown>(
  input: RequestInfo | URL,
  init?: RequestInit & { restxop?: RestxopOptions },
): Promise<RestxopMessage<T>> {
  const { restxop, ...requestInit } = init ?? {};
  const response = await fetch(input, requestInit);
  if (!response.ok) {
    throw new TransferError(`request failed: HTTP ${response.status} ${response.statusText}`);
  }
  if (!response.body) {
    throw new TransferError("response has no body stream");
  }
  const options: RestxopOptions = {
    signal: requestInit.signal ?? undefined,
    ...restxop,
  };
  return readMessage<T>(response.headers.get("content-type"), response.body, options);
}

/**
 * Convenience POST: assembles the payload (attachment fields wrapped with
 * `attachment()`) via {@link buildMessage} and sends it with global fetch.
 */
restxopFetch.post = async function post(
  input: RequestInfo | URL,
  payload: unknown,
  init?: RequestInit,
): Promise<Response> {
  const { contentType, body } = buildMessage(payload);
  const headers = new Headers(init?.headers);
  headers.set("content-type", contentType);
  return fetch(input, {
    ...init,
    method: init?.method ?? "POST",
    headers,
    body: body as BodyInit,
  });
};
