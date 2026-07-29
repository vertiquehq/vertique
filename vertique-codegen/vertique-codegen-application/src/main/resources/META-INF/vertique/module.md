<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Codegen Application Module

> **Status:** Alpha
> **Package:** `dev.vertique.codegen.application`
> **Artifact:** `vertique-codegen-application`
> **Depends on:** `vertique-codegen-core` (compile), `vertique-application` (processor classpath only)

`vertique-codegen-application` is an annotation processor that generates an application's
`VertiqueComponentFactory` implementation and its `META-INF/services` SPI registration from a
single `@VertiqueApp`-annotated Dagger `@Component` interface. A standalone application using
this processor writes neither the factory class nor the SPI file.

This module has **no runtime classpath presence**. It is consumed exclusively via
`<annotationProcessorPaths>` and produces only source and resource files that are compiled into
the application's own artifact.

---

## When To Use It

This processor owns application-factory generation when:

- The application uses the standalone launcher (`vertique-launcher`) and wants to eliminate the
  hand-written `VertiqueComponentFactory` and `META-INF/services` entry.
- The `@Component` interface already extends `VertiqueApplicationComponent` and includes
  `VertxModule` directly or transitively through a component module's `@Module(includes = …)`
  graph — for example, a component naming `dev.vertique.starter.core.CoreApplicationModule`
  satisfies this because `CoreApplicationModule` includes `VertxModule`.

Applications that manage their own factory (the manual Phase-3 path) do not need this module.
Both paths satisfy the same `VertiqueComponentFactoryLoader` discovery contract, so the choice is
additive: switching to `@VertiqueApp` requires removing the hand-written factory and SPI entry.

---

## Core Concepts

### Zero-boilerplate standalone entry

The standard standalone entry sequence (described in `dev.vertique:vertique-launcher`) requires one
`VertiqueComponentFactory` registered in `META-INF/services/dev.vertique.core.VertiqueComponentFactory`.
Before this module existed, every application wrote:

```java
// Hand-written factory (no longer needed with @VertiqueApp)
public final class AppComponentVertiqueComponentFactory
        implements VertiqueComponentFactory<AppComponent> {
    @Override
    public AppComponent build(VertiqueRuntime runtime) {
        return DaggerAppComponent.builder()
                .vertxModule(new VertxModule(runtime.vertx(), runtime.config()))
                .build();
    }
}
```

and a corresponding `META-INF/services/dev.vertique.core.VertiqueComponentFactory` file.

With `@VertiqueApp`, the processor generates both artifacts from the `@Component` interface itself:

```java
@VertiqueApp
@Singleton
@Component(modules = { VertxModule.class, CoreLifecycleStepsModule.class, AppModule.class })
interface AppComponent extends VertiqueApplicationComponent {
    // framework methods inherited from VertiqueApplicationComponent
}
```

The processor emits `AppComponentVertiqueComponentFactory` (same name pattern as the hand-written
form) and the SPI file. The generated factory's `.vertxModule(…)` call references
`DaggerAppComponent` by name — the class Dagger generates in the same compilation — so the
reference is type-checked at the final javac compile with no reflection.

### Multi-round safety

`VertiqueAppProcessor` returns `false` from `process()`, ensuring Dagger's own processor
continues to see the `@Component` element and emits `DaggerAppComponent`. The by-name reference in
the generated factory then resolves at the final compile, after both processors have written their
output.

---

## Key Classes

### `VertiqueApp` (in `vertique-application`)

Source-retained annotation that marks the application's Dagger `@Component` interface.

```java
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface VertiqueApp {}
```

Defined in `vertique-application` (package `dev.vertique.application`), alongside
`VertiqueApplicationComponent`. No elements.

#### Invariants & Gotchas

- Exactly one `@VertiqueApp` per compilation is allowed. The processor emits a compile error on
  each annotated element when more than one is present.
- The annotation is `SOURCE`-retained and absent at runtime; it carries no overhead in the
  deployed artifact.

---

### `VertiqueAppProcessor`

`AbstractProcessor` registered via
`META-INF/services/javax.annotation.processing.Processor`. Initialized with a `CodegenContext`
and a `ComponentFactoryEmitter` in `init()`.

`process(annotations, roundEnv)` collects every `@VertiqueApp`-annotated `TypeElement`, enforces
the four validations (below), and delegates to `ComponentFactoryEmitter.emit(component)` for the
one valid survivor. Always returns `false`.

**Validations — on any violation a `Messager` error is emitted on the offending element and no
output is generated:**

