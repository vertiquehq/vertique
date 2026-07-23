<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Inbox/Outbox Delayed Job Module

> **Status:** Implemented
> **Package:** `dev.vertique.inboxoutbox.delayedjob`
> **Artifact:** `vertique-inbox-outbox-delayed-job`
> **Depends on:** inbox-outbox-core, job-delayed

Delayed job adapter for Transactional Messaging. Provides two components: `TransactionalDelayedJobPublisher` (authoring — lets application code record a typed delayed-job side-effect without constructing a raw `OutboxEntry`) and `DelayedJobOutboxDestinationHandler` (relay — resolves durable delayed-job target ids and enqueues the job only after the business transaction commits).

---

## Package Layout

| Package | Contents |
|---------|----------|
| `dev.vertique.inboxoutbox.delayedjob` | `TransactionalDelayedJobPublisher`, `DelayedJobOutboxDestinationHandler`, `TransactionalMessagingDelayedJobModule` |

---

## Key Classes

### `TransactionalDelayedJobPublisher`

Typed authoring API for recording transactional delayed-job side-effects. Works with typed `DelayedJobClient<P>` contract interfaces. Resolves the durable delayed-job target id and effective defaults at write time via `DelayedJobTargetResolver`, snapshots them into the outbox row, and calls `OutboxService.publish()` within the caller's transaction.

```java
@Inject TransactionalDelayedJobPublisher txJobPublisher;

pool.withTransaction(tx ->
    orderRepository.save(order, tx)
        .compose(v -> txJobPublisher.publish(
            DeliverWebhookJob.class,
            new WebhookPayload(order.id()),
            tx))
);
```

**Snapshot semantics:** At the moment `publish()` is called, `TransactionalDelayedJobPublisher` resolves the effective queue, priority, `maxAttempts`, and `scheduledAt` from `DelayedJobTargetResolver` and stores them as snapshot data in `OutboxEntry.headers`. The relay reads these snapshots at publish time and does not re-resolve them from contract or config defaults. Later config changes do not affect already-recorded outbox rows.

**`publish()` overloads:**

| Method | Description |
|--------|-------------|
| `publish(Class<C> contract, P payload, SqlClient tx)` | Immediate; uses contract defaults |
| `publish(Class<C> contract, P payload, Instant runAt, SqlClient tx)` | Scheduled for a specific time |
| `publish(Class<C> contract, P payload, Duration delay, SqlClient tx)` | Delayed by a duration from now |

Returns `Future<Long>` carrying the outbox entry id. Does not return the eventual job execution UUID because the job is not enqueued until the relay fires after commit.

### `DelayedJobOutboxDestinationHandler`

`OutboxDestinationHandler` implementation for `DestinationType.DELAYED_JOB`. Registered by `TransactionalMessagingDelayedJobModule` into the `Set<OutboxDestinationHandler>` multibinding.

**Claim scope:** Returns `ClaimScope.destinations(DelayedJobTargetResolver::supportedTargetIds)` from `claimScope()`. This node claims only `DELAYED_JOB` outbox rows whose `destination` value is present in the resolver's supported target id set — i.e., delayed-job handlers registered on this node. Rows for targets not reachable here are left for another node that hosts the relevant handler.

At relay time:
1. Reads `destination` (durable delayed-job target id) from the `OutboxEnvelope`.
2. Reads snapshotted queue, priority, `maxAttempts`, and `runAt` from `envelope.record().headers()`.
3. Resolves the current handler address via `DelayedJobTargetResolver`.
4. If the target id is not resolvable, returns `OutboxPublishResult.unresolvable()`.
5. Calls `DelayedJobService.enqueue(job)` — standalone enqueue (not transactional), because the relay only sees committed outbox rows and the business transaction has already committed.
6. On successful enqueue: returns `OutboxPublishResult.success()`.
7. On transient enqueue failure: returns `OutboxPublishResult.retryable(...)`.

**Snapshot priority:** Queue, priority, and `maxAttempts` always come from the snapshot stored on the outbox row. The adapter never re-reads `@DelayedJobContract` defaults or config overrides for already-recorded rows. This guarantees that delayed-job behavior is determined at write time, not at relay time.

**`scheduledAt` handling:** If `scheduledAt` is present in the outbox row, the adapter sets `DelayedJob.runAt` to that value. The job becomes eligible for execution after that time. Jobs with no `scheduledAt` are immediately claimable by the poller.

---

## Extension Points

None beyond the `OutboxDestinationHandler` SPI. `DelayedJobOutboxDestinationHandler` is the concrete extension for `DELAYED_JOB` destinations.

---

## Dagger Wiring

```java
@Module
public abstract class TransactionalMessagingDelayedJobModule {

    @Provides @IntoSet
    static OutboxDestinationHandler delayedJobHandler(
            DelayedJobOutboxDestinationHandler handler) {
        return handler;
    }

    @Provides @Singleton
    static TransactionalDelayedJobPublisher transactionalJobPublisher(
            DelayedJobTargetResolver resolver,
            OutboxService outboxService) {
        return new TransactionalDelayedJobPublisher(resolver, outboxService);
    }
}
```

Include alongside `TransactionalMessagingPostgresqlModule` and `DelayedJobModule`:

```java
@Component(modules = {
    VertxModule.class,
    DispatchModule.class,
    DbPostgresqlModule.class,
    DbFlywayModule.class,
    DelayedJobModule.class,
    TransactionalMessagingPostgresqlModule.class,
    TransactionalMessagingDelayedJobModule.class,
    AppModule.class
})
public interface AppComponent { ... }
```

---

## Snapshot Data Format

The adapter stores snapshot data in `OutboxEntry.headers` under well-known keys:

| Key | Value |
|-----|-------|
| `dj-queue` | Effective queue name at write time |
| `dj-priority` | Effective priority at write time |
| `dj-max-attempts` | Effective `maxAttempts` at write time |
| `dj-run-at` | ISO-8601 scheduled time, if `scheduledAt` was provided |

---

## Related ADRs

- ADR-0082: Adapter-Owned Claim Eligibility — establishes that each `OutboxDestinationHandler` declares its own `ClaimScope` instead of contributing capability sets via qualified multibindings.
