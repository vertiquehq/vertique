<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Codegen Dagger Auto-Wiring Module

> **Status:** Beta

## Overview

`vertique-codegen-dagger` is an annotation processor (`AutoWireProcessor`) that eliminates the `@Provides @IntoSet` ceremony users currently write for every `@Path` resource, `@RestClient` interface, Kafka consumer, and `DelayedJobExecutor`. The information needed to wire each binding already lives on the type itself; the processor discovers these types at compile time and emits a `Generated{Qualifier}Module` for each active qualifier.

Two framework qualifiers use the multibinding emit shape; one uses a direct singleton binding:

| Marker | Discovery | Emitted binding |
|--------|-----------|-----------------|
| `@KafkaListener` / `@KafkaSource` | annotation-rooted | `@Provides @IntoSet @KafkaConsumers Object` |
| `DelayedJobExecutor<P,C>` impls | root-element scan | `@Provides @IntoSet @DelayedJobs Object` |
| `@RestClient` interfaces | annotation-rooted | `@Provides @Singleton {Interface} provideXxx(RestClientFactory)` |

**`@Path` (JAX-RS) resource binding is not owned by this processor.** `@Path`-resource DI binding (`@Provides @IntoSet @JaxRsResources Object`) is emitted by `vertique-codegen-jaxrs`'s `JaxRsPipelineProcessor`, which also handles discovery, validation, and runtime optimization for JAX-RS resources. See `dev.vertique:vertique-codegen-jaxrs`.

`@ServiceContract` implementation wiring is owned by `vertique-codegen-services`, not by this processor.

The processor uses `@SupportedAnnotationTypes("*")` so it runs every round regardless of which annotations are present. It always returns `false` from `process()` so Dagger, Lombok, and other processors see the same elements unmodified.

---

## Package Layout

| Package | Contents |
|---------|----------|
| `dev.vertique.codegen.dagger.processor` | `AutoWireProcessor`, `Qualifier` (enum), `Binding` (record) |
| `dev.vertique.codegen.dagger.processor.collect` | `AnnotationRootedCollector` (shared base), `RestClientCollector`, `KafkaConsumerCollector`, `DelayedJobExecutorScanner` |
| `dev.vertique.codegen.dagger.processor.emit` | `MultibindingModuleEmitter`, `RestClientModuleEmitter` |
| `dev.vertique.codegen.dagger.processor.support` | `Filters`, `FilerWriter` |

---

## Key Classes

### `AutoWireProcessor`

`AbstractProcessor` extension registered via `META-INF/services/javax.annotation.processing.Processor`. Initialized with a `CodegenContext` in `init()`. Holds an `EnumMap<Qualifier, List<Binding>> accumulator` that survives across rounds.

`process(annotations, round)` runs in two phases:

1. **Annotation-rooted collectors** — `RestClientCollector`, `KafkaConsumerCollector` each pull candidates from `round.getElementsAnnotatedWith(MarkerAnnotation.class)`.
2. **Root-element scan** — a single pass over `round.getRootElements()` feeds `DelayedJobExecutorScanner`.

`@Path`-resource collection is not part of this processor. JAX-RS resource discovery and DI binding emission are owned by `JaxRsPipelineProcessor` in `vertique-codegen-jaxrs`.

In the first non-final round the processor calls the appropriate emitter for each qualifier with non-empty bindings, then flips an `emitted` flag so subsequent rounds (e.g., the round triggered when Dagger writes its own generated sources) skip collection and emission. See "Pitfalls" below for the multi-round consequence — types first introduced by another processor's generated source in a later round are NOT auto-wired.

### `Qualifier`

Public enum mapping each qualifier to its FQN and the simple name of the module it generates:

```
KAFKA_CONSUMERS   → dev.vertique.kafka.KafkaConsumers       → GeneratedKafkaConsumersModule
DELAYED_JOBS      → dev.vertique.job.delayed.dagger.DelayedJobs  → GeneratedDelayedJobsModule
REST_CLIENTS      → (no qualifier — direct binding)          → GeneratedRestClientsModule
```

`JAX_RS_RESOURCES` is not a qualifier in this enum; `GeneratedJaxRsResourcesModule` is emitted by `vertique-codegen-jaxrs`'s `JaxRsPipelineProcessor`. `@ServiceContract` impls are **not** a qualifier in this enum; that wiring is owned by `vertique-codegen-services`, which generates `GeneratedServicesModule` with `@Provides @IntoSet ServiceContractContributor` bindings.

### `Binding`

Public record with two components: `TypeElement origin` (the discovered type — used for package resolution and diagnostics) and `ClassName implType` (the type to provide or contribute; for `@RestClient` this is the interface name). Passed from collectors to emitters.

### `PackageResolver` (in `vertique-codegen-core`)

`PackageResolver` lives in `vertique-codegen-core`. `AutoWireProcessor` and `JaxRsPipelineProcessor` both consume it:

1. If `-Avertique.codegen.package` is set, use it verbatim.
2. Otherwise, compute the longest common prefix (LCP) of `Elements.getPackageOf(binding.origin())` across all bindings in the round.
3. If the LCP is empty (types in wholly disjoint packages), emit `Diagnostics.error` and request the user set the override option.

