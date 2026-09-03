<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Resilience Module

> **Status:** Stable
> **Package:** `dev.vertique.resilience`
> **Artifact:** `vertique-resilience`
> **Depends on:** `vertique-core`, `vertique-aop`, `vertx-core`, `vertx-circuit-breaker`, Dagger, `jakarta.inject-api`

`vertique-resilience` provides the shared resilience vocabulary and runtime foundation used by
Vertique consumers. It contains type and method annotations for timeout, retry, circuit-breaker, and
bulkhead declarations; immutable metadata snapshots for resolved declarations; retry contracts; and an
application-scoped runtime with executable timeout/retry/circuit-breaker/bulkhead policy composition,
deterministic policy resolution, and execution budgets. Framework adapter contexts can own the
bulkhead component required by a resolved adapter policy.

---

## When To Use It

Add `dev.vertique:vertique-resilience` when application contracts use resilience annotations or when
an application supplies a `BackoffStrategy` or `RetryPolicy`. It is also included transitively by
consumer modules such as `vertique-services` and `vertique-rest-client`.

---

## Core Concepts

Annotations may be placed on a type or method. A method declaration replaces the corresponding
type-level declaration; values are not merged attribute by attribute. `ResilienceAnnotations`
resolves the effective declarations through the method and type hierarchy and exposes immutable
declaration records through `Optional` accessors.

The four declaration families are independent: an operation may configure any combination of
timeout, retry, circuit breaker, and bulkhead. `@Resilient` is a method-level anchor that may select
a named policy tier; an anchor-only method is active when its policy name is nonblank.
An empty or whitespace-only `policy()` is treated as no named tier.
`ResilienceAnnotations.NONE` is the shared empty value, and `hasAny()` is true when any declaration
or policy name is present. The `resolve(MethodMetadata)` overload reads only method-level metadata
through `findAnnotation`, while the reflective overloads also resolve type-level declarations.

The executable foundation is application-scoped. Create one `Resilience` for the application graph
and construct timeout/retry/breaker/bulkhead components or pipelines from that owner. Timeout and
retry execution is backed by isolated Vert.x 5.1.6 CircuitBreaker engines; a timeout is a
per-supplier-attempt fence and does not cancel the supplier's underlying future. Retry delays are
outside the per-attempt timeout, and public retry callbacks receive zero-based ordinals. A pipeline
is fixed-order typed composition state and must contain at least one concern.
A circuit breaker is an independently executable local component: it admits one logical execution,
counts its final result once, disables Vert.x's own timeout and retry behavior for the authoritative
breaker, and admits one
no-retry half-open probe after reset. A bulkhead admits whole logical executions with either
immediate rejection or a FIFO bounded queue. Stateful components never share state by equal names.

Resilience observation is opt-in through the `ResilienceObserver` SPI. Observers receive immutable,
redacted events synchronously on the current Vert.x context. Each observer is isolated from the
others; a non-fatal observer failure is logged with only the observer class, event kind, and
exception class, and cannot alter the operation result or resource cleanup. The runtime has no
hidden event queue, and observer implementations must keep callbacks bounded and offload I/O.

---

## AOP integration

`ResilienceAopModule` binds the internal `ResilientAspect` to the `vertique-aop` provider set and
contributes its shutdown step. Add this module to the application component together with the
runtime modules that provide `Resilience`; methods carrying `@Resilient` then activate the
reflection-free resilience pipeline. The aspect resolves the effective method declarations once
when an interceptor is built and memoizes the pipeline by the erased declaring type, method name,
and parameter signature.

The aspect does not intercept self-invocation or direct object construction. For a
`ServiceHandler<C>` implementation, wire either the generated `ServiceContract` or the AOP
provider, but do not apply both to the same method: doing so double-wraps one invocation and may
duplicate retries, timeout events, and breaker accounting. The aspect owns adapter state and closes
it during `VALIDATE` shutdown, before the synthetic runtime teardown step in `CONFIGURE`.

## Key Classes

### Resilience annotations

`dev.vertique.resilience.annotation.Timeout`, `Retry`, `CircuitBreaker`, and `Bulkhead` are runtime-retained
annotations targeting types and methods. `dev.vertique.resilience.annotation.Resilient` is a
runtime-retained method-level anchor whose `policy()` names an optional policy tier. `Retry` references
a `BackoffStrategy` class and lists exception types eligible for retry or immediate abort.

```java
@Timeout(value = 2, unit = TimeUnit.SECONDS)
@Retry(maxRetries = 3, delayMs = 100)
@CircuitBreaker(maxFailures = 5)
public interface PaymentClient {
    // consumer modules enforce the declarations
}
```

