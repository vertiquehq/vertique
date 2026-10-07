<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST Validation Module

> **Status:** Stable
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
under `http.uploadsDirectory`. Ingress body size is bounded by `http.maxBodySize` for every request
and by `http.maxMultipartBodySizeBytes` for `multipart/form-data` (effective limit is the tighter of
the two; 413 fail-closed, including Content-Length early reject before spool when present). Part
count is bounded separately at ingress by `http.maxFormFields`. Declared
media types are matched directionally; the configured subtype may be a wildcard, while a missing,
malformed, or wildcard client declaration fails closed. Text form fields are not file uploads and
are exempt from aggregate file constraints; their ordinary form schema validation still applies.

**Deep file verification** is optional. `FileContentVerifier` implementations are Dagger
multibindings consumed only by `web-validation`. Every verifier runs in `OrderedExtension` order for
each applicable physical upload, sequentially and fail-fast. A verifier rejection is a 400 file
validation error; a synchronous throw, failed future, null future, null result, or wait-deadline
timeout is a 500 infrastructure error. Each verifier invocation is bounded by
`jaxrs.fileContentVerifierDeadlineMs` (per call, not an overall chain budget); the framework stops
waiting on timeout but does not cancel verifier-owned work — see [FileContentVerifier](#filecontentverifier-multibinding).
The built-in magic-byte verifier is opt-in through `MagicBytesVerifierModule`.

**Schema synthesis** happens at router construction: `AnnotationSchemaSource` reads JAX-RS (`@PathParam`, `@QueryParam`, `@NotNull`, `@Pattern`, `@Size`, etc.) and Bean Validation annotations from each resource method and emits JSON Schema fragments. Body-type generation is **always profiled**: every route's body schema is produced by `dev.vertique:vertique-json-schema`'s `AnnotationJsonSchemaGenerator.forInputProfile(profile)` for the effective JSON profile the registrar resolved for that operation, so the synthesized document describes the wire shape that profile's mapper actually parses and the profile's input type overrides land in the schema the gate enforces. There is no profile-agnostic generation path and no fallback to a default generator. This module holds **no profile-selection rule**: the effective profile arrives as the `schemasFor(op, profile)` argument, and nothing here reads a profile id, a mapper identity, or configuration to choose one. Loose-parameter schema assembly remains owned by this module: the generator introspects types and fields, not individual method parameters, and loose parameters are not profiled. A body the profile's generator cannot represent fails router construction with a `RestConfigurationException` naming the operation id and carrying the generator's failure as cause — the mount is never installed and none of its routes serve traffic; request-validation outcomes and error categories for bodies that do generate are unaffected. Each operation's schemas are synthesized once at registration — no per-request reflection. There is deliberately no per-operationId schema cache: duplicate-operationId is enforced only within a single mount, so two mounts may legitimately reuse an operationId for different operations, and an operationId-keyed cache would hand the second mount the first mount's schema.

Under `web-validation` the body document's regular expressions are compiled at router construction too: `WebValidationStrategy.gateFor` walks the operation's body document once, beside its existing validator compilation, and compiles every string-valued member keyed `pattern` and every key of every object keyed `patternProperties`, at any depth and with no position allowlist. An uncompilable expression therefore fails the mount instead of failing per request: the failure is a `RestConfigurationException` naming the operation id, the JSON pointer, and the regex engine's description and index, with the complete pattern text and the `PatternSyntaxException` itself absent from the message, cause, and suppressed chains. A `patternProperties` key is pattern text however well it compiles, so the pointer never names one: a position inside such an object is reported as the key's bracketed ordinal in document order, `…/patternProperties/[key-0]/pattern`, whether the failing expression is the key itself or something beneath it. The assembled message is bounded to 512 UTF-16 code units by eliding the engine's description alone, never splitting a surrogate pair; when the identifying part — operation id, pointer, and index — reaches that bound by itself, it is reported in full and the description is dropped entirely, because cutting the identity would lose the failing position. A property literally named `pattern` is an object under `properties` and is never compiled. The walk never enters literal data — the value of `const`, `enum`, `default`, `examples`, or `example` — so a `pattern` member inside such a value is data and is not compiled; a property whose own name is one of those keywords is still a schema and is walked. A non-regex string keyed `pattern` anywhere else, including inside an annotation keyword a profile fragment carries, is a spurious startup failure, reported with its JSON pointer. Only `web-validation` performs this walk; loose-parameter patterns keep their pre-existing per-request behavior.

**Strict boolean coercion.** The `web-validation` gate enforces that boolean parameters accept only the literal strings `"true"` or `"false"`. Values such as `"1"`, `"yes"`, `"on"`, or `""` are rejected with a 400 type-violation error. This prevents silent coercion ambiguity for boolean query/path/header parameters.

**Per-route handler order** (as installed by `JaxRsRouteRegistrar`):
```
auth handler(s) → @Consumes 415 gate → validation gate → OperationHandlerContributors → ResourceMethodInvoker
```

---

## Key Classes

### RestValidationModule

Abstract Dagger `@Module` and the module's wiring entry point. Contributes
`WebValidationStrategy` into the `Set<RequestValidationStrategy>` declared by `RestModule`, binds
`AnnotationSchemaSource` as the single `OperationSchemaSource`, and declares the optional
`Validator` binding `AnnotationSchemaSource` consumes as `Optional<Validator>`.

```java
@Module
public abstract class RestValidationModule {
    @Binds @IntoSet
    abstract RequestValidationStrategy webValidationStrategy(WebValidationStrategy strategy);

    @Binds
    abstract OperationSchemaSource operationSchemaSource(AnnotationSchemaSource source);

    @BindsOptionalOf
    abstract Validator validator();
}
```

An application that also installs `vertique-validation`'s `ValidationModule` (which binds a plain
`Validator`) gets the Bean Validation metadata constraint source automatically, with no additional
wiring; one that does not gets `Optional.empty()` and the annotation walk, unchanged.

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

