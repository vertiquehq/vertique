<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Resilience Module

> **Status:** Stable
> **Package:** `dev.vertique.resilience`
> **Artifact:** `vertique-resilience`
> **Depends on:** `vertique-core`, `vertx-core`, `vertx-circuit-breaker`, Dagger, `jakarta.inject-api`

`vertique-resilience` provides the shared resilience vocabulary and runtime foundation used by
Vertique consumers. It contains type and method annotations for timeout, retry, and circuit-breaker
declarations; immutable metadata snapshots for resolved declarations; retry contracts; and an
application-scoped runtime with executable timeout/retry/circuit-breaker/bulkhead policy composition,
deterministic policy resolution, and execution budgets. Framework adapter policies containing a
bulkhead remain rejected eagerly until downstream adapter cutovers own that boundary.

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

The three declaration families are independent: an operation may configure any combination of
timeout, retry, and circuit breaker. `ResilienceAnnotations.NONE` is the shared empty value, and
`hasAny()` is the convenient test for whether a declaration is present.

The executable foundation is application-scoped. Create one `Resilience` for the application graph
and construct timeout/retry/breaker/bulkhead components or pipelines from that owner. A timeout is
a per-supplier-attempt fence backed by a Vert.x timer; it does not cancel the supplier's underlying
future. Retry delays are outside the per-attempt timeout, and retry callbacks receive zero-based
ordinals. A pipeline is fixed-order typed composition state and must contain at least one concern.
A circuit breaker is an independently executable local component: it admits one logical execution,
counts its final result once, disables Vert.x's own timeout and retry behavior, and admits one
no-retry half-open probe after reset. A bulkhead admits whole logical executions with either
immediate rejection or a FIFO bounded queue. Stateful components never share state by equal names.

---

## Key Classes

### Resilience annotations

`dev.vertique.resilience.annotation.Timeout`, `Retry`, and `CircuitBreaker` are runtime-retained
annotations targeting types and methods. `Retry` references a `BackoffStrategy` class and lists
exception types eligible for retry or immediate abort.

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
`TimeoutDeclaration`, `RetryDeclaration`, and `CircuitBreakerDeclaration` snapshots. The snapshots
are suitable for generated contributors and reflective registration; they do not expose live
annotation instances.

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
completion, and runtime close race through a settle-once fence: exactly one outcome wins, timers are
cancelled on settlement, and a late supplier completion is ignored. Neither timeout nor close
cancels the supplier's underlying future.

Closing a runtime is asynchronous and idempotent: repeated `close()` calls return the same terminal
future. Close fences active public executions with `ResilienceClosedException`, cancels runtime-owned
timers, and does not wait for uncancelled supplier futures. New pipeline construction and execution
are rejected after close: pipeline creation and component `build()` fail, while an existing pipeline's
`execute` returns a failed future. The closed state is also observed by the Dagger lifecycle shutdown
step.

### Retry contracts

`BackoffStrategy` computes a delay in milliseconds from a zero-based retry count. Use
`BackoffStrategy.exponential`, `fixed`, or `none` for standard policies, or implement the functional
interface for a custom strategy. `RetryPolicy` determines whether a failure is eligible for retry
when a consumer's retry configuration delegates eligibility to a policy.

`Retry.builder(resilience, operationName)` creates an independently executable retry component.
`ResiliencePipeline.Builder.retry(...)` accepts a prebuilt `Retry` or an inline retry builder. Retry
is opt-in, starts at attempt zero, and performs at most `maxRetries + 1` attempts. `abortOn` wins
over `retryOn`; delays are scheduled between attempts and do not consume the per-attempt timeout.
Callback failures become cause-free `ResiliencePolicyException` values with only safe callback-kind
and exception-class metadata. Fatal `Error` values are rethrown.

`ResiliencePolicyResolver` resolves immutable policy values using operation override, annotation,
default, then disabled precedence independently for each concern. `RetryConfig`, `RetryBackoff`,
`CircuitBreakerConfig`, and `BulkheadConfig` validate eagerly. `ResolvedResiliencePolicy.executionBudget()`
reports conservative active, queue, and total bounds using saturating arithmetic; unknown or
unbounded inputs remain explicit.

Framework adapters use `ResilienceAdapterSupport.pipeline(AdapterOperationIdentity,
ResolvedResiliencePolicy)` for the stable structured entry point. It derives the opaque key internally,
accepts timeout-only and retry-only policies, and rejects empty or bulkhead-bearing policies before
state, timers, or suppliers are started. For breaker policies, create a `ResilienceAdapterContext`
with `newContext()`. Its classifier overload maps only adapter-final failures into breaker
accounting; classifier failures preserve the original failure and count conservatively. Contexts
own breakers and fence active public futures on `close()`. Equal structured identities in separate
contexts do not share state. No raw-key overload or standalone public identity derivation is
provided.

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

Framework adapter factories continue to reject bulkhead-bearing resolved policies in this slice;
Services and REST cutover tasks own the decision to expose adapter bulkhead configuration.

### Runtime exception surface

Runtime failures use two sealed public roots: `ResilienceException` extends
`dev.vertique.core.exception.TechnicalException` and permits `ResilienceTimeoutException` and
`ResiliencePolicyException`; `ResilienceUnavailableException` extends
`dev.vertique.core.exception.UnavailableException` and permits `ResilienceClosedException`,
`CircuitOpenException`, `BulkheadRejectedException`, and `BulkheadQueueTimeoutException`.
`CircuitOpenException`, `BulkheadRejectedException`, and `BulkheadQueueTimeoutException` are part of
the published exception surface. `CircuitOpenException` carries the derived pipeline operation key
and breaker state key; direct breaker execution uses its state key for both. Bulkhead policy
execution is available for programmatic pipelines; framework adapter policies remain rejected until
the downstream cutover tasks own that boundary.

`ResilienceTimeoutException` exposes the validated derived operation key and `timeoutMs`;
`ResilienceClosedException` exposes the validated derived operation key. Public exception messages
are fixed and redacted: they omit raw operation or state names, configured values, callback
messages, request data, and supplier failures. Policy failures retain only safe enum, callback-kind,
and exception-class metadata. Exception constructors reject arbitrary raw keys or message text, and
do not attach the original throwable as a cause or suppressed exception.

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
Dagger-owned applications can share the runtime's closed fencing and timer cleanup.

---

## Dependencies

- `dev.vertique:vertique-core` — annotation hierarchy resolution, shared exception roots, and
  lifecycle contracts.
- `io.vertx:vertx-core` — runtime contexts, asynchronous futures, promises, and timers.
- `com.google.dagger:dagger` and `jakarta.inject:jakarta.inject-api` — the singleton runtime and
  lifecycle bindings exposed by `ResilienceModule`.
