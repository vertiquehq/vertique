<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Resilience Module

> **Status:** Stable
> **Package:** `dev.vertique.resilience`
> **Artifact:** `vertique-resilience`
> **Depends on:** `vertique-core`

`vertique-resilience` provides the shared resilience vocabulary used by Vertique consumers. It
contains type and method annotations for timeout, retry, and circuit-breaker declarations; immutable
metadata snapshots for resolved declarations; and the small contracts used for retry delays and
retry eligibility. The module defines declarations and contracts only. Policy execution remains in
the module that consumes them.

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

### Retry contracts

`BackoffStrategy` computes a delay in milliseconds from a zero-based retry count. Use
`BackoffStrategy.exponential`, `fixed`, or `none` for standard policies, or implement the functional
interface for a custom strategy. `RetryPolicy` determines whether a failure is eligible for retry
when a consumer's retry configuration delegates eligibility to a policy.

---

## Extension Points

Implement `BackoffStrategy` when a consumer needs a custom delay schedule. The class may be supplied
through `@Retry(backoff = CustomBackoff.class)` or through the consumer module's own configuration.
Consumer modules instantiate configured classes reflectively, so custom strategies must provide a
public no-argument constructor; construction failures are reported as `IllegalStateException`.
Implement `RetryPolicy` when retry eligibility depends on application-specific failure rules.

---

## Dependencies

- `dev.vertique:vertique-core` — annotation hierarchy resolution and shared framework utilities.
