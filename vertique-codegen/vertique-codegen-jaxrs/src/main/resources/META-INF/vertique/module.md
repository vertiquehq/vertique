<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Codegen JAX-RS Pipeline Module

> **Status:** Beta
> **Package:** `dev.vertique.codegen.jaxrs`
> **Artifact:** `vertique-codegen-jaxrs`
> **Depends on:** `vertique-codegen-core` (compile), `vertique-rest-core` (compile — for `RequestPreconditions` and `dev.vertique.rest.core.application.RestApplication`), `vertique-security-core` (compile — for `dev.vertique.security.authz.Authorized`), `vertique-input-processing` (compile), `jakarta.annotation-api` (compile), `swagger-annotations-jakarta` (compile), `vertique-rest-jaxrs` (test — the generated sources reference its runtime SPI types, so the consuming application declares it), `jakarta.ws.rs-api` (test)

`vertique-codegen-jaxrs` is a unified annotation processor that owns the entire compile-time JAX-RS pipeline: discovery, effective-contract resolution, validation, Dagger DI binding emission, and runtime performance optimization via generated descriptor, bean-param model, and execution plan companions.

Codegen is a **performance optimization, not a feature gate.** Both the generated path and the reflective runtime produce identical `ResourceMethodMeta` tuples and identical behavior — including full support for interface-declared JAX-RS contracts. Removing the processor from `annotationProcessorPaths` reverts the application to the reflective runtime with no source changes and no Dagger wiring changes.

---

## Adoption

Applications inheriting `vertique-app-parent` declare `vertique-rest-jaxrs` as a runtime dependency
and receive the complete processor facade automatically. Custom-parent applications import
`vertique-bom` and configure only the versionless Dagger and `vertique-codegen-all` processor
paths. See `docs/packaging.md`. No `@Component` changes are required beyond including the generated
`GeneratedJaxRsResourcesModule`.

`vertique-codegen-jaxrs` owns `@Path`-resource Dagger binding. `vertique-codegen-dagger` retains
responsibility for `@RestClient`, `@KafkaListener`/`@KafkaSource`, and `DelayedJobExecutor` wiring.

---

## Pipeline Overview

`JaxRsPipelineProcessor` runs in four logical steps per build:

1. **Discover** — collects concrete (non-abstract, non-interface) classes with an effective `@Path` — direct on the class, or on any transitively implemented interface, including an interface a superclass implements. A class that inherits `@Path` only from a superclass's class-level annotation is not a candidate: it gets no descriptor and keeps the reflective runtime path, which serves the same routes.
2. **Resolve** — builds an `EffectiveResourceContract` for each candidate applying the precedence rule: direct annotations → superclass chain → BFS interfaces (see "EffectiveJaxRsContractResolver" below). The candidate methods are the ones declared along the superclass chain plus the interface `default` methods the class inherits without overriding (see "Interface-Declared Contracts" below).
3. **Validate** — all five validators run against each resolved contract.
4. **Emit** — four artifact types are written when applicable: the `GeneratedJaxRsResourcesModule` Dagger module, `{Resource}_JaxRsDescriptor` companions, `{Bean}_BeanParamModel` companions, and `{Resource}_{method}_{idx}_ExecutionPlan` companions.

**Semantic vs. DI candidates.** "Is this a JAX-RS resource?" (semantic) is independent of "Should we generate a Dagger binding for it?" (DI eligibility). Validation and descriptor/plan emission apply to all semantic candidates; Dagger module generation applies only to DI-eligible candidates (those with an `@Inject` constructor that are not annotated `@NoAutoWire`).

---

## Key Classes

### `EffectiveJaxRsContractResolver`

Resolves the complete JAX-RS contract for a concrete resource class by applying the precedence rule:

1. Direct annotations on the concrete class or method.
2. Matching declarations in the superclass chain (most-derived wins).
3. Matching declarations in implemented interfaces in BFS discovery order (matching `TypeResolver.getAllInterfaces`).

Conflict policy:

- Two implemented interfaces carrying **conflicting** values for the same annotation kind → compile-time error via `Diagnostics.error`; the offending element is excluded from the resolved contract.
- **Identical** values across interfaces → not a conflict; first-found-wins.
- Direct annotation **overrides** an interface declaration for non-security kinds (for example `@Path`) → compile warning so the developer notices the silent override.
- **Security is fail-closed.** Method- and class-level security annotations are merged across every declaration of the same element (concrete, superclass chain, and interfaces), matching the reflective `AnnotationResolver` + `AnnotationSecurityPolicyResolver` path. `@RolesAllowed` and `@Authorized` are independent axes: roles on one declaration and scopes on another combine into one AND-constrained policy (for example `@RolesAllowed` on an interface method plus `@Authorized(scopes = …)` on the override). `@DenyAll` / `@PermitAll` mixed with any different kind, or differing `@RolesAllowed` / `@Authorized` member values when both declarations specify the same axis, are a compile-time error — a nearer declaration does not win. This closes the OWASP A01 / CWE-863 gap where codegen previously emitted `PermitAll` for a `@DenyAll` + `@PermitAll` split across an interface default or class override while the runtime rejected the same shape at startup.

Produces `EffectiveResourceContract` (one per class), which contains `EffectiveMethodContract` (one per resource method), which contains `EffectiveParamContract` (one per parameter) and `EffectiveSecurityContract`.

#### Input-policy derivation

Route- and parameter-level canonicalization and sanitization chains (`@Canonicalize`, `@Sanitize`, `@SkipCanonicalization`, `@SkipSanitization`) are not resolved here. The resolver delegates them to `ElementInvocationPolicies` in `vertique-input-processing`, the shared adapter the reflective runtime's own resolution is built on, so a generated execution plan carries exactly the chains the reflective path would have derived for the same declarations.

The practical consequence is that these annotations obey the same hierarchy rules as every other JAX-RS annotation the processor reads: a policy declared on an interface method, on an inherited superclass method, or at class level on a superclass reaches the generated plan, and a parameter annotation declared only on an interface method's parameter reaches the generated parameter chain. Composed annotations (a custom annotation meta-annotated with `@Sanitize(...)`) resolve recursively.

Declaring an additive annotation and its skip counterpart anywhere in the same merged element is a **compile error**, reported at the method for a route conflict and at the parameter for a parameter conflict. That includes the inherited shape: an override that adds `@SkipSanitization` over an interface method's `@Sanitize(...)`, or a subclass that adds a class-level `@Sanitize(...)` over a superclass's `@SkipSanitization`, is rejected — an override may replace an inherited policy but never remove one. Replacing is fine: an override declaring `@Sanitize(A.class)` over an inherited `@Sanitize(B.class)` resolves to `A`. A method excluded by a route conflict does not reach the generated contract at all; a parameter excluded by a parameter conflict falls back to no policies. Either way the diagnostic names the axis, both declaration sites, and the offending element.

