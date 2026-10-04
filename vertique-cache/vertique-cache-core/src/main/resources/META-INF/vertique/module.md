<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Cache Core

> **Status:** Stable
> **Package:** `dev.vertique.cache`
> **Artifact:** `vertique-cache-core`
> **Depends on:** `vertique-core`, `vertique-context`, `vertique-security-core`

`vertique-cache-core` is the provider-neutral foundation for programmatic method-result caching.
Injected `CacheBuilder` creates immutable `Cache<K,V>` handles; annotation support is layered in
`vertique-cache-aop` and no provider implementation is exposed.

**Maturity split.** This module and `vertique-cache-aop` (annotations) are Stable.
`vertique-cache-caffeine` (local provider) and `vertique-cache-redis` (clustered provider)
remain Alpha: the programmatic API, configuration, and provider SPI documented here are frozen
under the evolution rules of a Stable module, while those providers may still change without
notice.

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

## Key Classes

### CacheBuilder

Injected factory for immutable cache handles. `cache(String name, Class<V>)` and
`cache(String name, TypeRef<V>)` start a definition; `mode`, `ttl`, `identity`, `anonymous`,
and `key` refine it, and `build()` registers the logical name and returns a `Cache`. Names
are 1-128 characters from `[A-Za-z0-9._~-]`, at most 1,024 logical names may be registered, and
re-declaring a name with a different type, mode, TTL, identity, anonymous policy, or JSON profile
fails with `IllegalStateException`. Value types must be concrete: primitives, raw generics, type
variables, wildcards, `Object`, `Future`, and `CompletionStage` are rejected with
`IllegalArgumentException`; use `TypeRef` for parameterized types.

### Cache\<K,V\>

`get(K input, Function<? super K, Future<V>> loader)` is cache-aside: a hit returns the stored
value, a miss runs the loader and stores a non-null result. `invalidate(K)` evicts the entry for
the current identity bucket and `invalidateAll()` clears the logical region; both complete with
`false` instead of failing when caching is disabled, bypassed, or the backend fails. Loader
failures propagate to the caller untouched.

### CacheKey

`CacheKey.of(Object first, Object... rest)` produces an ordered, non-null component tuple from a
selector function. Components are type-framed by the runtime; a `CacheKey` is never a storage key.

### CacheMode, CacheIdentity, AnonymousCachePolicy

`CacheMode` is `DEFAULT`, `LOCAL`, or `CLUSTERED`; `DEFAULT` defers to `cache.defaultMode`.
`CacheIdentity` is `NONE`, `ACTOR`, `EFFECTIVE_PRINCIPAL` (the default), or `ACTOR_AND_SUBJECT`.
`AnonymousCachePolicy` is `BYPASS` (the default) or `CACHE_AS_ANONYMOUS`.

### CacheConfig / CacheEntryConfig

Validated records bound from the `cache` configuration section; see Configuration.

### CacheAdapterSupport

Framework integration seam used by `vertique-cache-aop` and generated code. It is not
application API; see Core Concepts.

## Extension Points

| Extension point | Purpose |
|---|---|
| `CacheStore` | Provider storage contract: `get`, `put`, `evict`, `clear`. Receives only `ResolvedCacheKey` and `CacheValueDescriptor`, never a `CacheKey` or application key. |
| `ResolvedCacheKey`, `CacheValueDescriptor`, `CacheRegion` | Runtime-resolved key (region, identity component, selector), exact value type plus JSON profile, and logical region identity handed to a provider. |
| `CacheModeKey`, `CacheProviderIdKey` | Dagger map keys a provider module uses to contribute its `CacheStore` and its bounded provider id (`[a-z][a-z0-9-]{0,31}`, not `none`) for one `LOCAL` or `CLUSTERED` mode. Both must be present for a mode; `DEFAULT` cannot be bound. |
| `CacheIdentityResolver` | Optional application override of the standard identity source; see Runtime behavior. |
| `CacheObserver` | Optional, repeatable observation seam over the sealed `CacheEvent` vocabulary. |

```java
public interface CacheIdentityResolver {
    Optional<SecurityIdentity> current();
}
```

## Configuration

All keys live under the root `cache` object and are bound to the typed `CacheConfig` record.
Durations carry an explicit unit suffix (`Seconds`, `Ms`).

```json
{
  "cache": {
    "enabled": true,
    "defaultMode": "LOCAL",
    "defaultTtlSeconds": 60,
    "maxTtlSeconds": 86400,
    "jsonProfile": "system",
    "maxKeyBytes": 1024,
    "maxValueBytes": 1048576,
    "maximumEntries": 10000,
    "backendTimeoutMs": 100,
    "caches": {
      "products": { "mode": "LOCAL", "ttlSeconds": 300, "jsonProfile": "system" }
    }
  }
}
```

An omitted or empty `cache` object yields the defaults below. A non-empty `cache` object is
bound as a whole record and omitted keys are **not** defaulted: supply every global key and the
`caches` object (use `{}` for no overrides), otherwise binding fails with a
`ConfigurationException`.

