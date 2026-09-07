<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST OpenAPI Validation Module

> **Status:** Beta
> **Package:** `dev.vertique.rest.openapi.validation`
> **Artifact:** `vertique-rest-openapi-validation`
> **Depends on:** rest-jaxrs

Opt-in `openapi-contract` request-validation strategy that validates incoming requests against the generated `openapi.json` using `vertx-openapi`. This module is the only place in the framework that takes a **production/runtime** dependency on `io.vertx:vertx-openapi`; the default `web-validation` path (in `vertique-rest-validation`) carries no such dependency. (`vertx-web-openapi-router` appears as a test-scope dependency in `vertique-rest-security` and `vertique-rest-auth-jwt` for legacy IT support, but those are test-only and do not affect the production classpath.) Applications install this module only when spec-strict contract validation and the `vertx-openapi` preview artifact are both acceptable.

---

## When To Use It

Install `OpenApiContractValidationModule` when:
- Contract-driven validation (types, patterns, enums) from the generated spec is required
- The application can accept the `vertx-openapi` preview artifact on the runtime classpath

For most new applications, the default `web-validation` strategy (annotation-synthesized schemas via `vertique-rest-validation`) covers the same constraints without the preview artifact. Use this module only when a pre-existing spec is the authoritative source and annotation parity is insufficient.

---

## Core Concepts

The `openapi-contract` strategy loads an `OpenAPIContract` asynchronously, caches the contract and the
standalone Vert.x validator derived from it (`io.vertx.openapi.validation.RequestValidator` — a
`vertx-openapi` type, not a Vertique one), and returns a contract-backed gate for every operation.
Validation strictness is therefore whatever the Vert.x validator enforces against the contract; the
framework adds no schema layer of its own. `jaxrs.validationMode` belongs to `web-validation`; this
strategy uses the standalone validator's result directly.

`OpenApiContractValidationStrategy` is a Dagger singleton, but the contract it validates against is
resolved **per mount**: it keeps one loaded contract plus validator per distinct
`MountMeta.openapiPath()`. Mounts declaring different `openapiPath` values are supported and each
validates against its own contract; mounts sharing one path share one load. The global
`jaxrs.openapiPath` is loaded ("pre-warmed") when the singleton is constructed, so the usual
single-mount application still starts its contract load as early as possible.

**Per-mount contract binding (`bindToMount`).** Before any of a mount's operation gates are produced,
the framework calls `bindToMount(MountMeta)` once for that mount. This implementation caches the
mount's contract under `mountMeta.openapiPath()`, starting the load only the first time a given path
is seen (`computeIfAbsent`), so binding is idempotent and safe when several mounts — or several
`HttpVerticle` instances — bind concurrently. A mount declaring **no** `openapiPath` is rejected with
`RestConfigurationException` naming the mount id, because this strategy cannot validate without a
contract. A contract that fails to load is logged as a WARN naming the mount and the path, and its
failure is cached: it is never retried, and it fails only that mount's own operations.

A multi-mount application whose mounts use contracts other than the default `openapi.json` should set
`jaxrs.openapiPath` to one of its mount contract paths (or to `null`) so the construction pre-warm
does not log a spurious WARN for a contract no mount uses.

**Gate lifecycle (`gateFor`).** After mount binding, `JaxRsRouteRegistrar` calls
`gateFor(JaxRsOperationDescriptor, OperationSchemas, MountMeta)` once per operation at router-build
time, and the gate closes over that mount's contract. This strategy ignores the synthesized schemas
and always returns a handler. At request time the handler looks up the exact operationId, extracts a
`ValidatableRequest` from the body already buffered by `BodyHandler`, and validates it against the
mount's cached contract. A contract-load failure or a missing operationId is a server/configuration
failure: it is logged with the mount path and the contract path, and reaches the REST pipeline as
HTTP 500. Validator request failures become sanitized HTTP 400 responses. The gate reads an
already-loaded contract synchronously and continues the request on the request's own Vert.x context;
a still-loading contract is awaited and the continuation is dispatched back onto that context, so a
request is never continued on the event loop that loaded the contract.

The two-argument `gateFor(JaxRsOperationDescriptor, OperationSchemas)` is a legacy form the framework
no longer calls. It validates against the global `jaxrs.openapiPath` contract and fails closed with an
`IllegalStateException` — naming the bound contract paths — once any mount with a different
`openapiPath` is bound, rather than validating that mount against the wrong contract.

`openapi-contract` does not execute `FileContentVerifier` bindings and inherits
`runsFileVerifiers() == false`. When such verifiers are bound, `JaxRsRouterMount` emits one startup
WARN for each mount that selects this strategy; `@FilePart` and verifier execution are provided by
`web-validation` only.

