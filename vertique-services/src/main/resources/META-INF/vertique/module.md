<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Services Module

> **Status:** Implemented
> **Package:** `dev.vertique.services`
> **Artifact:** `services`
> **Depends on:** core, deploy

### Package Layout

| Package | Contents |
|---------|----------|
| `dev.vertique.services` | `@ServiceContract`, `@ServiceOperation`, `@Services`, `ServiceContractRegistry`, `ServiceContractContributor`, `ServiceContractEntries`, `ServiceRegistrar`, `ContractDiscovery`, `MethodValidator`, `ParameterClassifier`, `ReturnTypeResolver`, `PolicyResolver`, `OperationIdResolver`, `ServiceTargetResolver`, `DefaultServiceTargetResolver`, `ResolvedServiceTarget`, `ServiceAddressing`, `ServiceDeploymentManager`, `ServiceVerticle`, `ServiceSupervisor`, `ServiceRequestSender`, `ServiceClientFactory`, `DispatchModule`, `ServiceTimeoutException`, `ServiceDispatchException`, `ServiceUnavailableException` |
| `dev.vertique.services.dispatch` | `ServiceMethodInvoker`, `ServiceMethodMeta`, `ServiceMethodDescriptor`, `DispatchPipeline`, `DispatchContext`, `NonRecoverableDispatchFailure` |
| `dev.vertique.services.interceptor` | `ServiceInterceptor`, `ServiceDispatchContext`, `ServiceAuthorizationInterceptor`, `NonRecoverableForbiddenException` |
| `dev.vertique.services.policy` | `PolicyStage`, `PolicyChainBuilder`, `DispatchPipeline` |
| `dev.vertique.core.resilience` | `@Timeout`, `@CircuitBreaker`, `@Retry` — shared with `rest-client`; see `dev.vertique:vertique-core` |

Event bus-based service dispatch with contract-first interfaces and declarative resilience policies. Defines services as annotated Java interfaces, deploys them as isolated Vert.x verticles, and provides typed clients for transparent event bus communication — generated `{Contract}_ServiceClientProxy` companions when `vertique-codegen-services` is on the build path, with a JDK dynamic proxy fallback otherwise.

---

## How It Works

1. Annotate a Java interface with `@ServiceContract` and declare methods with optional `@ServiceOperation` and resilience policy annotations
2. Implement the interface directly, or implement `ServiceHandler<Contract>` for the handler pattern (auto-injected SecurityContext)
3. Contribute the implementation to the `@Services` multibinding
4. At startup, `ServiceRegistrar` scans implementations, validates the contract, and builds `ServiceMethodMeta`
5. `ServiceContractRegistry.build()` assembles the registry (including a stable-target-id index); `ServiceDeploymentManager.deployAll()` deploys one `ServiceVerticle` per contract
6. Each `ServiceVerticle` registers an event bus consumer per operation, wrapping invocations with a `DispatchPipeline` built from policy annotations
7. Callers obtain a typed client from `ServiceClientFactory.create(Contract.class)` and invoke methods as plain Java calls — `create()` selects a generated `{Contract}_ServiceClientProxy` companion when one is on the classpath, otherwise builds a JDK dynamic proxy; either way, the client serializes the payload into a `DispatchEnvelope<?>` and sends it over the event bus

---

## Key Classes

### `@ServiceContract`

Type-level annotation that marks an interface as a service contract with a stable logical identity. The `namespace` and `value` attributes form the base address: `services/{namespace}/{value}` (or `services/{value}` when namespace is absent).

```java
@ServiceContract(namespace = "integration", value = "user-service")
public interface UserService {

    @ServiceOperation("get-user")
    @Timeout(5000)
    @CircuitBreaker(maxFailures = 5, timeoutMs = 3000)
    Future<UserResponse> getUser(String userId);

    @CircuitBreaker
    Future<List<UserResponse>> listUsers();

    @CircuitBreaker
    Future<Void> deleteUser(String userId);
}
```

| Attribute | Default | Description |
|-----------|---------|-------------|
| `namespace` | `""` (absent) | Optional namespace segment; when non-empty, prepended to both the address and stable target id. No implicit default — empty means no namespace categorization. |
| `value` | — (required) | Durable service name; combined with namespace to form `services/{namespace}/{value}` base address |

**Durability note:** Changing `namespace` or `value` is a contract-breaking change for any durable system that persists stable target ids (e.g., Transactional Messaging outbox rows).

### `@ServiceOperation`

Optional method-level annotation that declares a durable operation identity. When absent, the Java method name is used as the address segment and no stable target id is generated. When present, its value is used as the address segment and the operation becomes eligible for stable-target resolution.

```java
// Stable operation — eligible for durable references
@ServiceOperation("get-user")
Future<UserResponse> getUser(String userId);
// Produces address: services/integration/user-service/get-user  (namespace=integration, value=user-service)
// Stable target id: integration.user-service.get-user

// Plain operation — runtime dispatch only, no stable target id
Future<Void> ping();
// Produces address: services/integration/user-service/ping
```

**Durability note:** Changing the operation id is a contract-breaking change for any durable system that persists stable target ids. Renaming the Java method of an unannotated operation changes its address — add `@ServiceOperation` before relying on address stability.

### `@OneWay`

Method-level annotation that marks a service contract method as fire-and-forget. The client proxy sends the message using `eventBus.send()` (no reply expected) and returns `Future.succeededFuture()` immediately. The server processes the message normally — lifecycle hooks, MDC, security context, and resilience pipelines all execute — but does not call `message.reply()`. Server-side failures are logged at WARN and never propagated to the caller.

```java
@ServiceContract(namespace = "integration", value = "notification-service")
public interface NotificationService {

    @OneWay
    Future<Void> publish(NotificationEvent event);
}
```

**Constraints enforced at startup:**

- Methods annotated with `@OneWay` must return `Future<Void>`. Any other return type (e.g., `Future<String>`) is rejected with a `ServiceRegistrationViolation`.

**Caveats:**

- Delivery is best-effort. If no consumer is registered for the address at send time, the message is silently dropped.
- The `Future<Void>` returned by the proxy completing successfully only means the message was submitted to the event bus — it does not guarantee delivery or processing.
- Server-side failures are not observable by the caller. For operations that require delivery confirmation or failure handling, use the default request-response pattern.
- The availability check (`ServiceSupervisor.isAvailable()`) still applies. If the service is marked unavailable, the proxy returns a failed `Future<ServiceUnavailableException>` without sending.

### `@Services`

Dagger qualifier annotation for the `Set<Object>` multibinding that holds service implementations.

```java
@Provides @IntoSet @Services
static Object userService(UserServiceImpl impl) {
    return impl;
}
```

### `ServiceMethodMeta`

Immutable record describing a single service operation. Built by `ServiceRegistrar` and consumed by `ServiceMethodInvoker` and `ServiceClientFactory`.

| Field | Type | Description |
|-------|------|-------------|
| `serviceInstance` | `Object` | The concrete implementation instance |
| `method` | `ServiceMethodDescriptor` | The contract interface method descriptor; call `.resolveMethod()` to obtain the `Method` via reflection |
| `handlerMethod` | `ServiceMethodDescriptor` | The method descriptor to invoke reflectively (equals `method` for direct-impl); call `.resolveHandlerMethod()` for the `Method` |
| `address` | `String` | Full event bus address: `services/{namespace}/{name}/{operation}` or `services/{name}/{operation}` |
| `stableTargetId` | `String` (nullable) | Durable dot-delimited identity: `{namespace}.{name}.{operationId}` or `{name}.{operationId}`; `null` when no `@ServiceOperation` is present (e.g., contributor entries or unannotated methods) |
| `namespace` | `String` | Service namespace from `@ServiceContract#namespace()` (empty string when absent) |
| `name` | `String` | Service name from `@ServiceContract#value()` |
| `operation` | `String` | Operation id from `@ServiceOperation` or method name when annotation absent |
| `payloadType` | `Class<?>` | Payload parameter type, or `null` if no payload parameter |
| `returnType` | `Class<?>` | Unwrapped return type (T from `Future<T>`) |
| `params` | `List<ParamMeta>` | Parameter metadata for argument extraction |
| `handlerParams` | `List<ParamMeta>` | Handler method parameter metadata (equals `params` for direct-impl) |
| `resilienceAnnotations` | `ResilienceAnnotations` | Resolved policy annotations for the operation (from `core.resilience`) |
| `oneWay` | `boolean` | `true` if the method is annotated with `@OneWay`; controls client send vs. request and server reply vs. log |

