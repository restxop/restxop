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
package dev.restxop.samples.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Synthesizes a small but genuine one-page PDF, padded inside its content
 * stream to a requested total size — deterministic bytes, so demo clients
 * can verify checksums, and large enough to make streaming visible.
 */
public final class PdfSynthesizer {

    private PdfSynthesizer() {
    }

    /** A valid single-page PDF of exactly {@code totalSize} bytes (min ~1 KB). */
    public static byte[] synthesize(long totalSize, String title) {
        long padding = Math.max(0, totalSize - build(title, 0).length);
        byte[] document = build(title, padding);
        // Padding changes digit counts in Length/startxref; converge on the
        // requested size (a couple of iterations at most)
        for (int i = 0; i < 5 && document.length != totalSize; i++) {
            padding = Math.max(0, padding + (totalSize - document.length));
            document = build(title, padding);
        }
        return document;
    }

    private static byte[] build(String title, long padding) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long[] offsets = new long[6];

            write(out, "%PDF-1.4\n%âãÏÓ\n");

            offsets[1] = out.size();
            write(out, "1 0 obj\n<</Type /Catalog /Pages 2 0 R>>\nendobj\n");

            offsets[2] = out.size();
            write(out, "2 0 obj\n<</Type /Pages /Kids [3 0 R] /Count 1>>\nendobj\n");

            offsets[3] = out.size();
            write(out, "3 0 obj\n<</Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                    + "/Resources <</Font <</F1 5 0 R>>>> /Contents 4 0 R>>\nendobj\n");

            String text = "BT /F1 24 Tf 72 700 Td (" + title.replace("(", "").replace(")", "")
                    + ") Tj ET\n";
            long streamLength = text.length() + padding;
            offsets[4] = out.size();
            write(out, "4 0 obj\n<</Length " + streamLength + ">>\nstream\n" + text);
            byte[] pad = new byte[64 * 1024];
            java.util.Arrays.fill(pad, (byte) ' ');
            long remaining = padding;
            while (remaining > 0) {
                int n = (int) Math.min(pad.length, remaining);
                out.write(pad, 0, n);
                remaining -= n;
            }
            write(out, "\nendstream\nendobj\n");

            offsets[5] = out.size();
            write(out, "5 0 obj\n<</Type /Font /Subtype /Type1 /BaseFont /Helvetica>>\nendobj\n");

            long xref = out.size();
            StringBuilder table = new StringBuilder("xref\n0 6\n0000000000 65535 f \n");
            for (int i = 1; i <= 5; i++) {
                table.append(String.format("%010d 00000 n %n", offsets[i]).replace(System.lineSeparator(), "\n"));
            }
            write(out, table.toString());
            write(out, "trailer\n<</Size 6 /Root 1 0 R>>\nstartxref\n" + xref + "\n%%EOF\n");
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(ByteArrayOutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
    }
}
