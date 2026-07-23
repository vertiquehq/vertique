<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# vertique-launcher

> **Status:** Alpha
> **Package:** `dev.vertique.launcher`
> **Artifact:** `vertique-launcher`
> **Depends on:** `vertique-bootstrap`, `vertique-core`, `vertique-config-core`, `io.vertx:vertx-launcher-application`, `io.vertx:vertx-core`, `org.slf4j:slf4j-api`

Framework-owned application entrypoint. `VertiqueApplication` extends Vert.x's `VertxApplication`
and exists because Vert.x 5 requires builder-time wiring — metrics factories, tracers, and other
infrastructure must be passed to `Vertx.builder()` before the `Vertx` instance is created. That
seam is pre-Dagger by definition (every Dagger component in this codebase requires a live `Vertx`
instance to construct), so the bootstrap kernel (in `vertique-bootstrap`) provides a
`java.util.ServiceLoader`-based SPI — `VertxBuilderContributor` — that any classpath dependency
can implement without the application touching its `main` method.

When no contributors are present on the classpath and no `vertx.options` section is declared,
`VertiqueApplication` behaves identically to `io.vertx.launcher.application.VertxApplication`
with respect to CLI parsing, `--conf`/`Main-Verticle` manifest resolution, and exit codes
(FR-LAUNCH-001 parity scope). The bootstrap config load still runs and the `vertx.options`
overlay hook is still installed — they are zero-cost no-ops in that case.

---

## When To Use It

Every Vertique application that deploys a verticle should use `VertiqueApplication` as its
`Main-Class` (or call `new VertiqueApplication(args).launch()` from its own thin main). Modules
that need to configure `Vertx.builder()` — telemetry, custom cluster managers, custom transport —
ship a `VertxBuilderContributor` implementation and register it via `META-INF/services/`. No
application code changes are required: adding the dependency is the integration.

---

## Core Concepts

### Startup sequence

1. **CLI parsed** — `afterVertxOptionsParsed(JsonObject)` captures the raw `--options` JSON
   (normalised to an empty `JsonObject` when absent) into an internal field. `afterConfigParsed(JsonObject)`
   captures the raw `--conf` value (normalised to an empty `JsonObject` when absent).
2. **`createVertxBuilder(VertxOptions)`** runs in three ordered sub-steps:
   1. **Bootstrap config load** — `BootstrapConfigLoader.load(JsonObject)` is called with the
      captured `--conf` overlay. It spins up a temporary `Vertx` instance, resolves all config
      sources (file directories, env, sys, and any declared `config.stores`), overlays the `--conf`
      argument at highest precedence, and stores the fully-resolved tree. On failure (unknown store
      type, failing declared store, I/O error) the exception is captured and rethrown so that
      `launch()` remaps the exit code to `11`; the application aborts. See
      `dev.vertique:vertique-config-core` for the full source-precedence chain.
   2. **Builder construction** — the default `VertxBuilder` is created bound to the
      <em>original</em> `VertxOptions` instance (no overlay here). The builder is retained
      for re-binding in the `beforeStartingVertx` hook. Contributors receive the
      <em>pre-overlay</em> options.
   3. **Contributor chain** — `ContributorRunner.discover()` loads all registered
      `VertxBuilderContributor` implementations via `ServiceLoader`, sorts them by
      `OrderedExtension.comparator()` (phase → priority → orderKey), and threads each contributor's
      returned `VertxBuilder` into the next. Each contributor receives the resolved tree via
      `BootstrapContext.config()` and the <em>pre-overlay</em> `VertxOptions` via
      `BootstrapContext.vertxOptions()`. Contributor mutations to `VertxOptions` are on the
      original instance and survive the overlay step.
3. **Upstream `processVertxOptions`** applies cluster flags (`-cluster-host`, `-cluster-port`),
   `vertx.options.*` system properties, and metrics/tracer SPI conversions on the original
   `VertxOptions` instance (in that order).
