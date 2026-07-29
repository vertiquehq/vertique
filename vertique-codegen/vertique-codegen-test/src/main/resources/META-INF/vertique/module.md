<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# codegen-test

> **Status:** Beta

## Overview

`vertique-codegen-test` provides a `compile-testing`-backed test harness for annotation processors in the Vertique codegen series. It exposes `ProcessorTestHarness` from `src/main/java` so downstream modules consume it as a plain `<scope>test</scope>` dependency — keeping Guava (a transitive of `compile-testing`) off the main classpath of any framework module.

The harness is JUnit-version-agnostic: all assertion failures throw `org.opentest4j.AssertionFailedError` with the full compilation diagnostics embedded in the message, which integrates naturally with JUnit 5 (today's standard) and any other framework that handles opentest4j.

---

## Key Classes

### ProcessorTestHarness

Fluent harness that compiles one or more `JavaFileObject` sources through a given set of annotation processors and returns an assertion-capable `Result`.

**Static factory methods** — four `run(...)` overloads, one per combination of *one processor vs. several* and *with vs. without `-A` options*:

```java
// Single processor
Result result = ProcessorTestHarness.run(new MyProcessor(),
    SourceFiles.inline("com.example.Foo", """
        package com.example;
        public class Foo {}
        """));

// Multiple processors
Result result = ProcessorTestHarness.run(List.of(new ProcA(), new ProcB()), source);

// Single processor with processor options (-A flags)
Result result = ProcessorTestHarness.run(new MyProcessor(),
    Map.of("vertique.codegen.package", "com.example.generated"),
    source);

// Multiple processors with processor options — every processor receives every option,
// mirroring how javac broadcasts -A flags to all active processors
Result result = ProcessorTestHarness.run(List.of(new ProcA(), new ProcB()),
    Map.of("vertique.codegen.package", "com.example.generated"),
    source);
```

| Overload | Signature |
|----------|-----------|
| one processor | `run(Processor processor, JavaFileObject... sources)` |
| several processors | `run(Iterable<Processor> processors, JavaFileObject... sources)` |
| one processor + options | `run(Processor processor, Map<String, String> processorOptions, JavaFileObject... sources)` |
| several processors + options | `run(Iterable<Processor> processors, Map<String, String> processorOptions, JavaFileObject... sources)` |

Each `-A` option is emitted as `-Akey=value` and appended to the harness's pinned `--release` options.

**`--release 21` pin rationale:** The harness passes `--release 21` to every compilation. Without this, a host JDK newer than the project's target release would default to its own release, causing processors annotated `@SupportedSourceVersion(RELEASE_21)` to emit source-version-mismatch warnings. Those warnings would then fail `assertNoWarnings()` on developer machines running a newer JDK. The pin makes test results deterministic across JDK versions — the same reasoning behind `-Djdk.version.compileTarget` in Maven compiler plugin configurations.

---

### Result

`ProcessorTestHarness.run(...)` returns a `Result` — a `final class` wrapping the `compile-testing` `Compilation` and holding the lazily-built classloader for the compilation's output. All assertion methods return `this` for chaining and throw `org.opentest4j.AssertionFailedError` on failure, with the full diagnostic listing appended to the message.

| Method | Description |
|--------|-------------|
| `compilation()` | The raw `Compilation` this result wraps, for assertions the harness does not cover |
| `assertSuccess()` | Fails if `compilation.status() != SUCCESS` |
| `assertFailed()` | Fails if `compilation.status() != FAILURE` |
| `assertGeneratedSourceContains(String fqn, String snippet)` | Fails if no generated source exists for `fqn`, or if the source does not contain `snippet` |
| `assertGeneratedSourceDoesNotContain(String fqn, String snippet)` | Negative counterpart of the above: fails if no generated source exists for `fqn`, or if the source **does** contain `snippet` |
| `assertErrorMessage(String substring)` | Fails if no `ERROR` diagnostic has a message containing `substring` |
| `assertNoWarnings()` | Fails if any `WARNING` or `MANDATORY_WARNING` diagnostic was produced |
| `generatedClassLoader()` | Asserts success, then returns a `ClassLoader` over every class the compilation produced. Built once and reused |
| `loadGeneratedClass(String fqn)` | Loads one class the compilation produced, by fully-qualified name |

All failure messages include a `Compilation diagnostics:` section listing every diagnostic so test output is immediately actionable.

**Chained assertion example:**

```java
@Test
void validAnnotationCompiles() {
    ProcessorTestHarness.run(new MyProcessor(),
            SourceFiles.inline("com.example.Foo", """
                    package com.example;
                    @MyAnnotation
                    public class Foo {}
                    """))
        .assertSuccess()
        .assertNoWarnings()
        .assertGeneratedSourceContains("com.example.FooModule", "@Module");
}

@Test
void missingInjectConstructorIsAnError() {
    ProcessorTestHarness.run(new MyProcessor(),
            SourceFiles.inline("com.example.BadFoo", """
                    package com.example;
                    @MyAnnotation
                    public class BadFoo {}
                    """))
        .assertFailed()
        .assertErrorMessage("@Inject constructor");
}
```

**Roundtrip tests — loading what the processor emitted:**

`generatedClassLoader()` and `loadGeneratedClass(String)` take a test past "the generated source contains this text" to "the generated code actually behaves this way": both processor output and the classes compiled from your inline source fixtures are loadable, so the emitted class can be instantiated and exercised.

```java
@Test
void generatedContributorReportsItsContract() throws Exception {
    Class<?> generated = ProcessorTestHarness.run(new MyProcessor(), source)
        .assertSuccess()
        .loadGeneratedClass("com.example.UserService_ContractContributor");

    Object contributor = generated.getDeclaredConstructor().newInstance();
    assertInstanceOf(ServiceContractContributor.class, contributor);   // same Class object as the test's
}
```

Two behaviors matter when writing such tests:

- **Framework types keep their identity.** The loader's parent is the test classpath, so a type shared between the generated code and the test assertions (e.g. an SPI interface) resolves to the same `Class` object on both sides — `instanceof` and casts work as expected. Only the compilation's own output is served ahead of the parent; `java.*`, `javax.*`, and `jdk.*` always come from the parent.
- **A namesake on the classpath will not be substituted.** `loadGeneratedClass` fails with `AssertionFailedError` if the FQN was not produced by *this* compilation, even when a class of that name exists on the test classpath. That is what makes "the processor really emitted X" a provable claim. The failure message lists every FQN the compilation did produce.

`generatedClassLoader()` asserts compilation success before returning, builds the loader once, and reuses it — repeated `loadGeneratedClass` calls on the same `Result` return the same `Class` object.

---

### fixtures.SourceFiles

A thin wrapper around `JavaFileObjects.forSourceString` with a sane default for in-test source snippets.

```java
JavaFileObject src = SourceFiles.inline("com.example.Foo", """
        package com.example;
        public class Foo {}
        """);
```

`inline(String fqn, String body)` wraps `JavaFileObjects.forSourceString(fqn, body)`.

---

## Extension Points

### opentest4j Assertion Semantics

`AssertionFailedError` (from `org.opentest4j:opentest4j`) is the single assertion type thrown by all `Result` methods. This choice is intentional: opentest4j is the low-level assertion SPI shared by JUnit 5, JUnit 4 (via the opentest4j bridge), and other compatible frameworks. `vertique-codegen-test` does not import any JUnit Jupiter types in its public surface, so consumers are free to choose their JUnit generation.

---

## Module Dagger Bindings

None. Test-utility library only.

---

## Dependencies

| Dependency | Scope | Purpose |
|------------|-------|---------|
| `dev.vertique:vertique-codegen-core` | compile | APT helpers available to test fixtures |
| `com.google.testing.compile:compile-testing:0.23.0` | compile | In-process Java compiler with `Compilation` result and `JavaFileObjects` utilities |
| `org.opentest4j:opentest4j:1.3.0` | compile | `AssertionFailedError` thrown by all `Result` assertions |
| `com.palantir.javapoet:javapoet:0.14.0` | compile | Fixtures may build source objects via JavaPoet |
