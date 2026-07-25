<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Kafka Module

> **Status:** Alpha
> **Package:** `dev.vertique.kafka`
> **Artifact:** `vertique-kafka-core`
> **Depends on:** core, deploy, services

Kafka consumer bridge to event bus services with declarative listener registration, typed producer proxies, configurable error and commit strategies, and a consumer interceptor SPI. Consumed records are dispatched via the event bus to service contract operations, maintaining framework context propagation patterns.

---

## Package Layout

| Package | Contents |
|---------|----------|
| `dev.vertique.kafka` | `@KafkaSource`, `@KafkaListener`, `@KafkaHandler`, `@DispatchTo`, `@KafkaConsumers`; `KafkaConsumerBinding`, `ResolvedKafkaConsumerConfig`, `KafkaConsumerDeploymentManager`, `KafkaConsumerRegistrar`, `KafkaConsumerRegistry`, `KafkaConsumerVerticle`; `KafkaMessage`, `KafkaRecordFilter`, `KafkaRecordHandler`; `CommitStrategy`, `ErrorStrategy`, `RetryConfig`; `ConsumerEntry`, `DeserializationException`, `KafkaRegistrationException`; `KafkaConfigHelper`; `KafkaModule` |
| `dev.vertique.kafka.config` | Typed config records parsed at the `KafkaModule` boundary: `KafkaConfig`, `KafkaConsumerConfig`, `KafkaConsumerRetryConfig`, `KafkaProducerConfig`, `KafkaProducerMethodConfig`, `KafkaMessageKeyHashConfig` |
| `dev.vertique.kafka.interceptor` | `KafkaConsumerInterceptor`, `KafkaDispatchContext` |
| `dev.vertique.kafka.producer` | `@KafkaProducer`, `@Topic`, `KafkaProducerFactory` |
| `dev.vertique.kafka.serialization` | `KafkaDeserializer`, `KafkaSerializer`, `KafkaSerdeProvider`, `KafkaSerdeRegistry` |
| `dev.vertique.kafka.health` | `KafkaConsumerHealthCheck` |

---

## Consumption Models

The module supports four progressive consumption models. Choose the one matching your coupling level:

| Model | Annotation | When to use |
|-------|-----------|-------------|
| **1 — Service source** | `@KafkaSource` on impl method | Existing service operation, single topic, simple dispatch |
| **2 — Declarative binding** | `KafkaConsumerBinding` multibinding | Full config control, custom deserializer, pre-deserialization filter |
| **3 — Routing interface** | `@KafkaListener` + `@KafkaHandler` on interface | One topic, multiple routing rules to different service operations |
| **4 — Custom handler** | `@KafkaListener` + `KafkaRecordHandler` impl | Full control: multi-service dispatch, transformation, complex logic |

### Model 1 — `@KafkaSource`

Place on a service implementation method. The registrar creates a consumer binding automatically and dispatches records to the annotated operation.

```java
public class OrderServiceImpl implements OrderService {

    @KafkaSource(topic = "order.created", groupId = "order-processing")
    @Override
    public Future<Void> processOrder(OrderCreatedEvent event) {
        // receives deserialized event from Kafka
        return Future.succeededFuture();
    }
}
```

The binding name defaults to `"{serviceName}-{operationId}"` when the service has no namespace, or `"{namespace}-{serviceName}-{operationId}"` when a namespace is present (avoids collisions for same-name services under different namespaces). All fields can be overridden in config at `kafka.consumers.{name}.*`.

| Attribute | Default | Description |
|-----------|---------|-------------|
| `name` | `"{serviceName}-{operationName}"` | Config key for overrides |
| `topic` | — (required) | Kafka topic to consume |
| `groupId` | — (required) | Consumer group ID |
| `errorStrategy` | `SKIP` | What to do on dispatch failure |
| `commitStrategy` | `AUTO` | When to commit offsets |
| `deadLetterTopic` | `"{topic}.dlq"` | DLQ topic for `DEAD_LETTER` strategy |

### Model 2 — `KafkaConsumerBinding`

Contribute via Dagger multibinding for full programmatic control.

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
|----------------|---------|-------------|
| `topic(String)` | — (required) | Kafka topic |
| `groupId(String)` | — (required) | Consumer group ID |
| `dispatchTo(Class, String)` | — (required) | Target service + operation |
| `errorStrategy(ErrorStrategy)` | `SKIP` | Failure handling |
| `commitStrategy(CommitStrategy)` | `AUTO` | Offset commit timing |
| `deadLetterTopic(String)` | `"{topic}.dlq"` | DLQ topic name |
| `deserializer(KafkaDeserializer)` | framework-resolved (from format provider) | Custom deserializer |
| `filter(KafkaRecordFilter)` | `null` (accept-all) | Pre-deserialization filter |
| `enabled(boolean)` | `true` | Enable/disable at startup |
| `eventBusTimeoutMs(long)` | `30000` ms | Event bus dispatch timeout |
| `jsonProfile(JsonProfileId)` | `null` (framework default) | JSON mapper profile for value deserialization; `null` selects the `vertx` profile backed by `DatabindCodec.mapper()`. Bypassed when a custom `deserializer` instance is set (FR-JSON-037). |

### Model 3 — `@KafkaListener` Routing Interface

Declare an interface with `@KafkaHandler` methods for header- or property-based routing to different service operations.

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

Contribute via Dagger multibinding:

```java
@Provides @IntoSet @KafkaConsumers
static Object orderEventRouter(OrderEventRouterImpl impl) {
    return impl;
}
```

**`@KafkaListener` attributes (selection-relevant):**

| Attribute | Default | Description |
|-----------|---------|-------------|
| `name` | — (required) | Binding name for config reference |
| `topic` | — (required) | Kafka topic to consume |
| `groupId` | — (required) | Consumer group ID |
| `valueType` | `Void.class` | Deserialization target type (Model 4); inferred per-method in Model 3 |
| `errorStrategy` | `SKIP` | Error handling strategy |
| `commitStrategy` | `AUTO` | Offset commit strategy |
| `deadLetterTopic` | `"{topic}.dlq"` | DLQ topic for `DEAD_LETTER` strategy |

To select a JSON mapper profile for value deserialization, place `@JsonProfile("...")` at TYPE level on the `@KafkaListener` interface or class. This is not a `@KafkaListener` annotation attribute — it is a separate annotation resolved by the framework. Overridden by `kafka.consumers.<name>.jsonProfile` config. Bypassed when a `KafkaConsumerBinding.Builder#deserializer` instance is set (FR-JSON-037). A method-level `@JsonProfile` on a `@KafkaListener` type is rejected at build time (FR-JSON-066; see ADR-0138).

