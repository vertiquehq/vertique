<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Cache Core

> **Status:** Alpha
> **Package:** `dev.vertique.cache`
> **Artifact:** `vertique-cache-core`
> **Depends on:** `vertique-core`, `vertique-context`, `vertique-security-core`

`vertique-cache-core` is the provider-neutral foundation for programmatic method-result caching.
Injected `CacheBuilder` creates immutable `Cache<K,V>` handles; annotation support is layered in
`vertique-cache-aop` and no provider implementation is exposed.

## When To Use It

Use this artifact when an application or provider module needs the provider-neutral cache contracts. Install a concrete provider module alongside it to provide storage behavior.

## Core Concepts

The `CacheBuilder`/`Cache` application API and storage contracts are kept separate from provider
and annotation details. Local and clustered implementations depend on this module; this module
does not depend on Caffeine, Redis, AOP, or a serialization engine. Application code supplies
logical inputs and selector functions, never `CacheStore`, `ResolvedCacheKey`, or provider keys.
A selector function returns one supported scalar or an ordered `CacheKey.of(...)` component
tuple; there is no key template or format string. The runtime alone owns key format: each
component is independently type-framed and the framed components are joined with the
runtime-owned `:` separator, which component payloads percent-encode and cannot forge, so
distinct component tuples always render distinct keys.

The programmatic builder keeps its `identity(CacheIdentity)` method. The annotation API in
`vertique-cache-aop` uses `@Cacheable`'s `subject = CacheIdentity...` attribute for the same
caller-subject dimension; the two names are intentionally distinct API surfaces.

```java
Cache<ProductQuery, Product> products = cacheBuilder
        .cache("products", Product.class)
        .key(query -> CacheKey.of(query.tenantId(), query.productId()))
        .identity(CacheIdentity.EFFECTIVE_PRINCIPAL)
        .ttl(Duration.ofMinutes(5))
        .build();
```

## Configuration

The typed cache configuration uses explicit duration units such as `defaultTtlSeconds`,
`maxTtlSeconds`, and `backendTimeoutMs`. `jsonProfile` selects the existing JSON mapper
profile for cache values; a per-cache `jsonProfile` override may inherit the global
cache profile when omitted. Provider modules contribute storage bindings through the
provider SPI Dagger map keys `CacheModeKey` and `CacheProviderIdKey`. The standard
composition maps `LOCAL` to Caffeine and `CLUSTERED` to Redis. Annotation adapters in
`vertique-cache-aop` (package
`dev.vertique.cache.aop`) reach the same definition resolution through the public
framework seam `CacheAdapterSupport`; it is integration surface for adapters and
generated code, not application API. Its exact-eviction operation requires adapters
to pass the original ordered selector paths; integrations using the former
two-argument seam must be updated to supply that schema.

## Runtime behavior

The cache runtime is fail-open: disabled caches, invalid or oversized keys, backend
failures, and observer failures preserve the business invocation. A successful local
miss may populate the provider-neutral `CacheStore`; a hit skips the target after the
outer framework authorization boundary has run. Programmatic callers must invoke the handle
only after their application authorization decision. The optional `CacheObserver` set is
empty by default and receives the sealed `dev.vertique.cache.spi.event` vocabulary:
`CacheOperationCompleted` (typed `CacheOperation`/`CacheOutcome` enums plus provider,
cache name, and elapsed time), at most one `CacheLateCompletion` supplement after a
timed-out operation, and provider-maintenance `CacheCleanupCompleted` events. Observers
implement the single `onEvent(CacheEvent)` method, must remain bounded and
non-blocking, and their failures are suppressed. A standalone annotation eviction whose
name and ordered selector paths have no matching registered definition emits
`EVICT`/`UNRESOLVED_TARGET` instead of silently addressing the wrong identity bucket; once
the target is registered with an exact selector schema, the eviction resolves and reuses
that definition's identity, mode, and TTL policy. Identity-scoped cache definitions use
the standard `DefaultCacheIdentityResolver`, contributed by `CacheCoreModule`, to read
the current `SecurityContext` from the framework `ContextHolder`. `ACTOR` uses the actor,
`EFFECTIVE_PRINCIPAL` uses the subject when present and otherwise the actor, and
`ACTOR_AND_SUBJECT` preserves both dimensions. Missing context or identity fails closed for
identity-scoped caching unless `CACHE_AS_ANONYMOUS` is explicitly selected. `NONE` remains a
shared bucket and must only be used for data that is safe to share across callers. Identity
components use version-2 type framing and canonical characters. `CacheIdentityResolver` is
the provider-neutral runtime seam; the standard graph uses `DefaultCacheIdentityResolver` and
allows one explicitly supplied resolver to replace it.

## Conformance and operational limits

The provider-neutral `CacheStoreContractTest` runs the same contract against the Caffeine and
Redis providers, covering hits, misses, TTL, clear, failure handling, declared types, value
isolation, and repeatable eviction. Core operational validation rejects an oversized canonical
key before a provider operation. Resolved providers consume only `ResolvedCacheKey` and
`CacheValueDescriptor`. The public `CacheKey` is a logical ordered-component value produced by
selector functions; it is never a storage key and no provider SPI accepts it.
Core does not validate values before provider work: Caffeine and
Redis serialize values in their provider implementations and then enforce `maxValueBytes` on the
serialized bytes; those provider failures are handled by the cache core's fail-open path. An
explicit declared TTL — builder or annotation — above `maxTtlSeconds` is rejected with
`IllegalArgumentException`; it is not silently clamped. `ttlSeconds = 0` retains provider size
protection while disabling time expiration.

## Verification

Run the cache package proof with:

```text
./mvnw -ntp -pl vertique-cache/vertique-cache-core,vertique-codegen/vertique-codegen-cache,vertique-cache/vertique-cache-caffeine,vertique-cache/vertique-cache-redis,vertique-micrometer/vertique-micrometer-cache,vertique-opentelemetry/vertique-opentelemetry-cache -am verify
```

The clean reactor verification additionally checks dependency and BOM parity, forbidden provider
dependencies, packaged module-documentation parity, and regeneration of cache composition and AOP
output:

```text
./mvnw -ntp clean verify
```

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-core` | Framework foundations and shared configuration/runtime contracts |
| `vertique-context` | Vert.x context propagation and `ContextHolder` runtime binding |
| `vertique-security-core` | Provider-neutral `SecurityContext` and caller identity contracts |
