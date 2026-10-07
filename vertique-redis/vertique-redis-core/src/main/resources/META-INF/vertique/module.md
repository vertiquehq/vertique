<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Redis Core

> **Status:** Stable
> **Package:** `dev.vertique.redis`
> **Artifact:** `vertique-redis-core`
> **Depends on:** `vertique-core`, Vert.x Redis Client

`vertique-redis-core` is the shared infrastructure boundary for named Redis connection profiles,
client lifecycle, and topology-aware maintenance. Redis-backed features depend on this module
instead of defining duplicate endpoint, credential, TLS, or pool configuration.

## When To Use It

Use this artifact when composing a Vertique capability that needs shared Redis connections. It is
infrastructure and does not select or implement a feature-specific Redis use case. Install
`RedisConnectionModule` and reference a named profile from the feature module.

## Core Concepts

Connection profiles are named application configuration under `redis.connections.<name>`. Each
profile validates a non-blank name, one or more credential-free `redis://` or `rediss://`
endpoints, optional username and secret-reference fields, TLS mode, connect timeout, maximum pool
size, and maximum waiting requests. Profile names must be unique. Feature modules reference a
profile and receive shared infrastructure through explicit Dagger composition.

The typed profile configuration is built during application startup. The application-scoped
registry keeps an immutable snapshot of the validated profiles and their order, including
startup-resolved credentials. Changing credential configuration does not change that snapshot or
an existing client; credential rotation therefore requires an application restart.

Validation is performed while typed configuration is constructed. Diagnostics contain stable
field-level messages and never include endpoint credentials or password material.
`RedisConnectionConfig.toString()` also redacts `passwordSecret`.

## Redis client lifecycle

`RedisClientRegistry` is the application-scoped profile registry. Calling `client(name)` lazily
creates one shared standalone Vert.x Redis client for that profile; repeated calls for the same
name reuse it. Calling `primaryOperations(name)` lazily creates one separate shared cluster-capable
client and seam for that profile; repeated calls reuse both. Calling `topologyOperations(name)`
lazily creates one internal Lettuce cluster client and one cached topology-maintenance seam for
that profile; the Lettuce cluster connection is opened lazily by that seam. A request for an
unknown profile fails, and requests after shutdown has started are rejected. Client creation does
not claim that Redis is synchronously connected or ready.

Ordinary request-path Redis commands remain on the asynchronous Vert.x Redis clients. The Lettuce
client is an internal maintenance implementation detail and is not used by the ordinary cache
request path, readiness futures, or business futures.

The registry maps profile settings to the Vert.x Redis Client options as follows:

| Profile setting | Vert.x option |
|---|---|
| `endpoints` | `RedisOptions.setEndpoints(...)` |
| `tlsEnabled` | `NetClientOptions.setSsl(...)` |
| `connectTimeoutMs` | `NetClientOptions.setConnectTimeout(...)` |
| `maxPoolSize` | `RedisOptions.setMaxPoolSize(...)` |
| `maxPoolWaiting` | `RedisOptions.setMaxPoolWaiting(...)` |
| `username` | `RedisOptions.setUser(...)` when present |
| `passwordSecret` | `RedisOptions.setPassword(...)` when present |

Single Redis commands are asynchronous: Vert.x Redis Client `send(Request)` returns a
`Future<Response>`. This module does not claim per-request cancellation.

Redis clients close in validated profile order. For each profile, the registry closes any created
Vert.x standalone client, Vert.x cluster client, and Lettuce topology client; the first registry
`close()` call owns the asynchronous close sequence and its future, while later calls return that
same future. The Dagger contribution runs in lifecycle phase `INFRA` at the lowest same-phase
priority, which places shared-client shutdown after same-phase consumers during reverse-order
teardown. The registry closes only its Redis clients; the host-owned `Vertx` instance remains the
caller's responsibility.

## Primary-node operations