The protected `generateBodySchema(Type, JsonMapperProfile)` seam is the only generation path and is invoked exactly once per body synthesis; its default implementation returns the profile's generator's `CanonicalSchema`, and a subclass may substitute its own. The caller builds the body `JsonObject` from `CanonicalSchema#json()`, strips the swagger sentinel, and attaches `CanonicalSchema#redactionManifest()` to the schema as its opaque body provenance (`OperationSchemas#bodySchemaProvenance(Class)`), unchecked: a manifest that no longer matches the body it travels with — because an override substituted the document, or the body was later edited or replaced — is never a failure here or at the validation gate; it surfaces only where a consumer checks the pairing (`RedactionManifest#matches`). **This seam is INTERNAL**: it is a substitution point for framework and test code — counting or replacing generation invocations — and not an application contract. It sits outside this module's compatibility promise and may change or be removed in any release; application code should contribute an `OperationSchemaSource` instead of overriding it.

**One generator per profile instance.** The source builds at most one `AnnotationJsonSchemaGenerator` per distinct `JsonMapperProfile` instance, keyed by reference identity, on first use, and retains it for the source's lifetime — at most one even when parallel router builds call `schemasFor` concurrently. Retention is bounded by the number of distinct profile instances the profile registry hands out; the built-in registry and profiles contributed through `RestTestContributions.jsonMapperProfiles` hand out stable instances, so that bound is the profile count. A registry implementation that returns a **fresh profile instance per call** defeats the bound and grows the retained set without limit; that is a misconfiguration, not a supported mode. No generated schema is cached by operation id, Java type, or mapper identity.

