<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Cache Codegen

> **Status:** Alpha
> **Package:** `dev.vertique.cache.codegen`
> **Artifact:** `vertique-cache-codegen`
> **Depends on:** `vertique-cache-core`, `vertique-codegen-core`

`vertique-cache-codegen` is the build-time module boundary for cache annotation validation and generated cache metadata. It remains separate from the provider-neutral runtime and from storage providers. It is an annotation-processor artifact, not a runtime dependency.

## When To Use It

Use this artifact in the compile-time processor path of applications that use generated cache metadata. Runtime applications should also declare the cache runtime and a concrete provider according to their composition.

## Core Concepts

The registered `CacheAnnotationProcessor` validates `@Cacheable` and `@CacheEvict` declarations and returns control to the other processors. The generic AOP processor emits the application-owned subclass proxy and reflection-free `MethodMetadata`; generated types belong to the consuming application compilation and are not supplied by a runtime provider module.

## Validation boundary

The processor validates cache declarations at compilation. A cacheable or eviction method
must be declared on a public, non-final class with exactly one constructor annotated with
`jakarta.inject.Inject` or `javax.inject.Inject`. The annotated method must be an instance
method that can be overridden; final, private, static, abstract, and non-proxyable method
shapes produce diagnostics. `@Cacheable` methods must return a value, and raw, wildcard, or
type-variable `Future` results are rejected.

Selectors use literal text plus positional (`{0}`) or parameter-name (`{user}`) selectors.
Property paths may contain at most three properties after the parameter. Each property must
resolve to a public, zero-argument instance accessor: a record accessor, a method named for
the property, or a JavaBean `getX()`/`isX()` accessor. The terminal must be one of the
processor's supported scalar shapes:

| Shape | Accepted types |
|---|---|
| Text and primitives | `String`, `char`/`Character`, `boolean`/`Boolean`, and numeric primitives or wrappers |
| Numeric values | `BigInteger`, `BigDecimal` |
| Other scalar values | `UUID`, enum types, and `java.time.temporal.TemporalAccessor` types |

Blank selectors, unmatched braces, unsupported literal characters, invalid identifiers,
unresolved parameters or accessors, excessive property depth, object/container terminals,
and unsupported return shapes produce compile-time diagnostics. The processor does not
perform a separate ambiguity check; a selector must resolve through the ordinary parameter
and accessor lookup rules.

For `@CacheEvict`, proxyability is always validated. A nonblank key is selector-validated;
a clear operation has no selector to validate. The runtime eviction aspect treats a
declaration with neither a key nor `clear = true` as a no-op.

For a JAX-RS `@GET` method, `@Cacheable` accepts an entity or `Future<entity>` result. HTTP
response wrappers, transport response types, buffers, routing contexts, streams, publishers,
and `Multi` results are rejected. The processor emits no selector `toString()` fallback.
Generated method metadata is reflection-free, while the current runtime selector renderer
invokes the validated record or bean accessor and fails open when a null or invalid value
cannot produce a key. The configured `CacheConfig.maxKeyBytes` limit applies to the complete
canonical UTF-8 key; its default is 1,024 bytes.

Inherited annotations are not promoted to a different concrete bean by this processor; the
validated method belongs to its declaring class. Self-invocation is not diagnosed here:
interception occurs only when a call enters the generated AOP override, so direct
construction and calls that bypass the proxy are not intercepted.

The generic AOP processor must be present on the application's annotation-processor path
alongside this artifact for the validated annotations to produce proxies and metadata.

## Decision records

- [D012 — Named and property key selectors](../../../../../../../../../docs/specs/cache-001-annotation-cache-support/decisions/D012-named-and-property-key-selectors.md)
- [D015 — Cache REST entities, not HTTP responses](../../../../../../../../../docs/specs/cache-001-annotation-cache-support/decisions/D015-cache-rest-entities-not-http-responses.md)
- [D021 — Generated module composes cache runtime](../../../../../../../../../docs/specs/cache-001-annotation-cache-support/decisions/D021-generated-module-composes-cache-runtime.md)
- [D023 — Public cache key and value shape](../../../../../../../../../docs/specs/cache-001-annotation-cache-support/decisions/D023-public-cache-key-value-shape.md)

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-cache-core` | Cache annotation and runtime contract types |
| `vertique-codegen-core` | Shared annotation-processor utilities |
