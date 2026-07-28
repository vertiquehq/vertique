---
title: Persistence
description: Generate a PostgreSQL-backed Vertique REST application from the archetype, own its migrations and transactions, and prove its Docker-backed test database lifecycle.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Persistence

By the end of this page you will have generated a PostgreSQL-backed Vertique REST application from
the REST/PostgreSQL archetype, run its Docker-backed integration test, and understood the three
things the application itself owns: its Flyway migrations, its database connection configuration,
and its repository's use of the framework's transaction seam.

## Prerequisites

- JDK 21
- Apache Maven
- A local checkout of this repository's source, built and installed locally — see
  [Quickstart](quickstart.md)'s "Build and install the framework locally" step.
  `vertique-archetype-rest-postgresql` and the starters it depends on are not published to any
  remote Maven repository.
- A reachable Docker daemon: `mvn verify` runs a real integration test that starts a PostgreSQL
  container for the application to migrate and connect against.

## Generate the application

Run this command from an empty directory of your choice — it creates a new `rest-postgresql-app`
project inside it. `VERTIQUE_VERSION` is declared once and reused for both the archetype version
and the generated project's `vertiqueVersion` property, matching [Quickstart](quickstart.md)'s
convention.

```bash
VERTIQUE_VERSION=0.0.0-SNAPSHOT

mvn -B -ntp archetype:generate \
  -DarchetypeGroupId=dev.vertique \
  -DarchetypeArtifactId=vertique-archetype-rest-postgresql \
  -DarchetypeVersion=$VERTIQUE_VERSION \
  -DgroupId=com.example \
  -DartifactId=rest-postgresql-app \
  -Dversion=0.1.0-SNAPSHOT \
  -Dpackage=com.example.restpostgresqlapp \
  -DvertiqueVersion=$VERTIQUE_VERSION \
  -DinteractiveMode=false
```

Expected result: `BUILD SUCCESS`, and a new `rest-postgresql-app` directory containing:

```text
rest-postgresql-app/README.md
rest-postgresql-app/pom.xml
rest-postgresql-app/src/main/java/com/example/restpostgresqlapp/AppComponent.java
rest-postgresql-app/src/main/java/com/example/restpostgresqlapp/AppModule.java
rest-postgresql-app/src/main/java/com/example/restpostgresqlapp/package-info.java
rest-postgresql-app/src/main/java/com/example/restpostgresqlapp/model/CreateItemRequest.java
rest-postgresql-app/src/main/java/com/example/restpostgresqlapp/model/Item.java
rest-postgresql-app/src/main/java/com/example/restpostgresqlapp/model/UpdateItemRequest.java
rest-postgresql-app/src/main/java/com/example/restpostgresqlapp/model/package-info.java
rest-postgresql-app/src/main/java/com/example/restpostgresqlapp/repository/ItemRepository.java
rest-postgresql-app/src/main/java/com/example/restpostgresqlapp/repository/package-info.java
rest-postgresql-app/src/main/java/com/example/restpostgresqlapp/resource/ItemResource.java
rest-postgresql-app/src/main/java/com/example/restpostgresqlapp/resource/package-info.java
rest-postgresql-app/src/main/resources/config/application.json
rest-postgresql-app/src/main/resources/db/migration/V1__create_items.sql
rest-postgresql-app/src/test/java/com/example/restpostgresqlapp/ApplicationIT.java
```

Its `pom.xml` declares exactly `vertique-starter-rest`, `vertique-starter-postgresql`, and
`vertique-launcher` in production scope — two independent starters composed side by side, not one
combined persistence-and-REST starter; see [Application model](application-model.md) for how
starters compose. Test scope adds `vertique-application-test`, `vertique-db-test` (which brings
Testcontainers), `junit-jupiter`, and `rest-assured`.

## Run the test suite

Working directory: `rest-postgresql-app/`.

```bash
cd rest-postgresql-app
mvn -ntp verify
```