### Declaration metadata

`ResilienceAnnotations.resolve(Method)` or `resolve(Class<?>, Method)` returns immutable
`TimeoutDeclaration`, `RetryDeclaration`, `CircuitBreakerDeclaration`, and `BulkheadDeclaration` snapshots
plus the optional named policy tier. `resolve(MethodMetadata)` provides the reflection-free method-level
form for generated consumers. The snapshots are suitable for generated contributors and reflective
registration; they do not expose live annotation instances.

### Runtime and timeout construction

`Resilience.create(Vertx)` creates a standalone runtime. `Timeout.builder(resilience,
operationName)` creates an independently executable timeout, while `resilience.pipeline(operationName)`
starts a pipeline builder. The three supported timeout forms share the same execution fence:

```java
Resilience resilience = Resilience.create(vertx);

Timeout direct = Timeout.builder(resilience, "payment-direct")
        .duration(Duration.ofMillis(250))
        .build();

ResiliencePipeline inline = resilience.pipeline("payment-inline")
        .timeout(timeout -> timeout.duration(Duration.ofMillis(250)))
        .build();

Timeout prebuiltTimeout = Timeout.builder(resilience, "payment-component")
        .duration(Duration.ofMillis(250))
        .build();
ResiliencePipeline prebuilt = resilience.pipeline("payment-prebuilt")
        .timeout(prebuiltTimeout)
        .build();

Future<String> result = prebuilt.execute(() -> client.call());

CircuitBreaker breaker = CircuitBreaker.builder(resilience, "payment-state")
        .maxFailures(5)
        .resetTimeout(Duration.ofSeconds(10))
        .build();
ResiliencePipeline protectedCall = resilience.pipeline("payment-call")
        .retry(retry -> retry.maxRetries(1))
        .circuitBreaker(breaker)
        .build();
```

Timeout durations must be positive and exactly representable in milliseconds. `Duration` values
that are zero, negative, sub-millisecond, or too large for a millisecond `long` are rejected;
`TimeoutConfig.ofMillis` likewise requires a positive value. Every timeout duration setter and every
pipeline timeout setter may be used once, and each builder is single-use. A pipeline must configure
at least one timeout, retry, circuit-breaker, or bulkhead concern before `build()`. A prebuilt
timeout, retry, breaker, or bulkhead must belong to the same `Resilience` runtime as the pipeline; a foreign owner is
rejected eagerly. Reusing the same prebuilt breaker instance is the only sharing mechanism. Inline
breaker construction is local to its pipeline, and separately built breakers remain isolated even
when their construction names are equal.

Operation names are construction-time inputs only. The runtime requires a non-empty valid-Unicode
name no larger than 4,096 UTF-8 bytes, then derives an opaque SHA-256 operation key; raw names are
not retained in the executable identity. A prebuilt same-owner timeout may be composed into a
pipeline, but execution failures use the pipeline's operation key rather than the timeout's
construction label. `operationKey()` exposes that derived key for correlation.

`execute(Supplier<Future<T>>)` captures the current Vert.x context. If there is no current context,
the runtime uses a fallback context created from its supplied `Vertx`. Supplier invocation,
timeout settlement, and completion callbacks remain on the selected context. Timeout, supplier
completion, and runtime close race through a settle-once fence: exactly one outcome wins, private
engine resources are closed on settlement, and a late supplier completion is ignored. Neither
timeout nor close cancels the supplier's underlying future.

Closing a runtime is asynchronous and idempotent: repeated `close()` calls return the same terminal
future. Close fences active public executions with `ResilienceClosedException`, cancels runtime-owned
timers, and does not wait for uncancelled supplier futures. New pipeline construction and execution
are rejected after close: pipeline creation and component `build()` fail, while an existing pipeline's
`execute` returns a failed future. The closed state is also observed by the Dagger lifecycle shutdown
step.

### Retry contracts

`RetryBackoff.delayMs(int)` computes the delay in milliseconds from a zero-based retry count. Fixed
policies return their configured delay, exponential policies apply the configured cap and then add
bounded random jitter, and custom policies delegate to their `BackoffStrategy`. Negative retry counts
are rejected. Use `BackoffStrategy.exponential`, `fixed`, or `none` when a consumer needs a strategy
implementation, or implement the functional interface for a custom strategy. `RetryPolicy` determines
whether a failure is eligible for retry when a consumer's retry configuration delegates eligibility to
a policy. Runtime integrations that own a validated random source may use
`delayMs(int, DoubleSupplier)`; the one-argument method uses the default runtime random source.