**Optional Bean Validation metadata source.** `AnnotationSchemaSource` declares an
`Optional<jakarta.validation.Validator>` constructor parameter, satisfied by `RestValidationModule`'s
`@BindsOptionalOf Validator` — the same pattern `vertique-mcp-server`'s `McpServerModule` already
uses for tool-input validation. When present (an application depends on `vertique-validation`, which
binds a plain `Validator`, or an application binds its own), every body schema this source
synthesizes is built through `AnnotationJsonSchemaGenerator.forInputProfile(profile, validator)`, so
constraints additionally come from Bean Validation metadata — the annotation walk still runs first,
as the floor every generation mode shares, and the metadata source only supplements or, for a
bounded set of shapes, corrects it — see `vertique-json-schema`'s module reference for the join
rules, the group filter, and the rendered keyword table. When absent, generation is exactly what it
was before this binding existed: the annotation walk alone, with no behavior change. `AnnotationSchemaSource`'s no-argument public constructor
is retained (equivalent to `Optional.empty()`) for source compatibility with code that constructs it
directly rather than through Dagger; the `@Inject`-annotated constructor is the
`Optional<Validator>`-accepting one.

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
because an any-setter's name happens to imply it. **Values *inside* a described `Map` property are
themselves described too** (rest-023 T003): the value type's own schema — including a type-use
constraint declared on it — publishes as the `Map`'s `additionalProperties`, so the gate rejects a
wrong-typed or constraint-violating value the same way it rejects one at a named position; a `null`
inside a non-`Optional` map value is rejected as wrong-typed, and a `Map<String, Optional<T>>` entry
admits an explicit `null`. An explicit `@Schema(additionalProperties = TRUE|FALSE)` on the `Map`-typed
property itself has no effect on this rendering (`vertique-json-schema`'s own module reference).

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

**How a `@JsonAlias` spelling is validated.** Every spelling of a described body property is listed
in the request schema with a copy of that property's own schema, so the gate applies the same
constraints to it: `{"qty": 999}` is rejected with 400 against a `@Max(10) @JsonAlias("qty")
quantity`, and an unknown constant under an enum property's alias is rejected where the binder would
have bound the `@JsonEnumDefaultValue` constant. A required aliased property is satisfied by any one
of its spellings, so `{"qty": 5}` alone is accepted, and a body carrying none of them is still
rejected. Whether one body may carry several spellings at once follows the route's effective profile
and nothing else: a profile whose mapper enables strict duplicate detection — `vertique-strict`
among the built-ins — rejects `{"quantity": 5, "qty": 5}` with 400, while `system` and `vertique`
accept it. That rule is the gate's alone; every profile's binder accepts both spellings, so a route
on the `none` strategy is unaffected.

A spelling more than one property of the body type claims is described nowhere, because the
generator cannot predict which property Jackson binds it to; on a body whose extra keys are
described it is reserved, and elsewhere it reaches the binder unvalidated — constrain that shape
with Bean Validation. A spelling of a property the request schema does not publish, such as a
`@Schema(hidden = true)` field's alias, is likewise not described and stays a reserved name.

Each operation's schemas are synthesized once at registration and closed over by the per-route gate handler, so no schema is compiled on the request hot path. There is no per-operationId cache (see Core Concepts) — distinct operations sharing an operationId across mounts get distinct schemas.

### WebValidationStrategy

`RequestValidationStrategy` implementation for the `web-validation` strategy. `gateFor` compiles
body and parameter validators once at router-build time and closes over them in the returned
handler. Its gate builds the shared `DefaultBoundRequest` for body validation. Declared scalars
stay raw strings on that binder. Conversion runs later in `ParameterExtractor`, through the same
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

**A schema failure is always a rejection.** A validation call — the body, and each declared
parameter independently — whose result the validator does not report valid is rejected with 400,
whatever keywords the reported errors carry. Each reported error normally becomes one error detail
naming the violated keyword as `type` and its expected value as `args` — the declared number or
pattern, also when the keyword sits inside an `allOf`, `anyOf`, or `oneOf` branch, a `prefixItems`
position, behind a local `$ref`, or under an escaped property name, so a `maxLength` there reads
`{"maxLength": 3}` and "must have a maximum length of 3". A `type` detail names the declared type the
same way: `{"type": "string"}` and "must be of type: string", or for a declared list such as
`["string", "null"]` the JSON array `{"type": ["string", "null"]}` and "must be of type: string or
null". A declared value that cannot be reached — behind a remote `$ref` or a `$dynamicRef` — is
reported as `{"<keyword>": true}`; for `type` the message then reads "must be of the required
type". Structural keywords (`oneOf`, `anyOf`, `not`, `additionalProperties`) describe how the
schema was traversed rather than a constraint the client can act on, so they produce no such detail.
`propertyNames` and `patternProperties` — the two keywords this module's own `vertique-json-schema`
generator extends beyond vertx-json-schema's own generated rules (a case-insensitive type's non-ASCII
fold refusal, and its folded per-casing entries) — get a fixed, value-free message of their own
instead ("contains a property name the schema does not allow"; the generic structural fallback
below) rather than falling to the raw validator message: vertx-json-schema's own wrapper text for
both names the client's submitted key verbatim, and for `patternProperties` the generated regex too,
which would otherwise reach the response exactly like the raw-value echo this module's own detail
generation exists to prevent. Every other keyword this method does not explicitly render falls to a
value-free `"<keyword> constraint violated"` too — the raw validator message is never used as a
silent fallback for an unreviewed keyword. When every error a call reported is
structural, that call instead contributes exactly one value-free detail: it names the failing
instance location as its `path` and carries no `type` and no `args`. That location is cut back to the
part the schema declares, so it names no text the client chose: an undeclared property under a closed
object is reported at `#/<the client's own key>`, and the detail names the containing location `#`
instead, while a failure under a declared property keeps that property's location. Its message is a
fixed literal, so no submitted value and no raw validator message reaches the response through it.
A concrete detail's `path` is cut back by the same rule: a wrong-typed value under an undeclared key
— an extra an any-setter type describes, say — is reported at `#/<the client's own key>`, and its
detail names the containing location instead, so the key never reaches the response. The location
is kept segment by segment, up to the first segment the schema does not declare: a segment is kept
when it is a name a `properties` entry spells, or an array index a `prefixItems` position or an
`items` schema covers, at that point or in any `allOf`, `anyOf`, or `oneOf` branch there, following
local `$ref`s. A field inside a nullable nested object, published as `anyOf: [null, $ref]`, is
therefore named, and so is a tuple index. A declared name is kept in the spelling the validator
reports, RFC 6901-escaped and percent-encoded (`a b` as `a%20b`, `a/b` as `a~1b`). A key only
`additionalProperties` or `patternProperties` admits is never kept, and neither is anything under a
reference or composition that cannot be resolved within a fixed bound. An all-digit segment is kept
as an index only when it is a plausible one — at most ten digits, no leading zero other than `0`
itself, inside the tuple's length or under an `items` schema other than `false` — so a digit-only key
a client sends for the open-object branch of a map-or-list composition is cut like any other key. The rule counts per call, so a body failure is
never masked by a detail produced for a parameter, and a call that already produced a concrete detail
gains nothing extra. A body the validator reports valid still produces no detail and no rejection.
This is what makes a schema rule published as `oneOf`, `anyOf`, or `not` branches — the strict
one-spelling alias rule above among them — enforceable at the gate.