**Nested types:**

`ParamMeta(name, source, type, lookupKey)` — metadata for a single method parameter. `lookupKey` is non-null for `DISPATCH_CONTEXT` parameters; it is the map key used to retrieve the value from the dispatch context map (e.g., `SecurityContext.class.getName()` for `SecurityContext` injection).

`ParamSource` enum — where the argument value comes from:

| Value | Description |
|-------|-------------|
| `PAYLOAD` | The `DispatchEnvelope<T>` payload, deserialized to the parameter type |
| `DISPATCH_CONTEXT` | A value from the dispatch context map; resolved via `ParamMeta.lookupKey()`. SecurityContext uses the FQCN key `SecurityContext.class.getName()` like any other typed context value. |

### `ServiceContractRegistry`

Central registry mapping contract interfaces to their implementation, base address, operations, and deployment options. Built once at startup.

```java
// Factory methods
ServiceContractRegistry registry = ServiceContractRegistry.build(services);
ServiceContractRegistry registry = ServiceContractRegistry.build(services, config);

// Query
ContractEntry<UserService> entry = registry.resolve(UserService.class);
Collection<ContractEntry<?>> all = registry.entries();
```

**`ContractEntry<T>`** fields: `contract`, `serviceInstance`, `namespace`, `name`, `baseAddress`, `stableContractId` (dot-delimited contract identity, e.g. `integration.user-service`; `null` for non-service contributor entries), `operations` (Map keyed by operation id), `deploymentOptions`.

**Deployment config** (hierarchical JSON, per service):

```json
{
  "services": {
    "contracts": {
      "{namespace}": {
        "{name}": {
          "instances": 3,
          "worker": true
        }
      }
    }
  }
}
```

| Field | Default | Description |
|-------|---------|-------------|
| `instances` | `1` | Number of verticle instances to deploy |
| `worker` | `false` | Deploy as worker verticle |

### `ServiceTargetResolver`

Resolves stable service targets from durable identities. Provided as a Dagger singleton by `DispatchModule`. Only operations with explicit `@ServiceOperation` are indexed — calling `resolve()` with an id that has no stable target throws `IllegalArgumentException`.

```java
// Resolve by stable target id (e.g., stored in an outbox row)
ResolvedServiceTarget target = targetResolver.resolve("integration.user-service.get-user");
String address = target.address(); // "services/integration/user-service/get-user"
String targetId = target.targetId(); // "integration.user-service.get-user"

// Resolve by contract + operation id
ResolvedServiceTarget target2 = targetResolver.resolve(UserService.class, "get-user");

// Resolve by contract + method reference (requires @ServiceOperation on method)
Method method = UserService.class.getMethod("getUser", String.class);
ResolvedServiceTarget target3 = targetResolver.resolve(UserService.class, method);
```

### `ResolvedServiceTarget`

Immutable record returned by `ServiceTargetResolver`. Carries both the durable stable target id and the runtime routing coordinates.

| Field | Type | Description |
|-------|------|-------------|
| `targetId` | `String` | Durable dot-delimited identity (e.g. `integration.user-service.get-user`) |
| `namespace` | `String` | Service namespace segment (empty string when absent) |
| `name` | `String` | Service name segment |
| `operation` | `String` | Durable operation id |
| `meta` | `ServiceMethodMeta` | Full operation metadata (resilience annotations, parameter descriptors) |
| `address` | `String` | Runtime event bus address (e.g. `services/integration/user-service/get-user`) |

**Factory method:**

```java
// Convenience factory — builds from a known contract + operation metadata
ResolvedServiceTarget target = ResolvedServiceTarget.of(UserService.class, meta);
```

`ResolvedServiceTarget.of(Class<?> contract, ServiceMethodMeta meta)` extracts `stableTargetId`, `type`, `name`, `operation`, and `address` from the metadata, avoiding repeated field lookup in proxy dispatch.

---

### `ServiceRequestSender`

`@Singleton` that owns the transport concerns for service dispatch. Wraps `EventBusClient` with service-layer concerns: supervisor checks, resilience timeout computation, and error enrichment. Both `ServiceClientFactory` and the transactional messaging SERVICE adapter share this sender.

**Transport methods:**

| Method | Description |
|--------|-------------|
| `send(ResolvedServiceTarget, DispatchEnvelope)` | Request/reply; supervisor check + computed timeout |
| `send(ResolvedServiceTarget, DispatchEnvelope, long)` | Request/reply; supervisor check + explicit timeout |
| `send(String, DispatchEnvelope, long)` | Request/reply to raw address; no supervisor, no enrichment |
| `sendOneWay(ResolvedServiceTarget, DispatchEnvelope)` | Fire-and-forget; supervisor check, returns immediately |

**Enrichment:** Transport exceptions from `EventBusClient` (`EventBusTimeoutException`, `EventBusAddressUnavailableException`, `EventBusDispatchException`) are translated into service-specific subclasses that carry `contract()`:

| `EventBusClient` exception | `ServiceRequestSender` enriched exception |
|---|---|
| `EventBusTimeoutException` | `ServiceTimeoutException(contract, address, cause)` |
| `EventBusAddressUnavailableException` | `ServiceUnavailableException(contract, "no handlers at {address}")` |
| `EventBusDispatchException` | `ServiceDispatchException(contract, address, message, cause)` |

The `send(String, DispatchEnvelope, long)` overload skips enrichment — raw transport exceptions propagate.



---

### `ServiceRegistrar`

Orchestrates startup contract registration by delegating to focused collaborators. Called by `ServiceContractRegistry.build()` — not invoked directly.

| Collaborator | Responsibility |
|---|---|
| `ContractDiscovery` | Resolves the `@ServiceContract` contract from an implementation class (BFS via `TypeResolver`) |
| `MethodValidator` | Enforces all startup validation rules (see below) |
| `ParameterClassifier` | Classifies method parameters into `ParamMeta` entries by source (`PAYLOAD` or `DISPATCH_CONTEXT`); derives `lookupKey` for `DISPATCH_CONTEXT` params |
| `ReturnTypeResolver` | Unwraps `Future<T>` to the payload type `T` |
| `PolicyResolver` | Calls `ResilienceAnnotations.resolve()` to build per-operation `ResilienceAnnotations` |
| `OperationIdResolver` | Derives the operation name (`resolveOperationName`) from `@ServiceOperation` or method name; also resolves the stable operation id (`resolveStableOperationId`) — returns `null` when `@ServiceOperation` is absent |

**Handler contract resolution** uses `dev.vertique.core.util.TypeResolver.resolveTypeArgument()`, which performs a BFS over the full class and interface hierarchy. This supports intermediate typed interfaces: `interface X extends ServiceHandler<UserService>` + `class Impl implements X` resolves correctly to `UserService`.

**Validation rules enforced at startup:**

- Each implementation must have exactly one `@ServiceContract`-annotated interface
- For the handler pattern: the resolved contract interface (via `TypeResolver`) must not itself be `@ServiceContract`-annotated on the handler — handlers that additionally implement other `@ServiceContract`-annotated interfaces are rejected
- No duplicate contracts across different implementations
- No overloaded method names on the contract interface
- No duplicate resolved operation names within a contract (e.g., two methods with the same `@ServiceOperation` value)
- No duplicate full addresses across contracts (e.g., two contracts producing the same `services/{namespace}/{name}/{operation}`)
- No duplicate stable target ids across contracts (only for operations with `@ServiceOperation`)
- Each method must return `Future<T>` or `Future<Void>`
- Methods annotated with `@OneWay` must return `Future<Void>`
- At most one payload parameter per method
- `DispatchEnvelope<?>` parameters are not allowed on contract interfaces (the envelope is a transport wrapper created by the framework)
- Allowed parameter types: one payload, `SecurityContext`
- Implementation must provide all contract interface methods

**Handler pattern additional rules:**

- `ServiceHandler<C>` implementations must not also directly implement the contract interface
- Handler methods are matched by Java method name (not `@ServiceOperation` id)
- Exactly one handler method per operation name (no overloads on the handler class)
- Payload parameters must match the contract method's payload parameters in order and type
- Generic return type must match exactly (e.g., `Future<String>` must be `Future<String>`, not `Future<Integer>`)
- Extra parameters must be framework-injectable types (`SecurityContext` in v1)

Violations are collected before failing, so all errors are reported in a single `ServiceRegistrationException`.

