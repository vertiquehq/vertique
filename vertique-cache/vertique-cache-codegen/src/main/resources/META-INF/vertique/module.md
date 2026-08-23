<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Cache Codegen

> **Status:** Alpha
> **Package:** `dev.vertique.cache.codegen`
> **Artifact:** `vertique-cache-codegen`
> **Depends on:** `vertique-cache-core`, `vertique-codegen-core`

`vertique-cache-codegen` is the build-time module boundary for generated cache metadata. It remains separate from the provider-neutral runtime and from storage providers.

## When To Use It

Use this artifact in the compile-time processor path of applications that use generated cache metadata. Runtime applications should also declare the cache runtime and a concrete provider according to their composition.

## Core Concepts

Code generation consumes cache annotations and emits application-owned metadata. Generated types belong to the consuming application compilation and are not supplied by a runtime provider module.

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-cache-core` | Cache annotation and runtime contract types |
| `vertique-codegen-core` | Shared annotation-processor utilities |