**`@KafkaHandler` attributes:**

| Attribute | Description |
|-----------|-------------|
| `matchHeader` | Header name to match (mutually exclusive with `matchProperty` / `defaultHandler`) |
| `matchProperty` | JSON property name to match post-deserialization |
| `matchValue` | Value to match against the header or property |
| `defaultHandler` | Catches all unmatched records; at most one per listener |

**`@DispatchTo` attributes:**

| Attribute | Description |
|-----------|-------------|
| `service` | Target service contract interface |
| `operation` | Durable operation ID (`@ServiceOperation` value) — use the stable id for operations that are stable-target-eligible, so the resolved `stableTargetId` on `RouteEntry` is durable across refactors |

If `@DispatchTo` is omitted, the matched record is consumed but not dispatched (skip behavior).

### Model 4 — `@KafkaListener` + `KafkaRecordHandler`

Implement `KafkaRecordHandler<V>` and annotate the class with `@KafkaListener` for full custom dispatch logic.

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

Contribute via Dagger multibinding:

```java
@Provides @IntoSet @KafkaConsumers
static Object orderEventHandler(OrderEventHandler handler) {
    return handler;
}
```

---

## Key Classes

### `KafkaMessage<V>`

Immutable record passed to `KafkaRecordHandler.handle()`. Carries the deserialized value and all record metadata.

| Field | Type | Description |
|-------|------|-------------|
| `value` | `V` | Deserialized message value |
| `key` | `String` | Message key, or `null` |
| `topic` | `String` | Source topic |
| `partition` | `int` | Source partition |
| `offset` | `long` | Message offset |
| `timestamp` | `long` | Epoch milliseconds |
| `headers` | `Map<String, String>` | Immutable header map |

Convenience: `message.header("name")` returns `Optional<String>`.

### `KafkaRecordFilter`

Functional interface for pre-deserialization filtering based on key and headers. Built-in factory methods:

```java
KafkaRecordFilter.headerEquals("event-type", "order.created")
KafkaRecordFilter.headerExists("x-correlation-id")
KafkaRecordFilter.headerMatches("version", Pattern.compile("v[12]"))
KafkaRecordFilter.headerIn("event-type", "order.created", "order.updated")
KafkaRecordFilter.allOf(filter1, filter2)
KafkaRecordFilter.anyOf(filter1, filter2)
```

### `KafkaBindingMeta`

Public record in `dev.vertique.kafka` carrying precomputed, compile-time-knowable Kafka consumer binding metadata. Emitted by the `vertique-codegen-kafka` annotation processor into `{Consumer}_BindingMeta` companions.

`KafkaBindingMeta` holds static data only: name, topic, groupId, `Kind`, value type (`Class<?>`), error/commit strategy, dead-letter topic, `targetOperation` (`SOURCE` only — the impl method name that keys the `ServiceMethodMeta` lookup; there is **no** top-level `targetService` field), and route metadata (`RouteMeta` list for `ROUTER` bindings, each route carrying its own `targetService` + `targetOperation`). It deliberately does **not** carry resolved event-bus address, stable target id, or one-way flag — those require the live `ServiceContractRegistry` / `ServiceTargetResolver` and are resolved at boot.

`Kind` discriminates the three codegen-supported consumption models:

| Kind | Model | Maps to |
|------|-------|---------|
| `SOURCE` | Model 1 (`@KafkaSource` on impl method) | `ConsumerEntry.Kind.BINDING` |
| `ROUTER` | Model 3 (`@KafkaListener` routing interface) | `ConsumerEntry.Kind.ROUTER` |
| `HANDLER` | Model 4 (`@KafkaListener` + `KafkaRecordHandler`) | `ConsumerEntry.Kind.HANDLER` |

Invariants enforced by the compact constructor: `ROUTER` requires non-empty routes and null `valueType`; `SOURCE` and `HANDLER` require empty routes.

`KafkaBindingMeta` is public **API** — constructor and `Kind` changes are breaking. `GeneratedBindingMetaLoader` throws `KafkaRegistrationException` on a shape mismatch rather than silently falling back. See ADR-0072.

### `KafkaConsumerRegistry`

Built at startup from all registered bindings and handlers. Resolves annotation defaults against config overrides and validates all consumer definitions. `ServiceTargetResolver` is the source of truth for all service-targeted bindings: `@KafkaSource`, `KafkaConsumerBinding.dispatchTo(...)`, and `@DispatchTo` routes are each resolved through `ServiceTargetResolver` during registry construction, not per-record dispatch.

```java
// Built by KafkaModule — not instantiated directly
KafkaConsumerRegistry registry = KafkaConsumerRegistry.build(bindings, handlers, serviceRegistry, config);
```

`ConsumerEntry` and `RouteEntry` each carry a `stableTargetId` field populated at startup. This field is the stable service target ID (`{type}.{serviceName}.{operationId}` or `{serviceName}.{operationId}`) derived from the service contract metadata. Runtime dispatch uses the resolved event bus address on the entry — the stable target ID is stored for observability and outbox integration, not for routing.

### `GeneratedBindingMetaLoader`

Package-private in `dev.vertique.kafka`. Invoked inside `KafkaConsumerScanner` at both `scanKafkaSources` (Model 1) and `scanListeners` (Models 3/4) before the reflective scan path.

`load(Class<?> consumerClass)` calls `Class.forName` for the `{Consumer}_BindingMeta` companion (located via `GeneratedNames.companionFqn(consumerClass, "_BindingMeta")`). It returns `null` on `ClassNotFoundException` — the scanner falls through to reflective scanning. If the companion is present but malformed (missing `METAS` field, wrong type, inaccessible), it throws `KafkaRegistrationException` immediately: broken generated code is never silently skipped.

Three conversion helpers mirror the scanner's own resolution logic: `toSourceEntry` (Model 1), `toRouterEntry` (Model 3), `toHandlerEntry` (Model 4). All three use `ServiceTargetResolver` and `KafkaConsumerValidation.validateAndBuild`, producing identical `ConsumerEntry` output to the reflective path.

The generated path eliminates reflective annotation reads and `KafkaParamClassifier` generic resolution; runtime target resolution via `ServiceTargetResolver` still runs. The processor that populates companion classes is `vertique-codegen-kafka`; see `dev.vertique:vertique-codegen-kafka`.

### `KafkaConsumerDeploymentManager`

Deploys all enabled consumer verticles in parallel. On partial failure, successfully deployed consumers are rolled back. Follows the same pattern as `ServiceDeploymentManager`.

