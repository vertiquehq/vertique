<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Codegen Sanitization Input Processor Module

> **Status:** Implemented (CG-008)
> **Package:** `dev.vertique.codegen.sanitization.processor`
> **Artifact:** `vertique-codegen-sanitization`
> **Depends on:** `vertique-codegen-core`, `vertique-input-processing`, `vertique-rest-core` (compile)

`vertique-codegen-sanitization` is an annotation processor that generates `{DTO}_InputProcessor` walker classes for REST request body DTOs whose type tree carries `@Sanitize` or `@Canonicalize` annotations. The generated walkers replace the reflective `Map`/`List` traversal of the neutral input processing engine in `vertique-input-processing` with a direct field-name `switch` and pre-composed per-field chain constants, eliminating per-call metadata lookup and `descend()` allocation on the hot path.

Discovery is anchored on `@Path`-annotated resource methods in the current compilation unit. The emitted set is the **transitive closure** of participating DTO types reachable from those roots. No Dagger module is emitted — runtime registration self-populates via classloader lookup (`Class.forName`), mirroring `BeanParamAccessorRegistry`. The `@BindsOptionalOf InputObjectProcessor` wiring and the `SanitizationModule` binding are unchanged.

---

## Key Classes

### Generated `{DTO}_InputProcessor`

For each participating DTO type, the processor emits a `{DTO}_InputProcessor` class in the DTO's own package:

- `public final`, implements `GeneratedInputProcessor<T>`, with a public no-arg constructor for `Class.forName`-based instantiation by `GeneratedInputProcessorDispatcher`.
- `static final` chain constants — `List<Class<? extends Canonicalizer>>`, `List<Class<? extends Sanitizer>>`, and `boolean` skip flags — for the type-level chain and for each field, resolved once at class-load time.
- `process(...)` dispatches on a `switch` over JSON field names:
  - String fields call `GeneratedSupport.applyString(...)`.
  - String collection fields call `GeneratedSupport.applyStringCollection(...)`.
  - Nested DTO fields call `rootCtx.descend(...)` then `dispatcher.dispatchNested(...)`.
  - Nested DTO collection fields call `rootCtx.descend(...)` then `GeneratedSupport.dispatchObjectCollection(...)`.
  - External-jar nested types call `dispatcher.dispatchNested(...)` directly — no local chain, the reflective continuation handles them.
  - Unknown keys flow through `GeneratedSupport.applyDefault(...)` with an empty field-level chain, so inherited route/object-level chains still apply.

See "Runtime SPI Types" below for the interfaces and helpers this generated class calls into.

---

## Field Classification & DTO Participation

For a `@BODY` resource-method parameter typed `Collection<E>` or `E[]`, the element type `E` — not the collection/array type itself — is the discovery root for generation.

**Which nested DTOs get a generated processor.** A nested DTO type is included in the generated set only if its subtree carries at least one of `@Canonicalize`, `@Sanitize`, `@SkipCanonicalization`, or `@SkipSanitization` — directly on the type, on a field/component, or via a meta-annotation. A discovery-root DTO is always included regardless of local annotations, so route- and parameter-level policies still flow through the generated path. Field classification walks the superclass chain (stopping at `Object`) to collect inherited fields, mirroring `FR-CG008-005`, so they participate alongside a DTO's own fields.

**External-jar limit.** A field or record component whose declared type is not in the current compilation unit (an external-jar type) is not scanned for nested annotations; the generated processor calls `dispatcher.dispatchNested(...)` for that field instead, routing it to the reflective continuation (see "Codegen↔Reflection Handoff" below).

**Array fields classify like collections.** Both shapes arrive as a JSON array carrying exactly one element schema, so a `NestedDto[]` field is treated as `COLLECTION_OF_DTO` and a `String[]` field as `COLLECTION_OF_STRINGS` — elements are processed at the component type, exactly as for `Collection<E>`, matching the reflective baseline. An array whose component is itself an array (`String[][]`) carries no element schema and stays `OTHER`, keeping the inherited-chain path.

**Context parameters are never request bodies.** A resource method parameter annotated `@Context`, or one whose declared type is assignable to `dev.vertique.core.context.ContextValue`, is excluded from discovery — these are auto-classified as `CONTEXT` by the runtime regardless of whether `@Context` is present, and are never treated as request-body roots.

