<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST Test

> **Status:** Alpha
> **Package:** `dev.vertique.rest.test`
> **Artifact:** `vertique-rest-test`
> **Depends on:** rest-jaxrs, rest-core, config-core

Test-support module that lets a test graph assemble a production-faithful `JaxRsRouterMount` from
outside `dev.vertique.rest.jaxrs`. It ships a Dagger module, `RestTestFixtureModule`, that includes
the framework's real REST wiring and unions a consumer's additional test-only collaborators into the
same multibindings production uses, plus `RestTestMounts`, a pure Vert.x helper for turning a
graph-built `RestTestMount` into a router or a running HTTP server.

It is not a substitute for `HttpVerticle`. A fixture server is one JAX-RS mount with both of its
production middleware pipelines — not the whole verticle.

---

## When To Use It

Install this module at test scope in any REST-surface module that needs HTTP-level integration tests
against a real, production-faithful mount instead of hand-rolled encoder, decoder, or
exception-mapper stand-ins. Declare a package-private test `@Component` that includes
`RestTestFixtureModule` alongside any strategy module the consumer needs (for example a
request-validation module), bind the consumer's contributions, and build the router or server through
the mount helpers.

Do not use it for anything that needs real security policy validation: the fixture supplies a null
`SecurityPolicyValidator` (see Core Concepts), which is incompatible with including `AuthModule`.

---

## Core Concepts

### The two middleware tiers

A faithful mount has **two** middleware tiers, installed by two different pieces of production code:

- **API tier** — `JaxRsRouterMount` installs the `MiddlewareScope.API` middlewares on the API router
  it builds.
- **ROOT tier** — `HttpVerticle` installs the `MiddlewareScope.ROOT` middlewares on the main router,
  *above* the mount. This is the tier `Middleware.scope()` returns by **default**, so a contributed
  middleware whose author never considered the question belongs to it.

`RestTestMounts.startServer` reproduces both: it builds a root router, installs the ROOT tier on it
with the same `OrderedExtension` comparator `HttpVerticle` uses, and mounts the API router below it
as a sub-router. A request therefore traverses per-request context lifecycle, correlation ingress,
default headers, contextual logging, and request-completion emission exactly as it would in a
deployed application, plus any ROOT middleware the test contributes.

`RestTestMounts.router` deliberately returns the **API router alone**, for a caller assembling its
own root router. That caller owns the ROOT tier.

### What the fixture does not reproduce

Everything `HttpVerticle` does around a single mount is out of scope: multi-mount sorting and overlap
detection, `MountCustomizer` and `RouterCustomizer` hooks, and the configured `HttpServerOptions`
(TLS, compression, timeouts). A test asserting on any of those needs a real verticle.

### Configuration

Configuration flows in as the production `@VertxConfig JsonObject`, so every config-derived
collaborator (`HttpConfig`, `JaxRsConfig`, the SSE encoder, the default-header middleware) is built
from one coherent source.

**`jaxrs.validationStrategy` is the trap.** It defaults to `web-validation`, while this module alone
contributes only the `none` strategy. A component that includes **no** validation module must set it
explicitly, or the router build fails when it resolves the configured strategy id:

```json
{ "jaxrs": { "validationStrategy": "none" } }
```

A component that includes a validation module (for example `RestValidationModule`) gets that
module's strategy and does not need the override.

### Security stand-in

`RestTestFixtureModule` provides a **null** `SecurityPolicyValidator`. `JaxRsRouterMount.Factory`
consumes that type as `@Nullable` and no REST module binds it, so every graph must supply something;
this matches what an unauthenticated application declares in its own `AppModule`, and startup policy
validation is simply skipped.

The binding is unqualified, and `AuthModule` binds the same unqualified key — so a component
**cannot include both**. A test needing real policy validation includes the security module and does
not use this fixture's graph for that concern. Treat this stand-in as test scope only.

---

## Key Classes

### RestTestFixtureModule

The Dagger `@Module` to include. It includes `RestModule` (hence `RestCoreModule` and the JSON
runtime) and `ConfigParsingModule`, so the graph resolves the production `JaxRsRouterMount.Factory`
with the real encoders, decoders, exception-mapper registry, response serializer, and context
resolvers — several of which are package-private in their own modules and reachable no other way.

Declare one package-private test `@Component` per consuming Maven module:

```java
@Singleton
@Component(modules = {RestTestFixtureModule.class, RestValidationModule.class})
interface ValidationMountComponent {
    RestTestMount testMount();

    @Component.Factory
    interface Factory {
        ValidationMountComponent create(
                @BindsInstance Vertx vertx,
                @BindsInstance @VertxConfig JsonObject config,
                @BindsInstance RestTestContributions contributions);
    }
}
```

