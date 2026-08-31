<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Cache AOP

> **Status:** Alpha
> **Package:** `dev.vertique.cache.aop`
> **Artifact:** `vertique-cache-aop`
> **Depends on:** `vertique-cache-core`, `vertique-aop`, `vertique-core`

`vertique-cache-aop` adapts `@Cacheable` and `@CacheEvict` method declarations to the
provider-neutral programmatic cache API. It owns annotation vocabulary, method metadata
adaptation, ordered selector-path resolution for annotated methods, and the Dagger
bindings for the cache aspects.

Keys are declared as ordered selector paths, never as a template or format string:
`@Cacheable(name = "users", key = {"tenantId", "productId"})`. Each path names a
parameter (by name or position) plus optional record/bean accessor segments; path order
is component order, and the runtime alone composes and frames the canonical key. An
explicitly empty `key = {}` declares a value-independent constant operation key.
`@CacheEvict` declares exactly one of `clear = true` or an explicit `key` path array
(the explicit empty array evicts the constant entry); code generation rejects a
declaration with neither or both.

An exact `@CacheEvict` resolves its target policy rather than guessing it: a
co-located `@Cacheable` on the same method supplies the policy immediately, and a
standalone eviction resolves lazily — at first invocation — from the runtime catalog,
restricted to annotation-declared definitions, reusing the registered target's
identity, mode, and TTL so it can never address a different identity bucket than its
target. An unknown or programmatic-only target is a typed
`EVICT`/`UNRESOLVED_TARGET` observed no-op: no provider is called and the eviction
settles `false`. Programmatic caches are invalidated through their own `Cache`
handles, never by annotations.

The generic Vertique AOP processor generates the application proxy, reflection-free
`MethodMetadata`, annotation literals, and interceptor chain. This module does not own
proxy generation or cache storage behavior.

## Composition

Include `CacheAopModule` alongside a cache provider module in an application component.
Provider modules include only the provider-neutral `CacheCoreModule` and never this
module; an application that uses cache annotations installs `CacheAopModule`
explicitly, and a programmatic-only application omits it entirely. The adapters reach
definition resolution through the core's public `CacheAdapterSupport` seam.

Programmatic callers should depend only on `vertique-cache-core` and use `CacheBuilder`
and `Cache<K,V>` directly.

## Verification

```text
./mvnw -ntp -pl vertique-cache/vertique-cache-aop -am verify
```
