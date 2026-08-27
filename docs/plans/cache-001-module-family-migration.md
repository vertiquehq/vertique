# Plan — Cache-specific module family migration

**Status:** Implementing
**Risk:** Contract-critical — published Maven coordinates, reactor boundaries, and Dagger composition points

## Charter

Cache-specific build-time and observability adapters remain feature-owned by the
cache concern, while their Maven ownership follows the provider family that
supplies the integration boundary. Shared annotation processing remains in
`vertique-codegen`.

### Target structure

```text
vertique-cache/
├── vertique-cache-core
├── vertique-cache-caffeine
└── vertique-cache-redis

vertique-codegen/
└── vertique-codegen-cache

vertique-micrometer/
└── vertique-micrometer-cache

vertique-opentelemetry/
└── vertique-opentelemetry-cache
```

The adapter dependencies point inward:

```text
vertique-codegen-cache       → vertique-cache-core, vertique-codegen-core
vertique-micrometer-cache    → vertique-cache-core, vertique-micrometer-core
vertique-opentelemetry-cache → vertique-cache-core, vertique-opentelemetry-core
```

The provider cores must not depend on cache contracts or contribute cache
observers. Applications opt into each cache adapter's Dagger module explicitly.

### Invariants to preserve

- Cache operation and cleanup observation behavior remains unchanged.
- Cache-owned Micrometer and OpenTelemetry adapter packages use the provider-family
  namespace (`dev.vertique.micrometer.cache` and `dev.vertique.opentelemetry.cache`).
  Provider-wide packages remain unchanged.
- `OpenTelemetryModule` remains cache-agnostic; cache tracing is contributed only
  by the cache-owned adapter module.
- Cache codegen remains a consumable annotation-processor artifact under the codegen
  family and continues to generate the same application types.
- No stale reactor, BOM, publication, documentation, test, reflection, or DI
  references remain for the old Micrometer cache artifact or the old OpenTelemetry
  cache placement.

### Explicitly excluded

- Redis timeout/deadline implementation changes.
- Cache behavior, cleanup policy, retry policy, metrics policy, or tracing payload
  changes.
- Renaming Redis APIs or changing timeout/deadline behavior.

## Green checkpoints

1. Move cache code generation from `vertique-cache` into
   `vertique-codegen/vertique-codegen-cache`, changing its package base to
   `dev.vertique.codegen.cache` and updating processor discovery.
2. Move the Micrometer cache adapter from `vertique-cache` into
   `vertique-micrometer/vertique-micrometer-cache`, changing its package base to
   `dev.vertique.micrometer.cache` and preserving its observer/module behavior.
3. Move the OpenTelemetry cache adapter from `vertique-cache` into
   `vertique-opentelemetry/vertique-opentelemetry-cache`, changing its package base to
   `dev.vertique.opentelemetry.cache` and preserving its observer/module behavior.
4. Update reactor, BOM, publication, coverage, module index, canonical module
   docs, maintainer docs, and installation references.
5. Run exact old-coordinate/path absence checks, dependency-direction checks,
   focused affected-module tests, formatting, and module-doc validation.

## Baseline

Before editing, these commands were green on `e23317a1`:

```text
./mvnw -ntp -pl vertique-cache/vertique-cache-codegen -am test
./mvnw -ntp -pl vertique-micrometer/vertique-micrometer-cache -am test
./mvnw -ntp -pl vertique-opentelemetry/vertique-opentelemetry-core -am test
```

## Rollback and completion

Each checkpoint is committed independently and must compile before the next
checkpoint begins. If a checkpoint fails, stop at that commit and repair only the
responsible bounded slice; do not reset or discard unrelated work. Completion
requires the old artifact/path searches to be empty, the new modules to appear in
the expected reactor/BOM/publication/docs surfaces, and the affected reactor tests
to pass.