**Pattern and bounded-format input is bounded.** A regular expression can take far longer to
evaluate than its input is long, so the gate limits the client text that reaches one. It rejects a
string value or object key longer than `jaxrs.validationPatternMaxChars` (default 4,096 UTF-16 code
units) before any of these checks runs on it, with one schema-shape exception listed under "What the
limits leave open" below:

- a `pattern`, including one under `propertyNames`, which applies it to object keys;
- the key expressions of a non-empty `patternProperties`, for every key of that object; and
- the three bounded formats: `idn-hostname`, `idn-email`, and `regex`.

The gate also adds up the lengths of the strings and keys reaching those checks across the whole
request — every parameter and the body — and rejects the request once the total exceeds
`jaxrs.validationPatternMaxTotalChars` (default 262,144). A string checked at two positions counts
at each: two `pattern` schemas that both apply to it, or one schema carrying both a `pattern` and a
bounded format. The total covers one request's parameter and body validation and starts at zero for
every request.

No other format is bounded or counted. The date, time, duration, email, hostname, IP-address, and
UUID formats, and an application-defined format, are checked exactly as the validator checks them
without the limits. `iri` and `iri-reference` are not checked by the validator at all: any string
passes them. A body of dates, timestamps, or identifiers with no `pattern` is therefore never
rejected by the limits, however large it is. Only the `web-validation` strategy applies the limits;
`openapi-contract` does not.

