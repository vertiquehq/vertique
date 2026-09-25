<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Codegen JAX-RS Pipeline Module

> **Status:** Beta
> **Package:** `dev.vertique.codegen.jaxrs`
> **Artifact:** `vertique-codegen-jaxrs`
> **Depends on:** `vertique-codegen-core` (compile), `vertique-rest-core` (compile — for `dev.vertique.rest.core.security.Authorized`), `vertique-rest-jaxrs` (compile — runtime SPI types)

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

1. **Discover** — collects concrete (non-abstract, non-interface) classes with an effective `@Path` — direct on the class, in the superclass chain, or on any transitively implemented interface.
2. **Resolve** — builds an `EffectiveResourceContract` for each candidate applying the precedence rule: direct annotations → superclass chain → BFS interfaces (see "EffectiveJaxRsContractResolver" below).
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
- Direct annotation **overrides** an interface declaration → compile warning so the developer notices the silent override.

Produces `EffectiveResourceContract` (one per class), which contains `EffectiveMethodContract` (one per resource method), which contains `EffectiveParamContract` (one per parameter) and `EffectiveSecurityContract`.

#### Input-policy derivation

Route- and parameter-level canonicalization and sanitization chains (`@Canonicalize`, `@Sanitize`, `@SkipCanonicalization`, `@SkipSanitization`) are not resolved here. The resolver delegates them to `ElementInvocationPolicies` in `vertique-input-processing`, the shared adapter the reflective runtime's own resolution is built on, so a generated execution plan carries exactly the chains the reflective path would have derived for the same declarations.

The practical consequence is that these annotations obey the same hierarchy rules as every other JAX-RS annotation the processor reads: a policy declared on an interface method, on an inherited superclass method, or at class level on a superclass reaches the generated plan, and a parameter annotation declared only on an interface method's parameter reaches the generated parameter chain. Composed annotations (a custom annotation meta-annotated with `@Sanitize(...)`) resolve recursively.

Declaring an additive annotation and its skip counterpart anywhere in the same merged element is a **compile error**, reported at the method for a route conflict and at the parameter for a parameter conflict. That includes the inherited shape: an override that adds `@SkipSanitization` over an interface method's `@Sanitize(...)`, or a subclass that adds a class-level `@Sanitize(...)` over a superclass's `@SkipSanitization`, is rejected — an override may replace an inherited policy but never remove one. Replacing is fine: an override declaring `@Sanitize(A.class)` over an inherited `@Sanitize(B.class)` resolves to `A`. A method excluded by a route conflict does not reach the generated contract at all; a parameter excluded by a parameter conflict falls back to no policies. Either way the diagnostic names the axis, both declaration sites, and the offending element.

`resolveComponentType` classifies a `T[]` array-typed parameter as a multi-value parameter, at parity with the reflective runtime's `ResourceScanner.resolveComponentType` — a codegen'd resource binds all repeated values for `String[]`, `Integer[]`, boxed-wrapper array, and enum array element types identically to the reflective path. The classification is scoped by parameter **source**. The four bindable multi-value sources — `@QueryParam`, `@HeaderParam`, `@CookieParam`, `@FormParam` — resolve a component type, and so do the two native multipart aggregates (`FILE_UPLOADS`, `ENTITY_PARTS`), whose declared `List` element type is what the runtime scanner records for them. A body parameter also resolves one, which is a deliberate difference from the reflective scanner: it is inert for binding, because a body value is deserialized rather than materialized as a collection. `@PathParam`, `@Context`, request preconditions, and `@BeanParam` never resolve one. Path values in particular come from `RoutingContext.pathParams()`, a `Map<String, String>`, so a path parameter is single-valued on both paths, and an array-typed `@PathParam` is rejected at startup (`UNRESOLVABLE_PARAM_CONVERTER`) rather than mounted. Primitive scalar-array element types (`byte[]`, `char[]`, `int[]`, etc.) are deliberately excluded from this policy and remain BODY parameters on both paths; so is a nested array (`String[][]`), whose element type is itself an array. The processor implements the scalar-array-component policy over `TypeMirror`; the runtime implements the same policy over `Class<?>`; the two are held equal by a parity test rather than by sharing one method.