`RedisClientRegistry.primaryOperations(profileName)` returns a topology-aware
`RedisPrimaryOperations` wrapper around an explicit cluster-capable client for the named profile.
It does not wrap the standalone client returned by `client(profileName)`.

| Operation | Signature | Behavior |
|---|---|---|
| `scan` | `Future<List<Response>> scan(Object... args)` | Sends `SCAN` through `RedisCluster.onAllMasterNodes`. |
| `unlink` | `Future<List<Response>> unlink(Object... keys)` | Sends `UNLINK` through `RedisCluster.onAllMasterNodes`. |

Cleanup scheduling, scan bounds, retries, metrics, and key policy belong to the feature using this
seam.

## Topology maintenance

`RedisClientRegistry.topologyOperations(profileName)` exposes:

- `RedisPrimaryNode` — stable primary-node identity
- `RedisScanPage` — node-local cursor, keys, finished flag
- `RedisTopologyOperations` — discover primaries; scan one primary; unlink keys from one primary

An internal Lettuce adapter implements that seam. The registry owns the Lettuce client lifecycle.
This core seam does not define cleanup scheduling, key eligibility, deduplication, retry/backoff,
metrics, or cache-marker policy.

## Deadline behavior

`RedisDeadline.withDeadline(Vertx, Future<T>, Duration)` provides non-blocking event-loop settlement
for asynchronous Redis operations. The duration must be positive; when it expires, the returned
future fails with a timeout. The backend future is not canceled: late backend completion is fenced
and ignored after the returned future settles.

## Key Classes

### RedisConnectionModule

Dagger `@Module` that parses `redis.connections`, binds `RedisConnectionsConfig` and
`RedisClientRegistry`, and contributes `RedisClientShutdownStep` into the application shutdown set.

### RedisClientRegistry

Application-scoped lazy registry. `client(name)`, `primaryOperations(name)`, and
`topologyOperations(name)` create and cache per-profile clients/seams. `close()` is ordered and
idempotent.

### RedisConnectionConfig / RedisConnectionsConfig

Validated named profile record and the ordered profile list. Profile names must be unique across
the list.

### RedisPrimaryOperations

Cluster fan-out for `scan` / `unlink` on every primary.

### RedisTopologyOperations / RedisPrimaryNode / RedisScanPage

Minimal public maintenance seam for topology-aware cleanup.

### RedisDeadline

Fences an asynchronous Redis future to a positive wall-clock deadline without canceling the backend.

## Configuration

Profiles live under `redis.connections` as a keyed object (key = profile name). Each profile:

| Key | Type | Required | Constraints |
|---|---|---|---|
| `endpoints` | string[] | yes | Non-empty; each entry credential-free `redis://` or `rediss://` (no user-info, query, or fragment) |
| `username` | string | no | Blank treated as absent |
| `passwordSecret` | string | no | Blank treated as absent; redacted in diagnostics/`toString()` |
| `tlsEnabled` | boolean | yes | Mapped to Vert.x SSL |
| `connectTimeoutMs` | long | yes | 1–60000 |
| `maxPoolSize` | int | yes | 1–256 |
| `maxPoolWaiting` | int | yes | 0–10000 |

## Module Dagger Bindings

| Binding | Kind | Description |
|---|---|---|
| `RedisConnectionsConfig` | `@Provides` `@Singleton` | Parsed `redis.connections` profiles |
| `RedisClientRegistry` | `@Provides` `@Singleton` | Lazy shared clients for those profiles |
| `ApplicationShutdownStep` | `@Provides` `@IntoSet` | `RedisClientShutdownStep` (`INFRA`, lowest same-phase priority) |

## Verification

```text
./mvnw -ntp -pl vertique-redis/vertique-redis-core -am verify
```

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-core` | Framework foundation and typed configuration boundary |
| Vert.x Redis Client | Asynchronous Redis client API |
| Lettuce 7.7.0.RELEASE | Internal topology-maintenance client |
