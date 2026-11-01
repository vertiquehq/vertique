<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Workflow Kafka Module

> **Status:** Alpha
> **Package:** `dev.vertique.workflow.kafka`
> **Artifact:** `vertique-workflow-kafka`
> **Depends on:** workflow-services, kafka-core, inbox-outbox-core (through workflow-services)

`vertique-workflow-kafka` turns the records of a Kafka topic into correlated workflow signals. A source names a topic and a consumer group, says how a record addresses a waiting workflow instance, and builds a `KafkaConsumerBinding` that the Kafka consumer registry runs.

This module does not start workflows and does not call the workflow engine: it records signals through the source delivery bean of `vertique-workflow-services`.

---

## When To Use It

Install this module when a message on a Kafka topic must wake a workflow instance that waits for a signal. It pairs with `vertique-workflow-services` (which provides the delivery bean) and `vertique-kafka-core` (which runs the binding).

The status is Alpha: public signatures and configuration may break in any release.

---

## Core Concepts

**One source, one binding.** `WorkflowKafkaSignals.on(topic, type)` starts a `KafkaSignals<T>`. The shared stages (`when`, `scope`, `anyScope`, `dedupBy`, `lookupTimeoutMs`, `dispatchAs`, `restoreCorrelationFromHeaders`, `onNoMatch`, `signal(contract, name)` with a key source and an optional `payload`) are the stages of `vertique-workflow-services`, and mean the same here as on any other transport. This module adds `topic`, `groupId`, `name`, `filter` and `dlqReplay`.

**At-least-once from the start of the topic.** A built binding always commits manually and retries, and requires both strategies, so no record is lost. A new group starts at the beginning of the topic: `auto.offset.reset` defaults to `earliest`, so the first deploy of a source reads all retained history, and `dedupBy` does not catch those old events. An old event can match a current wait or park on a live business key. To start at the end of the topic instead, set `kafka.consumers.{name}.properties` `auto.offset.reset=latest`; a first-ever record that fails before the first commit can then be lost if the consumer restarts or the partition moves. The resolved configuration `kafka.consumers.{name}.*` cannot restore `commitStrategy=AUTO`, `errorStrategy=SKIP` or `retry.exhaustedStrategy=SKIP`: registration fails, naming the consumer and the option. `KafkaSignals` has no stage for a commit, error or retry strategy. The binding installs no durable context from record headers.

**Name.** A source is named `{groupId}-{topic}` unless `.name(...)` replaces it. The name is the consumer's configuration key and the source name in diagnostics and in events.

**Construction checks.** `build()` fails with an `IllegalStateException` that names the source and never a key, for a signal the contract does not declare, a signal with no key source, a business-key signal with no `dedupBy`, and `scope` beside `anyScope`. A blank topic, group id or name fails at once with an `IllegalArgumentException` naming it.

**Undecodable records.** A record that cannot be decoded is acknowledged and reported once as `UNDECODABLE` through the delivery bean. It does not go down the error strategy. A transient decode failure, such as a schema registry that cannot be reached (`DeserializationException.isTransient()`), is not ignored: it takes the error strategy, so the record is retried and then dead-lettered rather than lost.

**Concurrency, and no order.** A built binding runs records concurrently, up to `kafka.consumers.{name}.maxInFlight` at once (default `256`) across all the partitions of the consumer, like any `MANUAL` consumer. There is no ordering rule: not by partition, not by key. A later record, also of the same key, can be recorded before an earlier one, and the consumer commits only up to the lowest record that has not finished. The ceiling is the database: each record costs about a dozen statements in one transaction, and `maxInFlight` records that run at once hold that many connections of the application's pool, so size the pool for it and lower `maxInFlight` before the pool or `NOTIFY` becomes the bottleneck. Raise `kafka.consumers.{name}.instances` for more consumers, up to the number of partitions. A retry or a wait holds the commit of its partition, and for an ordinary retry also the fetching of that partition, not the other records of the consumer. A dead-letter publish that fails is made again after the retry backoff, and the record stays uncommitted until it is acknowledged.