4. **`beforeStartingVertx(HookContext)`** — computes the `vertx.options` overlay from the resolved
   config tree and re-binds the retained builder when an overlay is needed. Errors abort startup
   with exit `11`. See the **vertx.options overlay** section below for the full precedence rules.
5. **Vert.x built** from the (possibly re-bound) final `VertxBuilder`.
6. **`beforeDeployingVerticle(HookContext)`** — installs the resolved config tree as the verticle
   deployment config. The deployed verticle's `config()` is therefore the canonical, fully-merged
   tree (all config sources, declared stores, env, sys, and `--conf` at highest precedence). The
   Dagger `AppComponent` receives this tree and no separate `ConfigBootstrap.load` call is needed.
7. **Verticle deployed** — either the framework-owned `VertiqueBootstrapVerticle` (default) or the
   `Main-Verticle` resolved from the manifest (opt-out path).

The `BootstrapContext` passed to each contributor holds a defensive copy of the resolved config and
the <em>pre-overlay</em> `VertxOptions` instance. Contributors may read the config or mutate
`VertxOptions` as side effects; they must return a non-null `VertxBuilder`.

### Shutdown

`BootstrapShutdown` coordinates the full shutdown sequence and guarantees it runs **exactly once**
across all stop and failure paths (normal stop, deploy failure, start failure, contributor failure,
bootstrap failure), guarded by an `AtomicBoolean`. The sequence is:

1. **Contributor `onShutdown()` hooks** — run in reverse contribution order, confined to the
   successfully-contributed prefix.
2. **Bootstrap property-source `close()`** — called on each `ConfigPropertySource` in reverse
   declaration order. Individual close failures are caught, logged at error level (naming the
   source), and never rethrown — the remaining sources are always closed.

`BootstrapShutdown.runOnce()` is the entry point and is idempotent: the second and subsequent
calls are no-ops. `ContributorRunner` has its own idempotency guard; both guards are independent
and both must fire. The double-guard design absorbs the double-invocation that arises when
`afterFailureToDeployVerticle` closes Vert.x, which then triggers `afterVertxStopped`.

`runShutdownSequence()` on `VertiqueApplication` is the public entry point for applications
that close Vert.x programmatically outside the launcher shutdown pipeline. It delegates to
`BootstrapShutdown.runOnce()` and is therefore idempotent.

Shutdown fires on all stop and failure paths:

| Path | Hook |
|------|------|
| Normal stop / SIGTERM | `afterVertxStopped` |
| Vert.x failed to stop | `afterFailureToStopVertx` |
| Deploy failure | `afterFailureToDeployVerticle` (then Vert.x close → `afterVertxStopped`) |
| Vert.x start failure (before deploy) | `afterFailureToStartVertx` |
| Bootstrap or contributor failure | `launch()` after `super.launch()` returns |

**Limitation:** calling `vertx.close()` directly — outside the launcher shutdown pipeline — does not
invoke the shutdown sequence. Use `runShutdownSequence()` explicitly after a programmatic
close if hooks and source closes must run.

### ServiceLoader discovery

`ContributorRunner.discover()` calls `ServiceLoader.load(VertxBuilderContributor.class)`, sorts
the result, and caches it for the lifetime of the `VertiqueApplication` instance. ServiceLoader is
the deliberate pre-DI exception in an otherwise Dagger-first codebase. The kernel types —
`VertxBuilderContributor`, `BootstrapContext`, `ContributorRunner` — live in `vertique-bootstrap`
(package `dev.vertique.bootstrap`). See ADR-0095
for the seam rationale and ADR-0125
for the kernel extraction decision.

### Standalone entry

`VertiqueApplication.verticleSupplier()` returns `VertiqueBootstrapVerticle::new` by default,
bypassing `Main-Verticle` manifest resolution entirely. A standalone application therefore needs no
`MainVerticle` and no `Main-Verticle` manifest entry — `java -jar` works out of the box once a
`VertiqueComponentFactory` is registered (see below).

