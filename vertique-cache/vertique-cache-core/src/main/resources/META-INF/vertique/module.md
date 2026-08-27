<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Cache Core

> **Status:** Alpha
> **Package:** `dev.vertique.cache`
> **Artifact:** `vertique-cache-core`
> **Depends on:** `vertique-aop`, `vertique-core`, `vertique-security-core`

`vertique-cache-core` is the provider-neutral foundation for annotation-driven method-result caching. This artifact establishes the cache API boundary independently of local and Redis storage implementations.

## When To Use It

Use this artifact when an application or provider module needs the provider-neutral cache contracts. Install a concrete provider module alongside it to provide storage behavior.

## Core Concepts

Cache annotations and storage contracts are kept separate from provider details. Local and clustered implementations depend on this module; this module does not depend on Caffeine, Redis, or a serialization engine.

## Configuration

The typed cache configuration uses explicit duration units such as `defaultTtlSeconds`,
`maxTtlSeconds`, and `backendTimeoutMs`. `jsonProfile` selects the existing JSON mapper
profile for cache values; a per-cache `jsonProfile` override may inherit the global
cache profile when omitted. Provider modules contribute storage bindings through the
internal `CacheMode` Dagger map seam. The standard composition maps `LOCAL` to Caffeine and
`CLUSTERED` to Redis; the aspects resolve the provider for the effective mode and record that
provider in cache observations.

## Runtime behavior

The cache aspects are fail-open: disabled caches, invalid or oversized keys, backend
failures, and observer failures preserve the business invocation. A successful local
miss may populate the provider-neutral `CacheStore`; a hit skips the target after the
outer framework authorization boundary has run. The optional `CacheObserver` set is
empty by default and receives redacted operation, provider, cache, outcome, and duration
data without becoming a cache or telemetry dependency. The same observer seam exposes
redacted cleanup outcomes through `CacheObserver.onCleanup(CacheCleanupObservation)`; the
default method keeps operation-only observers source-compatible. Identity-scoped annotations use
the standard `DefaultCacheIdentityResolver`, contributed by `CacheCoreModule`, to read
the current `SecurityContext` from the framework `ContextHolder`. `ACTOR` uses the actor,
`EFFECTIVE_PRINCIPAL` uses the subject when present and otherwise the actor, and
`ACTOR_AND_SUBJECT` preserves both dimensions. Missing context or identity fails closed for
identity-scoped caching unless `CACHE_AS_ANONYMOUS` is explicitly selected. `NONE` remains a
shared bucket and must only be used for data that is safe to share across callers. Identity
components use the same canonical characters accepted by `CacheKey`. `CacheIdentityResolver` is
the provider-neutral runtime seam; the standard graph uses `DefaultCacheIdentityResolver` and
allows one explicitly supplied resolver to replace it.

## Conformance and operational limits

The provider-neutral `CacheStoreContractTest` runs the same contract against the Caffeine and
Redis providers, covering hits, misses, TTL, clear, failure handling, declared types, value
isolation, and repeatable eviction. Core operational validation rejects an oversized canonical
key before a provider operation. Core does not validate values before provider work: Caffeine and
Redis serialize values in their provider implementations and then enforce `maxValueBytes` on the
serialized bytes; those provider failures are handled by the cache core's fail-open path. An
explicit annotation TTL above `maxTtlSeconds` is rejected with
`IllegalArgumentException`; it is not silently clamped. `ttlSeconds = 0` retains provider size
protection while disabling time expiration.

## Verification

Run the cache package proof with:

```text
./mvnw -ntp -pl vertique-cache/vertique-cache-core,vertique-codegen/vertique-codegen-cache,vertique-cache/vertique-cache-caffeine,vertique-cache/vertique-cache-redis,vertique-micrometer/vertique-micrometer-cache,vertique-cache/vertique-cache-opentelemetry -am verify
```

The clean reactor verification additionally checks dependency and BOM parity, forbidden provider
dependencies, packaged module-documentation parity, and regeneration of cache and AOP metadata:

```text
./mvnw -ntp clean verify
```

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-aop` | Method interception runtime boundary |
| `vertique-core` | Framework foundations and shared configuration/runtime contracts |
| `vertique-context` | Vert.x context propagation and `ContextHolder` runtime binding |
| `vertique-security-core` | Provider-neutral `SecurityContext` and caller identity contracts |