`resolveComponentType` classifies a `T[]` array-typed parameter as a multi-value parameter, at parity with the reflective runtime's `ResourceScanner.resolveComponentType` — a codegen'd resource binds all repeated values for `String[]`, `Integer[]`, boxed-wrapper array, and enum array element types identically to the reflective path. The classification is scoped by parameter **source**. The four bindable multi-value sources — `@QueryParam`, `@HeaderParam`, `@CookieParam`, `@FormParam` — resolve a component type, and so do the two native multipart aggregates (`FILE_UPLOADS`, `ENTITY_PARTS`), whose declared `List` element type is what the runtime scanner records for them. A body parameter does not resolve one, matching the reflective scanner: `genericType` still carries shapes such as `List<Inner[]>` for deserialization, while `componentType` stays null, because a body value is deserialized rather than materialized as a collection. `@PathParam`, `@Context`, request preconditions, and `@BeanParam` never resolve one either. Path values in particular come from `RoutingContext.pathParams()`, a `Map<String, String>`, so a path parameter is single-valued on both paths, and an array-typed `@PathParam` is rejected at startup (`UNRESOLVABLE_PARAM_CONVERTER`) rather than mounted. Primitive scalar-array element types (`byte[]`, `char[]`, `int[]`, etc.) are deliberately excluded from this policy and remain BODY parameters on both paths; so is a nested array (`String[][]`), whose element type is itself an array. The processor implements the scalar-array-component policy over `TypeMirror`; the runtime implements the same policy over `Class<?>`; the two are held equal by a parity test rather than by sharing one method.

For a parameterized collection (`List<T>`, `Set<T>`, `SortedSet<T>`, `NavigableSet<T>`, `Collection<T>`) the element type resolves only when the type argument is one core reflection reifies as a plain `Class` — a non-generic type (`List<String>`, `List<Season>`), a member class of a non-generic owner (`List<Outer.Inner>`), or an array of one (`List<String[]>`). A wildcard (`List<? extends CharSequence>`, `List<?>`), a type variable (`List<T>`), a nested parameterized type (`List<List<String>>`), and a non-static member class of a parameterized owner (`List<Outer<String>.Inner>`, and the array form `List<Outer<String>.Inner[]>`) resolve no component type, exactly as the reflective scanner's `typeArg instanceof Class<?>` test rejects them — core reflection reifies `Outer<String>.Inner` as a `ParameterizedType` with empty type arguments and a parameterized owner, so the codegen gate inspects `getEnclosingType()` too. Since a non-null component type is the framework's single multiplicity trigger, this gate is what keeps the same declaration from being classified differently depending on whether codegen ran.

### Validators

| Class | Role |
|---|---|
| `ContextParamValidator` | Enforces FR-REST-187/188/189: (a) rejects an `@Context` parameter (or one auto-classified as CONTEXT by an injectable type) that also carries a value-binding annotation (`@PathParam`, `@QueryParam`, `@HeaderParam`, `@CookieParam`, `@FormParam`, `@BeanParam`) — conflict; (b) rejects reserved JAX-RS types unsupported in V1 (`UriInfo`, `HttpHeaders`, `Request`, `Configuration`, `Application`, `Providers`, `ResourceContext`); (c) rejects a `@Context` parameter whose type is neither a built-in nor a `ContextValue` subtype — non-injectable. Runs **before** the path/body-form validators and short-circuits them for any method carrying a context violation. Handles split inherited annotations (interface vs. impl) via interface-method walking. |
| `SecurityAnnotationValidator` | Mirrors `AnnotationSecurityPolicyResolver` conflict matrix: `@DenyAll` + `@PermitAll`/`@RolesAllowed`, `@PermitAll` + `@RolesAllowed`, `@RolesAllowed({})` empty array, and a `@RequiresPolicy` mixed with inline security or with a second policy in the same set. A method policy replaces a different type policy. Class-level and method-level checked independently. |
| `HttpVerbValidator` | Tier-B guardrail: error on multiple HTTP verb annotations on a single method. |
| `PathParamAlignmentValidator` | Bidirectional check between `@Path` placeholders and `@PathParam` declarations. Handles `@BeanParam` and `@RequestParams` composite types including record components. |
| `BodyFormValidator` | Mirrors `RouteValidator.validateMethodParams`: at most one body parameter; body and form parameters mutually exclusive. |
| `ApplicationAnnotationValidator` | Checks every `@RestApplication` declaration not annotated `@NoAutoWire` — the declaring interface and every superinterface, transitively — against the declaration annotation allow list, and the declaration's `@ApiDocs` against the documentation policy rules; see "Declaration Annotation Allow List" and "`@ApiDocs` Checks" below. |

`ContextParamValidator` runs first, before the remaining four resource-contract validators — see the short-circuit behavior noted under "Validation Rules" below. Those five validators take `EffectiveResourceContract`; `ApplicationAnnotationValidator` instead checks the `@RestApplication` declarations directly.

### Generated Artifacts

Four artifact types are emitted for every semantic candidate:

| Artifact | What it does |
|---|---|
| `GeneratedJaxRsResourcesModule` Dagger module | A presence-gated `@Provides @ElementsIntoSet @JaxRsResources Set<Object>` binding and a lazy `GeneratedJaxRsResourceEntry` catalog entry for each DI-eligible resource, plus a `GeneratedRestApplicationRegistration` for each registered `@RestApplication` declaration — see "Generated Binding Shape" below |
| `{Resource}_JaxRsDescriptor` | Precomputes `SecurityPolicy` constants and method/parameter metadata, eliminating the reflective `getDeclaredMethods()` walk at startup |
| `{Bean}_BeanParamModel` | Static field-metadata list per `@BeanParam`/`@RequestParams` type, eliminating the reflective bean-field scan |
| `{Resource}_{methodName}_{idx}_ExecutionPlan` | Precomputed `EffectiveInputPolicies` (`dev.vertique.input.processing`) constants plus a direct typed method call, eliminating `Method.invoke` from the request hot path. For `CONTEXT` parameters, emits a static `Class<?>` constant (`CTX{i}`) loaded once at class-initialization time and a `support.resolveContext(CTX{i}, ctx, "<declaringClassFqn>", "<method>")` call per parameter — no per-request reflection, no `ParamMeta`/policy entry for `CONTEXT` params |

Only the Dagger module row is gated by DI eligibility (see "Semantic vs. DI candidates" above) — the other three are emitted for every semantic candidate regardless.

---

## Generated Binding Shape

Each compilation unit's `GeneratedJaxRsResourcesModule` keeps one name and stays a concrete Dagger module. It is written only when the unit has at least one DI-eligible resource or at least one registered `@RestApplication` declaration (see "REST Application Declarations" below); when neither is present, nothing is written. Its package is resolved from the unit's DI-eligible resources whenever it has any; only in a unit without them do its declarations decide the package instead — so adding a declaration to a unit that already has resources never moves that unit's existing module. A `jakarta.ws.rs.core.Application` subclass counts toward neither (see "Jakarta `Application` Subclasses" below).

### Resource Bindings

Every DI-eligible resource `R` gets two `@Provides` methods, both taking `@VertxConfig JsonObject config` whether or not `R` carries `@ConditionalOnProperty`:

- a presence-gated `@Provides @ElementsIntoSet @JaxRsResources Set<Object>` binding, named by `R`'s decapitalized simple name plus `Binding`, that also takes `Set<GeneratedRestApplicationRegistration> applications` and a `Provider<R>`, and contributes `R` only when `applications` is empty (the component registers no `@RestApplication` declaration) and `R`'s own conditions, if any, are satisfied;
- a lazy `@Provides @IntoSet GeneratedJaxRsResourceEntry` catalog entry, named by `R`'s decapitalized simple name plus `Entry`, carrying `R`'s class, its evaluated condition result, and its `Provider<R>` — it never calls the provider.

