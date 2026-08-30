<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Kafka Module

> **Status:** Alpha
> **Package:** `dev.vertique.kafka`
> **Artifact:** `vertique-kafka-core`
> **Depends on:** core, resilience, context, logging, deploy, services

`vertique-kafka-core` bridges Kafka topics to Vertique services. Declare a consumer with an
annotation or a builder and the framework subscribes to the topic, deserializes the value, and
dispatches it over the event bus to a service contract operation with framework context propagation
intact. Producing works the same way in reverse: annotate an interface and call typed methods.

The module is **format-neutral**. It ships no serializer or deserializer of its own — add
`dev.vertique:vertique-kafka-json` for JSON, `dev.vertique:vertique-kafka-avro` for Avro, or
contribute your own `KafkaSerdeProvider`. A consumer whose resolved format has no registered
provider fails at startup rather than at the first record.

---

## When To Use It

Install `vertique-kafka-core` when an application consumes from or produces to Kafka and wants
records routed into the same service-dispatch, resilience, and context-propagation model the rest of
the framework uses. If you only need a raw Kafka client, use `io.vertx:vertx-kafka-client` directly.

Always pair it with at least one format module:

| Need | Artifacts | `@Component` modules |
|---|---|---|
| Raw handlers or a custom format only | `vertique-kafka-core` | `KafkaModule` plus your provider module |
| JSON | `vertique-kafka-core`, `vertique-kafka-json` | `KafkaModule`, `KafkaJsonModule` |
| JSON and Avro | `vertique-kafka-core`, `vertique-kafka-json`, `vertique-kafka-avro` | `KafkaModule`, `KafkaJsonModule`, `AvroModule` |

---

## Core Concepts

### Four consumption models

Pick the lowest-coupling model that expresses the routing you need.

| Model | Declared with | Use when |
|---|---|---|
| 1 — Service source | `@KafkaSource` on a service impl method | One topic feeds one existing service operation |
| 2 — Declarative binding | `KafkaConsumerBinding` multibinding | You need full config control, a custom deserializer, or a pre-deserialization filter |
| 3 — Routing interface | `@KafkaListener` interface with `@KafkaHandler` methods | One topic routes to several service operations by header or JSON property |
| 4 — Custom handler | `@KafkaListener` on a `KafkaRecordHandler` class | You need arbitrary dispatch, transformation, or multi-service fan-out |

Whatever the model, config at `kafka.consumers.{name}.*` always wins over annotation and builder
values.

### Delivery semantics

Delivery is decided by the pair of `CommitStrategy` and `ErrorStrategy`. The commit strategy is the
one that determines whether a record can be lost.

| `CommitStrategy` | Semantics | Notes |
|---|---|---|
| `AUTO` (default) | At-most-once | Kafka auto-commits on an interval. A crash between commit and processing loses the record. Cannot be combined with `DEAD_LETTER` or `RETRY` |
| `MANUAL` | At-least-once | Offsets commit only after a successful dispatch. Records may be reprocessed but are never lost. Required by `DEAD_LETTER` and `RETRY` |

Handlers must therefore be idempotent under `MANUAL`.

### Record headers the framework reads and writes

| Header | Direction | Meaning |
|---|---|---|
| `x-correlation-id` | read on consume | Seeds the record's correlation id. A record without it gets a generated UUID. Bound to MDC as `kafka.correlationId` for the dispatch |
| `x-dlq-source-topic` | written on DLQ publish | Original topic |
| `x-dlq-source-partition` | written on DLQ publish | Original partition |
| `x-dlq-source-offset` | written on DLQ publish | Original offset |
| `x-dlq-consumer` | written on DLQ publish | Name of the consumer that failed |
| `x-dlq-error` | written on DLQ publish | Exception simple name, plus up to 200 characters of its message |

A dead-lettered record keeps its original key, its original value bytes, and all of its original
headers; the five `x-dlq-*` headers are added on top. An original header of the same name is
overwritten.

---

## Getting Started

```java
@Singleton
@Component(modules = {
    VertxModule.class, ConfigModule.class, DispatchModule.class,
    KafkaModule.class,      // core runtime
    KafkaJsonModule.class,  // JSON format provider, from vertique-kafka-json
    AppModule.class, ServiceModule.class
})
interface AppComponent {
    KafkaConsumerDeploymentManager kafkaConsumerDeploymentManager();
    KafkaProducerFactory kafkaProducerFactory();
}
```

Consumers are **not** deployed automatically — the application starts them:

```java
component.kafkaConsumerDeploymentManager().deployAll();
```

`deployAll()` registers local codecs, then deploys every enabled consumer verticle in parallel; if
any deployment fails the successfully-started consumers are rolled back and the returned future
fails with the original cause. `undeployAll()` is the counterpart. Consumer verticles are deployed
in the `SERVICES` lifecycle phase.

