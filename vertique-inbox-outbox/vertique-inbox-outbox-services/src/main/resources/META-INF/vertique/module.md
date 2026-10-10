<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Inbox/Outbox Services Module

> **Status:** Stable
> **Package:** `dev.vertique.inboxoutbox.services`
> **Artifact:** `vertique-inbox-outbox-services`
> **Depends on:** inbox-outbox-core, services, context, logging, security-core

Service adapter for Transactional Messaging. Provides two components: `TransactionalServiceClientFactory`
(authoring — records a transactional service side-effect without constructing a raw `OutboxEntry`)
and `ServiceOutboxDestinationHandler` (relay — resolves stable service target ids at relay time and
dispatches using event bus request/reply semantics).

---

## Key Classes

### `TransactionalServiceClientFactory`

Creates a **transaction-scoped** JDK dynamic proxy for a `@ServiceContract` interface. Each method
invocation on the proxy records an outbox row inside the `SqlClient` captured at `create` time;
the relay delivers the call after commit.

```java
pool.withTransaction(tx -> {
    NotificationService txNotification =
            factory.create(NotificationService.class, tx);
    return orderRepository.save(order, tx)
            .compose(v -> txNotification.sendOrderConfirmation(
                    new OrderConfirmationPayload(order.id())));
});
```

**`create(Class<T> contract, SqlClient tx)`** validates every non-`Object` method on the contract
immediately and throws `IllegalArgumentException` if any rule fails. The proxy is not
application-scoped — create it inside the open transaction and do not inject it as a singleton.

Invoking a method on the returned proxy:
1. Looks up the precomputed stable service target id for that method.
2. Takes the single payload argument (proxy methods take the payload only — not `SqlClient`).
3. Calls `OutboxService.publish(tx, OutboxEntry)` with `destinationType = SERVICE` and
   `destination = stableTargetId`.
4. Returns `publish(...).mapEmpty()` — a `Future<Void>`. The contract method must itself declare
   `Future<Void>`; the proxy cannot return the outbox entry id or the eventual service result.

A `null` payload argument is not rejected by the proxy. It builds the entry with a `null` payload;
the outbox `payload` column is `NOT NULL`, so the insert fails, the returned future fails with it,
nothing is stored and the target service is never called.

Validation rules (all checked at `create`):
- Every non-`Object` method must carry `@ServiceOperation` (this is what supplies the stable target id)
- `@OneWay` operations are rejected — the relay needs request/reply confirmation
- Return type must be `Future<Void>`
- Exactly one payload parameter
- No `SecurityContext` or other dispatch-context / transport-injected parameters

### `ServiceOutboxDestinationHandler`

`OutboxDestinationHandler` implementation for `DestinationType.SERVICE`. Registered by
`TransactionalMessagingServiceModule` into the `Set<OutboxDestinationHandler>` multibinding.

**Claim scope:** Returns `ClaimScope.destinations(ServiceTargetResolver::supportedTargetIds)` from
`claimScope()`. This node claims only `SERVICE` outbox rows whose `destination` value is present in
the resolver's supported target id set — i.e., service targets locally registered on this node.
Rows for targets not reachable here are left for another node that hosts the relevant service.

At relay time:
1. Reads `destination` (stable service target id) from the `OutboxEnvelope`.
2. Resolves the current event bus address via `ServiceTargetResolver`.
3. If the target id is not resolvable (e.g., service not deployed on this node), returns
   `OutboxPublishResult.unresolvable(message)`.
4. Builds a `DispatchEnvelope` via `DispatchEnvelopeBuilder` with the payload and the application
   headers (`OutboxEnvelope.headers`, application-only). The durable propagation context is read
   from `OutboxEnvelope.metadata().context()` (NOT from headers) and decoded via
   `DurableContextPropagator.decodeToDispatchContext(metadata.context(), ...)`, then merged into
   the caller-override map — no holder write (the relay runs on the verticle's deployment context,
   not a duplicated context). The per-row carrier is reproduced from
   `metadata.delivery.outbox().carrierId()` so a transplanted identity snapshot fails closed. Relay
   control in `metadata.delivery` is otherwise ignored on the service path. Before building the
   envelope, a `DeferredExecutionOrigin` (`kind = "outbox-relay"`, `reference` = the event type) is
   also merged into the caller-override map, proving the dispatch is deferred execution for the
   opt-in identity-snapshot reconstruction initializer.
5. Sends via event bus request/reply with the service's configured timeout.
6. On a successful reply: returns `OutboxPublishResult.success()`.
7. On a failed reply from the service: returns `OutboxPublishResult.retryable(message, cause)`.
8. Rejects `@OneWay` service targets — any operation resolving to a `@OneWay` method returns
   `OutboxPublishResult.permanent(...)`.
9. A stored payload that cannot be decoded to the target's payload type, or a durable propagation
   context that cannot be decoded, returns `OutboxPublishResult.permanent(message, cause)` instead
   of being retried: the stored entry does not change between attempts. The message names the
   target and never the payload content; the decode failure is the cause.

`SERVICE` delivery guarantee: at-least-once handoff to the service. The service must be idempotent
or use `InboxService` for dedup if needed.

---

## Extension Points

None beyond the `OutboxDestinationHandler` SPI already defined in `inbox-outbox-core`.
`ServiceOutboxDestinationHandler` is the concrete extension point implementation for `SERVICE`
destinations.

---

## Dagger Wiring

`TransactionalMessagingServiceModule` includes `ContextRuntimeModule` and `LoggingContextModule`.
It contributes only the destination handler; `TransactionalServiceClientFactory` is a `@Singleton`
with an `@Inject` constructor and is JIT-bound — there is no `@Provides` factory method.

```java
@Module(includes = {ContextRuntimeModule.class, LoggingContextModule.class})
public abstract class TransactionalMessagingServiceModule {

    @Provides @IntoSet
    static OutboxDestinationHandler serviceHandler(
            ServiceOutboxDestinationHandler handler) {
        return handler;
    }
}
```

Include alongside `TransactionalMessagingPostgresqlModule` and `DispatchModule`:

```java
@Component(modules = {
    VertxModule.class,
    DispatchModule.class,
    DbPostgresqlModule.class,
    DbFlywayModule.class,
    TransactionalMessagingPostgresqlModule.class,
    TransactionalMessagingServiceModule.class,
    AppModule.class
})
public interface AppComponent { ... }
```
