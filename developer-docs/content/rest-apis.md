---
title: REST APIs
description: Add a JAX-RS resource with a path parameter and a validated request body, and understand how it is registered, validated, serialized, and how failures become HTTP responses.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST APIs

By the end of this page you will have extended the generated REST application from
[Quickstart](quickstart.md) with a resource method that takes a path parameter and a validated JSON
request body, and you will understand how that method is registered, validated, serialized, and how
its failures become HTTP responses.

## Add a resource

`HelloResource`, generated into `rest-app/src/main/java/com/example/restapp/resource/HelloResource.java`
by Quickstart, starts with a single `@GET /hello` method. Extend it with a path parameter and a
`@POST` method that accepts a JSON body, following the same `@PathParam` and bean-validation-annotated
request-body pattern used by the PostgreSQL REST archetype's
[`ItemResource`](../../vertique-archetype/vertique-archetype-rest-postgresql/src/main/resources/archetype-resources/src/main/java/resource/ItemResource.java)
and
[`CreateItemRequest`](../../vertique-archetype/vertique-archetype-rest-postgresql/src/main/resources/archetype-resources/src/main/java/model/CreateItemRequest.java):

```java
@Path("/hello")
public class HelloResource {

    @Inject
    public HelloResource() {}

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HelloResponse hello() {
        return new HelloResponse("Hello, Vertique!");
    }

    @GET
    @Path("/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public HelloResponse helloTo(@PathParam("name") String name) {
        return new HelloResponse("Hello, " + name + "!");
    }

    @POST
    @Path("/greetings")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public HelloResponse createGreeting(CreateGreetingRequest request) {
        return new HelloResponse("Hello, " + request.name() + "!");
    }

    public record HelloResponse(String message) {}

    public record CreateGreetingRequest(@NotBlank @Size(max = 255) String name) {}
}
```