`Retry.builder(resilience, operationName)` creates an independently executable retry component.
`ResiliencePipeline.Builder.retry(...)` accepts a prebuilt `Retry` or an inline retry builder. Retry
is opt-in, starts at attempt zero, and performs at most `maxRetries + 1` attempts through the
Vert.x CircuitBreaker retry engine. `abortOn` wins over `retryOn`; delays are scheduled between
attempts and do not consume the per-attempt timeout.
Callback failures become cause-free `ResiliencePolicyException` values with only safe callback-kind
and exception-class metadata. Fatal `Error` values are rethrown.

`ResiliencePolicyResolver` resolves immutable policy values using operation override, annotation,
default, then disabled precedence independently for each concern. `RetryConfig`, `RetryBackoff`,
`CircuitBreakerConfig`, and `BulkheadConfig` validate eagerly. `ResolvedResiliencePolicy.executionBudget()`
reports conservative active, queue, and total bounds using saturating arithmetic; unknown or
unbounded inputs remain explicit.

Framework adapters use `ResilienceAdapterSupport.pipeline(AdapterOperationIdentity,
ResolvedResiliencePolicy)` for the stable structured entry point. It derives the opaque key internally,
accepts timeout-only and retry-only policies, and rejects empty or stateful policies before state,
timers, or suppliers are started. For breaker or bulkhead policies, create a `ResilienceAdapterContext`
with `newContext()`. Its classifier overload maps only adapter-final failures into breaker
accounting; classifier failures preserve the original failure and count conservatively. Contexts
own breakers and bulkheads and fence active public futures on `close()`. Equal structured identities
in separate contexts do not share state. No raw-key overload or standalone public identity derivation
is provided.

### Circuit breaker and adapter context

`CircuitBreaker.builder(resilience, stateName)` creates a standalone local breaker. The component
builder accepts a positive failure threshold and `Duration` reset timeout. `execute` can be used
directly or a prebuilt breaker can be placed in a pipeline. Inline pipeline construction creates a
pipeline-local breaker. A pipeline uses its own operation key in `CircuitOpenException`, while the
breaker contributes only its state key; direct breaker execution uses the state key for both.

Framework adapters should obtain `ResilienceAdapterContext` from
`ResilienceAdapterSupport.newContext()`. The context creates structured-identity breakers and
explicitly tracks same-owner prebuilt reuse. Its classifier overload is the adapter-only failure
classification seam; application builders do not expose it. `close()` is idempotent, closes owned
breakers, and fences active timeout/retry/breaker pipeline futures with `ResilienceClosedException`.

### Bulkhead execution

`Bulkhead.builder(resilience, stateName)` creates a local capacity component. Use `reject()` for
immediate `BulkheadRejectedException`, or `queue(maxQueueSize, queueTimeout)` for FIFO bounded
waiting. A permit covers the complete logical execution, including retries and backoff. Queue
capacity counts waiting calls only; `maxQueueSize` is limited to `1..1024` and queue timeout to
`1..60_000` milliseconds. Queued suppliers run on the context captured at `execute`, and every
terminal path cancels its timer, releases its permit, and ignores late supplier completion. Inline
bulkheads are pipeline-local; only explicitly reusing a same-owner prebuilt instance shares
capacity. The pipeline performs the fast breaker-open check before bulkhead admission and the
authoritative breaker recheck after a queued call is admitted.

Framework adapter contexts translate resolved bulkhead configuration into context-owned components.
Services and REST use that context path, so explicit `@Bulkhead(mode = REJECT)` and
`@Bulkhead(mode = QUEUE)` declarations are enforced for their operations.

### Resilience observation

`dev.vertique.resilience.spi.ResilienceObserver` consumes the sealed event vocabulary under
`dev.vertique.resilience.spi.event`. Events expose only derived operation/state keys, positive
runtime-local execution IDs, enums, safe exception class names, and bounded numeric fields. The
runtime emits `ExecutionStarted` and `ExecutionCompleted` for every pipeline execution, plus
attempt, retry, timeout, circuit, and bulkhead events for the applicable path. `Resilience.create`
accepts an optional observer set for standalone use; Dagger applications contribute observers to
the `@Multibinds Set<ResilienceObserver>` declared by `ResilienceModule`.

### Runtime exception surface

