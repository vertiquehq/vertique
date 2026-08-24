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

Validation is performed while typed configuration is constructed. Diagnostics contain stable field-level messages and never include endpoint credentials or password material. This module owns profile shape and validation; secret resolution, client construction, connection reuse, and shutdown lifecycle belong to the Redis client-lifecycle boundary.

## Deadline behavior

`RedisDeadline.withDeadline(Vertx, Future<T>, Duration)` provides a non-blocking event-loop deadline for asynchronous Redis operations. The duration must be positive; when it expires, the returned future fails with a timeout. The backend future is not canceled: late backend completion is fenced and ignored after the returned future settles, so callers must not assume upstream cancellation.

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-core` | Framework foundation and typed configuration boundary |
| Vert.x Redis Client | Asynchronous Redis client API |
