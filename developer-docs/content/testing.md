---
title: Testing
description: Run a generated Vertique application's integration test suite, focus it to one test class, and use the framework's test harness and PostgreSQL test container to write a new test.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Testing

By the end of this page you will have run a generated application's full and focused test suites,
understood the `VertiqueAppExtension` test harness both applications already use, seen how the
PostgreSQL application's test boots a real database, and added a new test that compiles and passes
against the generated REST application.

## Run the full test suite

A generated Vertique application ships exactly one test class, `ApplicationIT`, run by the
Maven Failsafe plugin during the `integration-test` and `verify` phases — not by Surefire's `test`
phase. Working directory: `rest-app/`, the REST application generated in
[Quickstart](quickstart.md):

```bash
cd rest-app
mvn -ntp verify
```

Expected result: `BUILD SUCCESS`, with the test summary reporting `Tests run: 2, Failures: 0,
Errors: 0, Skipped: 0` — exactly as [Quickstart](quickstart.md) documents.

Running `mvn -ntp test` alone in the same directory does not execute `ApplicationIT` at all:
Surefire's default file-name convention only picks up a `*Test.java`/`Test*.java`/`*Tests.java`/
`*TestCase.java` class, and `ApplicationIT` matches none of those. The command still reports
`BUILD SUCCESS`, but Surefire's `test` step runs zero tests — a quiet indicator that the
application's real proof lives in the integration-test phase, not the unit-test phase.

The PostgreSQL application generated in [Persistence](persistence.md) has the same one-class
shape, plus a Docker prerequisite: its `ApplicationIT` starts a real PostgreSQL container before
the application boots. Working directory: `rest-postgresql-app/`, with a reachable Docker daemon:

```bash
cd rest-postgresql-app
mvn -ntp verify
```

Expected result: `BUILD SUCCESS`, with the test summary reporting `Tests run: 1, Failures: 0,
Errors: 0, Skipped: 0`, plus Flyway log lines showing the application migrating schema `public`
to version `1 - create items` against the container before the one `@Test` method runs. See
[Test database lifecycle](persistence.md#test-database-lifecycle) for exactly how the container and
the application boot sequence fit together.

## Focus on one test class

Failsafe's own property for selecting which integration-test classes run is `it.test`, not
Surefire's `test`. Working directory: `rest-app/`:

```bash
mvn -ntp verify -Dit.test=ApplicationIT
```

Expected result: `BUILD SUCCESS`, with the same `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`
summary as the full run above — Failsafe's `integration-test` phase runs only the named class.

Passing Surefire's `-Dtest=ApplicationIT` instead does not narrow anything usefully: because
`-Dtest` overrides Surefire's default file-name filter rather than being ignored, Surefire's `test`
phase now also matches and runs `ApplicationIT` once on its own, and Failsafe's
`integration-test` phase then runs the same class a second time — `ApplicationIT` executes twice
in one `mvn -ntp verify` instead of once. Use `-Dit.test`, Failsafe's own selection property, for a
single-class, single-run focus in a generated project.

## The application test harness: `VertiqueAppExtension`

Both `ApplicationIT` classes boot the whole generated application through
`vertique-application-test`'s `VertiqueAppExtension`, a JUnit 5 extension registered as a
`static final` field that starts the application once for the class and tears it down afterward.
The REST application's own generated test declares it with an ephemeral HTTP port so parallel test
runs never collide on a fixed port:

```java
@RegisterExtension
static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(new AppComponentVertiqueComponentFactory())
        .withConfig(new JsonObject()
                .put("http", new JsonObject().put("port", 0))
                .put("management", new JsonObject().put("enabled", true).put("port", 0)));

@BeforeAll
static void setUp() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = app.httpPort();
}
```

`app.httpPort()` reads the port `HttpVerticle` actually bound to back out of shared data, so the
test never has to guess or hardcode a port. The PostgreSQL application's generated test uses the
identical `forFactory(...).withConfig(...)` shape, with one difference: its `CONFIG` is built from
a running database container instead of being written inline, so the `db` and `flyway` sections
point at a real, already-started PostgreSQL instance before the extension boots the application —
see [Database tests](#database-tests-postgrescontainer) below.

See [`vertique-application-test`'s module reference](../../vertique-application-test/src/main/resources/META-INF/vertique/module.md)
for the full builder (`forFactory`, `withConfig`, `withVertx`, `startTimeout`) and accessor
(`vertx()`, `handle()`, `component()`, `config()`, `httpPort()`) surface, including why the
extension field must be `static final` to take effect once per class rather than once per test
method.

## Database tests: `PostgresContainer`

The PostgreSQL application's `ApplicationIT` declares and starts its `PostgresContainer` in a
static initializer, before the `VertiqueAppExtension` field is constructed, so the database is
already reachable by the time the extension boots the application and its `MIGRATE`-phase Flyway
step connects:

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

`buildConfig()` points the `db` section at the container's mapped connection and sets
`flyway.mode` to `MIGRATE` explicitly, so the *application* applies the migration during its own
startup — the container itself runs no migrations of its own. [Persistence](persistence.md)'s
[Test database lifecycle](persistence.md#test-database-lifecycle) section covers this ordering,
the three independent timeouts that bound the container start, the application boot, and the test
method, and the static-initializer failure path in full; this page does not repeat it.

`PostgresContainer`'s no-arg constructor runs in shared-server mode: every no-arg instance in the
same Surefire/Failsafe fork provisions its own database on one shared PostgreSQL server rather
than starting a dedicated container each time, so a test class using it must not create or depend
on cluster-global objects (roles, tablespaces, cluster-level extensions) that would leak across
every other test class sharing that server. See
[`vertique-db-test`'s module reference](../../vertique-db/vertique-db-test/src/main/resources/META-INF/vertique/module.md)
for `PostgresContainer`'s full fluent API, the shared-server isolation model, and
`DatabaseExtension`, the alternative JUnit 5 extension for a repository-level test that does not
need a whole booted application.

## Write a new test

Add a new `@Test` method to a generated `ApplicationIT` the same way the existing ones are
written: call the application through `RestAssured`, using `given()`/`when()`/`then()` to assert
the response. This example, added to and proven against the REST application generated in
[Quickstart](quickstart.md), asserts the framework's default behavior for a path no resource
handles:

```java
@Test
void rejectsUnknownPath() {
    given().when().get("/does-not-exist").then().statusCode(404);
}
```

Working directory: `rest-app/`.

```bash
mvn -ntp verify -Dit.test=ApplicationIT
```

Expected result: `BUILD SUCCESS`, with `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` — the two
generated tests plus the new one, all passing. This example was written into the generated
project's own `ApplicationIT`, compiled, and run to confirm it passes before being reconciled into
this page; the generated project ships without it, so add it (or a test shaped like it) yourself to
reproduce this result.

## Learn more

- [`vertique-application-test` module reference](../../vertique-application-test/src/main/resources/META-INF/vertique/module.md)
  — `VertiqueAppExtension`'s full builder and accessor surface.
- [`vertique-db-test` module reference](../../vertique-db/vertique-db-test/src/main/resources/META-INF/vertique/module.md)
  — `PostgresContainer`, `DatabaseExtension`, and the shared-server isolation model.
- [Quickstart](quickstart.md)
- [Persistence](persistence.md)
- [Application model](application-model.md)
- [Documentation overview](index.md)
