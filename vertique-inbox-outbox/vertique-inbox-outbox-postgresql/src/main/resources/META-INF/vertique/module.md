<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Inbox/Outbox PostgreSQL Module

> **Status:** Implemented
> **Package:** `dev.vertique.inboxoutbox.postgresql`
> **Artifact:** `vertique-inbox-outbox-postgresql`
> **Depends on:** inbox-outbox-core, db-postgresql, db-flyway

PostgreSQL persistence and relay engine for Transactional Messaging. Provides `DefaultInboxService` and `DefaultOutboxService` (the only implementations of the core write APIs), `PgInboxOutboxRepository` (claim/lease queries, stale recovery), `OutboxRelay` (relay verticle with POLLING and LISTEN_NOTIFY strategies), and the Flyway migration that creates the `inbox` and `outbox` tables.

---

## Package Layout

| Package | Contents |
|---------|----------|
| `dev.vertique.inboxoutbox.postgresql` | `DefaultInboxService`, `DefaultOutboxService`, `PgInboxOutboxRepository`, `OutboxRelay`, `OutboxRelayConfig`, `OutboxRecordMapper`, `TransactionalMessagingPostgresqlModule` |

---

## Key Classes

### `PgInboxOutboxRepository`

PostgreSQL repository that owns all DDL, claim queries, lease recovery, and LISTEN/NOTIFY integration. Extends `PgSqlRepository`.

**Inbox operations:**
- `insertIfAbsent(String messageId, String source, SqlClient tx)` — inserts the inbox row in the caller's transaction; returns `true` for new messages, `false` for duplicates
- `cleanupInbox(Instant before)` — batch-deletes inbox rows older than the retention threshold

**Outbox operations:**
- `insertOutbox(OutboxEntry entry, SqlClient tx)` — inserts the outbox row in the caller's transaction; returns the assigned id
- `claimBatch(RelayCapabilities capabilities, int batchSize, String claimedBy)` — short transaction: selects eligible `PENDING` rows with `FOR UPDATE SKIP LOCKED` filtered by capabilities, marks them `PROCESSING`
- `completePublish(long id, Instant publishedAt)` — transitions a row to `PUBLISHED`
- `retryRow(long id, int attempt, Instant availableAt, String lastError, String errorType)` — transitions back to `PENDING`, applies incremented attempt count and backoff time
- `deadLetterRow(long id, String lastError, String errorType)` — transitions to `DEAD_LETTER`
- `returnToQueue(long id, Instant availableAt)` — returns to `PENDING` without incrementing attempt (used for `unresolvable` outcome)
- `recoverStaleRows(Instant leaseExpiredBefore, String claimedBy)` — returns stale `PROCESSING` rows to `PENDING` without incrementing attempt; safe without table-wide locks
- `cleanupPublished(Instant before)` — batch-deletes `PUBLISHED` rows past retention
- `cleanupDeadLetter(Instant before)` — batch-deletes `DEAD_LETTER` rows past retention

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
2. For each claimed row, build `OutboxEnvelope` — application `headers` (app-only), durable context in `metadata.context`, and relay control projected into `metadata.delivery.outbox` from the row columns (no framework keys merged into `headers`).
3. Call `OutboxDestinationHandler.publish(envelope)` — outside the claim transaction.
4. On success: `completePublish()`.
5. On retryable failure: `retryRow()` with incremented attempt and backoff-computed `availableAt`.
6. On permanent failure or exhausted attempts: `deadLetterRow()`.
7. On `unresolvable`: `returnToQueue()` with short delay; attempt count unchanged.
8. A failed future from `publish()` is treated as retryable.

**`ClaimScopeException` (package-private, extends `InboxOutboxConfigurationException`):** When a `ClaimScope.Destinations` supplier misbehaves during `buildClaimEligibility`, the WHOLE claim cycle is aborted with a `ClaimScopeException`. The returned future is failed with zero rows claimed. Failure conditions:

| Condition | Reason in exception message |
|-----------|-----------------------------|
| Supplier — or its returned set (`size()`/`iterator()`/`toArray()`) — throws | `"supplier or its result failed"` |
| Supplier returns `null` | `"produced a null target set"` |
| Any element is `null` or blank | `"contains a null or blank element"` |
| Any element exceeds 255 characters | `"element exceeds maximum length of 255"` |
| More than 10,000 elements | `"target set exceeds limit of 10000"` |

The exception message names only the destination TYPE and reason category — never the offending target value — and carries no cause, so the relay's full-throwable failure log cannot leak target ids into observability pipelines. `ClaimScopeException` is not routed through `PgDbExceptionMapper`; callers can distinguish a misconfigured handler from a transient database error.

