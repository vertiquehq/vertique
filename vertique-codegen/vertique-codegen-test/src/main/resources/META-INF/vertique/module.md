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

**Static factory methods:**

```java
// Single processor
Result result = ProcessorTestHarness.run(new MyProcessor(),
    SourceFiles.inline("com.example.Foo", """
        package com.example;
        public class Foo {}
        """));

// Multiple processors
Result result = ProcessorTestHarness.run(List.of(new ProcA(), new ProcB()), source);

// With processor options (-A flags)
Result result = ProcessorTestHarness.run(new MyProcessor(),
    Map.of("vertique.codegen.package", "com.example.generated"),
    source);
```

**`--release 21` pin rationale:** The harness passes `--release 21` to every compilation. Without this, a host JDK newer than the project's target release would default to its own release, causing processors annotated `@SupportedSourceVersion(RELEASE_21)` to emit source-version-mismatch warnings. Those warnings would then fail `assertNoWarnings()` on developer machines running a newer JDK. The pin makes test results deterministic across JDK versions — the same reasoning behind `-Djdk.version.compileTarget` in Maven compiler plugin configurations.

---

### Result

`ProcessorTestHarness.run(...)` returns `Result(Compilation compilation)`. All assertion methods return `this` for chaining and throw `org.opentest4j.AssertionFailedError` on failure, with the full diagnostic listing appended to the message.

| Method | Description |
|--------|-------------|
| `assertSuccess()` | Fails if `compilation.status() != SUCCESS` |
| `assertFailed()` | Fails if `compilation.status() != FAILURE` |
| `assertGeneratedSourceContains(String fqn, String snippet)` | Fails if no generated source exists for `fqn`, or if the source does not contain `snippet` |
| `assertErrorMessage(String substring)` | Fails if no `ERROR` diagnostic has a message containing `substring` |
| `assertNoWarnings()` | Fails if any `WARNING` or `MANDATORY_WARNING` diagnostic was produced |

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
