<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Kafka JSON Module

> **Status:** Alpha
> **Package:** `dev.vertique.kafka.json`
> **Artifact:** `vertique-kafka-json`
> **Depends on:** kafka-core

Opt-in Jackson JSON value-format provider for `vertique-kafka`. JSON is the default/reference format for Kafka producers and consumers but is not built into the core runtime — applications include this module (and `KafkaJsonModule`) to activate the `"json"` format. Symmetric with `vertique-kafka-avro`: both are first-class `KafkaSerdeProvider` implementations under the `vertique-kafka` family aggregator.

---

## When To Use It

Add this module when:

- Producers or consumers exchange JSON-encoded payloads (POJOs, records, or any type that Jackson can serialize).
- The application uses the default `"json"` format — explicitly or by omitting a `format` config key, which falls back to `"json"`.
- JSON is needed alongside another format (e.g. `vertique-kafka-avro`) in the same application — both format modules can be included simultaneously.

Do not use when only non-JSON payloads are needed (e.g. Avro-only), or when a fully custom `KafkaSerdeProvider` covers the required format.

---

## Core Concepts

### Opt-in by module composition

`vertique-kafka-json` contributes a single `KafkaSerdeProvider` into the `Set<KafkaSerdeProvider>` multibinding declared by `KafkaModule`. Including `KafkaJsonModule` in the Dagger component is the only activation step. The core runtime (`vertique-kafka-core`) has no Jackson dependency and no awareness of JSON formatting; without `KafkaJsonModule`, any consumer or producer that resolves to `"json"` fails fast at startup with an actionable error naming the missing module.

### ObjectMapper resolution via profile registry

The backing `ObjectMapper` is resolved per endpoint with a three-tier precedence:

1. **Explicit bag selection** — a **non-blank** `jsonProfile` in the merged serde-config bag (set by the per-binding / `kafka.jsonProfile` resolution in `vertique-kafka-core`) wins outright and stops resolution. An explicit `"vertx"` selects the framework's shared `DatabindCodec.mapper()` (the mapper Vert.x uses internally) and **does not** fall through to the global default; any other id is looked up in the injected `JsonMapperProfileRegistry` from `vertique-json` (an unknown **bag** id ⇒ `JsonProfileConfigurationException` at deserializer-build time, fail-fast — distinct from the global `json.jsonProfile`, which is pre-resolved and fails fast at provider construction / startup).
2. **Global `json.jsonProfile`** — when the bag carries no explicit selection, the global default from `JsonConfig` applies (pre-resolved once at construction to avoid a per-record allocation).
3. **`vertx` floor** — otherwise `DatabindCodec.mapper()`.

`JsonMapperProfileRegistry` and `JsonConfig` are available because `KafkaJsonModule` includes `JsonRuntimeModule`. The global-tier mapper is computed once in the `JsonSerdeProvider` constructor (an unknown `json.jsonProfile` fails fast at startup; the per-binding `kafka.jsonProfile` is additionally validated by a `ComposeValidator`).

The per-binding profile (tier 1) is selected by placing `@JsonProfile("profile-id")` (`dev.vertique.core.json.JsonProfile`) on the `@KafkaListener` or `@KafkaProducer` **type**. Placing it on a method instead of the type is rejected at build time (FR-JSON-066). When the annotation is absent, resolution falls through to the global and `vertx`-floor tiers above.

### Property-route routing without a second byte-parse

For Model-3 router consumers that use `matchProperty` routing, `JsonSerdeProvider` overrides all three routing hooks so the dispatcher stays format-agnostic:

1. `routingDeserializer(cfg)` parses the wire bytes into a `JsonNode` tree once per record.
2. `matchValue(node, property)` reads the discriminator from the already-parsed tree via `path(property).asText()`.
3. `convertRouted(node, type, cfg)` maps the pre-parsed tree to the matched route's target type via `treeToValue` — no second wire-byte parse.

### Non-blocking

`JsonSerdeProvider.mayBlock()` returns `false`. JSON serialization and deserialization execute on the Vert.x event loop without offloading, unless a custom provider overrides this.

### Value-free deserialization failure messages

