<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Starter PostgreSQL Module

> **Status:** Alpha
> **Package:** `dev.vertique.starter.postgresql`
> **Artifact:** `vertique-starter-postgresql`
> **Depends on:** db-core, db-postgresql, db-flyway

The `vertique-starter-postgresql` module publishes one public Dagger aggregate,
`PostgresqlPersistenceModule`, that composes PostgreSQL connection pooling with the
application-owned Flyway migration wiring. An application names the aggregate in its `@Component`
instead of repeating the three database modules.

This module is a composition surface only. It declares no bindings of its own, contributes no
deployment entry, transport, test, or generated code, and adds no runtime behavior beyond what the
modules it includes already provide.

---

## When To Use It

Install `vertique-starter-postgresql` in any application that talks to PostgreSQL through the
framework's `SqlRepository` surface and migrates its schema with Flyway.

The starter is an **independent capability, not an application foundation**. It supplies pooling and
migration wiring — nothing else. It does not include `CoreApplicationModule`, so it never composes an
application on its own: it is named *alongside* an application starter, not instead of one.

```java
@VertiqueApp
@Singleton
@Component(
        modules = {
            RestApplicationModule.class,
            PostgresqlPersistenceModule.class,
            AppModule.class,
            GeneratedJaxRsResourcesModule.class
        })
interface AppComponent extends VertiqueApplicationComponent {}
```

`RestApplicationModule` (or `ServicesApplicationModule`, or `CoreApplicationModule` for a bare
application) brings the lifecycle foundation; `PostgresqlPersistenceModule` adds persistence to it.
A component that names only `PostgresqlPersistenceModule` has no Vert.x seam, no config parser, and
no lifecycle runner, and therefore does not build.

---

## Core Concepts

### The aggregate is exactly three modules

`PostgresqlPersistenceModule` includes exactly:

| Included module | Artifact | What it brings |
|---|---|---|
| `dev.vertique.db.DbModule` | `vertique-db-core` | the typed `db` config boundary — `DbPoolConfig` parsed from the application config |
| `dev.vertique.db.postgresql.DbPostgresqlModule` | `vertique-db-postgresql` | the `io.vertx.sqlclient.Pool`, `PgConnectOptions`, the optional `PoolConnectHandler` seam, `PgDbExceptionMapper`, and the `@Readiness` database health check |
| `dev.vertique.db.flyway.DbFlywayModule` | `vertique-db-flyway` | the typed `flyway` config boundary, the `MigrationRunner` binding, and the `MIGRATE`-phase `ApplicationStartupStep` that runs migrations before any verticle is deployed |

Dagger applies `@Module(includes = …)` recursively, so a component that names
`PostgresqlPersistenceModule` reaches all three — and everything they include in turn, such as
`HealthCheckModule` — transitively. The `Vertx` instance the pool needs is *not* supplied here; it
arrives from the application starter's `VertxModule`, which is why this aggregate is always composed
alongside one.

### Membership is a compatibility surface

Both the included-module membership above and this module's direct dependency ledger are
release-line compatibility surfaces. Consumers may rely on the aggregate resolving the bindings
those three modules declare, and on the aggregate pulling in nothing beyond its declared ledger.
Changes to either are treated as compatibility-affecting, not as internal refactoring.

### Applications own their migrations

The starter wires migration *execution*; it never supplies migration *content* or policy.
Applications own the SQL scripts under their configured Flyway locations (`classpath:db/migration`
by default), the `flyway.mode` choice (`MIGRATE`, `VALIDATE`, `DISABLED`), the DDL credentials, and
the schema layout. The `MIGRATE`-phase startup step runs whatever the application declares, before
the `INFRA`, `SERVICES`, and `EDGE` deployment phases.

### What applications still own

The starter deliberately stops at composition. Applications remain responsible for:

- **The application foundation** — `CoreApplicationModule` (directly, or through
  `RestApplicationModule` / `ServicesApplicationModule`) is not reached from this aggregate. Without
  one, there is no `Vertx` binding, no config parser, and no lifecycle runner.
- **Migration content and policy** — see above.
- **Deployment entries** — the aggregate contributes no `VerticleDeployment`. Persistence is a
  capability, not a deployed host.
- **Repositories** — `SqlRepository` implementations are application types bound by application
  modules.
- **Launcher choice** — `vertique-launcher` is not a dependency of this module.
- **Test libraries** — `vertique-application-test`, `vertique-db-test` (and with it Testcontainers),
  and JUnit stay explicit test-scope dependencies of the application.

---

## Key Classes

### `PostgresqlPersistenceModule`

Public abstract Dagger module. It declares no constructor, field, method, nested type, or scope —
downstream components name the class, and neither Dagger nor application code instantiates it.

Requesting `io.vertx.sqlclient.Pool` from a component that composes this aggregate with an
application starter yields the configured pool; requesting `dev.vertique.db.MigrationRunner` yields
the Flyway-backed runner the `MIGRATE`-phase startup step drives.

---

## Extension Points

This module publishes no extension point of its own. Extend the application through the bindings its
included modules declare — among them the optional `dev.vertique.db.PoolConnectHandler` binding for
per-connection initialization (from `dev.vertique:vertique-db-postgresql`), the
`@Readiness Set<HealthCheck>` multibinding the database health check joins, and the
`Set<ApplicationStartupStep>` set the Flyway migration step joins — by contributing `@Provides`
methods from an application-owned module.

---

## Common Mistakes

- **Naming only this aggregate in a component.** It supplies no application lifecycle. A component
  must also name an application starter (`CoreApplicationModule`, `RestApplicationModule`, or
  `ServicesApplicationModule`); otherwise `Vertx`, `@VertxConfig JsonObject`, and `ConfigParser` are
  unresolved.
- **Expecting a management or HTTP surface.** The aggregate brings neither. `vertique-management`
  and every `vertique-rest-*` artifact arrive through an application starter, not through this one.
- **Expecting a deployed verticle.** No `VerticleDeployment` is contributed here, so composing this
  aggregate adds nothing to the application's deployment set.
- **Expecting migrations to be supplied.** Only the wiring is. An application with no migration
  scripts at its configured locations migrates nothing.
- **Expecting a test database.** `vertique-db-test` and Testcontainers are deliberately absent from
  the ledger; an application adds them at test scope itself.
- **Expecting the starter to supply a launcher.** It does not depend on
  `dev.vertique:vertique-launcher`. A standalone application still declares the launcher itself.

---

## Dependencies

- **`dev.vertique:vertique-db-core`** — `DbModule`, `DbPoolConfig`, `MigrationRunner`, and the
  repository surface
- **`dev.vertique:vertique-db-postgresql`** — `DbPostgresqlModule`
- **`dev.vertique:vertique-db-flyway`** — `DbFlywayModule`
- **`com.google.dagger:dagger`** — the `@Module` annotation itself

The ledger above is exact: the module declares no other direct dependency, and in particular no
application, REST, management, services, launcher, test, or code-generation artifact.

---

## Verification

`PostgresqlPersistenceModule` is proven by the starter family's integration-test harness, which
compiles a consumer whose only production dependency is this starter — with no application framework
on the compile classpath at all — and then builds a Dagger graph composing this aggregate with a
test-scope core application fixture, asserting that `io.vertx.sqlclient.Pool`, `MigrationRunner`, and
the `MIGRATE`-phase Flyway startup contribution all resolve while the `VerticleDeployment` set stays
empty. A dependency fixture materializes the compile and runtime classpaths and fails when the direct
ledger drifts or an application, REST, management, services, launcher, test, code-generation, or
Testcontainers artifact leaks in.
