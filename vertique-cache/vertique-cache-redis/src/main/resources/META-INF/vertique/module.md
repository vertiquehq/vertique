<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Cache Redis

> **Status:** Alpha
> **Package:** `dev.vertique.cache.redis`
> **Artifact:** `vertique-cache-redis`
> **Depends on:** `vertique-cache-core`, `vertique-redis-core`

`vertique-cache-redis` is the clustered cache provider boundary. It keeps cache behavior behind the provider-neutral cache core and reuses shared Redis connection infrastructure.

## When To Use It

Use this provider when cache state must be shared across application instances. Configure shared Redis connection profiles through `vertique-redis-core`; do not duplicate connection settings in cache annotations.

## Core Concepts

Cache-specific storage behavior belongs here, while named Redis profiles and client lifecycle belong to the shared Redis module. Applications install the provider explicitly in their Dagger composition.

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-cache-core` | Provider-neutral cache contracts |
| `vertique-redis-core` | Shared Redis connection and lifecycle boundary |