**An early signal.** Because records run in no order, a signal can arrive before the thing it resumes. A signal by business key parks until the instance reaches a wait that accepts it. A signal by a wait's key finds no wait, which is a no-match: `.onNoMatch(RETRY)` retries it with the backoff of `kafka.consumers.{name}.retry` until the wait opens or the record reaches the dead-letter topic, and a source that ignores no-match drops it. Choose the policy for the order your topic really delivers.

**Stages have no side effects.** `.when`, `.dedupBy`, `.dispatchAs`, `.key`, `.businessKey`, the lookups and the payload mapping run again for every attempt and for a record the consumer reads again after a restart or a rebalance. They must be safe to repeat: read, compute and return, and change nothing.

**Idempotent through the inbox.** Each signal of a record commits one inbox row with the command that records it, so a record that runs again, after a restart, a rebalance or a dead-letter replay, ends as it did the first time. A recorded signal is deduplicated on replay. A signal that found no wait and is ignored, and a signal whose key or lookup answered empty, commit their row in a transaction of their own, so a replay stays ignored even when a wait has opened by then. A signal that failed, and a no-match that is retried, leave no row, so their next attempt runs normally; a record that was dead-lettered has no row for the target that failed, and a replay of it runs normally. The identity of a row is a digest of the contract, the signal, the narrowing scope and the record's dedup key, so it is the same for a replay from the dead-letter topic. The inbox retention (30 days by default) bounds it: an ignored record replayed after that is treated as new. **A source node needs the inbox module** (`vertique-inbox-outbox-postgresql`) with its migration and its cleanup job, a recording-only node included; a graph without an `InboxService` does not compile.

**Filter.** A record the `.filter` rejects is committed as ignored and reported once as `IGNORED`, with no key, header or payload. A filter that throws is reported as `STAGE_FAILED` and raises a terminal failure whose error type is the exception's class name alone, so the record takes the exhausted strategy at once, is never retried, and does not pause the consumer. A source with no `.filter` installs none.

**What a record becomes.** The handler decodes a record into the message the source's stages see (the value, the record key and the headers by name), runs it through the delivery bean, and maps the answer: a message that is finished completes the record; one that may succeed later fails it with an ordinary exception, so the binding retries it with the backoff of `kafka.consumers.{name}.retry` and then dead-letters it; one that is fine while the system is busy waits (see **Waiting out an intake refusal**); one that never will succeed fails it with `KafkaTerminalFailure`, so it is dead-lettered at once, never retried, and does not pause the consumer. When a message has several signals, the most severe fate wins: wait over terminal over retry over finished. A signal that waits for intake outranks every failure, so the record returns to the queue unattempted, spends no retry and dead-letters no signal, and its failed signals run again and decide once none waits. A failure carries an error type only, never a key, a header or a payload, and an error type that is not a short token of letters, digits and `_ $ . -` is replaced before it reaches a header, so a `SignalSourceRefusal` cannot inject one.

**Waiting out an intake refusal.** The binding carries a dispatch gate that follows the node's intake limit. While the node refuses new intake the consumer starts no record and fetches no partition, and it keeps polling, so it keeps its place in the group; it asks again every quarter second and resumes as soon as the node admits. A record the gate held back has not been attempted, so no retry is spent and nothing is dead-lettered. When a node starts refusing after a record started (`WorkflowIntakeRefusedException`), the message is fine and the system is busy, so the handler fails the record with `KafkaDeferredFailure`: it returns to the queue with its attempts unchanged and waits for the gate. The wait has no limit, so an intake refusal that never clears stalls the source and its lag grows. A record is safe only while the topic retains it. Alert on the `LIMITED` outcome and on consumer lag. The open-signal limit is not a wait: it is retried and then dead-lettered like any other retried failure.

**Terminal input.** Input that the type or the database refuses (a key over 255 characters or blank, a NUL character in a key or payload, an invalid scope, an oversized payload), an ambiguous match, an anonymous identity, an identity the actor mapper refuses, a `.dispatchAs` that fails with `SignalSourceRefusal`, and a `.dedupBy` that answers blank are terminal. A no-match under `RETRY`, the open-signal limit, and a failed lookup or `.dispatchAs` future are retried.

