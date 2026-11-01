<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Workflow Kafka Module

> **Status:** Alpha
> **Package:** `dev.vertique.workflow.kafka`
> **Artifact:** `vertique-workflow-kafka`
> **Depends on:** workflow-services, kafka-core

`vertique-workflow-kafka` turns the records of a Kafka topic into correlated workflow signals. A source names a topic and a consumer group, says how a record addresses a waiting workflow instance, and builds a `KafkaConsumerBinding` that the Kafka consumer registry runs.

This module does not start workflows and does not call the workflow engine: it records signals through the source delivery bean of `vertique-workflow-services`.

---

## When To Use It

Install this module when a message on a Kafka topic must wake a workflow instance that waits for a signal. It pairs with `vertique-workflow-services` (which provides the delivery bean) and `vertique-kafka-core` (which runs the binding).

The status is Alpha: public signatures and configuration may break in any release.

---

## Core Concepts

**One source, one binding.** `WorkflowKafkaSignals.on(topic, type)` starts a `KafkaSignals<T>`. The shared stages (`when`, `scope`, `anyScope`, `dedupBy`, `lookupTimeoutMs`, `dispatchAs`, `restoreCorrelationFromHeaders`, `onNoMatch`, `signal(contract, name)` with a key source and an optional `payload`) are the stages of `vertique-workflow-services`, and mean the same here as on any other transport. This module adds `topic`, `groupId`, `name`, `filter` and `dlqReplay`.

**The binding is not configurable towards loss.** A built binding always commits manually and retries, and forces both strategies. The resolved configuration `kafka.consumers.{name}.*` cannot restore `commitStrategy=AUTO`, `errorStrategy=SKIP` or `retry.exhaustedStrategy=SKIP`: registration fails, naming the consumer and the option. `KafkaSignals` has no stage for a commit, error or retry strategy. The binding installs no durable context from record headers.

**Name.** A source is named `{groupId}-{topic}` unless `.name(...)` replaces it. The name is the consumer's configuration key and the source name in diagnostics and in events.

**Construction checks.** `build()` fails with an `IllegalStateException` that names the source and never a key, for a signal the contract does not declare, a signal with no key source, a business-key signal with no `dedupBy`, and `scope` beside `anyScope`. A blank topic, group id or name fails at once with an `IllegalArgumentException` naming it.

**Undecodable records.** A record that cannot be decoded is acknowledged and reported once as `UNDECODABLE` through the delivery bean. It does not go down the error strategy. A transient decode failure, such as a schema registry that cannot be reached (`DeserializationException.isTransient()`), is not ignored: it takes the error strategy, so the record is retried and then dead-lettered rather than lost.

**One record at a time.** A built binding resolves `maxInFlight` and `max.poll.records` to `1`, and a configured `maxInFlight` other than `1` fails startup naming the option. A source therefore handles one record per consumer instance at a time, so its throughput is one recording transaction, plus any lookup, per record; raise `kafka.consumers.{name}.instances` to add consumers. The limit keeps a later record from committing past an earlier one whose retry or dead-letter publish is still pending. A dead-letter publish that fails pauses the consumer, seeks back to the record and delivers it again after the retry backoff, so the record is not left behind.

**Filter.** A record the `.filter` rejects is committed as ignored and reported once as `IGNORED`, with no key, header or payload. A filter that throws is reported as `STAGE_FAILED` and raises a terminal failure whose error type is the exception's class name alone, so the record takes the exhausted strategy at once, is never retried, and does not pause the consumer. A source with no `.filter` installs none.

**What a record becomes.** The handler decodes a record into the message the source's stages see (the value, the record key and the headers by name), runs it through the delivery bean, and maps the answer: a message that is finished completes the record; one that may succeed later fails it with an ordinary exception, so the binding retries it with the backoff of `kafka.consumers.{name}.retry` and then dead-letters it; one that never will succeed fails it with `KafkaTerminalFailure`, so it is dead-lettered at once, never retried, and does not pause the consumer. When a message has several signals, the most severe fate wins: terminal over retry over finished. A failure carries an error type only, never a key, a header or a payload, and an error type that is not a short token of letters, digits and `_ $ . -` is replaced before it reaches a header, so a `SignalSourceRefusal` cannot inject one.