`JsonSerdeProvider` intentionally omits the offending record value from `DeserializationException` messages. Jackson exception messages can embed scalar values from the untrusted wire record, which must not leak into operator logs or dead-letter queue headers. The Jackson cause is preserved as `DeserializationException.getCause()` for stacktrace debugging.

---

## Key Classes

### `JsonSerdeProvider`

`KafkaSerdeProvider` implementation for the `"json"` format. Constructed with an injected `JsonMapperProfileRegistry` (resolves named profiles to `ObjectMapper` instances) and `JsonConfig` (the global `json.jsonProfile` default, pre-resolved once at construction).

```java
public final class JsonSerdeProvider implements KafkaSerdeProvider {

    /**
     * Creates a provider that resolves named JSON mapper profiles from the registry and applies the
     * global {@code json.jsonProfile} default (from {@link JsonConfig}) when the serde bag carries no
     * explicit id. A blank/absent bag id falls to the global default then the {@code vertx} floor;
     * an explicit {@code "vertx"} resolves to {@link DatabindCodec#mapper()} and stops.
     */
    public JsonSerdeProvider(JsonMapperProfileRegistry registry, JsonConfig jsonConfig) { ... }

    /** Returns {@code "json"}. */
    @Override public String format() { return "json"; }

    /** Returns {@code false} — JSON is the default fallback and is never auto-detected. */
    @Override public boolean autoDetects(Class<?> type) { return false; }

    /** Returns {@code false} — JSON serialization does not block. */
    @Override public boolean mayBlock() { return false; }

    /**
     * Returns a new {@link JacksonKafkaSerializer} backed by the {@code ObjectMapper}
     * resolved from the bag's {@code jsonProfile} key.
     */
    @Override
    public <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig) { ... }

    /**
     * Returns a new {@link JacksonKafkaDeserializer} for {@code type} backed by the
     * {@code ObjectMapper} resolved from the bag's {@code jsonProfile} key.
     */
    @Override
    public <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig) { ... }

    /**
     * Parses wire bytes into a {@link JsonNode} tree (once per record) for property-route
     * discrimination, using the profile-resolved {@code ObjectMapper}.
     * Returns {@code null} when the input bytes are {@code null}.
     */
    @Override
    public KafkaDeserializer<Object> routingDeserializer(JsonObject endpointConfig) { ... }

    /**
     * Reads the discriminator field from a {@link JsonNode} via {@code path(property).asText()}.
     * Returns {@code null} when {@code deserializedValue} is not a {@link JsonNode}.
     */
    @Override
    public String matchValue(Object deserializedValue, String property) { ... }

    /**
     * Converts a pre-parsed {@link JsonNode} to the route's target type via
     * {@code resolvedMapper.treeToValue(node, type)}, reusing the already-parsed tree
     * without a second wire-byte parse.
     */
    @Override
    public <V> V convertRouted(Object routingValue, Class<V> type, JsonObject endpointConfig) { ... }
}
```

#### Invariants and Gotchas

