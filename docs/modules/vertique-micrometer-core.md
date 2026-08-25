# Developing Vertique Micrometer Core

> **Audience:** Vertique framework contributors and source agents
> **Public contract:** `vertique-micrometer-core/src/main/resources/META-INF/vertique/module.md`

This module owns the backend-agnostic Micrometer registry bootstrap and the `MeterRegistry`
binding. It does not own cache observers; cache telemetry is composed by the separate
`vertique-cache-micrometer` adapter.

## Source Map

- `dev.vertique.micrometer` — registry bootstrap, configuration, and Dagger bindings.
- `MicrometerModule` — application-facing Dagger module for the generic `MeterRegistry`.
- `MicrometerMetricsContributor` — pre-Vert.x contributor that assembles and publishes the registry.

## Runtime or Build Flow

ServiceLoader discovers registry backend providers before Vert.x is built. The contributor assembles
the composite registry, applies common tags and cardinality filters, installs it into Vert.x, and
publishes it through `MeterRegistryHolder`. Backend modules remain pluggable. Consumers that need
cache metrics install `MicrometerCacheModule` from `vertique-cache-micrometer` explicitly alongside
`MicrometerModule`; core does
not discover or contribute a cache observer.

## Load-Bearing Invariants

- The core module has no dependency on `vertique-cache-core` and does not contribute
  `CacheMetricsObserver`.
- Applications install adapter modules explicitly; the generic registry bootstrap does not select
  subsystem-specific observers.
- Registry assembly publishes only after successful backend and binder setup; rollback closes
  resources in reverse order.

## Testing

Focused core proof:

```text
./mvnw -ntp -pl vertique-micrometer/vertique-micrometer-core -am test
```

## Related ADRs

- ADR 0098: Micrometer facade and pluggable registry backends.
- D018: Public cache store SPI boundary — cache contracts and adapters remain provider-neutral.
