# Developing Vertique Cache Redis

> **Audience:** Vertique framework contributors and source agents
> **Public contract:** `vertique-cache-redis/src/main/resources/META-INF/vertique/module.md`

This module owns asynchronous Redis cache commands, canonical physical-key rendering,
generation visibility, JSON value conversion, operation-level deadline behavior, and the
bounded physical cleanup policy for unreachable old generations.
Connection profile validation, client reuse, and client shutdown remain in
`vertique-redis-core`.

## Source Map

- `dev.vertique.cache.redis` — Redis provider package root.
- `CacheRedisModule` — includes `CacheCoreModule`, `JsonRuntimeModule` from
  `vertique-json`, and `RedisConnectionModule`, so it supplies the
  `JsonMapperProfileRegistry` wiring; it parses `cache.redis` and provides the singleton
  `CLUSTERED` provider contribution and provider identity to the cache-core mode map.
- `CacheRedisConfig` — validates the Redis connection name, physical key namespace,
  and positive format version.
- `RedisCacheStore` — implements asynchronous `get`, `put`, `evict`, and `clear`.
- `RedisCacheKey` — renders and bounds generation and entry keys; package-private.
- `RedisCommandClient` — isolates the provider from the Vert.x Redis command API;
  package-private.
- `RedisCleanupJob` — defines bounded topology-aware old-generation cleanup and its cron policy;
  package-private.
- `RedisCleanupLifecycle` — orders cleanup deregistration before shared Redis client close;
  package-private.

## Runtime or Build Flow

The Dagger module reads `cache.redis` and obtains the named client from the shared
`RedisClientRegistry`. `RedisCacheStore` composes the generation lookup and the entry
operation into one asynchronous future, then applies the configured backend deadline.
No provider operation blocks the event loop. Ordinary request-path commands use the Vert.x Redis
client; the topology-maintenance seam and its internal Lettuce client are not coupled to a cache
business future or readiness future.

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

`RedisCleanupJob` is the separate physical-maintenance path for old generations. It registers the
stable cron id `cache-redis-old-generation-cleanup` with `0 */15 * * * *` (every 15 minutes, UTC),
`ExecutionMode.EVERY_INSTANCE`, `OverlapPolicy.SKIP`, `MisfirePolicy.SKIP`, and `tracked=false`.
Registration is idempotent. The first run includes a per-instance jitter in `[0, 60 seconds)`.

The job discovers every Redis primary through `RedisTopologyOperations`, scans with each
node-local cursor, and stops a sweep at 10,000 inspected keys or five seconds of monotonic time.
It deduplicates keys repeated across scan pages and primaries, protects generation markers, and
issues asynchronous `UNLINK` for only those provider-shaped entry keys whose readable marker has a
different generation. Marker-read or unlink failures produce a failed/backlogged maintenance
outcome without deleting unverified keys. Consecutive failures use capped exponential backoff of
15 minutes, 30 minutes, then at most one hour. Per-sweep metrics are bounded to profile,
namespace, scanned, deleted, backlog, and failure values; metrics recording cannot fail the sweep.

`RedisCleanupLifecycle` runs in `LifecyclePhase.INFRA` at
`RedisClientShutdownStep.SHUTDOWN_PRIORITY + 1`; reverse teardown therefore unregisters cleanup
dispatch before the shared Redis registry closes. When a `CronScheduler` is installed,
`CacheRedisModule` registers the cleanup job, its event-bus dispatch handler, and its shutdown step;
the handler reports the bounded sweep result through the cron reply address. Cleanup policy and
metrics remain owned by T010. Shutdown is best-effort and idempotent: repeated lifecycle
callbacks do not re-register cleanup or close the shared Redis clients more than once.

## Load-Bearing Invariants

- Redis connection profile parsing and client lifecycle remain in `vertique-redis-core`.
- The provider must not make `vertique-cache-core` depend on Redis or `vertique-cache-aop`.
- Rendered Redis keys are bounded by `CacheConfig.maxKeyBytes` in UTF-8 before command
  submission; the format version and opaque generation are part of the physical key.
- Whole-region clear replaces the generation marker but does not delete old physical
  entries. An in-flight lookup may complete from the old generation, while a lookup
  that begins after replacement uses the new generation. This is intentionally weak
  invalidation and does not provide per-key fencing or cancellation.
- T010 owns bounded background cleanup of unreachable old generations; cleanup is not
  part of this provider's request-path operations or business future.
- `RedisCleanupJob` uses the minimal topology seam from `vertique-redis-core`; it does not expose
  Lettuce or topology details through the provider-neutral cache contracts.
- Cache cleanup observation uses `CacheObserver` and `CacheCleanupObservation`; Redis remains
  Micrometer-free and does not select or install a metrics adapter.
- T011 owns Dagger/provider-selection, telemetry, cron dispatch, and application client/provider
  lifecycle composition. `CacheRedisModule` contributes that composition when the application
  graph provides `CronScheduler`.

## Testing

T013 owns the shared provider-neutral `CacheStoreContractTest` defined in cache-core; the Redis
provider test edge inherits it. T009's provider proof covers key rendering, selected-profile and
declared-type JSON conversion, codec fail-open behavior, finite and zero TTLs, exact-key eviction,
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

T010's focused cleanup proof is in `RedisCleanupJobTest` (eligibility, topology traversal,
deduplication, 10,000-key and five-second bounds, jitter, idempotence, retry/backoff, metrics,
and request-future isolation), `RedisCleanupCronWiringTest` (stable registration policy), and
`RedisCleanupLifecycleTest` (unregister-before-close ordering). `RedisCleanupIT` verifies the
real Redis `SCAN`/`UNLINK` path with Testcontainers. Run the focused unit proof with:

```text
./mvnw -ntp -pl vertique-cache/vertique-cache-redis -am test -Dtest=RedisCleanupJobTest,RedisCleanupCronWiringTest,RedisCleanupLifecycleTest
```

Run the module's `verify` command above when the Testcontainers integration proof is available.
T011's `CacheDaggerGraphIT` additionally proves the assembled Redis provider and cleanup shutdown
contribution without requiring a live Redis server.

## Related ADRs

- D009: Shared Redis connection profiles.
- D011: Repeatable exact-key and whole-cache eviction.
- D016: Explicit zero and finite TTL semantics.
- D018: Provider-neutral public `CacheStore` boundary.
- D020: Opaque generation tokens and weak invalidation.
- D021: Generated module composition of the cache runtime.
- D022: Shared Redis client Dagger ownership.
- D023: Explicit public cache key and region records.
