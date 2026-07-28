---
title: JAX-RS compatibility
description: Know exactly which parts of JAX-RS 4.0 the framework supports, where it deliberately diverges, and what is not supported at all.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# JAX-RS compatibility

Vertique's REST layer is authored entirely with JAX-RS 4.0 annotations (`jakarta.ws.rs-api` 4.0.0)
— `@Path`, `@GET`, `@QueryParam`, `@Produces`, and the rest, as [REST APIs](rest-apis.md) walks
through — but the runtime underneath is Vertique's own Vert.x-native engine, not Jersey, RESTEasy,
or any other reference implementation. `vertique-rest-jaxrs` supplies only a minimal
`RuntimeDelegate`, just enough for `jakarta.ws.rs.core.Response` and its builder to work
standalone. That combination means part of the JAX-RS surface is fully supported, part is
intentionally replaced with a Vert.x-shaped equivalent, and part is not implemented at all. By the
end of this page you will know which is which for every area a resource author is likely to reach
for — including, for anything not supported, what actually happens if you use it anyway.
Everything below applies the same way whether a resource is discovered by the reflective scanner at
startup or compiled ahead of time by the annotation processor — the two paths are built to match
exactly.

## At a glance

| Area | Verdict | Detail |
|---|---|---|
| [Resources and methods](#resources-and-methods) | Supported, with one gap | HTTP-verb annotations, `@Produces`/`@Consumes`, and regex-constrained `@Path` templates all work; sub-resource locators do not exist. |
| [Parameters](#parameters) | Supported, with one gap | Every standard parameter-binding annotation except `@MatrixParam` works on a resource method, and a real `ParamConverterProvider` is consulted for scalar conversion. |
| [Bodies and entities](#bodies-and-entities) | Diverges | Body reading and writing run through a framework decoder/encoder SPI, not `MessageBodyReader`/`MessageBodyWriter`; multipart `EntityPart` is supported within that model. |
| [Responses](#responses) | Supported, with gaps | `Response` and `@Produces` negotiation work as specified, though the builder's variant methods throw; `StreamingOutput` is not recognized; there is no response-body contract validation. |
| [Providers and filters](#providers-and-filters) | Diverges | `ContainerRequestFilter`, `ContainerResponseFilter`, `ReaderInterceptor`, `WriterInterceptor`, `@Provider`, and `@NameBinding` are all replaced by framework interceptor and codec extension points wired through Dagger. |
| [Exception handling](#exception-handling) | Supported | `ExceptionMapper` and `WebApplicationException` behave as specified, backed by a hierarchy-aware registry. |
| [Context injection](#context-injection) | Supported, with a reserved list | `@Context` resolves the Vert.x routing context, the JAX-RS `SecurityContext`, and framework context types; seven standard JAX-RS context types are rejected at startup. |
| [Validation](#validation) | Diverges by default | The default request-validation path enforces Bean Validation annotations as a synthesized JSON Schema, not as Jakarta Validation; a genuine Bean Validation runtime is available as a separate opt-in. |
| [Async model](#async-model) | Diverges | A resource method returns a `Future`; `@Suspended` and `AsyncResponse` are never recognized. |
| [Client API](#client-api) | Not supported | `jakarta.ws.rs.client` is not implemented; a separate declarative REST client module is the framework's alternative for calling other HTTP services. |

## Resources and methods

Every standard HTTP-verb annotation is supported: `@GET`, `@POST`, `@PUT`, `@DELETE`, `@PATCH`,
`@HEAD`, and `@OPTIONS` all route through the same scan, whether it runs reflectively or at compile
time. `@HEAD` additionally has its entity stripped from the response after conditional-request
evaluation, matching HTTP semantics. `@Produces` and `@Consumes` are read at both class and method
level (method overrides class) and are enforced: an incoming request whose Content-Type does not
satisfy the operation's `@Consumes` is rejected with 415 before validation or the resource method
ever runs, and `@Produces` drives Accept-header content negotiation on the way out (see
[Responses](#responses) below). One divergence in how the annotation is read: each array element
must hold exactly one media type — a comma-separated list inside a single string is not split, and
is treated as one invalid media type. Declare multiple media types as separate array elements.

A plain `@Path` template such as `/items/{id}` is translated into a Vert.x route with a named path
parameter. A regex-constrained template such as `/items/{id:[0-9]+}` is supported too: the whole
template compiles to a single anchored regular expression with one named capture group per
variable, so path-parameter binding by name still works identically.

Sub-resource locators — a method carrying `@Path` but no HTTP-verb annotation, returning another
resource instance for further dispatch — are not supported. A method with zero verb annotations is
treated as a non-endpoint method and silently skipped: no route is registered for it, and nothing
is logged or reported at startup. A resource method written as a sub-resource locator simply never
receives a request; there is no compile-time or startup signal that it was ignored.

## Parameters

Every JAX-RS parameter-binding annotation except one is supported on a resource method:
`@PathParam`, `@QueryParam`, `@HeaderParam`, `@CookieParam`, `@FormParam`, and `@BeanParam` all bind
exactly as specified, with `@DefaultValue` honored on every scalar form. `@MatrixParam` is the
exception — it is recognized only in the declarative REST client's request interfaces (see
[Client API](#client-api)) and in the framework's `UriBuilder` implementation for constructing
URIs, never on a server resource method. A `@MatrixParam`-annotated resource-method parameter is
not rejected: the parameter scanner does not recognize the annotation, so the parameter falls
through the same path an unannotated parameter takes and is bound as the request body instead. If
the method already declares a real, unannotated body parameter, that combination fails at startup
over the duplicate body binding; otherwise the framework silently tries to deserialize the request
body into whatever type the `@MatrixParam` parameter declared.

Scalar type conversion is a real, layered mechanism, not a fixed table. A built-in native converter
registry covers `String`, the primitive and boxed numeric types, `boolean`, `char`, `BigInteger`,
`BigDecimal`, `UUID`, `URI`, every `java.time` value type, and any enum (converted by exact constant
name). Beyond that registry, a standard `jakarta.ws.rs.ext.ParamConverterProvider` contributed by
the application is genuinely consulted at request time — in ascending `@Priority` order, first
non-null result wins — so a custom provider is not merely accepted and then ignored. A parameter
type that neither the registry nor any registered provider can satisfy is rejected at startup,
before the application ever serves a request, rather than surfacing as a runtime error on first
use.

## Bodies and entities

Request-body reading and response-body writing run entirely through two framework SPIs — a decoder
chain for reading, an encoder chain for writing — never through JAX-RS's own
`MessageBodyReader`/`MessageBodyWriter` contracts. Those two interfaces are not part of the model:
nothing in the framework looks for one, and contributing one has no effect.

The decoder chain ships four built-in decoders, tried in priority order; the first whose declared
Content-Type and target type both match wins: a text decoder for any `text/*` body into a `String`;
a binary decoder for `application/octet-stream` into a `Buffer` or a `byte[]`; a form decoder for
`application/x-www-form-urlencoded` into any plain object; and a JSON decoder as the fallback for
everything else, including a raw `JsonObject`. An application-contributed decoder runs before all
four by default, so it can intercept a content type the framework would otherwise handle.

Multipart is supported, including the JAX-RS `EntityPart` representation alongside the plain
Vert.x file-upload type: a resource method can declare a named `EntityPart` parameter, a named list
of them, or an unannotated aggregate list covering every part of the request — file-backed parts
and text-field parts alike. One bound on this support: the framework's own file-part
size/content-type constraint annotation is not accepted on an `EntityPart` parameter, only on the
plain file-upload type, because an `EntityPart` may represent a text field the constraint mechanism
cannot see — accepting the annotation there would open a bypass.

Streaming applies to responses, not requests. The request body is always fully read — and, for
multipart, spooled to disk — by the router's body handler before any decoder or your resource
method runs; there is no way to receive a live, unbuffered inbound stream. A response, on the other
hand, can stream: a resource method may return a live byte stream that is piped to the client as it
produces data, and a dedicated mechanism is available for Server-Sent Events.

## Responses

`jakarta.ws.rs.core.Response` is a first-class return type, built the normal way and returned
either directly or wrapped in a `Future`. Because the runtime is not built on Jersey or RESTEasy,
`vertique-rest-jaxrs` ships its own minimal `RuntimeDelegate` — just enough for the standard
`Response` builder, `UriBuilder`, and `Link` to work standalone; anything a full JAX-RS container
would additionally provide is out of scope. One bound inside the builder itself: the
`variants(...)` methods throw `UnsupportedOperationException`, so variant-based negotiation cannot
be expressed through the builder.

`@Produces` content negotiation works against the request's Accept header: candidate media types
come from the operation's `@Produces` list, or a JSON default when none is declared, matched by
specificity and quality value, with an unsatisfiable request answered with 406 — the same outcome a
compliant JAX-RS container would produce. This negotiation is media-type only; there is no charset
or language negotiation layered onto it.

Two things a JAX-RS-literate reader might expect are missing. First, `StreamingOutput` is not
implemented; the equivalent is returning a live stream directly from the resource method, as
described above. Second, and more consequential: neither of the framework's request-validation
strategies validates a resource method's response against any schema or contract — both gate the
request only. A response body that violates its own declared shape is never caught by the
framework; an application that needs that guarantee has to check it itself.

## Providers and filters

None of the classic JAX-RS provider or filter contracts are implemented: `ContainerRequestFilter`,
`ContainerResponseFilter`, `ReaderInterceptor`, `WriterInterceptor`, the `@Provider` registration
annotation, and `@NameBinding` do not exist anywhere in the runtime. This is a deliberate
divergence, not a gap — the framework replaces the whole family with its own Dagger-wired
interceptor and codec extension points, detailed in full in the `rest-core` module reference below.
The rough correspondence, for a reader coming from standard JAX-RS:

| Instead of | Reach for |
|---|---|
| `ContainerRequestFilter` / `ContainerResponseFilter` | a request interceptor, contributed through a Dagger multibinding, with hooks that run before dispatch and after the response is produced |
| `ReaderInterceptor` | the request body decoder chain described under [Bodies and entities](#bodies-and-entities) |
| `WriterInterceptor` | the response body encoder chain |
| `@Provider` | a Dagger multibinding contribution — registration happens at compile time, not by classpath scanning |
| `@NameBinding` | nothing declarative; a per-operation interceptor inspects the operation's own metadata to decide whether to act, or a handler contributor is placed at a specific priority range instead |

An additional, per-operation interceptor — running once per matched resource-method invocation
rather than once per request at the router level — is also available, with its own before, after,
and recover hooks around the method call. It is the closest match to a JAX-RS filter scoped to one
resource method, but it is still contributed the same way, through a Dagger multibinding, not a
name-binding annotation.

## Exception handling

`jakarta.ws.rs.ext.ExceptionMapper` is supported exactly as specified: implement it for your
exception type and contribute it through a Dagger multibinding, and it is consulted ahead of the
framework's own defaults for that same type. Lookup is hierarchy-aware — a mapper registered for a
superclass still matches a thrown subclass — and results are cached after the first lookup.

`WebApplicationException` behaves as specified too: its carried `Response` is used directly, with
the original entity preserved when present. A long list of the framework's own exception types
already has a mapper contributed out of the box — validation failures, not-found, conflict,
unauthorized, forbidden, unavailable, and a few more — and an uncaught, unmapped throwable of any
other type still gets a well-formed problem-details response rather than a raw stack trace or a
hung connection.

## Context injection

`@Context` resolves a fixed, framework-chosen set of types rather than any type a JAX-RS container
might supply: the Vert.x routing context, the JAX-RS `SecurityContext` (the exact interface only,
not a custom subtype), and any of the framework's own context-value types — its own security
context, correlation data, localization, and similar request-scoped application values. All of them
resolve through the same ordered resolver chain, and an application can contribute its own resolver
for a custom context-value type.

Seven standard JAX-RS context types are explicitly not supported: `UriInfo`, `HttpHeaders`,
`Request`, `Configuration`, `Application`, `Providers`, and `ResourceContext`. These are recognized
as syntactically valid `@Context` targets, but a resource method declaring one of them fails at
startup with a clear error rather than starting successfully and injecting a null value the first
time the method runs. The same fail-fast treatment applies to a `@Context` parameter combined with
a JAX-RS value-binding annotation on the same parameter, and to a `@Context` parameter whose
declared type is neither a built-in injectable type nor an application context-value type — both
are startup errors, not runtime surprises.

## Validation

Bean Validation annotations on a request body or parameter are read by default, but they are not
enforced by a Jakarta Validation (JSR 380) engine. The default request-validation path reads the
same annotations JAX-RS and Bean Validation put on a resource method and its request types and,
once, at startup, synthesizes a JSON Schema fragment per operation from them; every request is then
validated against that synthesized schema, not against the original annotations at request time.
Most of the time the two enforcement models agree, but they are not identical, and the difference
is observable: JSON Schema's `pattern` keyword is an unanchored search — it matches if the pattern
is found anywhere in the value — while a real Bean Validation `@Pattern` match is fully anchored
against the whole value. A constraint requiring at least one non-whitespace character, for example,
behaves like a not-blank check under the framework's default JSON-Schema-backed path, but would
reject any longer value outright under a genuine Bean Validation runtime.

A genuine Jakarta Validation runtime is also available, as a separate, opt-in module. Once it is
included in the application's dependency-injection graph, it runs real constraint validation against
resource-method arguments — with real Bean Validation semantics — after parameters are extracted
and before the resource method is invoked. The two mechanisms are independent and can both be
active on the same application at once: the schema gate runs first as part of request validation,
and the opt-in Bean Validation runtime runs second as part of method dispatch. Response bodies are
validated by neither mechanism.

See [REST APIs](rest-apis.md) for how the default schema-based path is wired and configured, and
the `vertique-validation` module reference below for the separate, real Bean Validation runtime.

## Async model

A resource method's return type is `Future` — a synchronous return works too, but every
non-trivial resource method that does I/O returns a `Future` of its result, resolved asynchronously
before the response is serialized; a `Future` that completes with no value sends 204 with no body,
matching a `void` method. That is the only asynchronous model available. The standard JAX-RS
asynchronous pattern — a `@Suspended` `AsyncResponse` parameter that the method resumes later from
another thread — is not implemented: both the annotation and the type ship with the Jakarta WS-RS
API dependency, so the declaration compiles, but no code path recognizes either, and the parameter
falls through the same unannotated-parameter body binding described under
[Parameters](#parameters). On a Vert.x-native runtime, a `Future`-returning method already
expresses the same intent without a second, parallel mechanism.

## Client API

`jakarta.ws.rs.client` — `Client`, `ClientBuilder`, `WebTarget`, and the rest of the JAX-RS client
API — is not implemented and is not the framework's model for calling another HTTP service. The
alternative is a separate, declarative REST client: annotate a plain Java interface with
`@RestClient` plus the same JAX-RS annotations used on a server resource method (`@GET`, `@Path`,
`@QueryParam`, and so on), build it once through the client factory, and call its methods like any
other injected service — each call becomes an asynchronous HTTP request over a Vert.x-backed web
client, sharing the same parameter-conversion stack the server side uses. See the `rest-client`
module reference below for the full annotation surface, configuration, and request-execution
pipeline.

## Why annotations, not a JAX-RS container

The `rest-jaxrs` module reference describes itself in one line as a "JAX-RS routing runtime on top
of Vert.x" — annotated resource classes are mapped to plain Vert.x routes, methods are invoked via
reflection, and responses are dispatched through the framework's own pipeline. That is the
relationship behind every row in the table above: JAX-RS annotations are the authoring surface — a
familiar, specified vocabulary for describing an HTTP resource — but nothing underneath expects a
full JAX-RS container. Where the specification names a provider or filter contract that assumes
one — `ContainerRequestFilter`, `MessageBodyReader`, `AsyncResponse`, a discoverable `Client` —
Vertique either has no use for it or has already built the Vert.x-native equivalent this page names
next to it.

## Learn more

- [`vertique-rest-jaxrs` module reference](../../vertique-rest/vertique-rest-jaxrs/src/main/resources/META-INF/vertique/module.md)
  — the routing runtime, parameter-extraction table, and return-type handling this page describes.
- [`vertique-rest-core` module reference](../../vertique-rest/vertique-rest-core/src/main/resources/META-INF/vertique/module.md)
  — the interceptor, decoder/encoder, and context-resolver extension points that replace the JAX-RS
  provider and filter contracts.
- [`vertique-rest-validation` module reference](../../vertique-rest/vertique-rest-validation/src/main/resources/META-INF/vertique/module.md)
  — the default JSON-Schema-synthesis request-validation strategy.
- [`vertique-rest-openapi-validation` module reference](../../vertique-rest/vertique-rest-openapi-validation/src/main/resources/META-INF/vertique/module.md)
  — the opt-in contract-validation strategy.
- [`vertique-validation` module reference](../../vertique-validation/src/main/resources/META-INF/vertique/module.md)
  — the separate, real Jakarta Bean Validation runtime.
- [`vertique-rest-client` module reference](../../vertique-rest/vertique-rest-client/src/main/resources/META-INF/vertique/module.md)
  — the declarative REST client named above as the `jakarta.ws.rs.client` alternative.
- [PostgreSQL REST archetype `CreateItemRequest`](../../vertique-archetype/vertique-archetype-rest-postgresql/src/main/resources/archetype-resources/src/main/java/model/CreateItemRequest.java)
  — the real `@Pattern`/`@NotBlank` combination behind the JSON-Schema-vs-Bean-Validation example
  above.
- [Quickstart](quickstart.md)
- [REST APIs](rest-apis.md)
- [Services](services.md) — build a headless capability without a REST adapter.
- [Documentation overview](index.md)

## Continue reading

- Previous: [REST APIs](rest-apis.md)
- Next: [Services](services.md)
