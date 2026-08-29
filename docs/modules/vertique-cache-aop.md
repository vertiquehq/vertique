# Developing Vertique Cache AOP

> **Audience:** Vertique framework contributors and source agents
> **Public contract:** `vertique-cache/vertique-cache-aop/src/main/resources/META-INF/vertique/module.md`

This module owns the annotation and AOP layer over the provider-neutral cache API. It contains
the cache annotations, invocation adapters, selector rendering, and Dagger aspect bindings.

## Source Map

- `dev.vertique.cache` — annotations and cache AOP adapters.
- `CacheAnnotationAdapter` — translates annotation metadata into the neutral `CacheBuilder` seam.
- `CacheAopModule` — contributes the cache aspect providers to Dagger.

## Boundary

`vertique-cache-core` is the programmatic API and runtime owner. This module depends inward on it;
cache-core does not depend on this module. The generic AOP processor generates subclass proxies,
`MethodMetadata`, annotation literals, and interceptor chains. The cache processor validates cache
declarations and emits only the optional provider-composition module.

## Testing

The moved annotation, selector, identity, eviction, and security characterization tests live in
this module. Run them with:

```text
./mvnw -ntp -pl vertique-cache/vertique-cache-aop -am test
```
