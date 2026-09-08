<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Input Processing Module

> **Status:** Stable
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

A declared policy runs wherever the engine can tell, from the **declared** Java types alone, which property a wire key belongs to. Three shapes defeat that, and in each one a policy you declared silently does not run. Nothing signals it at startup or at request time: the field is processed with the chains it inherits, which is indistinguishable from a working policy until the value that mattered gets through. Read this list as "do not declare a policy here and assume it applies".

| Shape | What still happens | What does not |
|---|---|---|
| A `Map`-typed field (`Map<String, ?>`) | Inherited chains — invocation-level, the owner type's object-level, and the field's own — reach every string inside, at any depth | The value type's own `@Canonicalize` / `@Sanitize`, at type level or on its fields. A map has no statically known property set, so no per-property metadata is resolved for its entries |
| An `Object`-typed field | Same — inherited chains reach every string in whatever arrives | Any policy declared on the runtime value's actual type |
| A polymorphic subtype (`@JsonTypeInfo`) | Inherited chains, plus every policy the **declared** base type carries | Policies a concrete subtype adds. Metadata is resolved from the declared type; the engine never inspects the runtime subtype the codec selects |

All three are structural: no statically known property set exists to project onto. None is claimed as supported, and none is detected by `InputObjectProcessor.declaresPolicies`, because each is reachable only through a runtime value, so no walk over *declared* types can see it.

A fourth limit is a codec's, not this engine's, and it is a retained residual rather than a structural one: a type the codec binds **outside its declaration view** is trusted as a whole. For Jackson that is a custom deserializer, a delegating creator, or a builder (`@JsonDeserialize(builder = …)`). The projection reports no bound names for such a type, so the registration check below does not run on it, and a field carrying a chain that the builder writes under a differently named method — `@Sanitize String streetName` set by `street(String)` — is a policy that silently never runs. Lombok's `@Builder @Jacksonized` names its methods after the fields and routes correctly; a hand-written builder with its own vocabulary does not. Name the builder methods after the fields they write.

The mirror image is a deserializer the projection cannot see. Only a deserializer declared on the class itself (`@JsonDeserialize(using = …)`) marks a type as bound outside its declaration view; one registered through a module (`SimpleModule.addDeserializer`) or declared on the referencing field does not, so the projection enumerates the type's properties as if Jackson bound them, and the registration check runs against names Jackson does not use. The failure is the loud one, not the silent one: a governed field whose name differs from what the introspection reports is refused at startup. Declare such a deserializer on the class, or name the governed field after the property the introspection reports.

Working within the limits: give a governed value a declared type with real properties rather than `Map` or `Object`, and declare the policy on the concrete type actually bound rather than on a polymorphic base.

### Two former limits, now covered

A codec that **renames or promotes** a key is no longer a gap. Two shapes that used to strand a declared policy are handled, both through the `InputFieldNameResolver` SPI rather than by teaching this engine any codec's rules:

- **Members a codec promotes into the enclosing object** — Jackson's `@JsonUnwrapped`. The projection reports, per owner type, which promoted keys are bound into which declaring type, and the engine resolves that type's metadata for them, so the promoted field's own chains run. A prefix or suffix and nested promotion are handled by the projection.
- **A key matched case-insensitively** — `ACCEPT_CASE_INSENSITIVE_PROPERTIES`. The projection folds case when the mapper does, so a differently-cased key selects the policies of the property the codec binds it to.

Routing a promoted key means descending each enclosing member on its path and then applying the declaring type's policies. Whether that changes anything is decided at registration: it does when the declaring type or anything reachable from it declares a chain, when the declaring type or the promoted field carries a skip, or when an enclosing member carries a chain or a skip. A promoted key for which nothing would change is accepted as is. Where routing matters and the engine cannot route, registration **fails startup** rather than letting the policies pass silently, naming the owner, the key, the declaring type and the field in every case:

- **A generated processor owns the type.** Its field-name `switch` is emitted from the owner's own declared fields, so a promoted key reaches its `default` arm and would receive only the inherited chains. Declare the member as a named nested property, or move the policies onto the owner.
- **The path cannot be descended.** An enclosing member on the path is not a field this engine tracks.
- **The path lands on another type.** Descending the path reaches a type other than the one the projection promotes from — a generic member whose type argument the codec resolved while the declared field type erases. Declare the member with its concrete type.

### A governed field the codec binds under another name

