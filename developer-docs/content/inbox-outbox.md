---
title: Transactional inbox/outbox messaging
description: Record inbound and outbound messages atomically with a business transaction, understand the framework's exactly-once effect seam, and find each module's canonical reference.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Transactional inbox/outbox messaging

This page explains when to reach for Vertique's transactional inbox/outbox messaging, the exact
delivery semantics it guarantees, and how it composes with the rest of the durable-work spine. It
does not repeat the constructs, SQL, or extension points the inbox/outbox modules' own references
already document in full.

Transactional inbox/outbox messaging is one of three durable-work capability families Vertique
ships. See [Durable workflows](workflows.md#choose-the-right-capability) for the full comparison
between durable workflows, scheduled and deferred jobs, and inbox/outbox messaging — it is not
repeated here.

## When to reach for inbox/outbox

Reach for inbox/outbox messaging when a business write must deduplicate an inbound message, or
record an outbound side effect — a service call, a delayed job, a Kafka publish — that must commit
atomically with the write and only take effect afterward. It is not for coordinating several steps
over time (that is a [durable workflow](workflows.md)), and not for a recurring or one-shot
background task with no transactional pairing (that is a [job](jobs.md), though a job handler can
still use inbox/outbox internally).

## Delivery semantics

The inbox and the outbox each guarantee something different, and the difference is the point:

- **Recording is atomic with the business transaction, on both sides.** The inbox dedup insert and
  the outbox entry insert each participate in the caller's own transaction — if the surrounding
  business write rolls back, the recorded row rolls back with it.
- **The outbox insert is not deduplicated.** Recording an outbound side effect a second time — a
  business retry that runs in a new transaction — records a second outbox row. Nothing on the
  outbox side collapses two calls that the application considers "the same" event into one.
- **The relay delivers with at-least-once delivery.** It reads only committed rows and hands each
  one to its destination; a retryable delivery failure returns the row to the pending state with an
  incremented attempt and a backoff-computed retry time, and the relay redelivers it on a later
  cycle.
- **Inbox dedup — not outbox delivery — is the framework's one exactly-once effect seam.** The
  inbox deduplicates strictly by message id and source: a redelivered pair (the same message id and
  source arriving again) is reported a duplicate, and the work supplier passed to it is never
  re-invoked. Because the outbox side is at-least-once and not deduplicated, an application that
  calls the outbox publish API more than once for what it considers one logical event gets more
  than one delivery — the destination side should still be idempotent on its own terms; see
  [Destination-side idempotency](#destination-side-idempotency) below for what each adapter
  guarantees on its own.

## Claim scopes and per-aggregate ordering

Each `OutboxDestinationHandler` declares a claim scope that decides which of its own outbox rows a
relay node is eligible to claim: `ClaimScope.all()` when every row of its destination type is
deliverable by any relay node (for example, Kafka topics — any node with the Kafka adapter
installed can publish to any topic); `ClaimScope.destinations(supplier)` when only rows whose
destination value is currently in the supplier's live set are claimable by this node (for example,
service targets or delayed-job handlers actually registered here). The relay derives its full claim
eligibility (`RelayCapabilities`) from the registered handler set at startup — a destination type
with no registered scope claims nothing, never everything.

Within one aggregate, the relay preserves order: while one outbox entry for a given aggregate id is
being delivered, later entries for the same aggregate are excluded from claim until it finishes,
while different aggregates may proceed concurrently. A consequence worth knowing: a row of an
unregistered destination type sitting at the head of its aggregate's queue blocks younger,
otherwise-claimable siblings of the same aggregate until a handler for that type is registered —
intentional ordering behavior, not a bug.

## Relay delivery strategies

The PostgreSQL relay supports two claim strategies, configured via
`inboxOutbox.relay.strategy`: `LISTEN_NOTIFY` (the default) has the relay listen for a
PostgreSQL notification so a newly inserted outbox row is claimed close to immediately, with
periodic polling still running underneath as a safety net for a missed notification or a dropped
connection; `POLLING` claims purely on a fixed-interval timer. When `LISTEN_NOTIFY` is configured
but the notification channel becomes unavailable, the relay falls back to polling-only mode
transparently, with no manual intervention required.

## Retention

Published and dead-lettered outbox rows are not kept forever:
`inboxOutbox.cleanup.publishedRetentionDays` (default 7) and
`inboxOutbox.cleanup.deadLetterRetentionDays` (default 30) bound how long `PUBLISHED` and
`DEAD_LETTER` rows survive, and `inboxOutbox.cleanup.inboxRetentionDays` (default 30) bounds how
long processed inbox rows are kept. Cleanup and stale-lease recovery run as cluster-singleton cron
jobs rather than a per-node timer — `cron.jobs.outbox-cleanup` (default every 6 hours) and
`cron.jobs.outbox-stale-lease-recovery` (default every 30 seconds) — so an application installs
`vertique-job-cron`'s persistent (`CronPersistenceModule`) configuration, not the in-memory one, for
this maintenance to run at all: installing `TransactionalMessagingPostgresqlModule` without it fails
at Dagger code generation, not silently at runtime.

## Destination-side idempotency

Each destination adapter documents its own delivery guarantee, and none of them upgrades it to
exactly-once on its own:

- The **`vertique-inbox-outbox-services`** adapter delivers to a stable service target over an
  event bus request/reply — at-least-once handoff; the service should be idempotent, or dedup
  inbound calls itself with the inbox.
- The **`vertique-inbox-outbox-delayed-job`** adapter enqueues a durable delayed job once the
  business transaction has committed; the job itself then runs under the delayed-job module's own
  retry and dead-letter handling (see [Scheduled and deferred jobs](jobs.md)).
- The **`vertique-inbox-outbox-kafka`** adapter publishes to a topic, keyed by the aggregate id when
  one is present so records for the same aggregate route to the same partition — at-least-once;
  consumers that need an exactly-once effect should dedup with the inbox on their own side.

The framework's own exactly-once guarantee is the inbox dedup seam described above — an adapter's
at-least-once delivery is expected behavior, not a defect to work around.

## Composing with adapters and PostgreSQL

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
exactly the destination adapter (or adapters) the application actually relays to. There is no
`vertique-starter-inbox-outbox`; an application composes these artifacts directly into its own
Dagger component.

`vertique-inbox-outbox-postgresql` wires the database connection layer directly, not through a
starter. It depends on `vertique-db-postgresql` — the same PostgreSQL access layer the
`vertique-starter-postgresql` aggregate composes for a REST or services application, covered in
[Persistence](persistence.md) — rather than on that starter aggregate. The application must still
install both `DbPostgresqlModule` and `DbFlywayModule` in its own Dagger component — the same two
modules the PostgreSQL starter composes for a REST or services application — because
`vertique-inbox-outbox-postgresql` does not wire Flyway migration for the application.
`vertique-inbox-outbox-postgresql` declares `vertique-db-flyway` at test scope only — the same
test-scope-only pattern `vertique-job-postgresql` follows, and unlike
`vertique-workflow-postgresql`'s compile-scope dependency — but that difference governs classpath
availability, not the Dagger-wiring requirement: the application still installs `DbFlywayModule`
itself either way. An application that already uses the PostgreSQL starter for its own repositories
composes the inbox/outbox PostgreSQL adapter alongside it, not instead of it. See [Durable
workflows](workflows.md#composing-with-jobs-and-inboxoutbox) for the identical pattern stated for
the workflow and job adapters.

## Learn more

- [`vertique-inbox-outbox-core` module reference](../../vertique-inbox-outbox/vertique-inbox-outbox-core/src/main/resources/META-INF/vertique/module.md)
  — `InboxService`, `OutboxService`, and the destination-handler contract.
- [`vertique-inbox-outbox-postgresql` module reference](../../vertique-inbox-outbox/vertique-inbox-outbox-postgresql/src/main/resources/META-INF/vertique/module.md)
  — persistence, claim eligibility, and the relay.
- [`vertique-inbox-outbox-services` module reference](../../vertique-inbox-outbox/vertique-inbox-outbox-services/src/main/resources/META-INF/vertique/module.md)
  — the service-target destination adapter.
- [`vertique-inbox-outbox-delayed-job` module reference](../../vertique-inbox-outbox/vertique-inbox-outbox-delayed-job/src/main/resources/META-INF/vertique/module.md)
  — the delayed-job destination adapter.
- [`vertique-inbox-outbox-kafka` module reference](../../vertique-inbox-outbox/vertique-inbox-outbox-kafka/src/main/resources/META-INF/vertique/module.md)
  — the Kafka destination adapter.
- [Durable workflows](workflows.md)
- [Scheduled and deferred jobs](jobs.md)
- [Persistence](persistence.md)
- [Application model](application-model.md)
- [Documentation overview](index.md)

## Continue reading

- Previous: [Scheduled and deferred jobs](jobs.md)
- Next: [Security](security.md)
