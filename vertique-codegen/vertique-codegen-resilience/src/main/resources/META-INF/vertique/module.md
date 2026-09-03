<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Vertique Codegen Resilience

> **Status:** Alpha
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

## Validation Rules

The registered `ResilienceAnnotationProcessor` validates:

- policy names against `[A-Za-z0-9._~-]{1,128}`;
- retry, timeout, circuit-breaker, and bulkhead value bounds on every placement,
  including interfaces;
- method-level anchors and the rule that concrete-class declarations must be
  on the same method as `@Resilient`;
- interface anchors only on `@ServiceContract` or REST-client interfaces;
- proxyability through the shared `ProxyabilityValidator`, including public,
  non-final, Dagger-managed classes with one `@Inject` constructor and
  overridable instance methods;
- concrete `Future<T>` return types using the shared `CodegenContext.unwrapFuture`
  classifier; and
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