Single-binding rounds return that binding's own package.

### `AnnotationRootedCollector`

Shared base for `RestClientCollector` and `KafkaConsumerCollector`. Filters out:

- Non-`TypeElement` elements (skip annotated methods).
- Types annotated `@NoAutoWire`.
- Abstract classes.

Validates `@Inject` constructor count (skipped for interfaces such as `@RestClient`):

- Zero `@Inject` constructors → `Diagnostics.note(...)`: type is skipped without error.
- Multiple `@Inject` constructors → `Diagnostics.error(...)` using `Diagnostics.duplicateInjectConstructor(typeFqn)`.

### `KafkaConsumerCollector`

Handles both `@KafkaListener` and `@KafkaSource` in a single pass (both map to `Qualifier.KAFKA_CONSUMERS`). Skips `@KafkaListener` interfaces — Model 3 routing interfaces use a `Class<?>` literal contribution that must remain manual.

### `RestClientCollector`

Collects interfaces annotated `@RestClient`. No `@Inject` constructor check (interfaces have none). Routes to `RestClientModuleEmitter` rather than `MultibindingModuleEmitter`.

### `DelayedJobExecutorScanner`

Root-element scan: loads `dev.vertique.job.delayed.DelayedJobExecutor` via `Elements.getTypeElement(...)`. If the class is unavailable in this compilation (no `job-delayed` dependency), the scanner is a no-op. Checks assignability via erasure FQN comparison. Only concrete classes pass.

### `MultibindingModuleEmitter`

Emits `Generated{Qualifier}Module` for the two `@IntoSet` qualifiers (`@KafkaConsumers`, `@DelayedJobs`) using `DaggerModuleWriter.named(...).addIntoSetProvides(qualifierClass, Object.class, methodName, implType).build()`.

Each generated provider method is named after the implementation type's **simple** name, decapitalized, with `Binding` appended — `UserServiceImpl` → `userServiceImplBinding`. A leading acronym keeps its casing (`URLProvider` → `URLProviderBinding`), and a name that would otherwise be a Java keyword gets a trailing underscore.

Detects same-simple-name collisions across packages before emitting: two types with the same simple name in different packages would produce duplicate method names. The emitter calls `Diagnostics.error` rather than writing a malformed module.

### `RestClientModuleEmitter`

Emits `GeneratedRestClientsModule` using `DaggerModuleWriter.named(...).addSingletonProvides(interfaceType, methodName, body)` where `body = factory.builder().build(Interface.class)`. One `@Provides @Singleton {Interface}` method per `@RestClient` interface found in the round.

Applies the same same-simple-name collision detection as `MultibindingModuleEmitter`.

### `@NoAutoWire` (in `vertique-codegen-core`)

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface NoAutoWire {}
```

Place on any marker-annotated type to exclude it from auto-wiring. `@Retention(SOURCE)` means the annotation has no runtime presence. Lives in `vertique-codegen-core` (not the dagger leaf) because users must be able to import it from their source code, and `vertique-codegen-dagger` is an `<annotationProcessorPaths>` entry only — its classes are not on the source compile classpath.

**When do you need `vertique-codegen-core` as a dependency?** Only if you write `@NoAutoWire` in your source code. In that case, add it with `<scope>provided</scope>` — the annotation is dropped after compilation and must not reach the runtime classpath.

---

## Extension Points

### `@NoAutoWire` opt-out

Annotate any marker-bearing type to prevent auto-wiring. `JaxRsPipelineProcessor` in `vertique-codegen-jaxrs` also honors `@NoAutoWire` for JAX-RS resources (suppresses Dagger binding emission; validation and descriptor emission still apply).

```java
@Path("/admin/users")
@NoAutoWire                // keep manual @Provides @IntoSet @JaxRsResources binding
public class AdminUserResource { ... }
```

The processor skips the type silently; any existing manual binding continues to work.

### `-Avertique.codegen.autoWire=false` — global disable

Passes through to `AutoWireProcessor` via `<compilerArg>`. Suppresses all generation for debugging or gradual migration:

```xml
<plugin>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <compilerArgs>
      <arg>-Avertique.codegen.autoWire=false</arg>
    </compilerArgs>
  </configuration>
</plugin>
```

### `-Avertique.codegen.package=...` — output package override

Overrides `PackageResolver`'s LCP computation. Required when your annotated types live in disjoint packages (the LCP would be empty, causing a compile error). Pin to a stable package to avoid import churn if your package structure changes:

```xml
<compilerArgs>
  <arg>-Avertique.codegen.package=dev.myapp.generated</arg>
