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

import dev.restxop.MalformedMessageException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Buffered MIME delimiter scanner: splits the transport stream into part
 * streams on the framed delimiter {@code CRLF "--" boundary} (bare-LF
 * framing accepted on read), emitting content in bulk. The delimiter's
 * leading line break is framing and never appears in part content; a
 * boundary-like byte sequence without correct framing (line-break prefix and
 * valid tail) is content.
 *
 * <p>Matching scans the buffered window for the LF-anchored pattern
 * {@code LF "--" boundary} (anchor-byte scan with direct verification); the
 * trailing
 * delimiter-length bytes of each window are retained across refills so a
 * delimiter (including its optional leading CR) can never be split by a
 * refill and leak bytes into content. Single-threaded use by the drain.</p>
 */
public final class DelimiterScanner {

    /** Longest accepted run of transport padding (WSP) after the boundary. */
    private static final int MAX_PADDING = 64;
    /** Tail lookahead: "--" + padding + CRLF. */
    private static final int TAIL_LOOKAHEAD = 2 + MAX_PADDING + 2;

    private final InputStream in;
    private final String exchangeId;
    /** LF-anchored pattern: {@code \n--boundary}. */
    private final byte[] pattern;
    private final byte[] buf;
    private int pos;
    private int limit;
    private boolean eof;
    /** Buffer index below which no delimiter candidate can start (scan cache). */
    private int cleanBefore;

    private PartStream current;
    private boolean closingSeen;
    private boolean epilogueDrained;

    public DelimiterScanner(InputStream in, String boundary, int bufferSize, String exchangeId) {
        this.in = in;
        this.exchangeId = exchangeId;
        byte[] boundaryBytes = boundary.getBytes(StandardCharsets.ISO_8859_1);
        this.pattern = new byte[3 + boundaryBytes.length];
        pattern[0] = '\n';
        pattern[1] = '-';
        pattern[2] = '-';
        System.arraycopy(boundaryBytes, 0, pattern, 3, boundaryBytes.length);
        int capacity = Math.max(bufferSize, pattern.length + 1 + TAIL_LOOKAHEAD + 128);
        this.buf = new byte[capacity];
        // Virtual CRLF ahead of the stream so an opening delimiter at byte 0
        // ("--boundary" without a preceding line break) matches uniformly
        buf[0] = '\r';
        buf[1] = '\n';
        this.limit = 2;
    }


    /**
     * Advances to the next part: drains the current part (and the preamble
     * before the first call), consumes the delimiter, and returns the new
     * part's content stream — or {@code null} once the closing delimiter was
     * reached and the epilogue drained.
     */
    public InputStream nextPart() throws IOException {
        if (closingSeen) {
            drainEpilogue();
            return null;
        }
        if (current == null) {
            // First call: the preamble is a discarded pseudo-part
            drain(new PartStream(true));
        } else {
            drain(current);
            current = null;
        }
        if (closingSeen) {
            drainEpilogue();
            return null;
        }
        current = new PartStream(false);
        return current;
    }

    private void drain(PartStream part) throws IOException {
        byte[] sink = new byte[4096];
        while (part.read(sink, 0, sink.length) != -1) {
            // discard
        }
    }

    private void drainEpilogue() throws IOException {
        if (epilogueDrained) {
            return;
        }
        epilogueDrained = true;
        pos = 0;
        limit = 0;
        byte[] sink = new byte[4096];
        while (!eof) {
            if (in.read(sink, 0, sink.length) < 0) {
                eof = true;
            }
        }
    }