This engine keys per-field metadata on the Java **field** name; a codec keys its binding on the **property** name it derives from the members it finds. The two agree for a field, a record component, and the accessor pair a field's name implies — `streetName` with `setStreetName`, the shape Lombok emits. They diverge for an accessor or creator parameter whose implicit name differs from the field it writes: for `@Sanitize private String streetName` behind `setStreet(String)`, Jackson binds the wire key `street` into a property named `street` and never learns that the setter writes `streetName`. Neither side can derive that mapping, so the field's policy could never be reached from the wire. An ignored or transient field the codec never binds is the same shape.

Registration refuses it. The projection enumerates the Java names its codec binds into (`InputFieldNameResolver.boundJavaNames`), and a field carrying a declared chain outside that set fails startup naming the type, the field, and what the codec does bind. Name the field after the property the codec binds, or declare it so the codec binds it directly. A projection that cannot enumerate — `IDENTITY`, whose keys are already Java names, or a Jackson type bound by a custom deserializer, a builder or a delegating creator — is trusted; a field carrying only a skip flag is never refused, because an unbound skip suppresses nothing that would otherwise run.

A creator parameter is the one shape the enumeration cannot settle. For `@JsonCreator Dto(@JsonProperty("street_name") String s) { streetName = s; }` Jackson binds `street_name` into the parameter and never learns which field the constructor assigns, so the key is bound but unroutable (`InputFieldNameResolver.unroutableWireNames`). Which field it writes is undecidable, so an owner with such a key **and** a chain on any field fails registration naming the key; an owner with such a key and no chain is unaffected. Name the parameter after the field it writes — `@JsonProperty("streetName")` on the parameter, or parameter-name support — and the key is routable by that name whether or not the field has an accessor; or give the field the same `@JsonProperty` name as the parameter, so Jackson links the two and binds under the field's own name. Record components are always linked to their creator parameters.

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

The prepared set holds exactly the **field-name owners** the engine may ever consult — a field-name owner is a class whose declared property schema an execution path may project a wire key against, never merely a class the engine happens to pass through as `InputValueContext.ownerType`. A schema-free fragment — a raw collection, a `Map`/`Object` target, a wire/declared shape mismatch, an unknown subtree — has no field-name owner: none of these is ever projected against, because their metadata declares no fields a projected key could match, so `InputFieldNameResolver` is never consulted for one. `ownerType` still flows through `InputValueContext` for such a fragment — it is processing *provenance*, not necessarily a property-declaring DTO — but that propagation needs no precomputed projection and so contributes nothing to this set.

Concretely, the set holds a class only when that class **declares fields of its own**. A `String` field's raw declared class is not prepared: a wire fragment disagreeing with the declared `String` shape does dispatch against it, but `String` declares no fields for a projected key to match, so the dispatch is schema-free. Nor is a raw-container entry point or a field whose element schema cannot be determined. Nor is a `Map`, an enum, or `Object` reached through an *annotated* field, and nor is a collection subtype's own raw container class **unless it declares fields of its own** — a subtype that does declare them is genuinely projectable when a map arrives where it was declared, and is prepared.

The distinction is not cosmetic. Composing a projection can fail, and that failure blocks registration, so preparing a class the engine can never project against would reject startup for a collision no request could reach. Metadata is still resolved for every reachable type, which is what preserves genuine startup detection of conflicting policy annotations; only the name-projection call is gated.

Two bounds are deliberate. A platform (`java.*` only — not `javax.*` or `jakarta.*`, which hold ordinary third-party and application-owned types with ordinary declared fields) class is never descended, and is prepared only if it declares fields like any other class: a JDK class's generic containers erase to `Object` or to platform interfaces, so descending one would introspect JDK internals for nothing. And an owner no declared type can name — a `@JsonTypeInfo` subtype, or the runtime value of a `Map`- or `Object`-typed field — is composed lazily on first use, because no static walk can enumerate it.

When a generated processor exists for a type it answers for itself: its `fieldNameOwnerTypes()` **replaces** the reflective contributions rather than supplementing them, so each execution path prepares what it actually dispatches against. An empty return means "declares no owner set" and falls back to the reflective walk, which keeps a hand-written or previously-generated processor working. That fallback is logged at `DEBUG` on `dev.vertique.input.processing.OwnerTypeWalk`, naming the processor and the type — enable it when a stale generated class on the classpath is suspected, since the fallback is otherwise indistinguishable from the ordinary reflective case.

