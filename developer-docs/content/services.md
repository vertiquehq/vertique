---
title: Services
description: Define a service contract and call it through a typed client, on an application with no REST dependency in its production path.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Services

By the end of this page you will have generated a headless Vertique services application, defined
and called a service through a typed event-bus client, and confirmed that nothing in its production
dependencies is REST-specific.

## Generate the application

`vertique-archetype-services` and the starter it depends on are not published to any remote Maven
repository, so build and install the framework from source first if you have not already done so —
see [Quickstart](quickstart.md)'s "Build and install the framework locally" step.

Run this command from an empty directory of your choice — it creates a new `services-app` project
inside it. `VERTIQUE_VERSION` is declared once and reused for both the archetype version and the
generated project's `vertiqueVersion` property, matching [Quickstart](quickstart.md)'s convention.

```bash
VERTIQUE_VERSION=0.0.0-SNAPSHOT

mvn -B -ntp archetype:generate \
  -DarchetypeGroupId=dev.vertique \
  -DarchetypeArtifactId=vertique-archetype-services \
  -DarchetypeVersion=$VERTIQUE_VERSION \
  -DgroupId=com.example \
  -DartifactId=services-app \
  -Dversion=0.1.0-SNAPSHOT \
  -Dpackage=com.example.servicesapp \
  -DvertiqueVersion=$VERTIQUE_VERSION \
  -DinteractiveMode=false
```

Expected result: `BUILD SUCCESS`, and a new `services-app` directory containing:

```text
services-app/README.md
services-app/pom.xml
services-app/src/main/java/com/example/servicesapp/AppComponent.java
services-app/src/main/java/com/example/servicesapp/AppModule.java
services-app/src/main/java/com/example/servicesapp/package-info.java
services-app/src/main/java/com/example/servicesapp/service/GreetingService.java
services-app/src/main/java/com/example/servicesapp/service/GreetingServiceImpl.java
services-app/src/main/java/com/example/servicesapp/service/package-info.java
services-app/src/main/resources/config/application.json
services-app/src/test/java/com/example/servicesapp/ApplicationIT.java
```

Its `pom.xml` declares exactly `vertique-starter-services` and `vertique-launcher` in production
scope — no REST starter anywhere. `vertique-application-test`, `junit-jupiter`, and no HTTP test
client are declared in test scope.

## Define a service contract

`GreetingService` is a plain Java interface, annotated with `@ServiceContract` to give it a stable
event-bus address and `@ServiceOperation` to name its one operation:

```java
package com.example.servicesapp.service;

import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;

@ServiceContract(namespace = "sample", value = "greeting")
public interface GreetingService {

    @ServiceOperation("greet")
    Future<String> greet(String name);
}
```

`GreetingServiceImpl` implements it directly and is the only implementation the generated project
contributes:

```java
package com.example.servicesapp.service;

import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public final class GreetingServiceImpl implements GreetingService {

    @Inject
    public GreetingServiceImpl() {}

    @Override
    public Future<String> greet(String name) {
        return Future.succeededFuture("Hello, " + name + "!");
    }
}
```

`namespace = "sample"` and `value = "greeting"` form the event-bus base address
`services/sample/greeting`; `@ServiceOperation("greet")` names the one operation on it. Add a
second method the same way to grow the contract — each `@ServiceOperation` value becomes its own
address segment.

## Register the service with the generated Dagger module

You never write a `@Provides @IntoSet` binding for `GreetingServiceImpl` by hand. At compile time,
`vertique-codegen-services`'s annotation processor emits one `{Contract}_ContractContributor` per
service contract and a `GeneratedServicesModule` that contributes it into the framework's contract
registry:

```java
@Module
public abstract class GeneratedServicesModule {
  @Provides
  @IntoSet
  static ServiceContractContributor greetingService(GreetingService_ContractContributor impl) {
    return impl;
  }
}
```

`AppComponent` lists this generated module alongside the starter aggregate:

```java
@VertiqueApp
@Singleton
@Component(
        modules = {
            ServicesApplicationModule.class,
            AppModule.class,
            GeneratedServicesModule.class
        })
interface AppComponent extends VertiqueApplicationComponent {

    ServiceClientFactory serviceClientFactory();
}
```

