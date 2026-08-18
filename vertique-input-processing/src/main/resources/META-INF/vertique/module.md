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

Each layer's chain is appended **exactly as declared** — same order, repeats included — and no entry is ever dropped because another layer already named that processor class. So `@Sanitize(StripHtml.class)` at the route, `@Sanitize(StripHtml.class)` on a type and `@Sanitize({DecodeEntities.class, StripHtml.class})` on one of its fields compose to `StripHtml, StripHtml, DecodeEntities, StripHtml`: the field's declared decode-then-strip order is what runs, and it runs after the layers above it. Repeats are kept rather than collapsed because idempotence is not commutativity — `Canonicalizer` and `Sanitizer` require the former and say nothing about the latter. Dropping the field's trailing `StripHtml` because an enclosing layer already named that class would leave `&lt;script` stripped while still entity-encoded (a no-op), then decoded, and the application would receive `<script`.

What keeps the chain bounded is one narrow rule, applied per **declaration site**: the chain declared at a site is contributed **once per descent path**. A declaration site is the type for an object-level chain, and the type plus the property name for a field-level one — so a field's chain and its owner type's chain are always different sites, and so is the same property name on two different DTOs. Policies are resolved per type, so a self-referential DTO offers every site on it again at each level of a recursive graph; recognizing that repeat offer for what it is — one declaration site re-offering itself — removes the amplification without touching what any declared chain contains. That covers both shapes of the amplifier equally: `@Sanitize(X) class Node { Node child; }` and `class Node { @Sanitize(X) Node child; }` each contribute `X` once. A site contributes **all** of its entries, in declared order, or none of them — never a partial chain. Distinct sites each contribute once, and both the number of sites and the length of each declared chain are fixed by your code, never by the request. The composed chain's length is therefore bounded by the declared type graph and is independent of how deeply the request body nests.

Skip flags (`@SkipCanonicalization` / `@SkipSanitization`) are **sticky**: once set by an ancestor, they suppress the corresponding layer for every descendant, whatever that descendant declares. An **object-level** skip is narrower: it suppresses the layer only for fields that declare no chain of their own. A field carrying its own `@Canonicalize` / `@Sanitize` therefore opts back in despite its owner type's skip — on a nested-object or collection field exactly as on a direct `String` field. Processing is copy-on-write — the engine never mutates the input structure.

**A collection's element type is its `Collection<E>` binding**, not a type argument read off the declared type by position. For a field or body type declared over a collection subtype, the engine resolves `E` by following the type's supertypes with its declared arguments substituted, so:

| Declared type | Element type | Why |
|---|---|---|
| `List<NestedDto>`, `Set<NestedDto>` | `NestedDto` | `List<E>` binds `E` directly |
| `Pair<NestedDto, Other>` where `class Pair<A, B> extends ArrayList<A>` | `NestedDto` | the binding is the *first* argument here — the arity of the declared type is irrelevant |
| `Weird<Other, NestedDto>` where `class Weird<A, B> extends ArrayList<B>` | `NestedDto` | the binding is the *second* argument |
| `Fixed<NestedDto>` where `class Fixed<T> extends ArrayList<String>` | `String` | the supertype fixes the element; the declared argument is not the element type and its policies do not run element-wise |
| a raw collection (`List`, `Pair`) | none | nothing binds `E`, so no element schema is determinable and the field keeps only its inherited chains |

`NestedDto`'s own declared policies therefore run against the elements of a `Pair`- or `Weird`-shaped field, and a `Fixed`-shaped field is processed as a collection of strings — in both cases matching what the codec actually binds.

