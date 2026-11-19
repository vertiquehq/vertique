<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Micrometer Outbox Module

> **Status:** Alpha
> **Package:** `dev.vertique.micrometer.outbox`
> **Artifact:** `vertique-micrometer-outbox`
> **Depends on:** `vertique-micrometer-core`, `vertique-inbox-outbox-core`, `vertique-core`, micrometer-core

Provides the explicit Micrometer adapter for the transactional outbox. It contributes one
`OutboxPublishObserver` that counts publish attempts, and a timer that reads the queue size through
`OutboxQueueProbe` for three gauges: queue depth, the age of the oldest waiting entry, and the dead-letter
count. The outbox modules stay independent of Micrometer; applications choose whether to install this adapter.

The module is Alpha: its meter names, tag values and the `metrics.outbox` key may change in any release, and
each break is recorded here.

## When To Use It

Add `vertique-micrometer-outbox` when the application runs the outbox relay and wants to see its throughput,
failures and backlog. Install `MicrometerOutboxModule` alongside `MicrometerModule` and the application's outbox
modules (the PostgreSQL outbox module binds the `OutboxQueueProbe` this adapter needs) in the Dagger `@Component`.

The adapter may be installed without `MicrometerModule`, but the application must provide the `MeterRegistry`
binding it injects. When no `MetricsConfig` binding is present, outbox metrics default to enabled. When
`metrics.enabled=false`, the observer records nothing and the timer never starts.

## Core Concepts

### Publish counters

The observer counts every attempt the relay reports, once per attempt, through `OutboxPublishObserver`.

| Meter | Type | Tags | Counts |
|---|---|---|---|
| `vertique.outbox.publish.attempts` | counter | `destination.type` | every attempt |
| `vertique.outbox.publish.failures` | counter | `destination.type`, `outcome` | attempts that did not succeed; `outcome` is `retryable-failure`, `permanent-failure` or `unresolvable` |
| `vertique.outbox.publish.outcomes` | counter | `destination.type`, `outcome` | what the relay did with the entry: `published`, `retry-scheduled`, `dead-lettered`, `deferred`, or `unrecorded` |

`outcome` means different things on the two counters: on `failures` it is how the attempt ended, on `outcomes` it
is the relay's disposition of the entry. `unrecorded` is counted when the repository did not confirm the state
change (the call failed, threw, or reported that this relay no longer owned the entry), so a `dead-lettered` count
is never an unconfirmed one. A retryable failure that uses up the last attempt adds one `retryable-failure` and one
`dead-lettered`.

`destination.type` is the entry's destination type id, lower-cased with `_` replaced by `-`: `service`,
`delayed-job`, `kafka`, or an adapter's own type. The destination, aggregate id, entry id, headers, payload,
error type and error message are never tags.

### Queue gauges

| Meter | Type | Tags | Reads |
|---|---|---|---|
| `vertique.outbox.queue.depth` | gauge | none | entries the relay may claim now |
| `vertique.outbox.queue.oldest.age` | gauge (seconds) | none | how long the oldest claimable entry has been waiting; `0` when none is |
| `vertique.outbox.dead.letters` | gauge | none | entries in the `DEAD_LETTER` state |

An entry is claimable when it is `PENDING` and neither its scheduled time nor its retry delay is in the future;
entries waiting for either are not counted, so a backlog that is only scheduled work does not look like lag. The
dead-letter count is the number of rows in the table, which `inboxOutbox.cleanup` trims by retention.

A timer reads these three figures with one `OutboxQueueProbe.sample()` call every `queueSampleIntervalMs`. The
gauges are registered on the first successful read, so a node that has not read yet publishes no queue meters.
A failed read is logged at debug level and skipped: the gauges keep their last values until a read succeeds, and a
read never starts while the previous one is still running. Every node that installs the adapter reads the same
table and reports the same figures; aggregate them with `max` rather than `sum` across nodes.

## Configuration

| Key | Type | Default | Description |
|---|---|---|---|
| `metrics.outbox.queueSampleIntervalMs` | long | `5000` | Time between two reads of the queue size, in milliseconds; at least `1000`, otherwise startup fails and the message names the key |

The default of 5 seconds is the workflow executor's `pollIntervalMs` default, the closest existing poller: it too
counts a table's available, stuck and dead-lettered rows on a timer for gauges. The minimum of 1 second is the
shortest time between two of that executor's queue samples. Enablement follows `metrics.enabled` from
`vertique-micrometer-core`.

The count statement reads through the partial indexes on pending and dead-lettered rows, so its cost grows with the
backlog and the dead-letter table, not with the published history.

## Key Classes

### MicrometerOutboxModule

Abstract Dagger module. Install it explicitly. It contributes one `OutboxPublishObserver` to the
`Set<OutboxPublishObserver>` multibinding, provides `OutboxMetricsConfig`, and contributes the sampler as one
`ApplicationStartupStep` (phase `AFTER_START`, starts the timer) and one `ApplicationShutdownStep` (stops it). It
declares `MetricsConfig` as an optional binding so the adapter works with or without `MicrometerModule`.

### OutboxMetricsConfig

The `metrics.outbox` settings. Unknown keys under `metrics.outbox` are ignored.

## Module Dagger Bindings

| Type | Qualifier | Description |
|---|---|---|
| `MetricsConfig` | optional | Declared for adapter enablement checks |
| `OutboxPublishObserver` | `@IntoSet` | Micrometer publish-attempt counters |
| `OutboxMetricsConfig` | `@Singleton` | The `metrics.outbox` settings |
| `ApplicationStartupStep` | `@IntoSet` | Starts the queue-size timer in `AFTER_START` |
| `ApplicationShutdownStep` | `@IntoSet` | Stops the timer |

The application must also provide `Vertx`, `MeterRegistry` and `OutboxQueueProbe`. The observer contribution is
declared on the injectable implementation with `@RegisterIntoSet`; `vertique-codegen-dagger` emits the
`GeneratedRegistrationsModule` this module includes. `vertique-codegen-core` is a `provided` dependency, because
`@RegisterIntoSet` has source retention.

## Verification

```text
./mvnw -ntp -pl vertique-micrometer/vertique-micrometer-outbox -am verify
```

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-micrometer-core` | Provides the application `MeterRegistry` and metrics configuration when `MicrometerModule` is installed |
| `vertique-inbox-outbox-core` | Provides `OutboxPublishObserver`, `OutboxPublishCompletedEvent` and `OutboxQueueProbe` |
| `vertique-core` | Lifecycle steps, config parsing and `ConfigurationException` |
| `micrometer-core` | Supplies counters, gauges, tags and registry APIs |