**Terminal input.** Input that the type or the database refuses (a key over 255 characters or blank, a NUL character in a key or payload, an invalid scope, an oversized payload), an ambiguous match, an anonymous identity, an identity the actor mapper refuses, a `.dispatchAs` that fails with `SignalSourceRefusal`, and a `.dedupBy` that answers blank are terminal. A no-match under `RETRY`, the open-signal limit, and a failed lookup or `.dispatchAs` future are retried.

**Null and undecodable values.** A record with a null value is acknowledged and reported once as `UNDECODABLE`; it never reaches the stages.

**Identity.** Each record is recorded under the system identity `kafka source: {name}` with the `kafka` invocation origin, so the command's actor is that system. `.dispatchAs(fn)` replaces the identity per record. No identity is ever restored from a record header.

**Provenance and deduplication.** The command records the consumed record's own source name, topic, partition and offset, also on a `.dlqReplay()` source. With no `.dedupBy`, the dedup key is `topic:partition:offset`, so a redelivered record is recorded once. On a `.dlqReplay()` source the key is built from the record's `x-dlq-source-topic`, `x-dlq-source-partition` and `x-dlq-source-offset` headers instead when it carries them, so a replayed record answers a duplicate of the original; every other source ignores those headers. A replay record whose headers are incomplete, or whose partition or offset is not a non-negative integer, has no default key. A `.dedupBy` then still names the key, and a record that `.when` rejects is still ignored; only a record that reaches the key with no `.dedupBy` is reported as `INVALID_INPUT` and dead-lettered at once with the error type `BlankDedupKey`.

**Dead-lettering twice.** The dead-letter headers `x-dlq-source-topic`, `x-dlq-source-partition` and `x-dlq-source-offset` name the position of the record that failed last. A replayed record that is dead-lettered again carries the position of the dead-letter record it was replayed from, not the original one, so a second replay is deduplicated against that dead-letter record and no longer against the original record. Keep the first dead-letter record if the original position matters.

**Correlation.** A source restores nothing from record headers unless it declares `.restoreCorrelationFromHeaders()`. Then it reads only the exact `vertique-correlation` header, ignores a value over 4096 characters, decodes it alone, takes the correlation id alone and only when it is at most 128 characters from `A-Z a-z 0-9 . _ ~ : / + = -`, and binds it on a context seeded by the application's `CorrelationContextFactory`, so the ids come from the application's correlation id generator, and that keeps its own request id. A header that is missing, too long, malformed or invalid is ignored: it never fails the record and its value is never logged, and the record keeps the freshly seeded correlation. A source without the stage restores nothing from headers, but the delivery bean installs the recording's dispatch scope, which runs the registered `CorrelationContextSeeder`: the record is recorded under a freshly seeded correlation, with ids from the application's correlation id generator. The seeder never replaces a correlation the stage bound.

---

## Key Classes

### WorkflowKafkaSignals

The injectable entry point, `@Singleton` with an `@Inject` constructor taking `WorkflowSourceDelivery` and the application's `CorrelationContextFactory`.

```java
KafkaSignals<ShipmentDelivered> source = signals
    .on("carrier.events", ShipmentDelivered.class)
    .groupId("logistics")
    .filter(KafkaRecordFilter.headerEquals("type", "delivered"));

KafkaConsumerBinding<ShipmentDelivered> binding = source
    .signal(OrdersContract.class, "shipment-delivered")
    .key(ShipmentDelivered::carrierRef)
    .build();
```

### KafkaSignals

The stages of a source, listed above. `build()` returns the `KafkaConsumerBinding<T>` for the consumer registry.

---

## Extension Points

The shared stage interfaces (`SignalStages`, `SourceStages`, `SignalTargetStage`, `KeyedTargetStage`) are the surface of `vertique-workflow-services`, not of this module. `WorkflowSourceDelivery`, `SourceEnvelope`, `SourceDisposition`, `SignalSourcePlan` and `AbstractSignalSourceBuilder` are **INTERNAL**: seams that a transport in another repository needs, outside the maturity promise of this module at every level. Application code does not depend on them.

---

## Dependencies

- **workflow-services** - the delivery bean, the source plan and the shared source stages. Its `vertique-workflow-core` types come through it.
- **kafka-core** - the consumer binding, record filter and terminal failure.

The module takes no compile dependency on `vertique-workflow-engine` or `vertique-workflow-postgresql`, and the build enforces it.
