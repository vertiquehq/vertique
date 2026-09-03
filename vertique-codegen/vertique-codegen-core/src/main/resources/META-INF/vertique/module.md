<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# codegen-core

> **Status:** Beta

## Overview

`vertique-codegen-core` is a compile-time APT helper library that provides shared infrastructure for all annotation processors in the Vertique codegen family. It wraps `javax.annotation.processing` with high-level utilities for type resolution, annotation mirror access, diagnostic formatting, and JavaPoet-backed Dagger module generation.

The library has no runtime footprint: it contains no `Processor` registration, no `META-INF/services` entry, and is never placed on a runtime classpath. Each downstream feature module (`vertique-codegen-<feature>`) depends on it at compile scope and registers its own `Processor`. Applications inherit `vertique-app-parent` and declare only runtime capabilities; custom-parent applications use the BOM plus `vertique-codegen-all` facade recipe in `docs/packaging.md`. This helper arrives transitively through the facade.

---

## Key Classes

### CodegenContext

Central context object wrapping `ProcessingEnvironment`. Create one instance per processor in `init()` and share it across rounds.

Constructor: `CodegenContext(ProcessingEnvironment env)`

Key methods:

| Method | Description |
|--------|-------------|
| `elements()` | Returns `Elements` from the wrapped environment |
| `types()` | Returns `Types` from the wrapped environment |
| `filer()` | Returns `Filer` for source/resource file creation |
| `messager()` | Returns `Messager` for emitting diagnostics |
| `env()` | Returns the raw `ProcessingEnvironment` |
| `typeResolver()` | Lazily-initialized `TypeResolver` instance |
| `annotations()` | Lazily-initialized `AnnotationMirrors` instance |
| `diagnostics()` | Lazily-initialized `Diagnostics` instance |
| `isRecord(TypeElement)` | True when `element.getKind() == ElementKind.RECORD` |
| `injectConstructor(TypeElement)` | Returns the `@Inject`-annotated constructor; tolerates both `jakarta.inject.Inject` and `javax.inject.Inject` |
| `unwrapFuture(TypeMirror)` | If the type is `io.vertx.core.Future<X>`, returns `X`; otherwise returns the type unchanged |
| `outputPackage(TypeElement)` | Returns the processor option `-Avertique.codegen.package` if set, else the origin element's package |

The constant `CodegenContext.OPTION_OUTPUT_PACKAGE` (`"vertique.codegen.package"`) is the processor option key for the package override.

```java
public class MyProcessor extends AbstractProcessor {
    private CodegenContext ctx;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        ctx = new CodegenContext(env);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (TypeElement type : round.getElementsAnnotatedWith(MyAnnotation.class)) {
            String pkg = ctx.outputPackage(type);
            // emit generated source into pkg
        }
        return false;
    }
}
```

---

### TypeResolver

Stateless supertype-walking helper bound to a `Types`/`Elements` pair. Mirrors the runtime `core.util.TypeResolver` BFS algorithm, but operates on `javax.lang.model.type.TypeMirror` instead of runtime reflection.

Obtain via `CodegenContext.typeResolver()`.

| Method | Description |
|--------|-------------|
| `directSupertypes(TypeMirror)` | Null-safe delegate to `Types.directSupertypes` |
| `allSupertypes(TypeMirror)` | BFS over direct supertypes, deduplicated by erasure name |
| `resolveTypeArgument(TypeMirror subject, TypeElement targetInterface, int index)` | Walks the hierarchy to find a parameterized supertype whose erasure matches `targetInterface`, returns the type argument at `index` |
| `isAssignable(TypeMirror sub, Class<?> superClass)` | Erasure-aware assignability check |

---

### AnnotationMirrors

Convenience helpers that reduce the verbosity of the JDK's annotation mirror API.

Obtain via `CodegenContext.annotations()`.

| Method | Description |
|--------|-------------|
| `find(Element, Class<? extends Annotation>)` | Returns the matching `AnnotationMirror`, if present |
| `attribute(AnnotationMirror, String name, Class<T>)` | Pulls a single annotation member, applying the correct `AnnotationValue` visitor |
| `attributeArray(AnnotationMirror, String name)` | Returns a list of annotation values for an array-typed member |
| `attributeClass(AnnotationMirror, String name)` | Handles the `MirroredTypeException` pattern for `Class`-typed members |

---

### Diagnostics

Combines instance methods bound to a `Messager` with static factory methods for consistent message wording. The static formatters form an externally-stable contract — downstream processors must use them so error messages stay consistent across the framework's codegen modules.

Obtain via `CodegenContext.diagnostics()`.

**Instance methods:**

