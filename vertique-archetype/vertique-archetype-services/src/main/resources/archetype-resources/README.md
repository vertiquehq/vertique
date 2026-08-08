# ${artifactId}

A Vertique contract-based services application. Application code injects `GreetingService`
directly; the generated services Dagger module provides its singleton typed client, and Vertique
handles registered context propagation and event-bus dispatch.

## Prerequisites

- JDK 21
- Apache Maven

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

Builds a local container image with Jib. The image contains no `config` directory: configure a
containerized run through environment variables, or mount a directory and name it with
`VERTX_CONFIG_LOCATIONS`.

```bash
mvn -ntp jib:dockerBuild
```

## Threading

Each service verticle instance runs on its own Vert.x event loop, which is non-blocking by default.
`GreetingServiceImpl` is written for that model: it returns an already-completed `Future` and never
blocks. Keep that default.

Set `services.contracts.sample.greeting.worker=true` in `config/application.json` only if the
implementation is changed to perform genuinely blocking work — a JDBC call, a filesystem read, or
CPU-bound computation. Worker mode is an opt-in for blocking implementations, not the default
execution model.
