<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Rate Limit Codegen

> **Status:** Stable
> **Package:** `dev.vertique.codegen.ratelimit`
> **Artifact:** `vertique-codegen-rate-limit`
> **Depends on:** `vertique-rate-limit-aop`, `vertique-codegen-core`

`vertique-codegen-rate-limit` is the build-time module boundary for `@RateLimited`
validation. It is an annotation-processor artifact, not a runtime dependency: it
generates no sources, and proxy/metadata generation for `@RateLimited` methods
remains owned by the generic Vertique AOP processor.

## When To Use It

Add this artifact to the compiler's annotation-processor path of any application
that uses `@RateLimited`, alongside the generic AOP processor. Runtime applications
separately declare `vertique-rate-limit-aop` and, for CLUSTERED policies,
`vertique-rate-limit-redis`.

## Core Concepts

The registered processor validates every `@RateLimited` declaration at compile time
and always returns `false` from `process()`. It checks:

- **Policy name syntax** — must match `[A-Za-z0-9._~-]{1,128}`.
- **Cost** — `cost()` must be at least `1`.
- **Selector paths** — each `key()` entry must be non-blank, at most 256 characters,
  and at most 8 segments including the root; the root must resolve to a declared
  method parameter (by name or position); each subsequent segment must be a public,
  zero-argument record component or `getX`/`isX` accessor; the terminal segment must
  resolve to a supported scalar type (`String`, `Character`, `Boolean`, a numeric
  primitive or wrapper, `BigInteger`, `BigDecimal`, `UUID`, an enum type, or a
  `java.time` scalar).
- **Proxyability preconditions** — the declaring type must be a public, non-final class
  with exactly one `@Inject` constructor, and the annotated method must be an
  instance method that can be overridden (not final, private, static, or abstract).
  Diagnostics are prefixed `rate-limited methods ...`.

A blank path, an invalid identifier, an unresolved parameter or accessor, excessive
segment depth, or an unsupported terminal type each produce a distinct compile-time
diagnostic naming the offending path, for example: *"rate-limit key property paths
are limited to eight segments including the root parameter"* and *"rate-limited
methods require exactly one @Inject constructor"*.

### `@RateLimited` on an interface method

`@RateLimited` on an interface method is allowed for weaving: `ProxyabilityValidator` skips
interface-enclosed methods, and `vertique-codegen-aop` attributes the trigger to every concrete
implementing class in the compilation that inherits or overrides the method. Policy name, cost,
and key-path checks still run on the interface method. An interface alone is not a proxy bean —
without a concrete implementor, no `$AopProxy` is generated and no admission occurs.

The generic AOP processor must be present on the same annotation-processor path for
a validated `@RateLimited` declaration to actually produce a proxy and method
metadata; this artifact validates only.

## Key Classes

### `RateLimitAnnotationProcessor`

`AbstractProcessor` registered via `META-INF/services/javax.annotation.processing.Processor`.

```
@SupportedAnnotationTypes("dev.vertique.ratelimit.aop.RateLimited")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
```

Validates every `@RateLimited` method as described under Core Concepts. `process()` always
returns `false`, so other processors still see the annotation, and the processor generates no
sources.

## Verification

```text
./mvnw -ntp -pl vertique-codegen/vertique-codegen-rate-limit -am verify
```

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-rate-limit-aop` | `@RateLimited` annotation this processor validates |
| `vertique-codegen-core` | Shared annotation-processor utilities |
