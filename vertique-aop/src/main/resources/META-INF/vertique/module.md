<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# AOP

> **Status:** Alpha
> **Package:** `dev.vertique.aop`
> **Artifact:** `vertique-aop`
> **Depends on:** `vertique-core`, `vertx-core`

`vertique-aop` is the runtime SPI module for compile-time method AOP. It defines the public contracts that annotated beans, generated proxies, and user-authored aspect providers depend on. The module contains no annotation processor and emits no generated sources — it is a pure runtime library that the `vertique-codegen-aop` processor targets during compilation.

The design is reflection-free at dispatch time. The generated proxy calls `super.method(args)` directly; no `Method.invoke` appears on the hot path. Aspect annotation attribute values are captured in generated annotation-literals rather than read via `getAnnotation()` at runtime.

---

## When To Use It

Add `vertique-aop` to any module that defines a custom aspect annotation (`@Timed` or any user-defined `@Aspect`-meta-annotated annotation), or to any module that implements `AspectProvider<A>` to supply the interceptor logic for an aspect. Applications inheriting `vertique-app-parent` declare runtime capabilities only and receive the complete processor facade automatically; custom-parent applications use the BOM plus `vertique-codegen-all` recipe in `docs/packaging.md`. `vertique-codegen-aop` remains the ownership boundary for generated AOP proxies.

The open-core framework ships one built-in aspect that depends on this module:

- `@Timed` — in `vertique-micrometer-core`, measures method execution time

---

## Core Concepts

The AOP model is a compile-time subclass proxy. `vertique-codegen-aop` generates `Bean$AopProxy extends Bean` for every bean class that has at least one method annotated with an `@Aspect`-meta-annotated annotation. The proxy overrides each annotated method and runs the aspect chain via `Invocations.run(...)`.

**Interceptor chain construction.** The chain is resolved once in the proxy constructor from the injected `AspectProvider<A>` instances and stored as a `MethodInterceptor[]` field per intercepted method. Each call to that method reuses the same pre-built array.

**Chain ordering.** Interceptors are ordered by descending `@Aspect.ordering()` — higher values are outermost and run first. Ties are broken deterministically by the fully-qualified name of the aspect annotation, making the chain order stable across compilations.

**Sync-returning methods.** When the intercepted method returns a non-`Future` type, the generated override unwraps the completed future and returns the value synchronously. Framework built-ins never defer the future, so this unwrapping succeeds. A custom aspect that defers on a sync-returning method receives an `IllegalStateException` at call time — never a blocked thread.

---

## Key Classes

### `@Aspect`

Meta-annotation that marks an annotation type as an aspect trigger. Placing `@Aspect` on `@MyAnnotation` tells `vertique-codegen-aop` that methods carrying `@MyAnnotation` should be wrapped by an `AspectProvider<MyAnnotation>`-produced interceptor.

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Aspect(ordering = 2000)
public @interface Timed {
    String value() default "";
}
```

| Attribute | Description |
|-----------|-------------|
| `ordering()` | Chain position — higher is outermost (default `1000`) |

`@Aspect` is `RUNTIME`-retained so that `AspectProvider` implementations can read their aspect annotation from the generated literal instance.

### `AspectProvider<A>`

Functional interface — the framework-side factory that builds the `MethodInterceptor` for a specific aspect-annotated method. The type parameter `A` is the aspect annotation type; this is the only association: a binding of `AspectProvider<Timed>` supplies the interceptor for every `@Timed`-annotated method the proxy encounters.

```java
public interface AspectProvider<A extends Annotation> {
    MethodInterceptor interceptor(MethodMetadata target, A annotation);
}
```

Both arguments are produced reflection-free by the processor: `target` is a generated `MethodMetadata` implementation (see `MethodMetadata` in `vertique-core`, `dev.vertique.core.codegen`), and `annotation` is a generated annotation-literal carrying the method's attribute values.

**Dagger wiring example:**

```java
@Provides
AspectProvider<Timed> timedAspectProvider(MeterRegistry registry) {
    return (target, timed) -> invocation -> {
        Timer.Sample sample = Timer.start(registry);
        return invocation.proceed().onComplete(ar -> {
            String name = timed.value().isEmpty() ? target.name() : timed.value();
            sample.stop(registry.timer(name, "status", ar.succeeded() ? "ok" : "error"));
        });
    };
}
```

### `MethodInterceptor`

Functional interface for the around-advice. An interceptor receives an `Invocation` and returns `Future<Object>`. It is free to run logic before and after calling `invocation.proceed()`, and may call `proceed()` 0..n times.

```java
@FunctionalInterface
public interface MethodInterceptor {
    Future<Object> intercept(Invocation invocation);
}
```

v1 aspects call `proceed()` exactly once. The contract permits multiple calls, so a future retry aspect can be implemented without any SPI change.

### `Invocation`

Represents a single method invocation flowing through the interceptor chain. Passed to every `MethodInterceptor.intercept()` call.

| Method | Description |
|--------|-------------|
| `target()` | Reflection-free `MethodMetadata` of the intercepted method |
| `arguments()` | The live argument array — mutations before `proceed()` are visible to the underlying call |
| `instance()` | The proxied bean instance on which the method was invoked |
| `proceed()` | Invokes the remainder of the chain, terminating in `super.method(...)` |

`proceed()` is re-entrant by construction. Each call independently traverses the downstream chain from this position; the continuation is captured per-call on the JVM call stack, not via a mutated shared cursor. Calling `proceed()` twice triggers two independent traversals to the terminal.

#### Invariants & Gotchas

- **Never mutate `arguments()` after calling `proceed()` and then call it again** unless the argument change is intentional for the second call; the array is read live by the terminal on every invocation.
- **Sync-returning method contract.** If the intercepted method does not return `Future`, the generated proxy unwraps the completed future synchronously. A custom interceptor that returns a not-yet-completed future on a sync-returning method receives an `IllegalStateException` at runtime. Only framework built-ins (which always complete synchronously) are safe with sync-returning methods in v1.

### `Invocations`

Static utility class — the continuation nester. Called by generated proxies; not called by user code.

```java
public static Future<Object> run(
    Object instance,
    MethodMetadata target,
    Object[] arguments,
    MethodInterceptor[] chain,
    Supplier<Future<Object>> terminal)
