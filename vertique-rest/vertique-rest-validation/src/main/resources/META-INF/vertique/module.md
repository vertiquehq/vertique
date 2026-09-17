<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST Validation Module

> **Status:** Beta
> **Package:** `dev.vertique.rest.validation`
> **Artifact:** `vertique-rest-validation`
> **Depends on:** rest-jaxrs, rest-core, json-schema, core

Default annotation-driven request-validation strategy for the REST framework. Synthesizes JSON Schemas from JAX-RS and Bean Validation annotations at startup and validates incoming requests against those schemas using `vertx-json-schema`. This is the `web-validation` strategy — the default path that carries no dependency on the preview `vertx-openapi` artifact. The opt-in `openapi-contract` strategy, which validates against the generated `openapi.json`, lives in the sibling `vertique-rest-openapi-validation` module.

---

## When To Use It

`vertique-rest-validation` is on the default request path; most applications install it implicitly by not specifying `jaxrs.validationStrategy`. Its body schemas are synthesized through each route's effective JSON profile, so a profile-specific wire shape — a `vertique-strict` `BigDecimal` carried as a decimal string, for instance — validates correctly here and is no longer a reason to change strategy. Use `vertique-rest-openapi-validation` instead when the generated `openapi.json` must itself be the validating authority, so the published contract document and the runtime check cannot diverge, and the `vertx-openapi` preview dependency is acceptable.

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

**Schema synthesis** happens at router construction: `AnnotationSchemaSource` reads JAX-RS (`@PathParam`, `@QueryParam`, `@NotNull`, `@Pattern`, `@Size`, etc.) and Bean Validation annotations from each resource method and emits JSON Schema fragments. Body-type generation is **always profiled**: every route's body schema is produced by `dev.vertique:vertique-json-schema`'s `AnnotationJsonSchemaGenerator.forInputProfile(profile)` for the effective JSON profile the registrar resolved for that operation, so the synthesized document describes the wire shape that profile's mapper actually parses and the profile's input type overrides land in the schema the gate enforces. There is no profile-agnostic generation path and no fallback to a default generator. This module holds **no profile-selection rule**: the effective profile arrives as the `schemasFor(op, profile)` argument, and nothing here reads a profile id, a mapper identity, or configuration to choose one. Loose-parameter schema assembly remains owned by this module: the generator introspects types and fields, not individual method parameters, and loose parameters are not profiled. A body the profile's generator cannot represent fails router construction with a `RestConfigurationException` naming the operation id and carrying the generator's failure as cause — the mount is never installed and none of its routes serve traffic; request-validation outcomes and error categories for bodies that do generate are unaffected. Each operation's schemas are synthesized once at registration — no per-request reflection. There is deliberately no per-operationId schema cache: duplicate-operationId is enforced only within a single mount, so two mounts may legitimately reuse an operationId for different operations, and an operationId-keyed cache would hand the second mount the first mount's schema.

Under `web-validation` the body document's regular expressions are compiled at router construction too: `WebValidationStrategy.gateFor` walks the operation's body document once, beside its existing validator compilation, and compiles every string-valued member keyed `pattern` and every key of every object keyed `patternProperties`, at any depth and with no position allowlist. An uncompilable expression therefore fails the mount instead of failing per request: the failure is a `RestConfigurationException` naming the operation id, the JSON pointer, and the regex engine's description and index, with the complete pattern text and the `PatternSyntaxException` itself absent from the message, cause, and suppressed chains. A `patternProperties` key is pattern text however well it compiles, so the pointer never names one: a position inside such an object is reported as the key's bracketed ordinal in document order, `…/patternProperties/[key-0]/pattern`, whether the failing expression is the key itself or something beneath it. The assembled message is bounded to 512 UTF-16 code units by eliding the engine's description alone, never splitting a surrogate pair; when the identifying part — operation id, pointer, and index — reaches that bound by itself, it is reported in full and the description is dropped entirely, because cutting the identity would lose the failing position. A property literally named `pattern` is an object under `properties` and is never compiled. A non-regex string that happens to be keyed `pattern` — a `const` value, say — is a spurious startup failure, reported with its JSON pointer. Only `web-validation` performs this walk; loose-parameter patterns keep their pre-existing per-request behavior.

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