**Unconditional resource binding** (`CatalogResource`, no `@ConditionalOnProperty`):

```java
@Provides
@ElementsIntoSet
@JaxRsResources
static Set<Object> catalogResourceBinding(@VertxConfig JsonObject config,
        Set<GeneratedRestApplicationRegistration> applications, Provider<CatalogResource> provider) {
    return applications.isEmpty() ? Set.of(provider.get()) : Set.of();
}

@Provides
@IntoSet
static GeneratedJaxRsResourceEntry catalogResourceEntry(@VertxConfig JsonObject config,
        Provider<CatalogResource> provider) {
    return GeneratedJaxRsResourceEntry.of(CatalogResource.class, true, provider);
}
```

**Conditional resource binding** (`@ConditionalOnProperty(name = "resources.disabledResource.enabled")` on `DisabledResource`):

```java
private static final PropertyCondition[] DISABLED_RESOURCE_BINDING_CONDITIONS = new PropertyCondition[] {
    new PropertyCondition("resources.disabledResource.enabled", "true", false)
};

@Provides
@ElementsIntoSet
@JaxRsResources
static Set<Object> disabledResourceBinding(@VertxConfig JsonObject config,
        Set<GeneratedRestApplicationRegistration> applications,
        Provider<DisabledResource> provider) {
    return applications.isEmpty()
            && PropertyCondition.matchesAll(config, DISABLED_RESOURCE_BINDING_CONDITIONS)
            ? Set.of(provider.get())
            : Set.of();
}

@Provides
@IntoSet
static GeneratedJaxRsResourceEntry disabledResourceEntry(@VertxConfig JsonObject config,
        Provider<DisabledResource> provider) {
    return GeneratedJaxRsResourceEntry.of(DisabledResource.class,
            PropertyCondition.matchesAll(config, DISABLED_RESOURCE_BINDING_CONDITIONS), provider);
}
```

The condition constant's name is the binding method's own name upper-cased to `SCREAMING_SNAKE_CASE`, plus `_CONDITIONS` — `disabledResourceBinding` yields `DISABLED_RESOURCE_BINDING_CONDITIONS`. The catalog entry method reuses that same constant rather than declaring its own.

Inactive resources are never instantiated (lazy `Provider` injection): the binding calls `provider.get()` only when it contributes, and the catalog entry only hands its `Provider` to the runtime, which calls it for a resource that a declared application selects and whose conditions match. Descriptor, bean-param model, and execution-plan companions are still emitted for all semantic candidates — only the DI set contribution and the catalog entry's condition flag follow the gate.

### Jakarta `Application` Subclasses

The processor never registers a `jakarta.ws.rs.core.Application` subclass and emits no construction code for one: a REST application is declared with `@RestApplication` instead (see "REST Application Declarations" below). Every concrete subclass — top-level or nested at any depth, static or not — gets exactly one mandatory warning: its binary name followed by static text, the same for every subclass:

```text
{Application}: @ApplicationPath and getClasses() have no effect; with no @RestApplication declared in the component, its resources are served on the legacy default mount at jaxrs.basePath; otherwise they are served only where a @RestApplication lists them; declare an application with @RestApplication
```

The text cannot be specific to one subclass, because neither a `getClasses()` body nor another compilation unit's declarations are visible at compile time. The warning is the only diagnostic a subclass gets, whatever its constructors, annotations, or `@ApplicationPath`, and it is the same under `-Avertique.codegen.autoWire=false`. It never fails compilation by itself, but a build that treats warnings as errors (for example, one compiled with `-Werror`) fails on it. Annotate the subclass `@NoAutoWire` to suppress the warning. A subclass counts toward neither the module-writing condition nor package resolution (see "Generated Binding Shape" above); to port one, see "Porting a Jakarta `Application` Subclass" below.

**Local and anonymous subclasses are invisible to the processor.** A subclass declared inside a method body (a local class), or as an anonymous class expression, is not among the round's root elements the processor scans, so it gets no warning.

### Application Path Grammar

A `@RestApplication` declaration's `path` (see "REST Application Declarations" below) is normalized at compile time: an empty value, or one with no leading `/`, is anchored to `/`; one terminal `/*` and every trailing `/` are then removed — so `/api/public/` and `/api/public/*` both normalize to `/api/public`, and a value of `/` or `/*` normalizes to `/`.

The normalized path must then consist only of `/` and the RFC 3986 unreserved characters (`A-Z a-z 0-9 . _ ~ -`), or compilation fails with the first matching rule, in this order:

| Rule | Rejects |
|---|---|
| `wildcard` | contains `*` |
| `router pattern` | contains `:`, `{`, or `}` |
| `query` | contains `?` |
| `fragment` | contains `#` |
| `repeated separator` | contains `//` |
| `dot segment` | a `/`-separated segment equal to `.` or `..` |
| `encoded separator` | contains `%2F`, `%2f`, `%5C`, or `%5c` |
| `unsupported character` | any character other than `/` outside the unreserved set, including any other `%` |

A `.` inside a segment (`v1.0`) is fine — only a segment consisting solely of `.` or `..` is a dot segment.

### Declaration Annotation Allow List

Every `@RestApplication` declaration not annotated `@NoAutoWire` is checked against a fixed annotation allow list. The checked scope is the declaring interface and every superinterface, transitively. Only annotations on the type declarations in that scope are checked — an annotation on a member, such as a constant or a `default` method, is never inspected — and a repeatable container annotation (for example the compiler-synthesized `ConditionalOnProperties` when two or more `@ConditionalOnProperty` annotations appear on one type) is checked as an annotation in its own right, not unwrapped into its repeated elements.

The declaring interface may carry only:

- `@RestApplication`;
- `@ApiDocs` (`dev.vertique.rest.openapi.docs.ApiDocs`), itself checked as described under "`@ApiDocs` Checks" below;
- `@io.swagger.v3.oas.annotations.OpenAPIDefinition` in which every element other than `info` equals its declared default;
- the `SOURCE`-retained `@ConditionalOnProperty` (single or repeated) and `@NoAutoWire`;
- annotation types in the packages `java.lang` and `java.lang.annotation`, such as `@Deprecated`.

Of its `RUNTIME`-retained annotations, a superinterface may carry only those whose types are in `java.lang` and `java.lang.annotation`. Any other `RUNTIME`-retained annotation on the declaration or on a superinterface fails the build (see "Diagnostics" below) — security, `@Path`, `@ApplicationPath`, scope, and qualifier annotations included — because a declaration carries no resource semantics: security and other resource annotations belong on resources, not on the interface that lists them. `@RestApplication`, `@ApiDocs`, `@OpenAPIDefinition`, `@ConditionalOnProperty` (or its `ConditionalOnProperties` container), and `@NoAutoWire` are honored only on the declaring interface; found on a superinterface, each fails the build instead. `@ConditionalOnProperty` and `@NoAutoWire` are `SOURCE`-retained, so on a superinterface they are seen only when it is compiled in the same build. Every other `CLASS`- or `SOURCE`-retained annotation is unchecked.

Each error names the declaration, the annotation, and the type carrying it, each by binary name. A declaration that fails the allow list is not registered.

### `@ApiDocs` Checks

A declaration's `@ApiDocs` is recognized by its fully qualified name, `dev.vertique.rest.openapi.docs.ApiDocs`, so this processor needs no dependency on the module that declares it. Its elements are checked at compile time:

| Rule | Outcome |
|---|---|
| `policy` must be a valid access policy | A public interface that extends only `AccessPolicy`, declares no members, and carries valid, non-conflicting requirements: no blank or empty `@RolesAllowed`, no `@PermitAll` mixed with another requirement. A class, bare `AccessPolicy`, an interface with extra parents or methods, or a non-public interface is an error, reported alone whatever `securityScheme` is. |
| Public policy (exactly one direct `@PermitAll`) | `securityScheme` must not be supplied; any value, blank included, is an error. |
| Any other valid policy (`@DenyAll` included) | `securityScheme` is required and must not be blank. |

`policy` has no default and `access` and `rolesAllowed` no longer exist: an `@ApiDocs` without `policy`, or one that sets a removed element, is the compiler's own error. A `policy` that is not an `AccessPolicy` type, or names a missing type, is also the compiler's own error. Elements are judged by value, so an explicit `securityScheme = ""` counts as not set. Each processor violation is a compile error naming the declaration and the element (see "Diagnostics" below), and a declaration with any violation is not registered.

### Diagnostics

| Diagnostic | Text |
| --- | --- |
| `Application` subclass warning (mandatory warning) | `{Application}: @ApplicationPath and getClasses() have no effect; with no @RestApplication declared in the component, its resources are served on the legacy default mount at jaxrs.basePath; otherwise they are served only where a @RestApplication lists them; declare an application with @RestApplication` |
| `@RestApplication` on a non-interface (error) | `{Declaration} is annotated @RestApplication, which belongs on an interface; declare the application on an interface instead of this {kind}.` |
| Missing `vertique-rest-jaxrs` dependency for a declaration (error) | `A @RestApplication declaration was found, but 'vertique-rest-jaxrs' is not on the compile classpath; add it as a dependency to generate application registrations.` |
| Invalid declaration name (error) | `{Declaration} has an invalid @RestApplication name "{name}"; an application name must match [a-z0-9][a-z0-9_-]{0,63}.` |
| Reserved declaration name (error) | `{Declaration} uses the @RestApplication name "{name}", which is reserved; none and null cannot name an application.` |
| Invalid declaration path (error) | `{Declaration} has an invalid @RestApplication path "{value}" (rule: {rule}); an application path may contain only '/' and the characters A-Z a-z 0-9 . _ ~ -` |
| Declaration membership form (error) | `{Declaration} sets both resources and discover = true (or: neither resources nor discover = true); a REST application declares exactly one of a non-empty resources list and discover = true.` |
| Unresolvable declaration resources entry (error) | `{Declaration} lists a resources entry that could not be resolved to a class; list only concrete @Path resource classes this compilation can reference.` |
| Duplicate declaration resources entry (error) | `{Declaration} lists {Resource} in resources more than once; list each resource class once.` |
| Rejected declaration resources entry (error) | `{Declaration} lists {Resource} in resources, but {reason}; list only concrete @Path resource classes.` |
| Inaccessible declaring interface (error) | `{Declaration} is not accessible from the generated module's package '{package}'; make it public, or package-private in that same package.` |
| Inaccessible listed resource (error) | `{Declaration} lists a resource class its generated registration cannot name: {the inaccessible-type message above, naming the resource}` |
| Duplicate declaration name (error) | `{Declaration} declares the application name "{name}", which {OtherDeclaration} also declares; application names must be unique within a compilation unit.` |
| Declaration `discover` not alone (error) | `{Declaration} sets discover = true, but this compilation unit declares another application ({OtherDeclarations}); discovery is only for a compilation unit's sole application declaration.` |
| Disallowed annotation on the declaration (error) | `{Declaration} carries @{annotation}, which a @RestApplication declaration may not carry: a declaration has no resource semantics and may carry only @RestApplication, @ApiDocs, @OpenAPIDefinition with only info set, @ConditionalOnProperty, @NoAutoWire, and java.lang and java.lang.annotation annotations` |
| Disallowed annotation on a superinterface (error) | `{Declaration} carries @{annotation} on its superinterface {Superinterface}, which a @RestApplication declaration's superinterface may not carry: a superinterface of a declaration may carry only java.lang and java.lang.annotation annotations` |
| Declaration-only annotation on a superinterface (error) | `{Declaration} carries @{annotation} on its superinterface {Superinterface}, which is not allowed: that annotation is honored only on the @RestApplication declaring interface itself` |
| `@OpenAPIDefinition` element other than `info` (error) | `{Declaration} carries @io.swagger.v3.oas.annotations.OpenAPIDefinition with an element other than info set, which is not allowed on a @RestApplication declaration: only its info element may be set` |
| `@ApiDocs` with an invalid policy (error) | `{Declaration} declares @ApiDocs with an invalid policy {Policy}: {reason}; policy must name a public interface that extends only AccessPolicy and declares valid, non-conflicting requirements` |
| `@ApiDocs` with a public policy and a scheme (error) | `{Declaration} declares @ApiDocs(policy = {Policy}) with a securityScheme; securityScheme is set only when the policy is not public (a public policy is exactly one @PermitAll)` |
| `@ApiDocs` with a policy that is not public and no scheme (error) | `{Declaration} declares @ApiDocs(policy = {Policy}) without a non-blank securityScheme; a policy that is not public needs securityScheme to name the security scheme that authenticates its readers` |
| Declaration `@NoAutoWire` warning | `{Declaration} is annotated @NoAutoWire, so it is not registered as a REST application and is not validated.` |
| Declaration `autoWire=false` warning | `{Declaration} is not registered as a REST application because -Avertique.codegen.autoWire=false is set.` |

Every type in the messages — `{Application}`, `{Declaration}`, `{Superinterface}`, `{Resource}`, `{Policy}`, and `{annotation}` — is named by its binary name.

Every `@RestApplication` declaration not annotated `@NoAutoWire` is checked regardless of `-Avertique.codegen.autoWire=false` — the annotation allow list, the `@ApiDocs` checks, and the declaration checks (see "Declaration Checks" below) all still fail the build when violated; only the module write itself is skipped under that option (see "Extension Points" below). The `Application` subclass warning is the same with or without that option.

**Accessibility reaches enclosing types.** The declaration accessibility diagnostics are not satisfied by the declaring interface or listed resource class alone: the type *and every one of its enclosing types* must each be accessible from the generated module's package — public, or non-private and declared in that same package. A type nested inside an inaccessible enclosing type is unreachable from outside that type's package even when the nested type itself is `public`, so it fails this check too.

**Declaration diagnostics: rejection reasons and accessibility variants.** A rejected `resources` entry names one reason: `it is an interface`, `it is an annotation type`, `it is an abstract class`, `it is a JAX-RS provider (@Provider)`, `it implements jakarta.ws.rs.core.Feature`, `it implements jakarta.ws.rs.container.DynamicFeature`, or `it has no effective @Path, on itself or on an implemented interface`. A declaration's accessibility diagnostic reaches enclosing types as described above: an inaccessible declaring interface or listed resource is named directly; when an enclosing type is the cause instead, the message names it too (`... its enclosing type {EnclosingType} is not public; make {EnclosingSimpleName} public, or package-private in that same package.`); and, when no generated-module package resolves at all, the message reads `{Declaration} is not accessible: no generated-module package could be resolved, so it and its enclosing types must be public.` A listed resource's accessibility diagnostic is one of these same messages, prefixed with `{Declaration} lists a resource class its generated registration cannot name: `.

