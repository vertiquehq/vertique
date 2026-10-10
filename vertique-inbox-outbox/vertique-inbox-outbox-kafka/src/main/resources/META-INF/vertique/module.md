<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Inbox/Outbox Kafka Module

> **Status:** Stable
> **Package:** `dev.vertique.inboxoutbox.kafka`
> **Artifact:** `vertique-inbox-outbox-kafka`
> **Depends on:** inbox-outbox-core, kafka-core

Kafka adapter for Transactional Messaging. Provides `KafkaOutboxDestinationHandler`, which publishes committed outbox rows to Kafka topics using `KafkaProducerFactory`. The relay uses `aggregateId` as the default Kafka record key, ensuring ordering within a partition for the same aggregate.

---

## When To Use It

Add this module when:

- The application uses transactional outbox (Stable `vertique-inbox-outbox-core`) and needs
  `DestinationType.KAFKA` delivery.
- A shared `KafkaProducerFactory` from Stable `vertique-kafka-core` is already on the component.

Do not use when outbox destinations are only PostgreSQL/internal, or when Kafka publish is done
outside the outbox relay.

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
   - **Headers:** application headers from `OutboxEntry.headers` (application-only), converted with `KafkaRecordHeaders.of(Map)` — one UTF-8 text header per map entry, in the map's iteration order; a `null` or empty map gives no application headers — followed by the durable propagation context projected to reserved `vertique-<namespace>` headers (e.g. `vertique-correlation`, `vertique-localization`) via `DurableMetadataHeaderCodec`. Relay control (message id, `eventType`, aggregate ids) is **not** emitted as headers — it is carried internally in `OutboxMetadata.delivery.outbox`.
4. Sends the record through the application's shared Kafka producer with `KafkaProducerFactory.sendForOutbox(topic, key, value, headers, context, entryId)`, passing the outbox entry id as the send's origin reference. Awaits producer acknowledgment.
5. On success: returns `OutboxPublishResult.success()`.
6. On transport/timeout/broker failure: returns `OutboxPublishResult.retryable(message, cause)`.
7. On a reserved-prefix header collision (see below): returns `OutboxPublishResult.permanent(message, cause)`.
8. When the stored header map has a `null` key or a `null` value: nothing is serialized or sent, and the handler returns `OutboxPublishResult.permanent(message, cause)`.

`KafkaProducerFactory` owns **one** shared Kafka producer per application, created lazily on first
send and reused for every topic and every send path (typed producers, DLQ, outbox relay). There is
no producer per topic and none to acquire or release per publish — the topic is just an argument to
the send.

**Outbound headers on every Kafka record:**

| Header | Value |
|--------|-------|
| Application headers | `OutboxEntry.headers` (application-only), each entry sent as one UTF-8 text header. They come first on the record; the `vertique-<namespace>` headers follow |
| `vertique-<namespace>` | One reserved header per bound durable-context namespace (e.g. `vertique-correlation`, `vertique-localization`), value = the namespace body as JSON, projected by `DurableMetadataHeaderCodec` |

Relay/delivery control (`x-message-id`, `eventType`, `aggregateType`, `aggregateId`) is no longer
emitted as Kafka headers — it lives in `OutboxMetadata.delivery.outbox` and is internal to the relay.
`aggregateId` is still used as the Kafka **message key**. An application header that uses
the reserved `vertique-` prefix is rejected: the entry is dead-lettered (`OutboxPublishResult.permanent`),
since the stored row cannot change.

**Ordering note:** Kafka ordering guarantees are meaningful only within a partition. When `aggregateId` is used as the record key, all records for a given aggregate route to the same partition (by default Kafka partitioning), preserving per-aggregate order within that partition. Ordering across different aggregates or partitions is not guaranteed.

**Delivery guarantee:** At-least-once. Consumers should use `InboxService` for deduplication if exactly-once semantics are required.

---

## Observing Kafka Outbox Publishes

This module has no extension point of its own. A Kafka outbox publish is observed at two places,
each with its own facts:

| What | Where | Facts |
|---|---|---|
| The publish attempt | `OutboxPublishObserver` (`dev.vertique:vertique-inbox-outbox-core`), notified by the relay once per attempt after it has recorded the entry's next state | The classified outcome, what the relay did with the entry (published, retry scheduled, dead-lettered, deferred), whether that was recorded, attempt numbers and timing, plus the relay-built `OutboxEnvelope`. Filter on `event.destinationType()` equal to `DestinationType.KAFKA` |
| The wire bytes | `KafkaProducerCaptureHook` (`dev.vertique:vertique-kafka-core`), called once per send after it settles | A `KafkaProducerSend` with origin `KafkaSendOrigin.OUTBOX`, the serialized value, the headers as sent (application headers followed by the `vertique-<namespace>` context headers), the send result, and `originRef` — the outbox entry id as a string |

`KafkaProducerSend.originRef()` equals `OutboxPublishCompletedEvent.entryId()` for the same entry, so
an adapter that needs both the attempt facts and the wire bytes joins the two callbacks on it. The
producer hook runs first: the send settles before the handler returns its result to the relay.

Keep these limits in mind when joining:

- **No producer callback when nothing is sent.** A payload that cannot be serialized, a stored header
  map with a `null` key or value, an application header with the reserved `vertique-` prefix, and a
  producer that cannot be created all end before a record is sent. The relay observer is still
  notified of the attempt; there are no wire bytes for it.
- **The entry id repeats.** One entry is sent once per attempt, so several sends carry the same
  `originRef`. The attempt number also repeats across deferrals and reclaims of a stale claim; see
  `OutboxPublishObserver` in `dev.vertique:vertique-inbox-outbox-core`.

### Migration

The Kafka-specific outbox capture hook and its multibinding on `TransactionalMessagingKafkaModule`
were removed, together with the handler constructor that took the hooks. Observe publish attempts
through `OutboxPublishObserver` and the wire bytes through the producer capture hook with origin
`OUTBOX`:

- the classified result is `event.outcome()`, with `event.disposition()` for what the relay did and
  `event.errorType()` in place of the failure's cause and message;
- the topic and key are `event.destination()` and `envelope.aggregateId()`, or `send.topic()` and
  `send.key()`;
- the serialized value and the headers as sent are `send.value()` and `send.headers()`, joined to the
  attempt on `send.originRef()`;
- the entry id is `event.entryId()` and `send.originRef()`.

---

## Module Dagger Bindings

The module ships one binding. `KafkaOutboxDestinationHandler` is a `@Singleton` built through its
`@Inject` constructor `(KafkaProducerFactory, ObjectMapper)`, and the module contributes it to the
relay's handler set:

```java
@Module
public abstract class TransactionalMessagingKafkaModule {

    @Provides @IntoSet
    static OutboxDestinationHandler kafkaHandler(KafkaOutboxDestinationHandler handler) {
        return handler;
    }
}
```

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

---

## Verification

```bash
./mvnw -ntp -pl :vertique-inbox-outbox-kafka -am test
```
