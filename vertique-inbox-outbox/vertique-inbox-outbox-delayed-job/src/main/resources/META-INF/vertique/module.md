<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Inbox/Outbox Delayed Job Module

> **Status:** Stable
> **Package:** `dev.vertique.inboxoutbox.delayedjob`
> **Artifact:** `vertique-inbox-outbox-delayed-job`
> **Depends on:** inbox-outbox-core, job-delayed

Delayed job adapter for Transactional Messaging. Provides two components: `TransactionalDelayedJobPublisher` (authoring — lets application code record a typed delayed-job side-effect without constructing a raw `OutboxEntry`) and `DelayedJobOutboxDestinationHandler` (relay — resolves durable delayed-job target ids and enqueues the job only after the business transaction commits).

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

**Snapshot semantics:** At the moment `publish()` is called, `TransactionalDelayedJobPublisher` resolves the effective queue, priority, and `maxAttempts` from `DelayedJobTargetResolver` and records them as a `DelayedJobControl` snapshot on `OutboxEntry.delayedJob()`. The relay reads that snapshot at publish time and does not re-resolve it from contract or config defaults, so later config changes do not affect already-recorded outbox rows. `scheduledAt` is *not* part of the snapshot — it is a first-class field of the outbox entry. Application `headers` are left untouched: they carry no scheduling data and are not forwarded to the job.

**`publish()` overloads:**

| Method | Description |
|--------|-------------|
| `publish(Class<? extends DelayedJobClient<P>> contract, P payload, SqlClient tx)` | Eligible as soon as the transaction commits; uses contract defaults |
| `publish(Class<? extends DelayedJobClient<P>> contract, P payload, Instant scheduledAt, SqlClient tx)` | Scheduled for a specific time; a `null` `scheduledAt` behaves like the three-argument form; the entry is in scope `default` |
| `publish(Class<? extends DelayedJobClient<P>> contract, P payload, @Nullable Instant scheduledAt, String scopeKey, SqlClient tx)` | As the four-argument form, with the entry in the given scope |

There is no duration-based overload — pass `Instant.now().plus(delay)` to the scheduled form. There is deliberately no four-argument overload that takes a scope key in place of `scheduledAt`, so `publish(contract, payload, null, tx)` stays unambiguous; pass the scope through the five-argument form.

**Scope:** A job published without a scope key is in scope `default`. The five-argument form records the entry in the scope you pass, and the job that the relay later enqueues from that row takes the same scope:

```java
pool.withTransaction(tx ->
    orderRepository.save(order, tx)
        .compose(v -> txJobPublisher.publish(
            DeliverWebhookJob.class,
            new WebhookPayload(order.id()),
            null,
            "acme",
            tx))
);
```

A `null` or invalid `scopeKey` (anything `ScopeKey` rejects, such as a blank value or one longer than 255 code points) returns a failed future carrying an `IllegalArgumentException` whose message does not repeat the key. Nothing is written in that case.

- **A label, not authorization.** The scope key partitions work; it grants nothing. It is not checked when you publish, when a job is looked up by id, or when a handler runs under a service principal. A handler that needs access control authorizes the access itself.
- **Row scope and `ScopeContext` are independent.** The scope of the job comes from the outbox row alone, never from envelope headers or metadata, and publishing does not read the ambient `ScopeContext`. A handler that partitions its data chooses one source, `JobContext.scopeKey()` or `ScopeContext`. When both are present it compares them and fails on a mismatch.

Returns `Future<Long>` carrying the outbox entry id. Does not return the eventual job execution UUID because the job is not enqueued until the relay fires after commit.

### `DelayedJobOutboxDestinationHandler`

`OutboxDestinationHandler` implementation for `DestinationType.DELAYED_JOB`. Registered by `TransactionalMessagingDelayedJobModule` into the `Set<OutboxDestinationHandler>` multibinding.

**Claim scope:** Returns `ClaimScope.destinations(DelayedJobTargetResolver::supportedTargetIds)` from `claimScope()`. This node claims only `DELAYED_JOB` outbox rows whose `destination` value is present in the resolver's supported target id set — i.e., delayed-job handlers registered on this node. Rows for targets not reachable here are left for another node that hosts the relevant handler.

At relay time:
1. Reads `destination` (durable delayed-job target id) from the `OutboxEnvelope` and resolves the current handler via `DelayedJobTargetResolver`.
2. If the target id is not registered on this node, returns `OutboxPublishResult.unresolvable(message)` — the relay backs off and retries once the handler is deployed.
3. Reads the snapshotted queue, priority, and `maxAttempts` from the `DelayedJobControl` in `envelope.metadata().delivery().delayedJob()`. If that snapshot is absent, returns `OutboxPublishResult.permanent(message, cause)` — an authoring bug that retrying cannot fix.
4. Builds the `DelayedJob`, taking `runAt` from `envelope.scheduledAt()`, its scope from `envelope.scopeKey()` (never from headers or metadata), and forwarding the durable propagation context from `envelope.metadata().context()` verbatim into `DelayedJob.metadata()`, so context captured at publish time survives the relay hop.
5. Calls `DelayedJobService.enqueue(job)` — standalone enqueue (not transactional), because the relay only sees committed outbox rows and the business transaction has already committed.
6. On successful enqueue: returns `OutboxPublishResult.success()`.
7. If the enqueue is rejected as invalid (an `IllegalArgumentException`, e.g. the handler name fails `DelayedJobService` validation): returns `OutboxPublishResult.permanent(message, cause)`.
8. On any other enqueue failure — typically transient infrastructure trouble: returns `OutboxPublishResult.retryable(message, cause)`.

The returned `Future` always completes successfully with an `OutboxPublishResult`; the handler never propagates a failed future.

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
}
```

The module contributes only the destination handler. `TransactionalDelayedJobPublisher` needs no
`@Provides` method: it is a `@Singleton` with an `@Inject` constructor, so including this module in
the component is enough to inject it. Construct neither class directly — both constructors are
package-private and exist for Dagger.

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

The write-time snapshot is a `DelayedJobControl` recorded on `OutboxEntry.delayedJob()`. It is
persisted in the outbox row's structured `metadata` column, under `delivery.delayedJob`:

```json
{
  "delivery": {
    "delayedJob": { "queue": "default", "priority": 0, "maxAttempts": 3 }
  },
  "context": { "…": "durable propagation context" }
}
```

| Field | Value |
|-------|-------|
| `queue` | Effective queue name at write time |
| `priority` | Effective priority at write time |
| `maxAttempts` | Effective `maxAttempts` at write time |

Two things deliberately live outside this snapshot:

- **`scheduledAt`** is a first-class field of the outbox entry, not a snapshot field. The relay reads
  it from `envelope.scheduledAt()` and uses it as the job's `runAt`.
- **`headers`** are application/transport-only. The adapter neither reads scheduling data from them
  nor forwards them to the enqueued job; durable context reaches the job through
  `metadata.context` instead. No application header of the outbox entry reaches the job — a
  `DelayedJob` has no header field, and the headers are copied into neither its payload nor its
  metadata.
