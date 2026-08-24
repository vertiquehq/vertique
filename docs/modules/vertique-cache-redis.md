# Developing Vertique Cache Redis

> **Audience:** Vertique framework contributors and source agents
> **Public contract:** `vertique-cache-redis/src/main/resources/META-INF/vertique/module.md`

This module owns asynchronous Redis cache commands, canonical physical-key rendering,
generation visibility, JSON value conversion, and operation-level deadline behavior.
Connection profile validation, client reuse, and client shutdown remain in
`vertique-redis-core`.

## Source Map

- `dev.vertique.cache.redis` — Redis provider package root.
- `CacheRedisModule` — includes `CacheCoreModule`, `JsonRuntimeModule` from
  `vertique-json`, and `RedisConnectionModule`, so it supplies the
  `JsonMapperProfileRegistry` wiring; it parses `cache.redis` and provides the singleton
  provider-neutral `CacheStore`.
- `CacheRedisConfig` — validates the Redis connection name, physical key namespace,
  and positive format version.
- `RedisCacheStore` — implements asynchronous `get`, `put`, `evict`, and `clear`.
- `RedisCacheKey` — renders and bounds generation and entry keys; package-private.
- `RedisCommandClient` — isolates the provider from the Vert.x Redis command API;
  package-private.

## Runtime or Build Flow

The Dagger module reads `cache.redis` and obtains the named client from the shared
`RedisClientRegistry`. `RedisCacheStore` composes the generation lookup and the entry
operation into one asynchronous future, then applies the configured backend deadline.
No provider operation blocks the event loop.

For a region with canonical prefix `<region namespace>:v<region format version>:<region name>`,
the generation key is
`<redis namespace>:v<Redis keyspace format version>:<region namespace>:v<region format version>:<region name>:generation`.
An entry key adds `:g<opaque generation>:<identity component>:<selector>`. The Redis
keyspace version is `CacheRedisConfig.formatVersion`; the region components remain the
`CacheRegion.canonicalPrefix()` grammar. The first lookup or write uses
`SET ... NX` to initialize a missing generation and then reads the stored token.
`clear(CacheRegion)` replaces that token with a new UUID. The replacement is the
logical linearization point; no request-path key enumeration is performed.

The `vertique-json` dependency and the `JsonRuntimeModule` included by `CacheRedisModule`
provide the `JsonMapperProfileRegistry` used below. The selected mapper comes from the
per-region `CacheEntryConfig.jsonProfile` when
present, otherwise `CacheConfig.jsonProfile`, and is resolved through
`JsonMapperProfileRegistry`. Writes serialize with that mapper and enforce
`maxValueBytes`; reads construct the Jackson target from the declared `Type`. A null,
missing, or undecodable value is returned as `Optional.empty()`; write-side encoding or
size failures produce failed futures for the cache core to handle fail-open.

`Duration.ZERO` produces a Redis `SET` without `PX`, while a positive duration produces
`SET ... PX <milliseconds>`. The provider's deadline covers generation initialization,
client-pool wait, and the Redis command. `RedisDeadline` settles the returned future at
the deadline and ignores late completion on the captured event-loop context; it does
not claim upstream cancellation. Cache failures therefore do not replace the
authoritative business result.

## Load-Bearing Invariants

- Redis connection profile parsing and client lifecycle remain in `vertique-redis-core`.
- The provider must not make `vertique-cache-core` depend on Redis.
- Rendered Redis keys are bounded by `CacheConfig.maxKeyBytes` in UTF-8 before command
  submission; the format version and opaque generation are part of the physical key.
- Whole-region clear replaces the generation marker but does not delete old physical
  entries. An in-flight lookup may complete from the old generation, while a lookup
  that begins after replacement uses the new generation. This is intentionally weak
  invalidation and does not provide per-key fencing or cancellation.
- T010 owns bounded background cleanup of unreachable old generations; cleanup is not
  part of this provider's request-path operations or business future.
- T011 owns Dagger/provider-selection, telemetry, and application client/provider
  lifecycle composition; this module owns the provider command behavior consumed by
  that graph.

## Testing

T009's provider proof covers key rendering, selected-profile and declared-type JSON
conversion, codec fail-open behavior, finite and zero TTLs, exact-key eviction,
generation replacement/recreation, weak invalidation, timeout/business-result
isolation, and event-loop responsiveness. Unit tests are in
`vertique-cache-redis/src/test/java/dev/vertique/cache/redis`; the Redis contract
integration test uses Testcontainers with the pinned image
`redis:7.2.4-alpine@sha256:c8bb255c3559b3e458766db810aa7b3c7af1235b204cfdb304e79ff388fe1a5a`.
The module activates the parent-managed Maven Failsafe plugin, so `RedisCacheStoreContractIT`
and `RedisEventLoopIT` run with the focused verification command:

```text
./mvnw -ntp -pl vertique-cache/vertique-cache-redis -am verify
```

T010 owns the separate physical old-generation cleanup proof. T011 owns the assembled
application-graph, provider-selection, telemetry, and shutdown-order proof that
consumes this module.

## Related ADRs

- D009: Shared Redis connection profiles.
- D011: Repeatable exact-key and whole-cache eviction.
- D016: Explicit zero and finite TTL semantics.
- D018: Provider-neutral public `CacheStore` boundary.
- D020: Opaque generation tokens and weak invalidation.
- D021: Generated module composition of the cache runtime.
- D022: Shared Redis client Dagger ownership.
- D023: Explicit public cache key and region records.
