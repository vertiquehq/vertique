<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Vertique Codegen Resilience

> **Status:** Stable
> **Package:** `dev.vertique.codegen.resilience`
> **Artifact:** `vertique-codegen-resilience`
> **Depends on:** `vertique-resilience`, `vertique-codegen-core`

`vertique-codegen-resilience` is the build-time module boundary for validating
`@Resilient` and its retry, timeout, circuit-breaker, and bulkhead declarations.
It is an annotation-processor artifact, not a runtime dependency. The processor
generates no sources and always returns `false` from `process()`.

## When To Use It

Put this artifact on the annotation-processor path of an application that uses
the resilience annotations, alongside `vertique-codegen-aop`. Applications
declare `vertique-resilience` separately at runtime. The `vertique-codegen-all`
facade includes this processor transitively.

## Key Classes

### `ResilienceAnnotationProcessor`

`AbstractProcessor` registered via `META-INF/services/javax.annotation.processing.Processor`.

```
@SupportedAnnotationTypes({
    "dev.vertique.resilience.annotation.Resilient",
    "dev.vertique.resilience.annotation.Retry",
    "dev.vertique.resilience.annotation.Timeout",
    "dev.vertique.resilience.annotation.CircuitBreaker",
    "dev.vertique.resilience.annotation.Bulkhead"
})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
```

Validates the resilience annotation family and generates no sources. Always
returns `false` from `process()` so Dagger, Lombok, `vertique-codegen-aop`, and
other processors still see the same elements. See [Validation Rules](#validation-rules).

## Validation Rules

The registered `ResilienceAnnotationProcessor` validates:

- policy names against `[A-Za-z0-9._~-]{1,128}`;
- retry, timeout, circuit-breaker, and bulkhead value bounds on every placement,
  including interfaces;
- method-level anchors and the rule that concrete-class declarations must be
  on the same method as `@Resilient`;
- interface anchors on any interface, because `@Resilient` follows the method-AOP rule every
  aspect follows: a trigger on an interface method is woven onto the concrete implementors in
  the compilation, as on a class method. A REST-client interface has no implementor, so its
  anchor wraps the generated client call. A service-contract interface anchor is applied once,
  by the services dispatch pipeline, and is not woven onto the contract's implementors (see the
  double-wrapping rule below for a handler that declares resilience itself). On an
  interface method the processor checks the policy-name syntax and the value bounds, that the
  anchor names a policy or carries a declaration, and that the return type is a concrete
  `Future<T>`; it applies no proxyability check. An implementor the proxy
  cannot subclass fails in `vertique-codegen-aop`, which owns the weaving;
- proxyability through the shared `ProxyabilityValidator`, including public,
  non-final, Dagger-managed classes with one `@Inject` constructor and
  overridable instance methods;
- concrete `Future<T>` return types on anchored methods, on concrete classes and on interfaces
  alike, using the shared `CodegenContext.unwrapFuture` classifier (an interface method may
  name a type variable as the payload, because each implementor specializes it); and
- duplicate resilience wrapping between a services contract and its concrete
  handler or implementor, with service types detected by fully qualified name.

The four declaration annotations are configuration inputs only. They are not
aspects by themselves, and this processor does not emit proxies or metadata.

## Verification

```text
./mvnw -ntp -pl vertique-codegen/vertique-codegen-resilience,vertique-codegen-all -am verify
```

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-resilience` | `@Resilient` and resilience declaration annotations |
| `vertique-codegen-core` | Shared annotation-processing context, diagnostics, Future classifier, and proxyability validator |