</compilerArgs>
```

---

## Enabling Auto-Wiring

### Step 1 — use the application processor boundary

Applications inheriting `vertique-app-parent` declare only the runtime capabilities they use; the
parent supplies Dagger and `vertique-codegen-all`. Custom-parent applications import
`vertique-bom` and configure those same two versionless processor paths. Processor leaves are
ownership units, not application setup choices. See `docs/packaging.md`.

### Step 2 — add the generated module to `@Component`

Each generated module must be listed explicitly in the `@Component` it participates in:

```java
@Component(modules = {
    AppModule.class,
    RestModule.class,
    DispatchModule.class,
    GeneratedJaxRsResourcesModule.class,   // generated by the JAX-RS processor
    GeneratedDelayedJobsModule.class,      // generated by the auto-wire processor
})
interface AppComponent { ... }
```

`GeneratedJaxRsResourcesModule` is emitted by `JaxRsPipelineProcessor` (`vertique-codegen-jaxrs`), not by `AutoWireProcessor`.

One line per qualifier is the explicit participation signal. Removing the line opts out of that qualifier's auto-wiring without touching `annotationProcessorPaths`.

For `@ServiceContract` impl wiring, add `GeneratedServicesModule.class` — generated by `vertique-codegen-services`. See `dev.vertique:vertique-codegen-services`.

### Step 3 — delete the corresponding manual bindings

For each type now auto-wired, remove the manual `@Provides @IntoSet @{Qualifier}` method (or `@Provides @Singleton {Interface}` for REST clients). See "Migrating from manual modules" below.

---

## Migrating from Manual Modules

When the processor is added to an existing project, manual and auto-wired bindings coexist correctly **only when they target different impl types**. Two bindings of the same impl type produce a duplicate-set-contribution error (or for `@RestClient`, a hard Dagger duplicate-binding error).

The processor does not detect existing manual bindings — that would require resolving every `@Module` class in the round to inspect its `@Provides` methods, which is fragile. Instead the migration contract shifts responsibility to the user:

For each marker-annotated type, do exactly one of:

1. **Remove the manual `@Provides` binding** for that type (the auto-wired binding takes over), **or**
2. **Annotate the type with `@NoAutoWire`** (the existing manual binding stays canonical).

Generated bindings for *different* impl types coexist with hand-written multibinding contributions for any other types. This means a gradual migration is safe: you can auto-wire some types and keep manual bindings for others as long as no type has both.

**Example — partial migration:**

```java
// Before: all manual
@Module
public abstract class ResourceModule {
    @Provides @IntoSet @JaxRsResources
    static Object userResource(UserResource resource) { return resource; }

    @Provides @IntoSet @JaxRsResources
    static Object adminResource(AdminResource resource) { return resource; }
}
```

```java
// After: UserResource auto-wired; AdminResource kept manual with @NoAutoWire
@Path("/admin")
@NoAutoWire  // kept manual — conditional wiring in ResourceModule
public class AdminResource { ... }

// ResourceModule now only contains AdminResource's binding
// UserResource is in GeneratedJaxRsResourcesModule
```

---

## Pitfalls

### Multi-round emission limitation

APT processes types from the current compilation unit only. Types introduced by *other* processors in later rounds (e.g., a processor that generates a `@Path`-annotated class from another annotation) are not visible to `AutoWireProcessor`. Only types present in the original source files are auto-wired.

### `@SupportedAnnotationTypes("*")` and warning suppression

Because the processor declares `@SupportedAnnotationTypes("*")`, the JVM may emit a note: "Processor AutoWireProcessor matches all annotations, consider restricting this." This note is informational and does not affect compilation. It can be suppressed with `-Xlint:-processing` in the compiler args if it clutters build output.

### Lombok is an explicit opt-in

The public parent does not activate Lombok. Applications that use it declare the provided
dependency and append the Lombok processor path with `combine.children="append"` as documented in
`docs/packaging.md`.

### Generated module import location depends on `PackageResolver`

By default, the generated module class is in the LCP package of the annotated types found in the round. If annotated types move to a different package (e.g., a module reorganization), the generated module's fully qualified name changes and any `import` statement in `AppComponent.java` must be updated. Pin via `-Avertique.codegen.package=dev.myapp.generated` if you need a stable import location.

### Model 3 Kafka routers are NOT auto-wired

`@KafkaListener` interfaces (Model 3 routing interfaces) are skipped by `KafkaConsumerCollector`. Their contribution to `@KafkaConsumers` is a `Class<?>` literal, not an instance, and is managed manually. Only `@KafkaListener`-annotated **classes** (Model 4) and `@KafkaSource`-annotated classes (Model 1) are auto-wired.

---

## Module Dagger Bindings

None. `vertique-codegen-dagger` is a compile-time annotation processor with no runtime Dagger module — it generates modules rather than providing one itself.

---

## Dependencies

| Artifact | Scope | Purpose |
|----------|-------|---------|
| `vertique-codegen-core` | compile | `CodegenContext`, `AnnotationMirrors`, `Diagnostics`, `DaggerModuleWriter`, `PackageResolver`, `@NoAutoWire` |
| `com.palantir.javapoet:javapoet` | compile | JavaPoet `CodeBlock` patterns not covered by `DaggerModuleWriter` |

Test-only dependencies (not in the processor jar's runtime classpath): `vertique-codegen-test`, `jakarta.ws.rs-api`, `jakarta.inject-api`, `dagger`, `vertique-services`, `vertique-rest-core`, `vertique-rest-client`, `vertique-kafka-core`, `vertique-job-delayed`.
