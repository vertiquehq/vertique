<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Application Module

> **Status:** Stable
> **Package:** `dev.vertique.application`
> **Artifact:** `vertique-application`
> **Depends on:** core, deploy

The `vertique-application` module provides the host-neutral lifecycle runner for Vertique
applications. It drives the eight-phase `LifecyclePhase` lifecycle — running startup steps and
deploying verticles in phase order — and produces a `VertiqueApplicationHandle` that supports
idempotent shutdown. The module has no compile dependency on `vertique-launcher` or on any
embedding host (Spring, Quarkus).

This module does not contain configuration logic, Jackson wiring, or service dispatch; those
concerns enter the lifecycle through Dagger multibinding contributions from their owning modules
(`CoreLifecycleStepsModule`, `DispatchModule`).

---

## When To Use It

Include `vertique-application` whenever an application wants the framework to manage its lifecycle
automatically:

- Standalone Vert.x applications replacing manual `MainVerticle.start()` choreography
- Future embedding host bridges (Spring Boot, Quarkus) that drive the same Vertique lifecycle from
  their own startup hooks

`vertique-application` is the framework's single supported lifecycle orchestrator — there is no
parallel, equally-supported path. A custom verticle may replace the framework-owned host verticle
(`VertiqueBootstrapVerticle`, from `dev.vertique:vertique-launcher`) as the Vert.x entry point, but
it must still delegate application startup and shutdown to
`VertiqueApplicationBootstrap.start(runtime, factory)` and the returned handle's `shutdown()`. The
step and verticle deployment APIs described in this document are the runner's primitives — they
exist so the runner can drive phase-ordered startup and teardown, not as a parallel,
application-facing lifecycle that a hand-rolled host can reimplement instead.

---

## Core Concepts

### The passive component contract

`VertiqueApplicationComponent` is an interface that a Dagger `@Component` extends. It is purely
a passive DI boundary: it exposes the application's startup step set, shutdown step set, and
verticle deployment manager — nothing more. The component does not start itself; it is driven by
the runner.

```java
// Application Dagger component
@Singleton
@Component(modules = { VertxModule.class, CoreLifecycleStepsModule.class, AppModule.class, ... })
interface AppComponent extends VertiqueApplicationComponent {
    // framework methods inherited from VertiqueApplicationComponent
    // application-specific accessors as needed
}
```

### The runner

`VertiqueApplicationBootstrap.start(runtime, factory)` builds the component and drives the
lifecycle. For each `LifecyclePhase` in declaration order it:

1. Runs that phase's `ApplicationStartupStep`s sequentially, ordered by
   `LifecycleOrdered.comparator()` (phase → priority → orderKey).
2. If the phase is a verticle-subset phase (`isVerticlePhase()` is true), deploys that phase's
   verticles via `VerticleDeploymentManager.deployPhase(phase)`.

Steps always precede verticles within a phase.

