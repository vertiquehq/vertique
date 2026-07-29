<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Inbox/Outbox Core Module

> **Status:** Implemented
> **Package:** `dev.vertique.inboxoutbox`
> **Artifact:** `vertique-inbox-outbox-core`
> **Depends on:** core, db-core

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
String messageId = record.header("x-message-id").orElseThrow();

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
| `headers` | No | Additional outbound metadata; may carry adapter-specific snapshot data |
| `scheduledAt` | No | Time after which the relay may publish |
| `availableAt` | No | Internal retry scheduling override; applications normally omit |

For `SERVICE` destinations, `destination` must be a stable service target id (not a raw event bus address). For `DELAYED_JOB` destinations, `destination` must be a durable delayed-job target id. For `KAFKA` destinations, `destination` is the Kafka topic name. For custom adapter destinations, the meaning of `destination` is defined by the adapter's `OutboxDestinationHandler`.

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
| `headers` | `Map<String, String>` | Application/transport headers only (no framework keys) |
| `scheduledAt` | `Instant` | Optional scheduled publish time |
| `availableAt` | `Instant` | Earliest publish time after backoff |
| `state` | `OutboxEntryState` | `PENDING`, `PROCESSING`, `PUBLISHED`, `DEAD_LETTER` |
| `attempt` | `int` | Current attempt count (incremented at retry) |
| `maxAttempts` | `int` | Maximum attempts before dead-letter |
| `claimedAt` | `Instant` | When the relay claimed this row |
| `claimedBy` | `String` | Node id that claimed this row |
| `publishedAt` | `Instant` | When the row transitioned to `PUBLISHED` |
| `lastError` | `String` | Last error message |
| `errorType` | `String` | Last error type |

### `OutboxEntryState`

Relay state machine for outbox rows.

| State | Description |
|-------|-------------|
| `PENDING` | Waiting for relay to claim |
| `PROCESSING` | Claimed by relay; publication in progress |
| `PUBLISHED` | Successfully published to destination |
| `DEAD_LETTER` | Exhausted attempts or permanent failure |

### `OutboxEnvelope`

Immutable record passed to `OutboxDestinationHandler.publish()` at relay time.

| Field | Description |
|-------|-------------|
| `entryId` | Outbox row id |
| `aggregateType` / `aggregateId` / `eventType` | Row columns (used e.g. for the Kafka message key) |
| `destination` / `payload` / `scheduledAt` / `attempt` / `createdAt` | Delivery fields from the row |
| `headers` | Application/transport headers only (`OutboxEntry.headers`) — no framework keys |
| `metadata` | `OutboxMetadata`: durable propagation context (`context`) plus delivery control (`delivery.outbox`: `OutboxRelayControl`, relay control projected from the row columns; `delivery.delayedJob`: scheduling) |

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

**Supplier contract (fail-closed).** The relay validates the supplier result before use. Any of the following causes the entire claim cycle to fail closed (zero rows claimed for this cycle; the relay retries on the next tick):
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

### Exceptions

| Exception | Extends | Description |
|-----------|---------|-------------|
| `InboxOutboxConfigurationException` | core `ConfigurationException` | Configuration or contract failure raised during inbox/outbox startup or wiring — e.g. an invalid claim-scope supplier or a misconfigured destination handler. |
| `InboxOutboxTechnicalException` | core `TechnicalException` | Technical root for inbox/outbox infrastructure failures. |
| `InboxOutboxPersistenceException` | `InboxOutboxTechnicalException` | Raised when an inbox/outbox operation fails because of a persistence-layer error. Adapter modules translate the underlying data-access failure into this type at the API boundary, so callers of `InboxService`/`OutboxService` see inbox/outbox exceptions rather than raw data-access exceptions. `retryable()` reports whether re-attempting the operation has a reasonable chance of succeeding — `true` for transient infrastructure failures (deadlock, lock timeout, optimistic/pessimistic locking), `false` otherwise. |

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
      "outbox-cleanup": { "cron": "0 0 */6 * * *" },
      "outbox-stale-lease-recovery": { "cron": "*/30 * * * * *" }
    }
  }
}
```

Cleanup cadence is owned by the framework's cron infrastructure, not by the cleanup config block itself. The legacy `cleanupIntervalHours` field was removed from the cleanup config; configs that still set it are accepted (and the value ignored) via `@JsonIgnoreProperties`. Tune cadence via `cron.jobs.<id>.cron` — e.g. `cron.jobs.outbox-cleanup.cron` (default above) and `cron.jobs.outbox-stale-lease-recovery.cron`.

Config classes: `OutboxRelayConfig` (`inboxOutbox.relay`), `InboxOutboxCleanupConfig` (`inboxOutbox.cleanup`).

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

---

## Dependency Graph

`inbox-outbox-core` is deliberately minimal. Applications compose it with adapter modules:

```
inbox-outbox-core  <--  inbox-outbox-postgresql  (persistence + relay verticle)
                   <--  inbox-outbox-services     (SERVICE adapter)
                   <--  inbox-outbox-delayed-job  (DELAYED_JOB adapter)
                   <--  inbox-outbox-kafka        (KAFKA adapter)
```
