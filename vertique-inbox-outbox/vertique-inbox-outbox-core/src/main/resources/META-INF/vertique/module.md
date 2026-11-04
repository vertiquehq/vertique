<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Inbox/Outbox Core Module

> **Status:** Stable
> **Package:** `dev.vertique.inboxoutbox`
> **Artifact:** `vertique-inbox-outbox-core`
> **Depends on:** `dev.vertique:vertique-core`, `dev.vertique:vertique-db-core`

Provides the core write APIs and relay contracts for Transactional Messaging. `InboxService` deduplicates inbound messages within the same transaction as business logic. `OutboxService` records outbound side-effects atomically with business writes. The relay SPI (`OutboxDestinationHandler`) allows adapter modules to publish recorded entries to their respective destinations after commit.

This module has no dependency on `services`, `job-delayed`, or `kafka` — those are optional adapter modules.

---

## Key Classes

### `InboxService`

Deduplicates inbound messages within the caller's transaction. The dedup `INSERT` and the application work share the same `SqlClient` — if the outer transaction rolls back, both roll back together.

```java
public interface InboxService {
    <T> Future<InboxResult<T>> processOnce(
        String messageId,
        String source,
        SqlClient tx,
        Supplier<Future<T>> work);
}
```

| Parameter | Description |
|-----------|-------------|
| `messageId` | Unique identifier for the inbound message (e.g., `x-message-id` header value) |
| `source` | Logical source name (e.g., `"payments"`, `"orders"`) |
| `tx` | Caller-provided transaction; dedup insert participates in this transaction |
| `work` | Business logic to execute only for new messages |

Processing steps:
1. Attempt `INSERT INTO inbox (message_id, source)`.
2. If the insert succeeds, execute `work` and return `InboxResult.Processed(value)`.
3. If the message already exists (unique constraint), skip `work` and return `InboxResult.Duplicate()`.

```java
KafkaRecordContext record = KafkaRecordContext.current().orElseThrow();
String messageId = Objects.requireNonNull(record.headers().asMap().get("x-message-id"), "x-message-id");

pool.withTransaction(tx ->
    inboxService.processOnce(messageId, "payments", tx, () ->
        orderRepository.markPaid(orderId, tx)
            .compose(v -> outboxService.publish(tx, OutboxEntry.builder()
                .aggregateType("Order")
                .aggregateId(orderId)
                .eventType("send-order-confirmation")
                .destinationType(DestinationType.SERVICE)
                .destination("integration.notification.send-order-confirmation")
                .payload(orderConfirmation)
                .build()))
    )
).onSuccess(result -> {
    if (result.isDuplicate()) {
        log.info("Duplicate message {} skipped", messageId);
    }
});
```

### `InboxResult<T>`

Sealed interface with two variants. Use `isDuplicate()` / `isProcessed()` or pattern match.

| Variant | Description |
|---------|-------------|
| `Processed(T value)` | New message; `work` ran and returned `value` |
| `Duplicate()` | Already-seen message; `work` was skipped |

### `OutboxService`

Records an outbound side-effect atomically within the caller's transaction. Returns the assigned outbox entry id.

```java
public interface OutboxService {
    Future<Long> publish(SqlClient tx, OutboxEntry entry);
}
```

The insert participates in the caller's transaction. If the transaction rolls back, the outbox row rolls back with it. The relay engine only sees committed rows.

### `OutboxEntry`

Lombok `@Builder` value object describing a single outbound side-effect.

