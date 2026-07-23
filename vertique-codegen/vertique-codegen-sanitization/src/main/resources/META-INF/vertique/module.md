<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Codegen Sanitization Input Processor Module

> **Status:** Implemented (CG-008)
> **Package:** `dev.vertique.codegen.sanitization.processor`
> **Artifact:** `vertique-codegen-sanitization`
> **Depends on:** `vertique-codegen-core` (compile)

`vertique-codegen-sanitization` is an annotation processor that generates `{DTO}_InputProcessor` walker classes for REST request body DTOs whose type tree carries `@Sanitize` or `@Canonicalize` annotations. The generated walkers replace the reflective `Map`/`List` traversal in `DefaultInputObjectProcessor` with a direct field-name `switch` and pre-composed per-field chain constants, eliminating per-call metadata lookup and `descend()` allocation on the hot path.

Discovery is anchored on `@Path`-annotated resource methods in the current compilation unit. The emitted set is the **transitive closure** of participating DTO types reachable from those roots. No Dagger module is emitted — runtime registration self-populates via classloader lookup (`Class.forName`), mirroring `BeanParamAccessorRegistry`. The `@BindsOptionalOf InputObjectProcessor` wiring and the `SanitizationModule` binding are unchanged.

---

## Package Layout

| Package | Contents |
|---------|----------|
| `dev.vertique.codegen.sanitization.processor` | `SanitizationProcessor` |
| `dev.vertique.codegen.sanitization.processor.scan` | `RestBodyDiscovery`, `DtoScanner`, `AnnotationCollector`, `DtoModel`, `FieldModel` |
| `dev.vertique.codegen.sanitization.processor.emit` | `InputProcessorEmitter` |

---

## Key Classes

### `SanitizationProcessor`

`AbstractProcessor` registered via `META-INF/services/javax.annotation.processing.Processor`.

```
@SupportedAnnotationTypes("jakarta.ws.rs.Path")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
```

Lifecycle:

1. `init(env)` — instantiates `CodegenContext`, `RestBodyDiscovery`, `DtoScanner`, `AnnotationCollector`, `InputProcessorEmitter`.
2. `process(annotations, round)`:
   - `RestBodyDiscovery` collects direct body-parameter types from `@Path`-annotated resource methods in the current compilation unit; scalar roots are filtered out.
   - `DtoScanner` expands those roots into the transitive closure of participating DTO types.
   - `AnnotationCollector` resolves per-type and per-field annotation metadata for the emitted set.
   - `InputProcessorEmitter` writes one `{DTO}_InputProcessor` source file per type in the emitted set.
3. Returns `false` so Dagger, Lombok, and other processors see the same elements unmodified.

### `RestBodyDiscovery`

Server-side body classifier that mirrors `ResourceScanner.resolveParams`. Visits every `ExecutableElement` on `@Path`-annotated types in the round's root elements. For each method parameter:

- Skips if the enclosing type is not itself annotated `@Path` (no-`@Path` enclosing class → resource skipped).
- **Excludes context parameters**: any parameter annotated `@Context` is not a request body and is skipped. Any parameter whose declared type is assignable to `dev.vertique.core.context.ContextValue` is likewise excluded — these are auto-classified as `CONTEXT` by the runtime regardless of whether `@Context` is present, and are never request bodies.
- Classifies the remaining parameters as `@BODY` using the same rules as `ResourceScanner` (no `@PathParam`/`@QueryParam`/`@HeaderParam`/`@CookieParam`/`@FormParam`/`@BeanParam`/`@Context`, and type is not a `ContextValue` subtype).
- When the parameter type is `Collection<E>` or `E[]`, records the **element type** `E` as the discovery root; otherwise records the raw parameter type.

Scalar element types are filtered out before returning: `String`, `CharSequence`, primitive wrappers, `Number`, `BigDecimal`, `BigInteger`, `Character`, all `java.time.*` types, `UUID`, and `Enum` subtypes.

### `DtoScanner`

