<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Services Module

> **Status:** Beta
> **Package:** `dev.vertique.services`
> **Artifact:** `vertique-services`
> **Depends on:** core, context, correlation, deploy, logging, security-core, security-runtime

`vertique-services` provides typed, in-process service calls over the Vert.x event bus. Applications
define annotated Java interfaces, implement them as ordinary injectable classes, and call them
through typed clients while Vertique owns addressing, dispatch context, lifecycle, failure
transport, and declarative resilience.

Use services as an application boundary, not as a remote-service protocol. The current transport is
the local Vert.x event bus and therefore assumes one application process.

---

## When To Use It

Use this module when an application needs:

- a typed boundary between independently testable application capabilities;
- asynchronous `Future<T>` request/reply without exposing event-bus addresses to callers;
- service-level timeout, retry, circuit-breaker, authorization, or observability policies;
- isolated service deployment and supervision within one Vert.x application; or
- a durable service-operation identity for workflows, outbox delivery, or scheduled work.

For a simple function call within one cohesive component, prefer a normal injected Java interface.
For communication between processes, use a network or broker integration with an explicit
serialization and compatibility contract.

---

## Core Concepts

### Contracts and operations

Annotate an interface with `@ServiceContract`. Its `namespace` and `value` form the logical service
identity. Each method is an operation and must return `Future<T>` or `Future<Void>`.

`@ServiceOperation("id")` gives an operation a durable identity. Without it, the Java method name is
used for runtime routing and the operation cannot be resolved as a stable target.

| Declaration | Runtime address | Stable target id |
|---|---|---|
| namespace `integration`, service `users`, operation `find` | `services/integration/users/find` | `integration.users.find` |
| no namespace, service `users`, operation `ping` without `@ServiceOperation` | `services/users/ping` | none |

Changing a namespace, service name, or explicit operation id breaks durable references that store
the stable target id.

### Handler implementations

The recommended server shape implements `ServiceHandler<Contract>` instead of the contract itself.
Handler methods match contract methods by Java method name and may add framework-provided parameters
such as `SecurityContext`. This keeps caller-visible contracts free of server-only context.

Direct implementations (`implements Contract`) remain supported. Do not register both a direct
implementation and a handler for the same contract.

### Generated registration and typed clients

The services annotation processor discovers service implementations and generates
`GeneratedServicesModule`, which contributes compile-time-validated contract metadata and
implementation providers. Add that generated module and `DispatchModule` to the application
component.

Callers currently obtain a typed JDK proxy from `ServiceClientFactory.create(Contract.class)`. The
proxy captures dispatch context, resolves operation metadata, and delegates transport concerns to
the framework.

### Delivery semantics

Request/reply is the default. The returned future completes with the service result or a transported
failure.

`@OneWay` uses fire-and-forget delivery and is restricted to `Future<Void>`. Successful completion
means only that the message was submitted to the event bus. It does not prove that a consumer
received or processed it, and server failures cannot reach the caller.

### Execution and lifecycle

Each registered service contract is deployed as an isolated Vert.x verticle. Operations run on the
event loop by default; blocking handlers must use worker deployment (`worker: true`) or move blocking
work behind an appropriate asynchronous boundary.

`DispatchModule` contributes service startup and shutdown steps. Applications using the standard
Vertique lifecycle do not call a deployment manager directly.

---

## Getting Started

### 1. Define a contract

```java
package com.example.users;

import dev.vertique.core.resilience.Retry;
import dev.vertique.core.resilience.Timeout;
import dev.vertique.services.OneWay;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;

@ServiceContract(namespace = "accounts", value = "users")
public interface UserService {

    @ServiceOperation("display-name")
    @Timeout(2_000)
    @Retry(maxRetries = 2, delayMs = 50)
    Future<String> displayName(String userId);

    @OneWay
    @ServiceOperation("invalidate-cache")
    Future<Void> invalidateCache(String userId);
}
```

At most one parameter may be a domain payload. A contract may declare dispatch-context values, but
server-only values such as `SecurityContext` are better added to a `ServiceHandler` method.

### 2. Implement a handler

