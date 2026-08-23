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

Connection profiles are named application configuration. Feature modules reference a profile and receive shared infrastructure through explicit Dagger composition.

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-core` | Framework foundation and typed configuration boundary |
| Vert.x Redis Client | Asynchronous Redis client API |
