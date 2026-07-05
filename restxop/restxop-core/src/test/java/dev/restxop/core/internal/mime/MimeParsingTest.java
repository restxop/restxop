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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.restxop.LimitExceededException;
import dev.restxop.MalformedMessageException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

// Wire strings keep explicit \r\n: text blocks would obscure the exact
// CRLF bytes under test
@SuppressWarnings("java:S6126")
class MimeParsingTest {

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static PartHeaders parse(String block) throws IOException {
        return PartHeaders.parse(stream(block), 64 * 1024, "ex-test");
    }

    @Nested
    class ContentTypeParamsParsing {

        @Test
        void parsesQuotedParametersCaseInsensitively() {
            ContentTypeParams params = ContentTypeParams.parse(
                    "MULTIPART/Related; TYPE=\"application/json\"; Boundary=\"abc-123\"; START=\"<root>\"");
            assertEquals("multipart/related", params.mediaType());
            assertEquals("application/json", params.parameter("type").orElseThrow());
            assertEquals("abc-123", params.parameter("BOUNDARY").orElseThrow());
            assertEquals("<root>", params.parameter("start").orElseThrow());
        }

        @Test
        void parsesUnquotedTokenValues() {
            ContentTypeParams params = ContentTypeParams.parse(
                    "multipart/related; type=application/json; boundary=simple-token");
            assertEquals("application/json", params.parameter("type").orElseThrow());
            assertEquals("simple-token", params.parameter("boundary").orElseThrow());
        }

        @Test
        void quotedValuesMayContainEscapesSemicolonsAndDashes() {
            ContentTypeParams params = ContentTypeParams.parse(
                    "multipart/related; boundary=\"ab--cd--ef\"; note=\"semi;colon and \\\"quote\\\"\"");
            assertEquals("ab--cd--ef", params.parameter("boundary").orElseThrow());
            assertEquals("semi;colon and \"quote\"", params.parameter("note").orElseThrow());
        }

        @Test
        void toleratesWhitespaceAroundDelimiters() {
            ContentTypeParams params = ContentTypeParams.parse(
                    "multipart/related ;  boundary = \"b\" ;type= application/json ");
            assertEquals("b", params.parameter("boundary").orElseThrow());
            assertEquals("application/json", params.parameter("type").orElseThrow());
        }

        @Test
        void requiredParameterFailsDescriptivelyWhenAbsent() {
            ContentTypeParams params = ContentTypeParams.parse("multipart/related; type=\"application/json\"");
            MalformedMessageException e = assertThrows(MalformedMessageException.class,
                    () -> params.requiredParameter("boundary"));
            assertTrue(e.getMessage().contains("boundary"), e.getMessage());
        }

        @Test
        void missingContentTypeIsMalformed() {
            assertThrows(MalformedMessageException.class, () -> ContentTypeParams.parse(null));
            assertThrows(MalformedMessageException.class, () -> ContentTypeParams.parse("  "));
        }
    }

    @Nested
    class PartHeaderBlocks {

        @Test
        void parsesBlockAndStopsExactlyAtBlankLine() throws IOException {
            InputStream in = stream("Content-ID: <abc>\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "\r\n"
                    + "CONTENT");
            PartHeaders headers = PartHeaders.parse(in, 64 * 1024, "ex-test");
            assertEquals("abc", headers.contentId().orElseThrow());
            assertEquals("application/octet-stream", headers.contentType().orElseThrow());
            assertEquals('C', in.read(), "parser must not consume content bytes");
        }

        @Test
        void headerNamesAreCaseInsensitiveAndValuesTrimmed() throws IOException {
            PartHeaders headers = parse("CONTENT-id:   <weird>  \r\ncontent-TYPE: text/plain\r\n\r\n");
            assertEquals("weird", headers.contentId().orElseThrow());
            assertEquals("text/plain", headers.firstValue("Content-Type").orElseThrow());
            assertEquals("text/plain", headers.firstValue("content-type").orElseThrow());
        }

