<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Cache In-JVM

> **Status:** Alpha
> **Package:** `dev.vertique.cache.injvm`
> **Artifact:** `vertique-cache-injvm`
> **Depends on:** `vertique-cache-core`

`vertique-cache-injvm` is the in-process provider boundary for annotation-driven cache support. It is intentionally separate from the provider-neutral cache contracts.

## When To Use It

Use this provider for application instances whose cache state is intentionally local to one process, including tests and single-instance deployments.

## Core Concepts

The provider owns local storage policy and lifecycle while the cache core owns the provider-neutral invocation contract. Applications install providers explicitly in their Dagger composition.

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-cache-core` | Provider-neutral cache contracts |
