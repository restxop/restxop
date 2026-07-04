# Contract: restxop Public API Surface v1

**Status**: Normative for module boundaries, public types, and
configuration. Signatures shown indicative; javadoc governs final form.

## Modules & coordinates (group `dev.restxop`)

| Artifact | Depends on | Public surface |
|---|---|---|
| `restxop-core` | slf4j-api | `Attachment` + factories, error hierarchy, `RestxopConfig`, SPIs (`RootPartCodec`, `SpoolStorage`, `ExchangeListener`), protocol engine (internal packages) |
| `restxop-jackson2` | core, jackson-databind 2.x | `Jackson2RootPartCodec`; Jackson 2 module registering Attachment (de)serializers |
| `restxop-jackson3` | core, jackson (tools.jackson) 3.x | `Jackson3RootPartCodec`; Jackson 3 module equivalent |
| `restxop-spring-boot-3-starter` | core, jackson2, spring-boot 3.2+ | auto-config, `RestxopHttpMessageConverter`, RestTemplate/RestClient customizers, optional Feign support |
| `restxop-spring-boot-4-starter` | core, jackson3, spring-boot 4.0+ | same surface on Framework 7 |
| `restxop-testkit` | core, junit-jupiter | wire fixtures, abstract conformance suite (test scope for adopters too) |

Internal packages (`dev.restxop.core.internal.*`) carry no compatibility
guarantee and are excluded from javadoc.

## `Attachment` (core)

```java
public interface Attachment {
    InputStream contentStream();          // single sequential consumption
    Optional<String> filename();
    Optional<String> contentType();
    OptionalLong contentLength();         // advisory

    static Attachment of(Path path);
    static Attachment of(File file);
    static Attachment of(byte[] bytes);
    static Attachment of(InputStream in);                 // unknown length
    static Attachment of(InputStream in, String filename, String contentType);
    // builder for metadata overrides: Attachment.builder(source)...
}
```

Activation interop (`jakarta.activation` optional dependency):
`AttachmentAdapters.fromDataHandler(DataHandler)`,
`AttachmentAdapters.toDataSource(Attachment)`.

Read-side semantics: `contentStream()` blocks per FR-016/FR-020; throws
the error hierarchy of data-model.md; is idempotent (same stream instance);
`close()` before/without full consumption is legal and triggers skip.

## SPIs (core)

```java
public interface RootPartCodec {
    boolean canHandle(ResolvableTypeInfo type);   // cached introspection: reachable Attachment?
    void writeRoot(Object payload, OutputStream out, AttachmentCollector collector);
    Object readRoot(InputStream in, ResolvableTypeInfo type, AttachmentResolver resolver);
}
public interface SpoolStorage {                    // Clarification #3
    OverflowStore createOverflow(String exchangeId, String contentId, RestxopConfig config);
}   // supplies chase-buffer overflow storage; default: owner-only temp files
public interface ExchangeListener {                // FR-033; all default methods
    default void exchangeStarted(ExchangeInfo info) {}
    default void payloadDelivered(ExchangeInfo info) {}
    default void attachmentConsumed(ExchangeInfo info, AttachmentInfo att) {}
    default void bytesSpooled(ExchangeInfo info, AttachmentInfo att, long totalSpooled) {}
    default void exchangeFailed(ExchangeInfo info, Throwable cause) {}
    default void exchangeClosed(ExchangeInfo info) {}
}
```

Listener exceptions are caught and logged, never propagated (FR-033).

## Spring starters

**Server**: auto-configured `RestxopHttpMessageConverter` (an
`HttpMessageConverter`) registered with MVC; controllers use plain
`@RequestBody`/return values on payload types containing `Attachment`.
Media-type gate: `multipart/related` (+ `composite/related` in compat
mode). Multipart-resolver guard per research R6
(`restxop.strict-multipart-resolution`, default true).

**Client**:
- `RestTemplate`: `RestxopRestTemplateCustomizer` installs the converter
  and deferred-close response handling (FR-024) — no user subclassing.
- `RestClient`: `RestxopRestClientCustomizer` equivalent.
- OpenFeign (optional, `@ConditionalOnClass(feign.Feign.class)`):
  `RestxopFeignDecoder` wrapping SpringDecoder with deferred close.

Deferred-close contract: the HTTP response is released as soon as the
drain reaches end-of-message (wire speed, independent of consumer pace —
research R1), or when the exchange fails, or when the exchange TTL reaper
fires — whichever first. Consumers keep reading from chase buffers after
release.

## Configuration properties (prefix `restxop.`)

| Property | Default | Constraint |
|---|---|---|
| `memory-window-per-part` | `256KB` | > 0, ≤ max-per-attachment |
| `drain.pool-size` | `32` | > 0; caller-runs fallback on saturation |
| `spool.directory` | system temp | must exist & be writable (fail fast at startup) |
| `spool.max-per-attachment` | `1GB` | ≥ threshold |
| `spool.max-per-message` | `2GB` | ≥ max-per-attachment |
| `limits.max-root-part-bytes` | `16MB` | > 0 |
| `limits.max-part-header-bytes` | `64KB` | > 0 |
| `limits.max-parts` | `1000` | > 0 |
| `timeouts.exchange-ttl` | `10m` | > 0 |
| `timeouts.read-wait` | `60s` | > 0, ≤ exchange-ttl |
| `read-buffer-size` | `8KB` | ≥ 1KB |
| `strict-multipart-resolution` | `true` | starter only |
| `legacy-compat.enabled` | `false` | starter + core behavior toggle |

Timeout mapping note: awaiting the root part is bounded by
`timeouts.exchange-ttl` plus the transport's read timeouts;
`timeouts.read-wait` governs chase-buffer awaits (FR-020).

Auto-configuration registration:
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
in each starter. Beans of type `ExchangeListener` and `SpoolStorage` in
the application context are picked up automatically.

## Logging (FR-033)

SLF4J, logger namespace `dev.restxop.*`: exchange started/closed (DEBUG),
failed (ERROR with cause), spool activation & cap breach (INFO/WARN),
skipped unreferenced part (WARN), timeout (WARN). Log messages include the
exchange id; never payload or attachment content.

## Compatibility promises

- SemVer on all public (non-`internal`) packages from 1.0.0.
- Wire format v1 (wire-format.md) frozen; changes = new `type` parameter
  semantics + spec amendment.
- The testkit conformance suite is part of the public contract: adopters
  extending `RestxopConformanceSuite` against custom SPI implementations
  is a supported use.