```java
// In a startup verticle or lifecycle step:
component.kafkaConsumerDeploymentManager().deployAll()
    .compose(v -> manager.deployPhase(LifecyclePhase.EDGE));
```

| Method | Description |
|--------|-------------|
| `deployAll()` | Registers codecs, deploys all enabled consumer verticles; rolls back on partial failure |
| `undeployAll()` | Undeploys all consumer verticles |

### `KafkaConsumerVerticle`

One verticle per enabled `ConsumerEntry`. Creates the Vert.x Kafka consumer, subscribes to the topic, applies the interceptor pipeline, deserializes records, and dispatches via `KafkaRecordDispatcher` to the target service operation.

**Dispatch routing in `KafkaRecordDispatcher`:**
- Service targets with a stable target id (`stableTargetId != null`): dispatched via `ServiceRequestSender` — includes supervisor check and service-specific error enrichment.
- Service targets without a stable target id (e.g., contributor entries): dispatched via `ServiceRequestSender.send(String, DispatchEnvelope, long)` — address-based, no supervisor, raw transport exceptions.
- Fire-and-forget sends (non-service): dispatched via `EventBusClient.send()`.

Backpressure: the consumer is paused when in-flight dispatches reach `maxInFlight` and resumed when the queue drains below threshold.

---

## Typed Config Layer (`dev.vertique.kafka.config`)

The `kafka` config section is parsed into a hierarchy of typed records at the `KafkaModule` boundary via `KafkaConfig.fromConfig(JsonObject)`. Module internals depend only on these records — never the raw `JsonObject`.

### `KafkaConfig`

Root typed config for the `kafka` section. Holds the global `format`, open `properties` bag, `schemaRegistry` block, the `consumers` list (keyed by `name`), the `producers` list (keyed by `name`), and the neutral `messageKeyHash` block. Two additional fields are extracted outside the structured section: `connectionProperties` (loose root-level Kafka client-connection scalars such as `bootstrap.servers`, `security.protocol`, `sasl.*`) and `producerProperties` (the global producer bag at `kafka.producer.properties`). Secret keys in `properties`, `schemaRegistry`, `connectionProperties`, and `producerProperties` are scrubbed when rendered by `toString()`.

### `KafkaConsumerConfig`

External typed per-consumer config record parsed from `kafka.consumers.{name}`. The `name` identity is injected from the keyed-object key during parsing. Fields `worker`, `format`, and `jsonProfile` are nullable — `null` means absent/inherit, not a concrete default. The `properties` and `serdeProperties` bags are open property bags whose secret keys are scrubbed in `toString()`.

`jsonProfile` (`String`, nullable): the JSON mapper profile id for value deserialization. `null` or blank selects the `vertx` profile backed by `DatabindCodec.mapper()`. When present, the resolved id is threaded into the `serdeConfig` `JsonObject` bag passed to `KafkaSerdeProvider.deserializer()`; the JSON provider reads it to resolve the backing `ObjectMapper` from `JsonMapperProfileRegistry`. A custom `KafkaDeserializer` instance on the binding bypasses the profile entirely (FR-JSON-037).

### `KafkaConsumerRetryConfig`

Typed retry config parsed from `kafka.consumers.{name}.retry`. All fields are non-null after construction: `maxRetries=3`, `backoffMs=1000`, `backoffMultiplier=2.0`, `maxBackoffMs=60000`, `exhaustedStrategy="DEAD_LETTER"`.

### `KafkaProducerConfig`

External typed per-producer config record parsed from `kafka.producers.{name}`. The `name` identity is injected from the keyed-object key. The `methods` list (keyed by method name) holds the per-method overrides nested under `methods.{method}`.

`jsonProfile` (`String`, nullable): the JSON mapper profile id for value serialization. `null` or blank selects the `vertx` profile backed by `DatabindCodec.mapper()`. Overridden by a non-blank `jsonProfile` on the individual method config; overrides the `@JsonProfile` annotation on the type.

### `KafkaProducerMethodConfig`

Typed per-method producer config parsed from `kafka.producers.{name}.methods.{method}`. The `method` identity is injected from the keyed-object key. Carries optional `topic`, `format`, `serdeProperties`, and `jsonProfile` overrides for the method. Secret keys in `serdeProperties` are scrubbed in `toString()`.

`jsonProfile` (`String`, nullable): the highest-priority profile source for a producer method. When non-blank, it overrides both the producer-level config and the `@JsonProfile` annotation on the type.

### `KafkaMessageKeyHashConfig`

Typed message-key hash config parsed from `kafka.messageKeyHash`. Carries the optional HMAC secret at `kafka.messageKeyHash.hmacSecret`. The secret is annotated `@JsonProperty(access = WRITE_ONLY)` — never serialized back out — and is redacted in `toString()`.

### `ResolvedKafkaConsumerConfig` (internal runtime VO)

Package-private runtime value-object in `dev.vertique.kafka` (not in the `config` sub-package). Produced by `ResolvedKafkaConsumerConfig.resolve(...)` from annotation defaults merged with the external `KafkaConsumerConfig`. This is the type that the consumer verticle and validation pipeline work against — never the external parsed record. Carries the tri-state `workerConfigured` field (`true` = explicitly set, `false` = explicitly false, `null` = absent) used to distinguish a forced-worker upgrade from a rejected `worker=false` when `mayBlock()` serdes are selected.

---

## Configuration

Config path: `kafka.consumers.{name}.*`. Config always wins over annotation defaults.

```json
{
  "kafka": {
    "bootstrap.servers": "localhost:9092",
    "security.protocol": "SASL_SSL",
    "sasl.mechanism": "PLAIN",
    "sasl.username": "user",
    "sasl.password": "secret",
    "properties": {
      "auto.offset.reset": "earliest"
    },
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
        "retry": {
          "maxRetries": 3,
          "backoffMs": 1000,
          "backoffMultiplier": 2.0,
          "maxBackoffMs": 60000,
          "exhaustedStrategy": "DEAD_LETTER"
        },
        "format": "avro",
        "jsonProfile": "cms",
        "serdeProperties": {
          "apicurio.registry.auto-register": false
        },
        "properties": {
          "max.poll.records": "100"
        }
      }
    },
    "format": "json",
    "schemaRegistry": {
      "url": "http://schema-registry:8080/apis/registry/v3"
    },
    "producer": {
      "properties": {
        "acks": "all"
      }
    },
    "producers": {
      "OrderEvents": {
        "format": "avro",
        "jsonProfile": "cms",
        "methods": {
          "orderCreated": {
            "topic": "order.created.override",
            "jsonProfile": "strict",
            "serdeProperties": {
              "apicurio.registry.auto-register": false
            }
          }
        }
      }
    }
  }
}
```