Policy annotations are resolved via `ResilienceAnnotations.resolve(type, method)` (from `core.resilience`): method-level annotations override type-level defaults.

### `ServiceMethodInvoker`

`Handler<Message<DispatchEnvelope<?>>>` for a single service operation. Registered as an event bus consumer per operation by `ServiceVerticle`.

**Dispatch flow per message:**

1. Decode the envelope's `dispatchContext()` carrier map via registered `ServiceDispatchContextDecoder`s and install the result via `InboundDispatchScope.install(...)` — one scope covers every typed value, including the `MDCContext` materialised by the MDC decoder registered in `LoggingContextModule` (via `ServiceDispatchCodecs.snapshotDecoder`) and the `SecurityContext` materialised by `SecurityContextServiceDispatchDecoder` (registered in `AuthModule`). The scope is closed in every terminal branch.
2. Run `ServiceInterceptor.beforeDispatch()` interceptors in priority order — first failure short-circuits
3. Execute the `DispatchPipeline` (or invoke directly when no policies apply)
4. Map failures through `ServiceExceptionMapper`
5. Run `ServiceInterceptor.afterDispatch()` interceptors independently — failures are logged, not propagated
6. For `@OneWay` operations: if the result is a failure, log at WARN — no reply is sent. For all other operations: reply with a `Result<?>` using the local `dispatch.result` codec
7. Close the dispatch-context scope (MDC restoration is part of it)

A reply is always sent for non-`@OneWay` operations, even on failure. `VirtualMachineError` instances are re-thrown and never wrapped.

### `ServiceVerticle`

`AbstractVerticle` that registers one event bus consumer per operation for a single service contract. Deployed by `ServiceDeploymentManager`; each contract gets its own verticle for actor-like isolation.

At start, `PolicyChainBuilder.build(meta)` is called per operation to construct the policy pipeline, and a `ServiceMethodInvoker` is registered as the consumer.

### `ServiceDeploymentManager`

Orchestrates deployment of all service verticles from the registry. Delegates individual deployment lifecycle to `VerticleDeployer` (calls `deployer.deploy()` directly) from the `deploy` module. Provided as a Dagger singleton by `DispatchModule`.

```java
// Typical usage with phase-based deployment manager
VerticleDeploymentManager manager = component.verticleDeploymentManager();
manager.deployPhase(LifecyclePhase.INFRA)
    .compose(v -> component.serviceDeploymentManager().deployAll())
    .compose(v -> manager.deployPhase(LifecyclePhase.EDGE));
```

Registers local event bus codecs (`dispatch.envelope`, `dispatch.result`) exactly once before deploying. All services deploy in parallel via `Future.join` (wait-all semantics). If any deployment fails, all successfully deployed services are rolled back.

Each service is deployed as a `VerticleDeployment` with a supplier that creates a fresh `ServiceVerticle` per instance, enabling multi-instance deployment. Deployment names follow the format `dispatch-service:{namespace}/{name}#{contractFqcn}`. Each deployment uses `LifecyclePhase.SERVICES` for ordering reference but is deployed individually via `VerticleDeployer.deploy()`, not through `VerticleDeploymentManager.deployPhase()`.

On successful deploy, the service is registered with `ServiceSupervisor` by calling `watch()`. Stale tracking recovery (when a verticle dies without the deployer knowing) is handled inside `VerticleSupervisor` automatically via CAS eviction — `ServiceDeploymentManager` does not need to implement this logic.

**Methods:**

| Method | Description |
|--------|-------------|
| `deployAll()` | Registers codecs, deploys all service verticles via `VerticleDeployer`; rolls back on partial failure; returns `Future<Void>` |
| `undeployAll()` | Deregisters from supervisor, undeploys all verticles; retains tracking on failed undeploys |

### `ServiceSupervisor`

Thin adapter over `VerticleSupervisor` (from the `deploy` module) that adds service-contract keying and per-service supervision config loading. All restart logic, backoff, and availability tracking is delegated to `VerticleSupervisor`.

Service contracts (identified by a `Class`) are mapped to the deployment names used by `VerticleSupervisor`. This class handles only the contract-to-deployment-name translation and config loading.

**Lifecycle methods:**

| Method | Description |
|--------|-------------|
| `watch(contract, type, name, deploymentName, deploymentId, redeployAction)` | Called after successful deploy; loads per-service supervision config and delegates to `VerticleSupervisor.supervise()` |
| `deregister(contract)` | Called before intentional undeploy; removes contract mapping and calls `VerticleSupervisor.unsupervise()` to suppress restart |

**Availability and error reporting:**

| Method | Description |
|--------|-------------|
| `isAvailable(contract)` | Delegates to `VerticleSupervisor.isAvailable(deploymentName)`; returns `true` for unregistered contracts (fail-open) |
| `serviceAvailability()` | Returns a snapshot map of service name → availability for all supervised contracts |
| `reportFatalError(contract, error)` | Delegates to `VerticleSupervisor.reportFatalError(deploymentName, error)` |
| `reportRedeployFailure(contract, cause)` | Delegates to `VerticleSupervisor.reportRedeployFailure(deploymentName, cause)` |

**Per-service supervision config** (hierarchical JSON):

```json
{
  "services": {
    "contracts": {
      "{namespace}": {
        "{name}": {
          "supervision": {
            "maxRestarts": 10,
            "withinMs": 120000,
            "initialBackoffMs": 2000,
            "maxBackoffMs": 60000
          }
        }
      }
    }
  }
}
```

Config path: `services.contracts.{namespace}.{name}.supervision.{field}`. Use `_` as the namespace key when namespace is empty. Falls back to `SupervisionConfig.DEFAULT` (5 restarts / 60s window / 1s initial / 30s max backoff) for any missing values. See `dev.vertique:vertique-deploy` for full `SupervisionConfig` documentation.

### `DispatchModule`

Dagger `@Module` that wires all service dispatch infrastructure. Transitively includes `DeployerModule` and `HealthCheckModule`, so applications do not need to include them separately.

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    ConfigModule.class,
    RestModule.class,
    DispatchModule.class,  // auto-includes DeployerModule + HealthCheckModule
    AppModule.class,
    ResourceModule.class,
    ServiceModule.class    // app-specific service registration
})
interface AppComponent {
    HttpVerticle httpVerticle();
    ServiceDeploymentManager serviceDeploymentManager();
}
```

**Bindings provided:**

| Type | Scope | Description |
|------|-------|-------------|
| `ServiceContractRegistry` | `@Singleton` | Registry built from `@Services` multibinding |
| `ServiceTargetResolver` | `@Singleton` | O(1) stable-target-id index for durable operation lookup |
| `ServiceSupervisor` | `@Singleton` | Restart tracking and availability checks |
| `ServiceDeploymentManager` | `@Singleton` | Deploys all service verticles |
| `ServiceRequestSender` | `@Singleton` | Transport layer: supervisor check, timeout, error enrichment |
| `ServiceClientFactory` | `@Singleton` | Creates typed service clients, preferring a generated companion proxy over a JDK dynamic proxy; delegates transport to `ServiceRequestSender` |

### `ServiceInterceptor`

Extension point for cross-cutting concerns (audit, metrics, tracing). Contribute via `@Provides @IntoSet ServiceInterceptor`.

`ServiceInterceptor extends OrderedExtension`. Interceptors are sorted by `OrderedExtension.comparator()` — phase ascending, then priority ascending, then `orderKey` (default FQCN) as a stable tie-break. Lower priority values run first.

```java
public interface ServiceInterceptor extends OrderedExtension {

    // Sync observers (fire-and-forget; exceptions are swallowed)
    default void onDispatch(ServiceDispatchContext ctx) {}
    default void onComplete(ServiceDispatchContext ctx, Result<?> result, Instant startTime, Instant endTime) {}
    default void onTerminalComplete(ServiceDispatchContext ctx, Result<?> result, Instant startTime, Instant endTime) {}

