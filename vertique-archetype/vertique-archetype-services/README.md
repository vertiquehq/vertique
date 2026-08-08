# Vertique Services Application Archetype

`dev.vertique:vertique-archetype-services` generates a Vertique application around the framework's
contract-based service execution model. The generated component includes the services application
starter and the generated services Dagger module. `GreetingClient` receives `GreetingService`
directly as an injected typed client, while Vertique handles context propagation and event-bus
dispatch.

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
Its application-owned deployment entry starts the management verticle; service verticles are
deployed by the Services lifecycle.
