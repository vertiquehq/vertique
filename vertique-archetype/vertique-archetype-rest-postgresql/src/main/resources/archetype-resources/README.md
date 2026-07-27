# ${artifactId}

A PostgreSQL-backed Vertique REST application exposing item CRUD under `/items`.

## Prerequisites

- JDK 21
- Apache Maven
- A reachable Docker daemon, required to run `mvn verify`: the integration test starts a real
  PostgreSQL container for the application to migrate and connect against

## Run

Runs the application locally.

```bash
mvn -ntp exec:java
```

## Verify

Compiles, packages, and runs the unit and integration test suites.

```bash
mvn -ntp verify
```

## Package

Builds the deployable JAR. `verify` already runs `package` as an earlier lifecycle phase, so this
is only needed to build the JAR without also running the integration test suite.

```bash
mvn -ntp package
```

## Build a container image

Builds a local container image with Jib.

```bash
mvn -ntp jib:dockerBuild
```

## Database

`src/main/resources/config/application.json` points at a local PostgreSQL instance and applies the
application-owned migrations in `src/main/resources/db/migration` at startup
(`flyway.mode=MIGRATE`). Its connection defaults to `localhost:5432/vertique` with
`vertique`/`vertique` — development-only placeholders, unsuitable for production.