`@NotBlank` and `@Size` are Jakarta Bean Validation annotations; the generated project already
depends on `jakarta.validation:jakarta.validation-api` transitively through `vertique-starter-rest`,
so no additional dependency is required. What consumes these annotations is covered in
[Validate requests automatically](#validate-requests-automatically) below.

An operation's id — used for routing and, when generated, for the OpenAPI document — defaults to
the method name (`hello`, `helloTo`, `createGreeting` above); set it explicitly with
`@Operation(operationId = "...")` when you want a stable id independent of the method name.

## Register the resource with the generated Dagger module

You never add a resource to a Dagger module by hand. At compile time,
`vertique-codegen-jaxrs`'s annotation processor scans every `@Path`-annotated class and emits
`GeneratedJaxRsResourcesModule`, a `@Module` that provides the resource instance into the
`@JaxRsResources` multibinding `RestModule` reads at startup. This is the same generated module
`AppComponent` already lists, as shown in [Application model](application-model.md):

```java
@Module
public class GeneratedJaxRsResourcesModule {
  @Provides
  @ElementsIntoSet
  @JaxRsResources
  static Set<Object> helloResourceBinding(@VertxConfig JsonObject config,
      Provider<HelloResource> provider) {
    return Set.of(provider.get());
  }
}
```

Extending `HelloResource` with the two new methods above does not change this generated module —
one binding method covers every method on the resource class. It does add a generated execution
plan and descriptor per method, which is how the framework avoids reflecting on your resource at
request time. `@NoAutoWire` on a resource class opts it out of generation, keeping a hand-written
binding canonical instead.

## Generate the OpenAPI document

Vertique can generate an `openapi.json`/`openapi.yaml` pair from your annotated resources at build
time using `swagger-maven-plugin-jakarta` plus the framework's own `vertique-rest-openapi-plugin`
model converter, which unwraps a resource method's `Future` return type to its resolved type in the
generated spec. This is documentation only — it is not consulted by the default request-validation path (see
the next section) — and the REST archetype does not wire this plugin into the generated project by
default, so add it yourself when you want the document. Add a `vertique.version` property to
`pom.xml` (matching the framework revision you generated against, `VERTIQUE_VERSION` from
[Quickstart](quickstart.md)) and this plugin block, reconciled from
[`vertique-example-hello`'s build configuration](../../examples/vertique-example-hello/pom.xml):

```xml
<plugin>
  <groupId>io.swagger.core.v3</groupId>
  <artifactId>swagger-maven-plugin-jakarta</artifactId>
  <configuration>
    <outputFileName>openapi</outputFileName>
    <outputPath>${project.build.directory}/classes</outputPath>
    <outputFormat>JSONANDYAML</outputFormat>
    <resourcePackages>
      <package>com.example.restapp.resource</package>
    </resourcePackages>
    <prettyPrint>true</prettyPrint>
    <modelConverterClasses>
      dev.vertique.openapi.FutureModelConverter
    </modelConverterClasses>
  </configuration>
  <dependencies>
    <dependency>
      <groupId>dev.vertique</groupId>
      <artifactId>vertique-rest-openapi-plugin</artifactId>
      <version>${vertique.version}</version>
    </dependency>
  </dependencies>
  <executions>
    <execution>
      <phase>compile</phase>
      <goals>
        <goal>resolve</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

Running `mvn -ntp compile` with this plugin in place writes `openapi.json` and `openapi.yaml` under
`target/classes`, with one path entry per resource method — `POST /hello/greetings`'s `operationId`
resolves to `createGreeting`, matching the fallback described above.

## Validate requests automatically

The generated project's default request-validation strategy needs no wiring: `vertique-starter-rest`
already includes `RestValidationModule`. At startup it reads the same JAX-RS and Bean Validation
annotations on your resource methods and request bodies — including `@NotBlank` and `@Size` on
`CreateGreetingRequest` above — and synthesizes a JSON Schema for each operation, validated with
`vertx-json-schema` on every request. This does not consult `openapi.json` at all; the two are
independent, and OpenAPI-contract validation against the generated document is available as a
separate opt-in strategy (see [`vertique-rest-openapi-validation`](../../vertique-rest/vertique-rest-openapi-validation/src/main/resources/META-INF/vertique/module.md)
below) selected with `jaxrs.validationStrategy = "openapi-contract"` in configuration.

Posting a blank `name` to `/hello/greetings` is rejected before `createGreeting` ever runs — see
[Map failures to HTTP responses](#map-failures-to-http-responses) for the exact response body.

## Serialize the response

A resource method's return value is serialized to JSON automatically; you never call an encoder
yourself. `hello`, `helloTo`, and `createGreeting` above all return a plain record, so each response
is `200 OK` with a JSON body produced from that record and `Content-Type: application/json`. A
method may instead return a `Future` (resolved asynchronously, then serialized the same way); `void`,
or a `Future` that completes with no value (`204 No Content`); or a `Response`, plain or wrapped in a
`Future`, whose status, headers, and entity are taken directly from what you built.

By default this serialization goes through Vert.x's own Jackson-backed JSON codec. A resource class
or method can instead select a named Jackson mapper profile with `@JsonProfile("...")` for stricter
or customized behavior — see the [`vertique-json` module reference](../../vertique-json/src/main/resources/META-INF/vertique/module.md)
for the built-in `vertique` profile and how to register your own.

## Map failures to HTTP responses

An exception thrown from a resource method, or a request-validation rejection, flows through the
same error pipeline and comes out as an RFC 9457 Problem Detail JSON response — you do not write a
`try`/`catch` around ordinary failures. Posting `{"name":""}` to `/hello/greetings` above produces
exactly this response, `400 Bad Request` with `Content-Type: application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/hello/greetings",
  "errors": [
    {
      "path": "#/name",
      "detail": "must have a minimum length of 1",
      "location": "body",
      "type": "minLength",
      "args": { "minLength": 1 }
    }
  ]
}
```

Common exceptions — not-found, conflict, unauthorized, forbidden, unavailable, and more — already
map to the matching HTTP status without any code of yours. To map your own exception type, contribute
a `jakarta.ws.rs.ext.ExceptionMapper` for it through the Dagger `Set<ExceptionMapper<?>>` multibinding,
or customize the shared translator through the `RestExceptionMapperCustomizer` multibinding — both are
covered in the [`vertique-rest-jaxrs` module reference](../../vertique-rest/vertique-rest-jaxrs/src/main/resources/META-INF/vertique/module.md).

## Learn more

- [`vertique-rest-jaxrs` module reference](../../vertique-rest/vertique-rest-jaxrs/src/main/resources/META-INF/vertique/module.md)
  — resource scanning and registration, response serialization, and the full error pipeline.
- [`vertique-codegen-jaxrs` module reference](../../vertique-codegen/vertique-codegen-jaxrs/src/main/resources/META-INF/vertique/module.md)
  — the annotation processor that generates `GeneratedJaxRsResourcesModule`, and the `@NoAutoWire`
  opt-out.
- [`vertique-rest-validation` module reference](../../vertique-rest/vertique-rest-validation/src/main/resources/META-INF/vertique/module.md)
  — the default annotation-driven JSON Schema request-validation strategy.
- [`vertique-rest-openapi-validation` module reference](../../vertique-rest/vertique-rest-openapi-validation/src/main/resources/META-INF/vertique/module.md)
  — the opt-in strategy that validates against the generated OpenAPI document instead.
- [`vertique-rest-openapi-plugin` module reference](../../vertique-rest/vertique-rest-openapi-plugin/src/main/resources/META-INF/vertique/module.md)
  — the build-time OpenAPI model converters.
- [`vertique-json` module reference](../../vertique-json/src/main/resources/META-INF/vertique/module.md)
  — named Jackson mapper profiles for request and response serialization.
- [PostgreSQL REST archetype `ItemResource`](../../vertique-archetype/vertique-archetype-rest-postgresql/src/main/resources/archetype-resources/src/main/java/resource/ItemResource.java)
  — a full CRUD resource using the same path-parameter and validated-body pattern, backed by a
  repository.
- [Quickstart](quickstart.md)
- [Application model](application-model.md)
- [Services](services.md) — build a headless capability without a REST adapter.
- [Documentation overview](index.md)