For a parameterized collection (`List<T>`, `Set<T>`, `SortedSet<T>`, `NavigableSet<T>`, `Collection<T>`) the element type resolves only when the type argument is one core reflection reifies as a plain `Class` — a non-generic type (`List<String>`, `List<Season>`) or an array of one (`List<String[]>`). A wildcard (`List<? extends CharSequence>`, `List<?>`), a type variable (`List<T>`), and a nested parameterized type (`List<List<String>>`) resolve no component type, exactly as the reflective scanner's `typeArg instanceof Class<?>` test rejects them. Since a non-null component type is the framework's single multiplicity trigger, this gate is what keeps the same declaration from being classified differently depending on whether codegen ran.

### Validators

| Class | Role |
|---|---|
| `ContextParamValidator` | Enforces FR-REST-187/188/189: (a) rejects an `@Context` parameter (or one auto-classified as CONTEXT by an injectable type) that also carries a value-binding annotation (`@PathParam`, `@QueryParam`, `@HeaderParam`, `@CookieParam`, `@FormParam`, `@BeanParam`) — conflict; (b) rejects reserved JAX-RS types unsupported in V1 (`UriInfo`, `HttpHeaders`, `Request`, `Configuration`, `Application`, `Providers`, `ResourceContext`); (c) rejects a `@Context` parameter whose type is neither a built-in nor a `ContextValue` subtype — non-injectable. Runs **before** the path/body-form validators and short-circuits them for any method carrying a context violation. Handles split inherited annotations (interface vs. impl) via interface-method walking. |
| `SecurityAnnotationValidator` | Mirrors `AnnotationSecurityPolicyResolver` conflict matrix: `@DenyAll` + `@PermitAll`/`@RolesAllowed`, `@PermitAll` + `@RolesAllowed`, `@RolesAllowed({})` empty array. Class-level and method-level checked independently. |
| `HttpVerbValidator` | Tier-B guardrail: error on multiple HTTP verb annotations on a single method. |
| `PathParamAlignmentValidator` | Bidirectional check between `@Path` placeholders and `@PathParam` declarations. Handles `@BeanParam` and `@RequestParams` composite types including record components. |
| `BodyFormValidator` | Mirrors `RouteValidator.validateMethodParams`: at most one body parameter; body and form parameters mutually exclusive. |
| `ApplicationAnnotationValidator` | Checks every eligible `jakarta.ws.rs.core.Application`'s annotation scope — the application, its superclasses strictly below `jakarta.ws.rs.core.Application`, and every interface any of them implements, transitively including superinterfaces — against the fixed application annotation allow list, right after `JaxRsApplicationScanner.scan` and before `JaxRsApplicationScanner.validate` runs; see "Application Annotation Allow List" below. |

`ContextParamValidator` runs first, before the remaining four resource-contract validators — see the short-circuit behavior noted under "Validation Rules" below. Those five validators take `EffectiveResourceContract`; `ApplicationAnnotationValidator` instead validates an eligible application's annotation scope directly, against the list `JaxRsApplicationScanner.scan` produces.

### Generated Artifacts

Four artifact types are emitted for every semantic candidate:

| Artifact | What it does |
|---|---|
| `GeneratedJaxRsResourcesModule` Dagger module | A presence-gated `@Provides @ElementsIntoSet @JaxRsResources Set<Object>` binding and a lazy `GeneratedJaxRsResourceEntry` catalog entry for each DI-eligible resource, plus a `GeneratedJaxRsApplicationRegistration` for each eligible `jakarta.ws.rs.core.Application` subtype — see "Generated Binding Shape" below |
| `{Resource}_JaxRsDescriptor` | Precomputes `SecurityPolicy` constants and method/parameter metadata, eliminating the reflective `getDeclaredMethods()` walk at startup |
| `{Bean}_BeanParamModel` | Static field-metadata list per `@BeanParam`/`@RequestParams` type, eliminating the reflective bean-field scan |
| `{Resource}_{methodName}_{idx}_ExecutionPlan` | Precomputed `EffectiveInputPolicies` (`dev.vertique.input.processing`) constants plus a direct typed method call, eliminating `Method.invoke` from the request hot path. For `CONTEXT` parameters, emits a static `Class<?>` constant (`CTX{i}`) loaded once at class-initialization time and a `support.resolveContext(CTX{i}, ctx, "<declaringClassFqn>", "<method>")` call per parameter — no per-request reflection, no `ParamMeta`/policy entry for `CONTEXT` params |