**`InboxOutboxExceptionMapper` (stage-2, package-private):** `DefaultOutboxService.publish` and `DefaultInboxService.processOnce` wrap any `DataAccessException` that escapes the repository into `InboxOutboxPersistenceException` (via `InboxOutboxExceptionMapper.translate`), so these public APIs never expose raw `DataAccessException` to callers. The `retryable()` signal on `InboxOutboxPersistenceException` is `true` for transient and locking failures (deadlock, optimistic/pessimistic lock) and `false` for all other data-access failures. In `processOnce`, the wrapping `recover` is chained on the `tryInsert` future **before** the `.compose` that runs the caller's `work` supplier — so a business-logic failure from `work` propagates unwrapped (FR-IO-004). `OutboxMaintenanceException` (raised by the maintenance service for cleanup failures) extends `InboxOutboxTechnicalException` directly and is not processed by `InboxOutboxExceptionMapper`.

**Stale lease recovery:** A background cron job calls `reclaimStale(leaseTimeout)` to return rows stuck in `PROCESSING` beyond `leaseTimeoutMs`. Multiple relay nodes may share the same database safely.

**Per-publish metadata model (ADR 0065):**
- Application `headers` are stored and relayed as-is — application/transport headers only; no framework keys are merged in.
- Durable propagation context bound at publish is captured into `metadata.context` and, at the Kafka boundary, projected to reserved `vertique-<namespace>` headers (e.g. `vertique-correlation`).
- Relay control (message id, `eventType`, `aggregateType`, `aggregateId`) is exposed at relay time via `metadata.delivery.outbox` (projected from the row columns) — it is **not** merged into `headers` and is internal to the relay. `aggregateId` is still used as the Kafka message key.

**Shutdown:** On `stop()`, the poll timer and LISTEN connection are closed. In-flight publish calls complete independently.

### `OutboxRelayConfig`

Deserialized from `inboxOutbox.relay`.

| Field | Default | Description |
|-------|---------|-------------|
| `strategy` | `LISTEN_NOTIFY` | `POLLING` or `LISTEN_NOTIFY` |
| `pollingIntervalMs` | `1000` | Milliseconds between poll cycles |
| `batchSize` | `50` | Maximum rows to claim per cycle |
| `leaseTimeoutMs` | `30000` | Stale lease recovery threshold in ms |
| `maxAttempts` | `20` | Default max attempts for outbox rows |
| `backoffBaseDelayMs` | `1000` | Base delay for exponential backoff |
| `backoffMaxDelayMs` | `300000` | Maximum backoff cap in ms |
| `instances` | `1` | Number of `OutboxRelay` verticle instances to deploy |

### `InboxOutboxCleanupConfig`

Deserialized from `inboxOutbox.cleanup`.

| Field | Default | Description |
|-------|---------|-------------|
| `publishedRetentionDays` | `7` | Days to retain `PUBLISHED` outbox rows before cleanup |
| `deadLetterRetentionDays` | `30` | Days to retain `DEAD_LETTER` outbox rows before cleanup |
| `inboxRetentionDays` | `30` | Days to retain processed inbox dedup records before cleanup |
| `cleanupBatchSize` | `1000` | Maximum records deleted per cleanup batch, across each table |

---

## Database Schema

The Flyway migration creates two tables and associated indexes.

**`outbox` table** — relay rows:

| Column | Type | Description |
|--------|------|-------------|
| `id` | `BIGSERIAL PK` | Auto-assigned outbox entry id |
| `aggregate_type` | `VARCHAR(255)` | Logical aggregate type |
| `aggregate_id` | `VARCHAR(255)` | Aggregate instance id |
| `event_type` | `VARCHAR(255) NOT NULL` | Event type |
| `destination` | `VARCHAR(255) NOT NULL` | Stable target id or topic |
| `destination_type` | `VARCHAR(32) NOT NULL` | Open value type — any id matching `[A-Za-z0-9_-]{1,32}` (built-ins: `SERVICE`, `DELAYED_JOB`, `KAFKA`) |
| `payload` | `JSONB NOT NULL` | Event payload |
| `headers` | `JSONB` | Outbound metadata + adapter snapshot data |
| `scheduled_at` | `TIMESTAMPTZ` | Optional scheduled publish time |
| `available_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | Earliest publish time |
| `state` | `VARCHAR(32) NOT NULL DEFAULT 'PENDING'` | Relay state |
| `attempt` | `INTEGER NOT NULL DEFAULT 0` | Current attempt count |
| `max_attempts` | `INTEGER NOT NULL DEFAULT 20` | Max attempts |
| `claimed_at` | `TIMESTAMPTZ` | Claim timestamp |
| `claimed_by` | `VARCHAR(255)` | Node that claimed the row |
| `published_at` | `TIMESTAMPTZ` | Publish timestamp |
| `last_error` | `TEXT` | Last error message |
| `error_type` | `VARCHAR(500)` | Last error type |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | Row creation time |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | Row last-modified time |

Indexes: `idx_outbox_pending` (partial, `state = 'PENDING'`, on `available_at ASC, id ASC`), `idx_outbox_processing` (partial, `state = 'PROCESSING'`, on `claimed_at ASC`), `idx_outbox_aggregate` (partial, pending/processing non-null aggregates), `idx_outbox_published`, `idx_outbox_dead_letter`.

**`inbox` table** — dedup log:

| Column | Type | Description |
|--------|------|-------------|
| `message_id` | `VARCHAR(255) PK` | Inbound message id |
| `source` | `VARCHAR(255) NOT NULL` | Logical source name |
| `processed_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | When the message was first seen |

