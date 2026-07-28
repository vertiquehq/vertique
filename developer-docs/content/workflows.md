---
title: Workflows, jobs, and inbox/outbox
description: Decide between durable workflow orchestration, scheduled or deferred jobs, and inbox/outbox messaging for a piece of background work, and find each capability's canonical module reference.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Workflows, jobs, and inbox/outbox

This page explains when to reach for each of Vertique's three durable-work capability families —
durable workflows, scheduled and deferred jobs, and inbox/outbox messaging — the minimum artifacts
each one needs, and how they compose. It does not repeat the constructs, SQL, or extension points
each family's own module reference already documents in full.

Unlike REST, services, and PostgreSQL persistence (see [Application model](application-model.md)
and [Persistence](persistence.md)), none of these three families publishes a starter aggregate. An
application composes exactly the artifacts it needs directly into its own Dagger component,
following the wiring each family's own module reference documents — there is no
`vertique-starter-workflow`, `vertique-starter-job`, or `vertique-starter-inbox-outbox`.

## Choose the right capability

| Capability family | What it is for | Reach for it when |
|---|---|---|
| Durable workflows | Coordinating several steps over time, potentially across systems, as one resumable, versioned instance | The work has more than one step, must survive a restart, and may wait on a signal, a timer, or a human decision |
| Scheduled and deferred jobs | Running one unit of background work on a schedule or after a delay, with retry and dead-letter handling | The work is a single handler invocation — recurring maintenance on a cron, or work deferred to a future time — not a multi-step instance |
| Inbox/outbox messaging | Recording an inbound or outbound message atomically with a business transaction — the insert commits atomically with that transaction, but is not itself deduplicated, so a business retry in a new transaction records again — while the relay then delivers a recorded outbound entry to its destination with at-least-once delivery | A business write must deduplicate an inbound message, or record an outbound side effect (a service call, a delayed job, a Kafka publish) that commits atomically with the write and is relayed to its destination afterward — the destination side should be idempotent, since inbox dedup, not outbox delivery, is where the framework guarantees exactly-once effect |

