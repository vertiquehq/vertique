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
infrastructure and returns Vert.x futures for every cache operation. It does not own
named Redis profile parsing or shared-client lifecycle.

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
lifecycle belong to the shared Redis module. `CacheRedisModule` contributes the
provider-neutral `CacheStore` binding and includes the cache core, JSON runtime, and Redis
connection modules. Its included `JsonRuntimeModule` supplies the `JsonMapperProfileRegistry`
wiring used for cache value conversion.

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

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-cache-core` | Provider-neutral cache contracts |
| `vertique-json` | JSON mapper profiles and `JsonMapperProfileRegistry` runtime wiring |
| `vertique-redis-core` | Shared Redis connection and lifecycle boundary |