Index: `idx_inbox_processed_at` (for cleanup queries).

**LISTEN/NOTIFY trigger:** `transactional_outbox_notify` fires `AFTER INSERT ON outbox` and calls `pg_notify('transactional_outbox_channel', NEW.id::text)`.

---

## Dagger Wiring

`TransactionalMessagingPostgresqlModule` includes `TransactionalMessagingModule` (core) and provides:

| Binding | Type | Description |
|---------|------|-------------|
| `InboxService` | Singleton | `DefaultInboxService` backed by `PgInboxOutboxRepository` |
| `OutboxService` | Singleton | `DefaultOutboxService` backed by `PgInboxOutboxRepository` |
| `PgInboxOutboxRepository` | Singleton | PostgreSQL repository |
| `OutboxRelayConfig` | Singleton | Relay configuration deserialized from `inboxOutbox.relay` |
| `InboxOutboxCleanupConfig` | Singleton | Cleanup configuration deserialized from `inboxOutbox.cleanup` |
| `Set<VerticleDeployment>` | `@ElementsIntoSet` | `OutboxRelay` verticle; SERVICES phase, priority 100 |
| `@Services Set<Object>` | `@IntoSet` | `OutboxMaintenanceServiceImpl` — provides cron-job entry points for stale-lease recovery and table cleanup |

`RelayCapabilities` are derived at startup by `OutboxRelay.deriveCapabilities(handlerMap)` from the registered `Set<OutboxDestinationHandler>`. No `@ServiceTargetIds`- or `@DelayedJobTargetIds`-qualified `Set<String>` bindings are provided by this module — each adapter handler owns its own claim scope.

```java
@Component(modules = {
    VertxModule.class,
    DispatchModule.class,
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
      "maxAttempts": 20,
      "backoffBaseDelayMs": 1000,
      "backoffMaxDelayMs": 300000,
      "instances": 1
    },
    "cleanup": {
      "publishedRetentionDays": 7,
      "deadLetterRetentionDays": 30,
      "inboxRetentionDays": 30,
      "cleanupBatchSize": 1000
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

---

## Related ADRs

- ADR-0065: Structured Durable Context Metadata — establishes the `metadata.context` / `metadata.delivery` envelope split; governs how relay-control values (`messageId`, `eventType`, `aggregateType`, `aggregateId`) are exposed via `OutboxRelayControl` rather than merged into `headers`.
- ADR-0060: Cluster-Singleton Maintenance via Cron — mandates that stale-lease recovery and row cleanup run as cluster-singleton `@CronJob` operations rather than per-node `setPeriodic` timers.
- ADR-0081: DestinationType Is an Open Value Type, Not a Closed Enum — `DestinationType` is a validated string value type; new destination adapters register by returning `DestinationType.of("their-id")` with no edit to the framework class. The read path uses `DestinationType.of(...)` (lenient) so unrecognised but well-formed ids are preserved rather than rejected.
- ADR-0082: Adapter-Owned Relay Claim Eligibility via Per-Handler ClaimScope — each `OutboxDestinationHandler` declares its own `ClaimScope`; the relay derives `RelayCapabilities` from the handler set and builds the SQL destination-eligibility disjunction dynamically, eliminating hardcoded type lists from the claim query.
- ADR-0112: Framework Exception Hierarchy and REST Mapping — establishes `InboxOutboxConfigurationException` (→ core `ConfigurationException`) and `InboxOutboxTechnicalException` (→ core `TechnicalException`) as the semantic roots; mandates `InboxOutboxExceptionMapper` as the API-boundary wrapper of `DataAccessException`; `ClaimScopeException` extends `InboxOutboxConfigurationException` per this hierarchy.
