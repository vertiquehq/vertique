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
single-mount application still starts its contract load as early as possible. The pre-warm itself is
not awaited: when no mount binds the global path, a failure to load it is only logged as a WARN and
does not fail startup. When a mount does bind it, that mount's startup check awaits it like any other
mount contract.

**Per-mount contract binding (`bindToMount`).** Before any of a mount's operation gates are produced,
the framework calls `bindToMount(MountMeta)` once for that mount. This implementation caches the
mount's contract under `mountMeta.openapiPath()`, starting the load only the first time a given path
is seen (`computeIfAbsent`), so binding is idempotent and safe when several mounts — or several
`HttpVerticle` instances — bind concurrently. A mount declaring **no** `openapiPath` is rejected with
`RestConfigurationException` naming the mount id, because this strategy cannot validate without a
contract. A contract that fails to load is logged as a WARN naming the mount and the path, and its
failure is cached and never retried; the startup contract-load check below turns it into a startup
failure for every mount bound to that path.

**Startup contract-load check.** The module also contributes a contract-load check that runs when each
JAX-RS mount built under `openapi-contract` and bound by the strategy finishes building its router:
that mount's router creation waits for the cached load of every contract bound under the mount's id —
more than one when hand-built mounts sharing a mount path were bound to different contracts. A
contract `vertx-openapi` cannot load fails the mount's router, and so startup — no server listens — with a
`RestConfigurationException` that has no cause. The message names the application and the setting its
contract location came from — `jaxrs.applications.<name>.openapiPath`,
`the @RestApplication annotation's openapiPath`, or `jaxrs.openapiPath`, or a generic phrase when the
application is not in the declared-application view — or, for a hand-built mount serving no declared
application, the mount path.
It then gives exactly one reason:

- `its location must end in .json, .yaml, or .yml` — checked before loading, without regard to case;
- `the file cannot be read`;
- `a servers url is not a valid absolute URL; use an absolute URL or omit servers` — a relative or
  malformed server URL;
- `the file is not a valid OpenAPI contract` — any other contract `vertx-openapi` rejects.

The message never echoes the location, the file's content, or parser text; the strategy's WARN log
still carries the underlying cause for operators. The check starts no load of its own: each distinct
path is still loaded once and that load is reused by every gate, with no per-request reload. Mounts
under any other strategy (`web-validation`, `none`) and empty mounts are untouched. Configurations
that used to start and then answer every validated request with HTTP 500 now fail at startup instead.

A multi-mount application whose mounts use contracts other than the default `openapi.json` should set
`jaxrs.openapiPath` to one of its mount contract paths (or to `null`) so the construction pre-warm
does not log a spurious WARN for a contract no mount uses.

