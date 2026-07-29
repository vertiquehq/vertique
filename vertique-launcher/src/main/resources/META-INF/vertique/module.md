<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# vertique-launcher

> **Status:** Alpha
> **Package:** `dev.vertique.launcher`
> **Artifact:** `vertique-launcher`
> **Depends on:** `vertique-bootstrap`, `vertique-core`, `vertique-application`, `vertique-config-core`, `io.vertx:vertx-launcher-application`, `io.vertx:vertx-core`, `org.slf4j:slf4j-api`

Framework-owned application entrypoint. `VertiqueApplication` extends Vert.x's `VertxApplication`
and exists because Vert.x 5 requires builder-time wiring — metrics factories, tracers, and other
infrastructure must be passed to `Vertx.builder()` before the `Vertx` instance is created. That
seam is pre-Dagger by definition (every Dagger component in this codebase requires a live `Vertx`
instance to construct), so the bootstrap kernel (in `dev.vertique:vertique-bootstrap`) provides a
`java.util.ServiceLoader`-based SPI — `VertxBuilderContributor` — that any classpath dependency can
implement without the application touching its `main` method.

When no contributors are present on the classpath and no `vertx.options` section is declared,
`VertiqueApplication` behaves identically to `io.vertx.launcher.application.VertxApplication` with
respect to CLI parsing, `--conf`/`Main-Verticle` manifest resolution, and exit codes
(FR-LAUNCH-001 parity scope). The bootstrap config load still runs and the `vertx.options` overlay
hook is still installed — they are zero-cost no-ops in that case.

---

## When To Use It

Every Vertique application that deploys a verticle should use `VertiqueApplication` as its
`Main-Class` (or call `new VertiqueApplication(args).launch()` from its own thin main). Modules that
need to configure `Vertx.builder()` — telemetry, custom cluster managers, custom transport — ship a
`VertxBuilderContributor` implementation and register it via `META-INF/services/`. No application
code changes are required: adding the dependency is the integration.

---

## Core Concepts

### What runs before your code

`VertiqueApplication` performs the pre-`Vertx` work an application would otherwise have to write by
hand in `main`:

1. **CLI capture** — the raw `--options` JSON and the raw `--conf` value are captured (each
   normalised to an empty `JsonObject` when the flag is absent).
2. **Bootstrap config load** — the full config tree is resolved *before* `Vertx` exists, using a
   temporary `Vertx` instance: file directories, env, sys, any declared `config.stores`, and the
   `--conf` argument at highest precedence. On failure (unknown store type, failing declared store,
   I/O error) startup aborts with exit `11`. See `dev.vertique:vertique-config-core` for the full
   source-precedence chain.
3. **Contributor chain** — every registered `VertxBuilderContributor` runs in
   `OrderedExtension.comparator()` order (phase → priority → orderKey), each receiving the resolved
   config tree and threading its returned `VertxBuilder` into the next.
4. **Upstream option processing** — Vert.x applies cluster flags (`-cluster-host`,
   `-cluster-port`), `vertx.options.*` system properties, and metrics/tracer SPI conversions.
5. **`vertx.options` overlay** — the config tree's `vertx.options` section is merged on top of the
   processed options (see below). A malformed section aborts startup with exit `11`.
6. **`Vertx` built**, then the resolved config tree is installed as the verticle deployment config.
7. **Verticle deployed** — the framework-owned `VertiqueBootstrapVerticle` by default, or the
   `Main-Verticle` resolved from the manifest when the opt-out is set.

Two consequences an application can rely on:

- **The deployed verticle's `config()` is the canonical, fully-merged tree.** The Dagger
  `AppComponent` receives this tree; no separate `ConfigBootstrap.load()` call is needed.
- **Contributors observe the *pre-overlay* `VertxOptions`.** The `vertx.options` tree section is
  applied after the contributor chain, so a contributor's mutations survive the overlay — but a
  contributor cannot read the final effective options.

### Standalone entry

`VertiqueApplication.verticleSupplier()` returns `VertiqueBootstrapVerticle::new` by default, which
makes the upstream launcher bypass `Main-Verticle`/CLI verticle resolution entirely. A standalone
application therefore needs no `MainVerticle` and no `Main-Verticle` manifest entry — `java -jar`
works out of the box once a `VertiqueComponentFactory` is registered.

