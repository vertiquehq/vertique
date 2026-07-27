<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Starter REST Module

> **Status:** Alpha
> **Package:** `dev.vertique.starter.rest`
> **Artifact:** `vertique-starter-rest`
> **Depends on:** starter-core, management, rest-jaxrs, rest-security, rest-validation

The `vertique-starter-rest` module publishes one public Dagger aggregate,
`RestApplicationModule`, that composes the mechanism-neutral REST application foundation on top of
the core application starter. An application names the aggregate in its `@Component` instead of
repeating the JAX-RS, validation, security, and management module list in every generated
application.

This module is a composition surface only. It declares no bindings of its own, contributes no
deployment entry, transport, test, or generated code, and adds no runtime behavior beyond what the
modules it includes already provide.

---

## When To Use It

Install `vertique-starter-rest` in any application that serves an HTTP API through JAX-RS
resources. It already includes `CoreApplicationModule`, so an application that names this aggregate
does not also name the core starter.

Applications that serve no HTTP surface install `vertique-starter-core` (or the headless services
starter) instead.

---

## Core Concepts

### The aggregate is exactly six modules

`RestApplicationModule` includes exactly:

| Included module | Artifact | What it brings |
|---|---|---|
| `dev.vertique.starter.core.CoreApplicationModule` | `vertique-starter-core` | the host-neutral lifecycle foundation — Vert.x seam, config parsing, verticle deployment, core lifecycle steps |
| `dev.vertique.rest.jaxrs.RestModule` | `vertique-rest-jaxrs` | the JAX-RS routing runtime — exception mapping, response serialization, request-body decoders, response-body encoders, and the default JAX-RS router mount |
| `dev.vertique.rest.validation.RestValidationModule` | `vertique-rest-validation` | the `web-validation` request-validation strategy and its annotation-driven `OperationSchemaSource` |
| `dev.vertique.rest.security.AuthModule` | `vertique-rest-security` | identity resolution, authorization contributors, the `SecurityPolicyValidator`, and the auth enforcement capability signal |
| `dev.vertique.rest.security.SecurityModule` | `vertique-rest-security` | the holder-backed `SecurityRuntime` and the JAX-RS `SecurityContext` factory |
| `dev.vertique.management.ManagementModule` | `vertique-management` | `ManagementConfig`, the health-check multibindings, and the management endpoint contributor set |

Dagger applies `@Module(includes = …)` recursively, so a component that names
`RestApplicationModule` reaches all six — and everything they include in turn — transitively.
`VertxModule` arrives through `CoreApplicationModule`; it is stateful and therefore remains a
generated-builder input, so the generated application factory still calls
`.vertxModule(new VertxModule(runtime.vertx(), runtime.config()))`.

### Membership is a compatibility surface

Both the included-module membership above and this module's direct dependency ledger are
release-line compatibility surfaces. Consumers may rely on the aggregate resolving the bindings
those six modules declare, and on the aggregate pulling in nothing beyond its declared ledger.
Changes to either are treated as compatibility-affecting, not as internal refactoring.

### The starter is mechanism-neutral about authentication

The aggregate wires the security *runtime* and the enforcement seams, not a concrete
authentication mechanism. No JWT artifact is on its dependency ledger, and no
`SecuritySchemeHandler` is contributed here. An application that authenticates callers adds the
mechanism module it wants — for example `dev.vertique:vertique-rest-auth-jwt` — as its own explicit
dependency and names that module in its component.

### What applications still own

The starter deliberately stops at composition. Applications remain responsible for:

- **HTTP and management deployment entries** — every `@Provides @IntoSet VerticleDeployment` that
  deploys the HTTP verticle and the management verticle. The starter contributes none, so an
  application that names only this aggregate builds a complete graph but listens on no port.
- **Generated JAX-RS modules** — the annotation-processor output module carrying the resource
  bindings is named explicitly in the component.
