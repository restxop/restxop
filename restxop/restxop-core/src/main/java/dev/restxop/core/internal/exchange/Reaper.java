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

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single shared scheduler enforcing exchange TTLs (FR-023). Purely
 * infrastructure — it holds no exchange state; each exchange schedules its
 * own reclamation and cancels it on normal termination. Daemon threads, so
 * it never blocks JVM shutdown.
 */
public final class Reaper {

    private static final class Holder {
        private static final ScheduledExecutorService SCHEDULER =
                Executors.newSingleThreadScheduledExecutor(new ReaperThreadFactory());
    }

    private static final class ReaperThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "restxop-reaper-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private Reaper() {
    }

    /** Schedules {@code reclamation} to run after {@code ttl}. */
    // ScheduledFuture<?> is the JDK scheduler's own return shape; callers
    // only cancel it
    @SuppressWarnings("java:S1452")
    public static ScheduledFuture<?> schedule(Runnable reclamation, Duration ttl) {
        return Holder.SCHEDULER.schedule(reclamation, ttl.toNanos(), TimeUnit.NANOSECONDS);
    }
}
