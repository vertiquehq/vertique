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

Annotations may be placed on a type or method. Type-level declarations are supported on transport
interfaces. On concrete classes, declarations must be placed on the same method as `@Resilient`;
class-level declarations are rejected at compile time. A method declaration replaces the
corresponding type-level declaration; values are not merged attribute by attribute.
`ResilienceAnnotations` resolves the effective declarations through the method and type hierarchy
and exposes immutable declaration records through `Optional` accessors.

The four declaration families are independent: an operation may configure any combination of
timeout, retry, circuit breaker, and bulkhead. `@Resilient` is a method-level anchor that may select
a named policy tier; an anchor-only method is active when its policy name is nonblank.
An empty or whitespace-only `policy()` is treated as no named tier.
`ResilienceAnnotations.NONE` is the shared empty value, and `hasAny()` is true when any declaration
or policy name is present. The `resolve(MethodMetadata)` overload reads only method-level metadata
through `findAnnotation`, while the reflective overloads also resolve type-level declarations.

The executable foundation is application-scoped. Create one `Resilience` for the application graph
and construct timeout/retry/breaker/bulkhead components or pipelines from that owner. Timeout and
retry execution is backed by isolated Vert.x CircuitBreaker engines; a timeout is a
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

An anchor on an interface method follows the method-AOP rule: it is woven onto the concrete
implementors in the compilation, as on a class method, so a JAX-RS server resource interface or any
other interface can carry `@Resilient` and its declarations for the resource that implements it. A
REST-client interface has no implementor, so its anchor wraps the generated client call. A
service-contract interface anchor is applied once, by the services dispatch pipeline, and is not woven
onto the contract's implementors; see the double-wrapping caveat below for a handler that declares
resilience itself. A call dispatched through the contract runs the policy once, and a direct in-process
call on the implementor is not wrapped. Any other aspect declared on the same contract method is still
woven.

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

### Cancelling an execution

`pipeline.execute(operation, cancellation)` runs one execution that a caller can cancel. Create the
handle with `ResilienceCancellation.create()` and call `cancel(Throwable reason)` from any thread; the
first call wins and later calls do nothing. `cancel(null)`, `execute(null, cancellation)` and
`execute(operation, null)` throw `NullPointerException` synchronously. `execute(operation)` behaves as
the overload with a cancellation that is never cancelled.

Cancelling ends the execution's own waiting states. A pending retry delay stops and no further
attempt starts, a call queued in a bulkhead leaves the queue and frees its slot, and a bulkhead permit
is released. The returned future fails once, on the execution context, with the given reason, and the
execution is reported as `ExecutionCompleted` with outcome `APPLICATION_FAILURE`. An operation future
already in flight is not cancelled: its late result is ignored, and the owner of the operation resets
its own work. A cancelled execution never closes a circuit breaker. It counts as a failure when it was
the half-open trial, so the breaker re-opens and waits `resetTimeout` again, and as a success when the
breaker is closed, which like any success resets the consecutive-failure count.

### Supplier-reported timeouts

When an attempt's supplier throws, or its future fails with, a `ResilienceTimeoutException` whose
`operationKey()` equals the pipeline's `operationKey()`, the runtime records that attempt as a timeout
of this pipeline. It emits `TimeoutTriggered` carrying the exception's `timeoutMs()`, never the
pipeline's configured timeout, then `AttemptCompleted` with outcome `TIMEOUT`. This applies on every
attempt path, including pipelines without a timeout or retry concern and the half-open trial. The
exception instance reaches retry eligibility, the breaker classifier and the caller unchanged. Exactly
one `TimeoutTriggered` is emitted per attempt, whether the pipeline's own timer or the supplier ends
it. A `ResilienceTimeoutException` with another key keeps today's behavior: `AttemptCompleted` with
outcome `TIMEOUT` and no `TimeoutTriggered`.

Operation keys identify operations, not pipeline instances, so nesting two pipelines that share an
operation name, one executed inside the other's supplier, changes behavior: the inner timeout now also
emits `TimeoutTriggered` on the outer pipeline, where the outer previously recorded only
`AttemptCompleted` with outcome `TIMEOUT`. Give nested pipelines distinct names to count a timeout once.

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