| Method | Description |
|--------|-------------|
| `error(Element, String, Object...)` | Emits `Diagnostic.Kind.ERROR` attributed to the given element |
| `warning(Element, String, Object...)` | Emits `Diagnostic.Kind.WARNING` |
| `mandatoryWarning(Element, String, Object...)` | Emits `Diagnostic.Kind.MANDATORY_WARNING`, which survives `-nowarn` and `-Xlint:none`. Use when the warning is the only signal that generated output was reduced — an ordinary warning makes such a case silent under flags common in large reactors |
| `note(Element, String, Object...)` | Emits `Diagnostic.Kind.NOTE` |

All instance methods accept a `String.format`-style format string.

**Static message formatters (externally-stable contract):**

| Method | Output template |
|--------|----------------|
| `mustReturnFuture(String context)` | `"{context} must return Future<T> or Future<Void>"` |
| `duplicateOperation(String opName)` | `"Duplicate operation name '{opName}' in contract"` |
| `unsupportedAnnotation(String fqn)` | `"@{fqn} is not supported in this position"` |
| `expectedRecord(String typeFqn)` | `"{typeFqn} must be a record"` |

---

### TypeVisibility

Answers whether generated source placed in one package may name a user type declared in another.

```java
static boolean isReferenceableFrom(TypeElement type, String fromPackage)
```

An aggregate emitter writes one `public` type into a package derived from all its origins, then references each origin by name. Consult this before emitting such a reference: a reference the generated package cannot legally make produces source that does not compile, and because javac compiles generated sources in the same task, the application's build breaks merely by putting the processor on `annotationProcessorPaths` — whether or not the generated module is installed in a `@Component`. Emitters skip what they cannot name (and say so via `Diagnostics.mandatoryWarning`) rather than emitting it.

The rules, each matching what javac accepts:

| Case | Referenceable |
|---|---|
| Same package, any access level except `private` | yes |
| Same package, `private` nested type (or nested inside one) | no — a `private` nested type is in scope only inside its enclosing class body, and generated source is a separate compilation unit |
| Other package, type and every enclosing type `public` | yes |
| Other package, type or any enclosing type not `public` | no |
| Type in the unnamed package, generated source in a named package | no — it can neither be imported nor named by simple name |
| Type in a named package, generated source in the unnamed package | yes — the restriction is not symmetric |

The type's own package is derived from its enclosing elements rather than passed in, so a caller cannot silently supply the wrong one.

---

### SelectorPathValidator

Compile-time validator for selector paths used by annotation processors. Construct one per
processor with a diagnostic family prefix, then call `validate` for the annotated method and its
selector-path array. It reports every invalid path through `CodegenContext.diagnostics()` and
returns `true` only when all paths are valid.

```java
SelectorPathValidator validator = new SelectorPathValidator(ctx, "cache");
boolean valid = validator.validate(method, new String[] {"user.address.postalCode"});
```

The selector grammar is bounded and resolves against the method being processed:

- A path is at most 256 UTF-16 code units and contains at most 8 dot-separated segments,
  including its root parameter.
- The root is either a decimal parameter position (`"0"`) or a declared parameter name.
- Every later segment must be a valid Java identifier. On a record, a bare segment may resolve to
  a public zero-argument record-component accessor; otherwise the segment must resolve to a public,
  zero-argument, non-static `getX()` or `isX()` accessor.
- The final type must be a supported scalar: any primitive except `void`; `String`, `Character`,
  `Boolean`, `Byte`, `Short`, `Integer`, `Long`, `Float`, or `Double`; `BigInteger` or
  `BigDecimal`; `UUID`; an enum; or `Instant`, `LocalDate`, `LocalDateTime`, `OffsetDateTime`,
  or `ZonedDateTime`.

Blank paths, invalid identifiers, missing parameters or accessors, excessive depth, and
non-scalar terminal types produce family-prefixed compile-time diagnostics. The public bounds are
`MAX_PATH_LENGTH` (`256`) and `MAX_SEGMENTS` (`8`).

---

### ProxyabilityValidator

Compile-time validator for methods that an annotation processor will proxy. Construct one per
processor with a diagnostic family prefix and call `validate` for each annotated method.

```java
ProxyabilityValidator validator = new ProxyabilityValidator(ctx, "cacheable");
boolean valid = validator.validate(method);
```

Validation requires a public, non-final enclosing class with exactly one constructor annotated with
`jakarta.inject.Inject` or `javax.inject.Inject`. The method must be an instance method that can be
overridden: final, private, static, and abstract methods are rejected. Each failure is reported as
a family-prefixed compile-time diagnostic. Enclosing-class checks are memoized per class, so
co-located annotations do not repeat the same class-level diagnostics; method-level checks still
apply to each method.

---

### dagger.DaggerModuleWriter

