# Plan — Cache-specific module family migration

**Status:** Implementing
**Risk:** Contract-critical — published Maven coordinates, reactor boundaries, and Dagger composition points

## Charter

Cache-specific build-time and observability adapters are feature-owned by the
`vertique-cache` family. Provider-wide infrastructure remains in the
`vertique-micrometer` and `vertique-opentelemetry` families, and shared annotation
processing remains in `vertique-codegen`.

### Target structure

```text
vertique-cache/
├── vertique-cache-core
├── vertique-cache-codegen
├── vertique-cache-injvm
├── vertique-cache-redis
├── vertique-cache-micrometer
└── vertique-cache-opentelemetry
```

The adapter dependencies point inward:

```text
vertique-cache-codegen       → vertique-cache-core, vertique-codegen-core
vertique-cache-micrometer    → vertique-cache-core, vertique-micrometer-core
vertique-cache-opentelemetry → vertique-cache-core, vertique-opentelemetry-core
```

The provider cores must not depend on cache contracts or contribute cache
observers. Applications opt into each cache adapter's Dagger module explicitly.

### Invariants to preserve

- Cache operation and cleanup observation behavior remains unchanged.
- Micrometer and OpenTelemetry adapter public Java packages and Dagger binding
  semantics remain unchanged, except for their Maven artifact coordinates and
  installation documentation.
- `OpenTelemetryModule` remains cache-agnostic; cache tracing is contributed only
  by the cache-owned adapter module.
- Cache codegen remains a consumable annotation-processor artifact under the cache
  family and continues to generate the same application types.
- No stale reactor, BOM, publication, documentation, test, reflection, or DI
  references remain for the old Micrometer cache artifact or the old OpenTelemetry
  cache placement.

### Explicitly excluded

- Redis timeout/deadline implementation changes.
- Cache behavior, cleanup policy, retry policy, metrics policy, or tracing payload
  changes.
- Renaming Java packages or public classes.

## Green checkpoints

1. Move the Micrometer cache adapter from `vertique-micrometer` into
   `vertique-cache`, preserving its Java packages and tests.
2. Extract the OpenTelemetry cache observer and Dagger contribution from
   `vertique-opentelemetry-core` into `vertique-cache-opentelemetry`, preserving
   Java packages and observer behavior.
3. Update reactor, BOM, publication, coverage, module index, canonical module
   docs, maintainer docs, and installation references.
4. Run exact old-coordinate/path absence checks, dependency-direction checks,
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