On any failure the runner tears down: runs the shutdown steps for completed phases in reverse
order (best-effort — each step's failure is logged and swallowed), then undeploys any deployed
verticles in reverse phase order. The original cause is propagated. The `Vertx` instance is never
closed — the caller owns it.

### Contributed lifecycle steps

Framework modules contribute their own lifecycle work via Dagger `@IntoSet` multibinding:

| Step | Phase | Module | What it does |
|------|-------|--------|--------------|
| `JsonSystemProfileInstallStep` | `CONFIGURE` | `JsonRuntimeModule` | Installs the `json.systemProfile` profile's `ObjectMapper` as the process JSON codec's mapper |
| `ComposeValidationStep` | `VALIDATE` | `CoreLifecycleStepsModule` | Materializes the `Set<ComposeValidator>` multibinding, forcing construction of every validator |
| `FlywayMigrationStartupStep` | `MIGRATE` | `DbFlywayModule` | Runs `MigrationRunner.migrate(vertx)` — schema is migrated before INFRA/SERVICES/EDGE verticles deploy |
| `ServiceDeploymentStartupStep` | `SERVICES` | `DispatchModule` | Deploys all service verticles via `ServiceDeploymentManager.deployAll()` |
| `ServiceDeploymentShutdownStep` | `SERVICES` | `DispatchModule` | Undeploys service verticles (deregister-first) via `ServiceDeploymentManager.undeployAll()` |

Applications do not call these steps directly; the runner drives them automatically.

### Host-owned Vertx

The `Vertx` instance flows into the runner via `VertiqueRuntime` and is never closed by the
framework. The standalone launcher creates `Vertx` before calling `start()` and closes it after
the handle's shutdown future completes. An embedding host bridge likewise closes `Vertx` at host
shutdown time, independently of the application framework teardown.

### Standalone entry

For standalone Vert.x applications, the launcher's `VertiqueBootstrapVerticle` (in
`vertique-launcher`) is the standard consumer of `VertiqueApplicationBootstrap`. It translates the
Vert.x verticle lifecycle (`start` / `stop`) into runner calls: it builds `VertiqueRuntime` from
its `config()`, discovers the application's `VertiqueComponentFactory` via ServiceLoader, then
calls `VertiqueApplicationBootstrap.start(runtime, factory)` and stores the returned handle for
teardown. Applications do not call `VertiqueApplicationBootstrap` directly when using the
standalone launcher.

The `VertiqueComponentFactory` can be registered in two ways:

- **`@VertiqueApp` (zero-boilerplate)** — inherit `vertique-app-parent`, declare
  `vertique-application` as a runtime dependency, and place `@VertiqueApp` on the `@Component`
  interface. The `@Component` must include `VertxModule` directly or transitively through a
  component module's `@Module(includes = …)` graph — for example, a component naming
  `dev.vertique.starter.core.CoreApplicationModule` satisfies this because `CoreApplicationModule`
  includes `VertxModule`. Custom-parent applications use the BOM plus `vertique-codegen-all` recipe
  in `docs/packaging.md`. The `vertique-codegen-application` processor owns factory-class and
  `META-INF/services` generation.
- **Manual (explicit)** — write a `VertiqueComponentFactory` implementation and register it in
  `META-INF/services/dev.vertique.core.VertiqueComponentFactory` by hand. Both paths are
  supported simultaneously; only one may be registered per application (exactly-one rule).

See `dev.vertique:vertique-launcher` for the full standalone entry sequence, factory discovery
rules, and the opt-out system property. For container and local-run packaging, see `docs/packaging.md`.

---

## Key Classes

### `VertiqueApplicationComponent`

Marker interface for the application's Dagger component. An application's `@Component` extends
this interface so the runner can drive it without knowing the concrete component type.

```java
public interface VertiqueApplicationComponent {
    Set<ApplicationStartupStep> startupSteps();
    Set<ApplicationShutdownStep> shutdownSteps();
    VerticleDeploymentManager verticleDeploymentManager();
}
```

- `startupSteps()` — the full multibinding set contributed by all included Dagger modules
- `shutdownSteps()` — the full multibinding set for teardown; may be empty
- `verticleDeploymentManager()` — the manager used for `deployPhase()` and `undeployAll()`

#### Invariants & Gotchas

- The runner calls `startupSteps()` and `shutdownSteps()` exactly once, immediately after the
  component is built, and works from an immutable snapshot of each. An unscoped or dynamic
  provider therefore cannot change the step sets mid-startup: every phase reads the same
  partition, and teardown runs against the same shutdown set. A provider that throws while the
  multibinding set is materialized fails the returned future rather than escaping as a
  synchronous throw.
- A component that does not include `CoreLifecycleStepsModule` will have no CONFIGURE or VALIDATE
  steps. The multibinding declared in `DeployerModule` provides an empty-by-default `Set`, so the
  build does not fail — but Jackson will not be configured and compose validators will not run.

---

### `VertiqueApplicationBootstrap`

Non-instantiable class with the single static entry point.