**`Optional<T>` is transparent for classification.** The intermediate wire value of an `Optional<T>` field is the unwrapped `T`, so field classification strips `java.util.Optional` layers before deciding the field kind:

| Declared field type | Classified as |
|---------------------|---------------|
| `Optional<String>` | `STRING` |
| `Optional<NestedDto>` | `NESTED_DTO` (nested type `NestedDto` — so its own chains are generated too) |
| `Collection<Optional<String>>` | `COLLECTION_OF_STRINGS` |
| `Optional<Optional<T>>` | classified as `T` (unwrapping recurses) |
| raw `Optional`, `Optional<?>` | `OTHER` (or omitted when unannotated) |
| `OptionalInt` / `OptionalLong` / `OptionalDouble` | `OTHER` — scalar leaves, no string payload |

**Bounded type arguments are normalized to their upper bound.** Wildcard and type-variable type
arguments carry no runtime identity of their own: javac erases them to their bound and Jackson
binds the wire value against that bound, so field classification resolves the bound before deciding
the field kind:

| Declared field type | Classified as |
|---------------------|---------------|
| `Optional<? extends NestedDto>` | `NESTED_DTO` (nested type `NestedDto`) |
| `Optional<T>` where `T extends NestedDto` | `NESTED_DTO` (nested type `NestedDto`) |
| `Collection<? extends NestedDto>` | `COLLECTION_OF_DTO` (element type `NestedDto`) |
| `Collection<Optional<? extends NestedDto>>` | `COLLECTION_OF_DTO` (element type `NestedDto`) |
| `Optional<? super NestedDto>` | `OTHER` — no upper bound above `java.lang.Object`, which is what Jackson materializes |
| `Collection<?>` / `Collection<? super NestedDto>` | `OTHER` — the element normalizes to `java.lang.Object`, a scalar leaf |
| `Optional<T>` where `T extends A & B` | classified against `A` — javac erases an intersection bound to its leftmost member, and that is the type in the erased field signature Jackson binds against |

Without this normalization `Optional` would classify as a nested DTO and emit `dispatcher.dispatchNested(v, Optional.class, …)`; no `Optional_InputProcessor` exists, so the field's chain would be silently dropped and nested DTO metadata would be resolved from `Optional` rather than the wrapped type. Likewise, without bound normalization a bounded nested DTO would fall to the `OTHER` tail and never receive its own generated processor while the runtime still materialized it. This keeps the generated path aligned with the reflective `InputPolicyMetadataResolver`, which applies the same `Optional`-stripping and bound-resolution rules.

---

## Runtime SPI Types (in `vertique-input-processing`)

The following types live in `dev.vertique.input.processing` (artifact `vertique-input-processing`) as the stable public SPI. They are available at runtime regardless of whether `vertique-codegen-sanitization` is on the processor path.

### `GeneratedInputProcessor<T>`

Interface implemented by every emitted `{DTO}_InputProcessor`.

```java
public interface GeneratedInputProcessor<T> {
    Class<T> targetType();

    Object process(Object intermediate,
                   EffectiveInputPolicies policies,
                   InputLocation location,
                   ChainResolver resolver,
                   GeneratedInputProcessorDispatcher dispatcher,
                   @Nullable InputTraversalContext parent,
                   String parentPath);
}
```

`parent == null` signals that this is the top-level entry; the generated class seeds from `InputTraversalContext.fromPolicies(policies)`. A non-null `parent` means the caller has already accumulated traversal state (nested dispatch). `parentPath` is the dot-separated path prefix of the field this DTO is nested under — an empty string at the top level — and is composed into the `path` of every `InputValueContext` the processor builds.

### `ChainResolver`

Functional interface threaded through the generated call chain.

```java
@FunctionalInterface
public interface ChainResolver {
    String apply(String value,
                 List<Class<? extends Canonicalizer>> canonicalizers,
                 List<Class<? extends Sanitizer>> sanitizers,
                 InputValueContext valueContext);
}
```

Built once by the default engine (`InputObjectProcessor.createDefault(...)`) from the `Function<Class, Canonicalizer>` / `Function<Class, Sanitizer>` factories and threaded into generated `process(...)` calls.

### `InputTraversalContext`

Public final class (not a record) that carries traversal state across generated and reflective dispatch. Two `descend(...)` overloads preserve the metadata-shape for the reflective walker (package-private — the walker shares the package) and provide a public primitive-shape for generated callers (avoiding metadata allocation on the hot path).