`VertiqueBootstrapVerticle` builds the `VertiqueRuntime`, discovers the application's
`VertiqueComponentFactory` via `VertiqueComponentFactoryLoader`, then delegates to
`VertiqueApplicationBootstrap.start(runtime, factory)` (the Phase-2 host-neutral runner in
`vertique-application`). The verticle's `stop()` calls `VertiqueApplicationHandle.shutdown()`
(idempotent reverse-order teardown); it never closes `Vertx`.

The config install step is unchanged: `beforeDeployingVerticle` installs the fully-resolved tree
as the deployment config before the verticle starts, so `VertiqueBootstrapVerticle.config()` is the
canonical merged tree — no separate `ConfigBootstrap.load()` call is needed.

**Factory discovery (`VertiqueComponentFactoryLoader`):** On `start()`, the verticle discovers all
`VertiqueComponentFactory` providers via `ServiceLoader` using the SPI resource
`META-INF/services/dev.vertique.core.VertiqueComponentFactory`. Exactly one provider must be
registered:

| Providers found | Outcome |
|-----------------|---------|
| Zero | `IllegalStateException` — names the SPI resource and the discovery classloader |
| Exactly one | Normal startup |
| More than one | `IllegalStateException` — lists every discovered FQN, names the SPI resource and classloader |

Both failure cases propagate to the launcher as a deploy failure (exit code `15`). The error
message is designed to point at the correct registration path without exposing resolved config
values.

**Factory registration options:** the SPI file entry can be written in two ways:

- **`@VertiqueApp` (zero-boilerplate)** — add `vertique-codegen-application` to
  `<annotationProcessorPaths>` and place `@VertiqueApp` on the `@Component` interface. The
  processor generates the factory class and the `META-INF/services` file at compile time. See
  `dev.vertique:vertique-codegen-application` and
  ADR-0132.
- **Manual** — write a `VertiqueComponentFactory` implementation and add it to the SPI file by
  hand. Both paths satisfy the same exactly-one discovery rule.

All framework examples use `@VertiqueApp` on their `AppComponent`, include `CoreLifecycleStepsModule`,
and package as OCI container images via Jib. `vertique-example-hello` and `vertique-example-services`
are the simplest reference implementations. See `docs/packaging.md` for the container
build commands, `exec-maven-plugin` local-run setup, and the migration recipe from maven-shade.

**Opt-out:** set `-Dvertique.bootstrap.verticle=false` (case-insensitive) to make
`verticleSupplier()` return `null`, restoring the standard `Main-Verticle` manifest resolution.
Use this when an application manages its own main verticle and needs to coexist with
`vertique-launcher` on the classpath.

See ADR-0131 for the design rationale.

---

## Key Classes

### VertiqueApplication

Extends `VertxApplication` and implements `VertxApplicationHooks` as its own delegate. The public
constructor takes only `args`; a protected constructor `(args, printUsageOnFailure, exitOnFailure)`
is available as a test and embedded-use seam — set `exitOnFailure = false` to prevent
`System.exit` calls in tests.

**Subclassing contract:** subclasses that override `afterVertxOptionsParsed`, `afterConfigParsed`,
`createVertxBuilder`, or `beforeStartingVertx` **must** call the corresponding `super` method
and return its result. Failing to do so breaks CLI options capture, config capture, the contributor
chain, or the `vertx.options` overlay.

**Exit codes:**

| Code | Meaning |
|------|---------|
| `0` | Verticle deployed; Vert.x event loops keep the JVM alive |
| `11` (`ExitCodes.VERTX_INITIALIZATION`) | Bootstrap config load failed (unknown store type, failing declared store, I/O error), a `VertxBuilderContributor` failed during the contribution phase, **or** the `vertx.options` overlay failed (malformed section); the shutdown sequence runs before exit |
| `15` (`ExitCodes.VERTX_DEPLOYMENT`) | The main verticle failed to deploy |