`ServicesApplicationModule`, from `vertique-starter-services`, includes exactly three modules: the
core lifecycle foundation, the event-bus dispatch runtime (contract registry, target resolver,
supervisor, and `ServiceClientFactory`) from `vertique-services`, and the management endpoint
module. It brings no HTTP server, no JAX-RS routing, and no REST security — see
[Keep REST optional](#keep-rest-optional) below.

## Call the service through a typed client

`AppComponent` exposes `ServiceClientFactory`; call `create(Contract.class)` to obtain a JDK dynamic
proxy that dispatches each method call over the event bus and returns a `Future` with the result.
The generated integration test calls `GreetingService` exactly this way:

```java
AppComponent component = app.component();
GreetingService greetings = component.serviceClientFactory().create(GreetingService.class);

String greeting = greetings.greet("Vertique")
        .toCompletionStage()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);

assertEquals("Hello, Vertique!", greeting);
```

Application code calling a service typically injects it directly instead of injecting the factory:
provide the client once from a Dagger module (`@Provides @Singleton static GreetingService
greetingServiceClient(ServiceClientFactory factory) { return factory.create(GreetingService.class);
}`) and inject `GreetingService` wherever it is needed — including from a JAX-RS resource, which is
how a REST adapter reaches a service without any REST-specific code inside the service contract or
its implementation.

## Run the tests

Working directory: `services-app/`.

```bash
cd services-app
mvn -ntp verify
```

Expected result: `BUILD SUCCESS`, with the test summary reporting `Tests run: 2, Failures: 0,
Errors: 0, Skipped: 0`. `invokesGreetingThroughTypedProxy` calls `GreetingService` through
`ServiceClientFactory` as shown above; `keepsGreetingServiceOnEventLoopByDefault` asserts that the
packaged configuration carries no `worker` opt-in for the sample service, since its implementation
never blocks.

## Run the application

Working directory: `services-app/`.

```bash
mvn -ntp exec:java
```

This starts the application in the foreground and keeps running until you stop it, so leave this
terminal open. There is no HTTP edge to call — `GreetingService` is reached only through
`ServiceClientFactory`, in-process or from another Vertique application on the same event bus.
Confirm the deployed service from a separate terminal instead, through the management port
(`9090` by default):

```bash
curl -s http://localhost:9090/health/ready
```

Expected result:

```text
{"status":"UP","checks":[{"name":"services","status":"UP","data":{"greeting":"UP"}}]}
```

Return to the terminal running `mvn -ntp exec:java` and press `Ctrl+C` to stop it.

## Threading

Service implementations run on the non-blocking Vert.x event loop by default, and
`GreetingServiceImpl` above is written for it: it returns an already-completed `Future` and never
blocks. The packaged `src/main/resources/config/application.json` reflects this as a shape
reference only — its `services.contracts.sample.greeting` section sets only `instances`:

```json
{
  "management": { "port": 9090, "enabled": true },
  "services": {
    "contracts": {
      "sample": {
        "greeting": { "instances": 1 }
      }
    }
  }
}
```

As [Configuration](configuration.md) explains, `mvn -ntp exec:java` resolves `config/` against the
running process's own working directory, not the packaged classpath resource, so editing the
packaged file above has no observable effect on that run. Set
`services.contracts.sample.greeting.worker` to `true` in `services-app/config/application.json`
under the project's own working directory instead, and only once `GreetingServiceImpl` is changed
to perform genuinely blocking work — a JDBC call, a filesystem read, or CPU-bound computation.
Worker mode is an opt-in for blocking implementations, not the default execution model; the
generated project's own `README.md` documents this alongside its run, verify, package, and
container-image commands.

## Keep REST optional

Nothing in this application's production dependencies is REST-specific: its `pom.xml` declares only
`vertique-starter-services` and `vertique-launcher`, and `ServicesApplicationModule` brings no HTTP
server, no JAX-RS routing, and no REST security. A capability defined here stays callable in-process
or from another Vertique application on the event bus without ever adding a REST starter.

If a capability needs an HTTP edge later, add that edge as a separate, optional adapter instead of
coupling it into the service contract: install `vertique-starter-rest` alongside
`vertique-starter-services`, then inject the service (as shown in
[Call the service through a typed client](#call-the-service-through-a-typed-client) above) from a
JAX-RS resource — see [REST APIs](rest-apis.md) for adding that resource.

## Learn more

- [`vertique-services` module reference](../../vertique-services/src/main/resources/META-INF/vertique/module.md)
  — `@ServiceContract`, `@ServiceOperation`, resilience policy annotations, and
  `ServiceClientFactory`.
- [`vertique-codegen-services` module reference](../../vertique-codegen/vertique-codegen-services/src/main/resources/META-INF/vertique/module.md)
  — the annotation processor that generates `GeneratedServicesModule` and each
  `{Contract}_ContractContributor`.
- [`vertique-starter-services` module reference](../../vertique-starter/vertique-starter-services/src/main/resources/META-INF/vertique/module.md)
  — the three modules `ServicesApplicationModule` composes.
- [`vertique-starter-rest` module reference](../../vertique-starter/vertique-starter-rest/src/main/resources/META-INF/vertique/module.md)
  — the starter to add when a capability also needs an HTTP edge.
- [Vertique services application archetype](../../vertique-archetype/vertique-archetype-services/README.md)
  — the archetype's own reference, including exactly what dependencies the generated project
  declares.
- [Quickstart](quickstart.md)
- [Application model](application-model.md)
- [REST APIs](rest-apis.md) — add a JAX-RS resource that calls a service.
- [Documentation overview](index.md)

## Continue reading

- Previous: [REST APIs](rest-apis.md)
- Next: [Persistence](persistence.md)