### Components Must Also List `RestModule`

Each presence-gated resource binding consumes the `Set<GeneratedRestApplicationRegistration>` multibinding that `RestModule` declares. A Dagger component that lists a generated `GeneratedJaxRsResourcesModule` must therefore also list `RestModule`, or that set is unsatisfied and the component fails to compile.

### Distinct-Package Rule

Two compilation units whose classes resolve to the same package both write `<package>.GeneratedJaxRsResourcesModule`, and whichever copy lands first on the classpath wins silently — the processor cannot reliably tell a stale copy of a unit's own prior output from another unit's module, so it does not check for a collision. Each compilation unit must resolve a distinct package for its generated module, or set `-Avertique.codegen.package` to force one; otherwise one unit's bindings and registrations can silently never reach the component.

### Porting a Jakarta `Application` Subclass

A `jakarta.ws.rs.core.Application` subclass declares nothing the processor acts on: its `@ApplicationPath` and `getClasses()` have no effect, and it only gets the warning described under "Jakarta `Application` Subclasses" above. To serve its resources as a named application at its path, declare a `@RestApplication` interface with the same path that lists those resources — or sets `discover = true` instead, when it is the compilation unit's sole declaration — and delete the subclass:

```java
// Before: warned about, never registered
@ApplicationPath("/api/mgmt")
public class ManagementApplication extends Application {
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(OrderResource.class);
    }
}

// After
@RestApplication(name = "mgmt", path = "/api/mgmt", resources = OrderResource.class)
interface MgmtApi {}
```

The declaration also needs a `name` (see "Declaration Checks" below). A subclass kept for another reason can be annotated `@NoAutoWire` to suppress its warning.

Until the port, the subclass's resources are served at `jaxrs.basePath` when no `@RestApplication` is declared, or only where a declared application selects them, never under the old `@ApplicationPath`, so perimeter rules keyed on that prefix (gateway routes, allowlists, rate limits) stop matching them.

---

## REST Application Declarations

`@RestApplication` (Beta, package `dev.vertique.rest.core.application`, in `vertique-rest-core`) declares a named REST application on an interface:

```java
@RestApplication(name = "mgmt", path = "/api/mgmt", resources = OrderResource.class,
        openapiPath = "openapi/mgmt.yaml")
interface MgmtApi {}
```