**Exit-code ownership:** `VertiqueApplication` is the sole owner of the exit decision.
`super.launch()` is always called with `exitOnFailure=false` internally, so the JVM stays alive
regardless of the upstream result. `VertiqueApplication.launch()` then:
1. Detects a stored bootstrap failure, `ContributorFailureException`, or overlay failure, logs the
   failure (message only — loader messages contain no resolved values), runs the shutdown sequence,
   and remaps the code to `11`.
2. Calls `System.exit(code)` if `exitOnFailure` is `true` and `code != 0`.
3. Returns the final code.

#### Invariants & Gotchas

- Config contents are never logged — only structural diagnostics (contributor class names, exit
  codes) are emitted. Never log `BootstrapContext.config()` contents; the config may carry resolved
  secret values.
- `capturedConfig` starts as an empty `JsonObject`, is replaced with the raw `--conf` value in
  `afterConfigParsed`, and finally replaced with the fully-resolved tree by `BootstrapConfigLoader`
  inside `createVertxBuilder`. Contributors always receive the resolved tree; `beforeDeployingVerticle`
  installs a defensive copy of that same resolved tree as the deployment config.
- The `runner` field is lazily initialised on the first call to `createVertxBuilder` and
  `volatile`-published; `createVertxBuilder` is not expected to be called concurrently in normal
  operation.
- Bootstrap config failures and contributor failures are captured into a `startupFailure` field
  and re-thrown from `createVertxBuilder`; overlay failures are captured in `beforeStartingVertx`.
  All cause `launch()` to detect the stored failure, run the shutdown sequence, and remap the exit
  code to `11` before returning.
- The `vertx.options` overlay is computed in `beforeStartingVertx` (not `createVertxBuilder`) so
  that upstream cluster flags, `vertx.options.*` system properties, and metrics/tracer SPI
  conversions are visible in the base before the tree is merged. This is why contributor mutations
  to `VertxOptions` survive the overlay step: they land on the same instance that upstream mutates,
  which becomes the base for the merge.
- **`vertx.options.*` sysprops: warn-and-skip on coercion errors.** When the `vertx.options` tree
  section is present, each `vertx.options.*` system property is applied individually. A property
  whose value causes a `RuntimeException` during `VertxOptions` construction (e.g. an unknown enum
  constant such as `maxWorkerExecuteTimeUnit=NOT_A_UNIT`) is logged at `WARN` level — naming the
  property key and the exception class, but never the value — and skipped. Startup is **not** aborted. This matches the upstream
  `configureFromSystemProperties` warn-and-skip behaviour. The tree and CLI `--options` layers
  remain fail-fast: a bad value in a config file or `--options` JSON still aborts startup with
  exit `11`.
- **`MetricsOptions`/`TracingOptions` retain source JSON.** Vert.x keeps "a copy of the original
  json" in `MetricsOptions` and `TracingOptions` so that JSON-representable provider config
  (e.g. `MicrometerMetricsOptions`, `OpenTelemetryOptions`) survives the JSON round-trip on the
  present-section path. Non-JSON-representable programmatic state (e.g. a pre-built
  `MeterRegistry` instance) is not preserved on that path; contributors that set such state should
  operate on the absent-section path or re-apply their state in a post-build hook.

---

### BootstrapContext

Read-only view of bootstrap state passed to each `VertxBuilderContributor`.

| Method | Contract |
|--------|----------|
| `config()` | Returns a **defensive copy** of the fully-resolved, merged config tree (all config sources: file directories, env, sys, declared `config.stores`, and `--conf` at highest precedence). Each call produces a fresh copy — mutations do not affect other contributors or the stored tree. Never null. |
| `vertxOptions()` | Returns the **live, shared** <em>pre-overlay</em> `VertxOptions` instance — the original instance passed by the upstream launcher, before the `vertx.options` tree is applied. Mutations are visible to subsequent contributors and survive the overlay step. |

The asymmetry is intentional: config is read-only input (defensive copy protects against
accidental aliasing); `VertxOptions` is a mutable accumulator deliberately shared across the chain
so contributors can cooperate on options without needing to inspect each other's output.

