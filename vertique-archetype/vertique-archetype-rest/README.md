# Vertique REST Application Archetype

Generates a Vertique REST application on the REST application starter.

## Prerequisites

- JDK 21
- Apache Maven

## Generate

```bash
mvn -B -ntp archetype:generate \
  -DarchetypeGroupId=dev.vertique \
  -DarchetypeArtifactId=vertique-archetype-rest \
  -DarchetypeVersion=<vertiqueVersion> \
  -DgroupId=<groupId> \
  -DartifactId=<artifactId> \
  -Dversion=0.1.0-SNAPSHOT \
  -Dpackage=<packageName> \
  -DvertiqueVersion=<vertiqueVersion> \
  -DinteractiveMode=false
```

The generated project depends on exactly `dev.vertique:vertique-starter-rest` and
`dev.vertique:vertique-launcher` in production scope, and declares its test libraries explicitly.