```java
package com.example.users;

import dev.vertique.security.SecurityContext;
import dev.vertique.services.ServiceHandler;
import io.vertx.core.Future;
import jakarta.inject.Inject;

public final class UserServiceHandler implements ServiceHandler<UserService> {

    @Inject
    public UserServiceHandler() {}

    public Future<String> displayName(String userId, SecurityContext securityContext) {
        return Future.succeededFuture("User " + userId);
    }

    public Future<Void> invalidateCache(String userId) {
        return Future.succeededFuture();
    }
}
```

Handler matching is strict:

- method names match the Java symbols on the contract, not `@ServiceOperation` values;
- contract and handler overloads are rejected;
- payload parameter order and types must match;
- extra handler parameters must be framework-injectable; and
- generic `Future<T>` return types must match exactly.

Registration failures are aggregated and reported at startup rather than discovered on first call.

### 3. Include generated and runtime modules

```java
package com.example;

import com.example.users.GeneratedServicesModule;
import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.starter.services.ServicesApplicationModule;
import jakarta.inject.Singleton;

@VertiqueApp
@Singleton
@Component(
        modules = {
            ServicesApplicationModule.class,
            GeneratedServicesModule.class
        })
interface ApplicationComponent extends VertiqueApplicationComponent {}
```

`ServicesApplicationModule` includes `DispatchModule` plus the core lifecycle and management
foundation. Applications using `vertique-app-parent` receive the processor facade through the
supported build boundary. Custom-parent builds configure `vertique-codegen-all` as described by the
public packaging guide. If generated registration does not fit a deliberate custom binding,
annotate the implementation with `@NoAutoWire` and contribute it manually to
`@Services Set<Object>`. Because `@NoAutoWire` is source-retained, applications using that opt-out
also declare `dev.vertique:vertique-codegen-core` with `provided` scope as documented in
`docs/packaging.md`.

### 4. Bind and use a client

```java
package com.example.users;

import dagger.Module;
import dagger.Provides;
import dev.vertique.services.ServiceClientFactory;
import jakarta.inject.Singleton;

@Module
public final class UserClientModule {

    private UserClientModule() {}

    @Provides
    @Singleton
    static UserService userService(ServiceClientFactory factory) {
        return factory.create(UserService.class);
    }
}
```

Inject and call `UserService` like any other asynchronous dependency. Do not construct event-bus
addresses in application code.

---

## Key Classes

### `@ServiceContract`, `@ServiceOperation`, and `@OneWay`

These annotations define the public service protocol:

| Type | Purpose | Important constraint |
|---|---|---|
| `@ServiceContract` | Stable namespace and service name | Only interfaces are contracts |
| `@ServiceOperation` | Stable operation id and address segment | Required for durable target resolution |
| `@OneWay` | Fire-and-forget operation | Method must return `Future<Void>` |

Resilience annotations from `dev.vertique.core.resilience` may be placed on contracts or methods.
Method annotations override contract-level values.

### `ServiceHandler<C>`

Marker interface for an implementation that keeps framework-injected values off the caller-visible
contract. `SecurityContext` and types annotated with `@DispatchContextValue` may be added to handler
methods when a matching receive-side decoder is installed.

### `ServiceClientFactory`

`create(Class<T>)` returns the current typed client implementation for a registered contract.
Creation fails with `IllegalArgumentException` when the contract is absent from the registry.

The client automatically propagates registered dispatch-context values and MDC. When a
`SecurityContext` is bound to the current Vert.x context and the matching security dispatch codecs
are installed, it is captured without an explicit contract parameter.

### `ServiceTargetResolver`

Use the injected `ServiceTargetResolver` when another subsystem must persist or later resolve a
service operation:

```java
ResolvedServiceTarget target =
        targetResolver.resolve("accounts.users.display-name");

String durableId = target.targetId();
String currentAddress = target.address();
```

Persist `targetId()`, not `address()`. Only methods with explicit `@ServiceOperation` ids appear in
the resolver.

### `DispatchModule`