The engine resolves a build-time-generated `{DTO}_InputProcessor` for the target type first (see [Extension Points](#extension-points)) and falls back to a reflective walk when none is on the classpath. Both paths produce the same output.

---

## Coverage limits

A declared policy runs wherever the engine can tell, from the **declared** Java types alone, which property a wire key belongs to. Five shapes defeat that, and in each one a policy you declared silently does not run. Nothing signals it at startup or at request time: the field is processed with the chains it inherits, which is indistinguishable from a working policy until the value that mattered gets through. Read this list as "do not declare a policy here and assume it applies".

| Shape | What still happens | What does not |
|---|---|---|
| A `Map`-typed field (`Map<String, ?>`) | Inherited chains — invocation-level, the owner type's object-level, and the field's own — reach every string inside, at any depth | The value type's own `@Canonicalize` / `@Sanitize`, at type level or on its fields. A map has no statically known property set, so no per-property metadata is resolved for its entries |
| An `Object`-typed field | Same — inherited chains reach every string in whatever arrives | Any policy declared on the runtime value's actual type |
| A polymorphic subtype (`@JsonTypeInfo`) | Inherited chains, plus every policy the **declared** base type carries | Policies a concrete subtype adds. Metadata is resolved from the declared type; the engine never inspects the runtime subtype the codec selects |
| `@JsonUnwrapped` members | Inherited chains reach the promoted keys | The unwrapped type's field-level policies. Its members arrive as keys of the *enclosing* object, where the projection matches no declared property of that enclosing type |
| `ACCEPT_CASE_INSENSITIVE_PROPERTIES` | Inherited chains reach the key, and the codec still binds it | The field's own declared policies, whenever the incoming key differs from the declared name in case only. The projection publishes the names the mapper declares, not the case folding it applies when matching |

The first three are structural: no statically known property set exists to project onto. The last two are projection gaps — the wire-name projection (`InputFieldNameResolver`) is built from a codec's declared property names, and neither an unwrapped member nor a case-folded match is one of them. None is claimed as supported, and none is detected by `InputObjectProcessor.declaresPolicies`: every one of them is reachable only through a runtime value or a codec-side name, so no walk over *declared* types can see it.

Working within the limits: give a governed value a declared type with real properties rather than `Map` or `Object`; declare the policy on the concrete type actually bound rather than on a polymorphic base; and prefer an explicitly named nested property over `@JsonUnwrapped` when the nested type carries policies.

---

## Key Classes

### `InputObjectProcessor`

The processing entry point. Transform-only — it never invokes Bean Validation.

```java
static InputObjectProcessor createDefault(
        Function<Class<? extends Canonicalizer>, Canonicalizer> canonicalizerResolver,
        Function<Class<? extends Sanitizer>, Sanitizer> sanitizerResolver);

static boolean declaresPolicies(Type targetType);

Object processInput(
        Object input,
        Type targetType,
        EffectiveInputPolicies policies,
        InputLocation location,
        InputFieldNameResolver nameResolver);
```

`createDefault(...)` returns the default engine — the reflective walker with the generated-processor fast path — and owns the construction of its internal annotation-metadata resolver and per-type cache. Callers supply only the two resolver functions that produce canonicalizer and sanitizer instances (typically backed by dependency injection).

The engine memoizes each resolver function's result **per processor class** and reuses the returned instance for every value it processes, so a resolver need not cache anything itself and is not called once per string value. A resolution that fails is memoized too and rethrown on every later use of that class, so an unresolvable processor still fails the request but costs one lookup rather than one per value. Both caches are owned by the engine instance and die with it.

Memoization is not mutual exclusion: threads racing on a cold class may each run the resolver, and one result wins while the others are discarded. Once per class is the steady state, not a guarantee. A resolver function must therefore be safe to call concurrently, must return an instance safe to share across requests and threads, and must not depend on being invoked exactly once.

`processInput(...)` accepts the decoded intermediate (`Map<String, Object>` for objects, `List<Object>` for arrays, or a raw value; `null` is returned unchanged) and returns a new structure with string values transformed.

`nameResolver` is a `dev.vertique.core.sanitization.InputFieldNameResolver` — the codec-neutral wire-name → Java-property-name projection the engine consults for every intermediate key before looking that field's policies up. The contract is declared in `vertique-core` beside `InputLocation`, `Canonicalizer` and `Sanitizer`; codec-backed implementations live in the module owning that codec (`JacksonFieldNameResolver` in `vertique-json`), which is what keeps this module free of any codec dependency. Pass `InputFieldNameResolver.IDENTITY` when the intermediate's keys are already Java property names — including every call that processes a bare `String`, where there is no object whose fields could be renamed.

`targetType` may be anything that reduces to a class: a class, a parameterized type, a bounded wildcard or type variable, an `Optional` of any of those, or an array of them. A type that reduces to no class at all — a `GenericArrayType` such as `List<Inner>[]`, or a foreign `Type` implementation — cannot be processed: the call throws `IllegalStateException` when `policies` is non-empty, because the caller declared processing that provably cannot run, and returns `input` unchanged when `policies` is empty.

`declaresPolicies(Type)` answers whether the type graph reachable from `targetType` declares any canonicalizer or sanitizer chain, without an engine instance. It exists for the composition decision a transport makes at startup: the engine binding is optional, so a transport that mounts a route whose body type declares `@Canonicalize` or `@Sanitize` while nothing is bound would otherwise serve requests with none of the declared processing running. The walk is breadth-first with a visited set, so it terminates on any type graph, and it reports only *declared chains* — a `@SkipCanonicalization` / `@SkipSanitization` declares nothing to run and is not a policy. The [coverage limits](#coverage-limits) below apply to it in full, for the same reason they apply to the engine: nothing reachable only through a runtime value is visible to a walk over declared types. Its link set is deliberately **narrower** than the set of types the engine can key metadata against at runtime — it skips `String` and every type `isDescendableObject` rejects, and for a collection field it follows the element type instead of the container. That is correct for the question it answers (*does anything declare a policy?*) and is why it is **not** the walk that decides which projections to precompute; see `precomputeFieldNameResolution` below. It is a startup-time query that builds and discards its own cache, retaining no `Class` past the call.

`precomputeFieldNameResolution(Type, InputFieldNameResolver)` hands the resolver every owner type this engine may pass to `InputFieldNameResolver.logicalName` while processing the given declared type, so no projection is composed on the request path. A transport calls it once per body or message type at registration, when an engine is bound.

The prepared set is deliberately **wider** than the declared type graph. A field's *raw* declared class is an owner too, because a wire fragment whose shape disagrees with the declared shape is dispatched against it — a body `{"name": {"a": 1}}` against a `String name` field keys its metadata lookup on `String`. That is why `String`, `List` and `Map` appear in the prepared set; they are not noise, and removing them reintroduces request-path introspection.

Two bounds are deliberate. A platform (`java.*` only — not `javax.*` or `jakarta.*`, which hold ordinary third-party and application-owned types with ordinary declared fields) class is precomputed but never descended: a JDK class's generic containers erase to `Object` or to platform interfaces, so descending one would introspect JDK internals for nothing. And an owner no declared type can name — a `@JsonTypeInfo` subtype, or the runtime value of a `Map`- or `Object`-typed field — is composed lazily on first use, because no static walk can enumerate it.

When a generated processor exists for a type it answers for itself: its `fieldNameOwnerTypes()` **replaces** the reflective contributions rather than supplementing them, so each execution path prepares what it actually dispatches against. An empty return means "declares no owner set" and falls back to the reflective walk, which keeps a hand-written or previously-generated processor working. That fallback is logged at `DEBUG` on `dev.vertique.input.processing.OwnerTypeWalk`, naming the processor and the type — enable it when a stale generated class on the classpath is suspected, since the fallback is otherwise indistinguishable from the ordinary reflective case.

Composing a projection can fail, and failing here is the point: a wire-name collision that would otherwise throw on every request instead fails registration once. The call also resolves each reachable type's policy metadata, so conflicting annotations (`@Canonicalize` with `@SkipCanonicalization`) surface at registration as `IllegalStateException` rather than on the first request.

Both shifts are **consumer-visible**: an application carrying either fault boots today and fails on the request that reaches it. After this change it fails at startup instead. That is the intended direction — the fault was always there, and a startup failure is the one you can act on.

> **Migrating a custom `InputObjectProcessor`.** `precomputeFieldNameResolution` is abstract, so an implementation outside this repository must add it. Implement it by preparing every owner type your processor may pass to `logicalName`; if yours resolves no per-type metadata, an empty body is correct. This module is **Alpha** — the break is deliberate and is the reason the method is not a `default` that would silently leave a processor under-prepared.

**What a policy observes in `InputValueContext`.** `path` is the **wire** path, so a diagnostic points at the key the caller actually sent. `logicalName` is the **Java** property name once the projection matched a declared property, and the wire name otherwise. List elements carry the element path in both components. Sanitizer authors who key on `logicalName` therefore see the Java name, not the wire key, for every matched property; branch on `path` when the wire form is what matters. The record itself is documented in the `vertique-core` reference.

### `EffectiveInputPolicies`

Record carrying the invocation-level chains for a single processing call:

- `List<Class<? extends Canonicalizer>> canonicalizers()`
- `List<Class<? extends Sanitizer>> sanitizers()`
- `EffectiveInputPolicies.NONE` — the empty constant
- `boolean isEmpty()` — `true` when both invocation-level chains are empty; object- and field-level policies may still apply

### `InputTraversalContext`

Immutable accumulated traversal state (inherited chains plus sticky skip flags). Every `descend` returns a new instance.

- `static InputTraversalContext fromPolicies(EffectiveInputPolicies policies, InputFieldNameResolver nameResolver)` — seeds the root of a traversal with the projection it will consult; there is no resolver-less overload, so no caller can re-seed `InputFieldNameResolver.IDENTITY` by omission and silently drop a wire-name projection below that point
- `String logicalFieldName(Class<?> ownerType, String wireName)` — projects one intermediate key through the seeded resolver
- `InputTraversalContext descend(Class<?> ownerType, String fieldName, List<Class<? extends Canonicalizer>> objectCanon, List<Class<? extends Sanitizer>> objectSanit, boolean objectSkipCanon, boolean objectSkipSanit, List<Class<? extends Canonicalizer>> fieldCanon, List<Class<? extends Sanitizer>> fieldSanit, boolean fieldSkipCanon, boolean fieldSkipSanit)` — the raw-list overload generated processors call. `ownerType` names the DTO that declared `objectCanon` / `objectSanit` (the enclosing type, not the nested one); `ownerType` plus `fieldName` names the property that declared `fieldCanon` / `fieldSanit`. Those two keys are what make a recursive DTO's chains contribute once per descent path, whichever of the two sites they sit on. The two kinds of site are distinct keys, never one key with an optional name: a `null` `fieldName` names **no** field site at all, so a field-level chain offered without a name is appended unconditionally rather than being read as the owner type's object-level site. `fieldName` is `null` when there is no enclosing field, and `fieldCanon` / `fieldSanit` may be `null` when descending from a list element
- `InputTraversalContext descend(List<...> objectCanon, ..., boolean fieldSkipSanit)` — the same overload without declaration sites. Retained so processors emitted before the site-keyed form keep linking; because it records no provenance, a self-referential DTO re-offering its own chain through it contributes that chain once per level of the input. Prefer the site-keyed overload — it is what codegen emits.
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

`parent` carries the caller's accumulated traversal state **and** the traversal's `InputFieldNameResolver`; a generated processor consults it through `logicalFieldName(...)` before matching a wire key against a Java field name. The engine's own entry points always pass a real context, including at the top level. `parent` is `null` only when a caller has no context at all — direct invocation of a generated processor — and the emitted code then seeds `InputTraversalContext.fromPolicies(policies, InputFieldNameResolver.IDENTITY)`. That fallback assumes identity naming, so a caller with a real projection must not rely on it. `parentPath` is the dot-separated prefix for every field path this DTO composes (`""` at the top level).

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
- `applyStringCollection(...)` — a `Collection<String>` or `String[]` field
- `dispatchObjectCollection(...)` — a collection or array of nested DTOs
- `applyDefault(...)` — the generated `switch`'s `default` arm and annotated `Object`-kind fields
- `childPath(String parentPath, String key)` — composes the dot-separated field path

### Not API

The default engine implementation, the annotation-metadata resolver and its metadata carrier records, and the dispatcher's lookup and unknown-value walk entry points are package-private internals of `dev.vertique.input.processing`. Obtain the engine through `InputObjectProcessor.createDefault(...)`.

---

## Dependencies

- `dev.vertique:vertique-core` — the canonicalization and sanitization contracts and annotation model this module executes, including `InputFieldNameResolver`, the wire-name projection every entry point takes.
- `org.slf4j:slf4j-api` — the registration-time diagnostic described under `precomputeFieldNameResolution`. Nothing on the request path logs.