`VertiqueBootstrapVerticle` builds a `VertiqueRuntime` from the deployed `Vertx` and the canonical
merged config, discovers the application's `VertiqueComponentFactory` via `ServiceLoader`, and then
delegates to `VertiqueApplicationBootstrap.start(runtime, factory)` in
`dev.vertique:vertique-application`.

**Factory registration.** The SPI resource is
`META-INF/services/dev.vertique.core.VertiqueComponentFactory`, and **exactly one** provider must be
registered. Two supported ways to produce that entry:

- **`@VertiqueApp` (zero boilerplate)** — inherit `vertique-app-parent`, declare the application
  runtime capability, and place `dev.vertique.application.VertiqueApp` on the `@Component`
  interface. Custom-parent applications use the BOM plus the `vertique-codegen-all` recipe in
  `docs/packaging.md`. `dev.vertique:vertique-codegen-application` generates the factory class and
  the `META-INF/services` entry.
- **Manual** — write a `VertiqueComponentFactory` implementation and add it to the SPI file by hand.

Both paths satisfy the same exactly-one rule. All framework examples use `@VertiqueApp` on their
`AppComponent`, include `CoreLifecycleStepsModule`, and package as OCI container images via Jib;
`vertique-example-hello` and `vertique-example-services` are the simplest reference implementations.
See `docs/packaging.md` for container build commands, `exec-maven-plugin` local-run setup, and the
migration recipe from maven-shade.

**Opt-out.** Set `-Dvertique.bootstrap.verticle=false` (case-insensitive) to make
`verticleSupplier()` return `null`, restoring the standard `Main-Verticle` manifest resolution. Use
this when an application manages its own main verticle and needs to coexist with `vertique-launcher`
on the classpath.

### The `vertx.options` overlay

When the resolved config tree has no `vertx → options` section (or the section is empty), the
original `VertxOptions` instance is passed through unchanged — a zero-cost path that also preserves
programmatic options with no JSON representation.

When the section is present, an effective `VertxOptions` is produced with this precedence, lowest to
highest:

| Layer | Source |
|---|---|
| 1 (lowest) | Base options after Vert.x has applied cluster flags, `vertx.options.*` system properties, and metrics/tracer SPI conversions |
| 2 | `vertx.options` section of the resolved config tree |
| 3 | CLI `--options` JSON, re-applied so explicit CLI overrides survive the tree merge |
| 4 (highest) | `vertx.options.*` system properties, re-applied last so emergency runtime overrides need no config edit |

Layers 1–3 are merged and validated in a single construction and are **fail-fast**: a malformed value
in the tree or in `--options` aborts startup with exit `11`. Layer 4 is applied one key at a time and
is **warn-and-skip**: a system property whose value cannot be coerced (for example
`-Dvertx.options.maxWorkerExecuteTimeUnit=NOT_A_UNIT`) is logged at `WARN` — naming the property key
and the failure class, never the value — and ignored; startup continues. This matches upstream
`configureFromSystemProperties` behaviour.

### Shutdown

The shutdown sequence runs **exactly once** across every stop and failure path:

1. **Contributor `onShutdown()` hooks** — in reverse contribution order, confined to the
   successfully-contributed prefix.
2. **Bootstrap property-source `close()`** — each `ConfigPropertySource` in reverse declaration
   order. Individual close failures are caught, logged at error level (naming the source), and never
   rethrown, so the remaining sources are always closed.

| Path | Hook |
|---|---|
| Normal stop / SIGTERM | `afterVertxStopped` |
| Vert.x failed to stop | `afterFailureToStopVertx` |
| Deploy failure | `afterFailureToDeployVerticle` (then Vert.x close → `afterVertxStopped`) |
| Vert.x start failure (before deploy) | `afterFailureToStartVertx` |
| Bootstrap or contributor failure | `launch()`, after the upstream launcher returns |

---

## Key Classes

### VertiqueApplication

Extends `io.vertx.launcher.application.VertxApplication` and implements `VertxApplicationHooks` as
its own delegate.

| Member | Contract |
|---|---|
| `VertiqueApplication(String[] args)` | Production constructor: prints usage and calls `System.exit` on failure. |
| `VertiqueApplication(String[] args, boolean printUsageOnFailure, boolean exitOnFailure)` | `protected` test and embedded-use seam. Set `exitOnFailure = false` to inspect the returned exit code instead of exiting the JVM. |
| `static void main(String[] args)` | Constructs the production instance and calls `launch()`. This is the `Main-Class`. |
| `int launch()` | Runs the application and owns the exit decision. Returns the exit code. |
| `final void runShutdownSequence()` | Runs contributor hooks then property-source closes, exactly once. Idempotent. |

