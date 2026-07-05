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
package dev.restxop.core.internal.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.restxop.ExchangeFailedException;
import dev.restxop.ExchangeTimeoutException;
import dev.restxop.LimitExceededException;
import dev.restxop.RestxopConfig;
import dev.restxop.core.internal.buffer.ChaseBuffer;
import dev.restxop.spi.AttachmentInfo;
import dev.restxop.spi.ExchangeInfo;
import dev.restxop.spi.ExchangeListener;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
// Thread.sleep here simulates pacing and park windows in real-time
// streaming behavior — replacing it with synchronization would change
// what is being tested
@SuppressWarnings("java:S2925")
class ExchangeTest {

    private static final RestxopConfig CONFIG = RestxopConfig.defaults();

    private record TestAttachmentInfo(String contentId) implements AttachmentInfo {
        @Override
        public Optional<String> filename() {
            return Optional.empty();
        }

        @Override
        public Optional<String> contentType() {
            return Optional.empty();
        }
    }

    private static final class CapturingListener implements ExchangeListener {
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> lastFailure = new AtomicReference<>();

        @Override
        public void exchangeStarted(ExchangeInfo info) {
            events.add("started");
        }

        @Override
        public void exchangeFailed(ExchangeInfo info, Throwable cause) {
            events.add("failed");
            lastFailure.set(cause);
        }

        @Override
        public void exchangeClosed(ExchangeInfo info) {
            events.add("closed");
        }
    }

    private static ChaseBuffer newBuffer(Exchange exchange, String contentId) {
        return new ChaseBuffer(exchange.id(), contentId, 1024, Long.MAX_VALUE,
                Duration.ofSeconds(5), () -> {
                    throw new IOException("no overflow expected");
                }, total -> {
                });
    }

    @Test
    void completeIsTerminalAndLaterFailIsNoOp() {
        CapturingListener listener = new CapturingListener();
        Exchange exchange = Exchange.open(CONFIG, List.of(listener));

        exchange.complete();
        assertEquals(Exchange.State.COMPLETED, exchange.state());

        exchange.fail(new IOException("too late"));
        assertEquals(Exchange.State.COMPLETED, exchange.state(), "terminal states never transition");
        assertEquals(List.of("started", "closed"), listener.events);
    }

    @Test
    void doubleCloseIsNoOp() {
        CapturingListener listener = new CapturingListener();
        Exchange exchange = Exchange.open(CONFIG, List.of(listener));

        exchange.close();
        exchange.close();
        exchange.complete();

        assertEquals(1, listener.events.stream().filter("closed"::equals).count());
    }

    @Test
    void failPoisonsBuffersReleasesResourcesAndDispatches() {
        CapturingListener listener = new CapturingListener();
        Exchange exchange = Exchange.open(CONFIG, List.of(listener));
        AtomicInteger closeCount = new AtomicInteger();
        exchange.registerResource(closeCount::incrementAndGet);
        ChaseBuffer buffer = newBuffer(exchange, "cid-a");
        exchange.registerBuffer(buffer);

        IOException cause = new IOException("mid-stream failure");
        exchange.fail(cause);

        assertEquals(Exchange.State.FAILED, exchange.state());
        assertSame(cause, exchange.failureCause().orElseThrow());
        assertEquals(1, closeCount.get(), "registered resource must be released");
        ExchangeFailedException e = assertThrows(ExchangeFailedException.class,
                () -> buffer.read(new byte[8], 0, 8));
        assertSame(cause, e.getCause());
        assertEquals(List.of("started", "failed", "closed"), listener.events);
        assertSame(cause, listener.lastFailure.get());
    }

    @Test
    void resourcesAreReleasedOnceInReverseOrder() {
        Exchange exchange = Exchange.open(CONFIG, List.of());
        List<String> order = new ArrayList<>();
        exchange.registerResource(() -> order.add("first-registered"));
        exchange.registerResource(() -> order.add("second-registered"));

        exchange.complete();
        exchange.complete();

        assertEquals(List.of("second-registered", "first-registered"), order);
    }

    @Test
    void resourceRegisteredAfterTerminalIsClosedImmediately() {
        Exchange exchange = Exchange.open(CONFIG, List.of());
        exchange.complete();
        AtomicInteger closed = new AtomicInteger();
        exchange.registerResource(closed::incrementAndGet);
        assertEquals(1, closed.get());
    }

