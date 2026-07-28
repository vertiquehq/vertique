---
title: Durable workflows
description: Decide when to reach for a durable workflow instead of a scheduled job or inbox/outbox message, compose the engine with jobs and inbox/outbox, and find its canonical module references.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Durable workflows

This page explains when to reach for Vertique's durable workflow engine, the minimum artifacts it
needs, and how it composes with the rest of the durable-work spine. It does not repeat the
constructs, SQL, or extension points `vertique-workflow-*`'s own module references already document
in full.

Durable workflows are one of three durable-work capability families Vertique ships, alongside
[scheduled and deferred jobs](jobs.md) and [transactional inbox/outbox messaging](inbox-outbox.md).
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
| Inbox/outbox messaging | Recording an inbound or outbound message atomically with a business transaction. Inbound, the inbox deduplicates by message id and source, so a redelivered message is reported as a duplicate and its work is never re-invoked. Outbound, the outbox insert commits atomically with the transaction but is not itself deduplicated — a business retry in a new transaction records again — and the relay then delivers each recorded entry with at-least-once delivery | A business write must deduplicate an inbound message, or record an outbound side effect (a service call, a delayed job, a Kafka publish) that commits atomically with the write and is relayed to its destination afterward — the destination side should be idempotent, since inbox dedup, not outbox delivery, is where the framework guarantees exactly-once effect |

These three compose rather than compete: a durable workflow step commonly dispatches its service
call or publishes its external event through inbox/outbox, and a workflow's durable timers are
themselves backed by jobs. See [Composing with jobs and
inbox/outbox](#composing-with-jobs-and-inboxoutbox) below. [Scheduled and deferred jobs](jobs.md)
and [Transactional inbox/outbox messaging](inbox-outbox.md) each link back to this table rather
than repeating it.

## The workflow engine and capability modules

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
[Composing with jobs and inbox/outbox](#composing-with-jobs-and-inboxoutbox).

## Composing with jobs and inbox/outbox

- **Workflow service dispatch and events use inbox/outbox.** `vertique-workflow-services` and
  `vertique-workflow-events` both depend on `vertique-inbox-outbox-core` and record their work
  through the transactional outbox. A workflow application that dispatches service steps or
  publishes workflow events also composes the matching inbox/outbox adapter module in its
  component, in addition to the workflow modules themselves — `vertique-workflow-core`'s own
  required-wiring example names `TransactionalMessagingPostgresqlModule` and
  `TransactionalMessagingServiceModule` alongside the workflow modules for exactly this reason. See
  [Transactional inbox/outbox messaging](inbox-outbox.md) for the messaging side of this
  composition.
- **Workflow timers use jobs.** `vertique-workflow-delayed` depends on `vertique-job-core`,
  `vertique-job-delayed`, and `vertique-job-cron` to give a workflow instance durable timers,
  signal timeouts, and task due dates; add it, and the job modules it depends on, only when a
  workflow definition actually uses those steps. See [Scheduled and deferred jobs](jobs.md) for the
  job side of this composition.
- **`vertique-workflow-postgresql` wires the database connection layer directly, not through a
  starter.** It depends on `vertique-db-postgresql` — the same PostgreSQL access layer the
  `vertique-starter-postgresql` aggregate composes for a REST or services application, covered in
  [Persistence](persistence.md) — rather than on that starter aggregate. The application must still
  install both `DbPostgresqlModule` and `DbFlywayModule` in its own Dagger component — the same two
  modules the PostgreSQL starter composes for a REST or services application — because
  `vertique-workflow-postgresql` does not wire Flyway migration for the application; its own module
  reference is explicit that applications must also include `DbPostgresqlModule` and
  `DbFlywayModule` for the connection pool its repositories and transaction runner depend on.
  `vertique-workflow-postgresql` depends on `vertique-db-flyway` at compile scope, so it is always
  on the classpath transitively — but Dagger wiring still requires the application to install
  `DbFlywayModule` itself either way; see the module's own reference for the exact wiring. An
  application that already uses the PostgreSQL starter for its own repositories composes the
  workflow PostgreSQL adapter alongside it, not instead of it. [Scheduled and deferred
  jobs](jobs.md) and [Transactional inbox/outbox messaging](inbox-outbox.md) each state the
  identical pattern for their own PostgreSQL adapter — including the Maven-scope difference for
  `vertique-db-flyway` that does not change the Dagger-wiring requirement.

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
- [Scheduled and deferred jobs](jobs.md)
- [Transactional inbox/outbox messaging](inbox-outbox.md)
- [Persistence](persistence.md)
- [Application model](application-model.md)
- [Documentation overview](index.md)

## Continue reading

- Previous: [Persistence](persistence.md)
- Next: [Scheduled and deferred jobs](jobs.md)
