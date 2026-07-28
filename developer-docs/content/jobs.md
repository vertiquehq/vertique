---
title: Scheduled and deferred jobs
description: Decide between a cron-scheduled job and a delayed job, choose in-memory or PostgreSQL-backed operation, and find each module's canonical reference.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Scheduled and deferred jobs

This page explains when to reach for Vertique's scheduled and deferred job modules, the minimum
artifacts a job needs, and how it composes with the rest of the durable-work spine. It does not
repeat the constructs, SQL, or extension points the job modules' own references already document in
full.

Scheduled and deferred jobs are one of three durable-work capability families Vertique ships. See
[Durable workflows](workflows.md#choose-the-right-capability) for the full comparison between
durable workflows, scheduled and deferred jobs, and inbox/outbox messaging — it is not repeated
here.

## What a job is here

`vertique-job-core` defines the shared job state machine, the `JobContext` API a handler uses to
report progress and check cancellation, the `JobRepository` and `JobInterceptor` SPIs, and
`JobCoordinator`, which tracks node heartbeats and recovers orphaned executions across a cluster.
Two triggers build on it: `vertique-job-cron` schedules recurring executions from
`@CronJob`-annotated service methods or config-only entries, and `vertique-job-delayed` is a
persistent, database-backed queue for work that must run once at or after a future time, with
configurable retry and dead-letter handling.

Reach for a job when the work is a single handler invocation on a schedule or a delay — not a
multi-step instance that waits on a signal or a human decision (that is a [durable
workflow](workflows.md)), and not itself the mechanism for handing a transactional side effect to
another system (that is [inbox/outbox messaging](inbox-outbox.md), though a job handler can still
use inbox/outbox internally).

`vertique-job-cron` can run without persistence: install `vertique-job-core` and `vertique-job-cron`
alone, and cron scheduling stays in memory on each node. `vertique-job-delayed` has no such
in-memory mode — it depends directly on `vertique-job-postgresql`, so a delayed job's state is
always durable and visible across the cluster once it is enqueued. There is no
`vertique-starter-job`; an application composes exactly these artifacts directly into its own
Dagger component.

| Add | For |
|---|---|
| `vertique-job-core` | The state machine, `JobContext`, and the coordinator every job scheduling module shares |
| `vertique-job-cron` | Recurring executions from `@CronJob` methods or config; runs in memory unless paired with `vertique-job-postgresql` |
| `vertique-job-delayed` | Deferred, durable execution with retry and dead-letter; always requires `vertique-job-postgresql` |
| `vertique-job-postgresql` | The `JobRepository` implementation backing durable cron scheduling and every delayed job |

## Cron scheduling

Mark a service implementation method with `@CronJob` to register a recurring job — place the
annotation on the implementation method, not the contract interface. A cron expression uses a
6-field format, `second minute hour day-of-month month day-of-week`, not the 5-field Unix cron
format: for example, `0 0 8 * * *` fires daily at 08:00 in the job's configured timezone (`UTC` by
default). A config-only job — an entry with no matching `@CronJob` annotation — registers the same
way through a named entry under the `cron.jobs` config subtree (for example,
`cron.jobs.weekly-report`), with a `target` of either `service:{stableServiceTargetId}` or
`eventbus:{eventBusAddress}`.

`SINGLE_INSTANCE` execution mode runs a job on exactly one node per cluster per fire time: every
node races to insert an execution row for the same job id and scheduled time, and a unique index
lets exactly one node win the race — every other node skips dispatch for that fire.
`SINGLE_INSTANCE` requires a `JobRepository` (install `vertique-job-postgresql`) and the `SKIP`
overlap policy; it is not available in the in-memory-only configuration.

Misfire handling recovers fires missed while every node was down. It only applies with a
`JobRepository` and a persisted schedule that already recorded a previous fire time; without a
repository, or with per-job tracking disabled, a job always behaves as `MisfirePolicy.SKIP`. Three
misfire policies are available: `FIRE_NOW` executes only the single most recent missed fire (the
default for `SINGLE_INSTANCE`); `SKIP` ignores every missed fire and waits for the next scheduled
tick (the default for `EVERY_INSTANCE`); `FIRE_ALL` executes every missed fire in sequence, oldest
to newest, capped at 100 fires.

## Delayed and deferred execution