Minimal embedding:

```java
package com.example.app;

import dev.vertique.launcher.VertiqueApplication;

public final class Main {

    public static void main(String[] args) {
        new VertiqueApplication(args).launch();
    }
}
```

**Exit-code ownership.** The upstream `super.launch()` is always invoked with `exitOnFailure=false`,
so the JVM stays alive regardless of the upstream result. `VertiqueApplication.launch()` then detects
any stored startup failure, logs it, runs the shutdown sequence, remaps the code to `11`, calls
`System.exit(code)` when `exitOnFailure` is `true` and the code is non-zero, and returns the code.

#### Invariants & Gotchas

- **Subclassing contract.** A subclass that overrides `afterVertxOptionsParsed`, `afterConfigParsed`,
  `createVertxBuilder`, or `beforeStartingVertx` **must** call the corresponding `super` method and
  return its result. Skipping `super` breaks CLI options capture, config capture, the contributor
  chain, or the `vertx.options` overlay — silently, with no startup error.
- **Config contents are never logged.** Only structural diagnostics (contributor class names, exit
  codes, property keys) are emitted. A `VertxBuilderContributor` must hold the same line: never log
  `BootstrapContext.config()` contents, because the tree may carry resolved secret values.
- **`vertx.close()` does not run the shutdown sequence.** Closing `Vertx` directly, outside the
  launcher shutdown pipeline, skips contributor hooks and property-source closes. Call
  `runShutdownSequence()` explicitly after a programmatic close.
- **Options that have no JSON representation are lost when the `vertx.options` section is present.**
  The effective instance round-trips through JSON on that path. `MetricsOptions` and `TracingOptions`
  retain a copy of their source JSON, so JSON-representable provider config (for example
  `MicrometerMetricsOptions`, `OpenTelemetryOptions`) survives. Programmatic state that is not
  JSON-representable — a pre-built `MeterRegistry` instance, say — does not. A contributor that sets
  such state should rely on the absent-section path or re-apply the state in a post-build hook.

---

### VertiqueBootstrapVerticle

The framework-owned standalone entry verticle, supplied by `VertiqueApplication.verticleSupplier()`.
The class is `final`; applications do not subclass it and normally never name it.

`start(Promise<Void>)`:

1. Builds `VertiqueRuntime.of(vertx, config())` — `config()` is the canonical merged tree.
2. Discovers exactly one `VertiqueComponentFactory` from the classpath; a discovery failure fails the
   start promise with the original cause.
3. Calls `VertiqueApplicationBootstrap.start(runtime, factory)` and completes or fails the start
   promise with that result.

`stop(Promise<Void>)` calls `VertiqueApplicationHandle.shutdown()` (idempotent, reverse-order
teardown) when startup produced a handle, and is a no-op when it did not. **`Vertx` is never closed
here** — the launcher owns the `Vertx` lifecycle and closes it after this verticle stops.

---

## Extension Points

### VertxBuilderContributor

The `VertxBuilderContributor` SPI and its supporting types — `BootstrapContext`, `ContributorRunner`,
`ContributorFailureException` — are defined in `dev.vertique:vertique-bootstrap` (package
`dev.vertique.bootstrap`). Depend on that artifact directly when shipping a contributor; the launcher
does not re-export the SPI. Its module document holds the full SPI reference: registration, the
ordering contract, `BootstrapContext` semantics, an example implementation, and the test seams.

From the launcher's side of the seam: `VertiqueApplication.createVertxBuilder` discovers the
registered contributors, drives the chain, and retains the runner so contributor `onShutdown()` hooks
run on every stop and failure path.

---

## Configuration

| Knob | Kind | Effect |
|---|---|---|
| `--conf <json-or-path>` | CLI flag | Overlaid at highest precedence onto the resolved config tree. |
| `--options <json>` | CLI flag | `VertxOptions` JSON. Applied above the `vertx.options` tree section, below `vertx.options.*` system properties. |
| `vertx.options` | Config-tree section | `VertxOptions` fields set from any config source. Absent or empty means the original options instance is used unchanged. |
| `vertx.options.*` | System property | Highest-precedence `VertxOptions` override. Applied per key with warn-and-skip on coercion failure. |
| `vertique.bootstrap.verticle` | System property | `false` (case-insensitive) disables the framework bootstrap verticle and restores `Main-Verticle`/CLI verticle resolution. Any other value, or absent, keeps the default. |
| `config.stores` | Config-tree section | Declared config stores resolved during the bootstrap load. Owned by `dev.vertique:vertique-config-core`. |

