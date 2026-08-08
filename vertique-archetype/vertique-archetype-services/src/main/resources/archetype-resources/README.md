# ${artifactId}

A headless Vertique event-bus services application. It exposes no HTTP edge — application code
injects `GreetingService` directly, and the generated Dagger module creates its typed event-bus
client through `ServiceClientFactory`.

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

Service implementations run on the non-blocking Vert.x event loop by default, and
`GreetingServiceImpl` is written for it: it returns an already-completed `Future` and never
blocks. Keep that default.

Set `services.contracts.sample.greeting.worker=true` in `config/application.json` only if the
implementation is changed to perform genuinely blocking work — a JDBC call, a filesystem read, or
CPU-bound computation. Worker mode is an opt-in for blocking implementations, not the default
execution model.
