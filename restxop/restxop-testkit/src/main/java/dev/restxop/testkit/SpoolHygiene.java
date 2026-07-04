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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.opentest4j.AssertionFailedError;

/**
 * Spool-hygiene assertions (SC-004): no spool file may outlive its
 * exchange, and connections handed to restxop must be released on every
 * outcome.
 */
public final class SpoolHygiene {

    private SpoolHygiene() {
    }

    /** Files matching the restxop spool naming scheme currently in {@code dir}. */
    public static List<Path> spoolFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "restxop-*.spool")) {
            stream.forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    /**
     * Asserts the spool directory holds no residual files, polling briefly
     * because cleanup may complete asynchronously right after a terminal
     * exchange state becomes observable.
     */
    public static void assertNoResidualSpoolFiles(Path dir) {
        long deadline = System.currentTimeMillis() + 5000;
        List<Path> residual = spoolFiles(dir);
        while (!residual.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            residual = spoolFiles(dir);
        }
        if (!residual.isEmpty()) {
            throw new AssertionFailedError("residual spool files after exchange end: " + residual);
        }
    }

    /**
     * A connection-release probe: hand {@code asReleaseHandle()} to restxop
     * as the upstream release and assert {@link #assertReleased()} after the
     * exchange ends.
     */
    public static final class ConnectionProbe {

        private final AtomicBoolean released = new AtomicBoolean();

        public AutoCloseable asReleaseHandle() {
            return () -> released.set(true);
        }

        public boolean isReleased() {
            return released.get();
        }

        /** Polls briefly: release may fire from the drain thread. */
        public void assertReleased() {
            long deadline = System.currentTimeMillis() + 5000;
            while (!released.get() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!released.get()) {
                throw new AssertionFailedError("upstream connection was not released");
            }
        }
    }
}