Takes the set of discovery roots from `RestBodyDiscovery` and computes the transitive closure of DTO types to emit.

**Participation rule for transitive types:** a nested DTO type participates (and is included in the emitted set) only if its subtree carries at least one of `@Canonicalize`, `@Sanitize`, `@SkipCanonicalization`, or `@SkipSanitization` — directly on the type, on a field/component, or via a meta-annotation. Discovery roots are emitted unconditionally regardless of local annotations so that route- and parameter-level policies still flow through the generated path.

Field/component types that are not in the current compilation unit (external-jar types) are tracked as `externalNestedTypes`. The emitter emits a `dispatcher.dispatchNested(...)` call for these fields so the runtime routes them to the reflective continuation, preserving `InputTraversalContext` across the codegen↔reflection boundary.

Array fields at the nested level (e.g., `NestedDto[]` as a field) are not emitted — the reflective baseline handles only `Collection<E>` field types for nested objects, and the generated path mirrors that constraint.

### `AnnotationCollector`

APT mirror of `InputPolicyMetadataResolver`. Collects per-type and per-field annotation data from `TypeElement` and `VariableElement`/`RecordComponentElement` mirrors:

- Walks the superclass chain (stopping at `Object`) to collect inherited fields (mirroring `FR-CG008-005`).
- Resolves `@Canonicalize`, `@Sanitize`, `@SkipCanonicalization`, `@SkipSanitization` via direct and meta-annotation inspection using `CodegenContext.annotationMirrors()`.
- Detects conflicting annotations on the same element (e.g., `@Canonicalize` and `@SkipCanonicalization` on the same field) and emits an `ERROR` diagnostic at compile time.
- Produces `DtoModel` / `FieldModel` carriers consumed by the emitter.

### `InputProcessorEmitter`

JavaPoet-based emitter. For each `DtoModel` in the emitted set, generates `{DTO}_InputProcessor` in the DTO type's package:

- Implements `GeneratedInputProcessor<T>`.
- Static `final` per-field chain constants (`List<Class<? extends Canonicalizer>>`, `List<Class<? extends Sanitizer>>`, `boolean` skip flags) resolved once at class-load time.
- Static `final` per-type (object-level) chain constants covering type-level annotations.
- `process(...)` body: checks `intermediate instanceof Map<?,?>`; seeds `InputTraversalContext` from `parent` or `InputTraversalContext.fromRoute(policies)`; iterates the map with a `switch(k)` over known field names.
  - String fields: calls `GeneratedSupport.applyString(...)` (imported via `import static`).
  - String collection fields: calls `GeneratedSupport.applyStringCollection(...)`.
  - Nested DTO fields: calls `rootCtx.descend(...)` then `dispatcher.dispatchNested(...)`.
  - Nested DTO collection fields: calls `rootCtx.descend(...)` then `GeneratedSupport.dispatchObjectCollection(...)`.
  - External-jar nested types: calls `dispatcher.dispatchNested(...)` directly (no `descend` with known chains — the reflective continuation handles them).
  - Unknown keys fall through to `out.put(k, v)` (passthrough).

### `DtoModel` / `FieldModel`

Immutable carriers populated by `DtoScanner` and `AnnotationCollector`, consumed by `InputProcessorEmitter`.

| Field | Type | Description |
|-------|------|-------------|
| `DtoModel.typeElement` | `TypeElement` | APT type mirror |
| `DtoModel.fields` | `List<FieldModel>` | Ordered field list (superclass fields first) |
| `DtoModel.objectLevelChains` | resolved chain lists | Type-level `@Canonicalize`/`@Sanitize` + skip flags |
| `FieldModel.name` | `String` | Java field / record component name |
| `FieldModel.kind` | `FieldKind` | `STRING`, `STRING_COLLECTION`, `NESTED_DTO`, `NESTED_DTO_COLLECTION`, `EXTERNAL_NESTED`, `PASSTHROUGH` |
| `FieldModel.canonChain` | `List<TypeMirror>` | Per-field canonicalizer classes |
| `FieldModel.sanitChain` | `List<TypeMirror>` | Per-field sanitizer classes |
| `FieldModel.skipCanon` | `boolean` | `@SkipCanonicalization` present on field |
| `FieldModel.skipSanit` | `boolean` | `@SkipSanitization` present on field |

