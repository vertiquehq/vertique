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
same multibindings production uses, plus `RestTestMounts`, a pure Vert.x helper for turning a built
mount factory into a router or a running HTTP server.

---

## When To Use It

Install this module at test scope in any REST-surface module that needs HTTP-level integration tests
against a real, production-faithful mount instead of hand-rolled encoder, decoder, or
exception-mapper stand-ins. Declare a package-private test `@Component` that includes
`RestTestFixtureModule` alongside any strategy module the consumer needs (for example a
request-validation module), bind the consumer's contributions, and build the router or server through
the mount helpers.

---

## Dependencies

- `dev.vertique:vertique-rest-jaxrs`
- `dev.vertique:vertique-rest-core`
- `dev.vertique:vertique-config-core`
- `com.google.dagger:dagger`
- `io.vertx:vertx-core`
- `io.vertx:vertx-web`