```java
public static <C extends VertiqueApplicationComponent> Future<VertiqueApplicationHandle<C>>
    start(VertiqueRuntime runtime, VertiqueComponentFactory<C> factory)
```

**Usage — standalone application:**

```java
VertiqueComponentFactory<AppComponent> factory = rt ->
    DaggerAppComponent.builder()
        .vertxModule(new VertxModule(rt.vertx(), rt.config()))
        .build();

VertiqueApplicationBootstrap.start(VertiqueRuntime.of(vertx, config()), factory)
    .onSuccess(handle -> {
        // application is fully started; keep the handle for shutdown
    })
    .onFailure(cause -> {
        // startup failed; teardown already ran; close Vertx
        vertx.close();
    });
```

**Execution order (per phase in `LifecyclePhase` declaration order):**

```
CONFIGURE  → JsonSystemProfileInstallStep (from JsonRuntimeModule, when included)
VALIDATE   → ComposeValidationStep (from CoreLifecycleStepsModule)
MIGRATE    → FlywayMigrationStartupStep (from DbFlywayModule, when included)
BOOTSTRAP  → deploy BOOTSTRAP-phase verticles
INFRA      → deploy INFRA-phase verticles (management, health)
SERVICES   → ServiceDeploymentStartupStep (from DispatchModule) → deploy SERVICES-phase verticles
EDGE       → deploy EDGE-phase verticles (HTTP, WebSocket)
AFTER_START → (no built-in steps; apps contribute post-start notifications etc. here)
```

#### Invariants & Gotchas

- The factory's `build()` call and the step-set snapshot are wrapped in a `try/catch`; a thrown
  exception fails the returned future without running teardown (nothing to tear down).
- `runtime` and `factory` must not be `null`. A `null` factory does not throw out of `start()`:
  the resulting `NullPointerException` is caught alongside any other build failure and comes back
  as a failed future, so callers always handle failure the same way.

---

### `VertiqueApplicationHandle`

Returned by `VertiqueApplicationBootstrap.start()` on success. Carries the built component, the
runtime, and an idempotent `shutdown()` method.

```java
public final class VertiqueApplicationHandle<C extends VertiqueApplicationComponent> {
    public C component();
    public VertiqueRuntime runtime();
    public Future<Void> shutdown();   // idempotent; memoized
}
```

- `component()` — the Dagger component built by the factory, returned as the application's own
  component type `C`; useful for accessing application services after startup without a cast
- `runtime()` — the `VertiqueRuntime` passed to `start()`
- `shutdown()` — the first call runs reverse-order teardown and memoizes the resulting future;
  every later call returns that same future

**Teardown order:**

1. Each contributed `ApplicationShutdownStep` whose phase is one whose startup work completed, in
   reverse `LifecycleOrdered.comparator()` order
2. If any verticle-subset phase was deployed: `verticleDeploymentManager().undeployAll()`

Each shutdown step is best-effort: a `stop()` failure or thrown exception is logged and swallowed
so it never masks the original startup failure or aborts subsequent teardown steps.

#### Invariants & Gotchas

- `shutdown()` never closes `Vertx`. The caller must close `Vertx` after the returned future
  completes.
- `shutdown()` is fully idempotent and safe to call from multiple threads: the first call
  memoizes the teardown future and every other caller — including a concurrent one — receives
  that same future. A second caller therefore observes success only once teardown has actually
  settled, never while it is still running.
- The handle captures the set of phases whose startup steps all completed. A shutdown step runs
  only when its phase is in that set, which bounds teardown to what was actually started. A phase
  whose startup steps failed part-way does not get its shutdown step run; a phase whose steps all
  succeeded but whose verticle deployment then failed does.

---

## Extension Points

### `ApplicationStartupStep` (from `vertique-core`)

Non-verticle startup work contributed to the lifecycle via Dagger `@IntoSet` multibinding.

```java
public interface ApplicationStartupStep extends LifecycleOrdered {
    Future<Void> start();
}
```

