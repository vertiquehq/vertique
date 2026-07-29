<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Inbox/Outbox Kafka Module

> **Status:** Implemented
> **Package:** `dev.vertique.inboxoutbox.kafka`
> **Artifact:** `vertique-inbox-outbox-kafka`
> **Depends on:** inbox-outbox-core, kafka

Kafka adapter for Transactional Messaging. Provides `KafkaOutboxDestinationHandler`, which publishes committed outbox rows to Kafka topics using `KafkaProducerFactory`. The relay uses `aggregateId` as the default Kafka record key, ensuring ordering within a partition for the same aggregate.

---

## Key Classes

### `KafkaOutboxDestinationHandler`

`OutboxDestinationHandler` implementation for `DestinationType.KAFKA`. Registered by `TransactionalMessagingKafkaModule` into the `Set<OutboxDestinationHandler>` multibinding.

**Claim scope:** Returns `ClaimScope.all()` from `claimScope()`. Kafka topics are globally deliverable — any relay node that has the Kafka adapter installed can publish to any topic — so every `KAFKA` outbox row is claimable by this node. No per-topic registration is required.

At relay time:
1. Reads `destination` (Kafka topic name) from the `OutboxEnvelope`.
2. Serializes `OutboxEnvelope.payload()` to JSON bytes (scalar payloads are first unwrapped via `PayloadCodec`). If serialization fails, publishing stops here and the handler returns `OutboxPublishResult.permanent(message, cause)` so the entry is dead-lettered immediately rather than retried — a payload that cannot be serialized will never serialize on a later attempt.
3. Builds a Kafka record:
   - **Key:** `aggregateId` when present; `null` otherwise.
   - **Value:** the serialized payload bytes from step 2.
   - **Headers:** application headers from `OutboxEntry.headers` (application-only) plus the durable propagation context projected to reserved `vertique-<namespace>` headers (e.g. `vertique-correlation`, `vertique-localization`) via `DurableMetadataHeaderCodec`. Relay control (message id, `eventType`, aggregate ids) is **not** emitted as headers — it is carried internally in `OutboxMetadata.delivery.outbox`.
4. Sends the record through the application's shared Kafka producer (`KafkaProducerFactory`). Awaits producer acknowledgment.
5. On success: returns `OutboxPublishResult.success()`.
6. On transport/timeout/broker failure: returns `OutboxPublishResult.retryable(message, cause)`.
7. On a reserved-prefix header collision (see below): returns `OutboxPublishResult.permanent(message, cause)`.

`KafkaProducerFactory` owns **one** shared Kafka producer per application, created lazily on first
send and reused for every topic and every send path (typed producers, DLQ, outbox relay). There is
no producer per topic and none to acquire or release per publish — the topic is just an argument to
the send.

**Outbound headers on every Kafka record:**

| Header | Value |
|--------|-------|
| Application headers | `OutboxEntry.headers`, forwarded verbatim (application-only) |
| `vertique-<namespace>` | One reserved header per bound durable-context namespace (e.g. `vertique-correlation`, `vertique-localization`), value = the namespace body as JSON, projected by `DurableMetadataHeaderCodec` |

Relay/delivery control (`x-message-id`, `eventType`, `aggregateType`, `aggregateId`) is no longer
emitted as Kafka headers — it lives in `OutboxMetadata.delivery.outbox` and is internal to the relay.
`aggregateId` is still used as the Kafka **message key**. An application header that uses
the reserved `vertique-` prefix is rejected: the entry is dead-lettered (`OutboxPublishResult.permanent`),
since the stored row cannot change.

**Ordering note:** Kafka ordering guarantees are meaningful only within a partition. When `aggregateId` is used as the record key, all records for a given aggregate route to the same partition (by default Kafka partitioning), preserving per-aggregate order within that partition. Ordering across different aggregates or partitions is not guaranteed.

**Delivery guarantee:** At-least-once. Consumers should use `InboxService` for deduplication if exactly-once semantics are required.

---

## Extension Points

### `KafkaOutboxCaptureHook`

Observer-only SPI hook fired by `KafkaOutboxDestinationHandler` exactly once per outbox publish attempt — after the payload has been serialized and after the `OutboxPublishResult` has been classified. Implementations receive:

| Parameter | Notes |
|---|---|
| `topic` | Kafka topic from the outbox entry |
| `key` | record key derived from `aggregateId`, or `null` |
| `value` | no-copy `PayloadSource` over the serialized wire bytes; `null` when serialization failed before bytes were produced |
| `headers` | application headers from the outbox envelope |
| `result` | classified `OutboxPublishResult` (`Success`, `RetryableFailure`, or `PermanentFailure`) |
| `entryId` | string form of the outbox entry's surrogate key |

Internally, the outbox relay uses `KafkaProducerFactory.sendForOutbox(...)`, which tags the send with `KafkaSendOrigin.OUTBOX` so the `KafkaProducerCaptureHook` in `vertique-kafka-core` also fires for the same send (see `dev.vertique:vertique-kafka-core`). The `KafkaOutboxCaptureHook` fires at the outbox-handler level (with the outbox envelope context) while the producer hook fires at the wire level.

Throwing implementations are caught, warn-logged, and discarded; the publish result is unaffected. Hooks implement `OrderedExtension` (phase → priority → orderKey). Register via `@IntoSet Set<KafkaOutboxCaptureHook>` on `TransactionalMessagingKafkaModule`.

---

## Dagger Wiring

The module ships three bindings. The handler is built by an explicit `@Provides` method rather than
from its `@Inject` constructor, because that is what injects the capture-hook set:

```java
@Module
public abstract class TransactionalMessagingKafkaModule {

    // Declares the hook set so it exists (empty) even when nobody contributes.
    @Multibinds
    abstract Set<KafkaOutboxCaptureHook> kafkaOutboxCaptureHooks();

    // Builds the handler WITH the registered hooks.
    @Provides @Singleton
    static KafkaOutboxDestinationHandler kafkaOutboxDestinationHandler(
            KafkaProducerFactory producerFactory,
            ObjectMapper objectMapper,
            Set<KafkaOutboxCaptureHook> captureHooks) {
        return new KafkaOutboxDestinationHandler(producerFactory, objectMapper, captureHooks);
    }

    @Provides @IntoSet
    static OutboxDestinationHandler kafkaHandler(KafkaOutboxDestinationHandler handler) {
        return handler;
    }
}
```

> **Do not replace the `@Provides` method with constructor injection.**
> `KafkaOutboxDestinationHandler`'s `@Inject` constructor takes only
> `(KafkaProducerFactory, ObjectMapper)` and binds an empty hook set. If Dagger resolves the handler
> through that constructor — which is what happens if the `@Provides` method above is dropped — the
> handler is built with **zero** capture hooks and every contributed `KafkaOutboxCaptureHook`,
> including the one from `audit-kafka`, is silently ignored. The hook-injecting constructor is the
> three-argument one used by the `@Provides` method.

Include alongside `TransactionalMessagingPostgresqlModule` and `KafkaModule`:

```java
@Component(modules = {
    VertxModule.class,
    KafkaModule.class,
    DbPostgresqlModule.class,
    DbFlywayModule.class,
    TransactionalMessagingPostgresqlModule.class,
    TransactionalMessagingKafkaModule.class,
    AppModule.class
})
public interface AppComponent { ... }
```

The dependency required is `vertique-kafka-core` (provides `KafkaModule` and `KafkaRecordHandler`). `KafkaOutboxDestinationHandler` serializes outbox payloads via its own `ObjectMapper` and the raw-byte send path, so no format module is needed for the outbox relay itself. An application that also consumes Kafka topics with JSON payloads would additionally include `vertique-kafka-json` and `KafkaJsonModule`.

---

## Configuration

No Kafka-specific configuration in this module. Kafka producer settings (bootstrap servers, serializers, acks, etc.) are configured in the `kafka` module: connection scalars at the `kafka` root (e.g. `kafka.bootstrap.servers`), global properties under `kafka.properties`, and the global producer bag under `kafka.producer.properties`. Per-named-producer overrides live under `kafka.producers.{name}.*`. See `dev.vertique:vertique-kafka-core` for the full configuration reference.
