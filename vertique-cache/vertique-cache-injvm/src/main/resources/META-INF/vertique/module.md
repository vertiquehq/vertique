<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Cache In-JVM

> **Status:** Alpha
> **Package:** `dev.vertique.cache.injvm`
> **Artifact:** `vertique-cache-injvm`
> **Depends on:** `vertique-cache-core`, `vertique-json`

`vertique-cache-injvm` is the in-process provider boundary for annotation-driven cache support. It is intentionally separate from the provider-neutral cache contracts and uses the existing named JSON profile registry for defensive-copy serialization.

## When To Use It

Use this provider for application instances whose cache state is intentionally local to one process, including tests and single-instance deployments.

## Core Concepts

The provider owns local storage policy and lifecycle while the cache core owns the provider-neutral invocation contract. Applications install providers explicitly in their Dagger composition.

The implementation maintains one bounded Caffeine cache per logical `CacheRegion`. Entries are JSON-serialized with the configured profile, so callers receive a defensive copy and generic declared result types remain supported. A finite TTL expires entries using a monotonic clock; TTL `0` disables time expiration while the per-region size bound remains active. Disabled caches and null results are no-ops, and codec or size failures are surfaced as failed provider futures for the runtime's fail-open policy.

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-cache-core` | Provider-neutral cache contracts |
| `vertique-json` | Named JSON mapper profiles used for value isolation |
