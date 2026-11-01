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

**Undecodable records.** A record that cannot be decoded is acknowledged and reported once as `UNDECODABLE` through the delivery bean. It does not go down the error strategy.

**Filter.** A record the `.filter` rejects is committed as ignored and reported once as `IGNORED`, with no key, header or payload. A filter that throws is reported as `STAGE_FAILED` and raises a terminal failure whose error type is the exception's class name alone, so the record takes the exhausted strategy at once, is never retried, and does not pause the consumer. A source with no `.filter` installs none.

---

## Key Classes

### WorkflowKafkaSignals

The injectable entry point, `@Singleton` with an `@Inject` constructor taking `WorkflowSourceDelivery`.

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