- **Launcher choice** — `vertique-launcher` is not a dependency of this module.
- **Test libraries** — `vertique-application-test`, JUnit, REST Assured, and Testcontainers stay
  explicit test-scope dependencies of the application.
- **Concrete authentication mechanisms** — see the mechanism-neutrality note above.

---

## Key Classes

### `RestApplicationModule`

Public abstract Dagger module. It declares no constructor, field, method, nested type, or scope —
downstream components name the class, and neither Dagger nor application code instantiates it.

```java
@VertiqueApp
@Singleton
@Component(
        modules = {
            RestApplicationModule.class,
            AppModule.class,
            GeneratedResourceModule.class
        })
interface AppComponent extends VertiqueApplicationComponent {}
```

`AppModule` is the application-owned module contributing its deployment entries and any
authentication mechanism bindings; `GeneratedResourceModule` stands for whichever module the
Vertique annotation processors generate for that application.

---

## Extension Points

This module publishes no extension point of its own. Extend the application through the
multibindings its included modules declare — among them `@JaxRsResources Set<Object>`,
`Set<RouterMount>`, `Set<Middleware>`, `Set<OperationHandlerContributor>`,
`Set<RequestInterceptor>`, `Set<RestExceptionMapperCustomizer>` (from the REST modules),
`Set<SecuritySchemeHandler>`, `Set<SecurityIdentityResolver>` (from `vertique-rest-security`),
`Set<ManagementEndpointContributor>` (from `dev.vertique:vertique-management`), and the
`Set<VerticleDeployment>`, `Set<ApplicationStartupStep>`, `Set<ApplicationShutdownStep>` sets the
core starter reaches — by contributing `@Provides @IntoSet` methods from an application-owned
module.

---

## Common Mistakes

- **Expecting JWT (or any mechanism) to be wired.** The aggregate is mechanism-neutral. Without an
  explicit mechanism module no `SecuritySchemeHandler` exists, and routes that declare a
  restrictive security policy fail startup rather than silently allowing anonymous access.
- **Expecting the starter to deploy anything.** No `VerticleDeployment` is contributed here, so an
  application that names only `RestApplicationModule` starts with an empty deployment set and
  serves neither HTTP nor management endpoints.
- **Expecting the generated module to be included.** The annotation-processor output module is
  never reachable from a framework aggregate; it must be listed in the component explicitly.
- **Naming `CoreApplicationModule` alongside this aggregate.** The core starter is already reached
  transitively; listing it again adds nothing.
- **Expecting the starter to supply a launcher.** It does not depend on
  `dev.vertique:vertique-launcher`. A standalone application still declares the launcher itself.

---

## Dependencies

- **`dev.vertique:vertique-starter-core`** — `CoreApplicationModule`, and through it the
  `VertiqueApplicationComponent` lifecycle contract consuming components extend
- **`dev.vertique:vertique-management`** — `ManagementModule`
- **`dev.vertique:vertique-rest-jaxrs`** — `RestModule`
- **`dev.vertique:vertique-rest-security`** — `AuthModule` and `SecurityModule`
- **`dev.vertique:vertique-rest-validation`** — `RestValidationModule`
- **`com.google.dagger:dagger`** — the `@Module` annotation itself

The ledger above is exact: the module declares no other direct dependency, and in particular no
services, database, JWT, launcher, test, or code-generation artifact.

---

## Verification

`RestApplicationModule` is proven by the starter family's integration-test harness, which compiles
a real `@VertiqueApp` component naming only this aggregate, builds it through the generated
application factory, and asserts that representative routing, validation, security-policy, and
management bindings resolve. A dependency fixture materializes the compile and runtime classpaths
and fails when the direct ledger drifts or a services, database, JWT, launcher, test, or
code-generation artifact leaks in.

---

## Related ADRs

Decision: ADR-0188 — Static Starter Aggregates and Separate Archetypes.
