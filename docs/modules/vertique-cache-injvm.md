# Developing Vertique Cache In-JVM

> **Audience:** Vertique framework contributors and source agents
> **Public contract:** `vertique-cache-injvm/src/main/resources/META-INF/vertique/module.md`

This module is the ownership boundary for the local cache provider. Its implementation may choose local storage details, but its integration surface remains the provider-neutral cache core.

## Source Map

- `dev.vertique.cache.injvm` — local provider package root.

## Runtime or Build Flow

The provider is selected by the cache-core `CacheMode` map and depends on the cache core contracts plus the named JSON profile registry. Generated applications receive the `LOCAL` contribution through `GeneratedCacheModule`; explicit Dagger composition is also supported. `CaffeineCacheStore` keeps one bounded Caffeine cache per `CacheRegion`, stores JSON bytes, and reconstructs values with the declared result `Type`. Finite TTL checks use a monotonic clock; TTL `0` disables time expiration while Caffeine's per-region maximum-entry bound remains active. Codec and size failures are returned as failed provider futures for the runtime's fail-open handling.

## Load-Bearing Invariants

- Local storage must not leak into the provider-neutral cache API.
- Caffeine and JSON mapper types remain confined to this provider module; `CacheStore` stays object-facing.
- The region registry is application-scoped and `maximumEntries` applies independently to each logical region.
- Null values and disabled-cache writes are no-ops; callers never receive the stored byte array or a shared mutable decoded instance.
- Provider modules are consumable JARs and belong in coverage; the family aggregator does not.

## Testing

T013 owns the shared provider-neutral `CacheStoreContractTest` defined in cache-core; the local
provider test edge inherits it. T005 owns the local provider proof: deterministic TTL, per-region
bounds, JSON defensive copies, logical-region clearing, disabled behavior, and null-result bypass.
T011 owns application-level provider composition and observation wiring.

```text
./mvnw -ntp -pl vertique-cache/vertique-cache-injvm -am test
```