    @Test
    void listenerExceptionsAreIsolated() {
        CapturingListener wellBehaved = new CapturingListener();
        ExchangeListener hostile = new ExchangeListener() {
            @Override
            public void exchangeStarted(ExchangeInfo info) {
                throw new IllegalStateException("listener bug");
            }

            @Override
            public void exchangeClosed(ExchangeInfo info) {
                throw new IllegalStateException("listener bug");
            }
        };

        Exchange exchange = Exchange.open(CONFIG, List.of(hostile, wellBehaved));
        exchange.complete();

        assertEquals(List.of("started", "closed"), wellBehaved.events,
                "hostile listener must not break dispatch to others");
        assertEquals(Exchange.State.COMPLETED, exchange.state());
    }

    @Test
    void reaperReclaimsAbandonedExchange() throws Exception {
        RestxopConfig shortTtl = RestxopConfig.builder()
                .exchangeTtl(Duration.ofMillis(250))
                .readWait(Duration.ofMillis(100))
                .build();
        CapturingListener listener = new CapturingListener();
        Exchange exchange = Exchange.open(shortTtl, List.of(listener));
        AtomicInteger closed = new AtomicInteger();
        exchange.registerResource(closed::incrementAndGet);
        ChaseBuffer buffer = newBuffer(exchange, "cid-b");
        exchange.registerBuffer(buffer);

        long deadline = System.currentTimeMillis() + 5000;
        while (exchange.state() == Exchange.State.OPEN && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertEquals(Exchange.State.RECLAIMED, exchange.state());
        assertEquals(1, closed.get(), "reclamation must release resources");
        assertInstanceOf(ExchangeTimeoutException.class, exchange.failureCause().orElseThrow());
        assertTrue(listener.events.contains("failed"));
        assertTrue(listener.events.contains("closed"));
        assertThrows(ExchangeTimeoutException.class, () -> buffer.read(new byte[8], 0, 8));
    }

    @Test
    void completedExchangeIsNotReclaimed() throws Exception {
        RestxopConfig shortTtl = RestxopConfig.builder()
                .exchangeTtl(Duration.ofMillis(200))
                .readWait(Duration.ofMillis(100))
                .build();
        CapturingListener listener = new CapturingListener();
        Exchange exchange = Exchange.open(shortTtl, List.of(listener));
        exchange.complete();

        Thread.sleep(400);

        assertEquals(Exchange.State.COMPLETED, exchange.state());
        assertEquals(List.of("started", "closed"), listener.events);
    }

    @Test
    void checkTtlFailsTheExchangeOnceExpired() throws Exception {
        RestxopConfig shortTtl = RestxopConfig.builder()
                .exchangeTtl(Duration.ofMillis(150))
                .readWait(Duration.ofMillis(100))
                .build();
        Exchange exchange = Exchange.open(shortTtl, List.of());

        Thread.sleep(200);

        assertThrows(ExchangeTimeoutException.class, exchange::checkTtl);
        Exchange.State state = exchange.state();
        assertTrue(state == Exchange.State.FAILED || state == Exchange.State.RECLAIMED,
                "expired exchange must be terminal, was " + state);
    }

    @Test
    void perMessageAggregateSpoolCapIsEnforced() {
        RestxopConfig config = RestxopConfig.builder()
                .memoryWindowPerPart(16)
                .spoolMaxPerAttachment(600)
                .spoolMaxPerMessage(1000)
                .build();
        Exchange exchange = Exchange.open(config, List.of());

        exchange.recordSpooled(new TestAttachmentInfo("cid-a"), 500);
        exchange.recordSpooled(new TestAttachmentInfo("cid-a"), 600); // running total, not additive
        TestAttachmentInfo cidB = new TestAttachmentInfo("cid-b");
        LimitExceededException e = assertThrows(LimitExceededException.class,
                () -> exchange.recordSpooled(cidB, 500));
        assertEquals("spool.max-per-message", e.limitName());
        assertEquals(1000, e.configuredValue());
    }

    @Test
    void infoCarriesStableIdentity() {
        Exchange exchange = Exchange.open(CONFIG, List.of());
        assertNotNull(exchange.id());
        assertEquals(exchange.id(), exchange.info().exchangeId());
        assertNotNull(exchange.info().startedAt());
        exchange.complete();
    }
}
