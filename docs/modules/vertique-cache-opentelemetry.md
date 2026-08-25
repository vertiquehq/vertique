# Developing Vertique Cache OpenTelemetry

> **Audience:** Vertique framework contributors and source agents
> **Public contract:** `vertique-cache-opentelemetry/src/main/resources/META-INF/vertique/module.md`

This module owns the OpenTelemetry implementation of the provider-neutral cache observation seam.
It is an optional cache-family adapter; cache-core and cache providers remain independent of
OpenTelemetry.

## Source Map

- `dev.vertique.cache.opentelemetry.CacheTracingObserver` — bounded cache span observer.
- `OpenTelemetryCacheModule` — explicit Dagger contribution module.

## Runtime or Build Flow

Applications install `OpenTelemetryCacheModule` alongside `OpenTelemetryModule` and their cache
provider modules. Dagger contributes one `CacheTracingObserver` to `Set<CacheObserver>`. The adapter
uses the tracer and tracing configuration supplied by `vertique-opentelemetry-core` and never makes
cache behavior depend on telemetry success.

## Load-Bearing Invariants

- `vertique-opentelemetry-core` remains cache-agnostic and does not contribute cache observers.
- Cache span names and bounded attributes remain unchanged by the module move.
- Observer and tracer failures are swallowed so cache execution remains fail-open.
- The adapter is installed explicitly; provider-wide OpenTelemetry bootstrap does not discover it.

## Testing

Focused adapter proof:

```text
./mvnw -ntp -pl vertique-cache/vertique-cache-opentelemetry -am test
```

The tests cover bounded span attributes, fail-open tracer behavior, and Dagger multibinding.
