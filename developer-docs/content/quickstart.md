---
title: Quickstart
description: Generate a Vertique REST application from the archetype, run its tests, start it, and call its hello endpoint.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Quickstart

By the end of this page you will have generated a Vertique REST application from the REST
archetype, run its unit and integration test suite, started it locally, and received a JSON
response from its generated `/hello` endpoint.

## Prerequisites

- JDK 21
- Apache Maven

## Generate the application

Run this command from an empty directory of your choice — it creates a new `rest-app` project
inside it.

`VERTIQUE_VERSION` is declared once below and reused for both the archetype version and the
generated project's `vertiqueVersion` property. `0.0.0-SNAPSHOT` is the framework source revision
this documentation ships with, not a released version.

```bash
VERTIQUE_VERSION=0.0.0-SNAPSHOT

mvn -B -ntp archetype:generate \
  -DarchetypeGroupId=dev.vertique \
  -DarchetypeArtifactId=vertique-archetype-rest \
  -DarchetypeVersion=$VERTIQUE_VERSION \
  -DgroupId=com.example \
  -DartifactId=rest-app \
  -Dversion=0.1.0-SNAPSHOT \
  -Dpackage=com.example.restapp \
  -DvertiqueVersion=$VERTIQUE_VERSION \
  -DinteractiveMode=false
```

Expected result: `BUILD SUCCESS`, and a new `rest-app` directory containing:

```text
rest-app/README.md
rest-app/pom.xml
rest-app/src/main/java/com/example/restapp/AppComponent.java
rest-app/src/main/java/com/example/restapp/AppModule.java
rest-app/src/main/java/com/example/restapp/package-info.java
rest-app/src/main/java/com/example/restapp/resource/HelloResource.java
rest-app/src/main/java/com/example/restapp/resource/package-info.java
rest-app/src/main/resources/config/application.json
rest-app/src/test/java/com/example/restapp/ApplicationIT.java
```

## Run the test suite

Working directory: `rest-app/`.

```bash
cd rest-app
mvn -ntp verify
```

This compiles the application, packages it, and runs its integration test class,
`ApplicationIT`, against a real deployed instance on a random port. Expected result:
`BUILD SUCCESS`, with the test summary reporting `Tests run: 2, Failures: 0, Errors: 0,
Skipped: 0`. One test (`servesHello`) calls `/hello` and asserts the JSON message; the other
(`servesLiveness`) calls the management `/health/live` endpoint and asserts an `UP` status.

## Start the application

Working directory: `rest-app/`.

```bash
mvn -ntp exec:java
```

This starts the application in the foreground and keeps running until you stop it, so leave this
terminal open. The generated `src/main/resources/config/application.json` configures two ports:
the HTTP port (`8080`), which serves the REST API, and the management port (`9090`), which serves
health and management endpoints.

## Call the hello endpoint

In a separate terminal, with the application still running:

```bash
curl -s http://localhost:8080/hello
```

Expected result:

```text
{"message":"Hello, Vertique!"}
```

## Stop the application

Return to the terminal running `mvn -ntp exec:java` and press `Ctrl+C`.

## Learn more

- [Vertique REST application archetype](../../vertique-archetype/vertique-archetype-rest/README.md)
  — the archetype's own reference, including exactly what dependencies the generated project
  declares.
- [`vertique-starter-rest` module reference](../../vertique-starter/vertique-starter-rest/src/main/resources/META-INF/vertique/module.md)
  — the starter the generated application depends on.
- [Documentation overview](index.md)
