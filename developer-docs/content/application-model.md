---
title: Application model
description: Understand how the launcher, your application's Dagger component and module, generated modules, starters, and lifecycle phases fit together.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Application model

This page explains who owns each piece of a Vertique application's Dagger wiring: what the
launcher does before your code runs, what your own Dagger component and module compose, what code
generation produces for you, how starters compose framework capability, and how lifecycle phases
order verticle deployment. It uses the REST application generated in
[Quickstart](quickstart.md) as its running example.

## What the launcher owns

`vertique-launcher` supplies `VertiqueApplication`, the framework-owned entrypoint every generated
application runs as its `Main-Class`. Before your Dagger graph exists, `VertiqueApplication`
resolves the application's configuration on a temporary Vert.x instance, builds the real `Vertx`
instance, discovers your application's component factory via `ServiceLoader`, and drives the
deployed application through to shutdown. A bootstrap-configuration failure exits with code `11`;
a verticle deploy failure exits with code `15`.

Because the REST archetype places `@VertiqueApp` on `AppComponent` (below), the generated project
needs no hand-written main class or manifest entry — the annotation is enough to register the
factory the launcher discovers. The generated project depends on both `vertique-starter-rest` and
`vertique-launcher`, and `mvn -ntp exec:java` (the command the quickstart uses to start the
application) runs `VertiqueApplication` as the Main-Class. Your application code never calls the
launcher directly.

## What your application owns

Every Vertique application supplies its own Dagger `@Component` and at least one
application-owned module contributing deployment entries. This is `AppComponent` from the
generated REST application:

```java
@VertiqueApp
@Singleton
@Component(
        modules = {
            RestApplicationModule.class,
            AppModule.class,
            GeneratedJaxRsResourcesModule.class
        })
interface AppComponent extends VertiqueApplicationComponent {}
```

`AppComponent` extends `VertiqueApplicationComponent`, the passive DI boundary the framework's
lifecycle runner drives: it exposes the application's startup steps, its shutdown steps, and its
verticle deployment manager, and nothing more — the component never starts itself.

`AppModule`, in the same generated project, is the application-owned module that makes this
component actually serve traffic (package and import lines omitted for focus):

```java
@Module
class AppModule {

    @Provides
    @IntoSet
    static VerticleDeployment managementVerticle(Provider<ManagementVerticle> provider) {
        return VerticleDeployment.of("management", provider::get, LifecyclePhase.INFRA);
    }

    @Provides
    @IntoSet
    static VerticleDeployment httpVerticle(Provider<HttpVerticle> provider) {
        return VerticleDeployment.of("http", provider::get, LifecyclePhase.EDGE);
    }
}
```

It contributes two `@Provides @IntoSet` bindings: a `management` verticle deployed at the
`INFRA` lifecycle phase, and an `http` verticle deployed at the `EDGE` lifecycle phase.
`RestApplicationModule` and `GeneratedJaxRsResourcesModule` contribute no deployment entries of
their own — without `AppModule`, this component builds a complete graph but serves neither
surface.

## What code generation produces

`GeneratedJaxRsResourcesModule`, named directly in `AppComponent` above, is annotation-processor
output: it is generated from the project's JAX-RS resource classes — `HelloResource` in the
quickstart — and carries their Dagger bindings. Generated modules like this one are never
reachable from a framework starter aggregate; they must be listed in your `@Component` explicitly,
exactly as `AppComponent` does here.

## What starters own

`RestApplicationModule`, from `vertique-starter-rest`, is the framework aggregate `AppComponent`
names for a REST-serving application. Starter aggregate membership — which modules a named
starter includes, and only those — is a release-line compatibility surface: consumers may rely on
it, and a change to it is treated as compatibility-affecting, not as internal refactoring. Vertique
publishes four such aggregates:

| Starter | Aggregate | When to use it |
|---|---|---|
| `vertique-starter-core` | `CoreApplicationModule` | Lifecycle foundation only — no HTTP, no event bus, no persistence |
| `vertique-starter-rest` | `RestApplicationModule` | Serves an HTTP API through JAX-RS resources; already includes `CoreApplicationModule` |
| `vertique-starter-services` | `ServicesApplicationModule` | Headless event-bus services; already includes `CoreApplicationModule` |
| `vertique-starter-postgresql` | `PostgresqlPersistenceModule` | PostgreSQL pooling and Flyway migration wiring; composed alongside an application starter, never in place of one |

An application names exactly the starters it needs, and never repeats a module a starter already
reaches transitively. `vertique-starter-postgresql` is an independent capability rather than an
application foundation — it supplies no `Vertx` binding, no config parser, and no lifecycle
runner on its own, so it is always paired with `vertique-starter-core`, `-rest`, or `-services`.

## How lifecycle phases order deployments

The framework's lifecycle runner drives eight ordered phases: `CONFIGURE`, `VALIDATE`, `MIGRATE`,
`BOOTSTRAP`, `INFRA`, `SERVICES`, `EDGE`, and `AFTER_START`. For each phase, in that order, it
runs the phase's contributed startup steps, then — for the four phases that carry deployments
(`BOOTSTRAP`, `INFRA`, `SERVICES`, `EDGE`) — deploys that phase's verticles.

In the generated REST application, `AppModule`'s two deployment entries land in two different
phases: the `management` verticle deploys at `INFRA`, before the `http` verticle deploys at
`EDGE`. The management (health) surface is therefore up before the HTTP surface starts serving
traffic. Adding the PostgreSQL starter to the same component inserts a `MIGRATE`-phase startup
step that runs schema migrations before any of these verticle phases deploy.

## Learn more

- [`vertique-launcher` module reference](../../vertique-launcher/src/main/resources/META-INF/vertique/module.md)
  — the startup sequence, exit codes, and the `VertxBuilderContributor` SPI.
- [`vertique-application` module reference](../../vertique-application/src/main/resources/META-INF/vertique/module.md)
  — the `VertiqueApplicationComponent` contract, the lifecycle runner, and every contributed
  startup and shutdown step.
- [`vertique-starter-core` module reference](../../vertique-starter/vertique-starter-core/src/main/resources/META-INF/vertique/module.md)
- [`vertique-starter-rest` module reference](../../vertique-starter/vertique-starter-rest/src/main/resources/META-INF/vertique/module.md)
- [`vertique-starter-services` module reference](../../vertique-starter/vertique-starter-services/src/main/resources/META-INF/vertique/module.md)
- [`vertique-starter-postgresql` module reference](../../vertique-starter/vertique-starter-postgresql/src/main/resources/META-INF/vertique/module.md)
- [Configuration](configuration.md) — how the config tree these components inject gets built.
- [Documentation overview](index.md)

## Continue reading

- Previous: [Quickstart](quickstart.md)
- Next: [Configuration](configuration.md)
