# Cache AOP boundary refactor

## Charter

`vertique-cache-core` currently combines the programmatic cache API, provider-neutral
cache execution, annotation adapters, AOP metadata, and generated cache catalog
preparation. This makes `CacheBuilder` the assembly point for unrelated concerns and
leaks codegen-shaped types into the programmatic runtime.

This refactor introduces `vertique-cache-aop` as the annotation/AOP adapter module.
`vertique-cache-core` remains the owner of the programmatic `CacheBuilder`/`Cache` API,
provider-neutral execution, configuration, identity, and storage SPI. The AOP module
depends inward on core and owns cache annotations, aspects, annotation key rendering,
and their Dagger bindings.

The existing AOP processor already generates proxies, `MethodMetadata`, annotation
literals, and interceptor chains for `@Aspect`-annotated annotations. Cache-specific
codegen is therefore removed from the runtime path first: `GeneratedCacheMetadata` and
generated-cache catalog preparation leave cache-core. The cache-specific processor is
retained only for compile-time cache validation; it emits no runtime or provider-composition
types, and no cache-specific processor output is allowed to re-enter `CacheBuilder`.

## Frozen invariants

- Programmatic `CacheBuilder` and `Cache<K,V>` source contracts remain unchanged.
- Cache hit, miss, load, write, invalidation, identity, provider selection, failure,
  timeout, observer, and serialization behavior remain unchanged.
- Annotation aspects continue to run through the generic AOP-generated proxy and
  delegate to the same `Cache` behavior owner.
- AOP metadata remains reflection-free on generated paths; reflective test/scanner
  paths remain supported.
- Provider modules depend inward on cache-core; cache-core has no dependency on
  cache-aop or cache-specific codegen.
- No compatibility bridge is added for the unmerged cache-001 API.

## Excluded from this slice

- New cache semantics or public operations.
- Provider implementation changes.
- Deleting cache-specific compile-time validation before an equivalent proof exists.
- Changing generic AOP processor behavior for unrelated annotations.

## Checkpoints

1. Move annotation/AOP classes and bindings into `vertique-cache-aop`; keep the current
   behavior tests green.
2. Remove `GeneratedCacheMetadata` and generated-operation preparation from core;
   route aspects through the core programmatic construction seam.
3. Reduce the cache-specific processor to validation only after confirming generic AOP coverage
   and explicit provider composition.
4. Run mechanical dependency checks, focused cache-family verification, full reactor
   verification, and review before commit/PR update.