**Null and undecodable values.** A record with a null value is acknowledged and reported once as `UNDECODABLE`; it never reaches the stages.

**Identity.** Each record is recorded under the system identity `kafka source: {name}` with the `kafka` invocation origin, so the command's actor is that system. `.dispatchAs(fn)` replaces the identity per record. No identity is ever restored from a record header.

**Provenance and deduplication.** The command records the consumed record's own source name, topic, partition and offset, also on a `.dlqReplay()` source. With no `.dedupBy`, the dedup key is `topic:partition:offset`, so a redelivered record is recorded once. On a `.dlqReplay()` source the key is built from the record's `x-dlq-source-topic`, `x-dlq-source-partition` and `x-dlq-source-offset` headers instead when it carries them, so a replayed record answers a duplicate of the original; every other source ignores those headers. A replay record whose headers are incomplete, or whose partition or offset is not a non-negative integer, has no default key. A `.dedupBy` then still names the key, and a record that `.when` rejects is still ignored; only a record that reaches the key with no `.dedupBy` is reported as `INVALID_INPUT` and dead-lettered at once with the error type `BlankDedupKey`.

**Dead-lettering twice.** The dead-letter headers `x-dlq-source-topic`, `x-dlq-source-partition` and `x-dlq-source-offset` name the position of the record the dead letter started as. A replayed record that is dead-lettered again keeps them, so every later replay computes the same default dedup key and the same inbox identities as the first delivery, however many times the record is replayed: a target that finished on an earlier replay is deduplicated, and only a target that failed runs again. The position of the dead-letter record itself is never used.

**Correlation.** A source restores nothing from record headers unless it declares `.restoreCorrelationFromHeaders()`. Then it reads only the exact `vertique-correlation` header, ignores a value over 4096 characters, decodes it alone, takes the correlation id alone and only when it is at most 128 characters from `A-Z a-z 0-9 . _ ~ : / + = -`, and binds it on a context seeded by the application's `CorrelationContextFactory`, so the ids come from the application's correlation id generator, and that keeps its own request id. A header that is missing, too long, malformed or invalid is ignored: it never fails the record and its value is never logged, and the record keeps the freshly seeded correlation. A source without the stage restores nothing from headers, but the delivery bean installs the recording's dispatch scope, which runs the registered `CorrelationContextSeeder`: the record is recorded under a freshly seeded correlation, with ids from the application's correlation id generator. The seeder never replaces a correlation the stage bound.

---

## Key Classes

### WorkflowKafkaSignals

The injectable entry point, `@Singleton` with an `@Inject` constructor taking `WorkflowSignalSourceDelivery` and the application's `CorrelationContextFactory`.

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

### KafkaSignalListener

An interface annotated `@KafkaSignalListener(name, topic, groupId)` whose methods carry `@WorkflowSignalTarget` is a signal source with no implementation and no Dagger code. The annotation carries `@SignalSourceForm(KafkaSignalSource.class)`, so the annotation processor of `vertique-codegen-workflow` generates `<Interface>_SignalSource`, a `KafkaSignalSource<T>` with an `@Inject` constructor, and the generated registrations module binds it into the `Set<KafkaSignalSource<?>>` by name, exactly as it does a source annotated for another transport. The application's build needs `vertique-codegen-workflow` on the processor path next to `vertique-codegen-dagger`, and this module on the compile classpath.

