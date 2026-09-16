<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST Validation Module

> **Status:** Beta
> **Package:** `dev.vertique.rest.validation`
> **Artifact:** `vertique-rest-validation`
> **Depends on:** rest-jaxrs, json-schema, core

Default annotation-driven request-validation strategy for the REST framework. Synthesizes JSON Schemas from JAX-RS and Bean Validation annotations at startup and validates incoming requests against those schemas using `vertx-json-schema`. This is the `web-validation` strategy — the default path that carries no dependency on the preview `vertx-openapi` artifact. The opt-in `openapi-contract` strategy, which validates against the generated `openapi.json`, lives in the sibling `vertique-rest-openapi-validation` module.

---

## When To Use It

`vertique-rest-validation` is on the default request path; most applications install it implicitly by not specifying `jaxrs.validationStrategy`. Use `vertique-rest-openapi-validation` instead only when spec-strict contract validation is required and the `vertx-openapi` preview dependency is acceptable.

---

## Core Concepts

Validation in this module is **strategy-pluggable**: the runtime selects an implementation by matching the configured `id()` against the registered `Set<RequestValidationStrategy>` bindings. Aggregate mode (collect all violations before failing) is the default; fail-fast (short-circuit at the first violation) is enabled by setting `jaxrs.validationMode = failFast`. The mode is parsed strictly **once at startup** when `WebValidationStrategy` is constructed: only the exact literals `aggregate` and `failFast` are accepted (a blank value defaults to `aggregate`), and any other value — including a wrong-case variant or a typo — fails startup with a `RestConfigurationException` rather than silently changing behavior.

**Multipart file validation** is part of the `web-validation` strategy. `@FilePart` on a named
`FileUpload`/`List<FileUpload>` or an aggregate `List<FileUpload>` constrains the uploaded size and
client-declared content type. Size checks are post-spool: `BodyHandler` has already written the file
under `http.uploadsDirectory`, and `http.maxBodySize` is the only ingress body-size limit — part
count is bounded separately at ingress by `http.maxFormFields`. Declared
media types are matched directionally; the configured subtype may be a wildcard, while a missing,
malformed, or wildcard client declaration fails closed. Text form fields are not file uploads and
are exempt from aggregate file constraints; their ordinary form schema validation still applies.

**Deep file verification** is optional. `FileContentVerifier` implementations are Dagger
multibindings consumed only by `web-validation`. Every verifier runs in `OrderedExtension` order for
each applicable physical upload, sequentially and fail-fast. A verifier rejection is a 400 file
validation error; a synchronous throw, failed future, null future, or null result is a 500
infrastructure error. The built-in magic-byte verifier is opt-in through
`MagicBytesVerifierModule`.

**Schema synthesis** happens at startup: `AnnotationSchemaSource` reads JAX-RS (`@PathParam`, `@QueryParam`, `@NotNull`, `@Pattern`, `@Size`, etc.) and Bean Validation annotations from each resource method and emits JSON Schema fragments. Body-type generation delegates to `dev.vertique:vertique-json-schema`'s `AnnotationJsonSchemaGenerator` in its `withVictoolsDefaults()` mode — a behavior-preserving translation of Java type and constraint annotations into JSON Schema 2020-12 vocabulary, with object keys canonically ordered. Loose-parameter schema assembly remains owned by this module: the generator introspects types and fields, not individual method parameters. A body type the generator cannot represent fails startup with a bounded `JsonSchemaGenerationException`; request-validation outcomes and error categories are unaffected. Each operation's schemas are synthesized once at registration — no per-request reflection. There is deliberately no per-operationId schema cache: duplicate-operationId is enforced only within a single mount, so two mounts may legitimately reuse an operationId for different operations, and an operationId-keyed cache would hand the second mount the first mount's schema.

**Strict boolean coercion.** The `web-validation` gate enforces that boolean parameters accept only the literal strings `"true"` or `"false"`. Values such as `"1"`, `"yes"`, `"on"`, or `""` are rejected with a 400 type-violation error. This prevents silent coercion ambiguity for boolean query/path/header parameters.

**Per-route handler order** (as installed by `JaxRsRouteRegistrar`):
```
auth handler(s) → @Consumes 415 gate → validation gate → OperationHandlerContributors → ResourceMethodInvoker
```

---

## Key Classes

### RestValidationModule

Abstract Dagger `@Module` and the module's wiring entry point. Contributes
`WebValidationStrategy` into the `Set<RequestValidationStrategy>` declared by `RestModule` and binds
`AnnotationSchemaSource` as the single `OperationSchemaSource`.

