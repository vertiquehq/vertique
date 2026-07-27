# ${artifactId}

A headless Vertique event-bus services application. It exposes no HTTP edge — callers reach
`GreetingService` through the typed event bus proxy created by `ServiceClientFactory`.

## Prerequisites

- JDK 21
- Apache Maven

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

## Threading

Service implementations run on the Vert.x event loop by default, and `GreetingServiceImpl` is
written for it: it returns an already-completed `Future` and never blocks. Keep that default.

Set `services.contracts.sample.greeting.worker=true` in `src/main/resources/config/application.json`
only if the implementation is changed to perform genuinely blocking work — a JDBC call, a filesystem
read, or CPU-bound computation. Worker mode is an opt-in for blocking implementations, not the
default execution model.
