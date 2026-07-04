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
package dev.restxop.core.internal.mime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.restxop.MalformedMessageException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class DelimiterScannerTest {

    private static final String BOUNDARY = "39783eb8-26f4-4d49-bc54-f44ca1dff15c";

    /** Assembles a CRLF-framed message body around raw part bytes. */
    private static byte[] message(String boundary, byte[]... parts) {
        StringBuilder sb = new StringBuilder();
        for (byte[] part : parts) {
            sb.append("\r\n--").append(boundary).append("\r\n");
            sb.append(new String(part, StandardCharsets.ISO_8859_1));
        }
        sb.append("\r\n--").append(boundary).append("--\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static DelimiterScanner scanner(byte[] data, String boundary, int bufferSize) {
        return new DelimiterScanner(new ByteArrayInputStream(data), boundary, bufferSize, "ex-test");
    }

    private static List<byte[]> allParts(DelimiterScanner scanner) throws IOException {
        List<byte[]> parts = new ArrayList<>();
        InputStream part;
        while ((part = scanner.nextPart()) != null) {
            parts.add(part.readAllBytes());
        }
        return parts;
    }

    @Test
    void delimiterOwnsItsLeadingCrlf() throws IOException {
        byte[] content = bytes("This is a test");
        DelimiterScanner scanner = scanner(message(BOUNDARY, content), BOUNDARY, 8192);

        List<byte[]> parts = allParts(scanner);

        assertEquals(1, parts.size());
        assertArrayEquals(content, parts.get(0), "no trailing CRLF may leak into content");
        assertEquals(content.length, parts.get(0).length);
    }

    @Test
    void multiplePartsSplitExactly() throws IOException {
        byte[] a = bytes("first part");
        byte[] b = bytes("second\r\nwith internal CRLF\r\n");
        byte[] c = new byte[0];
        DelimiterScanner scanner = scanner(message(BOUNDARY, a, b, c), BOUNDARY, 64);

        List<byte[]> parts = allParts(scanner);

        assertEquals(3, parts.size());
        assertArrayEquals(a, parts.get(0));
        assertArrayEquals(b, parts.get(1));
        assertArrayEquals(c, parts.get(2));
    }

    @Test
    void boundaryLikeContentIsNotSplit() throws IOException {
        // Mid-line occurrences and line-start occurrences with a wrong tail
        // are content, not delimiters (FR-006)
        byte[] content = bytes("data --" + BOUNDARY + " inline\r\n"
                + "--" + BOUNDARY + "X wrong tail\r\n"
                + "tail text");
        DelimiterScanner scanner = scanner(message(BOUNDARY, content), BOUNDARY, 128);

        List<byte[]> parts = allParts(scanner);

        assertEquals(1, parts.size());
        assertArrayEquals(content, parts.get(0));
    }

    @Test
    void legacyAdversarialRepeatBoundaryParses() throws IOException {
        // Mirrors the captured legacy fixture: the boundary is built from
        // repeated self-similar fragments and the content contains near-misses
        String boundary = "3978--3978--3968--3958--3948";
        byte[] root = bytes("{\"field1\":\"--3978--3978--3978 ---String Value 2\"}");
        byte[] attachment = bytes("This is a test");
        DelimiterScanner scanner = scanner(message(boundary, root, attachment), boundary, 64);

        List<byte[]> parts = allParts(scanner);

        assertEquals(2, parts.size());
        assertArrayEquals(root, parts.get(0));
        assertArrayEquals(attachment, parts.get(1));
    }

    @Test
    void bareLfFramingIsAcceptedOnRead() throws IOException {
        String body = "\n--" + BOUNDARY + "\n"
                + "part one lf"
                + "\n--" + BOUNDARY + "\n"
                + "part two lf"
                + "\n--" + BOUNDARY + "--\n";
        DelimiterScanner scanner = scanner(bytes(body), BOUNDARY, 64);

        List<byte[]> parts = allParts(scanner);

        assertEquals(2, parts.size());
        assertArrayEquals(bytes("part one lf"), parts.get(0));
        assertArrayEquals(bytes("part two lf"), parts.get(1));
    }

    @Test
    void preambleIsIgnoredAndFirstDelimiterMayLackLeadingCrlf() throws IOException {
        String body = "--" + BOUNDARY + "\r\n"
                + "no preamble at all"
                + "\r\n--" + BOUNDARY + "--\r\n";
        DelimiterScanner scanner = scanner(bytes(body), BOUNDARY, 8192);
        List<byte[]> parts = allParts(scanner);
        assertEquals(1, parts.size());
        assertArrayEquals(bytes("no preamble at all"), parts.get(0));

        String withPreamble = "this is preamble to ignore\r\n--" + BOUNDARY + "\r\n"
                + "real content"
                + "\r\n--" + BOUNDARY + "--\r\n";
        scanner = scanner(bytes(withPreamble), BOUNDARY, 64);
        parts = allParts(scanner);
        assertEquals(1, parts.size());
        assertArrayEquals(bytes("real content"), parts.get(0));
    }

    @Test
    void closingDelimiterDrainsEpilogueFully() throws IOException {
        byte[] content = bytes("payload");
        byte[] base = message(BOUNDARY, content);
        byte[] epilogue = bytes("trailing epilogue garbage that must be drained");
        byte[] data = new byte[base.length + epilogue.length];
        System.arraycopy(base, 0, data, 0, base.length);
        System.arraycopy(epilogue, 0, data, base.length, epilogue.length);

        CountingInputStream counting = new CountingInputStream(new ByteArrayInputStream(data));
        DelimiterScanner scanner = new DelimiterScanner(counting, BOUNDARY, 64, "ex-test");

        List<byte[]> parts = allParts(scanner);

        assertEquals(1, parts.size());
        assertArrayEquals(content, parts.get(0));
        assertEquals(data.length, counting.consumed, "epilogue must be drained to EOF");
        assertNull(scanner.nextPart(), "nextPart stays null after the closing delimiter");
    }

    @Test
    void closingDelimiterAtEofWithoutFinalLineBreakIsAccepted() throws IOException {
        String body = "\r\n--" + BOUNDARY + "\r\n" + "content" + "\r\n--" + BOUNDARY + "--";
        DelimiterScanner scanner = scanner(bytes(body), BOUNDARY, 64);
        List<byte[]> parts = allParts(scanner);
        assertEquals(1, parts.size());
        assertArrayEquals(bytes("content"), parts.get(0));
    }

    @Test
    void truncationMidPartIsMalformed() {
        byte[] full = message(BOUNDARY, bytes("this content will be cut off before the closing delimiter"));
        byte[] cut = new byte[full.length - 45];
        System.arraycopy(full, 0, cut, 0, cut.length);
        DelimiterScanner scanner = scanner(cut, BOUNDARY, 64);

        assertThrows(MalformedMessageException.class, () -> allParts(scanner));
    }

    @Test
    void truncationBeforeFirstDelimiterIsMalformed() {
        DelimiterScanner scanner = scanner(bytes("no delimiter anywhere in this stream"), BOUNDARY, 64);
        assertThrows(MalformedMessageException.class, scanner::nextPart);
    }

    @Test
    void unreadPartsAreAutoDrainedOnAdvance() throws IOException {
        byte[] a = bytes("skipped entirely");
        byte[] b = bytes("read this one");
        DelimiterScanner scanner = scanner(message(BOUNDARY, a, b), BOUNDARY, 64);

        InputStream first = scanner.nextPart();
        // do not read `first` at all
        InputStream second = scanner.nextPart();
        assertArrayEquals(b, second.readAllBytes());
        assertNull(scanner.nextPart());
        assertEquals(-1, first.read(new byte[8], 0, 8), "abandoned part reads as exhausted");
    }

    @Test
    void largeContentSpanningManyRefillsStaysByteExact() throws IOException {
        // Content sprinkled with CRLFs and boundary fragments near refill
        // edges; small scan buffer forces cross-refill delimiter matching
        Random random = new Random(7);
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 100_000) {
            switch (random.nextInt(5)) {
                case 0 -> sb.append("\r\n");
                case 1 -> sb.append("--").append(BOUNDARY, 0, 1 + random.nextInt(BOUNDARY.length() - 1));
                case 2 -> sb.append("\r\n--");
                case 3 -> sb.append('\r');
                default -> sb.append("plain text ");
            }
        }
        byte[] content = bytes(sb.toString());
        DelimiterScanner scanner = scanner(message(BOUNDARY, content), BOUNDARY, 1024);

        List<byte[]> parts = allParts(scanner);

        assertEquals(1, parts.size());
        assertArrayEquals(content, parts.get(0));
    }

    @Test
    void readsAreServedInBulk() throws IOException {
        byte[] content = new byte[32 * 1024];
        new Random(11).nextBytes(content);
        // Avoid accidental delimiter bytes: replace CR/LF
        for (int i = 0; i < content.length; i++) {
            if (content[i] == '\r' || content[i] == '\n') {
                content[i] = 'x';
            }
        }
        DelimiterScanner scanner = scanner(message(BOUNDARY, content), BOUNDARY, 8192);
        InputStream part = scanner.nextPart();
        byte[] chunk = new byte[4096];
        int n = part.read(chunk, 0, chunk.length);
        assertTrue(n > 1024, "scanner must emit in bulk, got " + n);
    }

    private static final class CountingInputStream extends InputStream {

        private final InputStream delegate;
        long consumed;

        CountingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                consumed++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) {
                consumed += n;
            }
            return n;
        }
    }
}