    // Async handlers (can affect the outcome)
    default Future<ServiceDispatchContext> beforeDispatch(ServiceDispatchContext ctx) {
        return Future.succeededFuture(ctx);
    }
    default Future<Void> afterDispatch(ServiceDispatchContext ctx, Result<?> result) {
        return Future.succeededFuture();
    }
    default Future<Void> recoverError(ServiceDispatchContext ctx, Throwable error) {
        return Future.failedFuture(error);
    }
}
```

`ServiceDispatchContext` carries `address`, `stableTargetId` (nullable — present only when `@ServiceOperation` is set), `namespace`, `name`, `operation`, `DispatchEnvelope<?> body`, `oneWay`, `methodAnnotations`, `classAnnotations`, and an `attributes` bag for passing data between interceptors.

The two completion observers fire at different points and see different outcomes:

- **`onComplete`** — fires with the **handler** outcome **before** `recoverError` runs, for both success and failure, with the full `Result` and wall-clock timing. Use it for handler-level latency/diagnostics.
- **`onTerminalComplete`** — fires **once** with the **terminal post-recovery dispatch** outcome *after* the `recoverError` decision (the recovery decision, not reply delivery): handler success → the original success; recovered failure → `Result.success(null)`; unrecovered failure → `Result.failure(finalCause)`; its `endTime` spans recovery. It is **guaranteed to run on the original dispatch Vert.x context while the dispatch-context scope is still open** — even if a user `afterDispatch`/`recoverError` future settles on a foreign context — so holder context is readable here and available to terminal-outcome observers.

Dispatch ordering: `onDispatch` → method invocation → `onComplete` → `afterDispatch` → `recoverError` (on failure) → `onTerminalComplete` → context-scope close.

**Failure semantics:**

| Method | Failure behavior |
|--------|-----------------|
| `onDispatch` / `onComplete` / `onTerminalComplete` | Sync observers — thrown exceptions are swallowed; cannot affect the dispatch outcome |
| `beforeDispatch` | Failure short-circuits — method is NOT invoked, remaining interceptors NOT called, `Result.failure(cause)` sent as reply |
| `afterDispatch` | Failure is logged at WARN; remaining `afterDispatch` interceptors still execute independently |
| `recoverError` | A succeeded `Future` marks the failure handled (no error reply); a failed `Future` declines recovery and the error propagates |

### `DispatchPipeline`

Chains `PolicyStage` instances into a composable pipeline. Stage order (outermost to innermost): `Timeout` → `CircuitBreaker` → `Retry` → method invocation.

Built once per operation by `PolicyChainBuilder` at verticle start. Returns `null` (no pipeline) when no policy annotations apply to avoid unnecessary overhead.

### `ServiceClientFactory`

Creates typed clients for service contract interfaces. `create(Class<T>)` selects a generated `{Contract}_ServiceClientProxy` companion when one is present on the contract's classloader (emitted by `vertique-codegen-services`), falling back to a JDK dynamic proxy otherwise. Both paths handle `DispatchEnvelope`/`Result` encoding identically and delegate all transport to `ServiceRequestSender`; policy enforcement happens server-side.

```java
// In a Dagger module:
@Provides @Singleton
static UserService userServiceClient(ServiceClientFactory factory) {
    return factory.create(UserService.class);
}

// In a JAX-RS resource:
@Inject UserService userService;

public Future<Response> getUser(String userId) {
    return userService.getUser(userId)
        .map(user -> Response.ok(user).build());
}
```

**Create-time contract completeness:** before building the proxy, `create(Class<T>)` resolves the contract in the registry — an unregistered contract throws `IllegalArgumentException` (unchanged, always checked first) — then validates that every non-static, non-`Object`-declared public method of the contract interface has a corresponding registered operation. Each method's operation id is resolved via `OperationIdResolver.resolveOperationName(method)` (the `@ServiceOperation` value, or the method name when the annotation is absent) and looked up in the resolved entry's operations map. A registry superset — extra registered operations with no corresponding interface method — is allowed. A missing operation fails `create()` immediately with `IllegalStateException` whose message begins with the exact literal `"Service client contract mismatch: "`, followed by the contract's fully-qualified name, the missing operation id, and the method name.

**Companion selection:** after the completeness check passes, `create()` looks up the generated `{Contract}_ServiceClientProxy` companion via `GeneratedNames.companionFqn` (contract's origin package; nested types flatten `Outer$Inner` → `Outer_Inner`). An absent companion is the expected fallback and yields the JDK dynamic proxy described below. A *present but broken* companion — a bad constructor, a failing static initializer, or a linkage error — fails loudly instead of silently falling back: the constructor's own contract-mismatch `IllegalStateException` (message prefixed with the exact literal `"Service client contract mismatch: "`) is unwrapped and rethrown unchanged, so a baked-vs-runtime drift reports identically on both paths; any other failure is wrapped as `"Generated service client proxy {fqn} is present but could not be instantiated"`.

**Common mistake:** a hand-built `ServiceContractContributor` entry that omits an operation the contract interface declares now fails at `create()` time rather than only surfacing on the first invocation of that method.

**One-way operations:** For methods annotated with `@OneWay`, the proxy calls `ServiceRequestSender.sendOneWay()` and returns `Future.succeededFuture()` immediately after the supervisor check passes.

**Event bus send timeout:** Delegated to `ServiceRequestSender.computeSendTimeout()`. Resolution precedence (highest wins):

1. `services.contracts.{namespace}.{name}.operations.{operation}.sendTimeoutMs` — explicit per-operation override
2. `services.contracts.{namespace}.{name}.sendTimeoutMs` — explicit per-service override
3. `services.sendTimeoutMs` — explicit global override
4. Computed from effective resilience config (annotation values + config overrides)
5. 30 s default

When an explicit `sendTimeoutMs` is shorter than the computed resilience timeout, a WARN is logged.

The resilience-based computation formula is:

```
sendTimeout = perAttemptTimeout × (1 + maxRetries) + totalBackoff + jitter + 1s buffer
```

**Availability check:** `ServiceRequestSender.send()` / `sendOneWay()` check `ServiceSupervisor.isAvailable(contract)` before touching the event bus. A failed future with `ServiceUnavailableException` is returned immediately when the service is unavailable.

**Security context propagation** is handled by the built-in `SecurityContextServiceDispatchEncoder`
(registered by `AuthModule` in `vertique-rest-security`). The encoder reads the ambient holder-bound `SecurityContext` at
dispatch time and writes it into the envelope's dispatch-context map. `ServiceClientFactory` adds
an explicit SC caller-override only when `ContextValues.current(SecurityContext.class).isEmpty()` —
preventing a key-collision (`ServiceDispatchContextCapturer.mergeCaptured(...)` throws on duplicate
keys, FR-CTX-063). The ambient holder-bound SC wins precedence.

Callers without an ambient SC (batch jobs, tests) can still propagate a specific security context
by declaring a `SecurityContext` parameter on the contract method and passing it explicitly.
The server-side `ServiceMethodInvoker` restores the context via `InboundDispatchScope`.

### `ServiceExceptionMapper`

Services-layer `Throwable → Throwable` pre-translator applied by `ServiceMethodInvoker` (step 5 of the dispatch flow). Extends the shared `core.failure.FailureMapper` and is wired from a `Set<ServiceExceptionMapperCustomizer>` multibinding.

```java
public class ServiceExceptionMapper extends FailureMapper {
    public Throwable translate(Throwable throwable) { ... }  // inherited
}
```

### `ServiceExceptionMapperCustomizer`

Extension point for contributing `Throwable → Throwable` translations to the service dispatch error pipeline. Implement and contribute via Dagger `@IntoSet`:

`ServiceExceptionMapperCustomizer extends OrderedExtension`. Customizers are applied as an ordered fold — sorted by `OrderedExtension.comparator()` (phase → priority → orderKey) and then called in sequence: a customizer that sorts later wins (its registration overwrites earlier ones for the same exception type). A `SYSTEM_LAST` customizer applies last regardless of numeric priority.

```java
@FunctionalInterface
public interface ServiceExceptionMapperCustomizer extends OrderedExtension {
    void customize(ServiceExceptionMapper mapper);
}

