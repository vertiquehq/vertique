# Developing Vertique Cache Codegen

> **Audience:** Vertique framework contributors and source agents
> **Public contract:** `vertique-cache-codegen/src/main/resources/META-INF/vertique/module.md`

This module owns the cache annotation processor boundary and must keep generated application types separate from runtime providers.

## Source Map

- `dev.vertique.cache.codegen` — cache processor package root.

## Runtime or Build Flow

The processor consumes `vertique-cache-core` contracts during application compilation. When cache annotations are present it emits one public `GeneratedCacheModule` in the annotated bean's package, including `CacheInJvmModule`; applications can include that generated module in their Dagger component to install the local provider. Generated classes belong to the consuming compilation and are not dependencies of cache providers.

For JAX-RS `@GET` methods, processor validation accepts entity and `Future<entity>`
results only. HTTP response wrappers, transport response types, buffers, routing contexts,
streams, publishers, and `Multi` results fail at annotation processing with a diagnostic;
they are never treated as cacheable transport values.

## Load-Bearing Invariants

- Processor code depends on neutral cache contracts, never on Caffeine or Redis implementation details.
- The module remains a consumable build artifact; the cache family aggregator is not BOM-managed.

## Testing

Processor behavior is outside T001. The foundation proof selects this module through the cache family aggregator.

```text
./mvnw -ntp -pl vertique-cache/vertique-cache-codegen -am test
```
