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
package dev.restxop.jackson2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.restxop.Attachment;
import dev.restxop.spi.AttachmentCollector;
import dev.restxop.spi.ResolvableTypeInfo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class Jackson2CodecTest {

    public static class Doc {
        public String title;
        public Attachment file;
    }

    public static class Nested {
        public String label;
        public Inner inner;

        public static class Inner {
            public Attachment data;
        }
    }

    public static class WithList {
        public List<Attachment> files;
    }

    public static class WithMap {
        public Map<String, Attachment> byName;
    }

    public static class InheritedBase {
        public Attachment baseFile;
    }

    public static class Inherited extends InheritedBase {
        public String extra;
    }

    public static class Duplicated {
        public Attachment left;
        public Attachment right;
    }

    public static class Plain {
        public String message;
        public int number;
    }

    private final Jackson2RootPartCodec codec = new Jackson2RootPartCodec(new ObjectMapper());

    /** Sequential collector with instance-identity awareness for assertions. */
    private static class TestCollector implements AttachmentCollector {
        final Map<Attachment, String> ids = new IdentityHashMap<>();
        final List<Attachment> registered = new ArrayList<>();

        @Override
        public String register(Attachment attachment) {
            return ids.computeIfAbsent(attachment, a -> {
                registered.add(a);
                return "att-" + registered.size();
            });
        }
    }

    private String writeRoot(Object payload, AttachmentCollector collector) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.writeRoot(payload, out, collector);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static ByteArrayInputStream json(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void serializesAttachmentAsIncludeStubExactShape() {
        Doc doc = new Doc();
        doc.title = "hello";
        doc.file = Attachment.of("x".getBytes(StandardCharsets.UTF_8));

        String jsonOut = writeRoot(doc, new TestCollector());

        assertEquals("{\"title\":\"hello\",\"file\":{\"Include\":{\"href\":\"cid:att-1\"}}}", jsonOut);
    }

    @Test
    void nullAttachmentSerializesAsJsonNullWithoutRegistration() {
        Doc doc = new Doc();
        doc.title = "empty";
        TestCollector collector = new TestCollector();

        String jsonOut = writeRoot(doc, collector);

        assertEquals("{\"title\":\"empty\",\"file\":null}", jsonOut);
        assertTrue(collector.registered.isEmpty());
    }

    @Test
    void discoveryHappensAtAnyDepthAndInCollections() {
        Nested nested = new Nested();
        nested.label = "n";
        nested.inner = new Nested.Inner();
        nested.inner.data = Attachment.of("a".getBytes(StandardCharsets.UTF_8));
        TestCollector collector = new TestCollector();
        String nestedJson = writeRoot(nested, collector);
        assertTrue(nestedJson.contains("\"data\":{\"Include\":{\"href\":\"cid:att-1\"}}"), nestedJson);

        WithList list = new WithList();
        list.files = List.of(Attachment.of("1".getBytes(StandardCharsets.UTF_8)),
                Attachment.of("2".getBytes(StandardCharsets.UTF_8)));
        TestCollector listCollector = new TestCollector();
        String listJson = writeRoot(list, listCollector);
        assertEquals(2, listCollector.registered.size());
        assertTrue(listJson.contains("cid:att-1") && listJson.contains("cid:att-2"), listJson);
    }

    @Test
    void duplicateInstanceSharesOneContentId() {
        Duplicated dup = new Duplicated();
        Attachment shared = Attachment.of("s".getBytes(StandardCharsets.UTF_8));
        dup.left = shared;
        dup.right = shared;
        TestCollector collector = new TestCollector();

        String jsonOut = writeRoot(dup, collector);

        assertEquals(1, collector.registered.size(), "one part per instance (FR-012)");
        assertEquals("{\"left\":{\"Include\":{\"href\":\"cid:att-1\"}},"
                + "\"right\":{\"Include\":{\"href\":\"cid:att-1\"}}}", jsonOut);
    }

    @Test
    void deserializesIncludeStubThroughResolverWithNormalizedId() {
        Attachment resolved = Attachment.of("r".getBytes(StandardCharsets.UTF_8));
        List<String> requestedIds = new ArrayList<>();

        Doc doc = (Doc) codec.readRoot(
                json("{\"title\":\"t\",\"file\":{\"Include\":{\"href\":\"cid:abc-123\"}}}"),
                ResolvableTypeInfo.of(Doc.class),
                id -> {
                    requestedIds.add(id);
                    return resolved;
                });

        assertEquals("t", doc.title);
        assertSame(resolved, doc.file);
        assertEquals(List.of("abc-123"), requestedIds, "href must be cid-stripped");
    }

    @Test
    void sameHrefResolvesToTheSharedInstanceTheResolverReturns() {
        Map<String, Attachment> instances = new HashMap<>();
        Duplicated dup = (Duplicated) codec.readRoot(
                json("{\"left\":{\"Include\":{\"href\":\"cid:one\"}},"
                        + "\"right\":{\"Include\":{\"href\":\"cid:one\"}}}"),
                ResolvableTypeInfo.of(Duplicated.class),
                id -> instances.computeIfAbsent(id,
                        i -> Attachment.of(i.getBytes(StandardCharsets.UTF_8))));

        assertSame(dup.left, dup.right, "duplicate references resolve to one instance");
    }

    @Test
    void nullFieldDeserializesToNull() {
        Doc doc = (Doc) codec.readRoot(json("{\"title\":\"t\",\"file\":null}"),
                ResolvableTypeInfo.of(Doc.class), id -> {
                    throw new AssertionError("resolver must not be called for null");
                });
        assertEquals("t", doc.title);
        assertNull(doc.file);
    }

    @Test
    void malformedStubFailsDescriptively() {
        var malformed = json("{\"title\":\"t\",\"file\":{\"NotInclude\":true}}");
        var docType = ResolvableTypeInfo.of(Doc.class);
        assertThrows(UncheckedIOException.class, () -> codec.readRoot(
                malformed, docType, id -> Attachment.of(new byte[0])));
    }

    @Test
    void serializingOutsideAnExchangeFailsWithGuidance() {
        Doc doc = new Doc();
        doc.file = Attachment.of("x".getBytes(StandardCharsets.UTF_8));
        ObjectMapper bare = new ObjectMapper().registerModule(new RestxopJackson2Module());
        Exception e = assertThrows(Exception.class, () -> bare.writeValueAsString(doc));
        assertTrue(e.getMessage().contains("restxop"), e.getMessage());
    }

    @Test
    void canHandleDetectsReachableAttachmentsAndCaches() {
        assertTrue(codec.canHandle(ResolvableTypeInfo.of(Doc.class)));
        assertTrue(codec.canHandle(ResolvableTypeInfo.of(Nested.class)), "nested");
        assertTrue(codec.canHandle(ResolvableTypeInfo.of(WithList.class)), "collection");
        assertTrue(codec.canHandle(ResolvableTypeInfo.of(WithMap.class)), "map values");
        assertTrue(codec.canHandle(ResolvableTypeInfo.of(Inherited.class)), "inherited field");
        assertTrue(codec.canHandle(ResolvableTypeInfo.of(Attachment.class)), "attachment itself");

        assertFalse(codec.canHandle(ResolvableTypeInfo.of(Plain.class)));
        assertFalse(codec.canHandle(ResolvableTypeInfo.of(String.class)));
        assertFalse(codec.canHandle(ResolvableTypeInfo.of(byte[].class)));

        // cached second call returns the same verdict
        assertTrue(codec.canHandle(ResolvableTypeInfo.of(Doc.class)));
        assertFalse(codec.canHandle(ResolvableTypeInfo.of(Plain.class)));
    }

    @Test
    void applicationMapperIsNotMutated() throws Exception {
        ObjectMapper appMapper = new ObjectMapper();
        new Jackson2RootPartCodec(appMapper);
        Doc doc = new Doc();
        doc.title = "t";
        // Without the restxop module, the app mapper fails on Attachment but
        // must not have been reconfigured behind the app's back
        doc.file = null;
        assertEquals("{\"title\":\"t\",\"file\":null}", appMapper.writeValueAsString(doc));
    }

    @Test
    void legacyModeCollectorYieldsBareHrefs() {
        Doc doc = new Doc();
        doc.title = "legacy";
        doc.file = Attachment.of("x".getBytes(StandardCharsets.UTF_8));

        final class BareCollector extends TestCollector
                implements dev.restxop.core.internal.write.ReferenceStyleAware {
            @Override
            public boolean bareReferences() {
                return true;
            }
        }

        String jsonOut = writeRoot(doc, new BareCollector());

        assertEquals("{\"title\":\"legacy\",\"file\":{\"Include\":{\"href\":\"att-1\"}}}", jsonOut);
    }
}
