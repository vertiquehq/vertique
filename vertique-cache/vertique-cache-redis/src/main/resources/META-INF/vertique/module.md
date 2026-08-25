<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Cache Redis

> **Status:** Alpha
> **Package:** `dev.vertique.cache.redis`
> **Artifact:** `vertique-cache-redis`
> **Depends on:** `vertique-cache-core`, `vertique-json`, `vertique-redis-core`

`vertique-cache-redis` is the asynchronous clustered provider for the provider-neutral
cache contracts. It stores JSON values in Redis through the shared Redis client
infrastructure and returns Vert.x futures for every cache operation. It also contains the
bounded physical cleanup policy for unreachable old generations. It does not own named Redis
profile parsing or shared-client lifecycle.

## When To Use It

Use this provider when cache state must be shared across application instances. Install
`vertique-cache-redis` explicitly in the application composition and configure the
connection profile through `vertique-redis-core`; do not duplicate connection settings
in cache annotations.

The provider-specific `cache.redis` section contains the selected profile, a physical
key namespace, and a positive provider format-version field:

```json
{
  "cache": {
    "redis": {
      "connection": "primary",
      "namespace": "shared",
      "formatVersion": 1
    }
  }
}
```

## Core Concepts

Cache-specific storage behavior belongs here, while named Redis profiles and client
lifecycle belong to the shared Redis module. `CacheRedisModule` contributes the `CLUSTERED`
`CacheStore` binding and provider identity to the cache-core mode map, and includes the cache
core, JSON runtime, and Redis connection modules. Its included `JsonRuntimeModule` supplies the
`JsonMapperProfileRegistry` wiring used for cache value conversion.

Redis operations are asynchronous and do not block the Vert.x event loop. The provider
uses a generation marker for each logical region. A physical entry key has the form
`<redis namespace>:v<Redis keyspace format version>:<region namespace>:v<region format version>:<region name>:g<generation>:<identity>:<selector>`;
the generation marker is the same prefix followed by `:generation`. The Redis keyspace
version is `CacheRedisConfig.formatVersion`; the remaining region components are the
`CacheRegion.canonicalPrefix()` grammar. The first operation
initializes a missing marker with an opaque token. Whole-region clear replaces the
marker with a new opaque token, so lookups that start afterward use a new logical
keyspace without scanning Redis in the request path.

Values are serialized with the per-cache `jsonProfile` when one is configured, or the
global cache JSON profile otherwise. Reads deserialize with the declared result
`Type`, which preserves generic value shapes. A missing value, JSON `null`, or a JSON
codec failure is a cache miss. Write-side serialization and size failures are reported
by the provider and remain subject to the cache core's fail-open behavior.

The `CacheStore` duration passed to a write is interpreted explicitly: `Duration.ZERO`
writes with `SET` and no expiration; a positive duration writes with a millisecond
`PX` expiration. A negative duration is ignored as a successful no-op. Finite old
generations therefore expire normally. Clear is logically immediate but physically
weak: an in-flight lookup may still return a value from the old generation, and old
physical keys are not deleted by the clear operation.

Every composed Redis operation is fenced by `cache.backendTimeoutMs`, including the
generation and client-pool wait portion. On timeout or Redis failure, the cache core
preserves the business result. The backend future is not assumed to be cancellable;
a late Redis completion is ignored by the returned operation future, although the
backend side effect may still complete.

## Physical old-generation cleanup

Whole-region clear leaves old physical generations for background maintenance. `RedisCleanupJob`
uses the `vertique-redis-core` topology seam and an ordinary Vert.x command client for marker
reads; it is not part of a cache request, readiness future, or business future. The normal cache
request path therefore remains on Vert.x Redis operations, while the internal Lettuce topology
client is reserved for maintenance.

The cleanup definition has the stable id `cache-redis-old-generation-cleanup` and the six-field
cron expression `0 */15 * * * *` (every 15 minutes, UTC). It runs with `EVERY_INSTANCE`, skips
overlap, skips misfires, and is untracked (`tracked=false`). Each instance selects a first-run
jitter in the bounded range `[0, 60 seconds)`. Registration is idempotent, so repeated
registration leaves one job with the stable id.

Each sweep is bounded to at most 10,000 inspected keys or five seconds of monotonic elapsed time.
It scans every discovered Redis primary with node-local cursors, deduplicates keys repeated across
pages or primaries, and uses `UNLINK` rather than `DEL`. A key is eligible only when it matches
this provider's namespace, keyspace version, region grammar, and generation-key shape, its
generation marker can be read, and the marker generation differs from the entry generation. The
generation marker itself is never a candidate; unreadable markers are protected and cause a
failed/backlogged outcome instead of deletion.

Failures are retained for the next run and use capped exponential retry backoff: 15 minutes,
30 minutes, then up to a one-hour ceiling. Each sweep sends a bounded `CacheCleanupObservation`
through the provider-neutral `CacheObserver.onCleanup` seam. The observation contains the
connection profile, namespace, success/error outcome, scanned count, deleted count, backlog
indicator, and failure flag. An observer failure does not fail the maintenance operation. A
metrics adapter may translate this observation into backend-specific counters without adding a
Micrometer dependency to this module.

The `RedisCleanupLifecycle` step runs in `INFRA` at one priority above
`RedisClientShutdownStep`. During reverse teardown it unregisters cleanup dispatch first, then
closes the shared Redis clients. When an application provides `CronScheduler`, this module also
registers the bounded cleanup job and its event-bus dispatch handler. Cleanup policy and metrics
remain owned by T010; this module only composes their lifecycle with the shared client.

## Conformance and verification

The Redis edge runs the same provider-neutral `CacheStoreContractTest` as the in-process provider,
including hit, miss, TTL, clear, failure, declared-type, value-isolation, and repeatable-eviction
semantics. Redis integration uses the pinned Testcontainers image
`redis:7.2.4-alpine@sha256:c8bb255c3559b3e458766db810aa7b3c7af1235b204cfdb304e79ff388fe1a5a`.

Run the Redis provider proof with:

```text
./mvnw -ntp -pl vertique-cache/vertique-cache-redis -am verify
```

The package-level clean verification also checks dependency/BOM parity, forbidden implementation
dependencies, packaged module-documentation parity, and clean generated-code regeneration.

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-cache-core` | Provider-neutral cache contracts |
| `vertique-json` | JSON mapper profiles and `JsonMapperProfileRegistry` runtime wiring |
| `vertique-redis-core` | Shared Redis connection and lifecycle boundary |