### Model 1 — `@KafkaSource`

Place it on a service implementation method. The registrar builds the binding and dispatches records
to that operation.

```java
public class OrderServiceImpl implements OrderService {

    @KafkaSource(topic = "order.created", groupId = "order-processing")
    @Override
    public Future<Void> processOrder(OrderCreatedEvent event) {
        return Future.succeededFuture();
    }
}
```

| Attribute | Default | Description |
|---|---|---|
| `name` | `""` — derived, see below | Config key used for overrides |
| `topic` | required | Topic to consume |
| `groupId` | required | Consumer group id |
| `errorStrategy` | `SKIP` | Failure handling |
| `commitStrategy` | `AUTO` | Offset commit timing |
| `deadLetterTopic` | `""` → `"{topic}.dlq"` | DLQ topic for `DEAD_LETTER` |

A derived name is `"{serviceName}-{operationId}"`, or `"{namespace}-{serviceName}-{operationId}"`
when the service declares a namespace, so same-named services in different namespaces do not
collide.

### Model 2 — `KafkaConsumerBinding`

```java
@Provides @IntoSet
static KafkaConsumerBinding<?> orderBinding() {
    return KafkaConsumerBinding.builder("order-created", OrderCreatedEvent.class)
            .topic("order.created")
            .groupId("order-processing")
            .dispatchTo(OrderService.class, "processOrder")
            .errorStrategy(ErrorStrategy.DEAD_LETTER)
            .commitStrategy(CommitStrategy.MANUAL)
            .filter(KafkaRecordFilter.headerEquals("event-type", "order.created"))
            .build();
}
```

| Builder method | Default | Description |
|---|---|---|
| `builder(String name, Class<V> valueType)` | required | Binding name and deserialization target |
| `topic(String)` | required | Topic |
| `groupId(String)` | required | Consumer group id |
| `dispatchTo(Class<?> service, String operation)` | required | Target service contract and operation |
| `errorStrategy(ErrorStrategy)` | `SKIP` | Failure handling |
| `commitStrategy(CommitStrategy)` | `AUTO` | Offset commit timing |
| `deadLetterTopic(String)` | `"{topic}.dlq"` | DLQ topic |
| `deserializer(KafkaDeserializer<V>)` | framework-built from the resolved format | Custom deserializer; bypasses format and profile resolution entirely |
| `filter(KafkaRecordFilter)` | `null` — accept all | Pre-deserialization filter on key and headers |
| `enabled(boolean)` | `true` | Deploy at startup |
| `eventBusTimeoutMs(long)` | `30000` | Event-bus dispatch timeout |
| `jsonProfile(JsonProfileId)` | `null` — framework default | JSON mapper profile for value deserialization |

Use the durable operation id (`@ServiceOperation` value) in `dispatchTo` for stable-target-eligible
operations so the binding survives a method rename.

### Model 3 — `@KafkaListener` routing interface

```java
@KafkaListener(name = "order-events", topic = "order.events", groupId = "order-processing")
public interface OrderEventRouter {

    @KafkaHandler(matchHeader = "event-type", matchValue = "order.created")
    @DispatchTo(service = OrderService.class, operation = "processOrder")
    void onOrderCreated(OrderCreatedEvent event);

    @KafkaHandler(matchHeader = "event-type", matchValue = "order.cancelled")
    @DispatchTo(service = OrderService.class, operation = "cancelOrder")
    void onOrderCancelled(OrderCancelledEvent event);

    @KafkaHandler(defaultHandler = true)
    void onUnmatched();
}
```

A routing interface contributes its **`Class<?>` literal**, not an instance:

```java
@Provides @IntoSet @KafkaConsumers
static Object orderEventRouter() {
    return OrderEventRouter.class;
}
```

| `@KafkaListener` attribute | Default | Description |
|---|---|---|
| `name` | required | Binding name used for config overrides |
| `topic` | required | Topic |
| `groupId` | required | Consumer group id |
| `valueType` | `Void.class` | Deserialization target for model 4; inferred per method in model 3 |
| `errorStrategy` | `SKIP` | Failure handling |
| `commitStrategy` | `AUTO` | Offset commit timing |
| `deadLetterTopic` | `""` → `"{topic}.dlq"` | DLQ topic |

| `@KafkaHandler` attribute | Default | Description |
|---|---|---|
| `matchHeader` | `""` | Header name to match; mutually exclusive with `matchProperty` and `defaultHandler` |
| `matchProperty` | `""` | JSON property name to match after deserialization |
| `matchValue` | `""` | Value the header or property must equal |
| `defaultHandler` | `false` | Catches unmatched records; at most one per listener |