**Error sanitization.** Validation errors produced by the `openapi-contract` strategy are sanitized before reaching the client: submitted values, client-supplied property names, and raw validator internals are stripped from the error response. The 400 response body contains only the violation location (JSON pointer), the failed keyword, and a stable message — no echoed request data.

**JSON profile first-parse.** When a route's resolved JSON mapper is not the same instance as the process codec's mapper (see `dev.vertique:vertique-rest-jaxrs` → request-body profiles, whose resolver returns that identity sentinel), the `openapi-contract` strategy runs that profile mapper's **first parse** of the request body — applying its strict parser features and rejecting a non-conforming body with a 400 — *before* OpenAPI schema validation, consistent with the default `web-validation` strategy. No profile is stashed when the route's mapper is the process codec's own instance, in which case this is a no-op and OpenAPI validation runs unchanged.

---

## Key Classes

### OpenApiContractValidationModule

Dagger `@Module` and the module's wiring entry point. Contributes
`OpenApiContractValidationStrategy` to the `Set<RequestValidationStrategy>` multibinding declared by
`RestModule`.

```java
@Module
public abstract class OpenApiContractValidationModule { ... }
```

Include alongside `RestModule` to make the `openapi-contract` strategy available:

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    RestModule.class,
    OpenApiContractValidationModule.class, // contributes openapi-contract strategy
    AppModule.class,
    ResourceModule.class
})
interface AppComponent {
    HttpVerticle httpVerticle();
}
```

Then activate it in config:

```json
{
  "jaxrs": {
    "validationStrategy": "openapi-contract",
    "openapiPath": "openapi.json"
  }
}
```

### OpenApiContractValidationStrategy

`RequestValidationStrategy` implementation that loads an `OpenAPIContract` per mount `openapiPath` and
produces Vert.x contract-backed validation gates per operation.

```java
public class OpenApiContractValidationStrategy implements RequestValidationStrategy {
    @Override public String id() { return "openapi-contract"; }

    // Caches this mount's contract; rejects a mount that declares no openapiPath.
    @Override
    public void bindToMount(MountMeta mountMeta) { ... }

    // The form the framework calls: validates against the registering mount's own contract.
    @Override
    public Optional<Handler<RoutingContext>> gateFor(
            JaxRsOperationDescriptor operation, OperationSchemas schemas, MountMeta mount) { ... }

    // Legacy, mount-agnostic form: the global contract only, fails closed on divergent mounts.
    @Override
    public Optional<Handler<RoutingContext>> gateFor(
            JaxRsOperationDescriptor operation, OperationSchemas schemas) { ... }
}
```

Each distinct contract path is loaded once — at construction for the global `jaxrs.openapiPath`, at
`bindToMount` for a mount's own path — and that one load is reused by every gate built for it. A
missing or malformed contract fails the cached future and is not retried; the failure surfaces as
HTTP 500 when a request reaches one of that mount's gates, while other mounts keep working.

The strategy also injects the framework's `ParamConversionResolver` (`vertique-rest-core`) and threads it into the `DefaultBoundRequest` it constructs to trigger the JSON-profile first-parse, so this strategy's parameter coercion goes through the same shared conversion chain as the `web-validation` strategy and the `rest-jaxrs` dispatch path rather than a separate one.

---

## Invariants and Gotchas

- **`openapiPath` must resolve through Vert.x file-system loading.** A missing or malformed spec
  fails the cached contract future and produces HTTP 500 when a request reaches that mount's gate; the
  failure is cached rather than retried per request, and the framework does not fall back silently.
- **Every mount must declare an `openapiPath`.** Binding a mount without one throws
  `RestConfigurationException` at startup naming the mount id; the strategy never borrows another
  mount's contract.
- **The contract is chosen per mount, not per application.** Two mounts with different `openapiPath`
  values validate against different contracts. An operationId therefore only has to exist in the
  contract of the mount that serves it.
- **operationId matching is exact.** A route whose operationId is absent from its mount's contract
  fails requests with HTTP 500 and logs an `ERROR` naming the mount path and the contract path; it
  never falls back to an unvalidated route.
- **File verification is inactive.** `openapi-contract` does not run `@FilePart` constraints or
  `FileContentVerifier`; bound verifiers cause a per-mount startup WARN.
- **`vertx-openapi` is a preview artifact.** Its API shape may change across Vert.x minor versions. This module pins the `vertx-openapi` version via the parent BOM.
- **Security semantics.** The active security model is OR-of-AND-with-scopes. The `openapi-contract` strategy inherits the same security handling as all other strategies — security is applied by `JaxRsRouteRegistrar`, not by the validation strategy itself. The validation gate runs after the auth/authorization chain and is unaffected by the security model shape.

---

## Dependencies

- `dev.vertique:vertique-rest-jaxrs` (for `RequestValidationStrategy`, descriptors, config, and REST error types)
- `io.vertx:vertx-openapi`
- `com.google.dagger:dagger`
- `jakarta.inject:jakarta.inject-api`