These three compose rather than compete: a durable workflow step commonly dispatches its service
call or publishes its external event through inbox/outbox, and a workflow's durable timers are
themselves backed by jobs. See [How the three compose](#how-the-three-compose) below.

## Durable workflows

`vertique-workflow-core` defines the workflow programming model: a builder DSL for describing a
workflow's steps, a registry that stores compiled plans, the SQL-free `WorkflowOperations` façade
application code calls to start, signal, cancel, and query instances, typed workflow contract
proxies, and the side-effect recorder contract that workflow steps use to reach other systems.
`vertique-workflow-engine` holds the dialect-neutral orchestration kernel underneath it — the saga
state machine, fork/join coordination, and timer and task lifecycle — and
`vertique-workflow-postgresql` is its PostgreSQL adapter: schema, Flyway migrations, and the
repositories that let an instance survive a restart.

An instance is pinned to the workflow definition version and plan hash it started under, so
registering a newer definition version never changes how an in-flight instance resumes. Reach for
a workflow when the work needs that resumability, a human task step, or several dispatched steps
coordinated as one instance — not for a single scheduled job or a single transactional side effect.

Install `vertique-workflow-core` in every application that uses workflows, and add capability
modules only as needed:

| Add | For |
|---|---|
| `vertique-workflow-postgresql` | A durable runtime that survives restarts and runs across more than one node |
| `vertique-workflow-services` | Dispatching workflow steps as service calls, recorded through the transactional outbox |
| `vertique-workflow-delayed` | Timers, signal timeouts, and task due dates |
| `vertique-workflow-tasks` | The human-task service API |
| `vertique-workflow-events` | Publishing workflow lifecycle events to external consumers, through the transactional outbox |
| `vertique-workflow-definition` | Authoring a workflow as a YAML/JSON document instead of a Java DSL |

`vertique-workflow-postgresql` includes the engine module transitively, so naming it is enough to
reach `WorkflowOperations` — no separate component entry for `vertique-workflow-engine` is needed.
Dispatching service steps or publishing events is different: both add a compile dependency on
`vertique-inbox-outbox-core` without wiring its Dagger module for you, so a workflow application
that uses either one also names the matching inbox/outbox adapter module directly — see
[How the three compose](#how-the-three-compose).

## Scheduled and deferred jobs

`vertique-job-core` defines the shared job state machine, the `JobContext` API a handler uses to
report progress and check cancellation, the `JobRepository` and `JobInterceptor` SPIs, and
`JobCoordinator`, which tracks node heartbeats and recovers orphaned executions across a cluster.
Two triggers build on it: `vertique-job-cron` schedules recurring executions from
`@CronJob`-annotated service methods or config-only entries, and `vertique-job-delayed` is a
persistent, database-backed queue for work that must run once at or after a future time, with
configurable retry and dead-letter handling.

Reach for a job when the work is a single handler invocation on a schedule or a delay — not a
multi-step instance that waits on a signal or a human decision (that is a workflow), and not
itself the mechanism for handing a transactional side effect to another system (that is
inbox/outbox, though a job handler can still use inbox/outbox internally).

`vertique-job-cron` can run without persistence: install `vertique-job-core` and
`vertique-job-cron` alone, and cron scheduling stays in memory on each node.
`vertique-job-delayed` has no such in-memory mode — it depends directly on
`vertique-job-postgresql`, so a delayed job's state is always durable and visible across the
cluster once it is enqueued.

| Add | For |
|---|---|
| `vertique-job-core` | The state machine, `JobContext`, and the coordinator every job scheduling module shares |
| `vertique-job-cron` | Recurring executions from `@CronJob` methods or config; runs in memory unless paired with `vertique-job-postgresql` |
| `vertique-job-delayed` | Deferred, durable execution with retry and dead-letter; always requires `vertique-job-postgresql` |
| `vertique-job-postgresql` | The `JobRepository` implementation backing durable cron scheduling and every delayed job |

## Inbox and outbox messaging

`vertique-inbox-outbox-core` provides `InboxService`, which deduplicates an inbound message inside
the same transaction as the business logic that handles it, and `OutboxService`, which records an
outbound side effect atomically with a business write. The `OutboxDestinationHandler` contract
lets an adapter module relay a committed row to its destination after commit;
`vertique-inbox-outbox-postgresql` is the only implementation of both write APIs and runs the
relay itself, while `vertique-inbox-outbox-services`, `vertique-inbox-outbox-delayed-job`, and
`vertique-inbox-outbox-kafka` are the destination adapters that deliver a relayed row to a service
target, a durable delayed job, or a Kafka topic.

Reach for inbox/outbox when a business transaction needs an exactly-once-effective inbound
handoff, or a side effect that must commit atomically with a write and only take effect
afterward — not for coordinating several steps over time (a workflow), and not for a recurring or
one-shot background task with no transactional pairing (a job).

`vertique-inbox-outbox-core` has no compile dependency on `services`, `job-delayed`, or Kafka —
those arrive only through the destination adapter an application actually chooses:

| Add | For |
|---|---|
| `vertique-inbox-outbox-core` | `InboxService`, `OutboxService`, and the `OutboxDestinationHandler` contract |
| `vertique-inbox-outbox-postgresql` | Persistence for both write APIs, plus the relay itself |
| `vertique-inbox-outbox-services` | Relaying to a stable service target over the event bus |
| `vertique-inbox-outbox-delayed-job` | Relaying to a durable delayed job |
| `vertique-inbox-outbox-kafka` | Relaying to a Kafka topic |

Install `vertique-inbox-outbox-core` and `vertique-inbox-outbox-postgresql` together, then add
exactly the destination adapter (or adapters) the application actually relays to.

## How the three compose

Each family names another as an ordinary compile dependency when it needs that family's
capability, but never wires the other family's Dagger module for you — an application still names
every module it needs in its own component, exactly as it does for a single family:

- **Workflow service dispatch and events use inbox/outbox.** `vertique-workflow-services` and
  `vertique-workflow-events` both depend on `vertique-inbox-outbox-core` and record their work
  through the transactional outbox. A workflow application that dispatches service steps or
  publishes workflow events also composes the matching inbox/outbox adapter module in its
  component, in addition to the workflow modules themselves — `vertique-workflow-core`'s own
  required-wiring example names `TransactionalMessagingPostgresqlModule` and
  `TransactionalMessagingServiceModule` alongside the workflow modules for exactly this reason.
- **Workflow timers use jobs.** `vertique-workflow-delayed` depends on `vertique-job-core`,
  `vertique-job-delayed`, and `vertique-job-cron` to give a workflow instance durable timers,
  signal timeouts, and task due dates; add it, and the job modules it depends on, only when a
  workflow definition actually uses those steps.
- **Each family's own PostgreSQL adapter wires the database connection layer directly, not
  through a starter.** `vertique-workflow-postgresql`, `vertique-job-postgresql`, and
  `vertique-inbox-outbox-postgresql` each depend directly on `vertique-db-postgresql` — the same
  PostgreSQL access layer the `vertique-starter-postgresql` aggregate composes for a REST or
  services application, covered in [Persistence](persistence.md) — rather than on that starter
  aggregate. All three require the application to install both `DbPostgresqlModule` and
  `DbFlywayModule` in its own Dagger component — the same two modules the PostgreSQL starter
  composes for a REST or services application — because none of the three adapter modules wires
  Flyway migration for the application; `vertique-workflow-postgresql`'s own module reference is
  explicit that applications must also include `DbPostgresqlModule` and `DbFlywayModule` for the
  connection pool its repositories and transaction runner depend on. Maven dependency scope
  differs per adapter, but that difference governs classpath availability, not Dagger wiring: only
  `vertique-workflow-postgresql` depends on `vertique-db-flyway` at compile scope, while
  `vertique-job-postgresql` and `vertique-inbox-outbox-postgresql` declare it at test scope only —
  either way, `DbFlywayModule` still has to be installed by the application itself; see each
  module's own reference for the exact wiring. An application that already uses the PostgreSQL
  starter for its own repositories composes a durable-work family's PostgreSQL adapter alongside
  it, not instead of it.

## Learn more

- [`vertique-workflow-core` module reference](../../vertique-workflow/vertique-workflow-core/src/main/resources/META-INF/vertique/module.md)
  — the DSL, registry, and `WorkflowOperations` façade.
- [`vertique-workflow-engine` module reference](../../vertique-workflow/vertique-workflow-engine/src/main/resources/META-INF/vertique/module.md)
  — the dialect-neutral orchestration kernel.
- [`vertique-workflow-postgresql` module reference](../../vertique-workflow/vertique-workflow-postgresql/src/main/resources/META-INF/vertique/module.md)
  — the durable PostgreSQL adapter.
- [`vertique-workflow-services` module reference](../../vertique-workflow/vertique-workflow-services/src/main/resources/META-INF/vertique/module.md)
  — service-dispatch steps through the transactional outbox.
- [`vertique-workflow-delayed` module reference](../../vertique-workflow/vertique-workflow-delayed/src/main/resources/META-INF/vertique/module.md)
  — durable timers, signal timeouts, and task due dates.
- [`vertique-workflow-tasks` module reference](../../vertique-workflow/vertique-workflow-tasks/src/main/resources/META-INF/vertique/module.md)
  — the human-task service API.
- [`vertique-workflow-events` module reference](../../vertique-workflow/vertique-workflow-events/src/main/resources/META-INF/vertique/module.md)
  — durable external workflow events.
- [`vertique-workflow-definition` module reference](../../vertique-workflow/vertique-workflow-definition/src/main/resources/META-INF/vertique/module.md)
  — YAML/JSON workflow authoring.
- [`vertique-job-core` module reference](../../vertique-job/vertique-job-core/src/main/resources/META-INF/vertique/module.md)
  — the shared job state machine, `JobContext`, and `JobCoordinator`.
- [`vertique-job-cron` module reference](../../vertique-job/vertique-job-cron/src/main/resources/META-INF/vertique/module.md)
  — recurring `@CronJob` scheduling.
- [`vertique-job-delayed` module reference](../../vertique-job/vertique-job-delayed/src/main/resources/META-INF/vertique/module.md)
  — the durable, retryable deferred queue.
- [`vertique-job-postgresql` module reference](../../vertique-job/vertique-job-postgresql/src/main/resources/META-INF/vertique/module.md)
  — the `JobRepository` implementation.
- [`vertique-inbox-outbox-core` module reference](../../vertique-inbox-outbox/vertique-inbox-outbox-core/src/main/resources/META-INF/vertique/module.md)
  — `InboxService`, `OutboxService`, and the destination-handler contract.
- [`vertique-inbox-outbox-postgresql` module reference](../../vertique-inbox-outbox/vertique-inbox-outbox-postgresql/src/main/resources/META-INF/vertique/module.md)
  — persistence and the relay.
- [`vertique-inbox-outbox-services` module reference](../../vertique-inbox-outbox/vertique-inbox-outbox-services/src/main/resources/META-INF/vertique/module.md)
  — the service-target destination adapter.
- [`vertique-inbox-outbox-delayed-job` module reference](../../vertique-inbox-outbox/vertique-inbox-outbox-delayed-job/src/main/resources/META-INF/vertique/module.md)
  — the delayed-job destination adapter.
- [`vertique-inbox-outbox-kafka` module reference](../../vertique-inbox-outbox/vertique-inbox-outbox-kafka/src/main/resources/META-INF/vertique/module.md)
  — the Kafka destination adapter.
- [Persistence](persistence.md)
- [Application model](application-model.md)
- [Documentation overview](index.md)

## Continue reading

- Previous: [Persistence](persistence.md)
- Next: [Security](security.md)
