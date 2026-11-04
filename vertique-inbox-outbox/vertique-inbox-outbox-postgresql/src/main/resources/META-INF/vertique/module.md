<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Inbox/Outbox PostgreSQL Module

> **Status:** Stable
> **Package:** `dev.vertique.inboxoutbox.postgresql`
> **Artifact:** `vertique-inbox-outbox-postgresql`
> **Depends on:** `dev.vertique:vertique-inbox-outbox-core`, `dev.vertique:vertique-db-postgresql`
> (which brings `dev.vertique:vertique-db-core` for `DbSchemas` and `MigrationDefinition`),
> `dev.vertique:vertique-core`, `dev.vertique:vertique-context`, `dev.vertique:vertique-deploy`,
> `dev.vertique:vertique-logging`, `dev.vertique:vertique-services`, `dev.vertique:vertique-job-cron`
> (the maintenance cron jobs). `dev.vertique:vertique-db-flyway` is test-scope here; the application
> supplies the migration runner.

PostgreSQL persistence and relay engine for Transactional Messaging. Provides `DefaultInboxService` and `DefaultOutboxService` (the only implementations of the core write APIs), `PgInboxOutboxRepository` (claim/lease queries, stale recovery), `OutboxRelay` (relay verticle with POLLING and LISTEN_NOTIFY strategies), `PgOutboxDeadLetterService` (the PostgreSQL implementation of the core `OutboxDeadLetterService` administration facade), and the Flyway migrations that create the `inbox` and `outbox` tables and the dead-letter administration objects. `InboxOutboxMigrationsModule` declares those migrations as the `inbox-outbox` migration instance for named Flyway instances, and the optional `db.schemas.inbox-outbox` setting places the tables in one schema for the migrations, the repository and the administration service.

---

## Key Classes

### `PgInboxOutboxRepository`

