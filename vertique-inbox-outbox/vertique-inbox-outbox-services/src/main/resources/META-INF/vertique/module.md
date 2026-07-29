<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Inbox/Outbox Services Module

> **Status:** Implemented
> **Package:** `dev.vertique.inboxoutbox.services`
> **Artifact:** `vertique-inbox-outbox-services`
> **Depends on:** inbox-outbox-core, services

Service adapter for Transactional Messaging. Provides two components: `TransactionalServiceClientFactory` (authoring — lets application code record a transactional service side-effect without constructing a raw `OutboxEntry`) and `ServiceOutboxDestinationHandler` (relay — resolves stable service target ids at relay time and dispatches using event bus request/reply semantics).

---

## Key Classes

### `TransactionalServiceClientFactory`

Creates JDK dynamic proxies for `@ServiceContract` interfaces that record outbox rows instead of sending event bus messages directly. Use this when you want to publish a service call as a transactional side-effect that the relay will deliver after commit.

```java
@Provides @Singleton
static NotificationService transactionalNotificationClient(
        TransactionalServiceClientFactory factory) {
    return factory.create(NotificationService.class);
}
```

Invoking a method on the returned proxy:
1. Resolves the stable service target id from contract metadata via `ServiceTargetResolver`.
2. Serializes the payload argument.
3. Calls `OutboxService.publish(tx, OutboxEntry)` with `destinationType = SERVICE` and `destination = stableTargetId`.
4. Returns a `Future<Long>` carrying the outbox entry id — not the future result of the service call.

The factory validates at startup that all methods on the interface satisfy the service authoring constraints. Validation fails if any method:
- is annotated with `@OneWay`
- has an unwrapped return type that is not `Void`
- has no payload parameter
- has more than one payload parameter
- requires `SecurityContext` or other transport-injected parameters
- does not resolve to a stable service target id

Validation failures throw `TransactionalMessagingConfigurationException` on first use of the factory.

**Usage pattern:**

```java
// Transactional proxy — records outbox row, not a live call
@Inject NotificationService txNotification;
@Inject OutboxService outboxService;

pool.withTransaction(tx ->
    orderRepository.save(order, tx)
        .compose(v -> txNotification.sendOrderConfirmation(
            new OrderConfirmationPayload(order.id()), tx))
);
```

The `tx` `SqlClient` is passed as a trailing argument. The proxy intercepts it, uses it for the `OutboxService.publish()` call, and does not forward it to any real service.

### `ServiceOutboxDestinationHandler`

`OutboxDestinationHandler` implementation for `DestinationType.SERVICE`. Registered by `TransactionalMessagingServiceModule` into the `Set<OutboxDestinationHandler>` multibinding.

**Claim scope:** Returns `ClaimScope.destinations(ServiceTargetResolver::supportedTargetIds)` from `claimScope()`. This node claims only `SERVICE` outbox rows whose `destination` value is present in the resolver's supported target id set — i.e., service targets locally registered on this node. Rows for targets not reachable here are left for another node that hosts the relevant service.

At relay time:
1. Reads `destination` (stable service target id) from the `OutboxEnvelope`.
2. Resolves the current event bus address via `ServiceTargetResolver`.
3. If the target id is not resolvable (e.g., service not deployed on this node), returns `OutboxPublishResult.unresolvable()`.
4. Builds a `DispatchEnvelope` via `DispatchEnvelopeBuilder` with the payload and the application headers (`OutboxEnvelope.headers`, application-only). The durable propagation context is read from `OutboxEnvelope.metadata().context()` (NOT from headers) and decoded via `DurableContextPropagator.decodeToDispatchContext(metadata.context(), ...)`, then merged into the caller-override map — no holder write (the relay runs on the verticle's deployment context, not a duplicated context). Relay control in `metadata.delivery` is ignored on the service path. Before building the envelope, a `DeferredExecutionOrigin` (`kind = "outbox-relay"`, `reference` = the event type) is also merged into the caller-override map, proving the dispatch is deferred execution for the opt-in identity-snapshot reconstruction initializer.
5. Sends via event bus request/reply with the service's configured timeout.
6. On a successful reply: returns `OutboxPublishResult.success()`.
7. On a failed reply from the service: returns `OutboxPublishResult.retryable(error, errorType)`.
8. Rejects `@OneWay` service targets — any operation resolving to a `@OneWay` method returns `OutboxPublishResult.permanent(...)`.

`SERVICE` delivery guarantee: at-least-once handoff to the service. The service must be idempotent or use `InboxService` for dedup if needed.

---

## Extension Points

None beyond the `OutboxDestinationHandler` SPI already defined in `inbox-outbox-core`. `ServiceOutboxDestinationHandler` is the concrete extension point implementation for `SERVICE` destinations.

---

## Dagger Wiring

```java
@Module
public abstract class TransactionalMessagingServiceModule {

    @Provides @IntoSet
    static OutboxDestinationHandler serviceHandler(
            ServiceOutboxDestinationHandler handler) {
        return handler;
    }

    @Provides @Singleton
    static TransactionalServiceClientFactory transactionalClientFactory(
            ServiceTargetResolver resolver,
            OutboxService outboxService) {
        return new TransactionalServiceClientFactory(resolver, outboxService);
    }
}
```

Include `TransactionalMessagingServiceModule` alongside `TransactionalMessagingPostgresqlModule` and `DispatchModule`:

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