| `@DispatchTo` attribute | Description |
|---|---|
| `service` | Target service contract interface |
| `operation` | Durable operation id |

Omitting `@DispatchTo` consumes the matched record without dispatching it.

### Model 4 — `@KafkaListener` on a `KafkaRecordHandler`

```java
@KafkaListener(name = "order-events", topic = "order.events",
               groupId = "order-processing", valueType = OrderEvent.class)
public class OrderEventHandler implements KafkaRecordHandler<OrderEvent> {

    private final OrderService orderService;
    private final InventoryService inventoryService;

    @Inject
    public OrderEventHandler(OrderService orderService, InventoryService inventoryService) {
        this.orderService = orderService;
        this.inventoryService = inventoryService;
    }

    @Override
    public Future<Void> handle(KafkaMessage<OrderEvent> message) {
        return switch (message.header("event-type").orElse("")) {
            case "order.created" -> orderService.processOrder(message.value());
            case "inventory.reserved" -> inventoryService.confirmReservation(message.value());
            default -> Future.succeededFuture();
        };
    }
}
```

```java
@Provides @IntoSet @KafkaConsumers
static Object orderEventHandler(OrderEventHandler handler) {
    return handler;
}
```

### Generated auto-wiring

Applications inheriting `vertique-app-parent` declare the Kafka runtime capability they use and get
the processor facade automatically; custom-parent applications follow the BOM plus
`vertique-codegen-all` recipe in `docs/packaging.md`. `dev.vertique:vertique-codegen-dagger` emits
`@Provides @IntoSet @KafkaConsumers Object` bindings for `@KafkaSource`-annotated classes (model 1)
and `@KafkaListener`-annotated **classes** (model 4); include `GeneratedKafkaConsumersModule` in the
`@Component`.

Model 3 routing **interfaces** are not auto-wired — their `Class<?>` contribution stays a manual
`@Provides @IntoSet @KafkaConsumers` binding.

---

## Key Classes

### `KafkaMessage<V>`

Immutable record handed to `KafkaRecordHandler.handle`.

```java
public record KafkaMessage<V>(
        V value, String key, String topic, int partition,
        long offset, long timestamp, Map<String, String> headers) {}
```

`key` may be `null`; `timestamp` is epoch milliseconds; `headers` is defensively copied and
immutable. `message.header("name")` returns `Optional<String>`.

### `KafkaRecordFilter`

Functional interface applied **before** deserialization, so a rejected record never pays the
deserialization cost. It sees the key and headers only.

```java
KafkaRecordFilter.headerEquals("event-type", "order.created")
KafkaRecordFilter.headerExists("x-correlation-id")
KafkaRecordFilter.headerMatches("version", Pattern.compile("v[12]"))
KafkaRecordFilter.headerIn("event-type", "order.created", "order.updated")
KafkaRecordFilter.allOf(filter1, filter2)
KafkaRecordFilter.anyOf(filter1, filter2)
```

A filtered record is committed under `MANUAL` and reported to capture hooks as `SKIP`.

### `KafkaProducerFactory`

Creates typed producer proxies and exposes raw sends.

| Method | Purpose |
|---|---|
| `create(Class<T> producerInterface)` | Builds the typed proxy for a `@KafkaProducer` interface |
| `send(String topic, String key, byte[] value, Map<String, String> headers)` | Raw send with pre-serialized bytes |
| `sendForDlq(...)` / `sendForOutbox(...)` | Raw sends tagged with the matching `KafkaSendOrigin` for capture hooks |
| `close()` | Closes the underlying producer |

### `KafkaConsumerDeploymentManager`

| Method | Description |
|---|---|
| `deployAll()` | Registers codecs, then deploys every enabled consumer verticle in parallel; rolls back on partial failure |
| `undeployAll()` | Undeploys every consumer verticle |

---

## Typed Producer Proxies

Annotate an interface with `@KafkaProducer`; every method needs a `@Topic`.

```java
@KafkaProducer
public interface OrderEvents {

    @Topic("order.created")
    Future<RecordMetadata> orderCreated(OrderCreatedEvent event);

    @Topic("order.created")
    Future<RecordMetadata> orderCreatedWithKey(String key, OrderCreatedEvent event);

    @Topic("order.created")
    Future<RecordMetadata> orderCreatedWithHeaders(OrderCreatedEvent event, Map<String, String> headers);
}
```

```java
@Provides @Singleton
static OrderEvents orderEvents(KafkaProducerFactory factory) {
    return factory.create(OrderEvents.class);
}
```

`@KafkaProducer.name()` defaults to `""`, which resolves to the interface's simple name and is the
key used for config overrides. Parameters are interpreted positionally:

| Parameters | Interpretation |
|---|---|
| `(V value)` | value only; key is `null` |
| `(String key, V value)` | key and value |
| `(V value, Map<String, String> headers)` | value and headers |
| `(String key, V value, Map<String, String> headers)` | key, value, and headers |

Topic, format, serde properties, and JSON mapper profile are overridable per method at
`kafka.producers.{producerName}.methods.{methodName}.*`.

To pick a JSON mapper profile, place `@JsonProfile("...")` at **type** level on the interface. It is
a separate annotation, not a `@KafkaProducer` attribute, and a method-level placement is rejected at
build time.

---

## Configuration

```json
{
  "kafka": {
    "bootstrap.servers": "localhost:9092",
    "security.protocol": "SASL_SSL",
    "sasl.mechanism": "PLAIN",
    "sasl.username": "user",
    "sasl.password": "secret",
    "format": "json",
    "jsonProfile": "cms",
    "properties": { "auto.offset.reset": "earliest" },
    "schemaRegistry": { "url": "http://schema-registry:8080/apis/registry/v3" },
    "messageKeyHash": { "hmacSecret": "…" },
    "consumers": {
      "order-created": {
        "topic": "order.created",
        "groupId": "order-processing",
        "enabled": true,
        "commitStrategy": "MANUAL",
        "errorStrategy": "DEAD_LETTER",
        "deadLetterTopic": "order.created.dlq",
        "eventBusTimeoutMs": 30000,
        "maxInFlight": 256,
        "instances": 2,
        "worker": false,
        "format": "avro",
        "jsonProfile": "cms",
        "retry": {
          "maxRetries": 3,
          "backoffMs": 1000,
          "backoffMultiplier": 2.0,
          "maxBackoffMs": 60000,
          "exhaustedStrategy": "DEAD_LETTER"
        },
        "serdeProperties": { "apicurio.registry.auto-register": false },
        "properties": { "max.poll.records": "100" }
      }
    },
    "producer": { "properties": { "acks": "all" } },
    "producers": {
      "OrderEvents": {
        "format": "avro",
        "jsonProfile": "cms",
        "methods": {
          "orderCreated": {
            "topic": "order.created.override",
            "jsonProfile": "strict",
            "serdeProperties": { "apicurio.registry.auto-register": false }
          }
        }
      }
    }
  }
}
```

### `kafka.consumers.{name}.*`

| Field | Default | Constraint / description |
|---|---|---|
| `topic` | annotation or builder value | Topic to consume |
| `groupId` | annotation or builder value | Consumer group id |
| `enabled` | `true` | Disabled consumers are not deployed |
| `commitStrategy` | annotation value (`AUTO`) | `AUTO` or `MANUAL` |
| `errorStrategy` | annotation value (`SKIP`) | `SKIP`, `DEAD_LETTER`, or `RETRY` |
| `deadLetterTopic` | `"{topic}.dlq"` | Used by `DEAD_LETTER` |
| `eventBusTimeoutMs` | `30000` | Must be `> 0` |
| `maxInFlight` | `256` | Must be `>= 1`. The consumer pauses at this many in-flight dispatches and resumes as the queue drains |
| `instances` | `1` | Must be `>= 1` |
| `worker` | unset | Event-loop threading unless a `mayBlock()` serde forces worker; see below |
| `format` | per-endpoint, else `kafka.format`, else auto-detect, else `json` | Any registered provider format |
| `jsonProfile` | unset | JSON mapper profile id for value deserialization |
| `serdeProperties` | — | Serde and registry config; never passed to the Kafka client |
| `properties` | — | Kafka-client property overrides for this consumer only |
| `retry.maxRetries` | `3` | Must be `>= 0` |
| `retry.backoffMs` | `1000` | Must be `>= 0` |
| `retry.backoffMultiplier` | `2.0` | Must be `>= 1.0` |
| `retry.maxBackoffMs` | `60000` | Must be `>= 0` |
| `retry.exhaustedStrategy` | `DEAD_LETTER` | Must not be `RETRY` |

A violated constraint fails startup with a `ConfigurationException` naming the exact config path.

### Kafka client property merge order

Later entries win:

1. root-level connection scalars — `bootstrap.servers`, `security.protocol`, `sasl.mechanism`,
   `sasl.jaas.config`;
2. global `kafka.properties`;
3. per-consumer `kafka.consumers.{name}.properties`.

`sasl.jaas.config` is constructed automatically when `sasl.username` and `sasl.password` are present
and `sasl.jaas.config` is absent.

When `bootstrap.servers` is not configured through any source, producer startup fails with an
`IllegalStateException` rather than silently falling back to `localhost:9092`.

