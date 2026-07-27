# ${artifactId}

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

## Security

The `/hello` endpoint ships without authentication: anyone who can reach the port can call it. Add a
security mechanism module — for example `dev.vertique:vertique-rest-auth-jwt` — and a security
policy before exposing this application beyond local development.
