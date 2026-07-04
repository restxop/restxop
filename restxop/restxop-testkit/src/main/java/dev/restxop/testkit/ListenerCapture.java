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
package dev.restxop.testkit;

import dev.restxop.spi.AttachmentInfo;
import dev.restxop.spi.ExchangeInfo;
import dev.restxop.spi.ExchangeListener;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Records every {@link ExchangeListener} event for FR-033 assertions:
 * event names in dispatch order, causal errors, spool totals, and a latch
 * for awaiting exchange termination.
 */
public final class ListenerCapture implements ExchangeListener {

    /** One captured listener event. */
    public record Event(String name, String exchangeId, String contentId, Throwable cause,
            long totalSpooled) {
    }

    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final CountDownLatch closed = new CountDownLatch(1);

    @Override
    public void exchangeStarted(ExchangeInfo info) {
        events.add(new Event("exchangeStarted", info.exchangeId(), null, null, -1));
    }

    @Override
    public void payloadDelivered(ExchangeInfo info) {
        events.add(new Event("payloadDelivered", info.exchangeId(), null, null, -1));
    }

    @Override
    public void attachmentConsumed(ExchangeInfo info, AttachmentInfo attachment) {
        events.add(new Event("attachmentConsumed", info.exchangeId(),
                attachment.contentId(), null, -1));
    }

    @Override
    public void bytesSpooled(ExchangeInfo info, AttachmentInfo attachment, long totalSpooled) {
        events.add(new Event("bytesSpooled", info.exchangeId(),
                attachment.contentId(), null, totalSpooled));
    }

    @Override
    public void exchangeFailed(ExchangeInfo info, Throwable cause) {
        events.add(new Event("exchangeFailed", info.exchangeId(), null, cause, -1));
    }

    @Override
    public void exchangeClosed(ExchangeInfo info) {
        events.add(new Event("exchangeClosed", info.exchangeId(), null, null, -1));
        closed.countDown();
    }

    /** All events captured so far, in dispatch order. */
    public List<Event> events() {
        return List.copyOf(events);
    }

    /** Event names in dispatch order. */
    public List<String> names() {
        return events.stream().map(Event::name).toList();
    }

    public boolean has(String name) {
        return events.stream().anyMatch(e -> e.name().equals(name));
    }

    /** The cause delivered with {@code exchangeFailed}, when one was captured. */
    public Optional<Throwable> failureCause() {
        return events.stream().filter(e -> e.name().equals("exchangeFailed"))
                .map(Event::cause).findFirst();
    }

    /** The highest spool total reported for any attachment. */
    public long maxSpooled() {
        return events.stream().filter(e -> e.name().equals("bytesSpooled"))
                .mapToLong(Event::totalSpooled).max().orElse(0);
    }

    /** Waits until {@code exchangeClosed} was dispatched. */
    public boolean awaitClosed(long timeout, TimeUnit unit) throws InterruptedException {
        return closed.await(timeout, unit);
    }
}