```java
@Module
public abstract class RestValidationModule {
    @Binds @IntoSet
    abstract RequestValidationStrategy webValidationStrategy(WebValidationStrategy strategy);

    @Binds
    abstract OperationSchemaSource operationSchemaSource(AnnotationSchemaSource source);
}
```

Include in your Dagger `@Component` alongside `RestModule` to activate the default `web-validation` path:

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    RestModule.class,
    RestValidationModule.class,   // activates web-validation
    AppModule.class,
    ResourceModule.class
})
interface AppComponent {
    HttpVerticle httpVerticle();
}
```

### RequestValidationStrategy

SPI for pluggable request validation. Resolved by `id()` from the `Set<RequestValidationStrategy>` multibinding. The framework selects the strategy matching `jaxrs.validationStrategy` (default `"web-validation"`).

```java
public interface RequestValidationStrategy {
    /** Stable identifier matched against {@code jaxrs.validationStrategy}. */
    String id();

    /** True only when this strategy executes bound FileContentVerifiers. */
    default boolean runsFileVerifiers() { return false; }

    /** Produce an optional per-operation gate at router-build time. */
    Optional<Handler<RoutingContext>> gateFor(
        JaxRsOperationDescriptor operation,
        OperationSchemas schemas);

    /**
     * Mount-aware overload, called once per operation in place of the 2-arg form. The default
     * delegates to the 2-arg {@code gateFor}; a strategy whose validation is driven by per-mount
     * state (e.g. {@code openapi-contract}) overrides this form to read {@code mount.openapiPath()}.
     */
    default Optional<Handler<RoutingContext>> gateFor(
        JaxRsOperationDescriptor operation,
        OperationSchemas schemas,
        MountMeta mount) {
        return gateFor(operation, schemas);
    }