**The `uri`, `uri-reference`, `url`, `uri-template`, `json-pointer`, `relative-json-pointer`, and
`json-pointer-uri-fragment` formats are decided by reused implementations, never by the validator's
own expressions for them,** with one schema-shape exception listed under "What the limits leave
open" below. Those seven expressions can overflow the stack or take far longer than
their input is long, so the gate keeps them from ever running: in its private compiled copy of each
schema it renames every `format` value among the seven to `x-vertique-format-<name>` — for example
`format: uri` becomes `format: x-vertique-format-uri` — a name the validator does not recognize and
therefore passes unconditionally, and the gate's own format check then decides that renamed name.
The rename happens only in the gate's private copy: the schema an `OperationSchemaSource` returns,
and every published document built from it, keep the standard format names throughout. No regular
expression decides any of the seven. `uri`, `uri-reference`, and `url` are decided by parsing the
value with `java.net.URI`; `json-pointer` and `relative-json-pointer` are decided by one
left-to-right pass over the string; `json-pointer-uri-fragment` is decided by percent-decoding its
fragment through `java.net.URI` and then running the same left-to-right pass over the result; and
`uri-template` is decided by the framework's own single-forward-pass syntax scanner for RFC 6570
template syntax, written for this project.

Because these reused implementations are not the validator's own expressions, a small number of
values judge differently than the validator alone would judge them; every difference found against
the pinned corpora — the JSON Schema Test Suite files and the tested corpus below — is recorded here,
as tested, so no new check is ever added to chase it. Against the tested corpus, each format's
recorded differences are:

- `uri`: `http://` (rejected here); a value that is valid only without its trailing line feed, for
  example `http://foo.bar/\n` (rejected here); `http://a.com/?x[0]=1` (accepted here); and
  `http://[v1.x]/` (rejected here).
- `uri-reference`: `http://` and `a:` (rejected here); a value that is valid only without its
  trailing line feed, for example `/p\n` (rejected here); `http://a.com/?x[0]=1` and `#[0]`
  (accepted here); and `http://[v1.x]/` (rejected here).
- `json-pointer`: a value that is valid only without its trailing line feed, for example `\n`
  (rejected here).
- `relative-json-pointer`: a value that is valid only without its trailing line feed, for example
  `0\n` (rejected here).
- `json-pointer-uri-fragment`: a value that is valid only without its trailing line feed, for
  example `#/a\n` (rejected here); `#/%7E2` and a trailing `#/%7E` (rejected here); and `#/ä`,
  `#/a?b`, and `#/a[0]` (accepted here).
- `uri-template`: `{a.b}`, `{a.%41}`, and `a'b` (accepted here); a value that is valid only without
  its trailing line feed, for example `{a}\n` (rejected here); and `a\u0085b`, a lone high surrogate
  (`a\uD800b` or a trailing `a\uD800`), a lone low surrogate (`a\uDC00b`), U+FFFF, U+E0001, U+EFFFE,
  and U+FFFFE (rejected here).

Against the JSON Schema Test Suite's own format cases, `uri` differs only on the suite's "non-numeric
port is invalid" and "leading zero in an embedded IPv4 address is invalid" cases — both accepted by
`java.net.URI` where the suite expects rejection; `uri-reference` differs only on the suite's "a
network-path reference with an empty authority" case — rejected by `java.net.URI` where the suite
expects acceptance — and, more leniently than the suite, its "a non-numeric port in a network-path
reference", "more than one at-sign in the authority", and "a leading zero in the IPv4 part of an IPv6
literal" cases; `json-pointer`, `relative-json-pointer`, `json-pointer-uri-fragment`, and
`uri-template` have no recorded suite difference.

