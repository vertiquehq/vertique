<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Cache Caffeine

> **Status:** Alpha
> **Package:** `dev.vertique.cache.caffeine`
> **Artifact:** `vertique-cache-caffeine`
> **Depends on:** `vertique-cache-core`, `vertique-cache-aop`, `vertique-json`

`vertique-cache-caffeine` is the Caffeine provider boundary for annotation-driven cache support. It is intentionally separate from the provider-neutral cache contracts and uses the existing named JSON profile registry for defensive-copy serialization.

## When To Use It

Use this provider for application instances whose cache state is intentionally local to one process, including tests and single-instance deployments.

## Core Concepts

The provider owns local storage policy and lifecycle while cache core owns the provider-neutral
invocation contract. This module includes only the provider-neutral `CacheCoreModule`; applications
using cache annotations additionally install `CacheAopModule` themselves, and
programmatic-only applications need no annotation dependency at all.

The implementation maintains one bounded Caffeine cache per logical `CacheRegion`. Entries are JSON-serialized with the configured profile, so callers receive a defensive copy and generic declared result types remain supported. A finite TTL expires entries using a monotonic clock; TTL `0` disables time expiration while the per-region size bound remains active. Disabled caches and null results are no-ops, and codec or size failures are surfaced as failed provider futures for the runtime's fail-open policy.

The provider edge runs the shared provider-neutral `CacheStoreContractTest`, so local behavior is
checked against the same hit, miss, TTL, clear, failure, declared-type, value-isolation, and
repeatable-eviction contract used by Redis. Core operational limits also apply here: oversized
keys and values are rejected, and an explicit annotation TTL above `maxTtlSeconds` is rejected
rather than clamped.

## Verification

Run the local-provider proof with:

```text
./mvnw -ntp -pl vertique-cache/vertique-cache-caffeine -am verify
```

The clean reactor verification also checks dependency/BOM parity, packaged module-documentation
parity, and generated AOP output.

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-cache-core` | Provider-neutral cache contracts |
| `vertique-json` | Named JSON mapper profiles used for value isolation |
