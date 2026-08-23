# Developing Vertique Cache In-JVM

> **Audience:** Vertique framework contributors and source agents
> **Public contract:** `vertique-cache-injvm/src/main/resources/META-INF/vertique/module.md`

This module is the ownership boundary for the local cache provider. Its implementation may choose local storage details, but its integration surface remains the provider-neutral cache core.

## Source Map

- `dev.vertique.cache.injvm` — local provider package root.

## Runtime or Build Flow

The provider is selected by explicit application composition and depends on the cache core contracts. Provider behavior and its conformance proof are owned by later tasks.

## Load-Bearing Invariants

- Local storage must not leak into the provider-neutral cache API.
- Provider modules are consumable JARs and belong in coverage; the family aggregator does not.

## Testing

Provider behavior is outside T001. The foundation proof selects this module through the cache family aggregator.

```text
./mvnw -ntp -pl vertique-cache/vertique-cache-injvm -am test
```