`url` keeps the validator's own scheme and host filtering: an absolute value is accepted only when
its scheme is `http`, `https`, or `ftp`; its host is present and is not an IPv6 literal; a
dotted-quad host is rejected in `0/8`, `10/8`, `127/8`, `169.254/16`, `192.168/16`, `172.16/12`, and
`224/4` and above, and a dotted-quad octet longer than one character that starts with `0` is
rejected outright, for example `0177.0.0.1`: common URL and address parsers read such an octet as
octal, so `0177.0.0.1` is `127.0.0.1` to them, and rejecting it here keeps the loopback and
private-range filtering above from being bypassed by an octal-looking octet; a host that is not a
dotted quad is rejected unless it contains a dot and ends in an alphabetic label of two or more
letters, so `localhost` and `intranet` are rejected; and a port, when present, is rejected outside 2
to 5 digits. That filtering matches the validator's own verdict, `[::1]` and every `file:`, `jar:`,
and `mailto:` value included. Outside that filtering, fifteen inputs are recorded as differing from
the validator: `http://bücher.de/` and `http://例え.テスト/` (`java.net.URI` cannot parse a non-ASCII
host at all, so the gate rejects both; an application that needs to accept one should convert it to
punycode first), `http://a--b.com/`, `http://xn--bcher-kva.de/`, `http://a.com./`,
`http://1.2.3.0/`, `http://1.2.3.255/`, `http://example.com?x=1`, `http://example.com#f`,
`http://example.com/a|b`, `http://example.com/%zz`, `http://us"er@example.com/`, and
`http://8.08.8.8/` (accepted by the validator's own expression; rejected here by the
leading-zero-octet rule above); `http://@example.com/` (rejected by the validator's own expression;
accepted here, the host unchanged); and a value that is valid only without its trailing line feed,
for example `http://foo.bar/\n` (accepted by the validator's own expression; rejected here).
`format: url` is not an SSRF control: it resolves no names (for example `127.0.0.1.nip.io`) and
follows no redirects; validate the resolved address at the outbound call.

Each of the seven formats was also proved at the longest value the default `http.maxBodySize` (2
MiB) and the default HTTP header-size limit (8 KiB) admit, with grammar-shaped worst-case values.
Every one stayed within a pre-decided budget — at most 250 ms and at most 16 bytes allocated per
character plus 1 MiB — with no stack overflow and no fallback to a bounded-and-counted check; the
highest figures observed were 26.2 ms (a `url` value built from many host labels) and about 21.8 MB
allocated (a `json-pointer-uri-fragment` value built from escaped segments, about 10.4 bytes per
character against the 16-byte budget). That is the tested envelope: these proofs claim nothing for a
value an application admits by raising `http.maxBodySize` or the header-size limit above these
defaults.

A Hibernate `@URL`-annotated property publishes as `format: uri` plus the pattern `@URL` composes on
top of it, so the pattern bound above still applies there even though `uri` itself is not a bounded
format: a value longer than `jaxrs.validationPatternMaxChars` at that position is rejected before
the composed pattern runs, with the same value-free per-string detail any other `pattern` position
gets.

Either rejection is a 400 `RestValidationException` carrying one `ValidationErrorDetail` for the
rejected validation call:

| Field | Per-string limit | Per-request limit |
|---|---|---|
| `path` | `""` for the body, else the parameter name | same |
| `location` | `body`, `path`, `query`, `header`, `cookie`, or `form` | same |
| `type` | `patternInputLength` | `patternInputTotalLength` |
| `detail` | `exceeds the maximum length of <N> characters for pattern validation` | `exceeds the maximum total length of <N> characters for pattern validation` |
| `args` | `{"maxChars": <N>}` | `{"maxTotalChars": <N>}` |

`<N>` is the configured limit. The detail reads "pattern validation" for a bounded format too, and
it never contains the value, the key, or the pattern. Validation of the request stops at the
rejection in both `aggregate` and `failFast` modes: nothing after the rejected call is evaluated —
no later parameter, no body after a rejected parameter, no `@FilePart` constraint, and no
`FileContentVerifier`. In `aggregate` mode the details that earlier parameters already contributed
stay in the response, ahead of the rejection's detail.

The gate never modifies the schemas an `OperationSchemaSource` returns. It compiles a private copy
of each body and parameter schema in which a length check precedes every pattern and bounded-format
position, and every detail it reports refers to the original schema. For input within both limits,
the verdict and the violations are exactly those of the schema without the limits. Both statements
hold for every schema but the draft-7 shape listed under "What the limits leave open".

Under the default limits, a request with longer input at these positions is rejected with 400 even
when its schema accepts the values. An application that must accept such input raises
`jaxrs.validationPatternMaxChars`, `jaxrs.validationPatternMaxTotalChars`, or both;
`vertique-rest-core` validates both at startup, and its reference lists the failure messages.

The `uri`, `uri-reference`, `url`, `uri-template`, `json-pointer`, `relative-json-pointer`, and
`json-pointer-uri-fragment` formats now judge every value, however long, with one schema-shape
exception listed under "What the limits leave open" below. A value long enough to overflow the
validator's own check for one of them previously could overflow the stack and fail the request with
a 500 error; outside that exception it is now judged, valid or invalid, by the reused implementation
described above, including its recorded verdict differences from the validator's own expressions.

What the limits leave open:

- With the default limits, one request can still spend about 5.7 seconds of validator time on
  `idn-hostname` and `idn-email` values: 64 values of 4,096 characters fill the total, at about
  89 ms each. Lower `jaxrs.validationPatternMaxTotalChars` where that matters.
- A `patternProperties` object with P patterns matches each of its K keys against every pattern —
  P × K matches — while each key counts once. The folded patterns the schema generator writes for a
  case-insensitively bound type are anchored and linear, so that cost is harmless there; the cost of
  an application-authored `patternProperties` is the application's responsibility.
- A key of a case-insensitively bound body type (for example one annotated
  `@JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)`) is counted once at
  each position that checks it — the reserved-name guard, the folded `patternProperties` key, and
  the non-ASCII-key refusal — so it counts up to three times toward the total per validation of its
  object, and once per `anyOf` branch for a polymorphic type: the three-count applies to a key of a
  type that carries a reserved-name guard (hidden or ignored names described through an any-setter),
  at any nesting depth; a key of a case-insensitive type with no reserved names has no guard and
  counts twice. Outside `anyOf` branches, nesting does not multiply the count. A polymorphic type is
  checked once per branch at each polymorphic level, so nested polymorphic types still multiply the
  count and the reused-format checks. The folded `patternProperties` key excludes the exact
  spelling, which `properties` validates, so each key is validated at most once per
  case-insensitive level. Operators sizing `jaxrs.validationPatternMaxTotalChars` should budget
  the per-object count times the number of objects, not a count that grows with depth; keep
  polymorphic trees shallow and bound the body size.
- Outside `anyOf` branches, a member's value is checked once per case-insensitive level, whatever
  the casing of its key, so a value nested `d` case-insensitive levels deep is checked once, not
  repeatedly. The seven reused formats (`uri`, `uri-reference`, `url`, `uri-template`,
  `json-pointer`, `relative-json-pointer`, and `json-pointer-uri-fragment`) therefore run once per
  nested value, and once per branch at each polymorphic level. Every other format, and
  every length and type check, remains bounded only per check, never toward
  `jaxrs.validationPatternMaxTotalChars`. The folded key's copy is the member's resolved schema,
  so a non-exact casing of a member described through a definition reference — a nested DTO, a
  map, an enum, a polymorphic base, or a list or optional of those — is validated against that
  schema and is rejected when the exact spelling would be; verdicts for exact spellings are
  unchanged, and `failFast` lists change only where such a casing lies on the violating path.
- The limits bound the length of the input, not the cost of a pattern. An application-authored
  pattern whose matching time is super-linear — quadratic or exponential backtracking — can still
  be slow on input within the limits; an exponential one can take seconds on a few dozen
  characters. Keeping its patterns linear is the application's responsibility.
- A linear pattern built from a repeated group — for example `^(a|b)*$` or `^([a-z0-9]+[-.]?)*$` —
  can exhaust the thread stack within the default 4,096-character
  `jaxrs.validationPatternMaxChars` limit, because Java's regular-expression engine recurses once
  per repetition of the group; the length bound only limits how much input the pattern sees, not
  how deep that recursion goes. The gate's rejection path catches only `RuntimeException`, and a
  `StackOverflowError` is not one, so it escapes uncaught and the request fails with an unhandled
  500 carrying no input value. Prefer a possessive or non-capturing pattern that does not repeat a
  group, or lower `jaxrs.validationPatternMaxChars` where the pattern cannot be rewritten.
- An application-authored schema that uses the draft-7 container `dependencies` or `definitions`
  with a member named like a JSON Schema keyword — `const`, `enum`, `default`, `examples`,
  `example`, `properties`, `patternProperties`, `$defs`, or `dependentSchemas` — is not bounded at
  that member: a pattern or bounded format inside it can run on input of any length, whether or
  not anything reaches that member through a `$ref`. A `dependencies` member named
  `patternProperties` also changes the verdict: an object validated there that has a property
  named `allOf` fails the request with a 500. Reaching a `definitions` or `dependencies` member
  through a local `$ref` — from the schema's root, from a property, or from another such member —
  does not by itself create a residual: that member is otherwise walked, bounded, and renamed
  exactly like any other schema position; only a member whose own name is one of the keywords
  above, for example `definitions/enum` or `definitions/const`, keeps the engine's own `format`
  and its own unbounded pattern. Generated schemas are unaffected. Schemas the
  `vertique-json-schema` generator writes use `$defs` only; an application-authored schema stays
  fully bounded and fully renamed when it uses `$defs` and `dependentSchemas` instead, or other
  member names.

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

**Wait deadline (per verifier invocation).** The gate races every verifier future against
`jaxrs.fileContentVerifierDeadlineMs` (must be `> 0`; default `5000`). The bound is per call: after
one verifier settles, the next gets a fresh deadline. When the deadline elapses first the request
fails closed with 500 and the sequential chain stops. A timeout-only race bounds the HTTP wait; it
does **not** cancel underlying scanner or client work. Remote or scanner-backed implementors must
therefore configure their own transport/client deadlines at or below this wait bound, and must
release sessions, clients, handles, and offloaded workers when their future completes or when they
observe request end / connection close / stream reset. Request-end upload cleanup deletes the
temporary file regardless of a still-running verifier; a hung scan that still reads the path must
tolerate that and release resources.

A verifier that does not apply returns an already-completed
`FileVerificationResult.accepted()`. Applications can opt into the dependency-free leading-byte
check by adding `MagicBytesVerifierModule.class` to their component. Its bounded catalog recognizes
common image, document, archive/compression, audio/video container, WebAssembly, and web-font
signatures within the first 12 bytes. It is a spoofing heuristic, not malware or structural format
validation; unmapped declared types are accepted without I/O.

---


## Module Dagger Bindings

`RestValidationModule` activates the default `web-validation` path.

| Binding | Kind | What it is |
|---|---|---|
| `RequestValidationStrategy` | `@Binds @IntoSet` | `WebValidationStrategy` with id `web-validation` |
| `OperationSchemaSource` | `@Binds` | `AnnotationSchemaSource` (single optional seam declared by `RestModule`; not a multibinding) |
| `Validator` | `@BindsOptionalOf` | Optional Bean Validation metadata source for schema generation |

Include `RestValidationModule` alongside `RestModule`. Pair with `ValidationModule` when Bean
Validation metadata should supplement the annotation walk; otherwise the optional `Validator` is
absent and generation uses the annotation floor alone.


## Configuration

| Key | Default | Description |
|-----|---------|-------------|
| `jaxrs.validationStrategy` | `"web-validation"` | ID of the `RequestValidationStrategy` to activate |
| `jaxrs.validationMode` | `"aggregate"` | `"aggregate"` (collect all violations, default) or `"failFast"` (stop on first); any other value fails startup |
| `jaxrs.validationPatternMaxChars` | `4096` | Most UTF-16 code units one string value or object key may have at a pattern or bounded-format check (see [WebValidationStrategy](#webvalidationstrategy)); a longer one is rejected with 400; at least `1`, else startup fails |
| `jaxrs.validationPatternMaxTotalChars` | `262144` | Most UTF-16 code units the strings and keys at those checks may add up to in one request; a request over it is rejected with 400; at least `1` and no smaller than `jaxrs.validationPatternMaxChars`, else startup fails |
| `jaxrs.fileContentVerifierDeadlineMs` | `5000` | Per-invocation wait deadline for each bound `FileContentVerifier` future; must be `> 0`, else startup fails; timeout fails closed (500) without cancelling verifier-owned work |
| `http.maxBodySize` | `2097152` | Global ingress body limit for every request; returns 413 when exceeded |
| `http.maxMultipartBodySizeBytes` | `2097152` | Must be positive; pre-auth multipart/form-data admission ceiling (`min` with `maxBodySize`); 413 before resource / with Content-Length early reject before spool |
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