Thin JavaPoet wrapper (Palantir fork) for the most common Dagger emit patterns. Downstream processors use this to generate `@Module` classes without duplicating JavaPoet boilerplate.

```java
JavaFile module = DaggerModuleWriter.named(ClassName.get(pkg, "GeneratedServicesModule"))
    .addIntoSetProvides(servicesQualifier, objectType, "userService", userServiceImplClass)
    .build();
module.writeTo(ctx.filer());
```

| Method | Description |
|--------|-------------|
| `static named(ClassName)` | Creates a writer for an abstract `@Module`-annotated class |
| `concrete()` | Switches the generated class from `abstract` to concrete |
| `addIntoSetProvides(ClassName qualifier, ClassName type, String methodName, ClassName implType)` | Emits a `@Provides @IntoSet [Qualifier] Type method(Impl impl)` static method |
| `addElementsIntoSetProvides(ClassName qualifier, ClassName setType, String methodName, CodeBlock body, ParameterSpec... deps)` | Emits a `@Provides @ElementsIntoSet [Qualifier] Set<Type> method(...)` static method from a `CodeBlock` body; used by `GeneratedJaxRsResourcesModuleEmitter` for the uniform `@ElementsIntoSet` binding shape |
| `addStaticFinalField(TypeName fieldType, String fieldName, CodeBlock initializer)` | Emits a `static final` field with the given type, name, and initializer; used by `ContributorEmitter` for per-impl `PropertyCondition[]` constants |
| `addSingletonProvides(ClassName type, String methodName, CodeBlock body, ParameterSpec... deps)` | Emits a `@Provides @Singleton` static method from a `CodeBlock` body |
| `addBindsOptionalOf(ClassName type)` | Emits an abstract `@BindsOptionalOf Type type()` declaration |
| `build()` | Returns a `JavaFile` ready for `Filer.createSourceFile()` |

Dagger types (`@Module`, `@Provides`, `@IntoSet`, `@Singleton`, `@BindsOptionalOf`) are referenced as `ClassName.get("dagger", "Module")` etc. — the generated source does not add a compile dependency on Dagger beyond what the consuming module already has.

---

### support.Identifiers

Stateless utility class for deriving Java identifiers during annotation processing.

| Method | Description |
|--------|-------------|
| `generatedClassName(TypeElement origin, String suffix)` | Flattens nested types using `_` separator and appends the suffix. `Outer.Inner` + `"Module"` → `Outer_InnerModule` |
| `constantName(String camel)` | Converts `camelCase`/`PascalCase` to `SCREAMING_SNAKE_CASE`. Non-alphanumeric characters become underscores |
| `sanitize(String raw)` | Replaces characters invalid in Java identifiers with underscores; prepends `_` if the result starts with a digit; appends `_` if the result is a Java 9+ reserved keyword (e.g., `_` alone is reserved) |

---

### meta.MetadataEmitter

JavaPoet emitter that, given a method element, generates a `dev.vertique.core.codegen.MethodMetadata` implementation whose accessors return compile-time constants and never reflect at call time. The generated class carries a nested `ParameterMetadataImpl implements dev.vertique.core.codegen.ParameterMetadata` for its parameters. `MethodMetadata` and `ParameterMetadata` live in `vertique-core`, which is **not** a compile dependency of this module, so they are referenced by raw `ClassName` rather than imported.

| Method | Description |
|--------|-------------|
| `emitMethodMetadata(ExecutableElement, ClassName, Types)` | Emits a `MethodMetadata` implementation with no materialized annotation literals (`findAnnotation`/`hasAnnotation` resolve nothing) |
| `methodMetadataType(ExecutableElement, ClassName, Types, List<AnnotationLiteralRef>, List<List<AnnotationLiteralRef>>)` | Builds the `MethodMetadata`-implementing `TypeSpec.Builder` with method-level **and** parameter-level annotation literals baked in, without top-level modifiers (so the caller can emit it top-level or nested) |

Both the method-level and parameter-level `findAnnotation`/`hasAnnotation` surfaces are reflection-free and literal-backed: for each runtime-retained method annotation the emitter bakes a `static final <Ann>` literal constant (`ANNOTATION_<i>`) and resolves `findAnnotation` by a `type == <Ann>.class` match; for each parameter's runtime-retained annotations it bakes `PARAM_<p>_ANNOTATION_<i>` literal constants and passes them to the nested `ParameterMetadataImpl`, whose `findAnnotation` matches the looked-up `type` against each literal's `annotationType()` — never `Method.getAnnotation`/`Parameter.getAnnotation`. This backs `ParameterMetadata.findAnnotation`/`hasAnnotation` on the codegen path. Generated method metadata emits `genericReturnType()` as a reflection-free `Type` graph, including parameterized, wildcard, and generic-array return shapes. `asMethod()` and parameter `genericType()` remain stubbed and throw `UnsupportedOperationException`.