`phase()` determines which lifecycle phase runs this step. Steps in non-verticle phases
(`CONFIGURE`, `VALIDATE`, `MIGRATE`, `AFTER_START`) run without any verticle deployment.
Steps in verticle-subset phases (`BOOTSTRAP`, `INFRA`, `SERVICES`, `EDGE`) run before that
phase's verticles are deployed.

**Contributing a step (Dagger wiring):**

```java
// Example: contributing a custom MIGRATE-phase step
@Provides @Singleton @IntoSet
static ApplicationStartupStep myMigrateStep(MyMigrationStep step) {
    return step;
}
```

```java
// The step itself — DbFlywayModule contributes FlywayMigrationStartupStep using this pattern
@Singleton
public final class MyMigrationStep implements ApplicationStartupStep {
    private final MigrationRunner runner;
    private final Vertx vertx;

    @Inject MyMigrationStep(MigrationRunner runner, Vertx vertx) {
        this.runner = runner;
        this.vertx = vertx;
    }

    @Override public LifecyclePhase phase() { return LifecyclePhase.MIGRATE; }
    @Override public Future<Void> start() {
        return runner.migrate(vertx).mapEmpty();
    }
}
```

#### Invariants & Gotchas

- When two steps share the same `phase()` and `priority()`, `orderKey()` (default: FQCN) provides
  a stable alphabetical tie-break. Register steps as lambdas only when their relative order is
  unimportant; otherwise give them explicit `priority()` overrides or a stable `orderKey()`.
- A step that throws from `start()` causes the runner to abort and tear down. The exception is
  the original failure cause.

---

### `ApplicationShutdownStep` (from `vertique-core`)

Paired teardown work for a startup step. Contributed via `@IntoSet` and run in reverse lifecycle
order during `VertiqueApplicationHandle.shutdown()` or startup-failure teardown.

```java
public interface ApplicationShutdownStep extends LifecycleOrdered {
    Future<Void> stop();
}
```

Shutdown steps run best-effort — failures are logged and swallowed to avoid masking startup
failures. A module that requires guaranteed cleanup must handle errors within `stop()` itself.

---

### `ComposeValidator` (from `vertique-core`)

Marker interface for constructible-as-validation types. A class that implements `ComposeValidator`
and declares required bindings in its `@Inject` constructor proves those bindings are present at
construction time (compile-time via Dagger, or startup-time if the binding is conditional).

```java
public interface ComposeValidator {}
```

`ComposeValidationStep` materializes the `Set<ComposeValidator>` multibinding in the `VALIDATE`
phase, which forces Dagger to construct every contributed validator. A missing binding is a Dagger
compile error; a violated invariant throws `IllegalStateException` from the constructor.

**Contributing a compose validator:**

```java
// The validator: proves MyRequiredService is bound
@Singleton
public final class MyModuleComposeValidator implements ComposeValidator {
    @Inject
    public MyModuleComposeValidator(MyRequiredService service) {
        Objects.requireNonNull(service, "MyRequiredService must be bound");
    }
}

// Wiring: contributed @IntoSet
@Provides @Singleton @IntoSet
static ComposeValidator myModuleComposeValidator(MyModuleComposeValidator v) {
    return v;
}
```

For the validator to run, the application `@Component` must include `CoreLifecycleStepsModule`
(which declares the `@Multibinds Set<ComposeValidator>` empty default and contributes
`ComposeValidationStep`).

---

## Dependencies

- **`vertique-core`** — `VertiqueRuntime`, `VertiqueComponentFactory`, `LifecyclePhase`,
  `LifecycleOrdered`, `ApplicationStartupStep`, `ApplicationShutdownStep`, `ComposeValidator`
- **`vertique-deploy`** — `VerticleDeploymentManager` (drives `deployPhase()` and `undeployAll()`)

The module has no compile dependency on `vertique-launcher`, `vertique-services`,
`vertique-workflow`, or any host framework. Lifecycle step _contributions_ come from other modules
at Dagger wiring time; the runner only sees the merged multibinding sets.
