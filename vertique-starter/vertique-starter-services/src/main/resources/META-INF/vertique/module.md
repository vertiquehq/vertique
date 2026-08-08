<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Starter Services Module

> **Status:** Alpha
> **Package:** `dev.vertique.starter.services`
> **Artifact:** `vertique-starter-services`
> **Depends on:** starter-core, management, services

The `vertique-starter-services` module publishes one public Dagger aggregate,
`ServicesApplicationModule`, that composes the headless event-bus services foundation on top of the
core application starter. An application names the aggregate in its `@Component` instead of
repeating the dispatch and management module list in every generated application.

This module is a composition surface only. It declares no bindings of its own, contributes no
deployment entry, transport, test, or generated code, and adds no runtime behavior beyond what the
modules it includes already provide.

---

## When To Use It

Install `vertique-starter-services` in any application whose surface is the event bus rather than
HTTP: services declared with `@ServiceContract` and dispatched through `ServiceClientFactory`. It
already includes `CoreApplicationModule`, so an application that names this aggregate does not also
name the core starter.

The starter is headless — it brings no HTTP server, no JAX-RS routing, and no REST security. An
application that also serves an HTTP API installs `vertique-starter-rest` instead; a service
application that needs nothing beyond the lifecycle foundation installs `vertique-starter-core`.

---

## Core Concepts

### The aggregate is exactly three modules

`ServicesApplicationModule` includes exactly:

| Included module | Artifact | What it brings |
|---|---|---|
| `dev.vertique.starter.core.CoreApplicationModule` | `vertique-starter-core` | the host-neutral lifecycle foundation — Vert.x seam, config parsing, verticle deployment, core lifecycle steps |
| `dev.vertique.services.DispatchModule` | `vertique-services` | the event-bus dispatch runtime — the typed `services` config boundary, contract registry, target resolver, supervisor, service exception mapper, `ServiceClientFactory`, and the paired `SERVICES`-phase deployment lifecycle steps |
| `dev.vertique.management.ManagementModule` | `vertique-management` | `ManagementConfig`, the health-check multibindings, and the management endpoint contributor set |

Dagger applies `@Module(includes = …)` recursively, so a component that names
`ServicesApplicationModule` reaches all three — and everything they include in turn — transitively.
`VertxModule` arrives through `CoreApplicationModule`; it is stateful and therefore remains a
generated-builder input, so the generated application factory still calls
`.vertxModule(new VertxModule(runtime.vertx(), runtime.config()))`.

### Membership is a compatibility surface

Both the included-module membership above and this module's direct dependency ledger are
release-line compatibility surfaces. Consumers may rely on the aggregate resolving the bindings
those three modules declare, and on the aggregate pulling in nothing beyond its declared ledger.
Changes to either are treated as compatibility-affecting, not as internal refactoring.

### The default execution model is the event loop

Service implementations reached through this aggregate run on the Vert.x event loop by default.
Worker opt-in is a per-contract application decision expressed in configuration
(`services.contracts.<namespace>.<name>.worker=true`) and is appropriate only for implementations
that perform genuinely blocking work. The starter sets no such flag and makes no execution-model
choice on the application's behalf.

### What applications still own

The starter deliberately stops at composition. Applications remain responsible for:

- **Management deployment entries** — the `@Provides @IntoSet VerticleDeployment` that deploys the
  management verticle. The starter contributes none, so an application that names only this
  aggregate builds a complete graph but exposes no management port. Service verticles themselves
  are deployed by the `SERVICES`-phase startup step `DispatchModule` contributes, not by an
  application deployment entry.
- **Generated services module** — the annotation-processor output module carrying server contributor
  bindings and injectable typed-client bindings is named explicitly in the component. The generated
  client provider still delegates to `ServiceClientFactory`; the starter does not create proxies.
- **Launcher choice** — `vertique-launcher` is not a dependency of this module.
- **Test libraries** — `vertique-application-test` and JUnit stay explicit test-scope dependencies
  of the application.
- **Worker opt-in** — see the execution-model note above.

---

## Key Classes

### `ServicesApplicationModule`

Public abstract Dagger module. It declares no constructor, field, method, nested type, or scope —
downstream components name the class, and neither Dagger nor application code instantiates it.

```java
@VertiqueApp
@Singleton
@Component(
        modules = {
            ServicesApplicationModule.class,
            AppModule.class,
            GeneratedServicesModule.class
        })
interface AppComponent extends VertiqueApplicationComponent {
    GreetingService greetingService();
}
```

`AppModule` is the application-owned module contributing its management deployment entry;
`GeneratedServicesModule` stands for whichever module the Vertique annotation processors generate
for that application. The `serviceClientFactory()` provision method is how application code obtains
typed event-bus clients.

---

## Extension Points

This module publishes no extension point of its own. Extend the application through the
multibindings its included modules declare — among them `@Services Set<Object>`,
`Set<ServiceInterceptor>`, `Set<ServiceExceptionMapperCustomizer>`,
`Set<ServiceContractContributor>` (from `dev.vertique:vertique-services`),
`@Liveness Set<HealthCheck>`, `@Readiness Set<HealthCheck>`,
`Set<ManagementEndpointContributor>` (from `dev.vertique:vertique-management`), and the
`Set<VerticleDeployment>`, `Set<ApplicationStartupStep>`, `Set<ApplicationShutdownStep>` sets the
core starter reaches — by contributing `@Provides @IntoSet` methods from an application-owned
module.

---

## Common Mistakes

- **Expecting an HTTP surface.** The aggregate is headless. No JAX-RS routing, HTTP verticle, or
  REST security binding exists in a graph composed from this starter alone; an application that
  needs one installs the REST starter.
- **Expecting the starter to deploy the management endpoint.** No `VerticleDeployment` is
  contributed here, so an application that names only `ServicesApplicationModule` starts with an
  empty deployment set and exposes no management port. Service verticles are deployed by the
  `SERVICES`-phase startup step, which is contributed.
- **Expecting the generated module to be included.** The annotation-processor output module is
  never reachable from a framework aggregate; it must be listed in the component explicitly. Once
  included, eligible service contracts are injectable without an application-owned provider.
- **Naming `CoreApplicationModule` alongside this aggregate.** The core starter is already reached
  transitively; listing it again adds nothing.
- **Marking services as workers by default.** Worker opt-in is per contract and only for genuinely
  blocking implementations; the default execution model is the event loop.
- **Expecting the starter to supply a launcher.** It does not depend on
  `dev.vertique:vertique-launcher`. A standalone application still declares the launcher itself.

---

## Dependencies

- **`dev.vertique:vertique-starter-core`** — `CoreApplicationModule`, and through it the
  `VertiqueApplicationComponent` lifecycle contract consuming components extend
- **`dev.vertique:vertique-management`** — `ManagementModule`
- **`dev.vertique:vertique-services`** — `DispatchModule`
- **`com.google.dagger:dagger`** — the `@Module` annotation itself

The ledger above is exact: the module declares no other direct dependency, and in particular no
REST, database, launcher, test, or code-generation artifact.

---

## Verification

`ServicesApplicationModule` is proven by the starter family's integration-test harness, which
compiles a real `@VertiqueApp` component naming only this aggregate, builds it through the
generated application factory without any REST module present, and asserts that the
`ServiceClientFactory`, the paired service deployment lifecycle steps, and the management binding
resolve. A dependency fixture materializes the compile and runtime classpaths and fails when the
direct ledger drifts or a REST, database, launcher, test, code-generation, or Testcontainers
artifact leaks in.