Include `DispatchModule` in the application component to install dispatch, deployment, health,
context, correlation, logging, and security-event infrastructure. It also declares empty
multibindings for application extension points.

When `@RequiresAction` is used on service contracts or operations, install the authorization engine.
Vertique validates the action gates at component construction and fails closed when the required
authorization bindings are absent.

---

## Resilience

The annotations `@Timeout`, `@Retry`, and `@CircuitBreaker` are defined by
`dev.vertique:vertique-core` and enforced server-side.

Effective policy order is timeout/circuit-breaker around retry. A configured timeout applies per
attempt. `@Retry.abortOn` wins over `retryOn`; when `retryOn` is empty, failures not matched by
`abortOn` are eligible for retry.

JSON configuration can override annotation values for an environment without changing the service
contract. Invalid values fail during startup parsing.

---

## Security and Dispatch Context

Outbound context propagation is registry-based. Installed encoders capture typed values into the
dispatch envelope; receive-side decoders restore them for the service invocation. MDC, correlation,
and security integrations use this same boundary.

Prefer a handler-only `SecurityContext` parameter:

```java
public Future<String> displayName(String userId, SecurityContext securityContext) {
    securityContext.snapshot();
    return Future.succeededFuture("User " + userId);
}
```

`@RequiresAction` adds a framework authorization gate. Authorization denials and identity-snapshot
degradation failures are non-recoverable; an application interceptor cannot turn them into a
successful dispatch.

---

## Extension Points

### `ServiceInterceptor`

Contribute ordered cross-cutting behavior through Dagger multibinding:

```java
@Provides
@IntoSet
static ServiceInterceptor serviceMetrics(ServiceMetricsInterceptor interceptor) {
    return interceptor;
}
```

Callbacks have distinct semantics:

| Callback | Role | Can change the dispatch outcome? |
|---|---|---|
| `beforeDispatch` | Async enrichment or precondition | Yes; failure short-circuits |
| `onDispatch` | Synchronous pre-dispatch observation | No |
| `onComplete` | Observe handler outcome before recovery | No |
| `afterDispatch` | Async post-processing | No; failures are logged |
| `recoverError` | Attempt application recovery | Yes; first successful recovery wins |
| `onTerminalComplete` | Observe final post-recovery outcome | No |

Interceptors follow the `OrderedExtension` order: phase, ascending priority, then stable
`orderKey`. Use `onComplete` for the raw handler outcome and `onTerminalComplete` for the final
outcome after recovery; they are intentionally different signals.

### `ServiceExceptionMapperCustomizer`

Contribute exception translation at the service boundary:

```java
@Provides
@IntoSet
static ServiceExceptionMapperCustomizer serviceErrors() {
    return mapper -> mapper.on(
            IllegalArgumentException.class,
            error -> new IllegalStateException(error.getMessage(), error));
}
```

Customizers follow `OrderedExtension`; registrations applied later win for the same exception type.

### `ServiceContractContributor`

This advanced SPI lets another framework module register service-shaped handlers without
`@ServiceContract` discovery. Build entries with `ServiceContractEntries`; do not instantiate
dispatch metadata records directly. Contributors participate in the same collision validation,
deployment, policies, and lifecycle as application contracts.

Use generated service registration for normal applications.

### Manual `@Services` contribution

Manual registration remains available for implementations that intentionally opt out of codegen:

```java
@Provides
@IntoSet
@Services
static Object userService(UserServiceHandler handler) {
    return handler;
}
```

This is an explicit fallback, not an additional registration to combine with the generated entry.

---

## Configuration

Configuration lives under `services`. The empty namespace uses `_` as its configuration key.

```json
{
  "services": {
    "sendTimeoutMs": 30000,
    "contracts": {
      "accounts": {
        "users": {
          "instances": 2,
          "worker": false,
          "sendTimeoutMs": 10000,
          "supervision": {
            "maxRestarts": 5,
            "withinMs": 60000,
            "initialBackoffMs": 1000,
            "maxBackoffMs": 30000
          },
          "operations": {
            "display-name": {
              "sendTimeoutMs": 5000,
              "timeout": {
                "valueMs": 2000
              },
              "retry": {
                "maxRetries": 2,
                "delayMs": 50,
                "backoffMultiplier": 2.0,
                "maxDelayMs": 1000
              },
              "circuitBreaker": {
                "maxFailures": 5,
                "timeoutMs": 2000,
                "resetTimeoutMs": 30000
              }
            }
          }
        }
      }
    }
  }
}
```