Composing a projection can fail, and failing here is the point: a wire-name collision that would otherwise throw on every request instead fails registration once. The call also resolves each reachable type's policy metadata, so conflicting annotations (`@Canonicalize` with `@SkipCanonicalization`) surface at registration as `IllegalStateException` rather than on the first request.

Both shifts are **consumer-visible**: an application carrying either fault boots today and fails on the request that reaches it. After this change it fails at startup instead. That is the intended direction — the fault was always there, and a startup failure is the one you can act on.

The same call refuses, as `ConfigurationException`, the two shapes under [coverage limits](#coverage-limits) where a declared chain provably could not run: a promoted key the engine cannot route where routing would apply a policy, and a field carrying a chain that the codec binds no wire key into. Both were silent before; an application carrying either now fails at startup naming the type and the field.

> **Implementing a custom `InputObjectProcessor`.** `precomputeFieldNameResolution` is a
> `default` no-op. Override it when your processor resolves per-type field-name metadata, to
> prepare every owner type you may later pass to `logicalName`; a processor that resolves none
> needs no override.

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

## Invocation-Level Policy Resolution

A transport resolves the invocation-level `EffectiveInputPolicies` it passes to `processInput(...)` from its own annotated endpoints (a REST resource method, a WebSocket handler, a generated MCP tool) before the engine ever runs. This module supplies the shared resolution rules and two adapters — one reflective, one for annotation processors — so every transport applies the same precedence instead of reimplementing it.

### Element definition

An **element**, for policy resolution, is the hierarchy-merged view of a declaration: annotations on the declaring element first, then the same declaration in each superclass (bottom-up), then in each transitively reachable interface. For one annotation type the first occurrence wins, resolved in **two passes** over that merged view: an annotation declared *directly* anywhere in it wins over a composed (meta-annotated) one anywhere in it, and within each pass the nearest declaration wins. So an override carrying a custom `@NormalizedInput` (meta-annotated `@Sanitize(B.class)`) over an interface method carrying a direct `@Sanitize(A.class)` resolves to `[A.class]`. Meta-annotations (composed annotations such as that `@NormalizedInput`) are resolved recursively, with a visited set and without descending into JDK `java.lang.annotation.*` meta-annotations, so a cyclic annotation graph terminates. The additive annotation (`@Canonicalize` / `@Sanitize`) and the skip annotation (`@SkipCanonicalization` / `@SkipSanitization`) are each resolved first-occurrence **independently**, and *then* compared: if both are present anywhere in the same element's merged view, the element is in conflict.

The consequence is directional: an override that declares `@SkipSanitization` over an inherited `@Sanitize(B.class)` is a **conflict**, not "nearest wins" — an override may *replace* an inherited policy but may never *remove* one silently. An override that declares `@Sanitize(A.class)` over an inherited `@Sanitize(B.class)` is not a conflict; it resolves to `[A.class]`, since the nearest declaration is the first occurrence for that polarity.

When the skip and the chain sit on **different** elements — an interface method's `@SkipSanitization` over the implementing class's `@Sanitize(A.class)` — the precedence above still applies unchanged (method-skip beats type-additive, and nothing conflicts), but because the element in front of the reader never opted out, the resolver logs one `WARN` naming both declaration sites whenever a skip the element only *inherited* removes a non-empty chain: in the build log under annotation processing, in the application log at registration at runtime.

### `InvocationPolicySource<V>`

One element's view of one `PolicyAxis`: `additive()` / `additiveDeclaredAt()`, `skip()` / `skipDeclaredAt()`, and `describe()` for diagnostics. A source is a passive data carrier — it never validates itself; `InvocationPolicyResolver` owns the conflict check. `InvocationPolicySource.none()` returns the no-op source (no additive chain, no skip).

### `PolicyAxis`

The two framework-owned axes, each pairing an additive annotation with its skip counterpart: `CANONICALIZE` (`@Canonicalize` / `@SkipCanonicalization`) and `SANITIZE` (`@Sanitize` / `@SkipSanitization`). `additiveAnnotation()` / `skipAnnotation()` return the display names used in conflict messages.

### `InvocationPolicyResolver`

Transport-neutral precedence over `InvocationPolicySource` — no reflection, no annotations:

```java
static <V> List<V> resolveRouteChain(InvocationPolicySource<V> method, InvocationPolicySource<V> type, PolicyAxis axis);
static <V> List<V> resolveParameterChain(InvocationPolicySource<V> parameter, List<V> routeChain, PolicyAxis axis);
```

- **Route chain:** method-skip &gt; method-additive &gt; type-skip &gt; type-additive &gt; none.
- **Parameter chain:** param-skip &gt; param-additive &gt; the already-resolved route chain.

Both throw `InvocationPolicyConflictException` when a source declares both an additive chain and a skip flag.

### `InvocationPolicyConflictException`

Thrown with the conflicting axis and both declaration sites. The message names both sites so the fix is unambiguous:

> `Conflicting @Sanitize (declared on IFoo.bar) and @SkipSanitization (declared on FooImpl.bar) for method FooImpl.bar — an override cannot remove an inherited policy; remove one of the annotations.`

For a same-declaration conflict (both annotations on the same element) the two sites named in the message are identical.

### `ReflectiveInvocationPolicies`

The reflective adapter — resolves `EffectiveInputPolicies` from real annotated `Method`/`Class` pairs through `dev.vertique.core.util.AnnotationResolver` (never `Method.getAnnotation`), so overrides, interface defaults, superclass declarations and composed annotations all resolve the same way they do everywhere else `AnnotationResolver` is used:

```java
static EffectiveInputPolicies resolveRoute(Method method, Class<?> owner);
static EffectiveInputPolicies resolveParameter(Method method, int index, EffectiveInputPolicies route);
```

`resolveRoute` resolves both `PolicyAxis` values and combines them into one `EffectiveInputPolicies`; `resolveParameter` does the same for one parameter, falling back to the route chains it is given. This is the reference reflective adapter — a REST resource scanner or a WebSocket endpoint registrar calls it once per route (and once per parameter) at registration time, so a conflicting declaration fails at startup rather than on the first matching request.

### `ElementInvocationPolicies` — the annotation-processing adapter

`dev.vertique.input.processing.apt.ElementInvocationPolicies` is the compile-time counterpart of `ReflectiveInvocationPolicies`: it resolves the same chains from `javax.lang.model` elements, so a build-time processor generates exactly the policies the reflective runtime would have derived for the same declarations.

```java
ElementInvocationPolicies(Elements elements, Types types);
ElementPolicyChains resolveRoute(ExecutableElement method, TypeElement owner);
ElementPolicyChains resolveParameter(VariableElement parameter, int index, ExecutableElement method, TypeElement owner, ElementPolicyChains route);
```

`ElementPolicyChains` is a record of the two resolved chains as `List<TypeMirror>` (`canonicalizers()`, `sanitizers()`), with `ElementPolicyChains.NONE` for "both axes empty". A processor resolves the route once per method and then each parameter over that result, mirroring the reflective call order.

The merged view is the element definition above, built from elements: the method itself, then the method it overrides in each superclass bottom-up, then in each transitively reachable interface in breadth-first order; class-level annotations are walked from the owner type through its superclasses and interfaces; a parameter's view is the matching parameter of every method in that merged view. Override matching uses `Elements.overrides`, so a genuine override of a generic supertype declaration is recognised and an unrelated same-named method is not. Composed annotations are followed recursively with a visited set — a self-referential annotation terminates the walk instead of recursing forever.

Conflicts surface as the same `InvocationPolicyConflictException`, with the same message and the same declaration-site names the reflective adapter produces. A processor catches it and reports a compile error at the offending element, so the mistake is caught at build time instead of at startup.

#### The `apt` package is compile-time only

`dev.vertique.input.processing.apt` requires the JDK `java.compiler` module and is never loaded by runtime code — nothing outside that package references it, and the module's own tests enforce both directions. Consumers that are not annotation processors can ignore it entirely; it adds no dependency to this artifact, because it imports only JDK types and this module's own base package.


### Scenario matrix

The test-jar (`dev.vertique:vertique-input-processing:test-jar`) ships `dev.vertique.input.processing.testkit.InvocationPolicyScenarios`, the precedence and conflict matrix used to prove `InvocationPolicyResolver`, `ReflectiveInvocationPolicies`, and `ElementInvocationPolicies` against the same expectations. A transport adopting any of them can reuse `InvocationPolicyScenarios.rows()` to parity-test its own adapter against the same scenarios (route/parameter overrides, interface and superclass inheritance, composed annotations, and every additive/skip conflict shape) instead of hand-rolling an equivalent fixture set.

---

### Framework seams

`GeneratedInputProcessorDispatcher` is the framework implementation behind the generated
processing path and carries an INTERNAL marker; it is outside this module's compatibility promise.
An application declares policies through annotations and, at most, implements
`InputObjectProcessor`.

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