- `autoDetects` is always `false` — JSON is the terminal fallback, not an auto-detected format. If no format is configured and no other provider's `autoDetects` fires, the registry resolves to `"json"` and requires `JsonSerdeProvider` to be registered.
- `matchValue` returns `null` (not an empty string) when the routing value is not a `JsonNode` (e.g. when a non-JSON provider's `routingDeserializer` was mistakenly used). The dispatcher treats a `null` match as "no route matched".
- `convertRouted` throws `DeserializationException` — not `null` — when `treeToValue` fails. The failure message is intentionally value-free (the offending record content is not interpolated into the message); the Jackson cause is preserved as `getCause()` for stacktrace debugging. The failure propagates through the configured `ErrorStrategy`.
- An unknown `jsonProfile` id throws `JsonProfileConfigurationException` at deserializer-build time (startup or first endpoint use), not per-record.

---

### `JacksonKafkaSerializer`

Jackson JSON serializer used by `JsonSerdeProvider`. Delegates to `DatabindCodec.mapper()` by default; accepts a custom `ObjectMapper` for testing or specialized use.

```java
public class JacksonKafkaSerializer<V> implements KafkaSerializer<V> {

    /** Creates a serializer using the framework's shared {@code DatabindCodec.mapper()}. */
    public JacksonKafkaSerializer() { ... }

    /** Creates a serializer with a custom ObjectMapper. */
    public JacksonKafkaSerializer(ObjectMapper mapper) { ... }

    @Override
    public byte[] serialize(V value, String topic, Map<String, String> headers) { ... }
}
```

---

### `JacksonKafkaDeserializer`

Jackson JSON deserializer used by `JsonSerdeProvider`. Deserializes to a caller-supplied target type using `DatabindCodec.mapper()` by default; accepts a custom `ObjectMapper`.

```java
public class JacksonKafkaDeserializer<V> implements KafkaDeserializer<V> {

    /** Creates a deserializer for {@code type} using {@code DatabindCodec.mapper()}. */
    public JacksonKafkaDeserializer(Class<V> type) { ... }

    /** Creates a deserializer for {@code type} with a custom ObjectMapper. */
    public JacksonKafkaDeserializer(Class<V> type, ObjectMapper mapper) { ... }

    @Override
    public V deserialize(byte[] data, String topic, Map<String, String> headers)
            throws DeserializationException { ... }
}
```

---

### `KafkaDefaultProfileValidator`

`@Singleton ComposeValidator` contributed by `KafkaJsonModule` to the `VALIDATE`-phase multibinding. Validates the Kafka per-boundary default profile id (`kafka.jsonProfile`) against the `JsonMapperProfileRegistry` at `@Inject` construction time.

A non-blank `kafka.jsonProfile` that names an unknown profile throws `JsonProfileConfigurationException` immediately at the `VALIDATE` phase — before any consumer or producer is started — even when zero bindings are active or when a more-specific per-binding profile would shadow the boundary default at runtime. This closes the lazy-resolution gap for inert or shadowed configured defaults (FR-JSON-050).

---

### `KafkaJsonModule`

Dagger `@Module` that contributes `JsonSerdeProvider` into the `Set<KafkaSerdeProvider>` multibinding and includes `JsonRuntimeModule` so the `JsonMapperProfileRegistry` binding is available.

```java
@Module(includes = JsonRuntimeModule.class)
public abstract class KafkaJsonModule {

    @Provides
    @Singleton
    @IntoSet
    static KafkaSerdeProvider jsonSerdeProvider(JsonMapperProfileRegistry registry, JsonConfig jsonConfig) {
        return new JsonSerdeProvider(registry, jsonConfig);
    }

    @Provides
    @Singleton
    @IntoSet
    static ComposeValidator kafkaDefaultProfileValidator(KafkaDefaultProfileValidator impl) {
        return impl;
    }
}
```

Include alongside `KafkaModule` in the application `@Component`:

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    ConfigModule.class,
    KafkaModule.class,
    KafkaJsonModule.class,   // enables "json" format
    AppModule.class,
    ServiceModule.class
})
interface AppComponent { ... }
```

To also enable Avro, add `AvroModule`:

```java
@Component(modules = {
    KafkaModule.class,
    KafkaJsonModule.class,
    AvroModule.class,        // enables "avro" format alongside json
    ...
})
interface AppComponent { ... }
```

---

## Extension Points

This module is itself an extension of the `KafkaSerdeProvider` SPI declared in `vertique-kafka-core`. There are no additional extension points declared here.

The JSON provider is the reference implementation of the SPI. To implement a custom format provider, depend only on `vertique-kafka-core` (which declares the SPI), implement `KafkaSerdeProvider`, and contribute it via `@Provides @Singleton @IntoSet` in a Dagger module — no dependency on `vertique-kafka-json` is required or appropriate.

---

## Dependencies

| Artifact | Scope | Purpose |
|----------|-------|---------|
| `dev.vertique:vertique-kafka-core` | compile | `KafkaSerdeProvider` SPI, `KafkaSerializer`, `KafkaDeserializer`, `DeserializationException` |
| `dev.vertique:vertique-json` | compile | `JsonRuntimeModule`, `JsonConfig` |
| `dev.vertique:vertique-core` | compile | `JsonMapperProfileRegistry`, `JsonProfileId` (package `dev.vertique.core.json`) |
| `com.fasterxml.jackson.core:jackson-databind` | compile | `ObjectMapper`, `JsonNode`, `treeToValue` |
| `io.vertx:vertx-core` | compile | `DatabindCodec.mapper()` (the shared ObjectMapper) |