Only the Dagger module row is gated by DI eligibility (see "Semantic vs. DI candidates" above) — the other three are emitted for every semantic candidate regardless.

---

## Generated Binding Shape

Each compilation unit's `GeneratedJaxRsResourcesModule` keeps one name and stays a concrete Dagger module. It is written only when the unit has at least one DI-eligible resource or at least one eligible `jakarta.ws.rs.core.Application` subtype (see "Application Registrations" below); when neither is present, nothing is written. Its package is resolved from the unit's DI-eligible resources whenever it has any; only in an applications-only unit does the set of eligible applications decide the package instead — so adding an `Application` to a unit that already has resources never moves that unit's existing module.

### Resource Bindings

Every DI-eligible resource `R` gets two `@Provides` methods, both taking `@VertxConfig JsonObject config` whether or not `R` carries `@ConditionalOnProperty`:

- a presence-gated `@Provides @ElementsIntoSet @JaxRsResources Set<Object>` binding, named by `R`'s decapitalized simple name plus `Binding`, that also takes `Set<GeneratedJaxRsApplicationRegistration> applications` and a `Provider<R>`, and contributes `R` only when `applications` is empty (no application is registered in this composition) and `R`'s own conditions, if any, are satisfied;
- a lazy `@Provides @IntoSet GeneratedJaxRsResourceEntry` catalog entry, named by `R`'s decapitalized simple name plus `Entry`, carrying `R`'s class, its evaluated condition result, and its `Provider<R>` — it never calls the provider.

**Unconditional resource binding** (`CatalogResource`, no `@ConditionalOnProperty`):

