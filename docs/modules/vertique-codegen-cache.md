# Developing Vertique Codegen Cache

> **Audience:** Vertique framework contributors and source agents
> **Public contract:** `vertique-codegen/vertique-codegen-cache/src/main/resources/META-INF/vertique/module.md`

This module owns the cache annotation processor boundary and must keep generated application types separate from runtime providers.

## Source Map

- `dev.vertique.codegen.cache` — cache processor package root.

## Runtime or Build Flow

The processor consumes `vertique-cache-core` contracts during application compilation. When cache annotations are present it emits one public `GeneratedCacheModule` in the annotated bean's package, including `CacheCaffeineModule`; applications can include that generated module in their Dagger component to install the local provider. Generated classes belong to the consuming compilation and are not dependencies of cache providers.

For JAX-RS `@GET` methods, processor validation accepts entity and `Future<entity>`
results only. HTTP response wrappers, transport response types, buffers, routing contexts,
streams, publishers, and `Multi` results fail at annotation processing with a diagnostic;
they are never treated as cacheable transport values.

## Load-Bearing Invariants

- Processor code depends on neutral cache contracts, never on Caffeine or Redis implementation details.
- The module remains a consumable build artifact; the `vertique-codegen` family aggregator is not itself consumed.

## Testing

Processor behavior is proven by the leaf tests and by facade discovery through `vertique-codegen-all`.

```text
./mvnw -ntp -pl vertique-codegen/vertique-codegen-cache -am test
```
