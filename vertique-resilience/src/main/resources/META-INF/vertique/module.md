<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Resilience Module

> **Status:** Stable
> **Package:** `dev.vertique.resilience`
> **Artifact:** `vertique-resilience`
> **Depends on:** `vertique-core`, `vertx-core`, Dagger, `jakarta.inject-api`

`vertique-resilience` provides the shared resilience vocabulary and runtime foundation used by
Vertique consumers. It contains type and method annotations for timeout, retry, and circuit-breaker
declarations; immutable metadata snapshots for resolved declarations; retry contracts; and an
application-scoped runtime with executable timeout and pipeline composition. This foundation does
not execute retry, circuit-breaker, or bulkhead policies; those concerns remain outside this slice.

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
and construct timeout components or pipelines from that owner. A timeout is a per-supplier-attempt
fence backed by a Vert.x timer; it does not cancel the supplier's underlying future. A pipeline is
fixed-order composition state. This module currently executes only its timeout concern, even though
the public exception vocabulary already includes circuit-breaker and bulkhead failure types.

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
```

Timeout durations must be positive and exactly representable in milliseconds. `Duration` values
that are zero, negative, sub-millisecond, or too large for a millisecond `long` are rejected;
`TimeoutConfig.ofMillis` likewise requires a positive value. Every timeout duration setter and every
pipeline timeout setter may be used once, and each builder is single-use. A pipeline must configure
one timeout before `build()`. A prebuilt timeout must belong to the same `Resilience` runtime as the
pipeline; a foreign owner is rejected eagerly.

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

### Runtime exception surface

Runtime failures use two sealed public roots: `ResilienceException` extends
`dev.vertique.core.exception.TechnicalException` and permits `ResilienceTimeoutException` and
`ResiliencePolicyException`; `ResilienceUnavailableException` extends
`dev.vertique.core.exception.UnavailableException` and permits `ResilienceClosedException`,
`CircuitOpenException`, `BulkheadRejectedException`, and `BulkheadQueueTimeoutException`.
`CircuitOpenException`, `BulkheadRejectedException`, and `BulkheadQueueTimeoutException` are part of
the published exception surface, but this foundation does not execute circuit-breaker or bulkhead
policies.

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