    /** Compacts the unconsumed window to the front and reads more bytes. */
    private void refill() throws IOException {
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, limit - pos);
            limit -= pos;
            cleanBefore = Math.max(0, cleanBefore - pos);
            pos = 0;
        }
        if (eof || limit == buf.length) {
            return;
        }
        int n = in.read(buf, limit, buf.length - limit);
        if (n < 0) {
            eof = true;
        } else {
            limit += n;
        }
    }

    /**
     * Search for the LF-anchored pattern fully inside {@code [from, limit)}.
     * Hot path: a tight scan for the LF anchor byte with a direct compare on
     * hits — equivalent to the KMP automaton (the pattern starts with a byte
     * that cannot recur inside a boundary token, so candidates never
     * overlap), but an order of magnitude faster per transported byte
     * (SC-006 tuning). The adversarial fixtures in the test suite pin the
     * equivalence.
     */
    private int findCandidate(int from) {
        int last = limit - pattern.length;
        for (int i = Math.max(from, cleanBefore); i <= last; i++) {
            if (buf[i] != '\n') {
                continue;
            }
            if (matchesPatternAt(i)) {
                return i;
            }
        }
        // Nothing can start before this point; per-byte callers (header
        // parsing) must not pay a full window re-scan per byte
        cleanBefore = Math.max(cleanBefore, last + 1);
        return -1;
    }

    private boolean matchesPatternAt(int position) {
        for (int j = 1; j < pattern.length; j++) {
            if (buf[position + j] != pattern[j]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates the bytes after a candidate {@code LF--boundary} match: an
     * optional {@code --} (closing), optional transport padding, then a line
     * break (or EOF for a closing delimiter).
     *
     * @return the buffer position just past the delimiter (with
     *         {@link #closingSeen} updated), -1 if the candidate is not a
     *         correctly framed delimiter and must be treated as content, or
     *         -2 if more buffered bytes are needed to decide
     */
    private int validateTail(int candidate) {
        int t = candidate + pattern.length;
        boolean closing = false;
        if (t + 1 < limit && buf[t] == '-' && buf[t + 1] == '-') {
            closing = true;
            t += 2;
        } else if (t + 1 >= limit && !eof) {
            return -2; // need more bytes to decide
        }
        int padding = 0;
        while (t < limit && (buf[t] == ' ' || buf[t] == '\t') && padding <= MAX_PADDING) {
            t++;
            padding++;
        }
        if (padding > MAX_PADDING) {
            return -1;
        }
        if (t >= limit) {
            if (!eof) {
                return -2; // need more bytes
            }
            // EOF directly after the (closing) boundary is acceptable
            return closing ? markClosing(t) : -1;
        }
        if (buf[t] == '\r') {
            if (t + 1 < limit && buf[t + 1] == '\n') {
                return closing ? markClosing(t + 2) : t + 2;
            }
            if (t + 1 >= limit && !eof) {
                return -2;
            }
            return -1;
        }
        if (buf[t] == '\n') {
            return closing ? markClosing(t + 1) : t + 1;
        }
        return -1;
    }

    private int markClosing(int endPos) {
        closingSeen = true;
        return endPos;
    }

    private MalformedMessageException truncated(String where) {
        return new MalformedMessageException(exchangeId,
                "truncated message: end of stream " + where);
    }

    /** Content stream of one part (or the discarded preamble). */
    private final class PartStream extends InputStream implements BulkTransfer {

        private final boolean preamble;
        private boolean ended;
        private final byte[] single = new byte[1];

        PartStream(boolean preamble) {
            this.preamble = preamble;
        }

        @Override
        public int read() throws IOException {
            int n = read(single, 0, 1);
            return n < 0 ? -1 : single[0] & 0xFF;
        }

        /**
         * Locates the next contiguous run of content bytes at {@code pos},
         * refilling and resolving delimiter candidates as needed.
         *
         * @return the run length (>=1), or -1 when the part is exhausted
         */
        private int nextChunkLength(int cap) throws IOException {
            while (true) {
                if (pos >= limit) {
                    if (eof) {
                        throw truncated(preamble ? "before the opening delimiter"
                                : "before the closing delimiter");
                    }
                    refill();
                    continue;
                }
                int candidate = findCandidate(pos);
                if (candidate < 0) {
                    if (eof) {
                        throw truncated(preamble ? "before the opening delimiter"
                                : "before the closing delimiter");
                    }
                    // Keep back pattern-length+1 bytes (a delimiter with its
                    // leading CR may straddle the refill edge)
                    int safeEnd = limit - (pattern.length + 1);
                    if (safeEnd > pos) {
                        return Math.min(cap, safeEnd - pos);
                    }
                    refill();
                    continue;
                }
                // Bytes before the candidate's line break (minus a preceding
                // CR, which belongs to the delimiter if the match confirms)
                int contentEnd = candidate > pos && buf[candidate - 1] == '\r'
                        ? candidate - 1 : candidate;
                if (contentEnd > pos) {
                    return Math.min(cap, contentEnd - pos);
                }
                // The candidate starts right at the read position: resolve it
                int resolution = resolveWithRefill(candidate);
                if (resolution == -1) {
                    // Not a delimiter: the line-break byte (and a preceding
                    // CR, if any) are content after all — and definitively so
                    cleanBefore = Math.max(cleanBefore, candidate + 1);
                    return Math.min(cap, (candidate + 1) - pos);
                }
                pos = resolution;
                ended = true;
                return -1;
            }
        }

        @Override
        public int read(byte[] out, int off, int len) throws IOException {
            if (ended) {
                return -1;
            }
            if (len == 0) {
                return 0;
            }
            int n = nextChunkLength(len);
            if (n < 0) {
                return -1;
            }
            System.arraycopy(buf, pos, out, off, n);
            pos += n;
            return n;
        }

        @Override
        public int transferNext(Sink sink) throws IOException {
            if (ended) {
                return -1;
            }
            int n = nextChunkLength(Integer.MAX_VALUE);
            if (n < 0) {
                return -1;
            }
            sink.accept(buf, pos, n);
            pos += n;
            return n;
        }

        /**
         * Resolves a candidate whose tail may span the refill edge. Because
         * {@code refill()} compacts by the current {@code pos}, the caller's
         * candidate index shifts with it; this method re-finds the candidate
         * after each refill (it stays the first match at/near {@code pos}).
         */
        private int resolveWithRefill(int candidate) throws IOException {
            while (true) {
                int result = validateTail(candidate);
                if (result != -2) {
                    return result;
                }
                int posBefore = pos;
                refill();
                candidate -= posBefore - pos;
                if (eof && validateTail(candidate) == -2) {
                    // Stream ended mid-tail: not a valid delimiter
                    return -1;
                }
            }
        }
    }
}