```java
@Provides
@ElementsIntoSet
@JaxRsResources
static Set<Object> catalogResourceBinding(@VertxConfig JsonObject config,
        Set<GeneratedJaxRsApplicationRegistration> applications, Provider<CatalogResource> provider) {
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
        Set<GeneratedJaxRsApplicationRegistration> applications,
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

### Application Registrations

Every eligible `jakarta.ws.rs.core.Application` subtype `A` — concrete, top-level or static nested at any depth, and not annotated `@NoAutoWire` — gets one `@Provides @IntoSet GeneratedJaxRsApplicationRegistration` method, named by `A`'s decapitalized simple name plus `Registration`, taking `@VertxConfig JsonObject config`:

```java
@Provides
@IntoSet
static GeneratedJaxRsApplicationRegistration managementApplicationRegistration(
        @VertxConfig JsonObject config) {
    return GeneratedJaxRsApplicationRegistration.of(
            ManagementApplication.class, "/api/mgmt", true, ManagementApplication::new);
}
```

`A::new` is the factory argument when `A` has no `@Inject` constructor, as shown above. When `A` has exactly one `@Inject` constructor (`jakarta` or `javax`) instead, the method additionally takes a `Provider<A>` parameter and passes it as the factory argument in place of `A::new`.

The path argument is `A`'s `@ApplicationPath` value, read from `A` itself or the nearest superclass that carries it (an interface is never read), then normalized at compile time: an empty value, or one with no leading `/`, is anchored to `/`; one terminal `/*` and every trailing `/` are then removed — so `/api/public/` and `/api/public/*` both normalize to `/api/public`, and a value of `/` or `/*` normalizes to `/`.

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

The condition argument follows the same `PropertyCondition.matchesAll(config, …_CONDITIONS)` rule as a resource binding, or `true` when `A` carries no `@ConditionalOnProperty`.

**Naming.** A registration method's base name is `A`'s decapitalized simple name plus `Registration`. When two eligible applications in one unit share a simple name (in different enclosing scopes), the processor appends `_2`, `_3`, and so on to the later ones, in fully-qualified-name order.

**Local and anonymous subclasses are invisible to the processor.** A `jakarta.ws.rs.core.Application` subclass declared inside a method body (a local class), or as an anonymous class expression, is not among the round's root elements the processor scans, so it is neither registered nor reported — no diagnostic names it. Declare every `Application` subclass as a top-level class, or a static nested class at any depth, so the processor can see it.

### Application Annotation Allow List

Every eligible application `A` is also checked against a fixed annotation allow list, across the same scope its registration and path resolution already use: `A` itself, every superclass of `A` strictly below `jakarta.ws.rs.core.Application` (nearest first), and every interface any of them implements, transitively including superinterfaces. Only annotations on the type declarations in that scope are checked — an annotation on a member, such as the `@Inject` constructor or an overriding method, has no effect on an application and is never inspected — and a repeatable container annotation (for example the compiler-synthesized `ConditionalOnProperties` when two or more `@ConditionalOnProperty` annotations appear on one type) is checked as an annotation in its own right, not unwrapped into its repeated elements.

Every `RUNTIME`-retained type-declaration annotation in that scope must be one of: `@jakarta.ws.rs.ApplicationPath`, but not on an interface; a type meta-annotated with `jakarta.inject.Scope`, `jakarta.inject.Qualifier`, `javax.inject.Scope`, or `javax.inject.Qualifier`; a type in package `java.lang`; or `@io.swagger.v3.oas.annotations.OpenAPIDefinition` in which every element other than `info` equals its declared default. Any other `RUNTIME`-retained annotation fails the build (see "Diagnostics" below), because application classes carry no resource semantics — security, audit, and profile annotations belong on resources, not on the `Application` that assembles them.

`@ConditionalOnProperty`, `@ConditionalOnProperties`, and `@NoAutoWire` are `SOURCE`-retained and honored only on `A` itself; found on any supertype compiled in the same build, each is a compile error instead, because the processor would otherwise ignore it there silently.

A class annotated `@NoAutoWire` is exempt from all of this: it is inert, neither registered nor validated by the allow list or by the checks described under "Application Registrations" above, and it gets only the `@NoAutoWire` warning below instead.

### Diagnostics

| Diagnostic | Text |
| --- | --- |
| Registration note | `Registered JAX-RS application {Application} at {normalized path}` |
| `@NoAutoWire` warning | `{Application} is annotated @NoAutoWire, so it is not registered or validated; its resources fall back to the default mount.` |
| `autoWire=false` warning | `{Application} is not auto-wired because -Avertique.codegen.autoWire=false is set; its resources are exposed through the default @JaxRsResources mount.` |
| Allow-list violation (error) | `Application {Application} carries @{annotation} on {declaring type}, which is not allowed: application classes carry no resource semantics` (appends `; only its info element may be set` when `{annotation}` is `@OpenAPIDefinition`) |
| Source-retained annotation on a supertype (error) | `Application {Application} carries @{annotation} on supertype {declaring type}, which is honored only on the application class itself` |
| Missing `@ApplicationPath` (error) | `{Application} declares no @ApplicationPath on itself or any superclass; declare @ApplicationPath("/") for a root application.` |
| Invalid `@ApplicationPath` value (error) | `{Application} has an invalid @ApplicationPath value "{value}" (rule: {rule}); an application path may contain only '/' and the characters A-Z a-z 0-9 . _ ~ -` |
| No usable constructor (error) | `{Application} has no usable constructor for application registration: it needs exactly one @Inject constructor, or an accessible no-arg constructor.` |
| Inaccessible class (error) | `{Application} is not accessible from the generated module's package '{package}'; make it public, or package-private in that same package.` |
| Non-static inner class (error) | `{Application} must be a top-level or static nested class to be an auto-wired JAX-RS application.` |
| Missing `vertique-rest-jaxrs` dependency (error) | `An eligible JAX-RS application was found, but 'vertique-rest-jaxrs' is not on the compile classpath; add it as a dependency to generate application registrations.` |

Every eligible application is validated regardless of `-Avertique.codegen.autoWire=false` — accessibility, construction, the required `@ApplicationPath`, and its path grammar (see "Application Registrations" above) all still fail the build when violated; only the module write itself is skipped under that option (see "Extension Points" below).

**Accessibility reaches enclosing types.** The inaccessible-class diagnostic above is not satisfied by the application class alone: the application *and every one of its enclosing types* must each be accessible from the generated module's package — public, or non-private and declared in that same package. A class nested inside an inaccessible enclosing type is unreachable from outside that type's package even when the nested class itself is `public`, so it fails this check too.

### Components Must Also List `RestModule`

Each presence-gated resource binding consumes the `Set<GeneratedJaxRsApplicationRegistration>` multibinding that `RestModule` declares. A Dagger component that lists a generated `GeneratedJaxRsResourcesModule` must therefore also list `RestModule`, or that set is unsatisfied and the component fails to compile.

### Distinct-Package Rule

Two compilation units whose classes resolve to the same package both write `<package>.GeneratedJaxRsResourcesModule`, and whichever copy lands first on the classpath wins silently — the processor cannot reliably tell a stale copy of a unit's own prior output from another unit's module, so it does not check for a collision. Each compilation unit must resolve a distinct package for its generated module, or set `-Avertique.codegen.package` to force one; otherwise one unit's bindings and registrations can silently never reach the component.

### Upgrading an Existing `Application` Subclass

A concrete `jakarta.ws.rs.core.Application` subclass that this processor previously ignored now becomes an eligible application on upgrade: it is registered, and the deployment switches from the implicit default mount into discovery or explicit mode for that composition. Such a class must carry `@ApplicationPath` on itself or a superclass, that value must satisfy the path grammar, and neither it nor a superclass or implemented interface may carry an annotation outside the application annotation allow list (see "Application Registrations" and "Application Annotation Allow List" above) — all three checks apply even under `-Avertique.codegen.autoWire=false`, which still validates every eligible application. Annotate the class `@NoAutoWire` to opt back out of all three: it exempts the class from registration, from the allow list, and from the path grammar, keeps the legacy default `@JaxRsResources` mount for its resources, and produces the `@NoAutoWire` warning above instead of a registration note.

---

## Interface-Declared Contracts

The processor supports the common OpenAPI Generator pattern where the contract is declared on an interface and the concrete class is mostly unannotated.

**Codegen path** — `EffectiveJaxRsContractResolver` walks the BFS interface graph and produces an `EffectiveResourceContract` that captures all interface-declared annotations. The descriptor and execution-plan emitters then generate artifacts from the effective contract.

**Reflective runtime path** — `ResourceScanner.scanResource`, `resolveHttpMethod`, `resolveParams`, and `AnnotationSecurityPolicyResolver` consume `AnnotationResolver`-merged annotation lists that include interface-declared annotations, so interface-backed contracts work correctly even when the processor is absent.

Interface- and superclass-declared canonicalization and sanitization policies follow the same rule on both paths, because both resolve them through the shared adapter in `vertique-input-processing` rather than through their own walk.

Both paths produce identical `ResourceMethodMeta` and identical `SecurityPolicy` outcomes. Removing `vertique-codegen-jaxrs` from `annotationProcessorPaths` changes performance, not behavior.

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
| `@DenyAll` combined with `@PermitAll`, `@RolesAllowed`, or `@Authorized` at the same level | `ResourceScanner` → `SecurityPolicyViolation.CONFLICTING_SECURITY_ANNOTATIONS` | `Conflicting security annotations at {level}: {combination}; pick one of @DenyAll, @PermitAll, or @RolesAllowed/@Authorized` |
| `@PermitAll` combined with `@RolesAllowed` or `@Authorized` at the same level | Same | Same |
| `@RolesAllowed({})` empty value array | `ResourceScanner` → `SecurityPolicyViolation.EMPTY_ROLES_ALLOWED` | `@RolesAllowed at {level} has empty value array — use @DenyAll to deny access or specify at least one role` |
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

Suppresses **only the generated `GeneratedJaxRsResourcesModule`**: no resource binding, catalog entry, or application registration is written for any unit in the build. Descriptor, bean-param model, and execution-plan companions are still emitted; only the DI module is skipped. Every eligible `jakarta.ws.rs.core.Application` is still validated — accessibility, construction, the required `@ApplicationPath`, its path grammar, and the application annotation allow list (see "Application Registrations" and "Application Annotation Allow List" above) all still fail the build when violated — and each eligible, non-`@NoAutoWire` application gets one `autoWire=false` warning naming it and stating that its resources are exposed through the default `@JaxRsResources` mount instead. Useful when you manage resource bindings manually or want to test companions without the generated module.

**Package resolution does not fail the build.** Because no module is written under this option, the processor still resolves a package to validate against, without emitting `PackageResolver`'s disjoint-packages error: the `-Avertique.codegen.package` override when set, otherwise the longest common package prefix of the unit's DI-eligible resources (or, in an applications-only unit, of its eligible applications), otherwise no package at all. When no package resolves, accessibility and no-arg-constructor validation fall back to public-only — an application, an enclosing type, or a no-arg constructor that is merely package-private has no package left to match against and fails the build.

### `-Avertique.codegen.package=...` — output package override

Overrides `PackageResolver`'s LCP computation. Required when annotated types live in disjoint packages **and auto-wiring is enabled**, because a module must be written in that case and `PackageResolver.resolve` fails the build rather than guess a package. With `-Avertique.codegen.autoWire=false`, no module is written, so disjoint origin packages resolve without that failure instead — to this override when set, otherwise to the origins' longest common package prefix, otherwise to no package at all — and no error is raised. Also forces two compilation units' generated modules apart when they would otherwise resolve to the same package (see "Distinct-Package Rule" above).

### `@NoAutoWire` opt-out

Place on any `@Path`-annotated type to exclude it from Dagger module emission; validation and descriptor emission still apply, and only its `GeneratedJaxRsResourcesModule` binding is suppressed.

Place on a `jakarta.ws.rs.core.Application` subtype instead, and it is inert: the class is neither registered nor validated (it is not checked for `@ApplicationPath`, its path grammar, a usable constructor, accessibility, or its annotations against the application annotation allow list), it keeps the legacy default `@JaxRsResources` mount for its resources, and it gets the `@NoAutoWire` warning above instead of a registration note or an error.

---

## Module Dagger Bindings

None at runtime. `vertique-codegen-jaxrs` is a compile-time annotation processor. It generates a `GeneratedJaxRsResourcesModule` for the consuming module's component, contributing to the `@JaxRsResources` multibinding set and, for each eligible application, to the `GeneratedJaxRsApplicationRegistration` set. A component that lists a generated module must also list `RestModule`, which declares that registration set (see "Components Must Also List `RestModule`" above).

---

## Dependencies

| Artifact | Scope | Purpose |
|----------|-------|---------|
| `vertique-codegen-core` | compile | `CodegenContext`, `TypeResolver`, `AnnotationMirrors`, `Diagnostics`, `PackageResolver`, `PathPlaceholders`, `JaxRsBeanScanner`, `JaxRsAnnotations`, `@NoAutoWire` |
| `vertique-rest-jaxrs` | compile | Runtime SPI interfaces: `GeneratedJaxRsResourceDescriptor`, `ResourceExecutionPlan`, `GeneratedJaxRsBeanParamModel`, `BeanParamFieldMeta`; `ResourceMethodMeta` (for descriptor method signature) |
| `vertique-rest-core` | compile | `dev.vertique.rest.core.security.Authorized`, `RequestPreconditions` |
| `vertique-input-processing` | compile | `dev.vertique.input.processing.EffectiveInputPolicies` (referenced by generated `ExecutionPlan` constants); `dev.vertique.input.processing.apt.ElementInvocationPolicies` and `InvocationPolicyConflictException`, the shared compile-time derivation of route and parameter policy chains |
| `jakarta.ws.rs-api` | compile | JAX-RS annotation types |
| `jakarta.annotation-api` | compile | `@PermitAll`, `@RolesAllowed`, `@DenyAll` |
| `com.palantir.javapoet:javapoet` | compile | Source code emission |

Test-only dependencies: `vertique-codegen-test`.

---

## Known Gaps

- Descriptor-registry hierarchy walk for proxy/subclass resource patterns — the current registry uses `resource.getClass()` exactly and does not walk superclasses to find a companion for a subclass or proxy.
