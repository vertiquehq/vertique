# Developing Vertique Cache Core

> **Audience:** Vertique framework contributors and source agents
> **Public contract:** `vertique-cache-core/src/main/resources/META-INF/vertique/module.md`

This module owns the provider-neutral cache package boundary. Provider implementations must depend inward on this module and must not introduce provider types into its public contracts.

`CacheBuilder` and immutable `Cache<K,V>` are the programmatic application API. Builder
definitions resolve policy once, while identity is read from the current security context for
every identity-scoped operation. Annotation callers are implemented by the separate
`vertique-cache-aop` adapter module.

## Source Map

- `dev.vertique.cache` — cache core package root.

## Runtime or Build Flow

The module is selected before provider modules in the reactor and supplies the neutral dependency
target for storage providers and API adapters. Its Dagger map seam selects a `CacheStore` by
effective `CacheMode`; the standard provider composition maps `LOCAL` to Caffeine and `CLUSTERED`
to Redis, while observations receive the selected provider identity.

The cache AOP adapter remains inside the framework authorization boundary. Authorization must
run before a lookup on both hits and misses; a hit may skip the target method only after
that outer boundary has completed. For identity-scoped definitions, the standard
`DefaultCacheIdentityResolver` reads the current `SecurityContext` from `ContextHolder`
and derives the canonical caller component from its actor and subject fields. Missing or
unavailable identity bypasses the cache unless the annotation explicitly opts into the anonymous
bucket. Cache core does not authorize the caller; it only prevents cache-key reuse across the
resolved identity buckets. `CacheObserver` is the neutral observation seam for operation
observations and for bounded cleanup outcomes through its default `onCleanup` method;
cache-core does not depend on Micrometer or any other telemetry implementation.

## Load-Bearing Invariants

- The core module must not depend on Caffeine, Redis, or provider serialization libraries.
- Cleanup observation remains a provider-neutral `CacheCleanupObservation` record; telemetry
  adapters consume it through `CacheObserver` rather than adding provider or Micrometer types.
- Aggregator POMs remain non-consumable and are not added to the BOM.
- Provider selection stays behind the cache-core resolver; AOP adapters do not know provider implementation classes.
- Providers consume `ResolvedCacheKey` and `CacheValueDescriptor`; application code cannot supply
  a resolved identity-bearing key through the builder API.

## Testing

T013 owns the shared conformance and operational-limit proof. The provider-neutral
`CacheStoreContractTest` is defined in cache-core and inherited by the Caffeine and Redis
provider test edges; T005 and T009 retain ownership of their provider-specific proofs.

```text
./mvnw -ntp -pl vertique-cache/vertique-cache-core -am test
```

## Related ADRs

- D018: Public cache store SPI boundary — provider-neutral cache contracts remain separate from storage implementations, including observation adapters.
