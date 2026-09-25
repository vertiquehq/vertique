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

`ContextParamValidator` runs first, before the remaining four — see the short-circuit behavior noted under "Validation Rules" below. All five validators take `EffectiveResourceContract`.

### Generated Artifacts

Four artifact types are emitted for every semantic candidate:

| Artifact | What it does |
|---|---|
| `GeneratedJaxRsResourcesModule` Dagger module | `@Provides @ElementsIntoSet @JaxRsResources Set<Object>` for each DI-eligible resource — see "Generated Binding Shape" below |
| `{Resource}_JaxRsDescriptor` | Precomputes `SecurityPolicy` constants and method/parameter metadata, eliminating the reflective `getDeclaredMethods()` walk at startup |
| `{Bean}_BeanParamModel` | Static field-metadata list per `@BeanParam`/`@RequestParams` type, eliminating the reflective bean-field scan |
| `{Resource}_{methodName}_{idx}_ExecutionPlan` | Precomputed `EffectiveInputPolicies` (`dev.vertique.input.processing`) constants plus a direct typed method call, eliminating `Method.invoke` from the request hot path. For `CONTEXT` parameters, emits a static `Class<?>` constant (`CTX{i}`) loaded once at class-initialization time and a `support.resolveContext(CTX{i}, ctx, "<declaringClassFqn>", "<method>")` call per parameter — no per-request reflection, no `ParamMeta`/policy entry for `CONTEXT` params |

Only the Dagger module row is gated by DI eligibility (see "Semantic vs. DI candidates" above) — the other three are emitted for every semantic candidate regardless.

---

## Generated Binding Shape

All JAX-RS resource bindings use a uniform `@Provides @ElementsIntoSet @JaxRsResources Set<Object>` form. This enables conditional resources to return `Set.of()` at startup without contributing to the Dagger graph.

**Unconditional resource binding:**

```java
@Provides
@ElementsIntoSet
@JaxRsResources
static Set<Object> helloResourceBinding(Provider<HelloResource> provider) {
    return Set.of(provider.get());
}
```

**Conditional resource binding** (`@ConditionalOnProperty(name = "adminApi.enabled")` on `AdminResource`):

```java
private static final PropertyCondition[] ADMIN_RESOURCE_CONDITIONS = new PropertyCondition[] {
    new PropertyCondition("adminApi.enabled", "true", false)
};

@Provides
@ElementsIntoSet
@JaxRsResources
static Set<Object> adminResourceBinding(
        @VertxConfig JsonObject config,
        Provider<AdminResource> provider) {
    return PropertyCondition.matchesAll(config, ADMIN_RESOURCE_CONDITIONS)
            ? Set.of(provider.get())
            : Set.of();
}
```

Inactive resources are never instantiated (lazy `Provider` injection). Descriptor, bean-param model, and execution-plan companions are still emitted for all semantic candidates — only the DI set contribution is empty.

---

## Interface-Declared Contracts

The processor supports the common OpenAPI Generator pattern where the contract is declared on an interface and the concrete class is mostly unannotated.

**Codegen path** — `EffectiveJaxRsContractResolver` walks the BFS interface graph and produces an `EffectiveResourceContract` that captures all interface-declared annotations. The descriptor and execution-plan emitters then generate artifacts from the effective contract.

**Interface default methods** — an annotated interface `default` method is a resource method of every class that implements the interface, whether or not the class overrides it. `JaxRsMethodDiscovery` adds each default the class inherits: one that no method of the superclass chain overrides and no method of a more specific interface overrides. The override test compares member types of the resource, so a class that overrides a generic default `remove(I)` with `remove(String)` shadows it — the same selection the runtime gets from `Class.getMethods()`. A default inherits annotations from the interfaces it overrides — its own interface hierarchy — as the runtime does from its declaring interface. The generated plan invokes the default through the resource type, and the descriptor names the interface as the method's declaring type, exactly as the reflective scanner does. An inherited generic method (a non-overridden default of `Crud<ID>`, or a generic superclass method) binds a type-variable parameter as its erasure on both paths; the processor emits no execution plan for it, because a typed call through the resource type would not compile, so it keeps reflective dispatch.

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

Suppresses **only Dagger module emission** (`GeneratedJaxRsResourcesModule`). Descriptor, bean-param model, and execution-plan companions are still emitted when `autoWire=false`; only the DI binding that registers resources with the Dagger component is skipped. Useful when you manage resource bindings manually or want to test companions without the generated module.

### `-Avertique.codegen.package=...` — output package override

Overrides `PackageResolver`'s LCP computation. Required when annotated types live in disjoint packages.

### `@NoAutoWire` opt-out

Place on any `@Path`-annotated type to exclude it from Dagger module emission. Validation and descriptor emission still apply; only the `GeneratedJaxRsResourcesModule` binding is suppressed.

---

## Module Dagger Bindings

None at runtime. `vertique-codegen-jaxrs` is a compile-time annotation processor. It generates a `GeneratedJaxRsResourcesModule` for the consuming module's component.

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
