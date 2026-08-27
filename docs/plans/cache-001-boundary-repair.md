# Plan — Cache boundary and unreleased API cleanup

**Status:** Approved for implementation
**Risk:** Contract-critical — public constructors, generated Dagger references, Maven coordinates, packages, and canonical module documentation

## Charter

The cache family is still unreleased. Its compatibility constructors and
provider-specific names must describe the intended first public shape instead of
preserving pre-release history. Cache-owned integrations must also comply with the
repository's package and layering standards: cache-specific artifacts live under the
provider family that supplies their integration boundary, while provider-wide
Micrometer and OpenTelemetry cores remain cache-agnostic.

The module-family placement was amended on 2026-08-27: cache code generation now
belongs to `vertique-codegen`, cache Micrometer integration to `vertique-micrometer`,
and cache OpenTelemetry integration to `vertique-opentelemetry`.

## Target structure

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

Target Java ownership:

- `dev.vertique.cache.caffeine` — Caffeine store and `CacheCaffeineModule`.
- `dev.vertique.micrometer.cache` — cache Micrometer observer and module.
- `dev.vertique.opentelemetry.cache` — cache tracing observer and module.
- `dev.vertique.codegen.cache` — cache annotation processor.
- `dev.vertique.micrometer` and `dev.vertique.opentelemetry` — provider-wide concerns only.

## Approved breaking changes

- Remove the pre-profile compatibility constructors from `CacheConfig` and
  `CacheEntryConfig`; all repository callers use the profile-aware records.
- Rename `vertique-cache-injvm` to `vertique-cache-caffeine`.
- Rename `CacheInJvmModule` to `CacheCaffeineModule` and update generated code references.
- Move cache codegen, Micrometer, and OpenTelemetry adapters to their provider-family
  modules and package bases above.
- Remove cache-adapter installation and ownership claims from provider-core canonical docs;
  document them only in the cache adapter module references.

No relocation artifacts or compatibility bridges are required because these artifacts
and APIs have never shipped.

## Invariants

- Cache operation, cleanup, TTL, serialization, Redis deadline, retry, metrics, and tracing
  behavior remain unchanged.
- Generated applications still receive one `GeneratedCacheModule`, now importing
  `CacheCaffeineModule`.
- Provider cores contain no cache observer types, imports, dependencies, or cache-adapter
  documentation.
- Maven reactor, BOM, publication inventory, coverage aggregate, module index, canonical
  docs, maintainer docs, tests, and generated-code assertions agree on the new names.
- `vertique-cache` remains an aggregator and is not a consumable publication artifact.
- The repository standards are updated where their enumerations/backstops were stale for
  the cache family (`cache` commit scope and cache exception-scan coverage).

## Baseline

Baseline ref: `7b847df7`.

```text
./mvnw -ntp -pl vertique-cache/vertique-cache-core,vertique-codegen/vertique-codegen-cache,vertique-cache/vertique-cache-caffeine,vertique-micrometer/vertique-micrometer-cache,vertique-opentelemetry/vertique-opentelemetry-cache -am -Dtest=CacheContractsTest,CacheOperationalLimitsTest,CacheResolutionTest,CacheAnnotationProcessorTest,CaffeineCacheStoreTest,CacheStoreContractTest,CacheObserverTest,MicrometerCacheModuleTest,OpenTelemetryCacheModuleTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Result: green; the selected cache/core/codegen/Caffeine/Micrometer/OpenTelemetry tests
passed with no failures.

## Ordered checkpoints

1. Remove pre-profile constructors and update profile-aware repository fixtures/tests;
   run cache-core and provider serialization tests.
2. Rename the Caffeine module, Dagger module, package, and generated code reference;
   run cache-codegen and Caffeine graph/store tests.
3. Move cache codegen to `vertique-codegen-cache`, including its package base and
   processor registration; run processor and facade discovery tests.
4. Move the Micrometer and OpenTelemetry cache adapters to their provider-family
   modules, including package bases and module docs; run both adapter test sets.
5. Reconcile reactor/BOM/publication/coverage/module-index/standards/docs references;
   run publication/module-doc contracts and mechanical absence checks.
6. Run formatting, affected-module verification, full verification, simplification once,
   and an explicit architecture/boundary review.

## Mechanical completion checks

```text
rg -n 'CacheInJvmModule|vertique-cache-injvm|dev\.vertique\.cache\.injvm' --glob '!**/target/**' --glob '!**/graphify-out/**' .
rg -n 'dev\.vertique\.cache\.(codegen|micrometer|opentelemetry)' --glob '!**/target/**' --glob '!**/graphify-out/**' .
rg -n 'pre-profile|preprofile|Compatibility constructor|Compatibility constructor retaining' vertique-cache docs/modules/vertique-cache* --glob '!**/target/**' --glob '!**/graphify-out/**'
rg -n 'CacheObserver|CacheMetricsObserver|CacheTracingObserver|vertique-cache-(micrometer|opentelemetry)' vertique-micrometer/vertique-micrometer-core vertique-opentelemetry/vertique-opentelemetry-core
```

The old names and provider-core cache references must be absent, while the new package,
module, artifact, and documentation references must be present in their intended owners.

## Rollback/checkpoint strategy

Each numbered checkpoint is committed only after its focused tests are green. If a later
checkpoint fails, stop at the last green commit and repair only the current checkpoint;
do not use destructive Git rollback commands. The pre-existing untracked
`vertique-cache/graphify-out/` remains outside this change.