PostgreSQL repository that owns the inbox and outbox SQL: inserts, claim queries, state transitions, lease recovery and retention cleanup. The table DDL and the `LISTEN/NOTIFY` trigger belong to the packaged migration (see [Database Schema](#database-schema)), and the `LISTEN` connection belongs to `OutboxRelay`. Extends `PgSqlRepository`.

**Constructors:**

```java
@Inject
public PgInboxOutboxRepository(Pool pool, PgDbExceptionMapper exceptionMapper)

public PgInboxOutboxRepository(Pool pool, PgDbExceptionMapper exceptionMapper, DbSchemas schemas)
```

The two-argument constructor leaves every table unqualified, so `inbox` and `outbox` resolve through the connection's default schema. The three-argument constructor qualifies every inbox and outbox table reference, in every statement, with the schema that `schemas` maps to `inbox-outbox`; with no `inbox-outbox` entry its SQL is the same as the two-argument constructor's. Statements are built once at construction. The mapping is validated then: a schema that is not a single valid PostgreSQL identifier (or is reserved by PostgreSQL) throws `IllegalArgumentException` before any query runs (see `PgTableNames` in `dev.vertique:vertique-db-postgresql`). Column qualifiers inside the SQL, such as `outbox.id`, name the table within the statement and are never schema-qualified. SQL in this document is shown with unqualified table names.

Mapped or not, `tryInsert` and `insert` run on the `SqlClient` the caller passes, so the inbox or outbox row commits or rolls back with the application's own writes.

**Inbox operations:**
- `tryInsert(String messageId, String source, SqlClient tx)` — inserts the inbox row in the caller's transaction; returns `true` for new messages, `false` when the `(messageId, source)` pair was already recorded
- `cleanup(int retentionDays, int batchSize)` — batch-deletes inbox rows older than the retention threshold

**Outbox operations.** Every `mark*` method carries a `claimed_by` owner guard and returns
`Future<Boolean>`: `false` means this node no longer owns the row, so a node whose lease was reclaimed
cannot complete a row another node now owns.
- `insert(OutboxEntry entry, OutboxMetadata metadata, UUID carrierId, SqlClient tx)` — inserts the outbox row in the caller's transaction; returns the assigned id. The row's `scope_key` is `entry.scopeKey()`, stored as given
- `claimBatch(int batchSize, String claimedBy, RelayCapabilities capabilities)` — short transaction: selects eligible `PENDING` rows with `FOR UPDATE SKIP LOCKED` and marks them `PROCESSING`
- `markPublished(long entryId, String claimedBy)` — transitions an owned row to `PUBLISHED`
- `markRetry(long entryId, String claimedBy, int newAttempt, Instant availableAt, String lastError, String errorType)` — transitions an owned row back to `PENDING` with the incremented attempt and backoff
- `markDeadLetter(long entryId, String claimedBy, String lastError, String errorType)` — transitions an owned row to `DEAD_LETTER`
- `markUnresolvable(long entryId, String claimedBy, Duration delay)` — returns an owned row to `PENDING` after the delay without incrementing attempt (the `unresolvable` outcome)
- `reclaimStale(Duration leaseTimeout)` — returns stale `PROCESSING` rows to `PENDING` without incrementing attempt; deliberately unguarded, because its job is to take rows from nodes that are gone
- `cleanupPublished(int retentionDays, int batchSize)` — batch-deletes `PUBLISHED` rows past retention
- `cleanupDeadLetter(int retentionDays, int batchSize)` — batch-deletes `DEAD_LETTER` rows past retention. The candidate select is ordered by `id` and locks its rows with `FOR UPDATE SKIP LOCKED`, so a row another transaction holds (for example one an administrative command is requeuing) is skipped and left for a later run; the delete re-checks `state = 'DEAD_LETTER'` and the age cutoff, so a row that is no longer an expired dead letter is never removed. `cleanupPublished` is unchanged

**Claim eligibility filter (applied in SQL):**
- `state = 'PENDING'`
- `available_at <= NOW()`
- `scheduled_at IS NULL OR scheduled_at <= NOW()`
- Dynamic destination-eligibility disjunction built from `RelayCapabilities.byType()` — see below.

**Dynamic destination-eligibility disjunction:** `claimBatch` builds the capability filter at call time from `RelayCapabilities.byType()` (a `Map<DestinationType, ClaimScope>`). Each registered scope contributes one SQL predicate:

| Scope | SQL predicate contributed |
|-------|--------------------------|
| `ClaimScope.All` | `(o.destination_type = $n)` |
| `ClaimScope.Destinations` (non-empty snapshot) | `(o.destination_type = $n AND o.destination = ANY($m))` |
| `ClaimScope.Destinations` (empty snapshot) | _(no predicate — claims nothing for that type)_ |
| No registered scopes, or all Destinations scopes yielded empty sets | `FALSE` — zero rows claimed |

All destination-type ids and target values are bound as prepared-statement parameters (`$n` / `ANY($m)`) — never concatenated into the SQL string. The fixed prefix binds `$1` (batchSize) and `$2` (claimedBy); dynamic parameters start at `$3`. The `Destinations` supplier is evaluated once per claim cycle; validation rules are enforced by `buildClaimEligibility` (see `ClaimScopeException` section below).

The read path uses `DestinationType.of(...)` (lenient factory) when mapping rows from the database — an unrecognised but well-formed `destination_type` value maps cleanly rather than throwing.

**Aggregate head-of-line ordering:** When one entry for a given `aggregateId` is in `PROCESSING`, later entries for the same aggregate are excluded from claim. Different aggregates may proceed concurrently.

**Sharp edge — unregistered destination types:** A destination type with no registered handler/scope stays `PENDING` indefinitely. Because of the per-aggregate head-of-line subquery, an unclaimable-type row at an aggregate HEAD (the lowest id among `PENDING`/`PROCESSING` for that `aggregate_id`) blocks younger claimable siblings of the same aggregate until that type's handler and claim scope are registered. This is intentional ordering behavior, not a bug.

**Scope:** `insert` writes `OutboxEntry.scopeKey()` to `outbox.scope_key`, and every read maps it to `OutboxRecord.scopeKey()`, including the rows `claimBatch` returns after a retry or a stale-lease reclaim. The claim predicate, the claim ordering, the lease recovery and the retention cleanup do not read it, so a relay claims rows of every scope. The head-of-line ordering above keys on `aggregate_id` alone and is therefore global across scopes: two scopes that reuse an `aggregateId` order against each other. Keep aggregate ids unique across scopes, for example `<scope>:<id>`.

### `PgOutboxDeadLetterService`

PostgreSQL implementation of `OutboxDeadLetterService` (the facade is owned by `dev.vertique:vertique-inbox-outbox-core`, package `dev.vertique.inboxoutbox.admin`) and of the receipt retention cleanup `OutboxAdminOperationCleanup`. It lists, reads and administers dead-lettered outbox entries: `retry` requeues one, `discard` deletes one, and both record a receipt. The facade performs no authorization, takes the actor as an explicit argument and exposes no endpoint: a binding is only the persistence half, and an administration adapter has to be installed to use it. It administers the configured store as a whole and never filters on, selects or returns `scope_key`.

**Constructor:** `PgOutboxDeadLetterService(Pool pool, PgDbExceptionMapper exceptionMapper, DbSchemas schemas, InboxOutboxAdminConfig config)`. Every statement is built once at construction, and the `outbox` table and the `outbox_admin_operations` table are both qualified with the schema mapped to `inbox-outbox`, exactly like `PgInboxOutboxRepository`, so a configured mapping places the receipt table and every statement together. Only the optional filters of the listing are appended per call, from constant fragments, and every value is a bound parameter.

- **`list`** reads a narrow projection (never payload, headers, metadata or `scope_key`) of `DEAD_LETTER` rows, filtered by exact match on the optional fields, ordered `updated_at DESC, id DESC` and paged by a strict keyset `(updated_at, id) < (after)`. The page holds at most `limit` items and a `next` key when a further row exists. A summary's `revision` is the row's `admin_revision`.
- **`find`** returns the detail projection with at most 4096 characters of `last_error`, cut in the database, and `lastErrorTruncated`. It is empty when the row is absent or not `DEAD_LETTER`.
- **`readContent`** decides in one statement whether the combined UTF-8 size of the stored payload text and headers text is within `maxContentBytes` and returns the text only when it is, so an oversized value is never transferred and there is no check-then-read race. It returns the payload as raw stored JSON text and the application headers; carrier, security and context metadata are never returned. Too large fails with `OutboxDeadLetterConflictException` reason `CONTENT_LIMIT_EXCEEDED`; absent or not `DEAD_LETTER` is an empty `Optional`.
- **`retry` and `discard`** each run in one READ COMMITTED transaction, in this order: (1) insert the receipt row for the operation id with `ON CONFLICT DO NOTHING`; a concurrent duplicate waits for the first transaction, and if the operation id was already committed the stored receipt is read with a new statement and returned unchanged, without looking at the entry, when action, entry id, expected revision, exact reason and actor all match, and fails `OPERATION_ID_CONFLICT` (revealing nothing about the other receipt) otherwise; (2) lock the entry `FOR UPDATE`: missing is `OutboxDeadLetterNotFoundException`, a state other than `DEAD_LETTER` is `NOT_DEAD_LETTER`, a revision other than the expected one is `REVISION_CONFLICT`, and a retry of a row at `Long.MAX_VALUE` is `REVISION_EXHAUSTED` (the revision never wraps); (3) retry sets `PENDING`, `attempt = 0`, increments `admin_revision`, clears the claim and the error fields, sets `available_at` and `updated_at` to database time and sends the same `pg_notify('transactional_outbox_channel', id)` the insert trigger sends, inside the transaction; discard deletes the row; (4) complete the receipt with the prior failure snapshot, the target identity, the disposition and, for a retry, the result revision, then commit. Any failure rolls the reservation back together with the state change, so only complete receipts are committed, and a rolled-back operation id can be used again. The receipt is returned only after the commit succeeds.
- **`history`** lists the completed receipts of one entry, newest first, ordered `recorded_at DESC, operation_id DESC` and paged by keyset. Receipts do not depend on the entry: they survive a discard.
- **`cleanupOperations(int retentionDays, int batchSize)`** deletes receipts older than the window in one bounded batch. Both arguments must be positive.

Retry preserves the entry's identity (row id, carrier, destination, payload, headers, metadata, aggregate and event identity, scheduling, `created_at`, `max_attempts`) and resets only the documented fields. The entry is an ordinary pending row afterwards and can fail again and be dead-lettered again; a replay of the original command still returns the original receipt after that, because the receipt is answered before the entry is looked at. A retry restores no ordering: rows of the same aggregate that were delivered while the entry was dead-lettered stay delivered ahead of it, and rows of the same aggregate that are still pending wait behind the requeued entry again, because it has the lowest id.

Database failures leave this class as `InboxOutboxPersistenceException` (`retryable` marks transient errors) with a message that carries no SQL, driver text, payload, reason or actor; the semantic exceptions and `ValidationException` pass through unchanged.

### `DefaultInboxService`

Default `InboxService` implementation backed by `PgInboxOutboxRepository`.

```java
pool.withTransaction(tx ->
    inboxService.processOnce("msg-abc-123", "payments", tx, () ->
        orderRepository.markPaid(orderId, tx)
    )
);
```

### `DefaultOutboxService`

Default `OutboxService` implementation backed by `PgInboxOutboxRepository`. Inserts the outbox row in the caller's transaction and returns the assigned id.

```java
outboxService.publish(tx, OutboxEntry.builder()
    .aggregateType("Order")
    .aggregateId(orderId)
    .eventType("order.confirmed")
    .destinationType(DestinationType.KAFKA)
    .destination("orders")
    .payload(event)
    .build())
    .onSuccess(id -> log.info("Outbox entry {} recorded", id));
```

**Scope selection.** The entry names its scope with `OutboxEntry.builder().scopeKey("acme")`; omitting it stores `default`. `publish` validates the key as a core `ScopeKey` before it writes anything: a `null` key (an explicit `scopeKey(null)`) and an invalid one (blank, longer than 255 code points, or containing a control or format character) return a failed future with an `IllegalArgumentException` whose message does not repeat the key, and nothing is inserted. The ambient `ScopeContext` never selects the row's scope; it is captured into `metadata.context` like any other durable context.

The scope is a label, not authorization. Nothing checks the caller's right to a scope at publish, by id, or under a service principal. Row scope and `ScopeContext` are independent, so a handler that partitions data chooses one source, compares the other when both are present, fails on a mismatch, and authorizes access itself. Inbox deduplication is not scoped: `inbox` rows key on `(message_id, source)` only.

A custom `OutboxRepository` must persist `scopeKey` with the row and return it on every read; a store that drops it returns rows that read `default`.

### `OutboxRelay`

Vert.x `AbstractVerticle` that polls the repository and dispatches claimed rows to registered `OutboxDestinationHandler` instances. Deployed in `SERVICES` phase at priority 100.

**Capability derivation:** `OutboxRelay.deriveCapabilities(handlerMap)` builds the `RelayCapabilities` passed to every `claimBatch` call. It iterates the deduped handler map (produced by `buildHandlerMap`), calls `OutboxDestinationHandler.claimScope()` on each handler, and collects the results into a `RelayCapabilities` keyed by the same `DestinationType` the dispatch path uses for handler lookup. Fails fast at startup with a `NullPointerException` (naming the offending destination type) if any handler returns `null` from `claimScope()`. `TransactionalMessagingPostgresqlModule` no longer injects `@ServiceTargetIds`- or `@DelayedJobTargetIds`-qualified `Set<String>` bindings — capability sourcing lives entirely in the handlers.

**Relay strategies:**

| Strategy | Description |
|----------|-------------|
| `POLLING` | Fixed-interval timer fires `claimBatch()` on every cycle |
| `LISTEN_NOTIFY` | PostgreSQL `LISTEN` on `transactional_outbox_channel` triggers immediate claim; periodic polling acts as a safety net for missed notifications or reconnects |

When `LISTEN_NOTIFY` is configured but the notification channel is unavailable or disconnected, the relay falls back transparently to polling-only mode. No manual intervention required.

**Relay cycle (per batch item):**
1. `claimBatch()` — short transaction selects and marks `PROCESSING` rows.
2. For each claimed row, build `OutboxEnvelope` before the handler lookup, so a row whose destination type has no handler has an envelope too — application `headers` (app-only), durable context in `metadata.context`, and relay control projected into `metadata.delivery.outbox` from the row columns (no framework keys merged into `headers`).
3. Call `OutboxDestinationHandler.publish(envelope)` — outside the claim transaction.
4. On success: `markPublished()`.
5. On retryable failure: `markRetry()` with incremented attempt and backoff-computed `availableAt`.
6. On permanent failure or exhausted attempts: `markDeadLetter()`.
7. On `unresolvable`: `markUnresolvable()` with short delay; attempt count unchanged.
8. A failed future from `publish()` is treated as retryable.
9. A handler that throws synchronously from `publish()` — an exception, an `AssertionError` or a `LinkageError` — returns `null` instead of a future, or completes its future with `null` is treated as retryable too: it costs that one entry an attempt, the rest of the claimed batch is still delivered, and polling continues.
10. When the `mark*` call of steps 4–7 has settled — succeeded, failed, or thrown — every registered `OutboxPublishObserver` (see `dev.vertique:vertique-inbox-outbox-core`) is notified once with the attempt's facts and the envelope from step 2. The callback runs on the relay's context: when the `mark*` call settles on another context or thread, the relay hops back to its own context before it notifies. `dispositionRecorded` is `true` only when the `mark*` call returned `true`.
11. When a claim has completed, every registered observer is also told through `onClaimCompleted` how long the claim took and how many entries it returned, on the relay's context, after the entries are dispatched. A claim that failed is not reported.

**Draining a backlog.** A claim that returns as many rows as the relay had free slots is followed by another almost at once, and so is a freed slot after a cycle that found more work than it could take: with every slot busy the relay claims nothing and waits, and the next slot to free asks for the pass. A cycle that comes back short or empty keeps the interval pacing and its backoff. A `NOTIFY` that arrives while a claim is in flight does not start a second claim; it makes that claim be followed by a pass. There is one claim in flight per relay instance, so the rate at which one instance drains is bounded by `batchSize` and handler latency, not by `pollingIntervalMs`.

The relay releases the entry's in-flight slot and schedules the next poll as soon as the `mark*` call has been made; only the observer notification waits for it to settle. A `mark*` call or a backoff computation that throws is handled as a failed `mark*` call: the slot is released, polling continues, and observers are notified with `dispositionRecorded` `false`. If building the envelope throws, the slot is released, the row stays claimed until stale-lease recovery, and — with no envelope and no attempt — observers are not notified. Every poll cycle schedules the next one whatever happens in it: a `claimBatch` that throws, returns `null` or completes with a `null` list (handled as an empty batch) leaves the loop running. A handler that throws a `StackOverflowError` from `publish` is a failed attempt like any other handler throw; any other `VirtualMachineError` propagates after the slot has been released and the next poll scheduled.

**`ClaimScopeException` (package-private, extends `InboxOutboxConfigurationException`):** When a `ClaimScope.Destinations` supplier misbehaves during `buildClaimEligibility`, the WHOLE claim cycle is aborted with a `ClaimScopeException`. The returned future is failed with zero rows claimed. Failure conditions:

| Condition | Reason in exception message |
|-----------|-----------------------------|
| Supplier — or its returned set (`size()`/`iterator()`/`toArray()`) — throws | `"supplier or its result failed"` |
| Supplier returns `null` | `"produced a null target set"` |
| Any element is `null` or blank | `"contains a null or blank element"` |
| Any element exceeds 255 characters | `"element exceeds maximum length of 255"` |
| More than 10,000 elements | `"target set exceeds limit of 10000"` |

The exception message names only the destination TYPE and reason category — never the offending target value — and carries no cause, so the relay's full-throwable failure log cannot leak target ids into observability pipelines. `ClaimScopeException` is not routed through `PgDbExceptionMapper`; callers can distinguish a misconfigured handler from a transient database error.

**`PgInboxOutboxExceptionMapper` (stage-2, package-private):** `DefaultOutboxService.publish` and `DefaultInboxService.processOnce` wrap any `DataAccessException` that escapes the repository into `InboxOutboxPersistenceException` (via `PgInboxOutboxExceptionMapper.translate`), so these public APIs never expose raw `DataAccessException` to callers. The `retryable()` signal on `InboxOutboxPersistenceException` is `true` for transient and locking failures (deadlock, optimistic/pessimistic lock) and `false` for all other data-access failures. In `processOnce`, the wrapping `recover` is chained on the `tryInsert` future **before** the `.compose` that runs the caller's `work` supplier — so a business-logic failure from `work` propagates unwrapped (FR-IO-004). `OutboxMaintenanceException` (raised by the maintenance service for cleanup failures) extends `InboxOutboxTechnicalException` directly and is not processed by `PgInboxOutboxExceptionMapper`.

**Stale lease recovery:** A background cron job calls `reclaimStale(leaseTimeout)` to return rows stuck in `PROCESSING` beyond `leaseTimeoutMs`. Multiple relay nodes may share the same database safely.

**Per-publish metadata model:**
- Application `headers` are stored and relayed as-is — application/transport headers only; no framework keys are merged in.
- Headers are stored in the `headers` column as one JSON object with a text value per key (`NULL` when the entry has no headers). A JSON object has unique keys and JSONB does not keep key order, so headers come back from storage in no guaranteed order.
- `OutboxService.publish` rejects headers that cannot be delivered before it inserts anything: a `null` key, a `null` value, or a key with the reserved `vertique-` prefix fails the returned future with an `IllegalArgumentException` that names the key (never the value; control characters in the key are replaced by `_` and the key is cut to 128 characters, here and in the relay's WARN for a dropped header), so the caller's transaction can still roll back. A `null` header map means no headers.
- When a stored header value is JSON `null` (a row written without `OutboxService.publish`), that header is dropped on read — it does not become the text `"null"` — so the relay logs one WARN with the entry id and the header key, and delivers the rest.
- Durable propagation context bound at publish is captured into `metadata.context` and, at the Kafka boundary, projected to reserved `vertique-<namespace>` headers (e.g. `vertique-correlation`).
- Relay control (message id, `eventType`, `aggregateType`, `aggregateId`) is exposed at relay time via `metadata.delivery.outbox` (projected from the row columns) — it is **not** merged into `headers` and is internal to the relay. `aggregateId` is still used as the Kafka message key.

**Shutdown:** On `stop()`, the poll timer and LISTEN connection are closed. In-flight publish calls complete independently.

### `OutboxRelayConfig` (owned by `dev.vertique:vertique-inbox-outbox-core`)

This module provides its Dagger binding, deserialized from the `inboxOutbox.relay` section; the type
itself belongs to the core artifact.


Deserialized from `inboxOutbox.relay`.

| Field | Default | Description |
|-------|---------|-------------|
| `strategy` | `LISTEN_NOTIFY` | `POLLING` or `LISTEN_NOTIFY` |
| `pollingIntervalMs` | `1000` | Milliseconds between poll cycles. An empty cycle backs this off — doubling up to 4×, capped at 60 s. A cycle that claims as many rows as the relay had free slots is followed by another almost at once, so this interval no longer caps how fast a backlog drains |
| `batchSize` | `50` | Maximum rows to claim per cycle |
| `leaseTimeoutMs` | `30000` | Stale lease recovery threshold in ms |
| `maxAttempts` | `20` | Currently unread by any runtime path — the per-row attempt limit is fixed at `20` by `OutboxEntry`'s own default at insert time; setting this key has no effect today |
| `backoffBaseDelayMs` | `1000` | Base delay for exponential backoff |
| `backoffMaxDelayMs` | `300000` | Maximum backoff cap in ms |
| `instances` | `1` | Number of `OutboxRelay` verticle instances to deploy |

### `InboxOutboxCleanupConfig` (owned by `dev.vertique:vertique-inbox-outbox-core`)

This module provides its Dagger binding, deserialized from the `inboxOutbox.cleanup` section; the type
itself belongs to the core artifact.


Deserialized from `inboxOutbox.cleanup`.

| Field | Default | Description |
|-------|---------|-------------|
| `publishedRetentionDays` | `7` | Days to retain `PUBLISHED` outbox rows before cleanup |
| `deadLetterRetentionDays` | `30` | Days to retain `DEAD_LETTER` outbox rows before cleanup |
| `inboxRetentionDays` | `30` | Days to retain processed inbox dedup records before cleanup |
| `cleanupBatchSize` | `1000` | Maximum records deleted per cleanup batch, across each table |

---

### `InboxOutboxAdminConfig` (owned by `dev.vertique:vertique-inbox-outbox-core`)

This module provides its Dagger binding, deserialized from the `inboxOutbox.admin` section; an absent section yields the defaults.

| Field | Default | Description |
|-------|---------|-------------|
| `operationRetentionDays` | `90` | Days to retain administration receipts before the maintenance cleanup deletes them. A positive integer. The `Days` suffix follows the retention keys of `InboxOutboxCleanupConfig` |
| `maxContentBytes` | `1048576` | Largest combined UTF-8 size, in bytes, of the stored payload text and headers text that `readContent` returns. A positive integer, checked in SQL before transfer |

---

### `OutboxMaintenanceService`, `OutboxMaintenanceContract`, and `OutboxMaintenanceCron`

The cluster-singleton maintenance path. `OutboxMaintenanceCron` is the `@CronJob` entry point (hence
this module's compile dependency on `dev.vertique:vertique-job-cron`); it delegates to
`OutboxMaintenanceService`, which reclaims stale leases and applies the retention cleanups through
`PgInboxOutboxRepository`. `OutboxMaintenanceContract` is the service contract the cron target
resolves. Applications install the module and tune the cron through configuration; they do not call
these types.

**Cleanup outcomes.** `cleanup()` runs four independent outcomes, labelled `published`, `deadLetter`, `inbox` and `operations` (the receipts of administrative commands, deleted past `inboxOutbox.admin.operationRetentionDays` in batches of `cleanupBatchSize`). All of them always run, success needs all of them to succeed, and a failure carries every cause: the first as the primary cause, the rest suppressed. The aggregate message now counts four outcomes, for example `outbox cleanup failed for [inbox, operations] (2 of 4): <first cause message>`; it read `(n of 3)` before, so match on the label list rather than the count. `OutboxMaintenanceService` keeps its public four-argument constructor, which runs the original three outcomes and reports `of 3`; the `@Inject` constructor adds the `OutboxAdminOperationCleanup` and `InboxOutboxAdminConfig` collaborators that Dagger supplies from this module.

## Database Schema

The Flyway migrations create the inbox and outbox tables, the administration receipt table and their indexes. **`V1__create_inbox_outbox_tables.sql`
is frozen from this Stable release.** Every later schema change ships as `V2+`, never as an edit to
`V1`.

- `V1__create_inbox_outbox_tables.sql` — the `outbox` and `inbox` tables, their indexes and the `LISTEN/NOTIFY` trigger.
- `V002__add_outbox_scope.sql` — adds `outbox.scope_key VARCHAR(255) NOT NULL DEFAULT 'default'`, so every existing row reads `default`. The default is a constant, so PostgreSQL 11 and later add the column without rewriting the table. The script sets `lock_timeout` to 30 s, which bounds only the wait for the table lock, and is safe to rerun. No index and no check constraint are added; `inbox` is unchanged.
- `V003__add_outbox_dead_letter_administration.sql` — adds `outbox.admin_revision BIGINT NOT NULL DEFAULT 0` (the count of administrative requeues, which only the administration commands change and the relay's writes never mention), the `outbox_admin_operations` receipt table, and three indexes: `idx_outbox_admin_operations_entry` on `(entry_id, recorded_at DESC, operation_id DESC)`, `idx_outbox_admin_operations_recorded_at` on `(recorded_at)`, and the partial `idx_outbox_dead_letter_listing` on `outbox (updated_at DESC, id DESC) WHERE state = 'DEAD_LETTER'`. Every statement is `IF NOT EXISTS`, so the script is safe to rerun, and it sets `lock_timeout` to 30 s first. **Lock hazard:** the `ADD COLUMN` default is a constant, so no row is rewritten, but the statement needs a lock that blocks every read and write of `outbox`, and `CREATE INDEX` inside the migration transaction scans the table under a lock that blocks inserts. `lock_timeout` bounds only the wait for a lock, not the index build once it holds the lock. **Very large installations** can create the partial index beforehand without blocking inserts, under the same name: `CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_dead_letter_listing ON outbox (updated_at DESC, id DESC) WHERE state = 'DEAD_LETTER'`; the script then finds it and does nothing.

**`outbox` table** — relay rows:

| Column | Type | Description |
|--------|------|-------------|
| `id` | `BIGSERIAL PK` | Auto-assigned outbox entry id |
| `carrier_id` | `UUID NOT NULL UNIQUE` | Framework-generated per-row durable carrier identity, allocated before insert and never derived from application input; a durable identity snapshot is signed against it |
| `aggregate_type` | `VARCHAR(255)` | Logical aggregate type |
| `aggregate_id` | `VARCHAR(255)` | Aggregate instance id |
| `event_type` | `VARCHAR(255) NOT NULL` | Event type |
| `destination` | `VARCHAR(255) NOT NULL` | Stable target id or topic |
| `destination_type` | `VARCHAR(32) NOT NULL` | Open value type — any id matching `[A-Za-z0-9_-]{1,32}` (built-ins: `SERVICE`, `DELAYED_JOB`, `KAFKA`) |
| `payload` | `JSONB NOT NULL` | Event payload |
| `headers` | `JSONB` | Application and transport headers only, as one JSON object of text values; no framework keys are merged in |
| `metadata` | `JSONB NOT NULL DEFAULT '{}'` | Structured document `{"context": …, "delivery": …}` — durable propagation context and relay control |
| `scheduled_at` | `TIMESTAMPTZ` | Optional scheduled publish time |
| `available_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | Earliest publish time. At insert it is the later of the entry's `availableAt` (`NOW()` when absent) and `scheduled_at`, so a row scheduled for later sits behind every row that can be claimed now and an idle claim does not walk it. A retry backoff sets it to the computed retry time |
| `state` | `VARCHAR(32) NOT NULL DEFAULT 'PENDING'` | Relay state |
| `attempt` | `INTEGER NOT NULL DEFAULT 0` | Current attempt count |
| `max_attempts` | `INTEGER NOT NULL DEFAULT 20` | Max attempts |
| `claimed_at` | `TIMESTAMPTZ` | Claim timestamp |
| `claimed_by` | `VARCHAR(255)` | Node that claimed the row |
| `published_at` | `TIMESTAMPTZ` | Publish timestamp |
| `last_error` | `TEXT` | Last error message |
| `error_type` | `VARCHAR(500)` | Last error type |
| `scope_key` | `VARCHAR(255) NOT NULL DEFAULT 'default'` | Scope label chosen at publish; not read by claim, ordering, lease recovery or cleanup. Added by `V002` |
| `admin_revision` | `BIGINT NOT NULL DEFAULT 0` | Count of administrative requeues; the revision an administration command must quote. The relay never writes it. Added by `V003` |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | Row creation time |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | Row last-modified time |

Indexes: `idx_outbox_pending` (partial, `state = 'PENDING'`, on `available_at ASC, id ASC`), `idx_outbox_processing` (partial, `state = 'PROCESSING'`, on `claimed_at ASC`), `idx_outbox_aggregate` (partial, pending/processing non-null aggregates), `idx_outbox_published`, `idx_outbox_dead_letter`, and `idx_outbox_dead_letter_listing` (partial, `state = 'DEAD_LETTER'`, on `updated_at DESC, id DESC`, serves the administration listing).

**`outbox_admin_operations` table** — one receipt per administrative command. Added by `V003`. It has no foreign key to `outbox`, so a receipt outlives a discarded entry, and it stores no payload, headers, raw error text or metadata:

| Column | Type | Description |
|--------|------|-------------|
| `operation_id` | `UUID PK` | The caller's operation id |
| `action` | `VARCHAR(16) NOT NULL` | `RETRY` or `DISCARD` |
| `entry_id` | `BIGINT NOT NULL` | The targeted outbox entry |
| `expected_revision` | `BIGINT NOT NULL` | The revision the command quoted |
| `reason` | `VARCHAR(1024) NOT NULL` | The reason, stored and compared exactly |
| `actor_type`, `actor_id` | `VARCHAR(64)`, `VARCHAR(255)` `NOT NULL` | The acting identity, as given |
| `recorded_at` | `TIMESTAMPTZ NOT NULL` | Database time of the command's transaction |
| `attempt`, `max_attempts`, `error_type` | `INTEGER`, `INTEGER`, `VARCHAR(500)` | Prior failure snapshot, read before the change |
| `destination_type`, `destination`, `event_type`, `aggregate_type`, `aggregate_id` | `VARCHAR` | Target identity |
| `disposition`, `result_revision` | `VARCHAR(16)`, `BIGINT` | `REQUEUED` or `DISCARDED`; the new revision for a retry |

The intent columns are required. The snapshot, target and result columns are nullable only because the row is inserted first and completed in the same transaction, so a committed row is always complete. Retention deletes receipts past `operationRetentionDays`.

**`inbox` table** — dedup log:

| Column | Type | Description |
|--------|------|-------------|
| `message_id` | `VARCHAR(255) NOT NULL` | Inbound message id; primary key is the composite `(message_id, source)` |
| `source` | `VARCHAR(255) NOT NULL` | Logical source name; part of the composite primary key |
| `processed_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | When the message was first seen |

Index: `idx_inbox_processed_at` (for cleanup queries).

**LISTEN/NOTIFY trigger:** `transactional_outbox_notify` fires `AFTER INSERT ON outbox` and calls `pg_notify('transactional_outbox_channel', NEW.id::text)`.

**Placement.** The migration scripts are unqualified, so the migration instance's default schema decides where the tables (including `outbox_admin_operations`), indexes, the `outbox_id_seq` sequence, the `notify_transactional_outbox` function and the trigger land. In named Flyway mode with a `db.schemas.inbox-outbox` entry, that schema is the default, and the repository and the administration service qualify every table with it, so all agree. In single mode the migration lands where the single-run configuration says (see [Flyway Configuration](#flyway-configuration)). The `transactional_outbox_channel` notification channel is **database-wide**: PostgreSQL channels are not schema-scoped, so every relay listening on the database receives every notification. Treat the channel as a wake-up hint, not a tenant boundary; a relay still claims rows only from its own mapped `outbox` table.

---

## Rollout prerequisite

Dead-letter administration changes how the cleanup must behave, so roll it out in this order:

- **Every cleanup-capable node must run this version's cleanup before any administration adapter exposes retry.** An older node's dead-letter cleanup selects and deletes without locking its candidates and without re-checking the state, so it can delete a row an administrative retry has just requeued. This version's cleanup locks its candidates with `FOR UPDATE SKIP LOCKED` and re-checks the state and age in the delete. The maintenance cron jobs run on one node at a time, which can be any node that has the module installed; upgrade all of them first.
- **Apply `V003` first.** It adds the revision column and the receipt table that the service and the maintenance cleanup read. Very large installations should pre-build the partial index concurrently, as described under [Database Schema](#database-schema).
- **A retried entry keeps its identity and can fail again.** It returns to `PENDING` with `attempt = 0` and the same row id, carrier, destination, payload and headers, so it goes through the same delivery path and can be dead-lettered again; each requeue raises `admin_revision` by one, and a command must quote the revision it read.
- **No ordering is restored.** Entries of the same aggregate that were delivered while this one was dead-lettered stay delivered ahead of it; the requeued entry is delivered when it is claimed, and pending entries of the same aggregate wait behind it again.

---

## Flyway Configuration

The inbox/outbox migration is declared once, by `InboxOutboxMigrationsModule`, and can be run two ways.
The declaration is metadata only: it names the `inbox-outbox` instance and the location
`classpath:db/migration/inbox-outbox`, has no dependencies and needs no connection pool, so a
migration-only component can include it on its own. `TransactionalMessagingPostgresqlModule` includes it,
so an application that wires the repository contributes the declaration automatically.

### Named instances

Give the inbox and outbox tables their own schema and history table:

```json
{
  "db": {
    "schemas": { "inbox-outbox": "messaging" }
  },
  "flyway": {
    "mode": "MIGRATE",
    "named": true,
    "instances": { "inbox-outbox": {} }
  }
}
```

`"named": true` turns on named mode; `instances` only carries per-instance overrides and may be omitted
(`"named": true` alone runs every wired declaration with the root settings). `instances` without
`"named": true` is a configuration error.

- **One setting places everything.** `db.schemas.inbox-outbox` is the instance's default schema and
  history schema, and `PgInboxOutboxRepository` qualifies every inbox and outbox table with it, so the
  migrations and every statement use one schema. The tables, indexes, the `outbox_id_seq` sequence, the
  notify function and its trigger all land there, and the history table is
  `flyway_schema_history_inbox_outbox` in that schema.
- **Run it through the named runner.** A migration component installs `DbModule`, `DbFlywayModule` and
  `InboxOutboxMigrationsModule` (plus the configuration and Vert.x modules the application already
  uses), and calls `FlywayMigrationRunner.run(vertx)`; `FlywayMigrationStartupStep` runs the same engine
  at startup. The component needs no connection pool. `dev.vertique:vertique-db-flyway` documents
  `instances()`, `run(Vertx)`, reports and failures.
- **Without a `db.schemas.inbox-outbox` entry** the migration uses the first entry of the instance's
  Flyway `schemas` list, or the connection's default schema, and the repository stays unqualified.
  Configure the migration and runtime connections to the same default schema.
- **Include `DbModule` in the migration component** (or bind `DbSchemas` yourself). A named migration
  component without either ignores `db.schemas`, and the tables can land in a different schema than a
  runtime component with `DbModule` queries.
- **The application's own migrations** are a separate instance with a disjoint location such as
  `classpath:db/migration/app`. A broad `classpath:db/migration` location overlaps
  `classpath:db/migration/inbox-outbox` and is rejected.
- **A dedicated DDL user** is set with `flyway.user` and `flyway.password` (both, or neither), or per
  instance with `flyway.instances.inbox-outbox.user` and `flyway.instances.inbox-outbox.password`.
  Without them the migration uses the `db` pool credentials.

`dev.vertique:vertique-db-flyway` documents the full instance configuration, the agreement rules between
`db.schemas` and Flyway schema lists, and the failure model.

### Single Flyway configuration

Without `flyway.named`, the default `classpath:db/migration` location already includes the
bundled `db/migration/inbox-outbox` scripts, so no extra location is needed. Every bundled adapter ships its own `V1` script, so one history can hold only one of them (a second would fail with a duplicate version); to run several adapters, set `"named": true`. Wired migration declarations are ignored in a single run, so adding another adapter module never switches the mode. The single-history example:

```json
{
  "flyway": {
    "mode": "MIGRATE",
    "user": "ddl_user",
    "password": "ddl_secret"
  }
}
```

If you override `locations`, list the inbox/outbox directory next to an application directory that does
not contain it, for example `["classpath:db/migration/inbox-outbox", "classpath:db/app"]`. Do not list
both `classpath:db/migration` and `classpath:db/migration/inbox-outbox`: the first already covers the
second.

In this mode the migration lands where the single-run configuration says, and nothing checks it against
`db.schemas`. If `db.schemas.inbox-outbox` is also set, the repository queries that schema, so make the
`flyway.schemas` list (first entry) or the connection default match it, or the repository will
look in a schema the migration never filled.

### Runtime placement

- **The repository reads placement from `DbSchemas`.** A runtime component that installs `DbModule`
  gets it from `db.schemas`, with no Flyway module or Flyway configuration. A component that installs
  neither `DbModule` nor its own `DbSchemas` binding keeps unqualified SQL.
- **Check the keys yourself.** `db.schemas` keys are open and are not validated, so a misspelled key
  (`inbox_outbox` or `inboxoutbox` for `inbox-outbox`) leaves the placement unmapped and the SQL
  unqualified, with no error. Compare `DbSchemas.mappings()` with the instance names before deploying.
- **The runtime database role needs `USAGE` on the mapped schema**, besides the usual privileges on the
  tables.
- **An invalid mapping fails at construction.** A mapped schema that is not a single valid PostgreSQL
  identifier throws `IllegalArgumentException` when the repository is created, not on the first query.
- **The notification channel is database-wide.** `transactional_outbox_channel` is shared by every
  schema in the database, so it is not a tenant boundary (see [Database Schema](#database-schema)).
- **Your own tables.** An application can inject `DbSchemas` and place its own tables with the same
  mapping mechanism; see `dev.vertique:vertique-db-core` (`DbSchemas`) and `PgTableNames` in
  `dev.vertique:vertique-db-postgresql`.
- **One source of `DbSchemas`.** A component that binds `DbSchemas` itself cannot also install
  `DbModule`: Dagger reports a duplicate binding at compile time, with no silent precedence.

---

## Dagger Wiring

`TransactionalMessagingPostgresqlModule` includes `TransactionalMessagingModule` (core), `InboxOutboxMigrationsModule` (the `inbox-outbox` migration declaration) and `DbSchemasModule` (the optional `DbSchemas` binding), and provides:

| Binding | Type | Description |
|---------|------|-------------|
| `InboxService` | Singleton | `DefaultInboxService` backed by `PgInboxOutboxRepository` |
| `OutboxService` | Singleton | `DefaultOutboxService` backed by `PgInboxOutboxRepository` |
| `PgInboxOutboxRepository` | Singleton | PostgreSQL repository, built from `Optional<DbSchemas>`: placed in the schema mapped to `inbox-outbox` when the component supplies `DbSchemas` (a component that installs `DbModule` gets it from `db.schemas.inbox-outbox`), unqualified when it does not. The `InboxRepository` and `OutboxRepository` bindings, both services, the maintenance cron and every relay instance resolve to this one instance, so they share one node identity (each relay instance appends a counter suffix to it for `claimed_by`) |
| `PgOutboxDeadLetterService` | Singleton | Dead-letter administration service, built from `Optional<DbSchemas>` and `InboxOutboxAdminConfig` like the repository. `OutboxDeadLetterService` and `OutboxAdminOperationCleanup` both resolve to this one instance. Binding it exposes no endpoint; an administration adapter must be installed to use it |
| `Set<MigrationDefinition>` | `@IntoSet` | The `inbox-outbox` declaration from `InboxOutboxMigrationsModule`: location `classpath:db/migration/inbox-outbox`, no dependencies |
| `OutboxRelayConfig` | Singleton | Relay configuration deserialized from `inboxOutbox.relay` |
| `InboxOutboxCleanupConfig` | Singleton | Cleanup configuration deserialized from `inboxOutbox.cleanup` |
| `InboxOutboxAdminConfig` | Singleton | Administration configuration deserialized from `inboxOutbox.admin`; an absent section yields the defaults |
| `Set<VerticleDeployment>` | `@ElementsIntoSet` | `OutboxRelay` verticle; SERVICES phase, priority 100. Every relay instance is given the contributed `Set<OutboxPublishObserver>`, sorted once per instance by `OrderedExtension` order |
| `@Services Set<Object>` | `@IntoSet` | `OutboxMaintenanceCron` — provides cluster-singleton `@CronJob` entry points for stale-lease recovery and table cleanup |

**Compatibility note.** `PgInboxOutboxRepository` is now provided by an explicit `@Provides` method. A component that installs this module and also provides `PgInboxOutboxRepository` itself sees a duplicate binding; remove its own provider. The `@Inject` two-argument constructor remains for components that do not install this module.

`RelayCapabilities` are derived at startup by `OutboxRelay.deriveCapabilities(handlerMap)` from the registered `Set<OutboxDestinationHandler>`. No `@ServiceTargetIds`- or `@DelayedJobTargetIds`-qualified `Set<String>` bindings are provided by this module — each adapter handler owns its own claim scope.

```java
@Component(modules = {
    VertxModule.class,
    DispatchModule.class,
    DbModule.class,                              // supplies DbSchemas from db.schemas
    DbPostgresqlModule.class,
    DbFlywayModule.class,
    TransactionalMessagingPostgresqlModule.class,
    TransactionalMessagingServiceModule.class,   // optional: SERVICE adapter
    TransactionalMessagingKafkaModule.class,     // optional: KAFKA adapter
    AppModule.class
})
public interface AppComponent { ... }
```

---

## Configuration

```json
{
  "inboxOutbox": {
    "relay": {
      "strategy": "LISTEN_NOTIFY",
      "pollingIntervalMs": 1000,
      "batchSize": 50,
      "leaseTimeoutMs": 30000,
      "backoffBaseDelayMs": 1000,
      "backoffMaxDelayMs": 300000,
      "instances": 1
    },
    "cleanup": {
      "publishedRetentionDays": 7,
      "deadLetterRetentionDays": 30,
      "inboxRetentionDays": 30,
      "cleanupBatchSize": 1000
    },
    "admin": {
      "operationRetentionDays": 90,
      "maxContentBytes": 1048576
    }
  },
  "cron": {
    "jobs": {
      "outbox-cleanup":              { "cron": "0 0 */6 * * *" },
      "outbox-stale-lease-recovery": { "cron": "*/30 * * * * *" }
    }
  }
}
```

The previous `cleanupIntervalHours` field on `InboxOutboxCleanupConfig` is removed; the configuration object still accepts it for upgrade compatibility (`@JsonIgnoreProperties(ignoreUnknown = true)`) but the value is ignored. Maintenance cadence is now controlled by `cron.jobs.outbox-cleanup.cron` (default `0 0 */6 * * *` — every 6h on wall-clock boundaries) and `cron.jobs.outbox-stale-lease-recovery.cron` (default `*/30 * * * * *` — every 30s). The cron periods match the prior `setPeriodic` defaults, but first-fire timing is wall-clock-aligned rather than uptime-relative.

`InboxOutboxPostgresqlComposeValidator` requires `CronJobRegistrar`, `CronScheduler`, and `CronPersistenceMarker`. The marker is bound exclusively by `CronPersistenceModule`, so installing `TransactionalMessagingPostgresqlModule` without it — including the case where the in-memory `CronModule` is installed instead — fails at Dagger codegen.
