<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Cache AOP

> **Status:** Alpha
> **Package:** `dev.vertique.cache`
> **Artifact:** `vertique-cache-aop`
> **Depends on:** `vertique-cache-core`, `vertique-aop`, `vertique-core`

`vertique-cache-aop` adapts `@Cacheable` and `@CacheEvict` method declarations to the
provider-neutral programmatic cache API. It owns annotation vocabulary, method metadata
adaptation, cache-key template rendering for annotated methods, and the Dagger bindings
for the cache aspects.

The generic Vertique AOP processor generates the application proxy, reflection-free
`MethodMetadata`, annotation literals, and interceptor chain. This module does not own
proxy generation or cache storage behavior.

## Composition

Include `CacheAopModule` alongside a cache provider module in an application component.
Provider modules include the provider-neutral `CacheCoreModule`; the AOP module supplies
the annotation adapters.

Programmatic callers should depend only on `vertique-cache-core` and use `CacheBuilder`
and `Cache<K,V>` directly.

## Verification

```text
./mvnw -ntp -pl vertique-cache/vertique-cache-aop -am verify
```