---

## Failures, Constraints, and Common Mistakes

**Exit codes:**

| Code | Meaning |
|---|---|
| `0` | Verticle deployed; Vert.x event loops keep the JVM alive |
| `11` (`ExitCodes.VERTX_INITIALIZATION`) | Bootstrap config load failed (unknown store type, failing declared store, I/O error), contributor discovery failed, a `VertxBuilderContributor` failed during contribution, **or** the `vertx.options` overlay failed (malformed section). The shutdown sequence runs before exit. |
| `15` (`ExitCodes.VERTX_DEPLOYMENT`) | The main verticle failed to deploy — including the standalone-entry factory-discovery failures below. |

**Component-factory discovery:**

| Providers found | Outcome |
|---|---|
| Zero | `IllegalStateException` naming the SPI resource and the discovery classloader |
| Exactly one | Normal startup |
| More than one | `IllegalStateException` listing every discovered fully-qualified name, plus the SPI resource and classloader |

A provider that *is* discovered but whose `build(VertiqueRuntime)` returns `null` or a component that
is not a `VertiqueApplicationComponent` fails with an `IllegalStateException` naming the provider and
the expected type. `ServiceLoader` erases the component type parameter, so this is checked when the
component is built rather than at discovery.

Every one of these failures reaches the launcher as a deploy failure (exit `15`). The messages carry
class names and classloader identity only — never resolved config values.

**Common mistakes:**

- Overriding a launcher hook without calling `super` (see the subclassing contract above).
- Expecting `vertx.close()` to run contributor shutdown hooks — it does not; call
  `runShutdownSequence()`.
- Registering two `VertiqueComponentFactory` providers, usually by adding a manual SPI file to an
  application that also uses `@VertiqueApp`. Pick one path.
- Setting programmatic, non-JSON-representable `VertxOptions` state in a contributor while also
  declaring a `vertx.options` config section — the state is dropped by the overlay's JSON round-trip.
- Adding a Dagger graph module (`vertique-rest-*`, `vertique-services`, `vertique-db-*`, or anything
  that assumes an injected `Vertx` already exists) as a dependency of a `VertxBuilderContributor`.
  The contributor seam is pre-`Vertx` and pre-Dagger by construction.

---

## Dependencies

- **`vertique-bootstrap`** — `VertxBuilderContributor` SPI, `BootstrapContext`, `ContributorRunner`,
  `ContributorFailureException`. The bootstrap kernel is a separate artifact so bridge adapters can
  depend on it without pulling in the standalone launcher entrypoint.
- **`vertique-core`** — `VertiqueRuntime` and `VertiqueComponentFactory` (the standalone entry seam),
  `JsonConfigPaths` (config-section navigation), and the `OrderedExtension`/`ExtensionPhase` ordering
  contract used to sort contributors.
- **`vertique-application`** — `VertiqueApplicationBootstrap` (host-neutral lifecycle runner),
  `VertiqueApplicationComponent` (the component interface the factory must produce),
  `VertiqueApplicationHandle` (returned on successful startup; drives teardown from
  `VertiqueBootstrapVerticle.stop()`), and the `VertiqueApp` annotation.
- **`vertique-config-core`** — `BootstrapConfigLoader` (pre-`Vertx` config resolution),
  `BootstrapConfigLoader.BootstrapResult` (resolved tree plus property sources),
  `ConfigPropertySource` and `ConfigPropertySources` (closed during shutdown).
- **`io.vertx:vertx-launcher-application`** — `VertxApplication` (the base class),
  `VertxApplicationHooks` (the hook interface), `HookContext`, and `ExitCodes`.
- **`io.vertx:vertx-core`** — `VertxBuilder`, `VertxOptions`, `JsonObject`, `AbstractVerticle`.
- **`org.slf4j:slf4j-api`** — structural diagnostics only; config contents are never logged.

This module orchestrates the full startup sequence but owns no application-domain logic. It must
never depend on Dagger graph modules, or on any module that assumes an injected `Vertx` is already
available.