A failure can ask for a longer wait by implementing `RetryDelayHint` (`Optional<Duration> retryAfter()`),
for example a server's `Retry-After` answer. The hint is opt-in: it is read only when the retry
configuration's `retryAfterMaxMs` is above 0 (the default, 0, ignores every hint). After the normal
eligibility decision, the retry waits `max(sampled backoff, hint)` when the hint is at most
`retryAfterMaxMs`, and is refused when the hint is above it: the original failure is surfaced,
`RetryExhausted` is emitted and no `RetryScheduled` is. `RetryScheduled` reports the delay actually
chosen. The hint never makes a failure retryable and never overrides `abortOn`. The first
`RetryDelayHint` in the failure's cause chain is used; an accessor that throws, or returns `null`, an
empty value, or a negative or overflowing duration, counts as no hint. A negative backoff still stops the
retry. Set the maximum with `RetryConfig.Builder.retryAfterMaxMs(long)` or the tier key
`resilience.policies.<name>.retry.retryAfterMaxMs`. The public builders expose the same maximum as
`Retry.Builder.retryAfterMaxMs(long)`, so a pipeline built with `resilience.pipeline(name).retry(r ->
r.maxRetries(2).retryAfterMaxMs(5000))` honors a hint of up to five seconds; a negative value is rejected.
Callback failures become cause-free `ResiliencePolicyException` values with only safe callback-kind
and exception-class metadata. Fatal `Error` values are rethrown.

`ResiliencePolicyResolver` resolves immutable policy values using operation override, annotation,
default, then disabled precedence independently for each concern. Annotation inheritance follows the Java override rules: a method also inherits the annotations of a generic declaration whose type variables its class binds (`delete(String)` overrides `Crud<ID>.delete(ID)` of a `Crud<String>`), and of protected and same-package declarations; a same-erasure overload with another type argument, and a static or private method, override nothing.
 `RetryConfig`, `RetryBackoff`,
`CircuitBreakerConfig`, and `BulkheadConfig` validate eagerly. `ResolvedResiliencePolicy.executionBudget()`
reports conservative active, queue, and total bounds using saturating arithmetic; unknown or
unbounded inputs remain explicit. With `retryAfterMaxMs` above 0, each retry's upper bound for fixed and
exponential backoff becomes the larger of its backoff bound and `retryAfterMaxMs`; custom backoff
stays unknown, and a maximum of 0 leaves the budget unchanged.

Framework adapters use `ResilienceAdapterSupport.pipeline(AdapterOperationIdentity,
ResolvedResiliencePolicy)` for the stable structured entry point. It derives the opaque key internally,
accepts timeout-only and retry-only policies, and rejects empty or stateful policies before state,
timers, or suppliers are started. For breaker or bulkhead policies, create a `ResilienceAdapterContext`
with `newContext()`. Its classifier overload maps only adapter-final failures into breaker
accounting; classifier failures preserve the original failure and count conservatively. Contexts
own breakers and bulkheads and fence active public futures on `close()`. Equal structured identities
in separate contexts do not share state. A context's `pipeline(identity, policy)` also accepts a resolved policy that enables no concern and runs the supplier as one recorded attempt, including a supplier-reported timeout of its own key; every other construction path still rejects it. No raw-key overload or standalone public identity derivation
is provided.

### Circuit breaker and adapter context

`CircuitBreaker.builder(resilience, stateName)` creates a standalone local breaker. The component
builder accepts a positive failure threshold and `Duration` reset timeout. `execute` can be used
directly or a prebuilt breaker can be placed in a pipeline. Inline pipeline construction creates a
pipeline-local breaker. A pipeline uses its own operation key in `CircuitOpenException`, while the
breaker contributes only its state key; direct breaker execution uses the state key for both.

By default every final failure counts against the breaker. `CircuitBreaker.Builder.countsAsFailure(Predicate<?
super Throwable>)` narrows that: a failure the predicate rejects still surfaces to the caller unchanged, but
it is recorded as a success, so it neither opens the breaker nor extends a failure streak. A predicate that
throws counts the failure, and fatal errors pass through before the predicate runs. A rejected failure on the
half-open trial counts as a success and closes the circuit, because the dependency answered. The predicate must be
non-blocking and thread-safe, since a breaker may be shared. A classifier supplied by a framework adapter takes
precedence over the builder's predicate.