**Properties files.** With Vert.x hierarchical expansion (`hierarchical=true`), dotted keys such as
`kafka.bootstrap.servers` expand into nested JSON. Both the flat and nested forms resolve.

### `serdeProperties` versus `properties`

`properties` is passed verbatim to the Vert.x Kafka client and must contain only Kafka-client keys.
Serde and schema-registry configuration lives in its own namespace:

| Path | Purpose |
|---|---|
| `kafka.schemaRegistry.*` | Global schema-registry config (URL, auth) |
| `kafka.consumers.{name}.serdeProperties.*` | Per-consumer serde overrides |
| `kafka.producers.{name}.serdeProperties.*` | Per-producer serde overrides |
| `kafka.producers.{name}.methods.{method}.serdeProperties.*` | Per-method serde overrides |

Merge order, most specific last: `kafka.schemaRegistry.*` → per-producer or per-consumer
`serdeProperties` → per-method `serdeProperties`.

### Secrets

Secret keys inside `properties`, `serdeProperties`, `schemaRegistry`, and the root connection
properties are masked when a config object is rendered, so a startup log or a `ConfigurationException`
never reveals a credential. `kafka.messageKeyHash.hmacSecret` is write-only — it deserializes in and
is never serialized back out.

### JSON mapper profile precedence

Consumers, highest first:

1. `kafka.consumers.{name}.jsonProfile`
2. `@JsonProfile("...")` at type level on the `@KafkaListener` type, or
   `KafkaConsumerBinding.Builder#jsonProfile(...)`
3. `kafka.jsonProfile`
4. `json.jsonProfile`
5. the `vertx` framework default, backed by `DatabindCodec.mapper()`

Producers, highest first:

1. `kafka.producers.{name}.methods.{method}.jsonProfile`
2. `kafka.producers.{name}.jsonProfile`
3. `@JsonProfile("...")` at type level on the `@KafkaProducer` type
4. `kafka.jsonProfile`
5. `json.jsonProfile`
6. the `vertx` framework default

A custom `KafkaDeserializer` set through `KafkaConsumerBinding.Builder#deserializer()` bypasses
profile selection entirely — the profile applies only when the framework builds the serde from a
`KafkaSerdeProvider`. A method-level `@JsonProfile` on a Kafka listener or producer type is rejected
at build time.

---

## Error Handling

### `ErrorStrategy`

| Value | Requires | Behavior |
|---|---|---|
| `SKIP` | any commit strategy | Log the error and commit, dropping the record |
| `DEAD_LETTER` | `MANUAL` | Publish the original bytes and headers to the DLQ topic, then commit. If the DLQ publish fails the offset is **not** committed and Kafka redelivers |
| `RETRY` | `MANUAL` | Pause the consumer for a backoff, then resume; Kafka redelivers the uncommitted record. After `maxRetries` redeliveries, fall back to `exhaustedStrategy` |

### How `RETRY` works

`RETRY` uses Kafka's native redelivery, not an application-level retry loop. On failure the offset is
not committed, the consumer pauses, and after the backoff it resumes and Kafka redelivers from the
uncommitted offset. The record then re-enters the full pipeline, including any service-level
`@Retry`, `@Timeout`, and `@CircuitBreaker` policies.

Retry delays use the shared `vertique-resilience` exponential backoff policy with zero jitter. The
configured `backoffMs`, `backoffMultiplier`, and `maxBackoffMs` therefore retain deterministic Kafka
timing while using the canonical retry-count validation and capped delay calculation.

```
Record B fails (attempt 1/3)
  → offset not committed
  → pause consumer, resume in 1s
  → Kafka redelivers B, full pipeline re-runs
  → fails again (2/3) → backoff 2s
  → fails again (3/3) → backoff 4s
  → fails again (exhausted):
      exhaustedStrategy = DEAD_LETTER → publish to DLQ, commit
      exhaustedStrategy = SKIP        → log, commit
```

Kafka `RETRY` and a service operation's `@Retry` compose multiplicatively. A service with
`@Retry(maxRetries = 3)` behind a consumer with `RETRY(maxRetries = 3)` gives a persistently failing
record up to twelve dispatch attempts before the exhausted strategy applies.

**`RETRY` blocks the partition.** No other record from that partition is delivered during the
backoff. That is appropriate for failures measured in seconds; for failures measured in hours use
`DEAD_LETTER` and reprocess from the DLQ.

### Poison-message guarantee

A record that always fails leaves the stream after `maxRetries` attempts. The longest it can block a
partition is:

```
sum(backoffMs × backoffMultiplier^i for i in 0..maxRetries-1), each term capped at maxBackoffMs
```

With the defaults (3 retries, 1s base, ×2, 60s cap) the worst case is 1s + 2s + 4s = 7 seconds.

