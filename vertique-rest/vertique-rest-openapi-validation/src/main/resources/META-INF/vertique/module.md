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

The `openapi-contract` strategy starts one asynchronous `OpenAPIContract` load at strategy
construction, caches the contract and the standalone Vert.x validator derived from it
(`io.vertx.openapi.validation.RequestValidator` — a `vertx-openapi` type, not a Vertique one), and
returns a contract-backed gate for every operation. Validation strictness is therefore whatever the
Vert.x validator enforces against the contract; the framework adds no schema layer of its own.
`jaxrs.validationMode` belongs to `web-validation`; this strategy uses the standalone validator's
result directly.

`OpenApiContractValidationStrategy` is a Dagger singleton and loads the one global
`jaxrs.openapiPath` configured for that instance. Multiple mounts can use it only when their
`MountMeta.openapiPath()` values match that loaded path.

**Per-mount contract binding (`bindToMount`).** Before any operation gate is produced, the
framework calls `bindToMount(MountMeta)` once for the selected strategy. This implementation compares
`mountMeta.openapiPath()` with the global contract path and throws `RestConfigurationException` at
startup on divergence, preventing wrong-contract validation.

**Gate lifecycle (`gateFor`).** After mount binding, `JaxRsRouteRegistrar` calls
`gateFor(JaxRsOperationDescriptor, OperationSchemas)` once per operation at router-build time. This
strategy ignores the synthesized schemas and always returns a handler. At request time the handler
looks up the exact operationId, extracts a `ValidatableRequest` from the body already buffered by
`BodyHandler`, and composes validation with the cached contract future. A contract-load failure or a
missing operationId is a server/configuration failure: it is logged and reaches the REST pipeline as
HTTP 500. Validator request failures become sanitized HTTP 400 responses.

`openapi-contract` does not execute `FileContentVerifier` bindings and inherits
`runsFileVerifiers() == false`. When such verifiers are bound, `JaxRsRouterMount` emits one startup
WARN for each mount that selects this strategy; `@FilePart` and verifier execution are provided by
`web-validation` only.

**Error sanitization.** Validation errors produced by the `openapi-contract` strategy are sanitized before reaching the client: submitted values, client-supplied property names, and raw validator internals are stripped from the error response. The 400 response body contains only the violation location (JSON pointer), the failed keyword, and a stable message — no echoed request data.

**JSON profile first-parse.** When a non-`vertx` JSON mapper profile is resolved for a route (see `dev.vertique:vertique-rest-jaxrs` → request-body profiles), the `openapi-contract` strategy runs that profile mapper's **first parse** of the request body — applying its strict parser features and rejecting a non-conforming body with a 400 — *before* OpenAPI schema validation, consistent with the default `web-validation` strategy. No profile is stashed for the `vertx` default, in which case this is a no-op and OpenAPI validation runs unchanged.

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

`RequestValidationStrategy` implementation that loads the `OpenAPIContract` from the configured
`openapiPath` and produces Vert.x contract-backed validation gates per operation.

```java
public class OpenApiContractValidationStrategy implements RequestValidationStrategy {
    @Override public String id() { return "openapi-contract"; }

    @Override
    public void bindToMount(MountMeta mountMeta) { ... }

    @Override
    public Optional<Handler<RoutingContext>> gateFor(
            JaxRsOperationDescriptor operation, OperationSchemas schemas) { ... }
}
```

The contract load begins once when the singleton is constructed and is reused by every returned
gate. A missing or malformed contract fails the cached future; the failure surfaces as HTTP 500 when
a request reaches a gate. The separate mount-path divergence check fails synchronously at router
startup.

The strategy also injects the framework's `ParamConversionResolver` (`vertique-rest-core`) and threads it into the `DefaultBoundRequest` it constructs to trigger the JSON-profile first-parse, so this strategy's parameter coercion goes through the same shared conversion chain as the `web-validation` strategy and the `rest-jaxrs` dispatch path rather than a separate one.

---

## Invariants and Gotchas

- **`openapiPath` must resolve through Vert.x file-system loading.** A missing or malformed spec
  fails the cached contract future and produces HTTP 500 when a request reaches the gate; the
  framework does not fall back silently.
- **operationId matching is exact.** A route whose operationId is absent from the loaded contract
  fails requests with HTTP 500 and logs an `ERROR`; it never falls back to an unvalidated route.
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