Runtime failures use two sealed public roots: `ResilienceException` extends
`dev.vertique.core.exception.TechnicalException` and permits `ResilienceTimeoutException` and
`ResiliencePolicyException`; `ResilienceUnavailableException` extends
`dev.vertique.core.exception.UnavailableException` and permits `ResilienceClosedException`,
`CircuitOpenException`, `BulkheadRejectedException`, and `BulkheadQueueTimeoutException`.
`CircuitOpenException`, `BulkheadRejectedException`, and `BulkheadQueueTimeoutException` are part of
the published exception surface. `CircuitOpenException` carries the derived pipeline operation key
and breaker state key; direct breaker execution uses its state key for both. Bulkhead policy
execution is available for programmatic pipelines and for framework adapter contexts.

`ResilienceTimeoutException` exposes the validated derived operation key and `timeoutMs`;
`ResilienceClosedException` exposes the validated derived operation key. Public exception messages
are fixed and redacted: they omit raw operation or state names, configured values, callback
messages, request data, and supplier failures. Policy failures retain only safe enum, callback-kind,
and exception-class metadata. Exception constructors reject arbitrary raw keys or message text, and
do not attach the original throwable as a cause or suppressed exception.

---

## Configuration

Named policy tiers are an opt-in configuration surface under the keyed object
`resilience.policies.<name>`. The entry key is injected as `ResiliencePolicyConfig.name` and must
match `[A-Za-z0-9._~-]{1,128}`. For example:

```json
{
  "resilience": {
    "policies": {
      "payments": {
        "timeout": {"valueMs": 2000},
        "retry": {"maxRetries": 5, "delayMs": 100},
        "circuitBreaker": {"maxFailures": 3, "resetTimeoutMs": 10000},
        "bulkhead": {"maxConcurrentCalls": 8, "mode": "QUEUE", "maxQueueSize": 16, "queueTimeoutMs": 250}
      }
    }
  }
}
```

Install `ResiliencePoliciesModule` alongside `ResilienceModule` when named tiers are used. The
provider requires the application's `@VertxConfig JsonObject` and canonical `ConfigParser`
bindings (normally supplied by `VertxModule` and `ConfigParsingModule`). Consumers inject
`Optional<ResiliencePolicyRegistry>` and use `ResiliencePolicyRegistry.empty()` when the opt-in
provider is absent:

```java
ResiliencePolicyRegistry registry = registryOptional.orElse(ResiliencePolicyRegistry.empty());
```

`ResiliencePolicyConfig.toOverrides()` preserves the raw partial tier: omitted fields remain
omitted and no declaration defaults are filled while parsing. When a named tier is selected by
`@Resilient(policy = "payments")`, `ResiliencePolicyRegistry.layer(...)` completes retry fields
(`delayMs = 500`, `backoffMultiplier = 2.0`, `maxDelayMs = 30000`) and circuit-breaker fields
(`maxFailures = 5`, `resetTimeoutMs = 10000`) only when that concern has no annotation declaration.
Declared concern values remain partial so the resolver can apply them; transport overrides have
higher precedence. Timeout and bulkhead tiers are complete by construction, and without a selected
named tier `layer(...)` returns transport overrides unchanged.

---

## Extension Points

Implement `BackoffStrategy` when a consumer needs a custom delay schedule. The class may be supplied
through `@Retry(backoff = CustomBackoff.class)` or through the consumer module's own configuration.
Consumer modules instantiate configured classes reflectively, so custom strategies must provide a
public no-argument constructor; construction failures are reported as `IllegalStateException`.
Implement `RetryPolicy` when retry eligibility depends on application-specific failure rules.

## Module Dagger Bindings

`dev.vertique.resilience.dagger.ResilienceModule` provides one `@Singleton` `Resilience` for the
application's `Vertx` binding and contributes one `ApplicationShutdownStep` to the host lifecycle.
The step invokes the same idempotent runtime `close()` operation during the `CONFIGURE` phase, so
Dagger-owned applications can share the runtime's closed fencing and timer cleanup. The module
also declares the empty-by-default multibound observer set.

---

## Dependencies

- `dev.vertique:vertique-core` — annotation hierarchy resolution, shared exception roots, and
  lifecycle contracts.
- `dev.vertique:vertique-aop` — the `@Aspect` metadata used by `@Resilient` and the
  `AspectProvider`/`MethodInterceptor` contracts used by the AOP integration.
- `io.vertx:vertx-core` — runtime contexts, asynchronous futures, promises, and timers.
- `com.google.dagger:dagger` and `jakarta.inject:jakarta.inject-api` — the singleton runtime and
  lifecycle bindings exposed by `ResilienceModule`.
