<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Cache AOP

> **Status:** Stable
> **Package:** `dev.vertique.cache.aop`
> **Artifact:** `vertique-cache-aop`
> **Depends on:** `vertique-cache-core`, `vertique-aop`, `vertique-core`

`vertique-cache-aop` adapts `@Cacheable` and `@CacheEvict` method declarations to the
provider-neutral programmatic cache API. It owns annotation vocabulary, method metadata
adaptation, delegation to the shared runtime selector-path resolver, and the Dagger bindings
for the cache aspects.

Keys are declared as ordered selector paths, never as a template or format string:
`@Cacheable(name = "users", key = {"tenantId", "productId"}, subject = CacheIdentity.ACTOR)`.
The caller-subject dimension is selected with `subject`; the attribute is typed as
`CacheIdentity`. Each path names a parameter (by name or position) plus optional
record/bean accessor segments. The shared
selector-path grammar and runtime resolution rules (the `Selector-path grammar` section of the
`dev.vertique:vertique-aop` module reference)
define the accepted roots, accessors, limits, and scalar terminals. Path order is component
order, and the runtime alone composes and frames the canonical key. An explicitly empty
`key = {}` declares a value-independent constant operation key.
`@CacheEvict` declares exactly one of `clear = true` or an explicit `key` path array
(the explicit empty array evicts the constant entry); code generation rejects a
declaration with neither or both.

An exact `@CacheEvict` resolves its target policy rather than guessing it. Co-located
`@Cacheable` and `@CacheEvict` declarations are rejected by the processor because
their ordering and result/cache ownership are ambiguous; runtime adapters fail open
without evicting when manually supplied metadata bypasses that validation. A
standalone eviction resolves lazily — at first invocation — from the runtime catalog,
restricted to annotation-declared definitions whose ordered selector paths exactly
match the eviction declaration. It reuses the registered target's identity, mode, and
TTL, so it cannot address a different identity bucket. An unknown, programmatic-only,
or selector-schema-mismatched target is a typed `EVICT`/`UNRESOLVED_TARGET` observed
no-op: no provider is called and the eviction settles `false`. Programmatic caches are
invalidated through their own `Cache` handles, never by annotations.

The generic Vertique AOP processor generates the application proxy, reflection-free
`MethodMetadata`, annotation literals, and interceptor chain. This module does not own
proxy generation or cache storage behavior.

## When To Use It

Add `vertique-cache-aop` and install `CacheAopModule` alongside a cache provider module when
methods should declare result caching or invalidation through `@Cacheable` / `@CacheEvict`
rather than manual `Cache<K,V>` calls. Add `vertique-codegen-cache` to the compiler's
annotation-processor path so declarations are validated at compile time. Applications that only
use the programmatic API depend on `vertique-cache-core` alone.

Both annotations are `@Target(ElementType.METHOD)` only and are intended for methods of
Dagger-provided classes.

## Composition

Include `CacheAopModule` alongside a cache provider module in an application component.
Provider modules include only the provider-neutral `CacheCoreModule` and never this
module; an application that uses cache annotations installs `CacheAopModule`
explicitly, and a programmatic-only application omits it entirely. The adapters reach
definition resolution through the core's public `CacheAdapterSupport` seam.

Programmatic callers should depend only on `vertique-cache-core` and use `CacheBuilder`
and `Cache<K,V>` directly.

`@Aspect(ordering = 300)` on `@RateLimited`, `200` on `@Cacheable`, and `100` on `@CacheEvict`
places rate limiting outermost, then cacheable, then cache evict, with timed instrumentation
outside all of them. When `@Cacheable` is composed with `@RateLimited`, admission occurs before
the cache lookup: a cache hit still consumes one quota unit; a cache miss consumes one unit for
the complete logical call, regardless of how many retries the nested resilience pipeline performs.

**Self-invocation bypass.** Only calls that arrive through the Dagger-resolved generated proxy
are intercepted. Calling a `@Cacheable` or `@CacheEvict` method on `this` from inside the same
bean, or constructing the bean directly instead of resolving it through Dagger, bypasses the
proxy and therefore bypasses caching and eviction entirely.

## Key Classes

### @Cacheable

`name()` (required), `key()` (required ordered selector paths; `{}` is the constant key),
`mode()` (default `CacheMode.DEFAULT`), `ttlSeconds()` (default `-1`), `subject()` (default
`CacheIdentity.EFFECTIVE_PRINCIPAL`), and `anonymous()` (default `AnonymousCachePolicy.BYPASS`).
Carries `@Aspect(ordering = 200)`. Place it on methods of Dagger-provided classes; it cannot be
combined with `@CacheEvict` on the same method.

### @CacheEvict

`name()` (required), `key()` (default `{}`), and `clear()` (default `false`). Declare exactly one
of `clear = true` or an explicit `key` path array. Repeatable. Carries `@Aspect(ordering = 100)`
and evicts after the annotated method completes successfully.

### CacheAopModule

Public abstract Dagger `@Module`. Its `@Binds` methods contribute the `AspectProvider` bindings
for `@Cacheable`, `@CacheEvict`, and repeated `@CacheEvict` declarations that the generated proxy
resolves to build each method's interceptor. The aspect implementations are not application API.
Install the module in the application component alongside a cache provider module, which
supplies the cache runtime the aspects depend on.

## Module Dagger Bindings

| Binding | Kind | Description |
|---|---|---|
| `AspectProvider<Cacheable>` | `@Binds` | Generated-proxy interceptor for `@Cacheable` methods |
| `AspectProvider<CacheEvict>` | `@Binds` | Generated-proxy interceptor for `@CacheEvict` methods |
| `AspectProvider<CacheEvict.List>` | `@Binds` | Generated-proxy interceptor for repeated `@CacheEvict` methods |

## Verification

```text
./mvnw -ntp -pl vertique-cache/vertique-cache-aop -am verify
```