`RetryConfig.DEFAULT` — `maxRetries = 3`, `backoffMs = 1000`, `backoffMultiplier = 2.0`,
`exhaustedStrategy = DEAD_LETTER`, `maxBackoffMs = 60000` — applies when `errorStrategy = RETRY` and
no `retry` block is configured.

---

## Value Formats

### `KafkaSerdeProvider`

The pluggable value-format SPI, contributed via `@IntoSet Set<KafkaSerdeProvider>`. `KafkaModule`
declares the multibinding but contributes no provider.

```java
public interface KafkaSerdeProvider {

    String format();                                        // e.g. "json", "avro"

    <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig);

    <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig);

    default boolean autoDetects(Class<?> type) { return false; }

    default boolean supports(Class<?> type) { return true; }

    /** {@code true} when this format's serdes may block (e.g. a remote schema registry). */
    default boolean mayBlock() { return false; }

    /** Property-based routing for model-3 routers. Throws UnsupportedOperationException by default. */
    default KafkaDeserializer<Object> routingDeserializer(JsonObject endpointConfig) { … }

    /** Extracts the match value for a property route. Throws UnsupportedOperationException by default. */
    default String matchValue(Object deserializedValue, String property) { … }

    /** Converts the routing value to the matched route's target type. */
    default <V> V convertRouted(Object routingValue, Class<V> type, JsonObject endpointConfig) { … }
}
```

`routingDeserializer` is built once per router consumer from the merged endpoint config and is
type-agnostic, so the format decodes the record before the route is chosen. `convertRouted` then
turns that value into the matched route's type; the default implementation reuses it when it is
already an instance of the target type and throws `DeserializationException` otherwise. A format
that decodes an intermediate representation while routing should override it — the JSON provider
does, calling `treeToValue` on the already-parsed tree so there is no second parse.

```java
public interface KafkaSerializer<V> {
    byte[] serialize(V value, String topic, Map<String, String> headers);
    default boolean mayBlock() { return false; }
    default void close() {}
}

public interface KafkaDeserializer<V> {
    V deserialize(byte[] data, String topic, Map<String, String> headers) throws DeserializationException;
    default boolean mayBlock() { return false; }
    default void close() {}
}
```

Both carry `topic` and `headers` because schema-registry-backed formats need them for subject
naming.

### Format precedence

First match wins:

1. the endpoint's own `format` — consumer or producer-method config;
2. global `kafka.format`;
3. auto-detection — each registered provider's `autoDetects(valueType)` in registration order;
4. `"json"`, the terminal fallback key.

`"json"` is an ordinary provider key, not a built-in. Resolving to it still requires a registered
provider, so a format with no provider fails startup with an `IllegalArgumentException` naming the
missing module.

### Worker threading for blocking formats

When the effective deserializer's `mayBlock()` is `true`:

- the consumer verticle is forced to worker threading;
- an explicit `worker = false` is **rejected at startup**;
- an absent `worker` field is silently forced to `true`.

This contract, not a timeout, is what keeps a remote registry call off the event loop.
`KafkaSerdeRegistry` wraps every provider-built serde so `mayBlock()` delegates to the provider — a
provider cannot forget to stamp it.

### When a provider is required

An endpoint needs a registered provider for its resolved format whenever the framework must build a
serde or routing artifact — including for `"json"`. Paths that build nothing need no provider.

| Path | Provider required |
|---|---|
| Enabled binding or handler with a framework-built deserializer | Yes |
| Disabled binding or handler with a declared `valueType` | Yes |
| Router with any property route or a non-`Void` payload route | Yes |
| All-`Void` property router (the selector is still read) | Yes |
| Model-2 binding with a custom `deserializer` | No |
| Model-4 raw `KafkaRecordHandler` | No |
| Non-property `Void` or no-payload binding or handler | No |
| Header- or default-only all-`Void` router | No |

### Adding a format

```java
@Provides @Singleton @IntoSet
static KafkaSerdeProvider myFormatProvider(@VertxConfig JsonObject config) {
    return new MyFormatSerdeProvider(config);
}
```

See `dev.vertique:vertique-kafka-avro` for a complete reference implementation.

---

## Extension Points

### `KafkaConsumerInterceptor` (multibinding)

`KafkaConsumerInterceptor extends OrderedExtension` and is sorted by `OrderedExtension.comparator()`
— phase, then ascending priority, then `orderKey()` (the FQCN by default). Lower priority runs
first.

Sync observers are fire-and-forget; a thrown exception is logged and swallowed, and the dispatch
outcome is unaffected.

| Callback | When |
|---|---|
| `void onRecord(KafkaDispatchContext<?> ctx)` | After deserialization, before `beforeDispatch` |
| `void onSuccess(KafkaDispatchContext<?> ctx)` | After a successful dispatch |
| `void onError(KafkaDispatchContext<?> ctx, Throwable error)` | On any dispatch failure |