The caller owns the generator namespace when materializing each `AnnotationLiteralRef`. `AnnotationLiteralEmitter.literalClassName(...)` and `emit(...)` require that namespace and generate `<Ann>$<Namespace>Literal`; there is no shared literal suffix or unnamespaced overload. `AopProxyEmitter` uses `Aop` and the JAX-RS emitters use `JaxRs`; each caller deduplicates its own generated FQNs before writing them. The dedicated `meta.AnnotationLiteralEmitter` entry below is authoritative for supported member kinds, exact floating-point representation, equality/hash semantics, defensive array behavior, and the retained JAX-RS compatibility hook.

### meta.AnnotationLiteralEmitter

Generates annotation implementations whose accessors return compile-time constants and whose `equals`/`hashCode` follow the `java.lang.annotation.Annotation` contract. All legal annotation member kinds are supported: the eight primitive kinds (`boolean`, `byte`, `short`, `int`, `long`, `char`, `float`, and `double`), `String`, `Class`, enum constants, nested annotations, and arrays of those kinds. `char` members use four-hex-digit `\uXXXX` literals. `float` and `double` members use `Float.intBitsToFloat` and `Double.longBitsToDouble` with the exact raw bit pattern read during processing, so NaN, infinities, and negative zero round-trip. Equality uses `Float.compare`/`Double.compare` for floating-point members and boxed `Float`/`Double` hash codes; other primitive members retain direct equality and boxed hash semantics. Arrays use `Arrays.equals` and `Arrays.hashCode`.

`firstUnsupportedAttribute(TypeElement)` is retained for signature compatibility with `vertique-codegen-jaxrs` and returns `Optional.empty()` for every annotation type. No legal annotation member kind is rejected by this emitter.

---

### `@ConditionalOnProperty` / `@ConditionalOnProperties`

Compile-time annotations, in the `dev.vertique.codegen` package, consumed by annotation processors in `vertique-codegen-services` and `vertique-codegen-jaxrs`. **SOURCE retention** — they do not appear on the runtime classpath.

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Repeatable(ConditionalOnProperties.class)
@Documented
public @interface ConditionalOnProperty {
    String name();                     // dot-delimited config property path
    String havingValue() default "true"; // expected scalar string value
    boolean matchIfMissing() default false; // treat MISSING path as a match
}

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface ConditionalOnProperties {
    ConditionalOnProperty[] value();
}
```

**Rules:**
- Multiple `@ConditionalOnProperty` annotations on the same type are ANDed.
- Only consumed by codegen processors; has no effect on manually-written Dagger bindings.
- Placing `@ConditionalOnProperty` on a type that is also annotated `@NoAutoWire` triggers a compile-time WARNING from `ImplCandidateScanner` — the conditional has no effect on opted-out types.

### Generic Dagger registration annotations

`@RegisterAs` and `@RegisterIntoSet` are source-retained, repeatable type annotations consumed by
`vertique-codegen-dagger`:

```java
@RegisterAs(MetricsObserver.class)
final class DefaultMetricsObserver implements MetricsObserver {
    @Inject
    DefaultMetricsObserver() {}
}

@RegisterIntoSet(EventInterceptor.class)
@RegisterIntoSet(RequestInterceptor.class)
final class LoggingInterceptor implements EventInterceptor, RequestInterceptor {
    @Inject
    LoggingInterceptor() {}
}
```

Each declaration carries one `Class<?> value()` target. The annotated type must be concrete, have
exactly one `jakarta.inject.Inject` or `javax.inject.Inject` constructor, and be assignable to the
target. `@NoAutoWire` suppresses every registration declaration on the same type. The processor
emits `GeneratedRegistrationsModule` in the package resolved from the annotated origins; the module
must be included explicitly by the owning Dagger component or aggregate module.

---

## Extension Points

### Static Diagnostics Formatters

The four static methods on `Diagnostics` (`mustReturnFuture`, `duplicateOperation`, `unsupportedAnnotation`, `expectedRecord`) are an externally-stable string contract. Downstream codegen processors must call these methods rather than inline the strings. Any change to their string values is a breaking change across the codegen modules.

---

## Module Dagger Bindings

None. This is a compile-time-only helper library with no Dagger `@Module`.

---

## Dependencies

| Dependency | Scope | Purpose |
|------------|-------|---------|
| `com.palantir.javapoet:javapoet:0.14.0` | compile | Java source generation with record and sealed-class support |
| `com.google.dagger:dagger` (annotations only) | compile | `ClassName` references for `@Module`, `@Provides`, etc. in generated output |
| JDK annotation processing API (`javax.annotation.processing`, `javax.lang.model`) | provided (JDK) | APT runtime |
