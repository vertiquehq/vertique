<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Redis Core

> **Status:** Alpha
> **Package:** `dev.vertique.redis`
> **Artifact:** `vertique-redis-core`
> **Depends on:** `vertique-core`, Vert.x Redis Client

`vertique-redis-core` is the shared infrastructure boundary for named Redis connection profiles and client lifecycle. Redis-backed features depend on this module instead of defining duplicate endpoint, credential, TLS, or pool configuration.

## When To Use It

Use this artifact when composing a Vertique capability that needs shared Redis connections. It is infrastructure and does not select or implement a feature-specific Redis use case.

## Core Concepts

Connection profiles are named application configuration under `redis.connections.<name>`. Each profile validates a non-blank name, one or more credential-free `redis://` or `rediss://` endpoints, optional username and secret-reference fields, TLS mode, connect timeout, maximum pool size, and maximum waiting requests. Profile names must be unique. Feature modules reference a profile and receive shared infrastructure through explicit Dagger composition.

The typed profile configuration is built during application startup. The application-scoped registry keeps an immutable snapshot of the validated profiles and their order, including startup-resolved credentials. Changing credential configuration does not change that snapshot or an existing client; credential rotation therefore requires an application restart.

Validation is performed while typed configuration is constructed. Diagnostics contain stable field-level messages and never include endpoint credentials or password material. `RedisConnectionConfig.toString()` also redacts `passwordSecret`. This module owns profile shape and validation; client construction, connection reuse, and shutdown lifecycle belong to the Redis client-lifecycle boundary.

## Redis client lifecycle

`RedisClientRegistry` is the application-scoped profile registry. Calling `client(name)` lazily creates one shared Vert.x Redis client for that profile; repeated calls for the same name reuse it. A request for an unknown profile fails, and requests after shutdown has started are rejected. Client creation does not claim that Redis is synchronously connected or ready.

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

Redis clients close in validated profile order. The first registry `close()` call owns the asynchronous close sequence and its future; later calls return that same future, so application teardown is ordered and idempotent. The Dagger contribution runs in lifecycle phase `INFRA` at the lowest same-phase priority, which places shared-client shutdown after same-phase consumers during reverse-order teardown. The registry closes only its Redis clients; the host-owned `Vertx` instance remains the caller's responsibility.

## Deadline behavior

`RedisDeadline.withDeadline(Vertx, Future<T>, Duration)` provides non-blocking event-loop settlement for asynchronous Redis operations. The duration must be positive; when it expires, the returned future fails with a timeout. The backend future is not canceled: late backend completion is fenced and ignored after the returned future settles, so callers must not assume upstream cancellation.

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-core` | Framework foundation and typed configuration boundary |
| Vert.x Redis Client 5.1.6 | Asynchronous Redis client API |