Optional seam that produces the validation schemas for a single REST operation. `JaxRsRouteRegistrar` — not the strategy — calls the registered `OperationSchemaSource` once per operation at router build, for **every** operation whatever `jaxrs.validationStrategy` selects, and passes the result to the selected strategy's `gateFor`. `web-validation` then closes over those schemas in its per-route gate handler; a strategy that ignores them, `none` among them, does not stop them being synthesized, so wherever a source is bound a synthesis failure fails the mount under any strategy. No operationId cache is involved (two mounts may legitimately reuse an operationId for different operations, so an operationId-keyed cache would hand the second mount the first mount's schema).

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

`RestModule` declares this seam with `@BindsOptionalOf OperationSchemaSource`: it is a single optional binding, **not** a multibinding. Contribute a custom source with a plain `@Provides` or `@Binds` of `OperationSchemaSource` — never `@IntoSet`, which satisfies nothing here — and do not include `RestValidationModule` in the same component, whose `@Binds` of `AnnotationSchemaSource` would then be a duplicate binding and fail the Dagger build. See [OperationSchemaSource (binding)](#operationschemasource-binding) below.

### AnnotationSchemaSource

Default `OperationSchemaSource` that synthesizes JSON Schema from JAX-RS and Bean Validation annotations. Body types are handed to the shared `AnnotationJsonSchemaGenerator` (`dev.vertique:vertique-json-schema`) built with `forInputProfile(profile)` for the effective profile the registrar resolved for the operation, which translates Java types, profile input overrides, and constraint annotations into canonically ordered JSON Schema 2020-12. Every body schema is generated this way; no route uses a profile-agnostic generator. Parameter schemas are assembled by this class from each parameter's declared type, collection component type, and constraint annotations, and are not profiled.

The protected `generateBodySchema(Type, JsonMapperProfile)` seam is the only generation path and is invoked exactly once per body synthesis; its default implementation parses the generator's canonical document into a fresh `JsonNode`, and a subclass may substitute its own node. The one-argument `generateBodySchema(Type)` seam no longer exists. **This seam is INTERNAL**: it is a substitution point for framework and test code — counting or replacing generation invocations — and not an application contract. It sits outside this module's compatibility promise and may change or be removed in any release; application code should contribute an `OperationSchemaSource` instead of overriding it.

**One generator per profile instance.** The source builds at most one `AnnotationJsonSchemaGenerator` per distinct `JsonMapperProfile` instance, keyed by reference identity, on first use, and retains it for the source's lifetime — at most one even when parallel router builds call `schemasFor` concurrently. Retention is bounded by the number of distinct profile instances the profile registry hands out; the built-in registry and profiles contributed through `RestTestContributions.jsonMapperProfiles` hand out stable instances, so that bound is the profile count. A registry implementation that returns a **fresh profile instance per call** defeats the bound and grows the retained set without limit; that is a misconfiguration, not a supported mode. No generated schema is cached by operation id, Java type, or mapper identity.

**A schema-implementation redirect on an overridden type fails router construction.** `@Schema(implementation = ...)` on a property whose declared type graph carries an effective override for the operation's profile is rejected during generation, so the mount fails to build with a `RestConfigurationException` naming the operation and the property, and is never installed. The redirect still applies exactly as before on a route whose effective profile declares no override for that type. The trigger is configuration-only: `json.jsonProfile: vertique-strict`, or a `@JsonProfile` selection of a profile carrying that override, on an application whose DTOs still carry the previously recommended `@Schema(implementation = String.class)` workaround on a `BigDecimal` property. Remove the redirect — the profile itself supplies the string form, with the decimal grammar and length bound the annotation never carried. Because the schema source runs for every operation whatever validation strategy is selected, this failure is not confined to `web-validation`.

**What it covers:**
- `@PathParam`, `@QueryParam`, `@HeaderParam` — type coercion + nullability
- `@NotNull`, `@Size`, `@Min`, `@Max`, `@Pattern`, `@Email` on parameters and DTO fields
- `@Consumes` → `content-type` enforcement via the 415 gate (separate from JSON Schema)
- `List<T>`, `Optional<T>`, primitive types, records, and nested DTOs

**Which body property shapes are described, and therefore validated.** A body property is described
when Jackson reports it deserializable **or** it has a backing field, and its access is not
read-only. So a private field reachable only through a getter, a field-backed getter-only
`List<String>` or `Map<String, String>`, and a DTO holding such a shape as a property are all
described with their types, formats, and item constraints, and the gate rejects a value the binder
would otherwise coerce at any of those positions — a number posted for a `LocalDate`, a numeric
string for an `Integer`, numeric items for a `List<String>`. A Lombok `@Builder @Jacksonized` type
is filled through its builder, so it is described only when it also carries `@Getter`; without one
its schema stays `{"type":"object"}` and nothing inside it is validated.

A `@JsonAnySetter` or `@JsonAnyGetter` backing store is never described as a named property, because
the keys it collects are extra keys rather than members of the body's property set. It is excluded
by member, never by a name an accessor implies, so a real constrained property is never hidden
because an any-setter's name happens to imply it. Values *inside* a described `Map` property are not
themselves described; constrain them with Bean Validation.

**How a `@JsonAnySetter` body is validated.** The extra keys such a body accepts *are* described, by
the any-setter's value type, so the gate validates them: a body posting `{"x": 5}` to a
`Map<String, String>` any-setter is rejected with 400 where the binder would have stored the string
`"5"`, and `19000` posted to a `Map<String, LocalDate>` any-setter is rejected where the binder would
have bound `2022-01-08`. Valid extras still reach the resource unchanged. An unconstrained value type
(`Object`, `JsonNode`) accepts every JSON value, and a body type carrying a class-level
`@Schema(additionalProperties = FALSE)` stays closed.

Beside those extras the schema also reserves every name Jackson binds on input that the request
schema does not publish, so such a name is rejected rather than routed into the member it names.
Without it, posting `{"role": "admin"}` or `{"id": "forged"}` to an any-setter body would reach the
binder — the read-only and ignored properties are absent from the schema, so nothing else refuses
them — and `{"extras": {"role": "admin"}}` would fill a method `@JsonAnyGetter`'s storage map through
its getter. The reserved set covers ignored and read-only names, a class-level ignoral, a method
any-getter's storage field, and a name bound only through a setter, an accessor pair, a
`@Schema(hidden = true)` field, or a `transient` field. A `@JsonCreator` parameter renamed away from
its field is the documented exception: it carries no member to identify it by, so it is not reserved
and keeps binding as before — constrain it with Bean Validation.

A property marked `@JsonIgnore` or read-only is absent from the request schema, so on an ordinary
body sending it is not a schema error; a write-only property is described and validated. On a body
whose extra keys are described, such a name is reserved and its presence *is* a schema error.

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

`RestModule` declares `@BindsOptionalOf OperationSchemaSource`, so the component holds **at most one**
schema source. This is not a `Set` multibinding: `@IntoSet` contributes to nothing the framework
reads. `RestValidationModule` supplies the one binding, `AnnotationSchemaSource`. A custom validation
assembly therefore replaces it — bind your own implementation and leave `RestValidationModule` out of
the component, because two bindings of the same type fail the Dagger build:

```java
@Provides
static OperationSchemaSource openApiEnrichedSource(OpenApiSchemaStore store) {
    return (descriptor, profile) -> store.schemasFor(descriptor.operationId());
}
```

This example ignores the `profile` parameter. A source that ignores the profile is guaranteeing
that its stored schemas already match that profile's wire shape; the framework cannot check this.

Dropping `RestValidationModule` also drops its `web-validation` strategy contribution, so the
default `jaxrs.validationStrategy` would match no registered strategy and `RequestValidationStrategySelector`
would fail the mount. Either select a strategy you contribute yourself, or re-contribute the
built-in one alongside your source:

```java
@Provides @IntoSet
static RequestValidationStrategy webValidation(WebValidationStrategy strategy) {
    return strategy;
}
```

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
- `dev.vertique:vertique-rest-core` — the `RestConfigurationException` the schema-synthesis and regex-precompilation failures are reported as
- `dev.vertique:vertique-json-schema`
- `dev.vertique:vertique-core`
- `io.vertx:vertx-json-schema`
- `com.google.dagger:dagger`
- `jakarta.inject:jakarta.inject-api`
- `com.fasterxml.jackson.core:jackson-databind`
- `jakarta.validation:jakarta.validation-api`
- `io.swagger.core.v3:swagger-annotations-jakarta`