```

`run` folds the ordered `chain` right-to-left into a continuation: interceptor `0` (outermost) wraps interceptor `1`, which wraps interceptor `2`, down to the `terminal` supplier that performs the `super.method(...)` call. With an empty `chain`, the terminal is invoked directly.

Synchronous throws from both the `terminal` supplier and from any interceptor's `intercept()` are captured into `Future.failedFuture(t)` rather than allowed to propagate. This upholds the chain contract — the result is always a `Future` — and ensures that a sync-throwing method surfaces as a failed future outcome that interceptors (such as `@Timed`) can observe and handle uniformly.

---

## Extension Points

### `AspectProvider<A>`

The primary extension point. Bind an implementation to add an aspect to the framework.

**Registration via Dagger:**

```java
// In a @Module
@Provides
AspectProvider<MyAnnotation> myAspectProvider() {
    return (target, annotation) -> invocation -> {
        // before
        return invocation.proceed()
            .onComplete(ar -> { /* after */ });
    };
}
```

**Aspect annotation definition:**

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Aspect(ordering = 1500)  // below Timed(2000) by default
public @interface MyAnnotation {
    String value() default "";
}
```

The processor discovers `@MyAnnotation` as an aspect trigger because it is meta-annotated with `@Aspect`. No registration step beyond annotating a method with `@MyAnnotation` is required — the proxy is generated automatically.

**Supported annotation attribute kinds.** Aspect annotation attributes may be: `boolean`, `byte`, `short`, `int`, `long`, `String`, `Class<?>`, enum values, or arrays of any of these. Attributes of type `char`, `float`, `double`, nested annotations, or arrays of nested annotations are rejected at compile time with a clear error.

---

## Dependencies

| Artifact | Purpose |
|----------|---------|
| `vertique-core` | `MethodMetadata` / `ParameterMetadata` SPI (`dev.vertique.core.codegen`); `Combinators` is not used here — the `Invocations` nester is AOP-specific |
| `vertx-core` | `io.vertx.core.Future` — the uniform async return type of the interceptor chain |

---

## Related ADRs

- ADR-0139: Method-AOP Model & SPI — establishes the compile-time subclass-proxy model, the uniform `Future`-returning `MethodInterceptor` SPI, the re-entrant `Invocations` nester, chain-ordering by `@Aspect.ordering()` + FQN tiebreak, and the `@Inject`-constructor-only binding-origin constraint.
- ADR-0141: Method/Parameter Metadata SPI — establishes `MethodMetadata` and `ParameterMetadata` in `dev.vertique.core.codegen`; explains why the metadata lives in `vertique-core` rather than in `vertique-aop`, and the reflection-free vs. reflective-accessor group split.