        @Test
        void acceptsBareLfLineEndings() throws IOException {
            PartHeaders headers = parse("Content-ID: <lf-part>\nContent-Type: text/plain\n\n");
            assertEquals("lf-part", headers.contentId().orElseThrow());
            assertEquals("text/plain", headers.contentType().orElseThrow());
        }

        @Test
        void unfoldsContinuationLinesOnRead() throws IOException {
            PartHeaders headers = parse("Content-Disposition: attachment;\r\n"
                    + " filename=\"folded.txt\"\r\n\r\n");
            assertEquals("folded.txt", headers.filename().orElseThrow());
        }

        @Test
        void doubleDashInsideValuesDoesNotTerminateParsing() throws IOException {
            PartHeaders headers = parse("Content-Disposition: attachment;filename=\"Test--123--x\"\r\n"
                    + "Content-ID: <has--dashes>\r\n\r\n");
            assertEquals("Test--123--x", headers.filename().orElseThrow());
            assertEquals("has--dashes", headers.contentId().orElseThrow());
        }

        @Test
        void nonAsciiHeaderBytesAreIso88591Interpreted() throws IOException {
            PartHeaders headers = parse("X-Custom: café\r\n\r\n");
            assertEquals("café", headers.firstValue("X-Custom").orElseThrow());
        }

        @Test
        void oversizedHeaderBlockHitsTheConfiguredBound() {
            String big = "X-Big: " + "v".repeat(200) + "\r\n\r\n";
            InputStream oversized = stream(big);
            LimitExceededException e = assertThrows(LimitExceededException.class,
                    () -> PartHeaders.parse(oversized, 64, "ex-test"));
            assertEquals("limits.max-part-header-bytes", e.limitName());
            assertEquals(64, e.configuredValue());
        }

        @Test
        void truncationBeforeBlankLineIsMalformed() {
            assertThrows(MalformedMessageException.class,
                    () -> parse("Content-ID: <abc>\r\nContent-Type: text/plain\r\n"));
        }

        @Test
        void malformedHeaderLineWithoutColonIsMalformed() {
            assertThrows(MalformedMessageException.class,
                    () -> parse("this-line-has-no-colon\r\n\r\n"));
        }

        @Test
        void filenameStarTakesPrecedenceAndPercentDecodes() throws IOException {
            PartHeaders headers = parse("Content-Disposition: attachment; filename=\"fallback.txt\";"
                    + " filename*=UTF-8''na%C3%AFve%20file.txt\r\n\r\n");
            assertEquals("naïve file.txt", headers.filename().orElseThrow());
        }

        @Test
        void legacyNameParameterServesAsFilenameFallback() throws IOException {
            PartHeaders headers = parse("Content-Disposition: attachment;name=\"Test-123\"\r\n\r\n");
            assertEquals("Test-123", headers.filename().orElseThrow());
        }

        @Test
        void repeatedHeadersExposeFirstValue() throws IOException {
            PartHeaders headers = parse("X-Dup: first\r\nX-Dup: second\r\n\r\n");
            assertEquals("first", headers.firstValue("X-Dup").orElseThrow());
        }
    }

    @Nested
    class IdNormalization {

        @Test
        void stripsOneAngleBracketPair() {
            assertEquals("abc", IdNormalizer.normalize("<abc>"));
            assertEquals("<abc>", IdNormalizer.normalize("<<abc>>"));
        }

        @Test
        void stripsOneCidPrefixCaseInsensitively() {
            assertEquals("abc", IdNormalizer.normalize("cid:abc"));
            assertEquals("abc", IdNormalizer.normalize("CID:abc"));
            assertEquals("cid:abc", IdNormalizer.normalize("cid:cid:abc"));
        }

        @Test
        void bracketsThenPrefix() {
            assertEquals("abc", IdNormalizer.normalize("<cid:abc>"));
        }

        @Test
        void bareIdentifiersAndWhitespacePassThroughTrimmed() {
            assertEquals("574e6d7e", IdNormalizer.normalize("  574e6d7e "));
            assertEquals("mainpart", IdNormalizer.normalize("mainpart"));
        }
    }
}