| Field | Required | Description |
|-------|----------|-------------|
| `aggregateType` | No | Logical aggregate/entity type (e.g., `"Order"`) |
| `aggregateId` | No | Aggregate instance id; used for ordering and Kafka default key |
| `eventType` | Yes | Event/message type identifier (e.g., `"send-order-confirmation"`) |
| `destinationType` | Yes | `DestinationType` open value type identifying the adapter to use (e.g., `DestinationType.SERVICE`) |
| `destination` | Yes | Stable service target id, durable delayed-job target id, or Kafka topic |
| `payload` | Yes | Jackson-serializable payload; stored as JSONB |
| `headers` | No | Additional outbound metadata; may carry adapter-specific snapshot data. A `null` map given to the builder reads back as an empty map. Keys and values must not be `null`, and keys must not start with the reserved `vertique-` prefix — `OutboxService.publish` fails for such an entry. See [Header model](#header-model) |
| `scheduledAt` | No | Time after which the relay may publish |
| `availableAt` | No | Internal retry scheduling override; applications normally omit |
| `scopeKey` | No | Scope label stored with the row; defaults to `"default"` (`ScopeKey.DEFAULT.value()`). `OutboxService.publish` validates it as a core `ScopeKey`. See [Scope](#scope) |

For `SERVICE` destinations, `destination` must be a stable service target id (not a raw event bus address). For `DELAYED_JOB` destinations, `destination` must be a durable delayed-job target id. For `KAFKA` destinations, `destination` is the Kafka topic name. For custom adapter destinations, the meaning of `destination` is defined by the adapter's `OutboxDestinationHandler`.

#### Scope

Every outbox row carries a `scopeKey`, the application's partition label for the entry (a tenant, project or workspace). Select it on the entry:

```java
OutboxEntry.builder()
    .eventType("order.placed")
    .destinationType(DestinationType.SERVICE)
    .destination("orders/handle-order-placed")
    .payload(new OrderPlacedEvent(orderId))
    .scopeKey("acme")
    .build();
```

- **Omitted means `default`.** An entry that never calls `scopeKey(...)` is stored in the `default` scope, and so is every row that existed before the column was added.
- **An explicit `null` fails.** `OutboxService.publish` returns a failed future with an `IllegalArgumentException`, before any write, for a `null` key and for one that is not a valid core `ScopeKey` (blank, longer than 255 code points, or containing a control or format character). The message does not repeat the key. The entry itself holds the text as given and does not validate it.
- **The ambient `ScopeContext` does not select it.** Publishing under a bound `ScopeContext` still stores `default` unless the entry names a scope. The context is captured into the durable `metadata` like any other durable context, independent of the row's scope.
- **A scope is a label, not authorization.** Nothing checks the caller's right to a scope at publish, by id or under a service principal; the application authorizes access itself.
- **Row scope and `ScopeContext` are independent.** A handler that partitions data by scope chooses one source, compares the other when both are present, fails on a mismatch, and authorizes access itself. Do not assume the two agree.
- **`aggregateId` ordering is global across scopes.** Entries that share an `aggregateId` stay in order whatever their scope, so two scopes that reuse an id order against each other. Keep aggregate ids unique across scopes, for example `<scope>:<id>`.
- **A custom `OutboxRepository` must persist `scopeKey`.** `OutboxRepository.insert` receives the entry; an implementation that drops `scopeKey` returns rows that read `default`.
- **A custom `OutboxRepository` must implement `cleanupDiscarded`.** It deletes `DISCARDED` entries past the retention window, in batches, and must not delete an entry an operator brought back in the meantime; `OutboxMaintenanceService` calls it with the dead-letter and published cleanups.
- **Inbox deduplication is not scoped.** `InboxService` keys on `(messageId, source)` only.

#### Header model

Outbox headers are a `Map<String, String>` on `OutboxEntry`, `OutboxRecord` and `OutboxEnvelope`, with the same rules on all three:

- **Text only.** A header value is a `String`; there are no binary values.
- **Unique keys.** One value per key — a header cannot repeat.
- **No ordering guarantee once stored.** The copy an entry takes keeps the iteration order of the map it was given, but storage does not preserve it, so a destination must not rely on header order.
- **Copied immutably.** `OutboxEntry` takes an unmodifiable copy when the entry is built, and `OutboxRecord` and `OutboxEnvelope` take one when they are constructed. Changing the map you passed in afterwards does not change the entry, and the map returned by `headers()` throws `UnsupportedOperationException` on mutation. A `null` map reads back as an empty map.
- **No reserved prefix, no `null`.** A key must not start with the reserved framework prefix `vertique-`, and neither a key nor a value may be `null`. The entry itself carries such a header, but `OutboxService.publish` rejects it before anything is inserted.

### `OutboxRecord`

Domain record representing a persisted outbox row. Carries all relay state fields including attempt count, lease fields, and error details. The relay reads `OutboxRecord` instances from the repository and wraps them in `OutboxEnvelope` before dispatching to adapters.

| Field | Type | Description |
|-------|------|-------------|
| `id` | `long` | Auto-assigned outbox entry id |
| `aggregateType` | `String` | Logical aggregate type |
| `aggregateId` | `String` | Aggregate instance id |
| `eventType` | `String` | Event type |
| `destination` | `String` | Stable target id or topic |
| `destinationType` | `DestinationType` | Open value type; built-ins are `SERVICE`, `DELAYED_JOB`, `KAFKA`; adapter-declared types are also valid |
| `payload` | `Object` | Deserialized payload (decoded from JSONB by `PayloadCodec`) |
| `headers` | `Map<String, String>` | Application/transport headers only (no framework keys); an unmodifiable copy — see [Header model](#header-model) |
| `scheduledAt` | `Instant` | Optional scheduled publish time |
| `availableAt` | `Instant` | Earliest publish time after backoff |
| `state` | `OutboxEntryState` | `PENDING`, `PROCESSING`, `PUBLISHED`, `DEAD_LETTER`, `DISCARDED` |
| `attempt` | `int` | Current attempt count (incremented at retry) |
| `maxAttempts` | `int` | Maximum attempts before dead-letter |
| `claimedAt` | `Instant` | When the relay claimed this row |
| `claimedBy` | `String` | Node id that claimed this row |
| `publishedAt` | `Instant` | When the row transitioned to `PUBLISHED` |
| `lastError` | `String` | Last error message |
| `errorType` | `String` | Last error type |
| `scopeKey` | `String` | Scope label chosen when the entry was published; never `null` — a `null` component reads `default`. See [Scope](#scope) |

The record is the canonical 23-component form with `scopeKey` last; a public 22-argument constructor without it remains and reads `default`.

### `OutboxEntryState`

Relay state machine for outbox rows.

| State | Description |
|-------|-------------|
| `PENDING` | Waiting for relay to claim |
| `PROCESSING` | Claimed by relay; publication in progress |
| `PUBLISHED` | Successfully published to destination |
| `DEAD_LETTER` | Exhausted attempts or permanent failure |
| `DISCARDED` | Dropped by an operator through `OutboxOperations.discard`; never claimed by the relay and removed after the discarded retention period |

### `OutboxEnvelope`

Immutable record passed to `OutboxDestinationHandler.publish()` at relay time.

| Field | Description |
|-------|-------------|
| `entryId` | Outbox row id |
| `aggregateType` / `aggregateId` / `eventType` | Row columns (used e.g. for the Kafka message key) |
| `destination` / `payload` / `scheduledAt` / `attempt` / `createdAt` | Delivery fields from the row |
| `headers` | Application/transport headers only (`OutboxEntry.headers`) — no framework keys; an unmodifiable copy — see [Header model](#header-model) |
| `metadata` | `OutboxMetadata`: durable propagation context (`context`) plus delivery control (`delivery.outbox`: `OutboxRelayControl`, relay control projected from the row columns; `delivery.delayedJob`: scheduling) |
| `scopeKey` | Scope label read from the row; never `null` — a `null` component reads `default`. The relay does not bind it as the ambient `ScopeContext`. See [Scope](#scope) |

The envelope is the canonical 12-component form with `scopeKey` last; a public 11-argument constructor without it remains and reads `default`.

Framework relay control (`x-message-id`, `eventType`, `aggregate*`) is **not** merged into `headers`; it lives in `metadata.delivery.outbox`. Durable context lives in `metadata.context`, not headers.

### `OutboxPublishResult`

Sealed interface returned by `OutboxDestinationHandler.publish()`. The relay uses the result to drive the state machine.

| Factory Method | Variant | Effect |
|----------------|---------|--------|
| `success()` | `Success()` | Transition to `PUBLISHED`, set `publishedAt` |
| `retryable(String message, Throwable cause)` | `RetryableFailure(String message, Throwable cause)` | Transition back to `PENDING`, increment attempt, apply backoff, record error — or to `DEAD_LETTER` once the incremented attempt reaches `maxAttempts` |
| `permanent(String message, Throwable cause)` | `PermanentFailure(String message, Throwable cause)` | Transition to `DEAD_LETTER`, record error |
| `unresolvable(String message)` | `Unresolvable(String message)` | Return to `PENDING` with short delay; does NOT increment attempt |

These four factories are the complete set — there are no overloads. `cause` may be `null` when no
exception is available. Handlers do **not** supply an error type string: on a failure outcome the
relay records `message` in the row's `lastError` column and derives `errorType` from the class name
of `cause` (`null` when no cause was supplied).

The `unresolvable` outcome is a safety net for rare races during rolling deploys. Under normal operation the capability-aware claim filter prevents unresolvable rows from being claimed.

### `DestinationType`

An **open value type** (`public final class`) wrapping a validated string id. Not a closed enum — a new destination adapter registers by declaring `DestinationType.of("its-id")` as a constant in the adapter module; no edit to this class is required.

```java
public final class DestinationType {
    // Built-in constants (ids equal their former enum names)
    public static final DestinationType SERVICE    = DestinationType.of("SERVICE");
    public static final DestinationType DELAYED_JOB = DestinationType.of("DELAYED_JOB");
    public static final DestinationType KAFKA       = DestinationType.of("KAFKA");

    @JsonCreator
    public static DestinationType of(String id) { ... }

    @JsonValue
    public String id() { ... }
}
```

| Member | Description |
|--------|-------------|
| `of(String id)` | Sole factory (`@JsonCreator`). Validates `^[A-Za-z0-9_-]{1,32}$`; `null` → `NullPointerException`; malformed → `IllegalArgumentException`. Built-in ids return their canonical constant; other valid ids return a fresh (non-interned) instance. |
| `id()` | String accessor (`@JsonValue`). The persisted `destination_type` column value and the JSON wire form. |
| `equals` / `hashCode` | By `id` string equality. A `DestinationType` read from the database via `of("SERVICE")` is equal to `DestinationType.SERVICE` even as a distinct instance — load-bearing for handler dispatch. |

The three built-in constants keep the same string ids as their former enum names (`"SERVICE"`, `"DELAYED_JOB"`, `"KAFKA"`), so the database wire form and JSON representation are unchanged — no migration required.

**Lenient read semantics.** An unrecognised-but-valid id (e.g., a row written by a Camel adapter before the Camel handler is deployed) does not throw at mapping time. The relay finds no handler for that type and leaves the row `PENDING` — correct behaviour for an unregistered destination.

#### Invariants & Gotchas

- There is no `name()`, `valueOf()`, `values()`, or `ordinal()` — those were enum APIs. Use `id()` and `of(...)`.
- `of()` does not intern arbitrary ids. The three built-in constants are canonical; any other valid id returns a fresh instance each call. Use `equals()`, never `==`, when comparing `DestinationType` values that may have come from `of()`.
- The error message on a malformed id does not echo more than 32 characters of the input — the factory is called from the JSON/DB read path where the input is untrusted.

### `ClaimScope`

Sealed interface (`permits All, Destinations`) that each `OutboxDestinationHandler` returns from `claimScope()` to declare which rows of its destination type this relay node is eligible to claim.

```java
public sealed interface ClaimScope permits ClaimScope.All, ClaimScope.Destinations {

    static ClaimScope all() { ... }
    static ClaimScope destinations(Supplier<Set<String>> claimableTargets) { ... }

    record All() implements ClaimScope {}

    record Destinations(Supplier<Set<String>> claimableTargets) implements ClaimScope {}
}
```

| Variant | Claim behaviour |
|---------|----------------|
| `All` | Every row of the associated destination type is claimable by this node. Use for globally deliverable types (e.g., Kafka topics, cluster-wide addresses). |
| `Destinations(Supplier<Set<String>> claimableTargets)` | Only rows whose `destination` column value is in the supplier's set are claimable. The supplier is evaluated lazily at each claim cycle so it reflects the live registration state. An empty set claims nothing. |

**Supplier contract (fail-closed).** The store adapter validates the supplier result before use — in
`dev.vertique:vertique-inbox-outbox-postgresql` that is `PgInboxOutboxRepository`, which raises
`ClaimScopeException`; the limits below are this module's contract, enforced at the store. Any of the following causes the entire claim cycle to fail closed (zero rows claimed for this cycle; the relay retries on the next tick):
- The supplier throws
- The supplier returns `null`
- Any element is blank or `null`
- Any element exceeds 255 characters
- The set exceeds 10,000 elements

The exception message names only the destination type and the failure category — it never includes the offending value, so target ids do not appear in logs.

### `RelayCapabilities`

Value object passed to `OutboxRepository.claimBatch` at each claim cycle. Carries the per-type `ClaimScope` derived from the registered handler set; the claim query uses this map to build a dynamic destination predicate so only rows this node can actually deliver are claimed.

```java
public record RelayCapabilities(Map<DestinationType, ClaimScope> byType) {}
```

The relay constructs this record from the registered `OutboxDestinationHandler` set at startup: each handler contributes a `(destinationType(), claimScope())` pair. A destination type absent from `byType` is not claimed — "not in the map" means "claim nothing for this type", never "claim everything".

### `TransactionalMessageContext`

Publish-side access to the application/transport headers for an outbound message (application-only). Framework durable context (correlation, trace, locale, …) is NOT carried here — it is captured from the ambient bound context into `OutboxMetadata.context` at publish. Outbox relay fields (message id, `eventType`, aggregate ids) are delivery control surfaced at relay time via `metadata.delivery.outbox`, not via this context.

| Field | Description |
|-------|-------------|
| `messageId` / `eventType` / `aggregateType` / `aggregateId` | Row columns of the originating entry |
| `headers` | Application/transport headers, an unmodifiable copy |
| `scopeKey` | Scope label read from the row; never `null` — a `null` component reads `default`. Independent of the ambient `ScopeContext`. See [Scope](#scope) |

The record is the canonical six-component form with `scopeKey` last; a public five-argument constructor without it remains and reads `default`.

### Reading and administering entries

`OutboxQueryService` and `OutboxOperations` are the in-process API an administration adapter (for example a REST module) uses to look at outbox entries and act on them. They are programmatic only: this module exposes no endpoint, and neither service performs authorization or takes an acting identity. The adapter authorizes the caller and records who acted. Both administer the configured store as a whole and neither filters on nor returns an entry's `scopeKey`, so they are a platform-operator surface, not a scope-isolation boundary. Carrier, security and context metadata are never returned.

```java
public interface OutboxQueryService {
    Future<PagedResult<OutboxEntrySummary>> list(OutboxQuery query, PageCursor cursor);
    Future<Optional<OutboxEntryDetail>> getById(long entryId);
    Future<Optional<OutboxEntryPayload>> getPayload(long entryId);
}

public interface OutboxOperations {
    Future<OutboxTransition> retry(long entryId, OutboxEntryState expectedState);
    Future<OutboxTransition> discard(long entryId, OutboxEntryState expectedState);
}
```

**Listing.**

- **State is required.** An `OutboxQuery` names at least one `OutboxEntryState`, so a listing never scans the whole table. The other filters are optional exact matches: `destinationType`, `destination`, `eventType`, `aggregateType`, `aggregateId`, plus `createdFrom`/`createdBefore` and `updatedFrom`/`updatedBefore` ranges (start inclusive, end exclusive).
- **Order.** `OutboxSort` is `UPDATED_AT_DESC` (default), `UPDATED_AT_ASC`, `CREATED_AT_DESC` or `CREATED_AT_ASC`, always tie-broken by entry id in the same direction. `updatedAt` is when the entry entered its current state, so for a dead letter it is when it failed.
- **Paging.** Keyset paging with `dev.vertique.db.query.PageCursor` and `PagedResult`, as in the workflow query service. A page size is 1 to 200 (`OutboxQueryService.MAX_PAGE_SIZE`); 50 is the default. A cursor is not bound to the query that produced it; an adapter that hands cursors to untrusted callers must bind them.
- **Live reads.** Each page reads the live table, so an entry that changes state between two pages can be missed or repeated.
- **Indexes.** Entries that are `PENDING`, `PROCESSING`, `DEAD_LETTER` or `DISCARDED` are served by partial indexes. `PUBLISHED` is not indexed for the listing: it is the large set and cleanup removes it after `publishedRetentionDays`.
- **What a row carries.** `OutboxEntrySummary` holds identity, state, destination, event and aggregate, attempt counts, `errorType`, `createdAt` and `updatedAt`. `OutboxEntryDetail` adds `availableAt`, `scheduledAt`, `publishedAt`, the relay lease (`claimedAt`, `claimedBy`) and `lastError` cut at 4096 characters with `lastErrorTruncated`. Neither carries payload, headers or metadata.
- **Payload.** `getPayload` returns the stored JSON text and the application headers. The size check and the read are one statement, so content larger than `inboxOutbox.query.maxPayloadBytes` (default 1 MiB, at most 16 MiB) is never transferred and the call fails with `OutboxPayloadTooLargeException`.

**Commands.** Each runs in its own transaction, locks the row, and takes the state the caller last saw, so a stale decision never acts on an entry that has moved on.

| Command | Accepted expected state | Result |
|---------|-------------------------|--------|
| `retry` | `DEAD_LETTER`, `DISCARDED` | Entry returns to `PENDING`: attempt reset to 0, lease and last error cleared, available now (or at its `scheduledAt` when that is still ahead), relay woken when the transaction commits. |
| `discard` | `PENDING`, `DEAD_LETTER`, `DISCARDED` | Entry becomes `DISCARDED`: attempt count and last error kept, lease cleared. An already `DISCARDED` entry succeeds with `changed = false`, whatever state the caller expected. |

- **Failures.** An id that is not positive or an expected state a command cannot act on fails with `ValidationException`; an unknown id with `OutboxEntryNotFoundException`; an entry that is not in the expected state with `OutboxEntryStateException`, whose `current()` says where it is. `PROCESSING` and `PUBLISHED` entries are never touched.
- **A second retry** finds the entry `PENDING` and fails with `OutboxEntryStateException`; a caller can read that as "already requeued".
- **Discarding `PENDING` drops a promise.** The row was recorded by a committed business transaction and nothing tells the code that recorded it, so for example a workflow still waits for the result of a discarded side effect. Discarding the oldest `PENDING` entry of an aggregate also releases the later entries of that aggregate to the relay.
- **Retry can reorder an aggregate.** A retried entry is delivered after later entries of the same aggregate that were delivered while it was parked.
- **Undo.** `retry` on a `DISCARDED` entry brings it back until the maintenance job removes it after `inboxOutbox.cleanup.discardedRetentionDays`.
- **Failures from the store** leave the services as `InboxOutboxPersistenceException` with a message that carries no SQL, driver text or payload; `retryable()` is `true` for a lock timeout.

### Exceptions

| Exception | Extends | Description |
|-----------|---------|-------------|
| `InboxOutboxConfigurationException` | core `ConfigurationException` | Configuration or contract failure raised during inbox/outbox startup or wiring — e.g. an invalid claim-scope supplier or a misconfigured destination handler. |
| `InboxOutboxTechnicalException` | core `TechnicalException` | Technical root for inbox/outbox infrastructure failures. |
| `InboxOutboxPersistenceException` | `InboxOutboxTechnicalException` | Raised when an inbox/outbox operation fails because of a persistence-layer error. Adapter modules translate the underlying data-access failure into this type at the API boundary, so callers of `InboxService`/`OutboxService` see inbox/outbox exceptions rather than raw data-access exceptions. `retryable()` reports whether re-attempting the operation has a reasonable chance of succeeding — `true` for transient infrastructure failures (deadlock, lock timeout, optimistic/pessimistic locking), `false` otherwise. |

---

### Framework seams

`OutboxBackoff`, `RelayStrategy`, `OutboxDeliveryMetadata`, `OutboxPermanentFailure`,
`DelayedJobControl`, `OutboxRelayControl`, and `TransactionalMessagingModule` are public for the
inbox-outbox adapters and sibling framework modules; their Javadoc marks them INTERNAL and they are
outside this module's compatibility promise. Applications install the store adapter module
(`TransactionalMessagingPostgresqlModule`), which includes the core wiring, alongside the
destination adapters they need (`TransactionalMessagingServiceModule`,
`TransactionalMessagingKafkaModule`, `TransactionalMessagingDelayedJobModule`), and program against `OutboxService`, `InboxService`, `OutboxQueryService`, `OutboxOperations`, the
record and result types, `ClaimScope`, `DestinationType`, `RelayCapabilities`, and the extension
points below.

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
      "discardedRetentionDays": 7,
      "inboxRetentionDays": 30,
      "cleanupBatchSize": 1000
    },
    "query": {
      "maxPayloadBytes": 1048576
    }
  },
  "cron": {
    "jobs": {
      "outbox-cleanup": { "cron": "0 0 */6 * * *" },
      "outbox-stale-lease-recovery": { "cron": "*/30 * * * * *" }
    }
  }
}
```

Cleanup cadence is owned by the framework's cron infrastructure, not by the cleanup config block itself. The legacy `cleanupIntervalHours` field was removed from the cleanup config; configs that still set it are accepted (and the value ignored) via `@JsonIgnoreProperties`. Tune cadence via `cron.jobs.<id>.cron` — e.g. `cron.jobs.outbox-cleanup.cron` (default above) and `cron.jobs.outbox-stale-lease-recovery.cron`.

Config classes: `OutboxRelayConfig` (`inboxOutbox.relay`), `InboxOutboxCleanupConfig` (`inboxOutbox.cleanup`), `OutboxQueryConfig` (`inboxOutbox.query`).

---

## Extension Points

### `OutboxDestinationHandler`

SPI for publishing outbox entries to a specific destination type. Register via Dagger `@IntoSet` multibinding.

```java
public interface OutboxDestinationHandler {
    DestinationType destinationType();
    ClaimScope claimScope();
    Future<OutboxPublishResult> publish(OutboxEnvelope envelope);
}
```

`claimScope()` is **mandatory** — there is no default. The absence of a default is deliberate: fail-closed means an adapter that forgets to implement the method does not compile rather than silently never claiming rows or silently claiming all rows. Return `ClaimScope.all()` for globally deliverable types (e.g., Kafka topics) and `ClaimScope.destinations(resolver::supportedTargetIds)` for resolver-scoped types.

A failed future from `publish()` is treated as a retryable adapter failure unless the adapter returned an explicit `permanent` or `unresolvable` result.

```java
@Provides @IntoSet
static OutboxDestinationHandler serviceHandler(ServiceOutboxDestinationHandler handler) {
    return handler;
}
```

The relay routes each claimed `OutboxRecord` to the handler whose `destinationType()` matches the row's `destination_type`. If no handler is registered for a type, the row is treated as unresolvable.

#### Implementing a custom destination

A new destination type registers entirely within the adapter module — no edit to `vertique-inbox-outbox-core` or `vertique-inbox-outbox-postgresql` is required:

```java
@Singleton
public class ExternalOutboxDestinationHandler implements OutboxDestinationHandler {

    public static final DestinationType EXTERNAL = DestinationType.of("EXTERNAL");

    private final ExternalPublisher publisher;

    @Inject
    ExternalOutboxDestinationHandler(ExternalPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public DestinationType destinationType() {
        return EXTERNAL;
    }

    @Override
    public ClaimScope claimScope() {
        // All rows of type EXTERNAL are deliverable by any relay node.
        return ClaimScope.all();
    }

    @Override
    public Future<OutboxPublishResult> publish(OutboxEnvelope envelope) {
        return publisher.send(envelope.destination(), envelope.payload())
            .map(ignored -> OutboxPublishResult.success())
            .recover(err -> Future.succeededFuture(
                OutboxPublishResult.retryable(err.getMessage(), err)));
    }
}
```

### `OutboxPublishObserver`

Observer-only SPI for every outbox publish attempt, of every destination type. The relay calls it
exactly once per claimed entry per attempt, after it has asked the repository to record the entry's
next state and that call has settled. Register via Dagger `@IntoSet` multibinding; the set is declared
by `TransactionalMessagingModule` and is empty by default.

```java
public interface OutboxPublishObserver extends OrderedExtension {
    default void onPublishCompleted(OutboxPublishCompletedEvent event, OutboxEnvelope envelope) {}
    default void onClaimCompleted(Duration duration, int claimed) {}
}
```

The relay also calls `onClaimCompleted` once per claim that completed, with or without entries, on the same context and with the
same observer-only contract. `duration` is the time from asking the repository for a batch to its completion and `claimed` is the
number of entries it returned (zero for an empty claim); a claim that failed is not reported. The report carries no entry,
aggregate id or payload.

**Aggregate-cardinality guidance.** The claim admits only the oldest unfinished entry of each aggregate, so a claim that is slow
for the number of entries it returns is the sign of many entries queued behind few aggregates, such as many service dispatches of
one workflow instance. Record `duration` and `claimed` as plain figures, for example a timer and a counter with no tag. An
aggregate id, a destination or an entry id must never become a metric label: the cardinality is unbounded, and the claim has none to
report. A workflow that dispatches several services in parallel branches still delivers them one at a time, because its entries
share one aggregate.

```java
@Provides @IntoSet
static OutboxPublishObserver publishMetrics(PublishMetricsObserver observer) {
    return observer;
}
```

`OutboxPublishCompletedEvent` carries framework facts only — no payload, headers, failure message or
cause. The payload, headers and metadata are on the `OutboxEnvelope` passed alongside it: the same
envelope the relay built for the destination handler, also present when no handler is registered for
the destination type.

| Component | Description |
|-----------|-------------|
| `entryId` | The outbox entry id, as a string |
| `destinationType` | The entry's `DestinationType` |
| `destination` | The entry's destination, equal to `envelope.destination()` |
| `attempt` | Attempts made before this one, `0` for the first; equal to `envelope.attempt()` |
| `maxAttempts` | The entry's attempt limit, as stored with the entry |
| `outcome` | `OutboxPublishOutcome`: `SUCCESS`, `RETRYABLE_FAILURE`, `PERMANENT_FAILURE`, `UNRESOLVABLE` |
| `disposition` | `OutboxEntryDisposition`: `PUBLISHED`, `RETRY_SCHEDULED`, `DEAD_LETTERED`, `DEFERRED` |
| `dispositionRecorded` | `true` only when the repository confirmed the state change; `false` when the repository call failed, threw, or reported that this relay no longer owned the entry |
| `nextAttemptAt` | Earliest time of the next attempt for `RETRY_SCHEDULED` and `DEFERRED`; `null` otherwise, and when the relay could not compute it. Approximate for `DEFERRED`: the relay computes it from its own clock, while the row is deferred from the database clock |
| `errorType` | Class name of the failure's cause; `null` when the attempt did not fail or the failure has no cause |
| `elapsed` | Time from the call to `publish` until its result; zero when no handler is registered for the destination type |
| `completedAt` | Time the relay notified the observers |

`outcome` is how the attempt ended; `disposition` is what the relay did with the entry. They differ
when a retryable failure uses up the last attempt: `RETRYABLE_FAILURE` with `DEAD_LETTERED`. A handler
that throws, returns a failed future, returns `null` instead of a future, or completes its future with
`null` is reported as `RETRYABLE_FAILURE`. An entry whose destination type has no registered handler
is reported as `UNRESOLVABLE` with `DEFERRED`.

#### Invariants & Gotchas

- **Observer-only.** Exceptions thrown by the callback are caught, logged, and swallowed; they do not
  affect the enclosing operation. An `AssertionError`, a `StackOverflowError` or a `LinkageError` is
  swallowed the same way. An observer changes neither the entry's state transition nor the relay's
  in-flight count or poll loop, and the observers after it still run. A swallowed failure is logged at
  WARN by class name, with the failure itself at DEBUG; a `LinkageError` is logged at ERROR at a
  limited rate per observer class — the first time, then at most once every five minutes.
- **The envelope is shared.** The destination handler and every observer receive the same
  `OutboxEnvelope` instance. Its payload object is not copied: do not modify it. It carries the
  payload, the headers and the durable context (which can include an identity snapshot), and its
  `toString()` prints all three: do not log the envelope or put it into an exception. Do not block
  in the callback, and do not keep the envelope after the callback returns — copy what you need.
- **Order.** `OrderedExtension`: phase, then ascending `priority()`, then `orderKey()`.
- **`attempt` is not a unique key.** A deferral and a reclaim of a stale claim leave the stored
  attempt count unchanged, so the same `entryId` and `attempt` can be notified more than once. Use
  `completedAt` to tell notifications for one entry apart.
- **The framework constructs the event.** Components may be added in later releases; read the
  accessors and do not construct the record or deconstruct it with a record pattern.
- **No notification** for an attempt whose handler future never settles, whose repository call never
  completes, or that is in flight when the relay stops and never completes. Nor for a claimed entry
  whose envelope the relay could not build: no attempt was made, and the entry stays claimed until
  stale-lease recovery.
- **Threading.** The callback runs on the relay's context — the event-loop context of the relay
  instance that made the attempt — whichever thread completes the repository call. Do not block in
  it. Several relay instances, each on its own context, may call one observer concurrently.
- **Outbox depth is not bounded by anything here.** Neither the claim report nor the workflow intake limit bounds the number of
  outbox entries: a command that runs can move its backlog into the outbox. Watch the claim report, and the age of pending entries
  in the table, to see it grow.

---

## Dependency Graph

`inbox-outbox-core` is deliberately minimal. Applications compose it with adapter modules:

```
inbox-outbox-core  <--  inbox-outbox-postgresql  (persistence + relay verticle)
                   <--  inbox-outbox-services     (SERVICE adapter)
                   <--  inbox-outbox-delayed-job  (DELAYED_JOB adapter)
                   <--  inbox-outbox-kafka        (KAFKA adapter)
```
