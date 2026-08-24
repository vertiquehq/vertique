<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Cache Core

> **Status:** Alpha
> **Package:** `dev.vertique.cache`
> **Artifact:** `vertique-cache-core`
> **Depends on:** `vertique-aop`, `vertique-core`

`vertique-cache-core` is the provider-neutral foundation for annotation-driven method-result caching. This artifact establishes the cache API boundary independently of local and Redis storage implementations.

## When To Use It

Use this artifact when an application or provider module needs the provider-neutral cache contracts. Install a concrete provider module alongside it to provide storage behavior.

## Core Concepts

Cache annotations and storage contracts are kept separate from provider details. Local and clustered implementations depend on this module; this module does not depend on Caffeine, Redis, or a serialization engine.

## Configuration

The typed cache configuration uses explicit duration units such as `defaultTtlSeconds`,
`maxTtlSeconds`, and `backendTimeoutMs`. `jsonProfile` selects the existing JSON mapper
profile for cache values; a per-cache `jsonProfile` override may inherit the global
cache profile when omitted. Provider modules contribute storage bindings through the
internal `CacheMode` Dagger map seam.

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-aop` | Method interception runtime boundary |
| `vertique-core` | Framework foundations and shared configuration/runtime contracts |
