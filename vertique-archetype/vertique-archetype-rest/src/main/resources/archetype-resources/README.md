# ${artifactId}

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
