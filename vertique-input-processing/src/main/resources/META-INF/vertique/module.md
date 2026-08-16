<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Input Processing Module

> **Status:** Alpha
> **Package:** `dev.vertique.input.processing`
> **Artifact:** `vertique-input-processing`
> **Depends on:** core

Processes decoded input values through declared canonicalization and sanitization policies. The module is transport-neutral: it operates on already-decoded values and has no dependency on any REST or transport layer.

---

## When To Use It

Include this module when an application or framework transport needs decoded input values (request bodies, message payloads, or other structured input) processed through the canonicalization and sanitization policies declared in `dev.vertique.core.sanitization`.

---

## Core Concepts

Input processing walks a decoded input object and applies the canonicalizers and sanitizers its declared policies select, producing a processed value with the same shape. Policies are declared with the annotation model in `dev.vertique.core.sanitization` (in the artifact `dev.vertique:vertique-core`); this module supplies the processing engine that honors them.

Three policy layers compose for every string value, in this order:

1. **Invocation-level** chains — supplied by the calling transport as an `EffectiveInputPolicies` value; applied to every string in the input.
2. **Object-level** chains — declared on the target type itself.
3. **Field-level** chains — declared on the field or record component.

Skip flags (`@SkipCanonicalization` / `@SkipSanitization`) are **sticky**: once set by any ancestor, they suppress the corresponding layer for every descendant. Processing is copy-on-write — the engine never mutates the input structure.

The engine resolves a build-time-generated `{DTO}_InputProcessor` for the target type first (see [Extension Points](#extension-points)) and falls back to a reflective walk when none is on the classpath. Both paths produce the same output.

---

## Key Classes

### `InputObjectProcessor`

The processing entry point. Transform-only — it never invokes Bean Validation.

```java
static InputObjectProcessor createDefault(
        Function<Class<? extends Canonicalizer>, Canonicalizer> canonicalizerResolver,
        Function<Class<? extends Sanitizer>, Sanitizer> sanitizerResolver);

Object processInput(
        Object input, Type targetType, EffectiveInputPolicies policies, InputLocation location);
```

`createDefault(...)` returns the default engine — the reflective walker with the generated-processor fast path — and owns the construction of its internal annotation-metadata resolver and per-type cache. Callers supply only the two resolver functions that produce canonicalizer and sanitizer instances (typically backed by dependency injection).

`processInput(...)` accepts the decoded intermediate (`Map<String, Object>` for objects, `List<Object>` for arrays, or a raw value; `null` is returned unchanged) and returns a new structure with string values transformed.

### `EffectiveInputPolicies`

Record carrying the invocation-level chains for a single processing call:

- `List<Class<? extends Canonicalizer>> canonicalizers()`
- `List<Class<? extends Sanitizer>> sanitizers()`
- `EffectiveInputPolicies.NONE` — the empty constant
- `boolean isEmpty()` — `true` when both invocation-level chains are empty; object- and field-level policies may still apply

### `InputTraversalContext`

Immutable accumulated traversal state (inherited chains plus sticky skip flags). Every `descend` returns a new instance.

- `static InputTraversalContext fromPolicies(EffectiveInputPolicies policies)` — seeds the root of a traversal
- `InputTraversalContext descend(List<Class<? extends Canonicalizer>> objectCanon, List<Class<? extends Sanitizer>> objectSanit, boolean objectSkipCanon, boolean objectSkipSanit, List<Class<? extends Canonicalizer>> fieldCanon, List<Class<? extends Sanitizer>> fieldSanit, boolean fieldSkipCanon, boolean fieldSkipSanit)` — the raw-list overload generated processors call; `fieldCanon` / `fieldSanit` may be `null` when descending from a list element
- `inheritedCanonicalizerChain()`, `inheritedSanitizerChain()`, `inheritedSkipCanonicalization()`, `inheritedSkipSanitization()` — accumulated state accessors

### `ChainResolver`

Functional interface that applies a canonicalizer chain followed by a sanitizer chain to one string value, sourcing processor instances from the configured factories:

```java
String apply(
        String value,
        List<Class<? extends Canonicalizer>> canonicalizers,
        List<Class<? extends Sanitizer>> sanitizers,
        InputValueContext valueContext);
```

The engine constructs it from the resolver functions passed to `createDefault(...)` and threads it into generated processors, so generated code never sees the raw factories.

---

## Extension Points

### `GeneratedInputProcessor<T>` — build-time-generated walkers

Per-type walker over the decoded intermediate, emitted by the `vertique-codegen-sanitization` annotation processor into the DTO's own package with the suffix `_InputProcessor`. Implementations are stateless, reentrant, copy-on-write, and must produce output identical to the reflective walker.

```java
Class<T> targetType();

Object process(
        Object intermediate,
        EffectiveInputPolicies policies,
        InputLocation location,
        ChainResolver resolver,
        GeneratedInputProcessorDispatcher dispatcher,
        @Nullable InputTraversalContext parent,
        String parentPath);
```

`parent` is `null` at the top level (the processor then seeds from `InputTraversalContext.fromPolicies(policies)`); `parentPath` is the dot-separated prefix for every field path this DTO composes (`""` at the top level).

### `GeneratedInputProcessorDispatcher` — generated/reflective handoff

Resolves generated processors from the consuming type's classloader (`Class.forName`, `ClassValue`-cached) and hands nested traversal back to the reflective walker when none exists. Public seams:

- `GeneratedInputProcessorDispatcher(ReflectiveContinuation continuation)` — construct with the fallback continuation
- `interface ReflectiveContinuation` — `continueAt(...)` and `walkUnknown(...)`; supplied by the engine so accumulated chains and sticky skip flags survive the codegen↔reflection boundary
- `static GeneratedInputProcessorDispatcher withoutContinuation()` — a dispatcher whose continuation throws; for tests that exercise generated processors in isolation
- `<T> void register(Class<T> type, GeneratedInputProcessor<T> instance)` — explicit registration, taking precedence over the classloader lookup
- `Object dispatchNested(Object intermediate, Class<?> nestedType, EffectiveInputPolicies policies, InputLocation location, ChainResolver resolver, InputTraversalContext parentCtx, String parentPath, Class<?> ownerType)` — the nested-type entry point generated code calls

A generated class that exists but cannot be instantiated is a build defect: the failure is cached and rethrown, never silently downgraded to the reflective path.

### `GeneratedSupport` — helpers generated code calls

Static helpers that keep chain-application logic in one place instead of inlining it into every generated class:

- `applyString(...)` — one string field
- `applyStringCollection(...)` — a `Collection<String>` field
- `dispatchObjectCollection(...)` — a collection of nested DTOs
- `applyDefault(...)` — the generated `switch`'s `default` arm and annotated `Object`-kind fields
- `childPath(String parentPath, String key)` — composes the dot-separated field path

### Not API

The default engine implementation, the annotation-metadata resolver and its metadata carrier records, and the dispatcher's lookup and unknown-value walk entry points are package-private internals of `dev.vertique.input.processing`. Obtain the engine through `InputObjectProcessor.createDefault(...)`.

---

## Dependencies

- `dev.vertique:vertique-core` — the canonicalization and sanitization contracts and annotation model this module executes.
