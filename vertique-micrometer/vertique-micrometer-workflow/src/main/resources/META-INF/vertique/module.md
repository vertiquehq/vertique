<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Micrometer Workflow Module

> **Status:** Alpha
> **Package:** `dev.vertique.micrometer.workflow`
> **Artifact:** `vertique-micrometer-workflow`
> **Depends on:** `vertique-micrometer-core`, `vertique-workflow-core`, micrometer-core

Provides the explicit Micrometer adapter for workflow executor events and signal source outcomes. It contributes one
`WorkflowExecutorObserver` and one `SignalSourceObserver` through `MicrometerWorkflowModule`; workflow modules remain independent of
Micrometer and applications choose whether to install this adapter. The module owns no configuration
keys: it follows `metrics.enabled` through the optional `MetricsConfig` binding.

## When To Use It

Add `vertique-micrometer-workflow` when the application runs the workflow executor and wants command,
retry, lease and queue metrics. Install `MicrometerWorkflowModule` alongside `MicrometerModule` and the
application's workflow modules in the Dagger `@Component`.

The adapter may be installed without `MicrometerModule`, but the application must provide the
`MeterRegistry` binding that the adapter injects. When no `MetricsConfig` binding is present, workflow
metrics default to enabled (an absent `MetricsConfig` means enabled). When `metrics.enabled=false`, the
adapter records nothing.

## Core Concepts

The contributed observer maps every `WorkflowExecutorEvent` variant to a meter. Each counter and the
timer increments once per event; the running counts carried by deferral and transient-retry events are
not added.

| Meter | Type | Tags | Event |
|---|---|---|---|
| `vertique.workflow.commands.settled` | counter | `kind`, `outcome` | `CommandSettled` |
| `vertique.workflow.commands.duration` | timer | `kind`, `outcome` | `CommandSettled` (records the elapsed time) |
| `vertique.workflow.commands.deferred` | counter | `kind` | `CommandDeferred` |
| `vertique.workflow.commands.transient.retries` | counter | `kind` | `TransientRetry` |
| `vertique.workflow.commands.lease.expired` | counter | `kind` | `LeaseExpired` |
| `vertique.workflow.recordings.actor.absent` | counter | `kind` | `ActorAbsent` |
| `vertique.workflow.source.outcomes` | counter | `transport`, `contract`, `signal`, `definition`, `outcome` | `SignalSourceEvent` (see below) |
| `vertique.workflow.commands.available` | gauge | none | `QueueSnapshot` |
| `vertique.workflow.commands.stuck` | gauge | none | `QueueSnapshot` |
| `vertique.workflow.commands.parked` | gauge | none | `QueueSnapshot` |
| `vertique.workflow.commands.dead` | gauge | none | `QueueSnapshot` |
| `vertique.workflow.notification.queue.usage` | gauge | none | `QueueSnapshot` |

Tag values are the event's `kind` and `outcome` strings, lower-cased and truncated to 64 characters.
No instance id, definition key, payload or actor value is ever a tag. Registry failures are swallowed,
so telemetry cannot change command execution.

`SignalSourceMetricsObserver` counts every outcome a correlated signal source reports, once per event, in
`vertique.workflow.source.outcomes`. The tags are exactly `transport`, `contract`, `signal`, `definition` and
`outcome`: the outcome is the lower-cased `SignalSourceEvent.Outcome` name, each value is truncated to 64
characters, and a value the event does not carry (a null contract, signal or definition) is `none`. The source's
name, a key, a header and a payload are never tags. It follows `metrics.enabled` and is fail-open like the
executor observer.

The gauges read the latest queue snapshot; the observer holds their state and registers them on the
first snapshot. The executor emits snapshots from two places and each fills only part of the record:
the stuck-command reporter fills the four counts with a usage of `0`, and the notification listener
fills the usage with all counts `0`. A snapshot with four zero counts and a positive usage updates the
usage gauge only; a snapshot with any non-zero count and a usage of `0` updates the four count gauges
only; any other snapshot updates all five. A queue that has just drained to zero therefore keeps its
last reported counts until a snapshot with a non-zero count or a positive usage arrives.

## Key Classes

### MicrometerWorkflowModule

Abstract Dagger module. Install it explicitly to contribute one `WorkflowExecutorObserver` to the
`Set<WorkflowExecutorObserver>` multibinding. The contributed observer records counters, a timer and
queue gauges in the injected `MeterRegistry`, is fail-open, and bounds metric dimensions to protect
registry cardinality. The module declares `MetricsConfig` as an optional binding so the adapter can be
used with or without `MicrometerModule`; without that module, the application must provide the required
`MeterRegistry` binding.

### SignalSourceMetricsObserver

Public final `@Singleton` observer of signal source outcomes, contributed to the `Set<SignalSourceObserver>`
multibinding that `WorkflowSourceModule` in `vertique-workflow-services` declares. Applications normally obtain
it through the module rather than constructing it.

### WorkflowExecutorMetricsObserver

Public final `@Singleton` observer. It switches exhaustively over the sealed event hierarchy, so a new
event variant in `vertique-workflow-core` fails compilation here until it is mapped; the adapter is
versioned with core through the BOM. Applications normally obtain it through the module rather than
constructing it.

## Module Dagger Bindings

| Type | Qualifier | Description |
|---|---|---|
| `MetricsConfig` | optional | Declared for adapter enablement checks |
| `WorkflowExecutorObserver` | `@IntoSet` | Micrometer workflow executor observer |
| `SignalSourceObserver` | `@IntoSet` | Micrometer signal source outcome observer |

The simple SPI contribution is declared on the injectable implementation with `@RegisterIntoSet`.
During the provider build, `vertique-codegen-dagger` emits `GeneratedRegistrationsModule`, which this
module includes explicitly. Configuration, registry and optional bindings remain hand-written.

## Verification

```text
./mvnw -ntp -pl vertique-micrometer/vertique-micrometer-workflow -am verify
```

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-micrometer-core` | Provides the application `MeterRegistry` and metrics configuration when `MicrometerModule` is installed |
| `vertique-workflow-core` | Provides `WorkflowExecutorObserver`, the sealed `WorkflowExecutorEvent` vocabulary, and `SignalSourceObserver` with `SignalSourceEvent` |
| `micrometer-core` | Supplies counters, timers, gauges, tags and registry APIs |