// Example:
@Provides @IntoSet
static ServiceExceptionMapperCustomizer myTranslator() {
    return mapper -> mapper.on(MyInfrastructureException.class,
        ex -> new ServiceUnavailableException(MyService.class));
}
```

`DispatchModule` declares the `@Multibinds Set<ServiceExceptionMapperCustomizer>` empty set.

### `DispatchContext`

Read-only static facade over the framework's per-request dispatch-context map for service method
bodies that need a value without declaring it as a parameter. Delegates to
`DefaultContextHolder.currentValue(...)`; the underlying `ContextLocal` slot is registered once by
`ContextLocalServiceProvider` (the single Vert.x SPI provider — see `core.md`).

The dispatch-context map is the unified propagation channel for framework values
(`SecurityContext`, MDC `MDCContext`, `DurablePropagationMetadata`, `JobContext`, etc.) keyed by
each value type's FQCN. On the inbound service-dispatch path, `ServiceMethodInvoker` decodes the
envelope's `dispatchContext()` map via registered `ServiceDispatchContextDecoder`s and installs
the result through `InboundDispatchScope`; values stay bound for the dispatch lifetime and are
restored on scope close.

```java
// In service methods that need the security context without a parameter:
SecurityContext sc = DispatchContext.currentSecurityContext();
```

`currentSecurityContext()` is a convenience wrapper that looks up `SecurityContext.class.getName()`
from the bound holder. This is the alternative to declaring `SecurityContext` as a method
parameter.

### `ServiceUnavailableException`

Extends `UnavailableException` (from `core.exception`). Thrown by `ServiceRequestSender` when the supervisor has marked a service unavailable, or when no event bus handlers are registered for the target address. Maps to HTTP 503 via `DefaultExceptionMapper`. Exposes `contract()` to identify which service is unavailable.

```java
ServiceUnavailableException ex = new ServiceUnavailableException(UserService.class);
ex.contract(); // → UserService.class
ex.getMessage(); // → "Service unavailable: UserService (restart budget exhausted)"

// Thrown by ServiceRequestSender when no handlers are registered at the address:
ServiceUnavailableException ex2 = new ServiceUnavailableException(UserService.class, "no handlers at services/...");
```

### `ServiceTimeoutException`

Extends `EventBusTimeoutException` (from `core.eventbus`). Thrown by `ServiceRequestSender` when a service request times out. Adds `contract()` to identify which service contract timed out.

```java
public class ServiceTimeoutException extends EventBusTimeoutException {
    public Class<?> contract() { ... }
}
```

Code that catches `EventBusTimeoutException` also catches this subclass.

### `ServiceDispatchException`

Extends `EventBusDispatchException` (from `core.eventbus`). Thrown by `ServiceRequestSender` when the recipient actively rejects a message or a fatal transport error occurs. Adds `contract()` to identify which service contract failed.

```java
public class ServiceDispatchException extends EventBusDispatchException {
    public Class<?> contract() { ... }
}
```

Code that catches `EventBusDispatchException` also catches this subclass.

### Exception Hierarchy

```
core.exception.TechnicalException
├── EventBusTimeoutException (core.eventbus)
│   └── ServiceTimeoutException         — adds contract()
└── EventBusDispatchException (core.eventbus)
    └── ServiceDispatchException        — adds contract()

core.exception.UnavailableException
├── EventBusAddressUnavailableException (core.eventbus)
└── ServiceUnavailableException         — adds contract()
```

### `ServiceConfigurationException`

Extends `ConfigurationException` (from `core.exception`). Base class for service startup and wiring errors.

```
ServiceConfigurationException (extends ConfigurationException)
└── ServiceRegistrationException  — contract scanning validation failures
```

### `ServiceRegistrationException`

Extends `ServiceConfigurationException`. Thrown at startup when scanning finds validation violations. Contains all violations for complete error reporting in a single pass.

```java
try {
    ServiceContractRegistry.build(services);
} catch (ServiceRegistrationException ex) {
    ex.violations().forEach(v -> log.error("{}", v));
}
```

### `ServiceRegistrationViolation`

Immutable record describing a single validation problem. Factory methods: `ofType(contract, message)`, `ofMethod(contract, method, message)`, `ofImpl(message)`.

---

## Resilience Policies

Policy annotations are declared on contract interface methods (or the interface type for defaults). Method-level annotations override type-level defaults. Configuration keys override annotation values at runtime.

### `@Timeout`

Defined in `dev.vertique.core.resilience`. Fails the invocation if it does not complete within the configured duration. Uses a Vert.x timer; the timer and operation race — whichever completes first wins.

| Attribute | Default | Description |
|-----------|---------|-------------|
| `value` | — (required) | Timeout duration; must be positive |
| `unit` | `MILLISECONDS` | Time unit for `value` |

Config override path: `services → contracts → {namespace} → {name} → operations → {operation} → timeout → valueMs`

> **Migration note:** The `valueMs` attribute was replaced by `value` + `unit` when `@Timeout` moved to `core.resilience`. Update usages from `@Timeout(valueMs = 5000)` to `@Timeout(5000)` (unit defaults to `MILLISECONDS`).

### `@CircuitBreaker`

Defined in `dev.vertique.core.resilience`. Wraps the operation (and any retry block) with a Vert.x circuit breaker. While the circuit is open, invocations fail immediately without calling downstream.

| Attribute | Default | Description |
|-----------|---------|-------------|
| `maxFailures` | `5` | Number of failures before the circuit opens |
| `timeoutMs` | `3000` | Per-attempt timeout in milliseconds |
| `resetTimeoutMs` | `10000` | Time before circuit transitions from open to half-open |

Config override path: `services → contracts → {namespace} → {name} → operations → {operation} → circuitBreaker → {maxFailures|timeoutMs|resetTimeoutMs}`

### `@Retry`

Defined in `dev.vertique.core.resilience`. Retries failed invocations with exponential backoff. Retries are innermost — the circuit breaker wraps the retry block, so only the final failure (after retries exhausted) counts toward the circuit breaker threshold.

| Attribute | Default | Description |
|-----------|---------|-------------|
| `maxRetries` | `3` | Maximum retry attempts (not counting the initial attempt) |
| `delayMs` | `500` | Initial delay between retries in milliseconds |
| `backoffMultiplier` | `2.0` | Exponential multiplier; `1.0` = fixed delay |
| `maxDelayMs` | `30000` | Maximum delay cap in milliseconds |
| `backoff` | `BackoffStrategy.Default` | Custom `BackoffStrategy` class; `Default` = use consumer's exponential strategy with jitter |
| `retryOn` | `{}` (all) | Exception types that trigger retry; empty = retry all failures |
| `abortOn` | `{}` (none) | Exception types that suppress retry even when `retryOn` would match |

Config override path: `services → contracts → {namespace} → {name} → operations → {operation} → retry → {maxRetries|delayMs|backoffMultiplier|maxDelayMs}`

### `PolicyStage`

Composable functional interface for pipeline stages:

```java
@FunctionalInterface
public interface PolicyStage {
    Future<Object> execute(ServiceMethodMeta meta, DispatchEnvelope<?> body, Supplier<Future<Object>> next);
}
```

### `PolicyChainBuilder`

Builds a `DispatchPipeline` from `ResilienceAnnotations` (resolved via `ServiceMethodMeta.policyAnnotations()`) and config overrides. Returns `null` when no policies apply.

- Uses `BackoffStrategy` and `BackoffStrategyResolver` from `core.resilience` for retry delay computation, including jitter on exponential strategies
- `retryOn`/`abortOn` filtering is fully enforced via `composeVertxRetryPolicy()` — only exceptions matching `retryOn` (and not blocked by `abortOn`) trigger a retry attempt
- Custom backoff strategies (`@Retry(backoff = MyStrategy.class)`) are instantiated and cached by `BackoffStrategyResolver`

```java
PolicyChainBuilder builder = new PolicyChainBuilder(vertx, config);
DispatchPipeline pipeline = builder.build(meta); // null if no policies
```

---

## Extension Points

### `@Services Set<Object>` — Service Implementations

Contribute service implementations to the multibinding. Each implementation is scanned, validated, and deployed as a `ServiceVerticle`.

```java
@Provides @IntoSet @Services
static Object userService(UserServiceImpl impl) {
    return impl;
}
```

#### Registration paths

There are two registration paths for service contract implementations.

**CG-005 + CG-011 generated path (recommended):** `vertique-codegen-services` generates one `{Contract}_ContractContributor` per contract. The contributor:

- Injects `Provider<Impl>` for each candidate (lazy instantiation).
- Emits a per-impl `PropertyCondition[]` constant (or shared `EMPTY_CONDITIONS` for unconditional candidates in multi-impl groups) referencing `dev.vertique.core.config.PropertyCondition`.
- In `contribute(JsonObject config)`, applies the **size-aware contract**:

| Group shape | Behavior |
|-------------|---------|
| Single-impl, unconditional | Always emits the entry |
| Single-impl, conditional | Emits entry if matched; returns `List.of()` if not (does NOT throw) |
| Multi-impl, conditional candidates only | Walks all candidates; exactly 1 match wins; 0 matches → throws `ServiceRegistrationException`; >1 match → throws `ServiceRegistrationException` |
| Multi-impl, conditional + unconditional default | As above; 0 conditional matches → default wins |

To adopt conditional implementations, annotate them with `@ConditionalOnProperty` and include
`GeneratedServicesModule.class` in the `@Component`. Applications inheriting
`vertique-app-parent` declare `vertique-services` as a runtime dependency and receive the complete
processor facade automatically; custom-parent applications use the BOM plus
`vertique-codegen-all` recipe in `docs/packaging.md`. `vertique-codegen-services` owns the
generated contributor and module. Because `@ConditionalOnProperty` and `@NoAutoWire` are
source-retained annotations, applications using either also declare `vertique-codegen-core` with
`provided` scope as documented in `docs/packaging.md`.

**Example — default + conditional override (codegen path):**

```java
@ServiceContract(value = "user-service", namespace = "integration")
public interface UserService {
    Future<UserResponse> getUser(String userId);
}

