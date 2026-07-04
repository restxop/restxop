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

import dev.restxop.ExchangeTimeoutException;
import dev.restxop.LimitExceededException;
import dev.restxop.RestxopConfig;
import dev.restxop.core.internal.buffer.ChaseBuffer;
import dev.restxop.spi.AttachmentInfo;
import dev.restxop.spi.ExchangeInfo;
import dev.restxop.spi.ExchangeListener;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lifecycle owner of one message read or write: configuration snapshot, TTL
 * deadline, chase-buffer index, resource registry, and listener dispatch.
 * Every cleanup guarantee hangs off this object — resources are released
 * exactly once, on every outcome. No static or thread-local state; exchanges
 * share nothing.
 */
public final class Exchange implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Exchange.class);

    public enum State {
        OPEN, COMPLETED, FAILED, RECLAIMED
    }

    /** Read-side drain progress; the drain owns the parser position. */
    public enum DrainState {
        QUEUED, RUNNING, CALLER_RUNS, DONE, FAILED
    }

    private final String id = UUID.randomUUID().toString();
    private final RestxopConfig config;
    private final Instant startedAt = Instant.now();
    private final long deadlineNanos;
    private final List<ExchangeListener> listeners;
    private final ExchangeInfo info;
    private final ScheduledFuture<?> reaperHandle;

    private final Object lock = new Object();
    private State state = State.OPEN;
    private volatile DrainState drainState = DrainState.QUEUED;
    private Throwable failureCause;
    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    private final Map<String, ChaseBuffer> bufferIndex = new LinkedHashMap<>();
    private final Map<String, Long> spooledPerPart = new HashMap<>();
    private long spooledAggregate;

    private Exchange(RestxopConfig config, List<ExchangeListener> listeners) {
        this.config = config;
        this.listeners = List.copyOf(listeners);
        this.deadlineNanos = System.nanoTime() + config.exchangeTtl().toNanos();
        this.info = new Info(id, startedAt);
        this.reaperHandle = Reaper.schedule(this::reclaim, config.exchangeTtl());
    }

    /** Opens an exchange, schedules its TTL reclamation, and fires {@code exchangeStarted}. */
    public static Exchange open(RestxopConfig config, List<ExchangeListener> listeners) {
        Exchange exchange = new Exchange(config, listeners);
        log.debug("[exchange {}] started", exchange.id);
        exchange.dispatch(l -> l.exchangeStarted(exchange.info));
        return exchange;
    }

    public String id() {
        return id;
    }

    public RestxopConfig config() {
        return config;
    }

    public ExchangeInfo info() {
        return info;
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    public Optional<Throwable> failureCause() {
        synchronized (lock) {
            return Optional.ofNullable(failureCause);
        }
    }

    public DrainState drainState() {
        return drainState;
    }

    public void drainState(DrainState newState) {
        this.drainState = newState;
    }

    /**
     * Registers a resource for release at exchange end (LIFO order). If the
     * exchange is already terminal the resource is closed immediately.
     */
    public void registerResource(AutoCloseable resource) {
        synchronized (lock) {
            if (state == State.OPEN) {
                resources.push(resource);
                return;
            }
        }
        closeQuietly(resource);
    }

    /** Indexes a chase buffer by normalized Content-ID; released at exchange end. */
    public void registerBuffer(ChaseBuffer buffer) {
        synchronized (lock) {
            if (state == State.OPEN) {
                bufferIndex.put(buffer.contentId(), buffer);
                return;
            }
        }
        buffer.release();
    }

    public Optional<ChaseBuffer> buffer(String contentId) {
        synchronized (lock) {
            return Optional.ofNullable(bufferIndex.get(contentId));
        }
    }

    /**
     * Throws (and fails the exchange) when the TTL deadline has passed —
     * called at parser/writer advance points.
     */
    public void checkTtl() {
        if (System.nanoTime() - deadlineNanos > 0) {
            ExchangeTimeoutException timeout = new ExchangeTimeoutException(id,
                    "exchange TTL of " + config.exchangeTtl() + " exceeded");
            fail(timeout);
            throw timeout;
        }
    }

    /**
     * Per-message aggregate spool accounting, called from chase-buffer spill
     * listeners with the part's running total. Fires the
     * {@code bytesSpooled} listener event; throws when the aggregate exceeds
     * {@code spool.max-per-message}.
     */
    public void recordSpooled(AttachmentInfo attachment, long partTotal) {
        synchronized (lock) {
            long previous = spooledPerPart.getOrDefault(attachment.contentId(), 0L);
            spooledAggregate += partTotal - previous;
            spooledPerPart.put(attachment.contentId(), partTotal);
            if (spooledAggregate > config.spoolMaxPerMessage()) {
                log.warn("[exchange {}] per-message spool cap breached at {} bytes", id, spooledAggregate);
                throw new LimitExceededException(id, "spool.max-per-message",
                        config.spoolMaxPerMessage(),
                        "aggregate spooled bytes across attachments exceed the per-message cap");
            }
        }
        dispatch(l -> l.bytesSpooled(info, attachment, partTotal));
    }

    /** Fires the {@code payloadDelivered} listener event (read side). */
    public void payloadDelivered() {
        log.debug("[exchange {}] payload delivered", id);
        dispatch(l -> l.payloadDelivered(info));
    }

    /** Fires the {@code attachmentConsumed} listener event. */
    public void attachmentConsumed(AttachmentInfo attachment) {
        log.debug("[exchange {}] attachment '{}' consumed", id, attachment.contentId());
        dispatch(l -> l.attachmentConsumed(info, attachment));
    }

    /** Normal termination: releases every resource. No-op when already terminal. */
    public void complete() {
        if (!transition(State.COMPLETED)) {
            return;
        }
        releaseAll();
        log.debug("[exchange {}] completed", id);
        dispatch(l -> l.exchangeClosed(info));
    }

    /**
     * Failure termination: poisons every chase buffer with {@code cause}
     * (waking blocked readers), releases every resource, and fires
     * {@code exchangeFailed} then {@code exchangeClosed}.
     */
    public void fail(Throwable cause) {
        synchronized (lock) {
            if (state != State.OPEN) {
                return;
            }
            state = State.FAILED;
            failureCause = cause;
        }
        reaperHandle.cancel(false);
        poisonBuffers(cause);
        releaseAll();
        log.error("[exchange {}] failed", id, cause);
        dispatch(l -> l.exchangeFailed(info, cause));
        dispatch(l -> l.exchangeClosed(info));
    }

    /** TTL reclamation (abandonment): failure with a timeout cause. */
    public void reclaim() {
        ExchangeTimeoutException cause = new ExchangeTimeoutException(id,
                "exchange reclaimed: TTL of " + config.exchangeTtl() + " expired");
        synchronized (lock) {
            if (state != State.OPEN) {
                return;
            }
            state = State.RECLAIMED;
            failureCause = cause;
        }
        poisonBuffers(cause);
        releaseAll();
        log.warn("[exchange {}] reclaimed after TTL {}", id, config.exchangeTtl());
        dispatch(l -> l.exchangeFailed(info, cause));
        dispatch(l -> l.exchangeClosed(info));
    }

    /** Idempotent close: normal completion. */
    @Override
    public void close() {
        complete();
    }

    private boolean transition(State target) {
        synchronized (lock) {
            if (state != State.OPEN) {
                return false;
            }
            state = target;
        }
        reaperHandle.cancel(false);
        return true;
    }

    private void poisonBuffers(Throwable cause) {
        List<ChaseBuffer> buffers;
        synchronized (lock) {
            buffers = new ArrayList<>(bufferIndex.values());
        }
        for (ChaseBuffer buffer : buffers) {
            buffer.poison(cause);
        }
    }

    private void releaseAll() {
        List<ChaseBuffer> buffers;
        List<AutoCloseable> toClose = new ArrayList<>();
        synchronized (lock) {
            buffers = new ArrayList<>(bufferIndex.values());
            while (!resources.isEmpty()) {
                toClose.add(resources.pop());
            }
        }
        for (ChaseBuffer buffer : buffers) {
            buffer.release();
        }
        for (AutoCloseable resource : toClose) {
            closeQuietly(resource);
        }
    }

    private void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception e) {
            log.warn("[exchange {}] error releasing resource", id, e);
        }
    }

    private void dispatch(Consumer<ExchangeListener> event) {
        for (ExchangeListener listener : listeners) {
            try {
                event.accept(listener);
            } catch (Throwable t) {
                log.warn("[exchange {}] listener threw; ignoring", id, t);
            }
        }
    }

    private record Info(String exchangeId, Instant startedAt) implements ExchangeInfo {
    }
}
