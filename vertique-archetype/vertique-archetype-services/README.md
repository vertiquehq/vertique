# Vertique Services Application Archetype

`dev.vertique:vertique-archetype-services` generates a headless Vertique event-bus services
application on the services application starter. The generated component exposes an injected
`GreetingClient`, whose constructor receives `GreetingService` directly from the generated Dagger
module; application code does not need to obtain the contract manually from `ServiceClientFactory`.

## Prerequisites

- JDK 21
- Apache Maven

## Generate

```bash
mvn -B -ntp archetype:generate \
  -DarchetypeGroupId=dev.vertique \
  -DarchetypeArtifactId=vertique-archetype-services \
  -DarchetypeVersion=<vertiqueVersion> \
  -DgroupId=<groupId> \
  -DartifactId=<artifactId> \
  -Dversion=0.1.0-SNAPSHOT \
  -Dpackage=<packageName> \
  -DvertiqueVersion=<vertiqueVersion> \
  -DinteractiveMode=false
```

The generated project depends on exactly `dev.vertique:vertique-starter-services` and
`dev.vertique:vertique-launcher` in production scope, and declares its test libraries explicitly.
It contributes no HTTP edge: the only deployment entry it owns is the management verticle.
