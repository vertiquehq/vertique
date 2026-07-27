# ${artifactId}

A PostgreSQL-backed Vertique REST application exposing item CRUD under `/items`.

## Prerequisites

- JDK 21
- Apache Maven
- A reachable Docker daemon, required to run `mvn verify`: the integration test starts a real
  PostgreSQL container for the application to migrate and connect against

## Run

Runs the application locally. Startup configuration is read from the `config/` directory in the
working directory — `config/application.json` here. Environment variables and system properties
override the files, and `VERTX_CONFIG_LOCATIONS` points the loader at other directories instead
(comma-separated).

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

Builds a local container image with Jib. The image contains no `config` directory — the
development-only database credentials below are deliberately never baked into it: configure a
containerized run through environment variables, or mount a directory and name it with
`VERTX_CONFIG_LOCATIONS`.

```bash
mvn -ntp jib:dockerBuild
```

## Database

`config/application.json` points at a local PostgreSQL instance and applies the
application-owned migrations in `src/main/resources/db/migration` at startup
(`flyway.mode=MIGRATE`). Its connection defaults to `localhost:5432/vertique` with
`vertique`/`vertique` — development-only placeholders, unsuitable for production.

## Security

The `/items` endpoints ship without authentication: anyone who can reach the port can read and
modify every item. Add a security mechanism module — for example `dev.vertique:vertique-rest-auth-jwt`
— and a security policy before exposing this application beyond local development.