| # | Rule | Error message |
|---|------|---------------|
| 1 | Target must be an **interface** | `@VertiqueApp must be placed on an interface …` |
| 2 | Target must be annotated with **`dagger.Component`** | `@VertiqueApp … must be annotated with @dagger.Component` |
| 3 | Interface hierarchy must include **`VertiqueApplicationComponent`** (transitively) | `@VertiqueApp … must extend dev.vertique.application.VertiqueApplicationComponent` |
| 4 | **Exactly one** `@VertiqueApp` per compilation | `exactly one @VertiqueApp per application; found also …` |

Validation 1 is checked first; a class or enum annotated with `@VertiqueApp` gets a clear error
without running the subsequent checks. Validation 4 is checked before validations 1–3, so when
multiple annotated elements are present, all of them get the "exactly one" error and no output is
produced regardless of whether they are individually valid.

---

### `ComponentFactoryEmitter`

JavaPoet emitter invoked by `VertiqueAppProcessor` for a validated `@VertiqueApp` component.

For a component `AppComponent` in package `com.example`, emits:

1. **`com.example.AppComponentVertiqueComponentFactory`** — `public final class` implementing
   `VertiqueComponentFactory<AppComponent>`:

   ```java
   public final class AppComponentVertiqueComponentFactory
           implements VertiqueComponentFactory<AppComponent> {
       @Override
       public AppComponent build(VertiqueRuntime runtime) {
           return DaggerAppComponent.builder()
                   .vertxModule(new VertxModule(runtime.vertx(), runtime.config()))
                   .build();
       }
   }
   ```

2. **`META-INF/services/dev.vertique.core.VertiqueComponentFactory`** — one line containing
   `com.example.AppComponentVertiqueComponentFactory`.

On an `IOException` writing either artifact, a compiler error is emitted via `Diagnostics` and
the method returns without throwing.

#### Invariants & Gotchas

- `DaggerAppComponent` is referenced by name (`"Dagger" + componentSimpleName`), never loaded.
  If Dagger is not on the processor classpath or `@Component` is malformed, the reference will
  fail at the final compile with a standard "cannot find symbol" error pointing at the generated
  factory.
- The `VertxModule` inclusion requirement is **not** validated by the processor. `VertxModule` may
  be included directly in the component's `modules = {...}` list or transitively through a
  component module's `@Module(includes = …)` graph — for example, a component naming
  `dev.vertique.starter.core.CoreApplicationModule` satisfies this because `CoreApplicationModule`
  includes `VertxModule`. A component whose complete module graph omits `VertxModule` compiles
  without error at the processor stage but fails at the final compile with a "cannot find symbol:
  method vertxModule" error on the generated factory's builder chain. This is by design: the
  generated-code error is more precise than a processor-level error.

---

## Generated Output

For a component `com.example.AppComponent`, the processor emits two artifacts:

### Source: `com.example.AppComponentVertiqueComponentFactory`

```java
package com.example;

import dev.vertique.core.VertiqueComponentFactory;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.core.VertxModule;

public final class AppComponentVertiqueComponentFactory
        implements VertiqueComponentFactory<AppComponent> {
    @Override
    public AppComponent build(VertiqueRuntime runtime) {
        return DaggerAppComponent.builder()
                .vertxModule(new VertxModule(runtime.vertx(), runtime.config()))
                .build();
    }
}
```

### Resource: `META-INF/services/dev.vertique.core.VertiqueComponentFactory`

```
com.example.AppComponentVertiqueComponentFactory
```

This is the exact resource `VertiqueComponentFactoryLoader` reads at standalone entry (see
`dev.vertique:vertique-launcher`).

---

## Consumer Wiring

Applications inheriting `vertique-app-parent` declare `vertique-application` as a runtime
dependency and receive the complete processor facade automatically. Custom-parent applications
import `vertique-bom` and configure only the versionless Dagger and `vertique-codegen-all`
processor paths. Do not select processor leaves per feature. See `docs/packaging.md` for both
recipes, explicit Lombok opt-in, and the annotation-processing escape hatch.

Then annotate the `@Component`:

```java
@VertiqueApp
@Singleton
@Component(modules = {
    VertxModule.class,
    CoreLifecycleStepsModule.class,
    AppModule.class
    // ... other modules
})
interface AppComponent extends VertiqueApplicationComponent {
    // no additional members required
}
```

No `META-INF/services` file and no factory class need to be written. A `clean` build is required
after removing `@VertiqueApp` from a component or deleting the component, because the processor
does not clean up previously generated files.

---

## Dependencies

- **`vertique-codegen-core`** — `CodegenContext`, `TypeResolver`, `Diagnostics`; APT helper
  library shared across the codegen family.
- **`vertique-application`** — `VertiqueApp` annotation and `VertiqueApplicationComponent`
  (referenced by FQN in the processor; on the processor classpath so the types resolve during
  annotation processing). Not on the application runtime classpath.

The module has no dependency on `vertique-launcher`, `vertique-core`, or any runtime module.