**Never name a component accessor `factory()`.** A component declaring a `@Component.Factory` gets a
generated static `factory()` on its `Dagger…` class, and Dagger rejects the collision. Name the mount
accessor `testMount()`.

### RestTestMount

The opaque handle the helpers consume: the graph's mount factory paired with the graph's complete
`Set<Middleware>`. Expose it — not `JaxRsRouterMount.Factory` — from the component, so the ROOT tier
travels with the factory and there is no factory-only helper overload to reach for that would drop
it. Its constructor rejects an empty middleware set, which a real graph never produces.

### RestTestMounts

Pure Vert.x — no Dagger, no JUnit — so it composes with any test framework:

- `router(vertx, mount, resources)` → `Future<Router>`, the API router only.
- `startServer(vertx, mount, resources)` → `Future<HttpServer>` bound to an ephemeral loopback port,
  with both middleware tiers installed. Read the port from `HttpServer.actualPort()` **after** the
  future resolves.
- `startServerBlocking(vertx, mount, resources, timeout)` — for synchronous test methods. Refuses to
  run on a Vert.x event-loop thread rather than deadlocking. The timeout bounds the wait for the
  bind, not the router build.
- `deleteRecursively(directory)` — teardown helper for upload-oriented tests.

These helpers **never close a caller-supplied `Vertx`**, on any path. The returned `HttpServer` is
the caller's to close.

### RestTestContributions

The additive test-only extensions unioned into the framework multibindings. Contributions never
replace framework defaults; a test that needs to take over a default contributes an extension that
out-ranks it by `priority()`.

**Always construct through `RestTestContributions.builder()`** — see Extension Points for why.

```java
RestTestContributions contributions = RestTestContributions.builder()
        .addMiddleware(new HeaderStampingMiddleware())
        .addResponseBodyEncoder(new StreamedUploadEncoder()) // priority 900 — out-ranks the defaults
        .build();
```

---

## Extension Points

The seams below are the module's product, and the set of them is a **compatibility surface**.

| Seam | Framework multibinding it unions into |
|---|---|
| `addMiddleware` | `Set<Middleware>` |
| `addRequestInterceptor` | `Set<RequestInterceptor>` |
| `addResponseBodyEncoder` | `Set<ResponseBodyEncoder>` |
| `addRequestBodyDecoder` | `Set<RequestBodyDecoder>` |
| `addExceptionMapper` | `Set<ExceptionMapper<?>>` |
| `addJsonMapperProfile` | `Set<JsonMapperProfile>` |
| `addFileContentVerifier` | `Set<FileContentVerifier>` |
| `addContextResolver` | `Set<RestContextResolver>` |

Removing a seam breaks every consumer that contributes through it — and **adding** one is not free
either. Each seam reads a component of `RestTestContributions`, which is a `record`, so a new seam
adds a record component. That changes the canonical constructor's arity, which is source-breaking for
any caller invoking it directly or destructuring the record in a record pattern, and binary-breaking
(`NoSuchMethodError`) for consumers already compiled against the previous arity.

Building through `RestTestContributions.builder()` is insulated from that: a new seam only adds an
`addX` method, and every existing call site keeps compiling and linking. Treat the seam list as
published API even though the artifact itself is test support.

Ordering is never decided by a contribution seam. The sets are unordered; the framework's own
providers produce the ordered lists the runtime consumes, and each middleware tier is sorted at its
installation site with the production `OrderedExtension` comparator.

Request-validation strategies are **not** contributed through a seam. Include the strategy's own
Dagger module in the component instead, so the graph never carries two strategies with the same id.

---

## Dependencies

- `dev.vertique:vertique-rest-jaxrs` — the real `JaxRsRouterMount.Factory` and `RestModule` the
  fixture graph resolves.
- `dev.vertique:vertique-rest-core` — `Middleware`, `MiddlewareScope`, the ROOT middleware set, and
  the REST config types.
- `dev.vertique:vertique-config-core` — `ConfigParsingModule`, so config-derived collaborators are
  parsed the production way.
- `com.google.dagger:dagger` — the module and multibinding annotations the fixture is built from.
- `io.vertx:vertx-core` — `Vertx`, `Future`, `HttpServer`.
- `io.vertx:vertx-web` — `Router`, the type both tiers are installed on.

Every dependency is `compile` scope: this is a test-support library whose dependencies are its
product. Consumers depend on the artifact itself in `test` scope.