// Default (unconditional) — selected when sandboxEnabled is absent or false
public final class UserServiceImpl implements UserService {
    @Inject public UserServiceImpl() {}
    @Override public Future<UserResponse> getUser(String userId) { ... }
}

// Conditional override — selected when sandboxEnabled=true
@ConditionalOnProperty(name = "sandboxEnabled")
public final class UserServiceSandbox implements UserService {
    @Inject public UserServiceSandbox() {}
    @Override public Future<UserResponse> getUser(String userId) { ... }
}
```

**Manual `@Services` binding (fallback):** Write a `@Provides @IntoSet @Services Object` method directly in a Dagger module. This is the appropriate path for:
- Impls annotated `@NoAutoWire` that must remain on a hand-written binding
- Any case where generated code does not fit the wiring requirement

Both paths are first-class. Manual is less ergonomic for the common case but is the correct choice when codegen does not fit.

A service module can conditionally switch implementations manually at runtime:

```java
@Provides @IntoSet @Services
static Object userService(@VertxConfig JsonObject config,
                          UserServiceImpl real, UserServiceSandbox sandbox) {
    return config.getBoolean("sandboxEnabled", false) ? sandbox : real;
}
```

Handler pattern — SecurityContext auto-injected server-side:

```java
@Provides @IntoSet @Services
static Object userService(UserServiceHandler handler) {
    return handler;
}
```

### `Set<ServiceInterceptor>` — Cross-Cutting Interceptors

Contribute interceptors for audit, metrics, distributed tracing, or any other cross-cutting concern.

```java
public class MetricsInterceptor implements ServiceInterceptor {
    @Override
    public int priority() { return 100; }

    @Override
    public Future<Void> afterDispatch(ServiceDispatchContext ctx, Result<?> result) {
        long durationMs = Duration.between(ctx.startTime(), ctx.endTime()).toMillis();
        metrics.record(ctx.meta().address(), durationMs, result.isSuccess());
        return Future.succeededFuture();
    }
}

// In a Dagger module:
@Provides @IntoSet
static ServiceInterceptor metricsInterceptor(MetricsInterceptor interceptor) {
    return interceptor;
}
```

### `ServiceAuthorizationInterceptor` — Built-In Action-Gate Interceptor

A framework-provided `ServiceInterceptor` that enforces `@RequiresAction` on service operations. It is registered automatically by `DispatchModule` when the authorization engine (`SecurityAuthzModule`) is present; applications do not contribute it via `@IntoSet`.

**Ordering:** runs in `ExtensionPhase.SYSTEM_FIRST` at priority `-100` — before all application interceptors and before the dispatch pipeline. A deny short-circuits the dispatch before any business logic executes.

**Runtime gate (`beforeDispatch`):**

1. Resolves the effective `@RequiresAction` from the dispatch context's boot-time annotation lists (method-level overrides class-level, per Jakarta semantics).
2. If no action is declared, passes through unchanged — no authorization work and no event.
3. Reads the propagated `SecurityContext` from the `ContextHolder` (installed by `ServiceMethodInvoker` before the `beforeDispatch` chain runs). If no `SecurityContext` is bound, **fails closed** with `AuthzReasonCodes.AUTHENTICATION_REQUIRED` — the anonymous stand-in is never authorized against.
4. Calls the `Authorizer` and enforces the decision fail-closed (throws/null future/null decision all map to `AuthzReasonCodes.INTERNAL_AUTHZ_ERROR`).
5. Emits exactly one `AuthorizationDecisionEvent` per gated dispatch via `SecurityEventEmitter`. Emission is best-effort; failure is swallowed and never alters the dispatch outcome.
6. A deny returns a failed `Future` carrying `NonRecoverableForbiddenException`, which implements `NonRecoverableDispatchFailure`. `ServiceMethodInvoker` bypasses the `recoverError` chain for this marker — an authorization deny can never be resurrected by a permissive `recoverError` implementation.

**Startup validation:** at construction time every registered `ServiceMethodMeta` is scanned for `@RequiresAction` (method- and class-level). Each declared action must parse as a canonical `ActionRef` and exist in the `ActionRegistry`. If a `@RequiresAction` is present but the authorization engine (`Authorizer` / `ActionRegistry`) is absent, construction fails with `ServiceRegistrationException` (fail-closed — mirrors the REST/WebSocket startup checks).

**`NonRecoverableDispatchFailure`:** marker interface (in `dev.vertique.services.dispatch`) that `ServiceMethodInvoker` checks before routing a `beforeDispatch` failure through `recoverError`. Only `NonRecoverableForbiddenException` implements it; ordinary `ForbiddenException` thrown by application service handlers remains recoverable.

**`NonRecoverableForbiddenException`:** `ForbiddenException` subclass (in `dev.vertique.services.interceptor`) that implements `NonRecoverableDispatchFailure`. Maps to HTTP 403 exactly like its parent; the only difference is its (non-)recoverability in the dispatch pipeline.

```java
// No application wiring needed — DispatchModule registers this interceptor automatically
// when SecurityAuthzModule is present.

// Mark a service operation with @RequiresAction on the contract interface:
@ServiceContract(namespace = "integration", value = "user-service")
public interface UserService {

    @RequiresAction("user:read")
    Future<UserResponse> getUser(String userId);
}