| Key | Default | Constraint |
|---|---:|---|
| `services.sendTimeoutMs` | `30000` | greater than 0 |
| `contracts.{ns}.{name}.instances` | `1` | at least 1 |
| `contracts.{ns}.{name}.worker` | `false` | boolean |
| `contracts.{ns}.{name}.sendTimeoutMs` | global value | greater than 0 |
| `supervision.maxRestarts` | `5` | see deploy supervision contract |
| `supervision.withinMs` | `60000` | milliseconds |
| `supervision.initialBackoffMs` | `1000` | milliseconds |
| `supervision.maxBackoffMs` | `30000` | milliseconds |
| `operations.{op}.sendTimeoutMs` | service value | greater than 0 |
| `operations.{op}.timeout.valueMs` | annotation value | greater than 0 |
| `operations.{op}.retry.maxRetries` | annotation value | at least 0 |
| `operations.{op}.retry.delayMs` | annotation value | at least 0 |
| `operations.{op}.retry.backoffMultiplier` | annotation value | at least 1.0 |
| `operations.{op}.retry.maxDelayMs` | annotation value | at least 0 |
| `operations.{op}.circuitBreaker.maxFailures` | annotation value | at least 1 |
| `operations.{op}.circuitBreaker.timeoutMs` | annotation value | greater than 0 |
| `operations.{op}.circuitBreaker.resetTimeoutMs` | annotation value | greater than 0 |

Operation keys are resolved operation ids: the `@ServiceOperation` value when present, otherwise the
Java method name.

---

## Failures, Constraints, and Common Mistakes

- Service contracts must be interfaces and operations must return parameterized `Future<T>`.
- Contract and handler overloads are rejected; operation identity must be unambiguous.
- A contract operation has at most one payload parameter. `DispatchEnvelope<?>` is a transport
  wrapper and is not a valid contract parameter.
- `@OneWay` is best-effort and does not provide delivery or processing confirmation.
- Do not block the event loop. Configure a worker service only when the handler genuinely performs
  blocking work.
- Do not persist event-bus addresses. Persist stable target ids from explicitly annotated
  operations.
- Do not bind a generated and manual implementation for the same contract.
- Do not use interceptor recovery for authorization or identity-degradation failures; Vertique
  marks those failures non-recoverable.

Transport failures are enriched as service failures:

| Failure | Meaning |
|---|---|
| `ServiceTimeoutException` | request/reply exceeded the effective send timeout |
| `ServiceUnavailableException` | supervisor unavailable or no event-bus handler |
| `ServiceDispatchException` | other service transport failure |
| `ServiceRegistrationException` | one or more contract/handler validation failures at startup |

---

## Verification

For a focused module check from the repository root:

```bash
./mvnw -ntp -pl vertique-services -am test
```

The public examples `examples/vertique-example-services` and
`examples/vertique-example-services-codegen` demonstrate generated registration, handler
implementations, typed clients, authorization, and lifecycle wiring.

---

## Dependencies

- `dev.vertique:vertique-core` — async results, event-bus envelopes, lifecycle, resilience, and
  ordered extensions.
- `dev.vertique:vertique-context` — typed dispatch-context capture and restoration.
- `dev.vertique:vertique-correlation` and `dev.vertique:vertique-logging` — correlation and MDC
  propagation.
- `dev.vertique:vertique-deploy` — verticle deployment, supervision, and lifecycle integration.
- `dev.vertique:vertique-security-core` and `dev.vertique:vertique-security-runtime` — security
  context, action authorization, identity-degradation policy, and security events.
- `io.vertx:vertx-core` and `io.vertx:vertx-circuit-breaker` — event-bus transport and resilience.
- Dagger and Jakarta Inject — application wiring and extension multibindings.