**Properties file compatibility:** When using `.properties` files with Vert.x's hierarchical expansion (`hierarchical=true`), dotted Kafka keys like `kafka.bootstrap.servers` are automatically expanded into nested JSON. The framework resolves both flat and nested forms transparently via `KafkaConfigHelper`.

**Producer fail-fast:** If `bootstrap.servers` is not configured for a Kafka producer (via any config source), startup fails immediately with an `IllegalStateException` instead of silently falling back to `localhost:9092`.

**Consumer config fields:**

| Field | Default | Description |
|-------|---------|-------------|
| `topic` | annotation value | Kafka topic to consume |
| `groupId` | annotation value | Consumer group ID |
| `enabled` | `true` | Enable/disable this consumer |
| `commitStrategy` | annotation value | `AUTO` or `MANUAL` |
| `errorStrategy` | annotation value | `SKIP`, `DEAD_LETTER`, or `RETRY` |
| `deadLetterTopic` | `"{topic}.dlq"` | DLQ topic for `DEAD_LETTER` |
| `eventBusTimeoutMs` | `30000` | Event bus dispatch timeout (ms) |
| `maxInFlight` | `256` | Max concurrent dispatches before pausing |
| `instances` | `1` | Number of verticle instances |
| `worker` | `false` | Use worker threading model; forced to `true` when effective deserializer `mayBlock()` is `true`; `false` rejected at startup in that case |
| `format` | auto-detect or `json` | Value format: `"avro"`, `"json"`, or any registered provider format |
| `serdeProperties` | — | Serde/registry config for the selected format (e.g. `apicurio.registry.*`); never passed to the Kafka client |
| `jsonProfile` | `null` (framework default) | JSON mapper profile id for value deserialization. Absent or blank selects the `vertx` profile backed by `DatabindCodec.mapper()`. Ignored when a custom `KafkaDeserializer` instance is set on the binding (FR-JSON-037). See [Profile Selection Precedence](#json-mapper-profile-selection-precedence). |
| `retry.*` | see `RetryConfig` | Retry config (active when `errorStrategy=RETRY`) |
| `properties` | — | Per-consumer Kafka native property overrides (Kafka-client keys only) |

**Kafka property merge order (highest priority last):**

1. Top-level keys: `bootstrap.servers`, `security.protocol`, `sasl.mechanism`, `sasl.jaas.config`
2. Global `kafka.properties`
3. Per-consumer `kafka.consumers.{name}.properties`

SASL JAAS config is auto-constructed when `sasl.username` + `sasl.password` are present and `sasl.jaas.config` is absent.

---

## Error Handling

### `CommitStrategy`

| Value | Delivery Semantics | Description |
|-------|-------------------|-------------|
| `AUTO` | At-most-once | Kafka auto-commits offsets at regular intervals. Messages may be lost if the consumer crashes between commit and processing. Cannot be combined with `DEAD_LETTER` or `RETRY` error strategies. |
| `MANUAL` | At-least-once | Offsets committed only after successful dispatch. Messages may be reprocessed on consumer restart but are never lost. Required for `DEAD_LETTER` and `RETRY` error strategies. |

### `ErrorStrategy`

| Value | Requires | Description |
|-------|----------|-------------|
| `SKIP` | Any commit strategy | Log the error and commit the offset, skipping the record |
| `DEAD_LETTER` | `MANUAL` commit | Publish the original record bytes to the DLQ topic, then commit. If DLQ publish fails, the offset is NOT committed — Kafka will redeliver. |
| `RETRY` | `MANUAL` commit | Pause the consumer for a backoff period, then resume. Kafka redelivers the uncommitted record on the next poll. After `maxRetries` redeliveries, falls back to `exhaustedStrategy`. |

### How Retry Works (Kafka-Native Redelivery)

The `RETRY` strategy uses Kafka's native redelivery mechanism rather than application-level retry. When a record fails:

1. The offset is **not committed** — Kafka will redeliver from this offset
2. The consumer is **paused** to prevent immediate re-poll
3. After the backoff delay, the consumer **resumes** and Kafka redelivers the record
4. The record goes through the normal processing pipeline again, including any service-level resilience policies (`@Retry`, `@Timeout`, `@CircuitBreaker`)

```
Record B fails (attempt 1/3)
  → Do NOT commit B's offset
  → Pause consumer, schedule resume in 1s
  → Timer fires → resume consumer
  → Kafka polls → redelivers B from uncommitted offset
  → B goes through processRecord → dispatchRecord → service pipeline
  → If B fails again (attempt 2/3) → pause, backoff 2s, resume, redeliver
  → If B fails again (attempt 3/3) → pause, backoff 4s, resume, redeliver
  → If B fails again (attempt 4/3 = exhausted):
      exhaustedStrategy = DEAD_LETTER → publish to DLQ, commit → B removed
      exhaustedStrategy = SKIP → log, commit → B removed
```

**Interaction with service-level resilience:**

The Kafka consumer's RETRY and the service operation's `@Retry` work at different levels:

- **Service `@Retry`**: Handles transient failures *within a single dispatch attempt* (e.g., database timeout, network glitch). Retries happen immediately within the same event bus request.
- **Kafka RETRY**: Handles broader failures where *re-dispatch later may succeed* (e.g., the target service was temporarily unavailable). Each Kafka retry is a full new dispatch through the event bus.

If a service operation has `@Retry(maxRetries=3)` and the Kafka consumer has `RETRY(maxRetries=3)`, a persistently failing record gets up to 3 service retries × 3 Kafka retries = 12 total attempts before reaching the exhausted strategy.

**Important: RETRY blocks the partition.** During the backoff period, no new records are delivered from the retrying partition. For short transient failures (seconds to minutes), this is appropriate. For long-lived failures (hours), use `DEAD_LETTER` instead and reprocess from the DLQ once the issue is resolved.

This approach is equivalent to Spring Kafka's `DefaultErrorHandler` with `FixedBackOff`/`ExponentialBackOff` — a blocking retry that pauses the consumer during backoff.

### Poison Message Guarantee

A poison message (one that always fails regardless of retries) is guaranteed to be removed from the stream after `maxRetries` attempts. The maximum time a poison message can block a partition is:

```
sum(backoffMs × backoffMultiplier^i for i in 0..maxRetries-1), capped at maxBackoffMs per attempt
```

With defaults (3 retries, 1s base, 2x multiplier, 60s cap): worst case = 1s + 2s + 4s = 7 seconds.

### `RetryConfig`

| Field | Default | Description |
|-------|---------|-------------|
| `maxRetries` | `3` | Maximum Kafka redelivery attempts before applying exhausted strategy |
| `backoffMs` | `1000` | Initial backoff delay in milliseconds |
| `backoffMultiplier` | `2.0` | Multiplier applied per retry (exponential backoff) |
| `maxBackoffMs` | `60000` | Upper cap on any single backoff delay |
| `exhaustedStrategy` | `DEAD_LETTER` | Strategy when all retries fail; must not be `RETRY` |

`RetryConfig.DEFAULT` applies when `errorStrategy=RETRY` and no `retry` block is present in config.

---

## Value Formats

### `KafkaSerdeProvider` SPI

`KafkaSerdeProvider` (in `dev.vertique.kafka.serialization`, module `vertique-kafka-core`) is the pluggable value-format SPI. Providers are contributed via `@IntoSet Set<KafkaSerdeProvider>` multibinding declared by `KafkaModule`. The registry has **no built-in format**: `KafkaModule` declares the multibinding but contributes no provider. An empty provider set means no format is available — any consumer whose resolved format requires a serde fails fast at startup with an actionable error (e.g. *"no KafkaSerdeProvider registered for format 'json'; add vertique-kafka-json … to your application"*). JSON is provided by `JsonSerdeProvider` in the `vertique-kafka-json` module, contributed by `KafkaJsonModule`.

```java
public interface KafkaSerdeProvider {
    String format();                                            // e.g. "json", "avro"
    default boolean autoDetects(Class<?> type) { return false; }
    default boolean supports(Class<?> type)    { return true;  }
    default boolean mayBlock() { return false; }               // true ⇒ serdes may block (e.g. Schema Registry)
    <V> KafkaSerializer<V> serializer(Class<V> type, JsonObject endpointConfig);
    <V> KafkaDeserializer<V> deserializer(Class<V> type, JsonObject endpointConfig);
    // Optional: property-based routing for Model-3 routers.
    // routingDeserializer is built once per router consumer from the merged endpointConfig;
    // it is type-agnostic so the format resolves the record before the route is chosen.
    default KafkaDeserializer<Object> routingDeserializer(JsonObject endpointConfig) { ... }
    default String matchValue(Object deserializedValue, String property) { ... }
    // Converts the routing-deserializer's output to the matched route's target type,
    // reusing the already-decoded value when possible.
    default <V> V convertRouted(Object routingValue, Class<V> type, JsonObject endpointConfig) { ... }
}
```

`KafkaSerializer<V>` carries the topic and headers (`serialize(V value, String topic, Map<String,String> headers)`), required for subject naming in schema-registry-backed formats. The default `mayBlock()` is `false`; providers whose serdes may call a remote registry on a cache miss override it to `true`.

**`convertRouted(routingValue, type, endpointConfig)`** — converts the value produced by `routingDeserializer` into the matched route's concrete type. The default implementation reuses `routingValue` when it is already an instance of `type` (avoiding a second wire-byte parse), and throws `DeserializationException` when it is not. Format providers that decode an intermediate representation during routing (e.g. JSON parses bytes into a `JsonNode` tree) override this to convert the intermediate to the target type. The `JsonSerdeProvider` override calls `mapper.treeToValue(node, type)`, reusing the parsed tree at zero re-parse cost.

### `KafkaSerdeRegistry`

`@Singleton` selector that resolves and builds serdes. The registry wraps every provider-built serde so the wrapper's `mayBlock()` delegates to the provider — a provider cannot forget to stamp it.

**Format precedence (first match wins):**

1. Per-endpoint `format` key in consumer/producer-method config
2. Global `kafka.format`
3. Auto-detect: each registered provider's `autoDetects(valueType)` is consulted in registration order
4. `DEFAULT_FORMAT` (`"json"`) — the terminal fallback key; resolving to it still requires a registered provider (e.g. `KafkaJsonModule`)

Any format with no registered provider fails fast at startup with an actionable `IllegalArgumentException` naming the missing module. The `"json"` format is no longer reserved — it is an ordinary provider key.

### `serdeProperties` vs. native `properties`

The native `properties` bag (`kafka.consumers.{name}.properties`, `kafka.producer.properties`) is passed verbatim to the Vert.x Kafka client and must contain only Kafka-client keys. Serde and registry configuration lives in its own namespace:

| Config path | Purpose |
|---|---|
| `kafka.schemaRegistry.*` | Global Schema Registry config (URL, auth) |
| `kafka.consumers.{name}.serdeProperties.*` | Per-consumer serde overrides (highest priority) |
| `kafka.producers.{name}.serdeProperties.*` | Per-producer serde overrides |
| `kafka.producers.{name}.methods.{method}.serdeProperties.*` | Per-method serde overrides (highest priority) |

Merge order (most specific wins): per-method `serdeProperties` > per-producer `serdeProperties` > `kafka.schemaRegistry.*`.

### JSON Mapper Profile Selection

When the resolved format is `"json"`, the framework threads the selected profile id through the `serdeConfig` `JsonObject` bag (under the key `"jsonProfile"`) that is passed to `KafkaSerdeProvider.serializer()` / `.deserializer()`. The JSON serde provider (`JsonSerdeProvider` in `vertique-kafka-json`) reads this key and resolves the backing `ObjectMapper` from `JsonMapperProfileRegistry` (from `vertique-json`, installed by `KafkaJsonModule` via `JsonRuntimeModule`). Providers for other formats (e.g. the Avro provider) ignore the `"jsonProfile"` key.

The `"jsonProfile"` key is added to the bag when a **non-blank** profile id is resolved in `vertique-kafka-core` (including an explicit `"vertx"`, which `JsonSerdeProvider` resolves to `DatabindCodec.mapper()` and stops). When no per-binding / `kafka.jsonProfile` id is resolved, the key is absent and the JSON serde provider applies the global `json.jsonProfile` default (if configured) then the `vertx` floor — so the zero-config path stays byte-for-byte unchanged. (The global tier lives in `vertique-kafka-json`; `vertique-kafka-core` only resolves the neutral string id.)

#### JSON Mapper Profile Selection Precedence

**Consumer** (highest to lowest):

1. `kafka.consumers.<name>.jsonProfile` — per-consumer config (always wins)
2. `@JsonProfile("...")` on the `@KafkaListener` type (TYPE-level) or `KafkaConsumerBinding.Builder#jsonProfile(JsonProfileId)`
3. `kafka.jsonProfile` — the kafka boundary default (a `KafkaConfig` field; resolved in `vertique-kafka-core`)
4. `json.jsonProfile` — the global default (applied in `vertique-kafka-json` `JsonSerdeProvider`)
5. `vertx` framework default — `DatabindCodec.mapper()`

A custom `KafkaDeserializer` instance set via `KafkaConsumerBinding.Builder#deserializer()` bypasses the profile entirely — the profile is only applied when the framework builds the serde from a `KafkaSerdeProvider` (FR-JSON-037). A method-level `@JsonProfile` on a `@KafkaListener` type is rejected at build time (FR-JSON-066; see ADR-0138).

**Producer** (highest to lowest):

1. `kafka.producers.<name>.methods.<method>.jsonProfile` — per-method config
2. `kafka.producers.<name>.jsonProfile` — producer config
3. `@JsonProfile("...")` on the `@KafkaProducer` type (TYPE-level)
4. `kafka.jsonProfile` — the kafka boundary default (resolved in `vertique-kafka-core`)
5. `json.jsonProfile` — the global default (applied in `vertique-kafka-json` `JsonSerdeProvider`)
6. `vertx` framework default — `DatabindCodec.mapper()`

### Worker-threading rule for `mayBlock` formats

When the effective deserializer's `mayBlock()` is `true`:

- The consumer verticle is **forced** to worker threading (`ThreadingModel.WORKER`).
- An explicit `worker = false` in consumer config is **rejected at startup**.
- An absent `worker` field is silently forced to `true`.

This contract — not a bounded timeout — is the event-loop-safety protection for formats that call a remote registry. `ResolvedKafkaConsumerConfig` carries a tri-state `workerConfigured` field (`true` = explicitly set, `false` = explicitly false, `null` = absent) to distinguish "forced" from "rejected."

### Dagger composition — choosing format modules

`KafkaModule` (in `vertique-kafka-core`) declares the `@Multibinds Set<KafkaSerdeProvider>` but contributes no provider. Applications compose the format modules they need alongside `KafkaModule`:

| Scenario | Dependencies | `@Component` modules |
|---|---|---|
| Core-only / advanced (raw handlers or custom provider) | `vertique-kafka-core` | `KafkaModule`, your provider module |
| Default JSON | `vertique-kafka-core` + `vertique-kafka-json` | `KafkaModule`, `KafkaJsonModule` |
| JSON + Avro | `vertique-kafka-core` + `vertique-kafka-json` + `vertique-kafka-avro` | `KafkaModule`, `KafkaJsonModule`, `AvroModule` |

`KafkaJsonModule` includes `JsonRuntimeModule` (from `vertique-json`), which installs the `JsonMapperProfileRegistry` binding. This registry is available throughout the application wherever Kafka JSON serde is wired — no separate `@Component` module inclusion is required for it.

```java
// JSON-only application
@Singleton
@Component(modules = { KafkaModule.class, KafkaJsonModule.class })
interface AppComponent { ... }

// JSON + Avro application
@Singleton
@Component(modules = { KafkaModule.class, KafkaJsonModule.class, AvroModule.class })
interface AppComponent { ... }
```

To add a custom format, implement `KafkaSerdeProvider`, expose a Dagger module that contributes the provider via `@Provides @Singleton @IntoSet`, and add the module to your `@Component`. See `dev.vertique:vertique-kafka-avro` for the complete Avro reference.

### Provider-required validation

An endpoint requires a registered `KafkaSerdeProvider` for its resolved format whenever the framework must build a serde or routing artifact for it — including the default `"json"` format. When the provider is missing, startup validation fails fast with an actionable error naming the missing module. Paths that build nothing require no provider:

| Path | Provider required? |
|---|---|
| Enabled binding/handler with framework-built deserializer | Yes |
| Disabled binding/handler with a declared `valueType` | Yes |
| Router with any property route or non-`Void` payload route | Yes |
| All-`Void` property router (reads selector to route) | Yes |
| Model-2 custom deserializer | No |
| Model-4 raw `KafkaRecordHandler` | No |
| Non-property `Void`/no-payload binding or handler | No |
| Header/default-only all-`Void` router (no property route, no payload) | No |

---

## Extension Points

### `KafkaConsumerInterceptor`

Consumer pipeline interceptor SPI. Contribute via `Set<KafkaConsumerInterceptor>` multibinding. `KafkaConsumerInterceptor extends OrderedExtension` and is sorted by `OrderedExtension.comparator()` — phase ascending, then priority ascending, then `orderKey` (default FQCN) as a stable tie-break. Lower priority values run first.

Callbacks are split into two categories:

**Sync observers** (fire-and-forget, cannot affect outcome):

| Callback | When called | Use for |
|----------|-------------|---------|
| `onRecord(ctx)` | After deserialization, before `beforeDispatch` | Structured logging, metrics |
| `onSuccess(ctx)` | After successful dispatch | Success metrics, evidence capture |
| `onError(ctx, error)` | On any dispatch failure | Error metrics, alerting |

Exceptions thrown in sync observers are swallowed and logged.

**Async handlers** (can affect outcome):

| Callback | Failure behavior | Use for |
|----------|-----------------|---------|
| `beforeDispatch(ctx)` | Failed future short-circuits dispatch | Filtering (`withFiltered(true)`), MDC setup, tracing |
| `afterDispatch(ctx)` | Failures logged, not propagated; returns `Future<Void>` | Async post-processing, trace span completion |
| `recoverError(ctx, error)` | Succeeded future = error handled (record committed); failed future = proceed with configured error strategy | Ignore specific errors, route to retry topic |

```java
@Provides @IntoSet
static KafkaConsumerInterceptor tracingInterceptor(TracingInterceptor interceptor) {
    return interceptor;
}
```

**`recoverError` example** — ignore validation errors instead of applying the configured error strategy:

```java
public Future<Void> recoverError(KafkaDispatchContext<?> ctx, Throwable error) {
    if (error instanceof BeanValidationException) {
        return Future.succeededFuture(); // handled — record will be committed
    }
    return Future.failedFuture(error); // not handled — proceed with SKIP/DEAD_LETTER/RETRY
}
```

Interceptors are tried in `OrderedExtension` order for `recoverError`; the first interceptor that returns a succeeded future wins and later interceptors are not invoked.

`KafkaDispatchContext<V>` is immutable with copy-on-write: `ctx.withFiltered(true)` and `ctx.withAttribute("key", value)` return new instances. The `ctx.retryCount()` field (0-based) indicates how many Kafka-native redeliveries have occurred, enabling retry-topic routing decisions in `recoverError`.

### `KafkaConsumerBinding<?>` multibinding (Model 2)

Contribute programmatic consumer definitions:

```java
@Multibinds
abstract Set<KafkaConsumerBinding<?>> kafkaConsumerBindings();

// Application:
@Provides @IntoSet
static KafkaConsumerBinding<?> myBinding() { ... }
```

### `@KafkaConsumers Set<Object>` multibinding (Models 3 and 4)

Contribute `@KafkaListener` routing interfaces and `KafkaRecordHandler` instances:

```java
@Provides @IntoSet @KafkaConsumers
static Object myHandler(MyHandler handler) {
    return handler;
}
```

**Generated auto-wiring (Models 1 and 4 only):**

Applications inheriting `vertique-app-parent` declare the Kafka runtime capability they use and
receive the complete processor facade automatically. Custom-parent applications use the BOM plus
`vertique-codegen-all` recipe in `docs/packaging.md`. `vertique-codegen-dagger` owns generated
`@Provides @IntoSet @KafkaConsumers Object` bindings for `@KafkaSource`-annotated classes (Model 1)
and `@KafkaListener`-annotated **classes** (Model 4). Include
`GeneratedKafkaConsumersModule.class` in the `@Component`.

**Model 3 routing interfaces (`@KafkaListener` on an interface) are NOT auto-wired.** Their contribution to `@KafkaConsumers Set<Object>` is the router interface's `Class<?>` literal, not an instance — this must remain a manual `@Provides @IntoSet @KafkaConsumers Class<?>` binding that returns the router class (e.g. `return OrderEventRouter.class;`). See `dev.vertique:vertique-codegen-dagger` for the full setup guide.

### `KafkaSerdeProvider` multibinding

Value-format provider SPI. Contribute via `@IntoSet Set<KafkaSerdeProvider>` to register a format. `KafkaModule` declares the multibinding but contributes no provider — include `KafkaJsonModule` for JSON support, `AvroModule` for Avro, or your own module for a custom format. An empty provider set is legal but any consumer needing a serde fails at startup with an actionable error. See [Value Formats](#value-formats) for the full contract and the `KafkaSerdeRegistry` precedence rules.

```java
@Provides @Singleton @IntoSet
static KafkaSerdeProvider myFormatProvider(@VertxConfig JsonObject config) {
    return new MyFormatSerdeProvider(config);
}
```

### `KafkaDeserializer<V>`

Deserialization SPI. By default the framework builds a deserializer via the registered `KafkaSerdeProvider` for the resolved format. Supply a custom deserializer via `KafkaConsumerBinding.Builder#deserializer()` to bypass format resolution entirely. Implement `mayBlock()` to return `true` when the deserializer may call a remote service (e.g., a Schema Registry) — the framework will force worker threading for consumers using it.

```java
public interface KafkaDeserializer<V> {
    V deserialize(byte[] data, String topic, Map<String, String> headers)
        throws DeserializationException;
    default boolean mayBlock() { return false; }
}
```

The `topic` and `headers` parameters are available for schema-registry-based deserializers (e.g., Avro with Confluent Schema Registry).

### Boundary Evidence Capture Hooks (AUD-003)

Three SPIs in `dev.vertique.kafka` carry raw wire facts to evidence-capture and observability adapters without creating a dependency from `vertique-kafka-core` on any concrete adapter module (see ADR-0093, ADR-0094). All three are observer-only: they fire **after** the irrevocable pipeline or send decision, and any exception thrown by an implementation is swallowed.

**`KafkaConsumerCaptureHook`** (`dev.vertique.kafka.interceptor`) — fires exactly once per consumer record at the terminal point (after all async work: DLQ publish, seek, recovery). Implementations receive the `KafkaDispatchContext` and the final `KafkaTerminalOutcome`:

| `KafkaTerminalOutcome` value | Meaning |
|---|---|
| `SUCCESS` | Handler completed successfully |
| `SKIP` | Record skipped per `ErrorStrategy.SKIP` |
| `RECOVERED` | Error recovered by an interceptor's `recoverError` |
| `RETRY_SCHEDULED` | Consumer paused; offset not committed; Kafka will redeliver |
| `DLQ_PUBLISHED` | Record forwarded to the dead-letter topic |
| `DLQ_FAILED` | DLQ publish failed; offset NOT committed |
| `ERROR_HANDLER_FAILED` | The error handler's own future failed; the record's disposition (commit, DLQ, retry) is unknown |

`KafkaErrorHandler.handleError(...)` now returns `Future<KafkaTerminalOutcome>` so it can feed this hook. Hooks are ordered by the `OrderedExtension` contract. Register via `@IntoSet Set<KafkaConsumerCaptureHook>`.

`KafkaDispatchContext.rawEvidence()` returns a `PayloadSource` over the deserialized record bytes (replacing the removed `rawValue()` raw-bytes method). The `PayloadSource` is a no-copy buffered view — hooks must not retain it across threads without copying.

**`KafkaProducerCaptureHook`** (`dev.vertique.kafka.producer`) — fires exactly once per send through the shared wire funnel of `KafkaProducerFactory`, after `producer.send(record)` settles. Implementations receive:

| Parameter | Notes |
|---|---|
| `origin` | `KafkaSendOrigin` — `DIRECT_PRODUCER`, `OUTBOX`, `DLQ`, or `INTERNAL` |
| `topic` | target topic |
| `key` | record key, or `null` |
| `value` | no-copy `PayloadSource` over the serialized wire bytes |
| `headers` | fully-merged wire headers |
| `producerMethod` | the `@KafkaProducer` interface method, non-null only for `DIRECT_PRODUCER` |
| `result` | settled `AsyncResult<RecordMetadata>` |

`producerMethod` is the only way for downstream adapters to access method-level annotations and resolve their policies on the direct-producer path. Register via `@IntoSet Set<KafkaProducerCaptureHook>` on `KafkaModule`.

Concrete implementations of these hooks live in downstream extension modules.

---

## Typed Producer Proxies

Define an interface annotated with `@KafkaProducer`. Each method requires a `@Topic` annotation declaring the target topic.

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

Create via `KafkaProducerFactory`:

```java
@Provides @Singleton
static OrderEvents orderEvents(KafkaProducerFactory factory) {
    return factory.create(OrderEvents.class);
}
```

**Parameter resolution per method (position-based):**

| Parameters | Interpretation |
|------------|---------------|
| `(V value)` | value only; key is `null` |
| `(String key, V value)` | key + value |
| `(V value, Map<String,String> headers)` | value + headers |
| `(String key, V value, Map<String,String> headers)` | key + value + headers |

Topics, formats, serde properties, and the JSON mapper profile can be overridden per-method in config at `kafka.producers.{producerName}.methods.{methodName}.*`.

**`@KafkaProducer` attributes (selection-relevant):**

| Attribute | Default | Description |
|-----------|---------|-------------|
| `name` | `""` (interface simple name) | Producer name for config reference |

To select a JSON mapper profile for value serialization, place `@JsonProfile("...")` at TYPE level on the `@KafkaProducer` interface. This is not a `@KafkaProducer` annotation attribute. Overridden by per-producer config (`kafka.producers.<name>.jsonProfile`) and per-method config. A method-level `@JsonProfile` on a `@KafkaProducer` type is rejected at build time (FR-JSON-066; see ADR-0138).

**Producer `@JsonProfile` precedence (highest to lowest):**

1. `kafka.producers.<name>.methods.<method>.jsonProfile` (per-method config)
2. `kafka.producers.<name>.jsonProfile` (producer config)
3. `@JsonProfile("...")` on the `@KafkaProducer` type (TYPE-level)
4. `kafka.jsonProfile` — the kafka boundary default
5. `json.jsonProfile` — the global default
6. `vertx` framework default — `DatabindCodec.mapper()`

---

## Module Dagger Bindings

Include `KafkaModule` in the application `@Component` along with at least one format module. `KafkaModule` transitively includes `DispatchModule`, `DeployerModule`, and `HealthCheckModule`.

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    ConfigModule.class,
    DispatchModule.class,
    KafkaModule.class,       // core runtime; auto-includes DispatchModule + DeployerModule + HealthCheckModule
    KafkaJsonModule.class,   // JSON format provider (from vertique-kafka-json)
    AppModule.class,
    ServiceModule.class
})
interface AppComponent {
    KafkaConsumerDeploymentManager kafkaConsumerDeploymentManager();
    KafkaProducerFactory kafkaProducerFactory();
}
```

**Bindings provided:**

| Type | Scope | Description |
|------|-------|-------------|
| `KafkaConsumerRegistry` | `@Singleton` | Registry built from all consumer sources |
| `KafkaConsumerDeploymentManager` | `@Singleton` | Deploys all consumer verticles |
| `KafkaProducerFactory` | `@Singleton` | Creates typed producer proxies and raw send |
| `HealthCheck @Readiness` (via `@IntoSet`) | — | `KafkaConsumerHealthCheck` contributed to readiness multibinding |

---

## Dependencies

| Artifact | Scope | Purpose |
|----------|-------|---------|
| `dev.vertique:core` | compile | DI wiring, FailureMapper, HealthCheck SPI |
| `dev.vertique:deploy` | compile | VerticleDeployer, LifecyclePhase |
| `dev.vertique:services` | compile | ServiceContractRegistry for dispatch address resolution |
| `io.vertx:vertx-kafka-client` | compile | Vert.x Kafka consumer/producer client |

`vertique-kafka-core` has no Jackson, Avro, or Apicurio dependency. Those live in the format modules: `vertique-kafka-json` depends on `jackson-databind`; `vertique-kafka-avro` depends on `org.apache.avro:avro` and `io.apicurio:apicurio-registry-avro-serde-kafka`.

---

## Related ADRs

- ADR-0072: Kafka `KafkaBindingMeta` Shape and Registrar Integration — metadata shape decisions, three-way `Kind` discriminator, companion origin-package pinning, dual-hook load-prefer-with-reflective-fallback, and shared `KafkaConsumerValidation` extraction.
- ADR-0074: Pluggable Kafka Value Serde Formats (Apicurio Avro Default) — original load-bearing decisions for the `KafkaSerdeProvider` SPI: byte-level wrapping, Confluent-wire encoding, Apicurio as the Avro provider, format precedence, event-loop-safety contract, and serde-config separation. Superseded on the "JSON built-in" and single-module decisions by ADR-0075.
- ADR-0075: Format-Neutral Kafka Core — records the multi-module family split (`vertique-kafka-{core,json,avro}`), JSON-as-ordinary-provider (not built-in), the `convertRouted` SPI addition, provider-required validation (including for the default `json` format), and the `DEFAULT_FORMAT` constant replacing the reserved `JSON` constant.
- ADR-0084: Framework Extension-Ordering Contract — establishes `OrderedExtension` and `ExtensionPhase` as the canonical ordering contract for framework extensions.
- ADR-0085: OrderedExtension Rolled Out Across Sorted Behavioral SPIs — `KafkaConsumerInterceptor` now follows the framework OrderedExtension ordering contract (phase → priority → orderKey).
- ADR-0093: Neutral Evidence Hook Model — establishes the neutral-hook pattern (producer modules carry no downstream adapter dependency; capture hooks are the data conduit between producers and adapters).
- ADR-0094: Kafka Boundary Capture Hooks — load-bearing decisions for `KafkaConsumerCaptureHook`, `KafkaProducerCaptureHook`, `KafkaSendOrigin`, and `KafkaTerminalOutcome`; `rawEvidence()` replacing `rawValue()` on `KafkaDispatchContext`.
- ADR-0106: Kafka Resolved VO Rename — records the rename of the internal runtime VO from `KafkaConsumerConfig` to `ResolvedKafkaConsumerConfig` and the introduction of the external typed `KafkaConsumerConfig` in `dev.vertique.kafka.config`.
- ADR-0107: Kafka Core Permit Jackson Annotations for Config — records the decision to allow Jackson annotations in `vertique-kafka-core` for the typed config records in `dev.vertique.kafka.config`.
- ADR-0127: Kafka Profile Serde Threading — records threading the selected JSON mapper profile id through the `serdeConfig` bag and the consumer precedence (per-consumer config → binding/`@JsonProfile` on type → `vertx`), with an explicit deserializer still winning and the `vertx`/default path byte-for-byte unchanged.
- ADR-0136: Global + per-boundary JSON default-profile config tiers — adds `kafka.jsonProfile` as the Kafka per-boundary default profile key (between per-binding profiles and the `json.jsonProfile` global default in the resolution chain); records that kafka-core remains format-agnostic and the global tier is applied in `vertique-kafka-json`.
- ADR-0138: Harmonized `@JsonProfile` selection surface — establishes that `@JsonProfile` is TYPE-level only on Kafka listener and producer types; a method-level placement is rejected at build time (FR-JSON-066).
