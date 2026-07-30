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

---

## When To Use It

Install this module at test scope in any REST-surface module that needs HTTP-level integration tests
against a real, production-faithful mount instead of hand-rolled encoder, decoder, or
exception-mapper stand-ins. Declare a package-private test `@Component` that includes
`RestTestFixtureModule` alongside any strategy module the consumer needs (for example a
request-validation module), bind the consumer's contributions, and build the router or server through
the mount helpers.

Expose the mount as `RestTestMount testMount();` on that component — that is the type
`RestTestMounts` accepts. `RestTestMount` is opaque on purpose: it pairs the graph's mount factory
with the graph's complete `Set<Middleware>`, and only a DI-built instance can carry both. That is
what lets `startServer` install the ROOT-scoped middleware tier — per-request context lifecycle,
correlation ingress, default headers, contextual logging, request-completion emission, plus any ROOT
middleware the test contributes — above the mount, the way the framework's HTTP verticle does, in
addition to the API-scoped tier `JaxRsRouterMount` installs on the API router itself.

`Middleware.scope()` defaults to ROOT, so a contributed middleware that never overrides it belongs to
that tier.

A server from `startServer` is one JAX-RS mount with both of its production middleware pipelines —
not a stand-in for the whole HTTP verticle. Multi-mount ordering, `MountCustomizer` and
`RouterCustomizer` hooks, and the configured `HttpServerOptions` (TLS, compression, timeouts) are
outside it; a test that asserts on any of those needs a real verticle.

---

## Dependencies

- `dev.vertique:vertique-rest-jaxrs`
- `dev.vertique:vertique-rest-core`
- `dev.vertique:vertique-config-core`
- `com.google.dagger:dagger`
- `io.vertx:vertx-core`
- `io.vertx:vertx-web`
