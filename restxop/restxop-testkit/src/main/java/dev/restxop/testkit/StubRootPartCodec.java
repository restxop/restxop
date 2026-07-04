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

import dev.restxop.Attachment;
import dev.restxop.spi.AttachmentCollector;
import dev.restxop.spi.AttachmentResolver;
import dev.restxop.spi.ResolvableTypeInfo;
import dev.restxop.spi.RootPartCodec;
import dev.restxop.testkit.model.BundlePayload;
import dev.restxop.testkit.model.ReportPayload;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal JSON-shaped codec for the testkit payload models — lets protocol
 * behavior (framing, failure, lifecycle) be exercised without any JSON
 * library. Wire behavior under test never depends on the codec; the real
 * codecs are proven separately by the conformance suite.
 */
public class StubRootPartCodec implements RootPartCodec {

    /** Marker payload whose serialization fails midway (write-path injection). */
    public static final class FailingPayload {
    }

    private static final Pattern TITLE = Pattern.compile("\"title\":\"([^\"]*)\"");
    private static final Pattern NAME = Pattern.compile("\"name\":\"([^\"]*)\"");
    private static final Pattern HREFS = Pattern.compile("\"href\":\"cid:([^\"]+)\"");

    @Override
    public boolean canHandle(ResolvableTypeInfo type) {
        Class<?> raw = type.rawClass();
        return raw == ReportPayload.class || raw == BundlePayload.class
                || raw == FailingPayload.class;
    }

    @Override
    public void writeRoot(Object payload, OutputStream out, AttachmentCollector collector) {
        try {
            if (payload instanceof FailingPayload) {
                out.write("{\"title\":\"about to".getBytes(StandardCharsets.UTF_8));
                throw new IllegalStateException("injected serialization failure mid-root");
            }
            if (payload instanceof ReportPayload report) {
                out.write(("{\"title\":\"" + report.title + "\",\"report\":" + stub(report.report,
                        collector) + "}").getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (payload instanceof BundlePayload bundle) {
                out.write(("{\"name\":\"" + bundle.name + "\",\"first\":"
                        + stub(bundle.first, collector) + ",\"second\":"
                        + stub(bundle.second, collector) + "}").getBytes(StandardCharsets.UTF_8));
                return;
            }
            throw new IllegalArgumentException("unsupported payload " + payload.getClass());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String stub(Attachment attachment, AttachmentCollector collector) {
        return attachment == null ? "null"
                : "{\"Include\":{\"href\":\"cid:" + collector.register(attachment) + "\"}}";
    }

    @Override
    public Object readRoot(InputStream in, ResolvableTypeInfo type, AttachmentResolver resolver) {
        String json;
        try {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Matcher hrefs = HREFS.matcher(json);
        if (type.rawClass() == BundlePayload.class) {
            BundlePayload bundle = new BundlePayload(group(NAME, json), null, null);
            if (hrefs.find()) {
                bundle.first = resolver.resolve(hrefs.group(1));
            }
            if (hrefs.find()) {
                bundle.second = resolver.resolve(hrefs.group(1));
            }
            return bundle;
        }
        ReportPayload report = new ReportPayload(group(TITLE, json), null);
        if (hrefs.find()) {
            report.report = resolver.resolve(hrefs.group(1));
        }
        return report;
    }

    private static String group(Pattern pattern, String json) {
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