```java
public final class InputTraversalContext {
    public static InputTraversalContext fromPolicies(EffectiveInputPolicies policies) { ... }

    // Reflective-walker overload (package-private)
    InputTraversalContext descend(InputPolicyMetadata parentMeta,
                                  @Nullable FieldPolicyMetadata fieldMeta) { ... }

    // Generated-caller overload — no metadata allocation
    public InputTraversalContext descend(
            List<Class<? extends Canonicalizer>> objectCanon,
            List<Class<? extends Sanitizer>> objectSanit,
            boolean objectSkipCanon, boolean objectSkipSanit,
            @Nullable List<Class<? extends Canonicalizer>> fieldCanon,
            @Nullable List<Class<? extends Sanitizer>> fieldSanit,
            boolean fieldSkipCanon, boolean fieldSkipSanit) { ... }

    public List<Class<? extends Canonicalizer>> inheritedCanonicalizerChain();
    public List<Class<? extends Sanitizer>> inheritedSanitizerChain();
    public boolean inheritedSkipCanonicalization();
    public boolean inheritedSkipSanitization();
}
```

### `GeneratedInputProcessorDispatcher`

Owns lookup and the reflective continuation. The default engine created by `InputObjectProcessor.createDefault(...)` constructs one dispatcher and self-bootstraps it with a `ReflectiveContinuation` that calls back into the reflective walker, preserving `InputTraversalContext` across the codegen↔reflection boundary.

```java
public final class GeneratedInputProcessorDispatcher {
    public GeneratedInputProcessorDispatcher(ReflectiveContinuation continuation);

    public <T> void register(Class<T> type, GeneratedInputProcessor<T> instance);

    public Object dispatchNested(Object intermediate, Class<?> nestedType,
                                  EffectiveInputPolicies policies, InputLocation location,
                                  ChainResolver resolver, InputTraversalContext parentCtx,
                                  String parentPath, Class<?> ownerType);

    public static GeneratedInputProcessorDispatcher withoutContinuation();

    // Deliberately NOT @FunctionalInterface — two methods
    public interface ReflectiveContinuation {
        Object continueAt(Object intermediate, Class<?> targetType,
                          InputTraversalContext ctx, InputLocation location,
                          String fieldPath, Class<?> ownerType);

        Object walkUnknown(Object intermediate, InputTraversalContext ctx,
                           InputLocation location, String fieldPath, Class<?> ownerType);
    }
}
```

Lookup checks explicit `register(...)` entries first, then a `ClassValue` cache. On cache miss it attempts `Class.forName(generatedName(type), true, type.getClassLoader())` where `generatedName` mirrors the emitter's `Identifiers.generatedClassName(...)`. Only `ClassNotFoundException` is cached as a miss — other instantiation failures (broken generated code) propagate as errors.

`dispatchNested(...)` tries the generated processor first; on miss, hands off to the `ReflectiveContinuation` with the current `InputTraversalContext`, enabling the reflective→generated handoff direction (a nested type that was added to the generated set after the parent type was compiled reflectively). `withoutContinuation()` builds a dispatcher whose continuation throws — useful in tests to prove no reflective fallback occurs — and `register(...)` supports explicit registration where classloader lookup cannot see the generated class.

### `GeneratedSupport`

Public class with public static helpers imported via `import static` in generated code. Public visibility is required because generated processors live in application DTO packages (e.g., `com.example.dto`), not `dev.vertique.input.processing`.

| Method | Description |
|--------|-------------|
| `applyString(...)` | Apply canonicalizer+sanitizer chain to a single string value |
| `applyStringCollection(...)` | Map `applyString` over a `Collection` or passthrough non-collection |
| `dispatchObjectCollection(...)` | Map `dispatcher.dispatchNested(...)` over a `Collection` of structured objects |
| `applyDefault(...)` | Default-arm handler for unknown keys and annotated `OTHER`-kind fields: composes inherited + object + field chains for strings, resumes the reflective walker (`walkUnknown`) for nested maps/lists, passes other scalars through |
| `childPath(String, String)` | Build `parent.field` dotted path string for `InputValueContext` |

`GeneratedSupport` is documented as a **stable SPI**: its helper signatures must not change in a way that breaks already-emitted `_InputProcessor` bytecode without a major version bump of `vertique-input-processing`.

---