Expected result: `BUILD SUCCESS`, with the test summary reporting `Tests run: 1, Failures: 0,
Errors: 0, Skipped: 0`. The one test, `supportsItemCrud`, starts a real PostgreSQL container, lets
the application apply its own migrations against it, and then drives one ordered item CRUD journey
over HTTP — see [Test database lifecycle](#test-database-lifecycle) below for how the container and
the application fit together.

## Application-owned migrations

The starter wires migration *execution*; the application owns the migration *content*. The
generated `AppComponent` names `PostgresqlPersistenceModule` — the aggregate
`vertique-starter-postgresql` publishes — alongside the REST application starter:

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

`PostgresqlPersistenceModule` contributes a `MIGRATE`-phase `ApplicationStartupStep` that runs
before the `INFRA`, `SERVICES`, and `EDGE` deployment phases — no manual
`migrationRunner().migrate(vertx)` call is needed. What it migrates is entirely the application's
own SQL, read from `classpath:db/migration` by default. The generated
`src/main/resources/db/migration/V1__create_items.sql` is the whole schema this archetype ships:

```sql
CREATE TABLE items (
    id          UUID PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT
);
```

Add `V2__*.sql` (and later) files to the same directory to evolve the schema — Flyway applies every
unapplied migration, in version order, the next time the `MIGRATE` phase runs.

## Repository pattern

`ItemRepository` extends `PgSqlRepository` (from `vertique-db-postgresql`) and is constructed with
an injected connection pool and exception mapper — it never opens a connection itself:

```java
@Singleton
public final class ItemRepository extends PgSqlRepository {

    @Inject
    public ItemRepository(Pool pool, PgDbExceptionMapper exceptionMapper) {
        super(pool, exceptionMapper);
    }

    public Future<Item> create(String name, String description) {
        UUID id = UUID.randomUUID();
        return this.<Item>query("INSERT INTO items (id, name, description) VALUES ($1, $2, $3)"
                        + " RETURNING id, name, description")
                .params(Tuple.of(id, name, description))
                .mapping(Item::fromRow)
                .returning();
    }
}
```

`findById`, `update`, and `delete` follow the same shape: each calls the inherited fluent
`query(String)` builder once and lets `PgDbExceptionMapper` translate any database failure into a
typed `DataAccessException` before it propagates. See [`vertique-db-core`'s module
reference](../../vertique-db/vertique-db-core/src/main/resources/META-INF/vertique/module.md) for
the full `Query`/`PagedQuery`/`OffsetPagedQuery` builder surface this repository is built on.

## Transactions

Every method on the generated `ItemRepository` runs exactly one statement, so none of them need a
transaction — the pooled connection each `query(...)` call borrows is enough on its own. The
canonical seam for work that must span more than one statement is `SqlRepository.transaction()`,
documented by [`vertique-db-core`'s module
reference](../../vertique-db/vertique-db-core/src/main/resources/META-INF/vertique/module.md#sqlrepository):
it returns a `TransactionBuilder` whose `execute(...)` runs a function against a transactional
connection, auto-committing on success and auto-rolling back on failure, with the same exception
translation `query(...)` already gives you:

```java
repository.transaction()
    .serializable()
    .readOnly()
    .execute(conn -> queryWork(conn));
```

Reach for this when a repository method needs to read-then-write, or write to more than one table,
as a single atomic unit — not for the single-statement CRUD this archetype ships.

## Test database lifecycle

`ApplicationIT` boots the whole application against a real PostgreSQL container. The container is
declared and started in a static initializer, and the application's config is built from it,
*before* the `VertiqueAppExtension` field is constructed — so the database is already reachable by
the time the extension boots the application and its `MIGRATE`-phase Flyway step connects:

```java
static final PostgresContainer db = new PostgresContainer().withDatabaseName("app_db");

static {
    db.start();
}

static final JsonObject CONFIG = buildConfig();

@RegisterExtension
static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(new AppComponentVertiqueComponentFactory())
        .withConfig(CONFIG);
```

`buildConfig()` points the `db` section at the container's own mapped connection and sets
`flyway.mode` to `MIGRATE` explicitly, so the *application* — not the container — applies the
migration:

```java
private static JsonObject buildConfig() {
    DbPoolConfig pool = db.toPoolConfig();
    return new JsonObject()
            .put("http", new JsonObject().put("port", 0))
            .put("management", new JsonObject().put("enabled", true).put("port", 0))
            .put(
                    "db",
                    new JsonObject()
                            .put("host", pool.host())
                            .put("port", pool.port())
                            .put("database", pool.database())
                            .put("user", pool.user())
                            .put("password", pool.password()))
            .put("flyway", new JsonObject().put("mode", "MIGRATE"));
}
```

A reachable Docker daemon is required: the static initializer's `db.start()` propagates any
discovery or startup failure, so a missing daemon fails the build rather than silently skipping the
proof. Three different spans are bounded by three different timeouts: the class-level `@Timeout`
(120 seconds) bounds the HTTP journey in the one `@Test` method; the container's own startup,
running in the static initializer above and outside that `@Timeout`, is bounded by
`vertique-db-test`'s own 120-second startup timeout; and the application boot plus its
`MIGRATE`-phase Flyway step, running in `VertiqueAppExtension`'s `beforeAll`, is bounded by that
extension's own 30-second start timeout. See [`vertique-db-test`'s module
reference](../../vertique-db/vertique-db-test/src/main/resources/META-INF/vertique/module.md) for
`PostgresContainer`'s shared-server isolation model.

## Configure the database connection

The generated project also ships `src/main/resources/config/application.json`, with `db` and
`flyway` sections pointed at a local development PostgreSQL instance. As
[Configuration](configuration.md) explains, Vertique resolves each relative configured directory —
`config/` by default — against the running process's own working directory, not the compiled
classpath. Running `mvn -ntp exec:java` from `rest-postgresql-app/` looks for
`rest-postgresql-app/config/`, which does not exist there by default, so the packaged file is never
read on that run — the same working-directory mechanism Configuration documents for the REST
archetype's `application.json` applies here too.

This is not a cosmetic gap: with neither a working-directory `config/` nor `flyway.jdbcUrl` set,
starting the application fails immediately during the `MIGRATE`-phase startup step — with the exact
same failure whether or not a matching PostgreSQL instance happens to be reachable on
`localhost:5432` — because `db.host` and `database` are never resolved from anywhere:

```text
java.lang.IllegalStateException: Either flyway.jdbcUrl or db.host + db.database must be configured for Flyway migrations
	at dev.vertique.db.flyway.FlywayMigrationRunner.resolveJdbcUrl(FlywayMigrationRunner.java:193)
```

To run the application outside its own test suite, create
`rest-postgresql-app/config/application.json` under the project's own working directory:

```json
{
  "http": { "port": 8080 },
  "management": { "port": 9090, "enabled": true },
  "db": {
    "host": "localhost",
    "port": 5432,
    "database": "vertique",
    "user": "vertique",
    "password": "vertique"
  },
  "flyway": { "mode": "MIGRATE" }
}
```

`flyway.mode` already defaults to `MIGRATE` — see [`vertique-db-flyway`'s module
reference](../../vertique-db/vertique-db-flyway/src/main/resources/META-INF/vertique/module.md) for
the other two modes, `VALIDATE` and `DISABLED`. The file above sets it explicitly for clarity, not
because the default would otherwise be different.

## Run the application

Start a disposable PostgreSQL matching the values above:

```bash
docker run -d --name rest-postgresql-app-db \
  -e POSTGRES_DB=vertique -e POSTGRES_USER=vertique -e POSTGRES_PASSWORD=vertique \
  -p 5432:5432 postgres:16-alpine
```

Working directory: `rest-postgresql-app/`, with `config/application.json` from the previous section
in place.

```bash
mvn -ntp exec:java
```

Expected result: log lines showing Flyway migrating schema `public` to version `1 - create items`,
followed by `Succeeded in deploying verticle`. Leave this terminal open — the application keeps
running in the foreground until stopped.

## Call the API

In a separate terminal, with the application still running:

```bash
curl -s -i -X POST http://localhost:8080/items \
  -H 'Content-Type: application/json' \
  -d '{"name":"Widget","description":"A sample widget"}'
```

Expected result: a `201 Created` status line, a `Location: /items/{id}` response header, and a JSON
body carrying a server-generated `id` — for example:

```text
HTTP/1.1 201 Created
Cache-Control: no-store
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Location: /items/d781c3a3-2ec6-4bc3-a1ac-d2a4f5f8b50a
Content-Type: application/json
content-length: 93
X-Request-Id: 09bb7eb0-48d3-4720-aea2-05f85af016b6

{"id":"d781c3a3-2ec6-4bc3-a1ac-d2a4f5f8b50a","name":"Widget","description":"A sample widget"}
```

Substitute the `id` your own request returned into the read call:

```bash
curl -s -i http://localhost:8080/items/d781c3a3-2ec6-4bc3-a1ac-d2a4f5f8b50a
```

Expected result: a `200 OK` status line and the same JSON body:

```text
HTTP/1.1 200 OK
Cache-Control: no-store
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Content-Type: application/json
content-length: 93
X-Request-Id: dc94975f-d9ca-446e-9a3a-a5633b1d4042

{"id":"d781c3a3-2ec6-4bc3-a1ac-d2a4f5f8b50a","name":"Widget","description":"A sample widget"}
```

An empty, whitespace-only, or over-length `name`, and a malformed identifier, are all rejected
before either reaches the database — see [REST APIs](rest-apis.md) for the request-validation gate
that guards this and every other generated resource.

## Stop the application

Return to the terminal running `mvn -ntp exec:java` and press `Ctrl+C`. Then remove the disposable
database:

```bash
docker rm -f rest-postgresql-app-db
```

## Learn more

- [Vertique REST/PostgreSQL application
  archetype](../../vertique-archetype/vertique-archetype-rest-postgresql/README.md) — the
  archetype's own reference, including exactly what dependencies the generated project declares.
- [`vertique-starter-postgresql` module
  reference](../../vertique-starter/vertique-starter-postgresql/src/main/resources/META-INF/vertique/module.md)
  — the three modules `PostgresqlPersistenceModule` composes, and what applications still own.
- [`vertique-db-core` module
  reference](../../vertique-db/vertique-db-core/src/main/resources/META-INF/vertique/module.md) —
  the exception hierarchy, the `Query`/`PagedQuery` builders, and `SqlRepository.transaction()`.
- [`vertique-db-postgresql` module
  reference](../../vertique-db/vertique-db-postgresql/src/main/resources/META-INF/vertique/module.md)
  — `PgSqlRepository`, `PgDbExceptionMapper`, and the PostgreSQL connection pool.
- [`vertique-db-flyway` module
  reference](../../vertique-db/vertique-db-flyway/src/main/resources/META-INF/vertique/module.md) —
  `FlywayMode`, `FlywayConfig`, and the `MIGRATE`-phase startup step.
- [`vertique-db-test` module
  reference](../../vertique-db/vertique-db-test/src/main/resources/META-INF/vertique/module.md) —
  `PostgresContainer`, `DatabaseExtension`, and the shared-server isolation model.
- [Quickstart](quickstart.md)
- [Application model](application-model.md)
- [Configuration](configuration.md)
- [REST APIs](rest-apis.md)
- [Documentation overview](index.md)

## Continue reading

- Previous: [Services](services.md)
- Next: [Workflows](workflows.md)