// Or at class level (applies to all operations in the contract):
@ServiceContract(namespace = "integration", value = "order-service")
@RequiresAction("order:manage")
public interface OrderService {
    Future<OrderResponse> placeOrder(OrderRequest request);
    Future<Void> cancelOrder(String orderId);
}
```

### `SnapshotDegradationGate` — Identity-Snapshot Degradation Gate

A framework-provided `ServiceInterceptor` that enforces the configured identity-snapshot degradation policy when an inbound dispatch carries a `SnapshotDegradationMarker` (bound by the receive-side identity-snapshot reconstruction initializer when a durably-carried `IdentitySnapshot` could not be reconstructed). It is registered automatically by `DispatchModule`; applications do not contribute it via `@IntoSet`.

**Ordering:** runs in `ExtensionPhase.SYSTEM_FIRST` at priority `-200` — earlier than `ServiceAuthorizationInterceptor`'s `-100`, so a `FAIL` verdict here preempts the action gate entirely: a dispatch carrying an unverifiable identity never reaches authorization.

**Runtime gate (`beforeDispatch`):**

1. Reads the bound `SnapshotDegradationMarker` from the `ContextHolder`. When absent — the overwhelmingly common path — the gate is a pure passthrough: no event is emitted and no policy is consulted.
2. When a marker is bound, builds an `IdentitySnapshotDegradationEvent` (timestamp, correlation, marker's `origin()` and `reasonCode()`) and emits it via `SecurityEventEmitter.emit(...)`, **awaiting** the emission future before applying the policy decision — the event can never be dropped by the dispatch outcome (non-droppable guarantee).
3. Applies the configured `IdentitySnapshotDegradationPolicy` only after the event has been emitted:
   - `FAIL` (default) — the dispatch is aborted with a failed future carrying `SnapshotDegradationForbiddenException`.
   - `CONTINUE_WITHOUT_IDENTITY` — the dispatch proceeds unchanged (subject-less continuation); the degradation is recorded only via the emitted event.
4. The gate never mutates identity — it only aborts (`FAIL`) or passes the context through unchanged (`CONTINUE_WITHOUT_IDENTITY`); it never substitutes or alters the bound `SecurityContext`.

**Policy default:** the gate injects `Optional<IdentitySnapshotDegradationPolicy>` so it constructs safely whether or not the application installs `IdentitySnapshotCarriageModule` (identity-snapshot durable carriage is opt-in). When absent, `IdentitySnapshotDegradationPolicy.FAIL` is assumed — this default mirrors `IdentitySnapshotConfig`'s own fail-closed default and is never actually exercised, since the marker the gate consults is itself only ever bound when carriage is installed.

**`SnapshotDegradationForbiddenException`:** `ForbiddenException` subclass (in `dev.vertique.services.interceptor`) raised by the gate on a `FAIL` verdict. Implements `NonRecoverableDispatchFailure`, so `ServiceMethodInvoker` bypasses the `recoverError` chain for it — a degraded, unverifiable identity can never be turned back into a successful dispatch by a permissive application `recoverError`. Maps to HTTP 403 exactly like its parent.

```java
// No application wiring needed — DispatchModule registers this interceptor automatically.
// Enable identity-snapshot durable carriage (IdentitySnapshotCarriageModule) and configure
// the degradation policy via IdentitySnapshotConfig.onDegradation (FAIL | CONTINUE_WITHOUT_IDENTITY).
```

### `Set<ServiceContractContributor>` — External Contract Contributors

Contribute service contract entries from modules that do not use `@ServiceContract` annotations. The contributor SPI allows any module to register handlers with their own entry building logic. All contributed entries are merged into the registry and participate in global address collision validation.

```java
public interface ServiceContractContributor {
    List<ContractEntry<?>> contribute(JsonObject config);
}
```

Use the `ServiceContractEntries` builder to construct entries — do not instantiate `ContractEntry` or `ServiceMethodMeta` directly.

```java
@Provides @IntoSet
static ServiceContractContributor myContributor(Set<MyHandler> handlers) {
    return config -> handlers.stream()
        .map(h -> ServiceContractEntries.deployable()
            .contract(h.getClass())
            .serviceInstance(h)
            .namespace("custom").name(h.name())
            .operation("execute")
                .method(resolveMethod(h))
                .payloadType(h.payloadType())
                .returnType(Void.class)
                .param("payload", ParamSource.PAYLOAD, h.payloadType())
                .done()
            .deploymentOptions(config, "services", "contracts", "custom", h.name())
            .build())
        .toList();
}
```

Contributed entries follow the same event bus address format: `services/{namespace}/{name}/{operation}`. The `deploymentOptions(config, path...)` method reads `instances` and `worker` from hierarchical config, enabling deployment scaling per contributor entry.

### `ServiceContractEntries` — Builder API

Fluent builder for `ContractEntry` records. Used by `ServiceContractContributor` implementations to construct entries without coupling to internal metadata types.

**Entry builder chain:**

```java
ServiceContractEntries.deployable()           // starts an EntryBuilder
    .contract(MyExecutor.class)               // contract key class
    .serviceInstance(executor)                // implementation instance
    .namespace("job").name("my-handler")      // address segments
    .operation("execute")                     // begins OperationBuilder
        .method(executeMethod)
        .payloadType(MyPayload.class)
        .returnType(Void.class)
        .param("payload", ParamSource.PAYLOAD, MyPayload.class)
        .param("ctx", ParamSource.DISPATCH_CONTEXT, JobContext.class)
        .done()                               // returns to EntryBuilder
    .deploymentOptions(config, "services", "contracts", "job", "my-handler")
    .build();
```

**`OperationBuilder` methods:**

| Method | Description |
|--------|-------------|
| `method(Method)` | Reflective method to invoke |
| `payloadType(Class<?>)` | Payload parameter type; `null` if no payload |
| `returnType(Class<?>)` | Unwrapped return type (`T` from `Future<T>`) |
| `param(name, ParamSource, type)` | Adds a parameter; for `DISPATCH_CONTEXT`, `lookupKey` is auto-derived as `type.getName()` |
| `handlerMethod(Method)` | When set, used for `ServiceMethodMeta.handlerMethod`; defaults to `method` (direct-impl equivalence). Use for handler-pattern contributors where the invocable method differs from the contract method. |
| `handlerParam(String, ParamSource, Class<?>)` | Repeated calls accumulate the handler method's parameter list. When not called, `handlerParams` defaults to the same list as `param(...)` calls. Use for injectable params (e.g., `SecurityContext`) that appear on the handler method but not on the contract. |
| `methodAnnotations(List<Annotation>)` | When set, used verbatim for `ServiceMethodMeta.methodAnnotations`; otherwise auto-resolved from `method` via `AnnotationResolver.resolveMethodAnnotations(method)`. Use when the annotation origin (e.g., the contract interface method) differs from the handler method. |
| `classAnnotations(List<Annotation>)` | When set, used verbatim for `ServiceMethodMeta.classAnnotations`; otherwise auto-resolved from `serviceInstance.getClass()`. Use to derive class-level annotations from the contract interface rather than the impl class — important for handler-pattern services whose handler class does not carry policy annotations. |
| `oneWay()` | Marks the operation as fire-and-forget |
| `done()` | Returns to the parent `EntryBuilder` |

The four new setters (`handlerMethod`, `handlerParam`, `methodAnnotations`, `classAnnotations`) are backwards-compatible additions. Existing contributors that do not call them retain current behavior via their defaults. Generated contributors from `vertique-codegen-services` always pass all four explicitly, with resilience and annotation metadata derived from the **contract interface**, ensuring that `DispatchPipeline` construction and `ServiceDispatchContext` interceptor metadata are consistent regardless of impl pattern.

### Security Context Bridge

Outbound `SecurityContext` propagation is automatic when the REST security stack is present.
The built-in `SecurityContextServiceDispatchEncoder` (registered by `AuthModule` in
`vertique-rest-security`) reads the
holder-bound SC and writes it into the envelope at dispatch time. No manual `SecurityContextAccessor`
binding is required. The `SecurityContextAccessor` type has been removed.

---

## Parameter Conventions

Service methods can accept the following parameter types, classified by `ServiceRegistrar`:

| Parameter type | `ParamSource` | `lookupKey` | Description |
|---------------|---------------|-------------|-------------|
| Any domain type | `PAYLOAD` | — | The message payload; at most one per method |
| `SecurityContext` | `DISPATCH_CONTEXT` | `SecurityContext.class.getName()` | The security context from the dispatch context map |

> **Note:** `DispatchEnvelope<?>` parameters are **not allowed** on contract interfaces. `DispatchEnvelope` is a transport wrapper created by the framework; use `DispatchContext.currentSecurityContext()` or a `SecurityContext` parameter for security context access.

```java
// Payload only (most common)
Future<UserResponse> getUser(String userId);

// Payload + security context (for authorization checks inside the service)
Future<UserResponse> getUser(String userId, SecurityContext securityContext);

// No payload (operations that only need the caller identity)
Future<Void> deleteUser(SecurityContext securityContext);
```

> **Deprecation:** Declaring `SecurityContext` on contract interfaces is deprecated. Use the [ServiceHandler pattern](#servicehandler-pattern) instead for server-side SecurityContext access. Direct-impl contracts with `SecurityContext` will log a warning during registration.

---

## ServiceHandler Pattern

The `ServiceHandler<C>` interface enables service implementations that do not directly implement the contract interface. Instead, the handler implements `ServiceHandler<Contract>` and provides matching methods that may declare extra framework-injectable parameters.

### Motivation

With direct implementation, any server-side concerns (e.g., `SecurityContext`) must either pollute the contract interface or be accessed via ambient `DispatchContext.currentSecurityContext()`. The handler pattern keeps the contract clean while making injectable parameters explicit:

```java
// Contract stays clean — pure domain API
@ServiceContract(namespace = "integration", value = "user-service")
public interface UserService {
    Future<UserResponse> getUser(String userId);
    Future<List<UserResponse>> listUsers();
}