The annotation has three attributes and no more: `name` (the consumer's name and configuration key), `topic` and `groupId`, all required and non-blank. The generated source commits manually and retries, requires both strategies, and takes no dead-letter topic, value type or JSON profile of its own, so none of them is an attribute; a `@JsonProfile`, or a `@KafkaHandler` method beside a target, is a compile error because the source would drop it or leave the handler without records. The message type is the type of the target methods' only parameter, and a target by `businessKey` names `dedupBy`. A key is read by the no-argument method named like the property (a record component too), else by `getX()` or `isX()`, and that method returns `String`.

```java
@KafkaSignalListener(name = "carrier-events", topic = "carrier.events", groupId = "logistics")
public interface CarrierSignals {
    @WorkflowSignalTarget(contract = OrdersContract.class, signal = "shipment-delivered", key = "carrierRef",
            dedupBy = "eventId")
    @WorkflowSignalTarget(contract = OrdersContract.class, signal = "order-delivered", businessKey = "orderNumber",
            dedupBy = "eventId")
    void delivered(ShipmentDelivered event);
}
```

An interface or class annotated `@KafkaListener` that carries a `@WorkflowSignalTarget` is a compile error that names `@KafkaSignalListener`: the Kafka listener processor routes records to `@KafkaHandler` methods and would otherwise leave the target without a source. Declare every target on the annotated interface itself; a target on a super-interface is an error.

### KafkaSignalSource and WorkflowKafkaSignalsModule

A source may be a class instead of a call to `WorkflowKafkaSignals`. A class annotated `@WorkflowSignalSource` that implements `KafkaSignalSource<T>` and has an `@Inject` constructor is registered by generated wiring into the `Set<KafkaSignalSource<?>>`, with no application `@Provides` method. Its dependencies come by constructor injection, so a lookup written in `define` uses the repository the class received.

```java
@WorkflowSignalSource
final class ShipmentSignals implements KafkaSignalSource<ShipmentDelivered> {
    private final ShipmentLookup lookup;

    @Inject
    ShipmentSignals(ShipmentLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public void define(KafkaSignals<ShipmentDelivered> source) {
        source.topic("shipment.events")
              .groupId("logistics")
              .dedupBy(ShipmentDelivered::eventId)
              .signal(OrdersContract.class, "shipment-delivered")
              .businessKeyFrom(message -> lookup.orderNumberFor(message.body().carrierRef()));
    }
}
```

`WorkflowKafkaSignalsModule` turns every `KafkaSignalSource<?>` of that `Set` into a `KafkaConsumerBinding` in the `Set<KafkaConsumerBinding<?>>` the Kafka consumer registry reads. It calls the source's `define` with a `KafkaSignals<T>` builder when the binding set is first built, which is after the Dagger graph exists, and then calls `build()`. Nothing runs `define` earlier. The module needs only a `WorkflowSignalSourceDelivery` and a `CorrelationContextFactory` from the application.

- **Name only the generated module.** `KafkaSignalSource` is marked `@SignalSourceType(module = WorkflowKafkaSignalsModule.class)`, so the generated registrations module includes `WorkflowKafkaSignalsModule`. An application does not list it.
- **A forgotten generated module leaves no source.** The generated registrations module carries the sources and this module, so a component that leaves it out builds and runs with no source consuming, as with any generated module. Name it in the component.
- **The message type is the type argument.** `T` is read from the class's `KafkaSignalSource<T>` type argument. A raw `KafkaSignalSource`, or a type variable the class leaves unresolved, fails startup with an `IllegalStateException` naming the class; it never falls back to `Object`.
- **Exceptions from `define` are fatal.** An exception thrown by `define`, or by the checks of `build()`, propagates and fails the startup that builds the binding set; the message names the class.

---

## Extension Points

The shared stage interfaces (`SignalStages`, `SourceStages`, `SignalTargetStage`, `KeyedTargetStage`) are the surface of `vertique-workflow-services`, not of this module. `WorkflowSignalSourceDelivery`, `SourceEnvelope`, `SourceDisposition`, `SignalSourcePlan` and `AbstractSignalSourceBuilder` are **INTERNAL**: seams that a transport in another repository needs, outside the maturity promise of this module at every level. Application code does not depend on them.

---

## Dependencies

- **workflow-services** - the delivery bean, the source plan and the shared source stages. Its `vertique-workflow-core` types come through it.
- **kafka-core** - the consumer binding, record filter and terminal failure.
- **codegen-dagger** - `provided`, and on the module's own processor path, so the module compiles with the generator that registers source classes. An application gets the generator from its parent's processor paths.

The module takes no compile dependency on `vertique-workflow-engine` or `vertique-workflow-postgresql`, and the build enforces it.
