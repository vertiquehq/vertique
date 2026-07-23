<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# vertique-bootstrap

> **Status:** Alpha
> **Package:** `dev.vertique.bootstrap`
> **Artifact:** `vertique-bootstrap`
> **Depends on:** `vertique-core`, `io.vertx:vertx-core`, `org.slf4j:slf4j-api`

Pre-Vertx bootstrap kernel. Provides the `VertxBuilderContributor` ServiceLoader SPI and the
machinery to discover, order, and drive it before a `Vertx` instance exists. Any framework module
that needs to customise `Vertx.builder()` — metrics factories, tracers, custom cluster managers —
implements this SPI without depending on `vertique-launcher` or any other application-entrypoint
module.

This module is the correct dependency for bridge adapters (e.g. Spring Boot or Quarkus integration
modules) that must participate in pre-Vertx wiring. It carries no standalone launcher, no Vert.x
application entrypoint, and no config-resolution machinery.

---

## When To Use It

Add `vertique-bootstrap` to a module that needs to hook into `Vertx.builder()` before the `Vertx`
instance is created. The module ships a `VertxBuilderContributor` implementation and registers it
via `META-INF/services/`. No application code changes are required — adding the dependency to the
classpath is the full integration.

`vertique-launcher` already depends on this module. Applications using `VertiqueApplication` as
their entry point receive the contribution chain automatically; the bootstrap module itself does not
need to be listed as an application-level dependency.

---

## Core Concepts

### The ServiceLoader seam

The Vertique framework is otherwise Dagger-first: every component in the dependency graph requires a
live `Vertx` instance. `VertxBuilderContributor` is the deliberate exception — it runs before the
Dagger graph exists and is therefore discovered via `java.util.ServiceLoader` rather than Dagger
multibinding. See ADR-0095 for the recorded
rationale.

### Contribution chain

`ContributorRunner` loads contributors from the classpath, sorts them by `OrderedExtension`
ordering (phase → priority → orderKey), and threads each contributor's returned `VertxBuilder`
into the next contributor. The chain is single-use: a given `ContributorRunner` instance drives
`contributeAll` exactly once. Calling it a second time throws `IllegalStateException`.

Contributors receive:
- **`BootstrapContext.config()`** — a defensive copy of the bootstrap configuration, so contributors
  cannot mutate each other's view.
- **`BootstrapContext.vertxOptions()`** — the live, shared `VertxOptions` instance. Mutations made
  by one contributor are immediately visible to subsequent contributors and take effect when the
  `Vertx` instance is built.

### Shutdown hooks

After a successful contribution, each contributor's `onShutdown()` is called in reverse
contribution order when the application stops. Only contributors that successfully completed their
`contribute` call receive an `onShutdown` callback — a contributor that threw or returned `null` is
excluded. `runShutdownHooks()` is idempotent; the second and subsequent calls are no-ops.

---

## Key Classes

### VertxBuilderContributor

The SPI interface. Implementations extend `OrderedExtension` (from `vertique-core`) and are
discovered via `ServiceLoader`. The two methods:

| Method | Contract |
|--------|----------|
| `contribute(VertxBuilder, BootstrapContext)` | Must return a non-null `VertxBuilder`. May return the received builder or a re-wrapped one. May mutate `context.vertxOptions()` as a side effect. Throwing any exception aborts startup. |
| `onShutdown()` | Default no-op. Called once in reverse contribution order on shutdown. Failures are logged and never rethrown. |

### BootstrapContext

Read-only view of bootstrap state shared across the contribution chain.

| Method | Contract |
|--------|----------|
| `config()` | Returns a **defensive copy** of the bootstrap config each time it is called. Mutations do not affect other contributors or the stored tree. Never null. |
| `vertxOptions()` | Returns the **live, shared** `VertxOptions` instance. Mutations are immediately visible to subsequent contributors. |

The asymmetry is intentional: config is read-only input (defensive copies prevent aliasing);
`VertxOptions` is a mutable accumulator that contributors cooperate on.

### ContributorRunner

Discovers, sorts, and drives the contributor chain.

| Method | Behaviour |
|--------|-----------|
| `ContributorRunner.discover()` | Static factory: loads contributors via `ServiceLoader`, sorts them with `OrderedExtension.comparator()`, and returns a runner over the immutable sorted list. |
| `ContributorRunner(List<VertxBuilderContributor>)` | Constructor seam: sorts a defensive copy of the provided list. Use this in tests to bypass ServiceLoader. |
| `contributeAll(VertxBuilder, BootstrapContext)` | Single-use: drives the chain and returns the final `VertxBuilder`. Throws `IllegalStateException` on a second call. Throws `ContributorFailureException` on any contributor failure. |
| `runShutdownHooks()` | Idempotent: runs `onShutdown()` on the contributed prefix in reverse order. Second and subsequent calls are no-ops. |