Async handlers can change the outcome.

| Callback | Failure behavior |
|---|---|
| `Future<KafkaDispatchContext<?>> beforeDispatch(KafkaDispatchContext<?> ctx)` | A failed future short-circuits the dispatch. **Return the context** — this is how `withFiltered(true)` and added attributes propagate |
| `Future<Void> afterDispatch(KafkaDispatchContext<?> ctx)` | Failures are logged, not propagated |
| `Future<Void> recoverError(KafkaDispatchContext<?> ctx, Throwable error)` | A succeeded future means handled — the record is committed. A failed future means proceed with the configured error strategy |

```java
@Override
public Future<Void> recoverError(KafkaDispatchContext<?> ctx, Throwable error) {
    if (error instanceof BeanValidationException) {
        return Future.succeededFuture();   // handled — commit the record
    }
    return Future.failedFuture(error);     // unhandled — apply SKIP / DEAD_LETTER / RETRY
}
```

Interceptors are offered `recoverError` in extension order and the first succeeded future wins;
later interceptors are not invoked.

`KafkaDispatchContext<V>` is an immutable record — `(consumerName, topic, partition, offset, key,
value, rawEvidence, headers, timestamp, retryCount, filtered, attributes)` — with copy-on-write
`withFiltered(boolean)` and `withAttribute(String, Object)`. `retryCount` is 0-based and counts
Kafka-native redeliveries, so a retry-topic routing decision in `recoverError` can read it.

### `KafkaConsumerCaptureHook` (multibinding)

Observer-only boundary hooks, ordered by `OrderedExtension`. They fire **after** the irrevocable
disposition decision and can never change commit, retry, or delivery; a thrown exception is
swallowed.

```java
public interface KafkaConsumerCaptureHook extends OrderedExtension {
    default void onTerminalOutcome(KafkaDispatchContext<?> ctx, KafkaTerminalOutcome outcome) {}
    default void onPreDispatchTerminalOutcome(
            KafkaRawRecordDisposition disposition, KafkaTerminalOutcome outcome) {}
}
```

`onTerminalOutcome` fires exactly once per dispatched record, at the terminal point after all async
work — DLQ publish, seek, recovery. `onPreDispatchTerminalOutcome` covers records that never reach
dispatch, such as a filtered record, and receives `KafkaRawRecordDisposition` —
`(consumerName, topic, partition, offset, key, headers, rawEvidence, timestamp, retryCount)` —
instead of a dispatch context.

| `KafkaTerminalOutcome` | Meaning |
|---|---|
| `SUCCESS` | The handler completed successfully |
| `SKIP` | Skipped per `ErrorStrategy.SKIP`, or filtered pre-dispatch |
| `RECOVERED` | An interceptor's `recoverError` handled the error |
| `RETRY_SCHEDULED` | Consumer paused, offset not committed, Kafka will redeliver |
| `DLQ_PUBLISHED` | Forwarded to the dead-letter topic |
| `DLQ_FAILED` | The DLQ publish failed; the offset was not committed |
| `ERROR_HANDLER_FAILED` | The error handler's own future failed; the record's disposition is unknown |

`KafkaDispatchContext.rawEvidence()` and `KafkaRawRecordDisposition.rawEvidence()` return a
`PayloadSource` — a **no-copy** buffered view of the record bytes. A hook must copy before retaining
it across threads.

### `KafkaProducerCaptureHook` (multibinding)

Fires exactly once per send, through the shared wire funnel in `KafkaProducerFactory`, after
`producer.send(record)` settles. Also `extends OrderedExtension` and observer-only.

| Parameter | Notes |
|---|---|
| `origin` | `KafkaSendOrigin` — `DIRECT_PRODUCER`, `OUTBOX`, `DLQ`, or `INTERNAL` |
| `topic` | Target topic |
| `key` | Record key, or `null` |
| `value` | No-copy `PayloadSource` over the serialized wire bytes |
| `headers` | Fully merged wire headers |
| `producerMethod` | The `@KafkaProducer` interface method; non-`null` only for `DIRECT_PRODUCER` |
| `result` | The settled `AsyncResult<RecordMetadata>` |

`producerMethod` is the only way to reach method-level annotations on the direct-producer path.

### Other multibindings

| Multibinding | Qualifier | Contributes |
|---|---|---|
| `Set<KafkaConsumerBinding<?>>` | — | Model-2 declarative bindings |
| `Set<Object>` | `@KafkaConsumers` | Model-3 router `Class<?>` literals and model-4 handler instances |
| `Set<KafkaConsumerInterceptor>` | — | Consumer pipeline interceptors |
| `Set<KafkaConsumerCaptureHook>` | — | Consumer boundary capture hooks |
| `Set<KafkaProducerCaptureHook>` | — | Producer boundary capture hooks |
| `Set<KafkaSerdeProvider>` | — | Value-format providers |