---

## Runtime SPI Types (in `vertique-rest-core`)

The following types were added to `dev.vertique.rest.core.request` as the stable public SPI. They are available at runtime regardless of whether `vertique-codegen-sanitization` is on the processor path.

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
                   @Nullable InputTraversalContext parent);
}
```

`parent == null` signals that this is the top-level entry; the generated class seeds from `InputTraversalContext.fromRoute(policies)`. A non-null `parent` means the caller has already accumulated traversal state (nested dispatch).

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

Built once by `DefaultInputObjectProcessor` from the existing `Function<Class, Canonicalizer>` / `Function<Class, Sanitizer>` factories and threaded into generated `process(...)` calls.

### `InputTraversalContext`

Public final class (not a record). Replaces the private `TraversalContext` record that was previously internal to `DefaultInputObjectProcessor`. Two `descend(...)` overloads preserve the metadata-shape for the reflective walker and provide a primitive-shape for generated callers (avoiding `InputPolicyMetadata` allocation on the hot path).

```java
public final class InputTraversalContext {
    public static InputTraversalContext fromRoute(EffectiveInputPolicies policies) { ... }

    // Reflective-walker overload
    public InputTraversalContext descend(InputPolicyMetadata parentMeta,
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

Owns lookup and the reflective continuation. `DefaultInputObjectProcessor` constructs one dispatcher in its existing 3-arg constructor and self-bootstraps it with a `ReflectiveContinuation` lambda that calls back into the existing reflective walker, preserving `InputTraversalContext` across the codegen↔reflection boundary.

```java
public final class GeneratedInputProcessorDispatcher {
    public GeneratedInputProcessorDispatcher(ReflectiveContinuation continuation);

    public <T> Optional<GeneratedInputProcessor<T>> resolve(Class<T> type);

    public Object dispatchNested(Object intermediate, Class<?> nestedType,
                                  EffectiveInputPolicies policies, InputLocation location,
                                  ChainResolver resolver, InputTraversalContext parentCtx,
                                  String fieldPath, Class<?> ownerType);

    @FunctionalInterface
    public interface ReflectiveContinuation {
        Object continueAt(Object intermediate, Class<?> targetType,
                          InputTraversalContext ctx, InputLocation location,
                          String fieldPath, Class<?> ownerType);
    }
}
```

`resolve(type)` uses a `ClassValue` cache. On miss it attempts `Class.forName(generatedName(type), true, type.getClassLoader())` where `generatedName` mirrors the emitter's `Identifiers.generatedClassName(...)`. Only `ClassNotFoundException` is cached as `Optional.empty()` — other instantiation failures (broken generated code) propagate as errors.

`dispatchNested(...)` tries the generated processor first; on miss, hands off to the `ReflectiveContinuation` with the current `InputTraversalContext`, enabling the reflective→generated handoff direction (a nested type that was added to the generated set after the parent type was compiled reflectively).

### `GeneratedSupport`

Public class with public static helpers imported via `import static` in generated code. Public visibility is required because generated processors live in application DTO packages (e.g., `com.example.dto`), not `dev.vertique.rest.core.request`.

| Method | Description |
|--------|-------------|
| `applyString(...)` | Apply canonicalizer+sanitizer chain to a single string value |
| `applyStringCollection(...)` | Map `applyString` over a `Collection` or passthrough non-collection |
| `dispatchObjectCollection(...)` | Map `dispatcher.dispatchNested(...)` over a `Collection` of structured objects |
| `applyDefault(...)` | Passthrough for non-string, non-collection, non-nested fields |
| `childPath(String, String)` | Build `parent.field` dotted path string for `InputValueContext` |

`GeneratedSupport` is documented as a **stable SPI**. Its method signatures must not change without a major version bump.

---

## Scalar-Root Filter

The following types are excluded from the discovery root set and are never passed to `DtoScanner`:

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

`AnnotationCollector` emits `ERROR` when mutually exclusive annotations appear on the same element:

| Conflict | Diagnostic |
|----------|------------|
| `@Canonicalize` + `@SkipCanonicalization` on same field/type | `Conflicting canonicalization annotations on {element}: @Canonicalize and @SkipCanonicalization cannot be combined` |
| `@Sanitize` + `@SkipSanitization` on same field/type | `Conflicting sanitization annotations on {element}: @Sanitize and @SkipSanitization cannot be combined` |

---

## Enabling the Processor

Add `vertique-codegen-sanitization` to `annotationProcessorPaths` in the module that contains the `@Path`-annotated resources. `vertique-codegen-core` arrives transitively.

```xml
<plugin>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths combine.children="append">
      <path>
        <groupId>dev.vertique</groupId>
        <artifactId>vertique-codegen-sanitization</artifactId>
        <version>${project.version}</version>
      </path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

No `@Component` changes are required. The dispatcher self-bootstraps inside `DefaultInputObjectProcessor`'s existing 3-arg constructor; the `SanitizationModule` binding remains unchanged.

---

## Module Dagger Bindings

None. `vertique-codegen-sanitization` is a compile-time annotation processor with no runtime Dagger module. The runtime dispatch path is activated solely by the presence of generated `{DTO}_InputProcessor` classes on the classpath, discovered via `Class.forName` by `GeneratedInputProcessorDispatcher`.

---

## Dependencies

| Artifact | Scope | Purpose |
|----------|-------|---------|
| `vertique-codegen-core` | compile | `CodegenContext`, `TypeResolver`, `AnnotationMirrors`, `Diagnostics`, `Identifiers` |
| `com.squareup:javapoet` | compile | Source generation (not on runtime classpath) |

Test-only dependencies: `vertique-codegen-test`, `vertique-rest-core` (for `GeneratedInputProcessor`/`GeneratedSupport` fixture compilation).

---

## Version History

| Date | Change |
|------|--------|
| 2026-05-01 | Initial implementation (CG-008): `SanitizationProcessor` with `RestBodyDiscovery`, `DtoScanner`, `AnnotationCollector`, `InputProcessorEmitter`; runtime SPI types (`GeneratedInputProcessor`, `GeneratedInputProcessorDispatcher`, `ChainResolver`, `InputTraversalContext`, `GeneratedSupport`) added to `vertique-rest-core`; `DefaultInputObjectProcessor` self-bootstraps dispatcher and consults generated processors before reflective traversal; bidirectional codegen↔reflection handoff via `ReflectiveContinuation` |

---

## Planned Additions

- **Pre-composed per-field chain constants** — NFR-CG008-001 (≥3× latency reduction vs reflection) is not yet met; current benchmarks show ~5% improvement. Reaching the target requires the emitter to compose the canonicalizer+sanitizer function chain into a single constant at class-load time rather than passing raw `List<Class<...>>` to `GeneratedSupport.applyString(...)` on every call. Tracked as a follow-up.
- **`Elements.getOrigin` edge-case verification** — APT's `Elements.getOrigin(Element)` returns `CLASS_FILE` for types loaded from external jars. `DtoScanner` currently relies on `typeElement.getKind()` to detect struct types but has not been exercised against all CU-classification edge cases in mixed-source/jar compilation units. Verification against these cases is deferred.
- **`LinkedHashMap` allocation skip** — DTOs with no emittable fields (all fields are passthrough or the DTO has no fields) still allocate a `LinkedHashMap` output. The emitter could detect this at generation time and emit a passthrough `return intermediate` instead.

---

## Related ADRs

- ADR-0069: REST Context Resolver Chain and Single Context Source — establishes that `@Context` parameters and `ContextValue`-typed parameters are classified as `CONTEXT` at the runtime layer; `RestBodyDiscovery` mirrors this classification to ensure context parameters are never treated as request-body roots.