#### Invariants & Gotchas

- `contributeAll` is guarded by an `AtomicBoolean`; the first call runs the chain, any subsequent
  call throws `IllegalStateException` without invoking any contributor.
- `runShutdownHooks` is guarded independently by a second `AtomicBoolean`; both guards are
  independent and both must fire on the shutdown path.
- A contributor that throws is wrapped in a `ContributorFailureException` whose message includes the
  contributor's FQCN; the original exception is preserved as the cause.
- A contributor that returns `null` from `contribute` triggers a `ContributorFailureException`
  whose message states "returned null builder" — the chain aborts immediately.
- `contributedCount` tracks how many contributors completed successfully; `runShutdownHooks` confines
  its reverse walk to that prefix only.

### ContributorFailureException

Extends `ConfigurationException` (from `vertique-core`). Thrown by `contributeAll` when a
contributor throws an exception (cause is set) or returns a null builder (no cause). The message
always contains the contributor's fully-qualified class name.

---

## Extension Points

### VertxBuilderContributor (ServiceLoader SPI)

Register an implementation by creating a service descriptor file on the classpath:

```
META-INF/services/dev.vertique.bootstrap.VertxBuilderContributor
```

containing the fully-qualified class name of your implementation.

**Ordering:** contributors are sorted by `OrderedExtension.comparator()` — ascending `phase()`
(default `APPLICATION`), then ascending `priority()` (default `0`), then ascending `orderKey()`
(default the implementation class name) as a stable tie-break.

**Example — enabling Micrometer metrics before `Vertx` is created:**

```java
package com.example.telemetry;

import dev.vertique.bootstrap.BootstrapContext;
import dev.vertique.bootstrap.VertxBuilderContributor;
import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.VertxBuilder;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.backends.BackendRegistries;

public class MetricsContributor implements VertxBuilderContributor {

    @Override
    public VertxBuilder contribute(VertxBuilder builder, BootstrapContext context) {
        MicrometerMetricsOptions options = new MicrometerMetricsOptions()
                .setEnabled(true)
                .setRegistryName("default");
        return builder.withMetrics(options);
    }

    @Override
    public void onShutdown() {
        MeterRegistry registry = BackendRegistries.getDefaultNow();
        if (registry != null) {
            registry.close();
        }
    }
}
```

`src/main/resources/META-INF/services/dev.vertique.bootstrap.VertxBuilderContributor`:

```
com.example.telemetry.MetricsContributor
```

**Packaging note:** `@VertiqueApp` applications package via Jib, which operates on the unpacked
classpath — each JAR's `META-INF/services/` file remains separate and ServiceLoader finds them all.
No `ServicesResourceTransformer` is needed. Any custom packaging that bundles JARs into a fat-jar
via the Maven Shade Plugin must include `ServicesResourceTransformer` to merge `META-INF/services/`
entries; without it, only one contributor registration survives the shading step:

```xml
<transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
```

See `docs/packaging.md` for Jib configuration.

**Test seam:** use `new ContributorRunner(List<VertxBuilderContributor>)` in unit tests to wire
contributors directly, bypassing ServiceLoader. ServiceLoader registrations are classpath-global
within a module's test run, so the constructor seam avoids polluting parallel test runs.

---

## Dependencies

- **`vertique-core`** — `OrderedExtension` and `ExtensionPhase` (the ordering contract used to sort
  contributors); `ConfigurationException` (the supertype of `ContributorFailureException`).
- **`io.vertx:vertx-core`** — `VertxBuilder`, `VertxOptions`.
- **`org.slf4j:slf4j-api`** — shutdown hook failure logging (contributor class names only; config
  contents are never logged).

This module must never depend on Dagger, `vertique-launcher`, `vertique-config-core`, or any module
that assumes a live `Vertx` instance is available.

---

## Related ADRs

- ADR-0084: Framework Extension-Ordering Contract —
  establishes `OrderedExtension` and `ExtensionPhase`; `VertxBuilderContributor` uses this
  comparator to sort contributors.
- ADR-0085: OrderedExtension Rolled Out Across Sorted Behavioral SPIs —
  documents the rollout of the ordering contract; `VertxBuilderContributor` follows the same
  comparator convention.
- ADR-0095: ServiceLoader as the Pre-DI Bootstrap Seam —
  records why ServiceLoader is used for contributor discovery rather than Dagger multibindings, and
  establishes the shutdown, ordering, and fail-fast contracts.
- ADR-0125: Bootstrap Kernel Extraction to `vertique-bootstrap` and SPI-FQN Move —
  records the decision to extract the kernel from `vertique-launcher` into this module and the
  SPI filename change from `dev.vertique.launcher.*` to `dev.vertique.bootstrap.*`.