`vertique-job-delayed` is a persistent, database-backed queue for work that must run once, at or
after a future time. Application code enqueues a job descriptor — only a handler name is required —
through the delayed-job service, either standalone or inside an existing transaction: the enqueue
insert can participate in the caller's transaction, so it commits or rolls back with the business
write it accompanies. A poller claims eligible rows with `FOR UPDATE SKIP LOCKED`, dispatches each
over the event bus to the handler method, and applies the configured backoff on failure — fixed,
linear, or exponential — up to a maximum attempt count before moving the row to a dead-letter state.
A future-scheduled job uses the same enqueued state as immediate work, just with a future eligible
time, so the one claim query picks up both.

## Typed job contracts and generated proxies

A typed job contract separates the enqueue API from the execution logic using a pair of linked
interfaces:

```java
@DelayedJobContract(name = "deliver-webhook", maxAttempts = 2)
public interface DeliverWebhookJob extends DelayedJobClient<WebhookPayload> {}

@Singleton
class DeliverWebhookJobImpl
        implements DelayedJobExecutor<WebhookPayload, DeliverWebhookJob> {

    private final WebhookClient webhookClient;

    @Inject
    DeliverWebhookJobImpl(WebhookClient webhookClient) {
        this.webhookClient = webhookClient;
    }

    @Override
    public Future<Void> execute(WebhookPayload payload, JobContext ctx) {
        ctx.logger().info("Delivering webhook for payment {}", payload.paymentId());
        return webhookClient.notify(payload.paymentId());
    }
}
```

`@DelayedJobContract` names the contract and its default enqueue parameters (`maxAttempts`,
`queue`, `priority`) on the client extension interface; `DelayedJobExecutor` supplies the execution
logic and is discovered at startup from its own generic type arguments, with no separate
registration call needed. `DelayedJobClientFactory.create(...)` returns a proxy for the client
interface: at runtime it first looks for a generated static proxy produced by the
`vertique-codegen-delayed-job` annotation processor, and falls back to a JDK dynamic proxy only when
no generated class is found; a generated class that is present but broken throws
`IllegalStateException` rather than silently falling back. Contribute the executor through the
`@DelayedJobs` Dagger multibinding, and read or override its effective queue, priority, and maximum
attempts at runtime through `DelayedJobTargetResolver`.

## PostgreSQL adapter composition

`vertique-job-postgresql` wires the database connection layer directly, not through a starter. It
depends on `vertique-db-postgresql` — the same PostgreSQL access layer the
`vertique-starter-postgresql` aggregate composes for a REST or services application, covered in
[Persistence](persistence.md) — rather than on that starter aggregate. The application must still
install both `DbPostgresqlModule` and `DbFlywayModule` in its own Dagger component — the same two
modules the PostgreSQL starter composes for a REST or services application — because
`vertique-job-postgresql` does not wire Flyway migration for the application. Its Flyway migrations
live at `classpath:db/migration/job`, so `flyway.locations` must list that path alongside the
application's own migration location for them to run.

`vertique-job-postgresql` declares `vertique-db-flyway` at test scope only — unlike
`vertique-workflow-postgresql`, which declares it at compile scope — but that difference governs
classpath availability, not the Dagger-wiring requirement: the application still installs
`DbFlywayModule` itself either way. An application that already uses the PostgreSQL starter for its
own repositories composes the job PostgreSQL adapter alongside it, not instead of it. See [Durable
workflows](workflows.md#composing-with-jobs-and-inboxoutbox) for the identical pattern stated for
the workflow and inbox/outbox adapters.

## Learn more

- [`vertique-job-core` module reference](../../vertique-job/vertique-job-core/src/main/resources/META-INF/vertique/module.md)
  — the shared job state machine, `JobContext`, and `JobCoordinator`.
- [`vertique-job-cron` module reference](../../vertique-job/vertique-job-cron/src/main/resources/META-INF/vertique/module.md)
  — recurring `@CronJob` scheduling, misfire recovery, and `SINGLE_INSTANCE` leader election.
- [`vertique-job-delayed` module reference](../../vertique-job/vertique-job-delayed/src/main/resources/META-INF/vertique/module.md)
  — the durable, retryable deferred queue and typed job contracts.
- [`vertique-job-postgresql` module reference](../../vertique-job/vertique-job-postgresql/src/main/resources/META-INF/vertique/module.md)
  — the `JobRepository` implementation.
- [Durable workflows](workflows.md)
- [Transactional inbox/outbox messaging](inbox-outbox.md)
- [Persistence](persistence.md)
- [Application model](application-model.md)
- [Documentation overview](index.md)

## Continue reading

- Previous: [Durable workflows](workflows.md)
- Next: [Transactional inbox/outbox messaging](inbox-outbox.md)