**Gate lifecycle (`gateFor`).** After mount binding, `JaxRsRouteRegistrar` calls
`gateFor(JaxRsOperationDescriptor, OperationSchemas, MountMeta)` once per operation at router-build
time, and the gate closes over that mount's contract. This strategy ignores the synthesized schemas
and always returns a handler. At request time the handler looks up the exact operationId, extracts a
`ValidatableRequest` from the body already buffered by `BodyHandler`, and validates it against the
mount's cached contract. A missing operationId is a server/configuration failure: it is logged with
the mount path and the contract path, and reaches the REST pipeline as HTTP 500. A contract that
failed to load reaches a gate only when the strategy is driven directly, without this module's
startup check; it then fails the same way, as HTTP 500. Validator request failures become sanitized
HTTP 400 responses. The gate reads an
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
`RestModule`, and the startup contract-load check (see [Core Concepts](#core-concepts)) to
`vertique-rest-jaxrs`'s `Set<MountPublicationHook>` multibinding. The check is internal; nothing
in an application calls or replaces it.

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
missing or malformed contract fails the cached future and is not retried; in a composition with this
module, the startup contract-load check fails the router of every mount bound to that path, and so
startup. Driven directly, without that check, the failure surfaces as HTTP 500 when a request reaches
one of that mount's gates, while other mounts keep working.

The strategy still passes the framework's `ParamConversionResolver` (`vertique-rest-core`) into the `DefaultBoundRequest` it constructs. Constructing that binder triggers the JSON-profile first-parse of the body. The binder stores declared scalars as raw strings and does not coerce them. `ParameterExtractor` converts after input policies, through the same resolver as dispatch.

---

## Invariants and Gotchas

- **`openapiPath` must resolve through Vert.x file-system loading.** A missing, unreadable, or
  malformed spec, or one whose location does not end in `.json`, `.yaml`, or `.yml`, fails startup
  through the contract-load check with a cause-free `RestConfigurationException` naming the
  application's contract setting (or the mount path) and the reason, never the location or content.
  The load is cached rather than retried, and the framework does not fall back silently.
- **A relative `servers` URL fails startup.** `vertx-openapi` accepts only absolute server URLs or
  none, so a contract whose `servers` holds a relative URL such as `/api` — or a malformed one —
  cannot be loaded, and startup fails with the reason `a servers url is not a valid absolute URL; use an
  absolute URL or omit servers`. Omit `servers` or use an absolute URL.
- **An unloadable global contract no mount uses does not fail startup.** The construction pre-warm of
  `jaxrs.openapiPath` is not awaited; when no mount binds that path, its failure is only a WARN.
- **Every mount must declare an `openapiPath`.** Binding a mount without one throws
  `RestConfigurationException` at startup naming the mount id; the strategy never borrows another
  mount's contract.
- **Each declared application resolves its own contract location.** `vertique-rest-jaxrs`'s
  `RestApplications` view decides each application's effective `openapiPath` by precedence
  (`jaxrs.applications.<name>.openapiPath`, then the `@RestApplication` annotation's `openapiPath`,
  then the global `jaxrs.openapiPath`); this strategy then caches and validates against one loaded
  contract per distinct `openapiPath` value, so two applications resolving to the same location share
  one contract and two resolving to different locations validate against different contracts. An
  operationId therefore only has to exist in the contract of the mount that serves it — but two
  operations that share an operationId across different JAX-RS mounts are still refused at startup by
  `vertique-rest-jaxrs`'s mount composition validator (its global cross-mount operationId rule, same
  owner exempted), even when both mounts resolve to the same contract location, until a later change
  scopes that rule per mount.
- **This strategy reports `resolvesOperationsFromMountContract()` (`true`).** The rest-jaxrs mount
  composition validator reads that flag from the strategy selected by `jaxrs.validationStrategy`, but
  only once an application is declared (active or not) or an application mount is present; under that
  gate, when the flag is set, it normalizes every JAX-RS mount's contract location and reports one it
  cannot parse as a startup violation, naming the mount and never the value.
- **operationId matching is exact.** A route whose operationId is absent from its mount's contract
  fails requests with HTTP 500 and logs an `ERROR` naming the mount path and the contract path; it
  never falls back to an unvalidated route.
- **File verification is inactive.** `openapi-contract` does not run `@FilePart` constraints or
  `FileContentVerifier`; bound verifiers cause a per-mount startup WARN.
- **Known limit: pattern and format input is not bounded.** The pattern-input bound
  (`jaxrs.validationPatternMaxChars` and `jaxrs.validationPatternMaxTotalChars`) and the reused
  format checks belong to the `web-validation` gate only; those keys are scoped to `web-validation`,
  and this strategy does not consult them. Under `openapi-contract`, a string or object key reaching a
  `pattern`, `patternProperties`, or format position is handed to `vertx-openapi` at whatever length
  the request carries, and is evaluated by that library's own keyword and format handling. An
  application that needs the bound selects `web-validation`.
- **`vertx-openapi` is a preview artifact.** Its API shape may change across Vert.x minor versions. This module pins the `vertx-openapi` version via the parent BOM.
- **Security semantics.** The active security model is OR-of-AND-with-scopes. The `openapi-contract` strategy inherits the same security handling as all other strategies — security is applied by `JaxRsRouteRegistrar`, not by the validation strategy itself. The validation gate runs after the auth/authorization chain and is unaffected by the security model shape.

---

## Dependencies

- `dev.vertique:vertique-rest-jaxrs` (for `RequestValidationStrategy`, descriptors, config, and REST error types)
- `io.vertx:vertx-openapi`
- `com.google.dagger:dagger`
- `jakarta.inject:jakarta.inject-api`