### Global keys

| Key | Type | Default | Range / rule | Description |
|---|---|---|---|---|
| `cache.enabled` | boolean | `true` | | Kill switch. When `false`, every handle runs its loader directly without touching a provider, and no provider needs to be installed. |
| `cache.defaultMode` | `LOCAL` \| `CLUSTERED` | `LOCAL` | required; `DEFAULT` is not a usable value | Mode applied when neither the definition nor `cache.caches.<name>.mode` selects one. |
| `cache.defaultTtlSeconds` | long (s) | `60` | `>= 0`, and `<= maxTtlSeconds` | TTL for a definition that declares none. `0` disables time expiration. |
| `cache.maxTtlSeconds` | long (s) | `86400` | `> 0`, and `>= defaultTtlSeconds` | Ceiling for every declared or configured TTL. A TTL above it is rejected, never clamped. |
| `cache.jsonProfile` | string | `system` | not blank | JSON profile used to serialize cached values. **Independent of `json.jsonProfile`**: the cache never follows the edge JSON profile, so changing the edge default cannot make durable entries unreadable. |
| `cache.maxKeyBytes` | int | `1024` | `> 0` | Maximum UTF-8 size of the complete canonical key; a larger key bypasses the provider fail-open. |
| `cache.maxValueBytes` | int | `1048576` | `> 0` | Maximum serialized value size, enforced by providers. |
| `cache.maximumEntries` | int | `10000` | `> 0` | Capacity bound for the local provider. |
| `cache.backendTimeoutMs` | long (ms) | `100` | `1..10000` | Deadline for each provider operation; an expired deadline is a fail-open `TIMEOUT`. |
| `cache.caches.<name>` | object | none | name not blank | Per-cache override; the key is the logical cache name. |

### Per-cache keys

| Key | Type | Default | Range / rule | Description |
|---|---|---|---|---|
| `cache.caches.<name>.mode` | `LOCAL` \| `CLUSTERED` \| `DEFAULT` | required | | Overrides the definition's mode unless `DEFAULT`, which defers to the definition and then `cache.defaultMode`. |
| `cache.caches.<name>.ttlSeconds` | long (s) | `0` when omitted | `-1`, `0`, or `1..maxTtlSeconds` | `-1` inherits the definition's TTL (then `defaultTtlSeconds`); `0` disables time expiration; a positive value overrides the definition. An omitted value binds as `0`, not as inherit. A value above `cache.maxTtlSeconds` fails configuration. |
| `cache.caches.<name>.jsonProfile` | string | inherits `cache.jsonProfile` | not blank when present | Per-cache JSON profile. |

## Provider composition

Provider modules contribute storage bindings through the provider SPI Dagger map keys
`CacheModeKey` and `CacheProviderIdKey`. The standard composition maps `LOCAL` to Caffeine and
`CLUSTERED` to Redis. Annotation adapters in `vertique-cache-aop` (package
`dev.vertique.cache.aop`) reach the same definition resolution through the public
framework seam `CacheAdapterSupport`; it is integration surface for adapters and
generated code, not application API. Its exact-eviction operation requires adapters
to pass the original ordered selector paths; integrations using the former
two-argument seam must be updated to supply that schema.

## Module Dagger Bindings

`CacheCoreModule` includes `ContextRuntimeModule`. It expects the application graph to supply
`@VertxConfig JsonObject` and `ConfigParser`.

| Binding | Kind | Description |
|---|---|---|
| `Map<CacheMode, CacheStore>` | `@Multibinds` | Provider stores, contributed with `@CacheModeKey` by provider modules |
| `Map<CacheMode, String>` | `@Multibinds` | Provider ids, contributed with `@CacheProviderIdKey`; every mode needs both entries or creating the builder fails with `IllegalStateException` |
| `Set<CacheObserver>` | `@Multibinds` | Observer contributions from telemetry adapters or application code; empty by default |
| `CacheIdentityResolver` | `@BindsOptionalOf` | See Extension Points; at most one application binding replaces the standard resolver |
| `CacheConfig` | `@Provides @Singleton` | Bound from the `cache` configuration section, or `CacheConfig.defaults()` when absent or empty |
| `CacheBuilder` | `@Provides @Singleton` | The application entry point |

`CacheAdapterSupport` is constructor-injected over the singleton `CacheBuilder`.

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
the standard identity resolver, contributed by `CacheCoreModule`, to read
the current `SecurityContext` from the framework `ContextHolder`. `ACTOR` uses the actor,
`EFFECTIVE_PRINCIPAL` uses the subject when present and otherwise the actor, and
`ACTOR_AND_SUBJECT` preserves both dimensions. Missing context or identity fails closed for
identity-scoped caching unless `CACHE_AS_ANONYMOUS` is explicitly selected. `NONE` remains a
shared bucket and must only be used for data that is safe to share across callers. Identity
components use version-2 type framing and canonical characters. `CacheIdentityResolver` is
the provider-neutral runtime seam; the standard graph uses the standard resolver and
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