**Note on overlay timing:** the `vertx.options` tree is applied in `beforeStartingVertx` (after
`processVertxOptions`), not in `createVertxBuilder`. Contributors therefore see the options *before*
the tree is merged. The final effective options are visible only after `beforeStartingVertx`
completes and are not exposed through `BootstrapContext` (use the `effectiveVertxOptions`
package-private test seam in tests).

---

### VertiqueBootstrapVerticle

Framework-owned standalone entry verticle. `VertiqueApplication.verticleSupplier()` returns a
supplier of this class by default, bypassing `Main-Verticle` manifest resolution.

`start(Promise<Void>)` sequence:

1. Builds `VertiqueRuntime.of(vertx, config())` — `config()` is the canonical merged tree installed
   by `beforeDeployingVerticle`.
2. Calls `VertiqueComponentFactoryLoader.discover()` — exactly-one `VertiqueComponentFactory`; fails
   fast with a descriptive `IllegalStateException` on zero or multiple providers.
3. Calls `VertiqueApplicationBootstrap.start(runtime, factory)` from `vertique-application`;
   completes or fails the start promise with the runner's result.

`stop(Promise<Void>)` calls `VertiqueApplicationHandle.shutdown()` when startup produced a handle.
When startup failed before producing a handle (discovery or an early phase failure), teardown is a
no-op. `Vertx` is never closed.

The class is `final` — applications do not subclass it.

#### Invariants & Gotchas

- The `handle` field is written and read on the same verticle event-loop context; no explicit
  synchronization is needed.
- A discovery failure (zero or multiple factories) fails `startPromise` which propagates to the
  launcher as a deploy failure (exit code `15`), consistent with any other verticle deploy failure.
- The error message logs only structural diagnostics (class names, classloader identity); no
  resolved config values are ever emitted.

---

### VertiqueComponentFactoryLoader

Package-private helper used by `VertiqueBootstrapVerticle` to discover the application's
`VertiqueComponentFactory` via `ServiceLoader`. The class is split into `discover()` (I/O-bound
ServiceLoader call) and `selectFactory(List)` (pure exactly-one rule) for testability.

**SPI resource:** `META-INF/services/dev.vertique.core.VertiqueComponentFactory`

| Method | Description |
|--------|-------------|
| `discover()` | Loads all providers on the thread context classloader (falls back to own classloader); enforces exactly-one; returns the factory typed as `VertiqueComponentFactory<? extends VertiqueApplicationComponent>` |
| `selectFactory(List)` | Pure selection logic — `IllegalStateException` on zero or multiple elements; testable without ServiceLoader I/O |

#### Invariants & Gotchas

- The thread context classloader is preferred for discovery because `VertiqueApplication` typically
  runs in a flat classloader; the fallback to the class's own classloader covers embedded use cases
  (e.g. test harnesses).
- The return type uses an unchecked cast from `VertiqueComponentFactory<?>` to
  `VertiqueComponentFactory<? extends VertiqueApplicationComponent>`. The generics are erased at the
  SPI boundary; a component type that does not implement `VertiqueApplicationComponent` surfaces at
  runner build time, not at discovery.

---

## Extension Points

### VertxBuilderContributor

The `VertxBuilderContributor` SPI and its supporting types (`BootstrapContext`, `ContributorRunner`,
`ContributorFailureException`) live in `vertique-bootstrap` (package `dev.vertique.bootstrap`).
See `dev.vertique:vertique-bootstrap` for the full SPI reference, registration instructions,
ordering contract, example implementation, and test-seam details.

From the launcher's perspective: `VertiqueApplication.createVertxBuilder` calls
`ContributorRunner.discover()` to load all registered contributors, drives the chain via
`contributeAll`, and stores the runner for use by `BootstrapShutdown.runOnce()` on all stop and
failure paths. The launcher itself does not re-export the SPI — depend on `vertique-bootstrap`
directly when shipping a contributor.

---

