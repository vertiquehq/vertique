# Developing Vertique Micrometer Cache

> **Audience:** Vertique framework contributors and source agents
> **Public contract:** `vertique-micrometer-cache/src/main/resources/META-INF/vertique/module.md`

This module owns the Micrometer implementation of the provider-neutral cache observation seam.
It is an optional adapter: cache-core, cache-injvm, and cache-redis remain Micrometer-free.

## Source Map

- `dev.vertique.micrometer.cache` — cache metrics adapter package.
- `MicrometerCacheModule` — explicit Dagger contribution module.
- `CacheMetricsObserver` — operation timer and cleanup counter implementation.

## Runtime or Build Flow

Applications install `MicrometerCacheModule` alongside `MicrometerModule` and their cache provider
modules. Dagger contributes one `CacheMetricsObserver` to `Set<CacheObserver>`. Cache operations
arrive as `CacheObservation`; Redis physical cleanup arrives as `CacheCleanupObservation`. The
observer checks `MetricsConfig.enabled`, records bounded Micrometer meters, and swallows registry
failures so telemetry remains fail-open.

## Load-Bearing Invariants

- The adapter is the only cache module that depends on Micrometer.
- `MicrometerModule` does not contribute cache observers; adapter installation is explicit.
- Operation dimensions are bounded `provider`, `cache`, and `outcome` values. Cleanup dimensions
  are bounded `profile`, `namespace`, and `outcome` values.
- Cleanup counters do not expose the boolean `failed` field as a tag; failed outcomes use the
  bounded `outcome` value.

## Testing

Focused adapter proof:

```text
./mvnw -ntp -pl vertique-micrometer/vertique-micrometer-cache -am test
```

The tests cover Dagger contribution, disabled metrics, bounded operation dimensions, and
fail-open registry behavior.

## Related ADRs

- ADR 0098: Micrometer facade and pluggable registry backends.
- D018: Public cache store SPI boundary — the adapter consumes provider-neutral cache contracts.