```java
ResiliencePipeline pipeline = resilience.pipeline("inventory")
        .circuitBreaker(cb -> cb.maxFailures(5).countsAsFailure(f -> !(f instanceof IllegalArgumentException)))
        .build();
```

A client library can classify its own failures the same way, for example by not counting a rejection whose
status code signals a caller mistake.

Framework adapters should obtain `ResilienceAdapterContext` from
`ResilienceAdapterSupport.newContext()`. The context creates structured-identity breakers and
explicitly tracks same-owner prebuilt reuse. Its classifier overload sets the failure classification for
adapter-final failures and takes precedence over a breaker's `countsAsFailure` predicate. `close()` is idempotent, closes owned
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

Runtime failures use two sealed public roots and one core-rooted timeout: `ResilienceException` extends
`dev.vertique.core.exception.TechnicalException` and permits `ResiliencePolicyException`;
`ResilienceTimeoutException` extends `dev.vertique.core.exception.OperationTimeoutException` (a REST boundary
answers it with 504) and is not a `ResilienceException`; `ResilienceUnavailableException` extends
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
        "retry": {"maxRetries": 5, "delayMs": 100, "retryAfterMaxMs": 5000},
        "circuitBreaker": {"maxFailures": 3, "resetTimeoutMs": 10000},
        "bulkhead": {"maxConcurrentCalls": 8, "mode": "QUEUE", "maxQueueSize": 16, "queueTimeoutMs": 250}
      }
    }
  }
}
```

`retry.retryAfterMaxMs` (long, default 0, must be `>= 0`) is the longest failure delay hint the retry
honors; an invalid value fails with a path-qualified `ConfigurationException`.

A tier must configure at least one of `timeout`, `retry`, `circuitBreaker`, or `bulkhead`. When a
block is present, `timeout.valueMs` (`> 0`), `retry.maxRetries` (`0..100`), and
`bulkhead.maxConcurrentCalls` (`> 0`) are required. `retry.delayMs` and `retry.maxDelayMs` must be
`>= 0` and `retry.backoffMultiplier` finite and `>= 1.0`. `circuitBreaker.maxFailures` must be `>= 1`
and `resetTimeoutMs` `> 0`. `bulkhead.mode` defaults to `REJECT`, which rejects `maxQueueSize` and
`queueTimeoutMs`; `QUEUE` requires `maxQueueSize` (`1..1024`) and `queueTimeoutMs` (`1..60000`).
Violations fail with a path-qualified `ConfigurationException`, and selecting a tier that is not
configured fails with `resilience.policies.<name> is not defined`.

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
A custom strategy class is instantiated reflectively, so it must provide a public no-argument
constructor. A class named by `@Retry(backoff = ...)` that cannot be instantiated surfaces from the
policy resolver as a `ResiliencePolicyException` with reason `INVALID_CONFIGURATION`; a consumer module
that instantiates a configured class itself, such as the REST client for
`restClient.<name>.retry.backoffStrategy`, reports the failure with its own exception type.
Implement `RetryPolicy` when retry eligibility depends on application-specific failure rules.

## Module Dagger Bindings

`dev.vertique.resilience.dagger.ResilienceModule` provides one `@Singleton` `Resilience` for the
application's `Vertx` binding and contributes one `ApplicationShutdownStep` to the host lifecycle.
The step invokes the same idempotent runtime `close()` operation during the `CONFIGURE` phase, so
Dagger-owned applications can share the runtime's closed fencing and timer cleanup. The module
also declares the empty-by-default multibound observer set and an optional
`ResiliencePolicyRegistry` binding that `ResiliencePoliciesModule` fills.

---

## Dependencies

- `dev.vertique:vertique-core` — annotation hierarchy resolution, shared exception roots, and
  lifecycle contracts.
- `dev.vertique:vertique-aop` — the `@Aspect` metadata used by `@Resilient` and the
  `AspectProvider`/`MethodInterceptor` contracts used by the AOP integration.
- `io.vertx:vertx-core` — runtime contexts, asynchronous futures, promises, and timers.
- `io.vertx:vertx-circuit-breaker` — the isolated Vert.x CircuitBreaker engines behind timeout, retry,
  and circuit-breaker execution.
- `com.google.dagger:dagger` and `jakarta.inject:jakarta.inject-api` — the singleton runtime and
  lifecycle bindings exposed by `ResilienceModule`.
