<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Redis Core

> **Status:** Alpha
> **Package:** `dev.vertique.redis`
> **Artifact:** `vertique-redis-core`
> **Depends on:** `vertique-core`, Vert.x Redis Client

`vertique-redis-core` is the shared infrastructure boundary for named Redis connection profiles, client lifecycle, and topology-aware maintenance. Redis-backed features depend on this module instead of defining duplicate endpoint, credential, TLS, or pool configuration.

## When To Use It

Use this artifact when composing a Vertique capability that needs shared Redis connections. It is infrastructure and does not select or implement a feature-specific Redis use case.

## Core Concepts

Connection profiles are named application configuration under `redis.connections.<name>`. Each profile validates a non-blank name, one or more credential-free `redis://` or `rediss://` endpoints, optional username and secret-reference fields, TLS mode, connect timeout, maximum pool size, and maximum waiting requests. Profile names must be unique. Feature modules reference a profile and receive shared infrastructure through explicit Dagger composition.

The typed profile configuration is built during application startup. The application-scoped registry keeps an immutable snapshot of the validated profiles and their order, including startup-resolved credentials. Changing credential configuration does not change that snapshot or an existing client; credential rotation therefore requires an application restart.

Validation is performed while typed configuration is constructed. Diagnostics contain stable field-level messages and never include endpoint credentials or password material. `RedisConnectionConfig.toString()` also redacts `passwordSecret`. This module owns profile shape and validation; client construction, connection reuse, and shutdown lifecycle belong to the Redis client-lifecycle boundary.

## Redis client lifecycle

`RedisClientRegistry` is the application-scoped profile registry. Calling `client(name)` lazily creates one shared standalone Vert.x Redis client for that profile; repeated calls for the same name reuse it. Calling `primaryOperations(name)` lazily creates one separate shared cluster-capable client and seam for that profile; repeated calls reuse both. Calling `topologyOperations(name)` lazily creates one internal Lettuce 7.7.0.RELEASE cluster client and one cached topology-maintenance seam for that profile; the Lettuce cluster connection is opened lazily by that seam. A request for an unknown profile fails, and requests after shutdown has started are rejected. Client creation does not claim that Redis is synchronously connected or ready.

Ordinary request-path Redis commands remain on the asynchronous Vert.x Redis clients. The Lettuce client is an internal maintenance implementation detail and is not used by the ordinary cache request path, readiness futures, or business futures.

The registry maps profile settings to the Vert.x Redis Client 5.1.6 options as follows:

| Profile setting | Vert.x option |
|---|---|
| `endpoints` | `RedisOptions.setEndpoints(...)` |
| `tlsEnabled` | `NetClientOptions.setSsl(...)` |
| `connectTimeoutMs` | `NetClientOptions.setConnectTimeout(...)` |
| `maxPoolSize` | `RedisOptions.setMaxPoolSize(...)` |
| `maxPoolWaiting` | `RedisOptions.setMaxPoolWaiting(...)` |
| `username` | `RedisOptions.setUser(...)` when present |
| `passwordSecret` | `RedisOptions.setPassword(...)` when present |

Single Redis commands are asynchronous: Vert.x Redis Client 5.1.6 `send(Request)` returns a
`Future<Response>`. `Future.timeout(long, TimeUnit)` fences the returned future to the timeout
boundary. This module does not claim per-request cancellation.

Redis clients close in validated profile order. For each profile, the registry closes any created Vert.x standalone client, Vert.x cluster client, and Lettuce topology client; the first registry `close()` call owns the asynchronous close sequence and its future, while later calls return that same future. This makes application teardown ordered and idempotent. The Dagger contribution runs in lifecycle phase `INFRA` at the lowest same-phase priority, which places shared-client shutdown after same-phase consumers during reverse-order teardown. The registry closes only its Redis clients; the host-owned `Vertx` instance remains the caller's responsibility.

## Primary-node operations

`RedisClientRegistry.primaryOperations(profileName)` returns a topology-aware
`RedisPrimaryOperations` wrapper around an explicit cluster-capable client for the named
profile. It does not wrap the standalone client returned by `client(profileName)`. The registry
retains lifecycle ownership of both client types and closes them in profile order.

The cluster client receives the profile's network and pool options on the `RedisOptions` passed to
Vert.x `Redis.createClusterClient(...)`. Its connect-options supplier copies connect-level settings
from that `RedisOptions` into `RedisClusterConnectOptions` and applies the profile endpoints as
cluster seed endpoints; pool and network options remain on `RedisOptions`.

The wrapper exposes asynchronous fan-outs to every Redis primary:

| Operation | Signature | Behavior |
|---|---|---|
| `scan` | `Future<List<Response>> scan(Object... args)` | Sends `SCAN` with the supplied arguments through `RedisCluster.onAllMasterNodes`. |
| `unlink` | `Future<List<Response>> unlink(Object... keys)` | Sends `UNLINK` with the supplied keys through `RedisCluster.onAllMasterNodes`. |

The returned futures contain the response list from the cluster operation. Cleanup
scheduling, scan bounds, retries and backoff, metrics, and cache-key policy are concerns of
the feature or cleanup policy using this seam; they are not defined by `vertique-redis-core`.

## Topology maintenance

`RedisClientRegistry.topologyOperations(profileName)` exposes the minimal public maintenance seam:

- `RedisPrimaryNode` is a stable primary-node identity.
- `RedisScanPage` carries a node-local cursor, the keys returned by that page, and its finished flag.
- `RedisTopologyOperations` discovers primary nodes, scans one primary with a caller-owned cursor and requested count, and asynchronously unlinks keys from one primary.

The package-private `LettuceRedisTopologyOperations` adapter implements that seam with Lettuce
7.7.0.RELEASE. It discovers upstream nodes from the Lettuce topology, performs node-local
`SCAN`, and issues asynchronous `UNLINK`. The registry owns the Lettuce client and closes it with
the other created Redis clients. This core seam does not define cleanup scheduling, key eligibility,
deduplication, retry/backoff, metrics, or cache-marker policy; those remain feature-policy concerns.

## Deadline behavior

`RedisDeadline.withDeadline(Vertx, Future<T>, Duration)` provides non-blocking event-loop settlement for asynchronous Redis operations. The duration must be positive; when it expires, the returned future fails with a timeout. The backend future is not canceled: late backend completion is fenced and ignored after the returned future settles, so callers must not assume upstream cancellation.

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-core` | Framework foundation and typed configuration boundary |
| Vert.x Redis Client 5.1.6 | Asynchronous Redis client API |
| Lettuce 7.7.0.RELEASE | Internal topology-maintenance client |
