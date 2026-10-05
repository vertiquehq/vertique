<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Cache Caffeine

> **Status:** Stable
> **Package:** `dev.vertique.cache.caffeine`
> **Artifact:** `vertique-cache-caffeine`
> **Depends on:** `vertique-cache-core`, `vertique-json`

`vertique-cache-caffeine` is the Caffeine provider for local (`CacheMode.LOCAL`) cache storage.
It is intentionally separate from the provider-neutral cache contracts and uses the named JSON
profile registry for defensive-copy serialization. Annotation apps install `CacheAopModule`
themselves; `vertique-cache-aop` is test-scope here only.

## When To Use It

Use this provider for application instances whose cache state is intentionally local to one process,
including tests and single-instance deployments. Programmatic-only apps need no annotation module.
Apps using `@Cacheable` / `@CacheEvict` also install `CacheAopModule` and put
`vertique-codegen-cache` on the compiler's annotation-processor path.

## Core Concepts

The provider owns local storage policy and lifecycle while cache core owns the provider-neutral
invocation contract. This module includes only `CacheCoreModule` (plus `JsonRuntimeModule`).

The implementation maintains one bounded Caffeine cache per logical `CacheRegion`. Entries are
JSON-serialized with the configured profile, so callers receive a defensive copy and generic
declared result types remain supported. A finite TTL expires entries using a monotonic clock;
TTL `0` disables time expiration while the per-region size bound remains active. Disabled caches
and null results are no-ops, and codec or size failures are surfaced as failed provider futures
for the runtime's fail-open policy.

The provider edge runs the shared provider-neutral `CacheStoreContractTest`, so local behavior is
checked against the same hit, miss, TTL, clear, failure, declared-type, value-isolation, and
repeatable-eviction contract used by Redis. Core operational limits also apply here: oversized
keys and values are rejected, and an explicit annotation TTL above `maxTtlSeconds` is rejected
rather than clamped.

## Key Classes

### CacheCaffeineModule

Dagger `@Module` that includes `CacheCoreModule` and `JsonRuntimeModule`. It contributes:

- `CacheStore` under `@CacheModeKey(CacheMode.LOCAL)`
- provider id `"caffeine"` under `@CacheProviderIdKey(CacheMode.LOCAL)`

Install it in the application component for local caching. Annotation apps also install
`CacheAopModule`.

### CaffeineCacheStore

`CacheStore` backed by per-region Caffeine caches. Public constructors:

- `CaffeineCacheStore(CacheConfig)` — direct use without a Dagger graph (empty JSON profile set)
- `CaffeineCacheStore(CacheConfig, JsonMapperProfileRegistry)` — Dagger-injected form

## Module Dagger Bindings

| Binding | Kind | Description |
|---|---|---|
| `CacheStore` | `@Provides` `@IntoMap` `@CacheModeKey(LOCAL)` | Local Caffeine store |
| `String` | `@Provides` `@IntoMap` `@CacheProviderIdKey(LOCAL)` | Provider id `"caffeine"` |

## Verification

```text
./mvnw -ntp -pl vertique-cache/vertique-cache-caffeine -am verify
```

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-cache-core` | Provider-neutral cache contracts |
| `vertique-json` | Named JSON mapper profiles used for value isolation |