// Handler gets SecurityContext auto-injected — NOT part of the contract
public class UserServiceHandler implements ServiceHandler<UserService> {
    public Future<UserResponse> getUser(String userId, SecurityContext sc) {
        // sc injected from DispatchContext during dispatch
    }
    public Future<List<UserResponse>> listUsers() {
        return Future.succeededFuture(List.of());
    }
}
```

### Method Matching Rules

1. Handler method name must match the contract method's Java symbol name (not the `@ServiceOperation` id, which is address/stable-target metadata only)
2. Exactly one handler method per name — overloads are rejected
3. Payload parameters (non-injectable types) must match the contract's payload parameters in order and type
4. Extra parameters must be framework-injectable types (`SecurityContext` in v1)
5. Generic return type must match exactly (`Future<String>` must be `Future<String>`)

### Injectable Types

| Type | ParamSource | lookupKey | Source |
|------|-------------|-----------|--------|
| `SecurityContext` | `DISPATCH_CONTEXT` | `SecurityContext.class.getName()` | `DispatchContext` context map, keyed by `SecurityContext.class.getName()` |

The invoker populates the dispatch context map from `DispatchEnvelope.metadata().context(SecurityContext.class)` (a convenience wrapper over the map) before dispatch. `ServiceContractEntries.param()` auto-derives the `lookupKey` for `DISPATCH_CONTEXT` parameters at build time.

### Registration

Handler instances are contributed to the `@Services` multibinding just like direct implementations:

```java
@Provides @IntoSet @Services
static Object userService(UserServiceHandler handler) {
    return handler;
}
```

Both patterns can coexist in the same application for different contracts. A single contract cannot have both a direct-impl and a handler-impl registered simultaneously.

### Backward Compatibility

Direct implementation (`implements Contract`) continues to work unchanged. The handler pattern is opt-in per service.

> **Deprecation note:** Declaring `SecurityContext` as a parameter on contract interfaces is deprecated. The handler pattern is the recommended approach for server-side SecurityContext access. A warning is logged during registration when a direct-impl contract declares `SecurityContext` parameters.

---

## Configuration Reference

All service dispatch configuration uses a hierarchical JSON structure under the `services` key. Per-service config lives under `services.contracts.{namespace}.{name}`; use `_` as the namespace key when namespace is empty. Policy overrides live under `services.contracts.{namespace}.{name}.operations.{operation}`.

```json
{
  "services": {
    "sendTimeoutMs": 60000,
    "contracts": {
      "integration": {
        "user-service": {
          "instances": 3,
          "worker": false,
          "sendTimeoutMs": 30000,
          "supervision": {
            "maxRestarts": 10,
            "withinMs": 120000,
            "initialBackoffMs": 2000,
            "maxBackoffMs": 60000
          },
          "operations": {
            "getUser": {
              "sendTimeoutMs": 10000,
              "timeout": { "valueMs": 5000 },
              "circuitBreaker": { "maxFailures": 10, "timeoutMs": 5000, "resetTimeoutMs": 30000 }
            },
            "listUsers": {
              "retry": { "maxRetries": 5, "delayMs": 1000, "backoffMultiplier": 2.0, "maxDelayMs": 30000 }
            }
          }
        }
      }
    }
  }
}
```

Configuration is accessed via `dev.vertique.core.config.JsonConfigPaths.navigateObject()` (in `vertique-core`), which safely traverses nested JSON objects and returns an empty `JsonObject` when any segment is missing.

### Configuration Reference

All fields are optional — when absent the annotation value, computed resilience timeout, or `SupervisionConfig.DEFAULT` is used.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `services.sendTimeoutMs` | long | 30000 | Global event bus send timeout (ms) |
| `services.contracts.{ns}.{name}.instances` | int | 1 | Number of `ServiceVerticle` instances (`{ns}` = `_` for empty namespace) |
| `services.contracts.{ns}.{name}.worker` | boolean | false | Deploy as worker verticle |
| `services.contracts.{ns}.{name}.sendTimeoutMs` | long | global | Per-service send timeout (ms) |
| `services.contracts.{ns}.{name}.supervision.maxRestarts` | int | 5 | Max restarts within time window |
| `services.contracts.{ns}.{name}.supervision.withinMs` | long | 60000 | Restart counting window (ms) |
| `services.contracts.{ns}.{name}.supervision.initialBackoffMs` | long | 1000 | Initial restart backoff (ms) |
| `services.contracts.{ns}.{name}.supervision.maxBackoffMs` | long | 30000 | Max restart backoff (ms) |
| `services.contracts.{ns}.{name}.operations.{op}.sendTimeoutMs` | long | service | Per-operation send timeout (ms) |
| `services.contracts.{ns}.{name}.operations.{op}.timeout.valueMs` | long | `@Timeout` | Dispatch timeout (ms) |
| `services.contracts.{ns}.{name}.operations.{op}.circuitBreaker.maxFailures` | int | `@CircuitBreaker` | Failure threshold |
| `services.contracts.{ns}.{name}.operations.{op}.circuitBreaker.timeoutMs` | long | `@CircuitBreaker` | CB per-attempt timeout (ms) |
| `services.contracts.{ns}.{name}.operations.{op}.circuitBreaker.resetTimeoutMs` | long | `@CircuitBreaker` | Half-open reset timeout (ms) |
| `services.contracts.{ns}.{name}.operations.{op}.retry.maxRetries` | int | `@Retry` | Max retry attempts |
| `services.contracts.{ns}.{name}.operations.{op}.retry.delayMs` | long | `@Retry` | Initial retry delay (ms) |
| `services.contracts.{ns}.{name}.operations.{op}.retry.backoffMultiplier` | double | `@Retry` | Backoff multiplier |
| `services.contracts.{ns}.{name}.operations.{op}.retry.maxDelayMs` | long | `@Retry` | Max retry delay (ms) |

---

## Dependencies

- `dev.vertique:core` — `FailureMapper`, `DispatchEnvelope<T>`, `DispatchMetadata`, `Result<T>`, `SecurityContext`, `LocalMessageCodec`
- `dev.vertique:deploy` — `VerticleDeployer`, `VerticleDeploymentManager`, `VerticleDeployment`, `DeployerModule`
- `io.vertx:vertx-core` — Vert.x event bus, timer, verticle API
- `io.vertx:vertx-circuit-breaker` — `CircuitBreaker` for `CircuitBreakerStage`
- `com.google.dagger:dagger`
- `jakarta.inject:jakarta.inject-api`
- `jakarta.annotation:jakarta.annotation-api`
- `org.slf4j:slf4j-api`
- `org.projectlombok:lombok` (provided)

---

## Related ADRs

- ADR-0084: Framework Extension-Ordering Contract — establishes `OrderedExtension` and `ExtensionPhase` as the canonical ordering contract for framework extensions.
- ADR-0085: OrderedExtension Rolled Out Across Sorted Behavioral SPIs — `ServiceInterceptor` and `ServiceExceptionMapperCustomizer` now follow the framework OrderedExtension ordering contract (phase → priority → orderKey).
- ADR-0104: Typed Config Architecture — establishes the typed-config-from-keyed-objects pattern; `ServicesConfig` parses `services.contracts.{namespace}.{name}` into typed `ServiceConfig` records at the Dagger boundary.
- ADR-0108: Unified Failure Mapping on a Context-Aware `FailureMapper` — collapses four layer-specific mapper wrappers onto one concrete `core.failure.FailureMapper`; `ServiceExceptionMapper` now extends it; `map(...)` renamed to `translate(...)`.
- ADR-0113: Federated Action and Policy Authorship for Framework Authorization — establishes `@RequiresAction` as the mechanism for declaring service operation action gates, with federated authorship in each service module.
- ADR-0114: Enforcement-Layer Emission Ownership for Authorization Decisions — establishes that each enforcement layer (including `ServiceAuthorizationInterceptor`) emits `AuthorizationDecisionEvent` directly via `SecurityEventEmitter`, not through a shared intermediary.
- ADR-0190: Service Client Companion Selection and Create-Time Fail-Fast — establishes the generated-companion-first, dynamic-proxy-fallback selection in `ServiceClientFactory.create()` and the narrow contract-mismatch unwrap for a present-but-broken companion.

---

## Possible Future Enhancements

The following are potential enhancements that may be revisited if specific needs arise. None are currently required — the module is feature-complete for its intended scope.

### Partition Key Support

Per-key message ordering for services where operation order matters within a logical partition (e.g., all events for `userId=abc` go to the same consumer instance). Only relevant if clustering is added — with a local event bus, single-instance deployment or an external message broker (Kafka, etc.) covers ordering needs.

### Service Variant Framework

Runtime per-request selection of contract implementations (e.g., route `sandbox` requests based on a header). Already achievable today via Dagger conditional `@Provides` methods or a `ServiceInterceptor` — a dedicated framework would only be warranted for complex multi-tenant routing scenarios.

### Metrics PolicyStage

Built-in `PolicyStage` that records per-operation latency, error rate, and circuit breaker state. Vert.x Micrometer integration already provides event bus and circuit breaker metrics when enabled — a custom stage would primarily add service-operation-level dimension consolidation.