---

## Module Dagger Bindings

`KafkaModule` transitively includes `ContextRuntimeModule`, `LoggingContextModule`, `DispatchModule`,
`DeployerModule`, and `HealthCheckModule`.

| Type | Scope | Description |
|---|---|---|
| `KafkaConfig` | `@Singleton` | Typed `kafka` config parsed at the module boundary |
| `KafkaConsumerRegistry` | `@Singleton` | Registry built from every consumer source, validated at startup |
| `KafkaConsumerDeploymentManager` | `@Singleton` | Deploys and undeploys consumer verticles |
| `KafkaProducerFactory` | `@Singleton` | Typed producer proxies and raw sends |
| `HealthCheck` (`@IntoSet`) | — | `KafkaConsumerHealthCheck`, contributed to the readiness set |

---

## Failures, Constraints, and Common Mistakes

### Startup failures

| Failure | Cause |
|---|---|
| `ConfigurationException` | A consumer config bound is violated — an unknown `commitStrategy`, `errorStrategy`, or `retry.exhaustedStrategy`, `eventBusTimeoutMs <= 0`, `maxInFlight < 1`, `instances < 1`, `maxRetries < 0`, `backoffMs < 0`, `backoffMultiplier < 1.0`, `maxBackoffMs < 0`. Enum failures identify the invalid type and value; other validation messages name the exact config path |
| `KafkaRegistrationException` | An invalid consumer declaration, or a generated binding companion that is present but malformed. A `ConfigurationException` subtype — broken generated code is never silently skipped |
| `IllegalArgumentException` | The resolved format has no registered `KafkaSerdeProvider`; the message names the missing module |
| `IllegalStateException` | `worker = false` on a consumer whose effective deserializer reports `mayBlock()` |
| `IllegalStateException` | `bootstrap.servers` missing for a producer |

### Runtime failures

| Failure | Meaning |
|---|---|
| `DeserializationException` | The value could not be decoded. A `TechnicalException` subtype; it enters the configured error strategy like any other dispatch failure |
| Event-bus timeout | Dispatch exceeded `eventBusTimeoutMs`; treated as a dispatch failure |

### Common mistakes

- **`AUTO` commit with `DEAD_LETTER` or `RETRY`.** Both strategies depend on withholding the commit;
  `AUTO` has already committed. Use `MANUAL`.
- **Non-idempotent handlers under `MANUAL`.** At-least-once means redelivery is normal, not
  exceptional.
- **Using `RETRY` for a long outage.** The retrying partition is blocked for the whole backoff. Use
  `DEAD_LETTER` and reprocess.
- **Dropping the context returned by `beforeDispatch`.** `KafkaDispatchContext` is immutable;
  `withFiltered(true)` has no effect unless the returned future carries the new instance.
- **Retaining a `PayloadSource` across threads.** It is a no-copy view of the record buffer — copy
  first.
- **Contributing a model-3 router instance.** A routing interface contributes its `Class<?>` literal;
  models 1 and 4 contribute instances.
- **Putting serde settings in `properties`.** That bag goes verbatim to the Kafka client. Serde and
  registry settings belong in `serdeProperties` and `kafka.schemaRegistry`.
- **Expecting consumers to start themselves.** Nothing calls `deployAll()` for you.
- **Installing `KafkaModule` alone.** With no format provider, any consumer needing a serde fails at
  startup.

---

## Dependencies

| Artifact | Scope | Purpose |
|---|---|---|
| `dev.vertique:vertique-core` | compile | DI wiring, typed config parsing, `HealthCheck` SPI, exception roots |
| `dev.vertique:vertique-context` | compile | Dispatch-context propagation across the event bus |
| `dev.vertique:vertique-logging` | compile | MDC binding for the record's correlation id |
| `dev.vertique:vertique-services` | compile | Service contract registry and target resolution for dispatch |
| `dev.vertique:vertique-deploy` | compile | Verticle deployment and lifecycle phases |
| `io.vertx:vertx-kafka-client` | compile | Vert.x Kafka consumer and producer |
| `com.fasterxml.jackson.core:jackson-annotations` | compile | Annotations only, for the typed config records |

`vertique-kafka-core` carries **no** Jackson databind, Avro, or Apicurio dependency at compile
scope — actual value serialization lives in the format modules. `vertique-kafka-json` brings
`jackson-databind`; `vertique-kafka-avro` brings `org.apache.avro:avro` and the Apicurio Avro serde.