## Scalar-Root Filter

The following types are excluded from the discovery root set and never receive a generated processor:

- `String`, `CharSequence`
- `boolean`, `byte`, `short`, `int`, `long`, `float`, `double`, `char` (and their boxed wrappers)
- `Number`, `BigDecimal`, `BigInteger`
- `Character`
- Any `java.time.*` type
- `UUID`
- Any `Enum` subtype

If a `@BODY` method parameter is one of the above types (or a `Collection<E>` / `E[]` whose element type resolves to one of the above), no processor is emitted for it. The scalar-type processing that happens at the route level (via `EffectiveInputPolicies`) is unaffected.

---

## Codegen↔Reflection Handoff

The dispatcher's `ReflectiveContinuation` preserves `InputTraversalContext` across both handoff directions:

- **Generated→reflective**: when `dispatchNested(...)` finds no generated processor for a nested type, it calls `continuation.continueAt(...)` with the current `InputTraversalContext`. The reflective walker resumes from that context, correctly inheriting accumulated chain state.
- **Reflective→generated**: `DefaultInputObjectProcessor.processNestedMap(...)` and `processNestedList(...)` route through `dispatcher.dispatchNested(...)`, so a generated processor at any depth is picked up even when the top-level type is handled reflectively.

This means adding `vertique-codegen-sanitization` to a project is always additive — types in the current compilation unit gain generated processors while external-jar types continue to use the reflective fallback, and both paths agree on `InputTraversalContext` state.

---

## Conflict Diagnostics

The processor emits `ERROR` when mutually exclusive annotations appear on the same element:

| Conflict | Diagnostic |
|----------|------------|
| `@Canonicalize` + `@SkipCanonicalization` on same field/type | `Conflicting canonicalization annotations on {element}: @Canonicalize and @SkipCanonicalization cannot be combined` |
| `@Sanitize` + `@SkipSanitization` on same field/type | `Conflicting sanitization annotations on {element}: @Sanitize and @SkipSanitization cannot be combined` |

---

## Enabling the Processor

Applications inheriting `vertique-app-parent` declare `vertique-sanitization` and the relevant
REST runtime capabilities, then receive the complete processor facade automatically. Custom-parent
applications import `vertique-bom` and configure only the versionless Dagger and
`vertique-codegen-all` processor paths. The facade supplies `vertique-codegen-core` transitively.
See `docs/packaging.md`.

No `@Component` changes are required. The dispatcher self-bootstraps inside the default engine created by `InputObjectProcessor.createDefault(...)`; the `SanitizationModule` binding remains unchanged.

---

## Module Dagger Bindings

None. `vertique-codegen-sanitization` is a compile-time annotation processor with no runtime Dagger module. The runtime dispatch path is activated solely by the presence of generated `{DTO}_InputProcessor` classes on the classpath, discovered via `Class.forName` by `GeneratedInputProcessorDispatcher`.

---

## Dependencies

| Artifact | Scope | Purpose |
|----------|-------|---------|
| `vertique-codegen-core` | compile | `CodegenContext`, `TypeResolver`, `AnnotationMirrors`, `Diagnostics`, `Identifiers` |
| `vertique-input-processing` | compile | Runtime SPI the emitted source references (`GeneratedInputProcessor`, `GeneratedSupport`, `GeneratedInputProcessorDispatcher`, `ChainResolver`, `EffectiveInputPolicies`, `InputTraversalContext`) |
| `vertique-rest-core` | compile | REST-rooted discovery: `RestBodyDiscovery` uses `RestContextTypes` and the `RequestPreconditions`/`RequestParams` FQNs to classify resource-method parameters |
| `com.palantir.javapoet:javapoet` | compile | Source generation (not on runtime classpath) |

Test-only dependencies: `vertique-codegen-test` (compilation harness), `vertique-sanitization` (built-in canonicalizers/sanitizers for fixtures), `jakarta.ws.rs-api` (resource fixtures).

---

## Known Gaps

- **Latency-reduction target not yet met.** NFR-CG008-001 targets a ≥3× latency reduction versus the reflective path; current benchmarks show roughly a 5% improvement. The per-field and per-type chain constants are resolved once at class-load time (see "Generated `{DTO}_InputProcessor`" above), but they are not yet composed into a single chain function — `GeneratedSupport.applyString(...)` still receives the raw `List<Class<...>>` on every call.
