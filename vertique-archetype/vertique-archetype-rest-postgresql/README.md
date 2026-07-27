# Vertique REST PostgreSQL Application Archetype

Generates a PostgreSQL-backed Vertique REST application on the REST application starter and the
independent PostgreSQL persistence starter.

## Prerequisites

- JDK 21
- Apache Maven

## Generate

```bash
mvn -B -ntp archetype:generate \
  -DarchetypeGroupId=dev.vertique \
  -DarchetypeArtifactId=vertique-archetype-rest-postgresql \
  -DarchetypeVersion=<vertiqueVersion> \
  -DgroupId=<groupId> \
  -DartifactId=<artifactId> \
  -Dversion=0.1.0-SNAPSHOT \
  -Dpackage=<packageName> \
  -DvertiqueVersion=<vertiqueVersion> \
  -DinteractiveMode=false
```

The generated project depends on exactly `dev.vertique:vertique-starter-rest`,
`dev.vertique:vertique-starter-postgresql`, and `dev.vertique:vertique-launcher` in production
scope, and declares its test libraries explicitly. It owns its Flyway migrations: the generated
`V1__create_items.sql` is applied by the application's own `MIGRATE` startup phase.

A reachable Docker daemon is required to run the generated project's integration test.