    /** Called once per mount before gateFor; no-op unless mount metadata is relevant. */
    default void bindToMount(MountMeta mountMeta) {}
}
```

`JaxRsRouterMount` selects one strategy per mount, warns once when file verifiers are bound but the
selected strategy reports `runsFileVerifiers() == false`, calls `bindToMount(mountMeta)`, then asks
the strategy for each operation's gate via the mount-aware 3-arg `gateFor` — the only form
`JaxRsRouteRegistrar` calls. A present handler is installed between the `@Consumes` gate and
operation contributors; `Optional.empty()` installs no validation handler.

Built-in strategy IDs:

| ID | Module | Behavior |
|----|--------|---------|
| `web-validation` | `vertique-rest-validation` | Annotation-synthesized JSON Schema via `vertx-json-schema` |
| `none` | `vertique-rest-jaxrs` | No request validation installed |
| `openapi-contract` | `vertique-rest-openapi-validation` | Contract-driven validation against the generated `openapi.json` |

### OperationSchemaSource

Optional seam that produces the validation schemas for a single REST operation. The `WebValidationStrategy` calls the registered `OperationSchemaSource` once per operation at mount time and closes over the returned schemas in the per-route gate handler — no operationId cache is involved (two mounts may legitimately reuse an operationId for different operations, so an operationId-keyed cache would hand the second mount the first mount's schema).

```java
public interface OperationSchemaSource {
    /**
     * Produces the parameter and body schemas for the given operation under the effective JSON
     * profile the registrar resolved for it — the profile whose mapper parses the operation's body.
     *
     * @param op      the JAX-RS operation descriptor whose parameters and body are introspected
     * @param profile the effective, registry-resolved profile for this operation; never {@code null}
     * @return the operation's schemas; never {@code null}
     */
    OperationSchemas schemasFor(JaxRsOperationDescriptor op, JsonMapperProfile profile);
}
```

Contribute a custom schema source via `@Provides @IntoSet OperationSchemaSource`.

### AnnotationSchemaSource

Default `OperationSchemaSource` that synthesizes JSON Schema from JAX-RS and Bean Validation annotations. Body types are handed to the shared `AnnotationJsonSchemaGenerator` (`dev.vertique:vertique-json-schema`, `withVictoolsDefaults()` mode), which translates Java types and constraint annotations into canonically ordered JSON Schema 2020-12. Parameter schemas are assembled by this class from each parameter's declared type, collection component type, and constraint annotations.

The protected `generateBodySchema(Type)` seam is overridable: its default implementation parses the generator's canonical document into a fresh `JsonNode`, and a subclass may substitute its own node.

**What it covers:**
- `@PathParam`, `@QueryParam`, `@HeaderParam` — type coercion + nullability
- `@NotNull`, `@Size`, `@Min`, `@Max`, `@Pattern`, `@Email` on parameters and DTO fields
- `@Consumes` → `content-type` enforcement via the 415 gate (separate from JSON Schema)
- `List<T>`, `Optional<T>`, primitive types, records, and nested DTOs

Each operation's schemas are synthesized once at registration and closed over by the per-route gate handler, so no schema is compiled on the request hot path. There is no per-operationId cache (see Core Concepts) — distinct operations sharing an operationId across mounts get distinct schemas.

### WebValidationStrategy

`RequestValidationStrategy` implementation for the `web-validation` strategy. `gateFor` compiles
body and parameter validators once at router-build time and closes over them in the returned
handler. Its gate builds the shared `DefaultBoundRequest` for body validation and uses the same
`ParamConversionResolver` as dispatch.

The gate processes request data in this order:

1. parameter-schema validation;
2. body-schema validation;
3. synchronous `@FilePart` size and declared-content-type validation; and
4. when the synchronous error set is empty, asynchronous `FileContentVerifier` execution.

Aggregate/fail-fast mode applies to stages 1–3. Verifier execution is always sequential and
fail-fast. Each physical `FileUpload` instance is checked and verified at most once even when more
than one resource parameter exposes it. Same-name duplicate uploads are distinct and use error
paths `name`, `name[1]`, and so on.

---

## Extension Points

### RequestValidationStrategy (multibinding)

Contribute a custom validation strategy to override or supplement the built-ins:

```java
@Provides @IntoSet
static RequestValidationStrategy myCustomStrategy(MySchemaStore store) {
    return new RequestValidationStrategy() {
        @Override public String id() { return "my-custom"; }
        @Override
        public Optional<Handler<RoutingContext>> gateFor(
                JaxRsOperationDescriptor operation, OperationSchemas schemas) {
            return Optional.of(ctx -> {
                // custom validation logic; call ctx.next() to proceed or ctx.fail(400) to reject
            });
        }
    };
}
```

Set `jaxrs.validationStrategy = "my-custom"` in `config/application.json` to activate.

### OperationSchemaSource (binding)

`RestValidationModule` binds `AnnotationSchemaSource` as the operation schema source. A custom
validation assembly can bind another implementation instead:

```java
@Provides
static OperationSchemaSource openApiEnrichedSource(OpenApiSchemaStore store) {
    return (descriptor, profile) -> store.schemasFor(descriptor.operationId());
}
```

This example ignores the `profile` parameter. A source that ignores the profile is guaranteeing
that its stored schemas already match that profile's wire shape; the framework cannot check this.

### FileContentVerifier (multibinding)

Contribute trusted, non-blocking deep file checks through the empty
`Set<FileContentVerifier>` declared by `RestModule`:

```java
@Provides @IntoSet
static FileContentVerifier antivirusVerifier(AsyncScanner scanner) {
    return part -> scanner.scan(part.uploadedFileName())
        .map(clean -> clean
            ? FileVerificationResult.accepted()
            : FileVerificationResult.rejected(
                "file content was rejected", "fileContentRejected"));
}
```

`verify(FileUpload)` is invoked on the event loop. Implementations must use async I/O or offload
internally; the framework does not apply `executeBlocking`. Instances may be created more than once,
so every instance must be stateless and thread-safe. Neither the `FileUpload` nor its temporary path
may be retained: the file is valid only until the request ends. Rejection fields are trusted
application response content and are not sanitized; do not include secrets, filenames, temporary
paths, raw headers, or submitted content.

A verifier that does not apply returns an already-completed
`FileVerificationResult.accepted()`. Applications can opt into the dependency-free leading-byte
check by adding `MagicBytesVerifierModule.class` to their component. Its bounded catalog recognizes
common image, document, archive/compression, audio/video container, WebAssembly, and web-font
signatures within the first 12 bytes. It is a spoofing heuristic, not malware or structural format
validation; unmapped declared types are accepted without I/O.

---

## Configuration

| Key | Default | Description |
|-----|---------|-------------|
| `jaxrs.validationStrategy` | `"web-validation"` | ID of the `RequestValidationStrategy` to activate |
| `jaxrs.validationMode` | `"aggregate"` | `"aggregate"` (collect all violations, default) or `"failFast"` (stop on first); any other value fails startup |
| `http.maxBodySize` | `2097152` | Global ingress body limit; the only pre-validation upload-size limit |
| `http.maxFormFields` | `256` | Pre-validation ingress limit on part count, shared across multipart file parts, multipart text parts, and URL-encoded attributes |
| `http.uploadsDirectory` | `"file-uploads"` | Non-blank Vert.x multipart spool directory; temporary files are always deleted at request end |

---

## Dependencies

- `dev.vertique:vertique-rest-jaxrs`
- `dev.vertique:vertique-json-schema`
- `dev.vertique:vertique-core`
- `io.vertx:vertx-json-schema`
- `com.google.dagger:dagger`
- `jakarta.inject:jakarta.inject-api`
