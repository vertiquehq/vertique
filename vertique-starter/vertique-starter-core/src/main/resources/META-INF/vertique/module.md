<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Starter Core Module

> **Status:** Alpha
> **Package:** `dev.vertique.starter.core`
> **Artifact:** `vertique-starter-core`
> **Depends on:** application, core, config-core, deploy

The `vertique-starter-core` module publishes one public Dagger aggregate,
`CoreApplicationModule`, that composes the host-neutral lifecycle foundation every Vertique
application needs. An application names the aggregate in its `@Component` instead of repeating the
same framework module list in every generated application.

This module is a composition surface only. It declares no bindings of its own, contributes no
host, transport, test, or generated code, and adds no runtime behavior beyond what the modules it
includes already provide.

---

## When To Use It

Install `vertique-starter-core` in any application whose Dagger component needs the framework
lifecycle foundation but no HTTP, event-bus, or persistence layer — for example a headless job
runner or a host bridge fixture.

Applications that additionally need REST or event-bus service capabilities install the matching
application starter, which itself includes this module; they do not list `CoreApplicationModule`
a second time.

---

## Core Concepts

### The aggregate is exactly four modules

`CoreApplicationModule` includes exactly:

| Included module | Artifact | What it brings |
|---|---|---|
| `dev.vertique.core.VertxModule` | `vertique-core` | the `Vertx` instance and the root `@VertxConfig` `JsonObject` |
| `dev.vertique.config.parser.ConfigParsingModule` | `vertique-config-core` | the canonical `ConfigParser` and its dedicated config `ObjectMapper` |
| `dev.vertique.deploy.DeployerModule` | `vertique-deploy` | the `VerticleDeployment`, `ApplicationStartupStep`, and `ApplicationShutdownStep` multibinding sets |
| `dev.vertique.core.lifecycle.CoreLifecycleStepsModule` | `vertique-core` | the `CONFIGURE` Jackson step, the `VALIDATE` compose step, and the `ComposeValidator` multibinding |

Dagger applies `@Module(includes = …)` recursively, so a component that names
`CoreApplicationModule` reaches all four transitively. `VertxModule` is stateful and therefore
remains a generated-builder input: the generated application factory still calls
`.vertxModule(new VertxModule(runtime.vertx(), runtime.config()))`.

### Membership is a compatibility surface

Both the included-module membership above and this module's direct dependency ledger are
release-line compatibility surfaces. Consumers may rely on the aggregate resolving the bindings
those four modules declare, and on the aggregate pulling in nothing beyond its declared ledger.
Changes to either are treated as compatibility-affecting, not as internal refactoring.

### What applications still own

The starter deliberately stops at composition. Applications remain responsible for:

- **Deployment entries** — every `@Provides @IntoSet VerticleDeployment` an application deploys.
  The starter contributes none; `DeployerModule` only declares the empty-by-default set.
- **Generated modules** — the annotation-processor output module (JAX-RS resources, services,
  and so on) is named explicitly in the component.
- **Launcher choice** — `vertique-launcher` is not a dependency of this module.
- **Test libraries** — `vertique-application-test`, JUnit, REST Assured, and Testcontainers stay
  explicit test-scope dependencies of the application.

---

## Key Classes

### `CoreApplicationModule`

Public abstract Dagger module. It declares no constructor, field, method, nested type, or scope —
downstream components name the class, and neither Dagger nor application code instantiates it.

```java
@VertiqueApp
@Singleton
@Component(
        modules = {
            CoreApplicationModule.class,
            AppModule.class,
            GeneratedServicesModule.class
        })
interface AppComponent extends VertiqueApplicationComponent {}
```

`AppModule` is the application-owned module contributing its deployment entries;
`GeneratedServicesModule` stands for whichever module the Vertique annotation processors generate
for that application.

---

## Extension Points

This module publishes no extension point of its own. Extend the application through the
multibindings its included modules declare — `Set<VerticleDeployment>`,
`Set<ApplicationStartupStep>`, `Set<ApplicationShutdownStep>` (from `dev.vertique:vertique-deploy`)
and `Set<ComposeValidator>` (from `dev.vertique:vertique-core`) — by contributing `@Provides
@IntoSet` methods from an application-owned module.

---

## Common Mistakes

- **Expecting the starter to supply a launcher.** It does not depend on
  `dev.vertique:vertique-launcher`. A standalone application still declares the launcher itself and
  runs through its bootstrap verticle.
- **Expecting the starter to deploy anything.** No `VerticleDeployment` is contributed here, so an
  application that names only `CoreApplicationModule` starts with an empty deployment set and
  serves no HTTP or management endpoint.
- **Expecting the generated module to be included.** The annotation-processor output module is
  never reachable from a framework aggregate; it must be listed in the component explicitly.
- **Listing `VertxModule` again alongside the starter.** It is already reached transitively;
  re-listing it is redundant, and omitting the generated `.vertxModule(…)` builder call is what
  actually breaks a component.
- **Adding a second application starter.** REST and services starters already include this module;
  naming both an application starter and `CoreApplicationModule` adds nothing.

---

## Dependencies

- **`dev.vertique:vertique-application`** — the `VertiqueApplicationComponent` lifecycle contract
  that consuming components extend, keeping the starter and the runner on one release line
- **`dev.vertique:vertique-core`** — `VertxModule` and `CoreLifecycleStepsModule`
- **`dev.vertique:vertique-config-core`** — `ConfigParsingModule`
- **`dev.vertique:vertique-deploy`** — `DeployerModule`
- **`com.google.dagger:dagger`** — the `@Module` annotation itself

The ledger above is exact: the module declares no other direct dependency, and in particular no
REST, services, management, database, launcher, test, or code-generation artifact.

---

## Verification

`CoreApplicationModule` is proven by the starter family's integration-test harness, which compiles
a real `@VertiqueApp` component naming only this aggregate, builds it through the generated
application factory, and asserts that the lifecycle step sets and the deployment manager resolve.
A dependency fixture materializes the compile and runtime classpaths and fails when the direct
ledger drifts or a launcher, test, or code-generation artifact leaks in.

---

## Related ADRs

Decision: ADR-0187 — Static Starter Aggregates and Separate Archetypes.