Exactly one of `resources` (a non-empty list of concrete `@Path` resource classes, listed in the order written) and `discover = true` (permitted only for a compilation unit's sole declaration) is set. `openapiPath` is carried as written; `""` (the default) means the global `jaxrs.openapiPath`. The declaring interface only carries the declaration — nothing implements or instantiates it.

This processor recognizes `RestApplication`, `jakarta.ws.rs.core.Application`, and `ApiDocs`, and locates `GeneratedRestApplicationRegistration`, by fully qualified name. `vertique-rest-core` is a compile dependency, but `RestApplication` is still matched by name, the same way as `ApiDocs`, whose module is not a dependency; `vertique-rest-jaxrs` and `jakarta.ws.rs-api` stay test-scope dependencies of this module, so declarations add no compile dependency.

### Declaration Checks

Every declaration is checked at compile time; each violation is a compile error naming the declaring interface by binary name (see "Diagnostics" below for the exact text):

| Check | Rule |
|---|---|
| Form | `@RestApplication` must annotate an interface; a class, enum, record, or annotation type fails compilation. |
| Name | Required; must match `[a-z0-9][a-z0-9_-]{0,63}` (at most 64 characters); must not be the reserved `none` or `null`. |
| Path | Required; normalized and checked, as written, by the rule order under "Application Path Grammar" above. |
| Membership | Exactly one of a non-empty `resources` and `discover = true`; both or neither fails. |
| Listed resources | Each entry must be a concrete class with an effective `@Path` (direct, or via an implemented interface), and must not be an interface, an abstract class, a `@jakarta.ws.rs.ext.Provider`, a `jakarta.ws.rs.core.Feature`, or a `jakarta.ws.rs.container.DynamicFeature`; no entry may repeat. |
| Accessibility | The declaring interface and every listed resource class must be accessible from the generated module's package — public, or non-private and declared in that package — since the emitted registration names each by its class literal. A type nested in an inaccessible enclosing type fails even when the nested type itself is `public` (see "Accessibility reaches enclosing types" above). When no module package resolves (possible only under `-Avertique.codegen.autoWire=false`), a type is accessible only when it and every enclosing type are `public`. |
| Unique names (per compilation unit) | No two declarations of one compilation unit may share a name, active or not; the error names both. Uniqueness across compilation units is a startup check, outside this module. |
| Sole discovery (per compilation unit) | `discover = true` fails when the compilation unit declares another `@RestApplication`, active or not. The cross-unit half of this rule, combined with startup composition, is also outside this module. |
| Annotations | The declaring interface and its superinterfaces carry only allowed annotations; see "Declaration Annotation Allow List" above. |
| `@ApiDocs` | `policy` is a valid access policy and `securityScheme` is empty exactly when it is public; see "`@ApiDocs` Checks" above. |

### Emitted Registration

For each registered declaration `D`, one `@Provides @IntoSet GeneratedRestApplicationRegistration` method is emitted, taking only `@VertxConfig JsonObject config` — never a `Provider`, and `D` itself is never constructed:

```java
private static final PropertyCondition[] MGMT_API_REGISTRATION_CONDITIONS =
        new PropertyCondition[] { new PropertyCondition("mgmt.enabled", "true", false) };

@Provides
@IntoSet
static GeneratedRestApplicationRegistration mgmtApiRegistration(@VertxConfig JsonObject config) {
    return GeneratedRestApplicationRegistration.of(MgmtApi.class, "mgmt", "/api/mgmt",
            List.of(OrderResource.class), false, "",
            PropertyCondition.matchesAll(config, MGMT_API_REGISTRATION_CONDITIONS));
}
```

The activation argument follows the same `PropertyCondition.matchesAll(config, …_CONDITIONS)` rule a resource binding uses, or `true` when `D` carries no `@ConditionalOnProperty`. The listed resources appear in the order written (empty for a `discover` declaration), and the path is the normalized one.

**Naming.** A declaration's registration method is named by the declaring interface's decapitalized simple name plus `Registration`. When two declarations in one unit share a simple name (in different enclosing scopes), the processor appends `_2`, `_3`, and so on to the later ones, in fully-qualified-name order.

A unit with at least one registered declaration and no DI-eligible resource still writes a module, in the declarations' package (see "Generated Binding Shape" above).

### Activation and Opting Out

`@ConditionalOnProperty` on a declaration (single or repeated) is evaluated exactly as it is for a resource binding. `@NoAutoWire` on a declaration makes it inert: it is checked first, gets one warning naming it, and is excluded before any other rule runs — it is neither registered nor validated, not even against the annotation allow list or the `@ApiDocs` checks, and does not count toward the compilation unit's unique-name or sole-discovery checks. Under `-Avertique.codegen.autoWire=false`, every declaration is instead still validated, the annotation allow list and the `@ApiDocs` checks included, but none is registered and no module is written; each declaration then gets one warning naming it instead of a registration.

---

## Interface-Declared Contracts

The processor supports the common OpenAPI Generator pattern where the contract is declared on an interface and the concrete class is mostly unannotated.

**Codegen path** — `EffectiveJaxRsContractResolver` walks the BFS interface graph and produces an `EffectiveResourceContract` that captures all interface-declared annotations. The descriptor and execution-plan emitters then generate artifacts from the effective contract.

**Interface default methods** — an annotated interface `default` method is a resource method of every class that implements the interface, whether or not the class overrides it. `JaxRsMethodDiscovery` adds each default the class inherits: one that no method of the superclass chain overrides and no method of a more specific interface overrides. The override test compares member types of the resource, so a class that overrides a generic default `remove(I)` with `remove(String)` shadows it — the same selection the runtime gets from `Class.getMethods()`. A default inherits annotations from the interfaces it overrides — its own interface hierarchy — as the runtime does from its declaring interface. The generated plan invokes the default through the resource type, and the descriptor names the interface as the method's declaring type, exactly as the reflective scanner does. An inherited generic method (a non-overridden default of `Crud<ID>`, or a generic superclass method) binds a type-variable parameter as its erasure on both paths; the processor emits no execution plan for it, because a typed call through the resource type would not compile, so it keeps reflective dispatch. The same holds for a default whose name and parameter types match a superclass's `private` method: the private method is not inherited and does not hide the default, but the JVM would resolve a typed call through the resource type to it, so that route also keeps reflective dispatch.

**Reflective runtime path** — `ResourceScanner.scanResource`, `resolveHttpMethod`, `resolveParams`, and `AnnotationSecurityPolicyResolver` consume `AnnotationResolver`-merged annotation lists that include interface-declared annotations, so interface-backed contracts work correctly even when the processor is absent. For a method declared by a superclass, both engines walk the *resource class*'s interfaces (codegen via `JaxRsHierarchy.interfacesForMethod`, runtime via `AnnotationResolver.resolveMethodAnnotations(method, resourceClass)`), so a `Base` method that implements a resource-level `Crud` contract inherits `Crud`'s annotations on both paths.

Interface- and superclass-declared canonicalization and sanitization policies follow the same rule on both paths, because both resolve them through the shared adapter in `vertique-input-processing` rather than through their own walk.

Both paths produce identical `ResourceMethodMeta` and identical `SecurityPolicy` outcomes. Removing `vertique-codegen-jaxrs` from `annotationProcessorPaths` changes performance, not behavior. A method `@RequiresPolicy` replaces a type policy, including when the method policy is action-only and its role/scope contract is empty. The match uses the method name and the signature viewed from the resource, so a type-variable parameter matches the type that binds it. The generated method-annotation list is `AccessPolicyResolver.collectMethodAnnotations` of the legacy list, so that action stays on the list even when the declaring method is package-private in the same package or protected. Like other hierarchy members, static methods, private methods, and package-private methods declared in another package are not part of that selection. The scanned method always selects itself whatever its modifiers, and a security annotation on the legacy list that the selection cannot reproduce fails registration instead of being replaced by a broader declaration.

---

## Runtime SPI (in `vertique-rest-jaxrs`)

The runtime SPI lives in `dev.vertique.rest.jaxrs.runtime`. These types are part of `vertique-rest-jaxrs`; the codegen processor generates classes that implement or use them.

| Type | Role |
|---|---|
| `GeneratedJaxRsResourceDescriptor<T>` | SPI interface implemented by each `{Resource}_JaxRsDescriptor` companion; `describe(T, GeneratedJaxRsDescriptorSupport, List<SecurityPolicyViolation>)` builds the `ResourceMethodMeta` list |
| `GeneratedJaxRsDescriptorSupport` | Helper bag passed to `describe(...)`: resolves `Method` objects, merges method/class annotation lists, resolves types from string FQNs |
| `GeneratedJaxRsDescriptorRegistry` | Process-wide singleton; `ClassValue<LookupResult>` cache; derives companion FQN as `{resource.binaryName}_JaxRsDescriptor`; caches `ClassNotFoundException` as an empty miss (normal fallback); wraps and rethrows linkage/instantiation failures |
| `GeneratedJaxRsBeanParamModel` | SPI interface implemented by each `{Bean}_BeanParamModel` companion; returns a list of `BeanParamFieldMeta` descriptors |
| `GeneratedJaxRsBeanParamRegistry` | Process-wide singleton; analogous to `GeneratedJaxRsDescriptorRegistry`; companion FQN suffix is `_BeanParamModel` |
| `BeanParamFieldMeta` | Immutable record `(String name, ResourceMethodMeta.ParamMeta meta)` describing a single bean-param field; `meta` carries the field's JAX-RS source, type, `@DefaultValue`, and annotations — including input-policy annotations (`@Canonicalize`, `@Sanitize`, `@Skip*`) that sanitization/canonicalization consume |
| `ResourceExecutionPlan` | SPI interface implemented by each `{Resource}_{method}_{idx}_ExecutionPlan`; two methods: `extractArguments(RoutingContext, BoundRequest, GeneratedJaxRsSupport)` and `invoke(Object resource, Object[] args)` |
| `GeneratedJaxRsSupport` | Per-request helper bag for generated execution plans: `extractScalarParam`, `extractFormParam`, `deserializeBody`, `extractBeanParam`, `resolveContext(Class<?>, RoutingContext, String, String)`, etc.; backed by `ParameterExtractorBackedSupport`. Context resolution goes through `resolveContext` and the `RestContextResolver` chain. |

Array-typed parameter FQNs are emitted onto the descriptor wire format as a **binary** base name plus source-form `[]` suffixes — for example `com.example.Outer$Inner[]` — because `Class.forName` cannot load the Java source array form (`com.example.Outer.Inner[]`) that a plain `toString()` of the type would produce. `GeneratedJaxRsDescriptorSupport.resolveClass` counts and strips the trailing `[]` pairs, resolves the base type via `Class.forName`, and reconstructs the array `Class` via `java.lang.reflect.Array.newInstance`. A separate source-form FQN rendering is used wherever the emitted string is interpolated into generated Java *source* rather than into a runtime class lookup — for example a Jackson `TypeReference` literal for a body parameter — since a binary name containing `$` is not valid Java source and would fail to compile.

`ResourceMethodMeta.ParamMeta` composes `dev.vertique.core.codegen.ParameterMetadata` (name, raw type, generic type, `annotationsLazy()`) rather than holding an eager live `Annotation[]`. Both codegen construction paths follow a **parity-first, reflection-free-as-best-effort** policy: every parameter annotation whose member shape can be rendered at compile time is literal-backed; a parameter carrying an annotation that cannot be is not left with a gap — it gets a lazy per-parameter reflective fallback instead, so runtime behavior is always identical to the reflective-scan path regardless of what a given annotation's members look like.

- **`ExecutionPlanEmitter`** — feeds the per-request extraction path (`ParameterExtractor`'s scalar/collection coercion). For each extractable parameter it materializes the parameter's `@Retention(RUNTIME)` annotations into a standalone `ParameterMetadata` implementation — one generated class per parameter, emitted as a top-level sibling of the execution plan (named `{PlanSimpleName}_P{paramIndex}Meta`) — reusing `vertique-codegen-core`'s `MetadataEmitter.emitParameterMetadata` and `AnnotationLiteralEmitter`, the same machinery `vertique-codegen-aop`'s `AopProxyEmitter` uses for its own parameter-level literals.
- **`JaxRsDescriptorEmitter`** — feeds the `JaxRsOperationDescriptor` (startup validation, OpenAPI, security-policy adaptation). It materializes parameter annotations the same way, into a standalone impl named `{DescriptorSimpleName}_M{methodIdx}P{paramIndex}Meta`, replacing the earlier live `support.effectiveParameterAnnotations(method, index)` reflective read.
- Both emitters read from `EffectiveParamContract.annotationSources()` (not the concrete parameter's `VariableElement` alone) — the concrete parameter plus the matching parameter on each superclass/interface override that declares the method, mirroring the runtime `AnnotationResolver.resolveParameterAnnotations` merge (`EffectiveJaxRsContractResolver.resolveParamAnnotationSources`). Materialization unions annotation types across all sources with concrete-first precedence, so a converter-decision marker declared only on an interface method's parameter is not lost on the codegen path.
- Both emitters share a single per-compilation-round `Set<String>` dedup set (threaded through `JaxRsPipelineProcessor`, passed to every `emit(...)` call on either emitter) so a JAX-RS-owned `<Ann>$JaxRsLiteral` class referenced by more than one parameter — including across the two emitters, since both visit the same parameters — is written to the `Filer` at most once. The `JaxRs` namespace makes this processor's ownership explicit and avoids colliding with literals emitted by AOP for the same annotation type.
- **Mixed literal/reflective mode.** Every legal Java annotation member kind, including `char`, floating-point, nested-annotation, and array members, is renderable by `AnnotationLiteralEmitter`, so a normal parameter's generated `ParameterMetadata` is purely literal-backed and fully reflection-free. If the retained compatibility hook reports a future or otherwise non-renderable shape, the generated `ParameterMetadata` instead bakes in literals for every annotation it can render and additionally carries a lazy reflective fallback for the rest, sourced from `dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsReflectiveAnnotations.mergedParameterAnnotations(...)` — which resolves the same merged concrete+superclass+interface annotation set `AnnotationResolver.resolveParameterAnnotations` produces for the reflective `ResourceScanner` path. `findAnnotation`/`hasAnnotation` check the literals first, then the reflective fallback; `annotationsLazy()` returns the full merged effective array (defensively cloned). No annotation is ever silently dropped and no compile error is raised for a non-renderable member on this path.
- The generated `annotationsLazy()` returns a defensive copy (`annotations.clone()` in the pure-literal case; a cloned merged reflective array in the mixed case), not a shared backing array, since `ParamConversionResolver` passes the array directly to external `ParamConverterProvider`s and the backing field/reflective read is reused across requests on the same generated route.

The reflective runtime path (`ResourceScanner`) backs the composed view with `dev.vertique.core.codegen.ReflectiveParameterMetadata` — the same reflective implementation `rest-client`'s scan path uses, not a jaxrs-local duplicate — wrapping the parameter's merged `Annotation[]` (from `AnnotationResolver.resolveParameterAnnotations`) via a small internal `AnnotatedElement` adapter on `ResourceMethodMeta.ParamMeta`'s convenience constructor. `annotationsLazy()` is consulted only when a JAX-RS `ParamConverterProvider` is registered; a registered provider sees real, materialized parameter annotations identically on the reflective-scan path and on both codegen paths — including a parameter whose annotations are only partly literalizable, via the mixed literal/reflective mode above.

**`JaxRsParamSource` enum (in `vertique-rest-jaxrs`).** The codegen pipeline uses `JaxRsParamSource` as its local counterpart to the runtime `ResourceMethodMeta.ParamSource`. The enum has a single `CONTEXT` value for all `@Context`-annotated parameters regardless of type. This enum is kept in lockstep with the runtime `ParamSource` — they are not interchangeable at runtime, but their value spaces must agree.

**Fallback policy:** `ClassNotFoundException` is cached as a normal miss; the caller falls back to the reflective path. Any other `ReflectiveOperationException` or cast failure is wrapped, cached, and rethrown on every subsequent access — a broken generated class is a build defect, not a silent miss.

---

## Validation Rules

### Tier A — Acceptance Parity

| Check | Runtime channel | Build-time diagnostic |
|---|---|---|
| `@Context` parameter also carries a value-binding annotation (`@PathParam`, `@QueryParam`, `@HeaderParam`, `@CookieParam`, `@FormParam`, `@BeanParam`) | `RouteValidator` → `CONTEXT_PARAM_CONFLICT` | `@Context parameter '{name}' on {method}() must not carry a value-binding annotation` |
| `@Context` parameter is a reserved JAX-RS V1-unsupported type (`UriInfo`, `HttpHeaders`, `Request`, `Configuration`, `Application`, `Providers`, `ResourceContext`) | `RouteValidator` → `UNSUPPORTED_JAXRS_CONTEXT_TYPE` | `@Context parameter '{name}' on {method}() uses unsupported JAX-RS type {type}` |
| `@Context` parameter type is neither a built-in nor a `ContextValue` | `RouteValidator` → `NON_INJECTABLE_CONTEXT_TYPE` | `@Context parameter '{name}' on {method}() type {type} is not @Context-injectable` |
| `@DenyAll` combined with `@PermitAll`, `@RolesAllowed`, or `@Authorized` at the same level (including when the annotations sit on different declarations of the same method or class) | `ResourceScanner` → `SecurityPolicyViolation.CONFLICTING_SECURITY_ANNOTATIONS` | `Conflicting security annotations at {level}: {combination}; pick one of @DenyAll, @PermitAll, or @RolesAllowed/@Authorized` |
| `@PermitAll` combined with `@RolesAllowed` or `@Authorized` at the same level (including across declarations) | Same | Same |
| Differing `@RolesAllowed` role arrays or `@Authorized` scopes/`matchAll` across declarations of the same method or class | Same | Same |
| `@RolesAllowed({})` empty value array | `ResourceScanner` → `SecurityPolicyViolation.EMPTY_ROLES_ALLOWED` | `@RolesAllowed at {level} has empty value array — use @DenyAll to deny access or specify at least one role` |
| `@RequiresPolicy` mixed with inline security, or two policies in one method or type set | `ResourceScanner` → `SecurityPolicyViolation.CONFLICTING_SECURITY_ANNOTATIONS` | `Conflicting security annotations at {level}: @RequiresPolicy; pick one of @DenyAll, @PermitAll, or @RolesAllowed/@Authorized` |
| An additive input-policy annotation and its skip counterpart in the same merged element (`@Sanitize` with `@SkipSanitization`, `@Canonicalize` with `@SkipCanonicalization`), including across an override | Shared invocation-policy resolution → `InvocationPolicyConflictException` at scan time | `Conflicting @Sanitize (declared on {site}) and @SkipSanitization (declared on {site}) for {element} — an override cannot remove an inherited policy; remove one of the annotations.` |
| More than one body parameter | `RouteValidator.validateMethodParams` | `Method {name}() has {count} body parameters; at most one is allowed` |
| `@FormParam`/file-upload mixed with body parameter | `RouteValidator.validateMethodParams` | `Method {name}() mixes @FormParam/file upload parameters with a body parameter; use one or the other` |

Class-level and method-level security conflicts are checked independently. Context violations (first three rows) short-circuit the remaining per-method checks.

### Tier B — Build-Time-Only Guardrails

| Check | Runtime behavior | Build-time diagnostic |
|---|---|---|
| Multiple HTTP verb annotations on one method | Picks first in declaration order | `Method {name}() declares multiple HTTP verb annotations ({verbs}); pick one` |

### Pre-empted Defects — No Runtime Equivalent

| Check | Runtime behavior | Build-time diagnostic |
|---|---|---|
| `@Path` placeholder with no matching `@PathParam` | Placeholder resolves to null at dispatch | `@Path placeholder '{name}' on {method} has no matching @PathParam` |
| `@PathParam` with no matching placeholder in the effective `@Path` | Parameter receives null or absent value | `@PathParam("name") on {method} has no matching placeholder in @Path` |

---

## Shared Helpers

`PathPlaceholders`, `JaxRsBeanScanner`, and `PackageResolver` live in `vertique-codegen-core`.

`PathPlaceholders` and `JaxRsBeanScanner` are shared with the rest-client codegen validator. `PathPlaceholders.extract(String path)` returns placeholder names with any `:regex` suffix stripped — for example, `{id:[0-9]+}` yields `id`. `JaxRsBeanScanner` recursively scans composite types (annotated `@RequestParams` or `@BeanParam`) for `@PathParam` and `@FormParam` names, covering both fields and record components.

`PackageResolver` computes the output package for generated files via LCP of origin-element packages. Override with `-Avertique.codegen.package=...`.

---

## Performance Characteristics

Benchmark numbers from `RegistrationBenchmark` and `InvocationBenchmark` (JUnit-based directional measurements; not research-grade microbenchmarks):

| Path | Observed speedup | NFR target |
|---|---|---|
| Registration: generated descriptor vs reflective `ResourceScanner` walk | 82–93× | ≥2× |
| Invocation: generated execution plan vs `ParameterExtractor + Method.invoke` | 46–52× | ≥1.5× |

The large observed ratios are directional measurements over a hot JVM loop with a representative fixture (`BenchResource` — 4 methods with path/query/header parameters). Real-world improvement depends on resource count, method count, parameter complexity, and JVM warm-up.

`RegistrationBenchmark` and `InvocationBenchmark` are manual-only: their class names deliberately do not match Surefire's include patterns, so they are excluded from `test`/`verify`/CI by name and must be run explicitly (see their class-level Javadoc for the exact command).

---

## Extension Points

### `-Avertique.codegen.autoWire=false` — global disable

Suppresses **only the generated `GeneratedJaxRsResourcesModule`**: no resource binding, catalog entry, or application registration is written for any unit in the build. Descriptor, bean-param model, and execution-plan companions are still emitted; only the DI module is skipped. Every `@RestApplication` declaration not annotated `@NoAutoWire` is still checked — the declaration checks, the annotation allow list, and the `@ApiDocs` checks (see "REST Application Declarations" above) all still fail the build when violated — and each gets one `autoWire=false` warning naming it and stating that it is not registered. A `jakarta.ws.rs.core.Application` subclass gets only its usual subclass warning (see "Jakarta `Application` Subclasses" above). Useful when you manage resource bindings manually or want to test companions without the generated module.

**Package resolution does not fail the build.** Because no module is written under this option, the processor still resolves a package to validate against, without emitting `PackageResolver`'s disjoint-packages error: the `-Avertique.codegen.package` override when set, otherwise the longest common package prefix of the unit's DI-eligible resources (or, in a unit without them, of its declarations), otherwise no package at all. When no package resolves, accessibility validation falls back to public-only — a declaring interface, a listed resource class, or an enclosing type that is merely package-private has no package left to match against and fails the build.

### `-Avertique.codegen.package=...` — output package override

Overrides `PackageResolver`'s LCP computation. Required when annotated types live in disjoint packages **and auto-wiring is enabled**, because a module must be written in that case and `PackageResolver.resolve` fails the build rather than guess a package. With `-Avertique.codegen.autoWire=false`, no module is written, so disjoint origin packages resolve without that failure instead — to this override when set, otherwise to the origins' longest common package prefix, otherwise to no package at all — and no error is raised. Also forces two compilation units' generated modules apart when they would otherwise resolve to the same package (see "Distinct-Package Rule" above).

### `@NoAutoWire` opt-out

Place on any `@Path`-annotated type to exclude it from Dagger module emission; validation and descriptor emission still apply, and only its `GeneratedJaxRsResourcesModule` binding is suppressed.

Place on a `@RestApplication` declaration instead, and the declaration is inert: it is neither registered nor checked, and gets one warning naming it (see "Activation and Opting Out" above).

Place on a `jakarta.ws.rs.core.Application` subclass, and it suppresses that subclass's warning; the subclass is never registered either way (see "Jakarta `Application` Subclasses" above).

---

## Module Dagger Bindings

None at runtime. `vertique-codegen-jaxrs` is a compile-time annotation processor. It generates a `GeneratedJaxRsResourcesModule` for the consuming module's component, contributing to the `@JaxRsResources` multibinding set and, for each registered `@RestApplication` declaration, to the `GeneratedRestApplicationRegistration` set. A component that lists a generated module must also list `RestModule`, which declares that registration set (see "Components Must Also List `RestModule`" above).

---

## Dependencies

| Artifact | Scope | Purpose |
|----------|-------|---------|
| `vertique-codegen-core` | compile | `CodegenContext`, `TypeResolver`, `AnnotationMirrors`, `Diagnostics`, `PackageResolver`, `PathPlaceholders`, `JaxRsBeanScanner`, `JaxRsAnnotations`, `@NoAutoWire` |
| `vertique-rest-jaxrs` | test | Runtime SPI types the generated sources reference by fully qualified name: `GeneratedJaxRsResourceDescriptor`, `ResourceExecutionPlan`, `GeneratedJaxRsBeanParamModel`, `BeanParamFieldMeta`, `GeneratedRestApplicationRegistration`; `ResourceMethodMeta` (for descriptor method signature). The consuming application declares it (see "Adoption"); tests load the generated companions reflectively |
| `vertique-rest-core` | compile | `RequestPreconditions`, `dev.vertique.rest.core.application.RestApplication` (matched by name) |
| `vertique-security-core` | compile | `dev.vertique.security.authz.Authorized` and framework security types such as `dev.vertique.security.SecurityContext`, a `ContextValue` the `@Context` parameter checks accept |
| `vertique-input-processing` | compile | `dev.vertique.input.processing.EffectiveInputPolicies` (referenced by generated `ExecutionPlan` constants); `dev.vertique.input.processing.apt.ElementInvocationPolicies` and `InvocationPolicyConflictException`, the shared compile-time derivation of route and parameter policy chains |
| `jakarta.ws.rs-api` | test | JAX-RS annotation types for resource fixtures; the processor recognizes them by fully qualified name |
| `jakarta.annotation-api` | compile | `@PermitAll`, `@RolesAllowed`, `@DenyAll` |
| `swagger-annotations-jakarta` | compile | `@io.swagger.v3.oas.annotations.Operation`, whose `operationId` the processor reads by fully qualified name |
| `com.palantir.javapoet:javapoet` | compile (transitive) | Source code emission, through `vertique-codegen-core` |

Other test-only dependencies: `vertique-codegen-test` (compilation harness) and the `vertique-input-processing` test-jar (input-policy fixture stubs).

---

## Known Gaps

- Descriptor-registry hierarchy walk for proxy/subclass resource patterns — the current registry uses `resource.getClass()` exactly and does not walk superclasses to find a companion for a subclass or proxy.