## Dependencies

- **`vertique-bootstrap`** — `VertxBuilderContributor` SPI, `BootstrapContext`, `ContributorRunner`,
  `ContributorFailureException`. The bootstrap kernel is extracted here so bridge adapters can depend
  on it without pulling in the standalone launcher entrypoint.
- **`vertique-core`** — `OrderedExtension`, `ExtensionPhase` (ordering contract, used transitively
  via `vertique-bootstrap`), `VertiqueRuntime`, `VertiqueComponentFactory` (standalone entry seam).
- **`vertique-config-core`** — `BootstrapConfigLoader` (pre-`Vertx` config resolution),
  `BootstrapResult` (resolved tree + property sources), `ConfigPropertySource` (closed during
  shutdown).
- **`vertique-application`** — `VertiqueApplicationBootstrap` (host-neutral lifecycle runner),
  `VertiqueApplicationComponent` (the component interface the factory must produce),
  `VertiqueApplicationHandle` (returned on successful startup; drives teardown from
  `VertiqueBootstrapVerticle.stop()`).
- **`io.vertx:vertx-launcher-application`** — `VertxApplication` (the base class),
  `VertxApplicationHooks` (the hook interface), `HookContext`, and `ExitCodes`.
- **`io.vertx:vertx-core`** — `VertxBuilder`, `VertxOptions`, `JsonObject`, `AbstractVerticle`.
- **`org.slf4j:slf4j-api`** — structural diagnostics (class names, exit codes only; config contents
  are never logged).

This module orchestrates the full startup sequence but owns no application-domain logic. It must
never depend on Dagger graph modules (`vertique-rest-*`, `vertique-services`, `vertique-db`, or any
module that assumes an injected `Vertx` is already available).

---

## Related ADRs

- ADR-0084: Framework Extension-Ordering Contract —
  establishes `OrderedExtension` and `ExtensionPhase` as the canonical ordering contract;
  `VertxBuilderContributor` uses this comparator to sort contributors.
- ADR-0085: OrderedExtension Rolled Out Across Sorted Behavioral SPIs —
  documents the rollout of the ordering contract; `VertxBuilderContributor` follows the same
  comparator convention.
- ADR-0095: ServiceLoader as the Pre-DI Bootstrap Seam —
  records why ServiceLoader is used for contributor discovery rather than Dagger multibindings,
  and establishes the shutdown, ordering, and fail-fast contracts.
- ADR-0096: Bootstrap Config Relocation and Store Declarations —
  records the decision to relocate config resolution into the launcher (pre-`Vertx`, via a
  temporary bootstrap `Vertx`), the `config.stores` two-phase load model, precedence guarantees,
  and the `ConfigPropertySource` shutdown contract.
- ADR-0125: Bootstrap Kernel Extraction to `vertique-bootstrap` and SPI-FQN Move —
  records the decision to extract `VertxBuilderContributor`, `BootstrapContext`, `ContributorRunner`,
  and related types from this module into `vertique-bootstrap`, and the SPI filename change from
  `dev.vertique.launcher.*` to `dev.vertique.bootstrap.*`.
- ADR-0131: Standalone Entry via `verticleSupplier()` and ServiceLoader Factory Discovery —
  records why `verticleSupplier()` defaults to `VertiqueBootstrapVerticle`, why exactly-one
  ServiceLoader discovery is used for `VertiqueComponentFactory`, and the opt-out system property.
- ADR-0132: `@VertiqueApp` Annotation Processor — Generated Factory and SPI Registration —
  records the decision to use annotation-on-the-`@Component` with a by-name Dagger builder reference
  (no reflection) as the zero-boilerplate alternative to the hand-written factory path.
- ADR-0133: Jib Container Packaging over Maven Shade Fat-JAR —
  records why `@VertiqueApp` applications package via Jib rather than maven-shade; the uniform
  `VertiqueApplication` main class; and the no-lifecycle-bind rationale. See `docs/packaging.md`
  for the full packaging guide.
