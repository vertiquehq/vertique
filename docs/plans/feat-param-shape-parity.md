# Plan — JAX-RS parameter-shape parity (legacy #153 / #155)

Task 3 of the pre-release blocker batch (`plans/release-blockers-batch-handoff.md`).
Code repo: `sources/vertique`. Meta repo: `vertique-dev` (ADRs).
Risk profile: security-relevant, dual-dispatch parity, spec-conformance, pre-0.1.0.

*Revision 3 — incorporates a first opinion and two adversarial plan reviews; see §3.*

**Provenance.** Approved 2026-07-28. Branch `feat/param-shape-parity`, worktree
`.claude/worktrees/issue-153-155-param-shapes`, branched from `main` at `b6a1104`.
Execution-start drift check (`planning.md`) run at S0 against every §8 manifest path over
`89d455e..b6a1104` — the range in which `main` advanced during planning: one hit,
`c4d8b90`, which touched an unrelated ADR-0121 spec-loading claim in
`vertique-rest-jaxrs`'s `module.md`. Every §2 line reference was re-verified after it;
`module.md:231` and the codegen `module.md:287` "Known Gaps" entry are unchanged. **No
reconciliation needed.** ADR marker re-checked: `0189` taken, `0190` free (F11 stands).

## 1. Context & goal

The framework has two dispatch paths that must stay at parity: a **reflective** path
(`ResourceScanner` + `ParameterExtractor`) and a **generated** path (an annotation
processor emits a descriptor companion per resource). Two legacy issues claim
parameter shapes misbehave silently:

- **#153** — "generated dispatch binds first-value-only for `T[]` query/header params."
- **#155** — "`@FormParam Set<T>`/array silently binds as `List`."

**Both premises are false**, and the real defects are worse (§2). Goal: make parameter
binding *honest, at parity, and conformant with the documented Jakarta REST contract* —
support the shapes the shared downstream machinery already handles, and fix the
adjacent defects in the same execution path that the original framing would have
papered over.

**Why now / why not defer:** this is a pre-release blocker batch; the array defect makes
codegen unusable for any resource with a binary (`byte[]`) body, FORM collections fail
at runtime, and collection elements bypass the input-policy chain. The boring
alternative (document all of it as V1 limitations) was the handoff's approved framing —
rejected because §2 shows it would document a symptom that does not exist while leaving
a startup crash, a 500, and a policy bypass in place.

**User rulings this plan carries** (asked and answered at planning time):
- #153 lands as **fix + support**, not rejection.
- #155 lands as **FORM multi-value support**, not rejection.

**Scope beyond the two issues, and why** (the user's call to confirm at approval):
fixing FORM collections forces a decision on what a collection parameter yields when
absent and whether it is mutable. Jakarta REST 4.0 documents both (§2 F12), and the
framework currently violates both for *every* source. Shipping conformant FORM
collections beside non-conformant QUERY collections would be incoherent, so the
collection *state machine* is unified once, in §4, for all bindable sources.

## 2. Pre-flight findings (verified against code + the jakarta.ws.rs-api 4.0.0 Javadoc, 2026-07-28)

**F1 — #153's symptom does not exist; a startup crash does.**
`TypeMirrorFqn.erasedFqn` falls through to `erased.toString()` for arrays
(`emit/TypeMirrorFqn.java:41`), emitting Java *source* form `"java.lang.String[]"`.
`GeneratedJaxRsDescriptorSupport.resolveClass` special-cases only scalar primitives then
calls `Class.forName` (`runtime/GeneratedJaxRsDescriptorSupport.java:81-87`) — **no array
handling**. The emitter puts that string into `resolveMethod(...)` *inside* the
`describe()` body (`emit/JaxRsDescriptorEmitter.java:246-251`) and into the
`ParamMeta.type` slot (`:604-626`); generated helpers rethrow CNFE as
`IllegalStateException` (`:855-899`). `ResourceScanner.scanResource:130-142` calls
`describe()` eagerly with **no fallback** (its comment forbids falling back to
reflection). Scanning runs during verticle start → deployment fails.
⇒ Any array-typed param on a codegen'd resource **crashes startup opaquely**; it does
not bind one value.

**F2 — F1 also breaks `byte[]` bodies, a documented working shape.**
`ResourceScanner.resolveComponentType:613-632` deliberately classifies `byte[]`/`char[]`
as BODY; `AnnotationDrivenRoutingIT.java:429` covers it reflectively. Under codegen the
same resource cannot start. Zero descriptor-path array coverage exists —
`ExecutionPlanEmitterTest:1260-1330` compiles an array-param resource but only loads the
ExecutionPlan, never calls `describe()`.

**F3 — the array-aware loader already exists, proven, in the same package.**
`runtime/GeneratedJaxRsReflectiveAnnotations.java:113-127` strips trailing `[]` pairs and
uses `Array.newInstance(base, new int[dims]).getClass()`. `ExecutionPlanEmitter`
(`:939-948`) independently dodges the trap by emitting `String[].class` literals.
`JaxRsDescriptorEmitter` + `resolveClass` are the pair that was missed.
**But the two callers differ in class-initialization semantics:**
`loadBaseClass:139-151` uses `Class.forName(fqn, false, cl)` while `resolveClass:86` uses
`Class.forName(fqn, true, cl)`. A naive extraction would silently change one caller ⇒ the
shared helper must take an `initialize` flag.

**F4 — suspected second-order gap: nested base types.** `erasedFqn`'s *non*-array branch
uses `getBinaryName()` precisely to get `Outer$Inner`; the array branch's `toString()` is
expected to yield dotted `Outer.Inner[]`, so dimension-stripping alone still leaves an
unresolvable base. **Unverified by execution** — S1 carries a red test that decides it.

**F5 — #155 understates the breakage; FORM already diverges between paths.**
`ResourceScanner.java:440-454` rewrites the declared type:
`componentType != null ? List.class : param.getType()`. `extractFormParam:738-794` has
native branches only for `FileUpload`, `EntityPart`, `List<FileUpload>`,
`List<EntityPart>`; everything else falls to single-value `getFormAttribute(name)` +
`coerceString`. So text `@FormParam List<String>` — not just `Set`/array — asks for a
`java.util.List` converter → `ParamConverterNotFoundException` → **HTTP 500**. Startup
misses it because the probe substitutes the element type
(`convert/ConversionContexts.java:103-109`). Codegen has **no** FORM normalization
(`EffectiveJaxRsContractResolver.java:574`), so `@FormParam Set<String>` emits
`type=java.util.Set` generated vs `List` reflective.
*Correction from review:* the generated failure is **also a 500**, not a
ClassCastException — a declared `Set` fails the `type == List.class` guard, falls to the
text branch, and `coerceString` throws before the emitted cast
(`ExecutionPlanEmitter:687-693`) ever receives a value. The two paths therefore fail
*differently in kind of message but identically in outcome*; the CCE is only a latent
hazard once extraction starts returning collections, which is exactly what S5 does — so
S5's parity test must assert the post-fix agreement, not a pre-fix CCE.

**F6 — the validation layer already reads all FORM values.**
`WebValidationStrategy.java:962-975`'s `allValues` switch has
`case FORM -> ctx.request().formAttributes().getAll(name)`, gated only on
`componentType != null` (`:906`). A `@FormParam List<T>` **passes JSON-schema array
validation over all values and then binds one**. This change closes that gap.

**F7 — two adjacent defects in the exact code being touched.**
- `@DefaultValue` on *any* collection param is broken: zero values →
  `extractScalarValue:380-385` → `coerceString(default)` with `rawType = List.class` →
  `ParamConverterNotFoundException` → **HTTP 500**. No test covers it.
- **Collection elements never traverse the input-policy chain.**
  `extractScalarValue:391-392` returns before the `objectProcessor` block at `:397-400`,
  and `coerceCollection:427-457` never references `objectProcessor`. So `@QueryParam
  String` is processed but `@QueryParam List<String>` is not. FORM scalars *are*
  (`:789-791`), so a naive copy of QUERY semantics would regress FORM.

**F8 — Vert.x documents no ordering.** `MultiMap.getAll` / `formAttributes()` in
vertx-core 5.1.2 document no insertion/submission order (`get` only says "the first
value"). ⇒ Do not promise ordering; document "as reported by Vert.x".

**F9 — bean-param collection fields are broken for QUERY too, but loudly.**
`ParameterExtractor.paramMeta:1135-1147` hard-codes `componentType = null` (and
`BeanParamModelEmitter.java:472-475` carries a matching `TODO(CG-010-later)`), so
`JaxRsRouteRegistrar.validateBeanParamFields:505-531` probes the whole `List` type via
`forParamMeta` and rejects at startup with `UNRESOLVABLE_PARAM_CONVERTER`. Loud, not
silent ⇒ **out of scope** (§11), with a test pinning the loudness.

**F10 — stale docs assert the wrong symptom.** `vertique-codegen-jaxrs`'s `module.md`
"Known Gaps" (line 287) says generated dispatch "binds only the first value" for `T[]`;
`vertique-rest-jaxrs`'s `module.md:231` claims collection element-wise coercion applies
generally (false for FORM) and its shapes table (`:644`) lists no FORM
collection-of-scalar shape. Corrections, not refreshes.

**F11 — ADR numbering drift.** `adr/product/README.md:201` says "Next ADR number: 0189"
but `0189-response-serializer-completion-future.md` exists (Accepted). Next free is
**0190**; the marker is stale by one. Re-check at S0 per memory
`adr-number-collisions-parallel-sessions` — a concurrent session is active.

**F12 — the documented Jakarta REST 4.0 collection contract (jakarta.ws.rs-api 4.0.0
Javadoc), which the framework currently violates on every source.**
`@FormParam`/`@QueryParam`/`@HeaderParam` each state: *"Be `List<T>`, `Set<T>`,
`SortedSet<T>` or `T[]` array … The resulting collection is read-only."* `@DefaultValue`
states: *"If this annotation is not used and the corresponding meta-data is not present
in the request, the value will be an empty collection for `List`, `Set` or `SortedSet`,
`null` for other object types"*, and with a default present *"the resulting collection
will have a single entry mapped from the supplied default value."*
Current behavior: absence yields **`null`** (`ParameterExtractor.java:380-384`), and
`coerceCollection:445-456` returns **mutable** `ArrayList`/`LinkedHashSet`/`TreeSet`.
Both violate the documented contract *for the three named collection interfaces*.
**Arrays are covered too, and in the other direction:** `T[]` is not one of
`List`/`Set`/`SortedSet`, so an absent array falls under *"`null` for other object
types"* — today's `null` is already conformant and must be preserved, not "improved"
to an empty array. Genuinely **not** addressed by the spec: element **ordering**
(consistent with F8) and `@DefaultValue` applied to a `T[]` — that one remains a
framework decision (§4 decision 4). `Collection<T>` and `NavigableSet<T>` are **never
named** by the spec, yet the framework already accepts both on QUERY/HEADER/COOKIE
(`ResourceScanner.isSupportedCollectionRawType:642-648`,
`EffectiveJaxRsContractResolver.isSupportedCollectionFqn:967-973`).
`@FormParam` additionally applies the default when *"the request entity body is absent or
is an unsupported media type"*.

**F13 — existing validation already restricts native multipart to `List` shapes.**
`RouteValidator.isSupportedFileUploadTarget:145-153` accepts only a FORM scalar
`FileUpload`, a `List<FileUpload>` (FORM-named or `FILE_UPLOADS` aggregate). So
`Set<FileUpload>` is already outside the supported contract *when `@FilePart` is used*.
⇒ Native multipart guards must stay `List`-restricted, and non-`List` native collection
shapes need an explicit loud rejection rather than silently falling into text conversion
(§4 decision 6).

## 3. Independent first opinion & adversarial review outcome

Two consultations ran: `vertique-toolkit:vertique-codex-architect` (first opinion, per
`planning.md`), then an adversarial review of the draft plan.

**Adopted from the first opinion:** the nested-base binary-name gap (F4, → S2);
`Collection<T>` added to the shape matrix; the `@BeanParam` field question scoped
explicitly (§11); splitting the array fix into a unit fix **and** a descriptor
end-to-end proof; both `module.md` "Known Gaps" entries treated as stale-doc defects.
Precision adopted: the pre-fix defect is "exactly one value bound", never "the first
value" — no ordering claim is made anywhere.

**Adopted from the adversarial review** (all verified against code before accepting):
- **F12** — the read-only + empty-on-absence contract. This is the largest change to the
  plan and the reason §4 now freezes one state machine instead of a single default rule.
- **F3's `initialize` flag** — extraction would otherwise silently flip
  `Class.forName(..., true, ...)` to `false` for descriptor support.
- **FORM `genericType` trimmed.** The draft froze a non-null FORM `genericType`; that is
  both unimplemented by the slices and contrary to the documented contract —
  `ResourceMethodMeta.java:147` states `genericType` is *"the full generic type for body
  parameters"*, and codegen emits it for BODY only. FORM keeps `null`.
- **F13** — native multipart guards stay `List`-restricted; non-`List` native shapes get
  a loud startup rejection instead of the draft's accidental broadening.
- **F5 corrected** — generated FORM `Set` fails with a 500, not a CCE.
- **Plan persistence** — `docs/plans/<branch>.md` on the *source* branch, per the rule and
  the in-repo precedent (`522fd7e` → `docs/plans/codex-feat-minimal-archetype.md`), not
  the meta repo. The linter re-run is a gate *before* S1, not slice work.
- **Concrete manifest paths** (no `…` abbreviations) so `plan-linter` can verify them.
- **S3's policy matrix broadened** beyond `String[]`/`byte[]`, and the "reuse
  `isScalarArrayComponent`" wording corrected: the runtime works on `Class<?>` and the
  processor on `TypeMirror`, so the policy is shared *by parity test*, not by one method.
- **S1's acceptance narrowed** to descriptor startup, with the "starts and serves" claim
  moved to an explicit generated-dispatch e2e in S3.
- Sanitization reframed as restoring the **input-policy contract**, not as general
  injection safety (sink-specific encoding remains the caller's job).

**Adopted from review round 2** (all four verified before accepting):
- **Absent `T[]` yields `null`, not an empty array.** Revision 2 wrongly classified array
  absence as spec-silent; the `@DefaultValue` Javadoc's *"`null` for other object types"*
  covers arrays, since `T[]` is not one of the three named interfaces. Today's behavior is
  already conformant, so the plan now *pins* it rather than changing it — a net reduction.
- **`Collections.unmodifiableNavigableSet`**, and `NavigableSet` ordered *before*
  `SortedSet` in the cascade: `unmodifiableSortedSet` returns a `SortedSet` that is not
  assignable to a `NavigableSet` parameter and would fail `Method.invoke`. A mutation-only
  test would not catch this, so an assignability + successful-invocation test was added.
- **Native multipart lists join the read-only guarantee** (the review's one open
  question). One rule beats an exception, and they are `@FormParam` collection targets like
  any other. Scope is smaller than the review assumed: `FILE_UPLOADS` already returns
  `List.copyOf` (`ParameterExtractor.java:293`); only the two native FORM branches
  (`:761-763`, `:766`) and `extractAllEntityParts` (`:807-821`) need wrapping.
- **S6 carries proof obligations** — mechanical (grep + `scripts/verify-module-docs.sh` +
  ADR existence/marker), since the slice ships prose rather than code.

**Rejected, with reason:**
- *Defer `Collection<T>`/`NavigableSet<T>` for FORM as non-portable.* Excluding them
  would cost **more** code than including them: `componentType` resolution already
  accepts both via the shared `isSupportedCollectionRawType`/`isSupportedCollectionFqn`
  policy, and the materializer's `SortedSet`/`Set`/else-`List` cascade handles them
  without a branch. A FORM-only exclusion would need an added special case and would
  reintroduce exactly the QUERY-vs-FORM asymmetry this task exists to remove. They are
  pre-existing accepted shapes on three other sources, not new surface. Documented as
  framework extensions beyond the spec's named set.
- *Extend `BodyFormValidator`* (first opinion) — moot; #155 became support, so no
  shape-rejection validator is needed there. The one new rejection (F13) is a runtime
  `RouteValidator` check, matching where `isSupportedFileUploadTarget` already lives.

**Unresolved:** none. F4 is an open *fact*, not an open decision — S1's red test settles
it mechanically, and S2 is skipped-with-an-amendment if it passes.

## 4. Contract Appendix (frozen)

No new public types. One existing public method widens its accepted input; one public
record changes an invariant; one new violation constant is added.

```java
// dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorSupport  (public API —
// generated descriptor code calls this; signature UNCHANGED, accepted input widened)

/**
 * Resolves a single class by its fully-qualified name using the given class loader.
 *
 * <p>Accepts three forms: a binary reference name ({@code com.example.Outer$Inner}),
 * a Java primitive name ({@code "int"}), and — new — an array type in Java *source*
 * form with one or more trailing {@code []} pairs ({@code "java.lang.String[]"},
 * {@code "byte[]"}, {@code "com.example.Outer$Inner[][]"}). Array FQNs are resolved by
 * counting and stripping the {@code []} pairs and applying
 * {@link java.lang.reflect.Array#newInstance(Class, int...)} to the resolved base type
 * — {@code Class.forName} cannot load the source array form. Reference types are
 * resolved with class initialization enabled, unchanged from before.
 */
public Class<?> resolveClass(String fqn, ClassLoader cl) throws ClassNotFoundException;
```

```java
// dev.vertique.rest.jaxrs.runtime.ArrayFqns  (NEW, package-private — internal
// deduplication of the two array-FQN loaders; the initialize flag preserves each
// caller's existing Class.forName semantics)
final class ArrayFqns {
    /**
     * Resolves a possibly-array type name in Java source form to its {@link Class}.
     *
     * @param fqn        the type name: binary reference name, primitive name, or either
     *                   with one or more trailing {@code []} pairs
     * @param cl         the class loader for reference base types
     * @param initialize whether to initialize the resolved reference base type —
     *                   {@code true} for {@code GeneratedJaxRsDescriptorSupport},
     *                   {@code false} for {@code GeneratedJaxRsReflectiveAnnotations}
     */
    static Class<?> resolve(String fqn, ClassLoader cl, boolean initialize)
            throws ClassNotFoundException;
}
```

```java
// dev.vertique.rest.jaxrs.RouteRegistrationViolation.ViolationType  (public enum —
// one new constant appended)

/**
 * A native multipart target ({@code FileUpload}/{@code EntityPart}) is declared in a
 * collection shape other than {@code List}. Only a scalar target and {@code List<T>}
 * are materialized natively; {@code Set}, {@code SortedSet}, {@code NavigableSet},
 * {@code Collection}, and array shapes have no native materialization and would
 * otherwise fall through to string conversion and fail per-request.
 */
UNSUPPORTED_MULTIPART_COLLECTION_SHAPE
```

One further constant was added during the review rounds, user-signed-off at the time
(Amendment 13). A second, `NON_COMPARABLE_SORTED_SET_ELEMENT`, was added and then removed again —
see Amendment 14:

```java
/**
 * Two parameters of one method bind the same name at the same location but declare incompatible
 * multiplicity — one collection-shaped, one scalar. {@code DefaultBoundRequest.findDescriptor} is
 * first-match, so one descriptor decides multiplicity for both and the other parameter is always
 * mis-bound. Scoped to the locations {@code findDescriptor} serves; FORM is excluded because form
 * extraction reads {@code formAttributes()} directly. Name comparison mirrors the binder:
 * case-insensitive for header and cookie, case-sensitive for path and query. Two same-name
 * parameters with the same multiplicity are redundant but well-defined, and are not rejected.
 */
DUPLICATE_PARAM_NAME_MULTIPLICITY_CONFLICT
```

```java
// dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamMeta  (public record — components
// UNCHANGED; one FORM invariant changes, one explicitly does NOT)
public record ParamMeta(
        String name,
        ParamSource source,
        Class<?> type,              // INVARIANT CHANGE for FORM, see below
        @Nullable Class<?> componentType,
        @Nullable Type genericType, // UNCHANGED — stays null for FORM
        @Nullable String defaultValue,
        ParameterMetadata parameterMetadata) { ... }
```

**FORM invariants — before → after:**

| Slot | Before (reflective) | Before (generated) | After (both) |
|---|---|---|---|
| `type` for a collection-shaped `@FormParam` | normalized to `List.class` | declared type | **declared type** (`List`, `Set`, `SortedSet`, `NavigableSet`, `Collection`, `T[]`) |
| `genericType` for FORM | `null` | `null` | **`null`** — unchanged; `genericType` is contractually BODY-only (`ResourceMethodMeta.java:147`) |
| `componentType` for `T[]` (any bindable source, generated) | `null` (array excluded) | `null` | **element type** when the element is a scalar-array component; `null` for `byte[]`/`char[]`/other primitive arrays (they stay BODY) |

### Frozen behavioral contracts

These are the decisions the executor must not re-open.

1. **Multiplicity trigger stays `componentType != null`** — the single existing gate
   (`DefaultBoundRequest.java:503`). FORM joins it; nothing else changes.

2. **One collection state machine, for every bindable source (QUERY / HEADER / COOKIE /
   FORM) and both dispatch paths.** Given a param with `componentType != null`:

   | Case | Result |
   |---|---|
   | ≥1 value present | each value converted via the element context, then materialized; `@DefaultValue` ignored |
   | 0 values, `@DefaultValue` present | **single-entry** collection holding the converted default |
   | 0 values, no `@DefaultValue` | **empty** collection for `List`/`Set`/`SortedSet`/`NavigableSet`/`Collection` (spec F12; today `null`); **`null`** for `T[]` (spec F12 — an array is an "other object type"; today already `null`) |

   "0 values" means the source reports no entry for the name — for FORM,
   `formAttributes().getAll(name).isEmpty()`, which is why the FORM branch must test
   emptiness *before* materializing (a bare `getAll()` + materialize would turn the
   default case into an empty collection).

3. **Injected collections are read-only** (spec F12). The materializer cascade, in this
   order — `NavigableSet` **before** `SortedSet`, because
   `Collections.unmodifiableSortedSet` returns a `SortedSet` that is **not** assignable
   to a `NavigableSet`-declared parameter and would fail `Method.invoke`:

   | Declared type | Materialized as |
   |---|---|
   | `T[]` | array (no read-only wrapper exists for arrays — see decision 4) |
   | `NavigableSet<T>` | `Collections.unmodifiableNavigableSet(new TreeSet<>(elements))` |
   | `SortedSet<T>` | `Collections.unmodifiableSortedSet(new TreeSet<>(elements))` |
   | `Set<T>` | `Collections.unmodifiableSet(new LinkedHashSet<>(elements))` |
   | `List<T>`, `Collection<T>` | `Collections.unmodifiableList(new ArrayList<>(elements))` |

   **This applies to native multipart collections too**, resolving the one open question
   the review raised: `List<FileUpload>` and `List<EntityPart>` are `@FormParam`
   collection injection targets, so one rule covers them rather than an exception.
   Concretely, the two native FORM branches (`ParameterExtractor.java:761-763`, `:766`)
   and the `ENTITY_PARTS` aggregate (`extractAllEntityParts:807-821`, a mutable
   `ArrayList`) are wrapped. `FILE_UPLOADS` already returns `List.copyOf(...)`
   (`:293`) and needs no change.

4. **Only `@DefaultValue` on an array is framework-decided** (F12 covers array
   *absence* as `null`): `T[]` with 0 values and a `@DefaultValue` → **single-element
   array**, by analogy with the spec's single-entry collection rule. Arrays are
   inherently mutable — no read-only wrapper exists, and none is invented; documented as
   a deliberate asymmetry and labeled a Vertique extension, not a spec guarantee.

5. **Collection elements traverse the input-policy chain per element**, for every
   bindable source, via
   `objectProcessor.processStructuredBody(element, String.class, policies, <source>)`,
   applied *after* conversion and only when the converted element is still a `String` —
   mirroring the scalar rule (`ParameterExtractor.java:397-400`). Defaults are not
   processed, mirroring the scalar rule. This restores the framework's declared
   input-policy contract for collections; it is **not** a substitute for sink-specific
   output encoding.

6. **Native multipart stays `List`-restricted** (F13). Guards match a scalar
   `FileUpload`/`EntityPart` target or `componentType ∈ {FileUpload, EntityPart}` with
   `type == List.class`. Any *other* collection shape carrying a native component type is
   **rejected at startup** with `UNSUPPORTED_MULTIPART_COLLECTION_SHAPE`, aligning
   binding with `RouteValidator.isSupportedFileUploadTarget:145-153`.

7. **Element ordering is "as reported by Vert.x"** and explicitly *not* a framework
   guarantee (F8, F12). Documented; tests assert content, never order.

8. **`byte[]`/`char[]` remain BODY shapes on both paths.** The policy is
   `isScalarArrayComponent`; the runtime implements it over `Class<?>` and the processor
   over `TypeMirror`, so it is shared **by parity test**, not by a single method.

## 5. Class inventory

| Type | Module | Kind | Visibility |
|---|---|---|---|
| `GeneratedJaxRsDescriptorSupport` | vertique-rest-jaxrs | class | **API** (generated code calls it) |
| `ResourceMethodMeta.ParamMeta` | vertique-rest-jaxrs | record | **API** |
| `RouteRegistrationViolation.ViolationType` | vertique-rest-jaxrs | enum | **API** |
| `ArrayFqns` *(new)* | vertique-rest-jaxrs (`…jaxrs.runtime`) | final class | Internal (package-private) |
| `GeneratedJaxRsReflectiveAnnotations` | vertique-rest-jaxrs | class | Internal |
| `ResourceScanner` | vertique-rest-jaxrs | class | Internal (package-private) |
| `ParameterExtractor` | vertique-rest-jaxrs | class | Internal (package-private) |
| `RouteValidator` | vertique-rest-jaxrs | class | Internal |
| `TypeMirrorFqn` | vertique-codegen-jaxrs | final class | Internal (package-private) |
| `EffectiveJaxRsContractResolver` | vertique-codegen-jaxrs | class | Internal |

## 6. Slice plan

Ordered; the tree compiles at every commit. `./mvnw -ntp spotless:apply` before every
Java commit. One slice per `implementer` call; all builds delegated to `build-validator`.

### S0 — persist the plan  *(plan-artifact commit, not implementation)*
Create the worktree inside `sources/vertique`:
`git worktree add .claude/worktrees/issue-153-155-param-shapes -b feat/param-shape-parity main`.
Persist this plan to `docs/plans/feat-param-shape-parity.md` **on the source branch** as
its first commit. Re-check the ADR marker (F11) for concurrent-session drift.
Commit: `docs: add implementation plan for JAX-RS param-shape parity`
**Gate before S1 (not slice work):** `plan-linter` on the persisted file must PASS.

### S1 — array FQNs resolve in generated descriptors  *(critical)*
**Red tests** — new `JaxRsDescriptorArrayParamTest` (vertique-codegen-jaxrs), using
`ProcessorTestHarness` + the `describe()`-invoking helper at `JaxRsDescriptorEmitterTest:30-57`:
- `arrayQueryParam_describe_resolvesArrayType` — given `@QueryParam("tags") String[] tags`,
  when `describe()` runs, then no throw and `ParamMeta.type() == String[].class`.
- `primitiveArrayBody_describe_resolvesByteArray` — given `byte[] body`, then no throw and
  `type() == byte[].class`.
- `multiDimensionalArrayParam_describe_resolves` — given `String[][]`, then
  `type() == String[][].class`.
- `nestedTypeArrayParam_describe_resolves` — given a nested-class element (`Outer.Inner[]`),
  then no throw. **Decides F4**: green from S1 alone ⇒ S2 drops out; still red ⇒ S2 required.
- `arrayFqns_referenceBase_initializationSemanticsPreserved` (unit) — given a base class
  with an observable static initializer, when resolved with `initialize=false` then the
  initializer has not run, and with `initialize=true` then it has (pins F3's flag).
**Green:** add package-private `ArrayFqns.resolve(fqn, cl, initialize)` in
`…rest.jaxrs.runtime`; delegate from `GeneratedJaxRsReflectiveAnnotations.loadClass`
(`initialize=false`) and `GeneratedJaxRsDescriptorSupport.resolveClass` (`initialize=true`).
Commit: `fix(rest-jaxrs): resolve array-typed parameter FQNs in generated descriptors`

### S2 — nested-type array base names  *(critical; conditional on S1's red test)*
**Red test:** `nestedTypeArrayParam_describe_resolves`, still failing.
**Green:** `TypeMirrorFqn.erasedFqn`'s array branch emits the **binary** base name plus
source-form `[]` suffixes (recurse to the component type, reusing the existing
`getBinaryName()` branch) instead of `erased.toString()`.
Commit: `fix(codegen-jaxrs): emit binary base names for array parameter FQNs`
*If S1's test passes, skip and record it in §Amendments — never silently.*

### S3 — `T[]` multi-value on generated dispatch  *(critical)*
**Red tests** — new `GeneratedArrayParamParityTest` (vertique-codegen-jaxrs):
- `scalarArrayComponentPolicy_generatedMatchesReflective` — **parameterized matrix** over
  `String[]`, `Integer[]`, `Long[]`, `Boolean[]`, `Character[]`, an `enum[]`, `int[]`,
  `char[]`, `byte[]`, `short[]`: for each, the generated `componentType` and param source
  equal the reflective scanner's for the same declaration. This is what "shared policy"
  means operationally (F8 / decision 8).
- `arrayQueryParam_generatedAndReflective_bindAllValues` — given `?tags=a&tags=b`, both
  paths yield 2 elements with equal content.
- `arrayHeaderParam_generatedAndReflective_bindAllValues` — same for `@HeaderParam`.
- `byteArrayBody_generatedDispatch_servesBinaryBody` — **e2e**: a codegen'd resource with a
  `byte[]` body, when a binary POST is dispatched, then the resource receives the bytes.
  (Carries the "starts and serves" acceptance criterion that S1 alone cannot prove.)
**Green:** remove the deliberate array exclusion in
`EffectiveJaxRsContractResolver.resolveComponentType:943-957` (and its now-wrong javadoc at
`:925-942`); recognize `ArrayType` and apply the scalar-array-component policy so
`byte[]`/`char[]` stay BODY.
Commit: `feat(codegen-jaxrs): bind T[] repeated query/header params on generated dispatch`

### S4 — the collection state machine  *(critical, security-relevant)*
**Red tests** — new `CollectionParamStateMachineTest` (vertique-rest-jaxrs), parameterized
over QUERY / HEADER / COOKIE where the source allows:
- `absentCollection_withoutDefault_yieldsEmptyCollection` — given no values and no
  `@DefaultValue`, when bound to `List<String>`, then an empty collection (today: `null`).
- `absentCollection_withDefault_yieldsSingleEntry` — given `@DefaultValue("x")` and no
  values, then a one-element collection holding `"x"` (today: HTTP 500).
- `presentCollection_ignoresDefault` — regression guard on the unchanged half.
- `absentArray_withoutDefault_yieldsNull` — given `T[]` with no values and no default,
  then `null` (spec F12; pins today's already-conformant behavior against regression).
- `absentArray_withDefault_yieldsSingleElementArray` — decision 4.
- `boundCollection_isReadOnly` — parameterized over `List`/`Set`/`SortedSet`/
  `NavigableSet`/`Collection`: when mutation is attempted, then
  `UnsupportedOperationException` (today: mutable).
- `boundNavigableSet_isAssignableAndInvocable` — given a parameter declared
  `NavigableSet<String>`, then the bound value `instanceof NavigableSet` **and** the
  resource method invokes successfully. Guards the `unmodifiableSortedSet` trap in
  decision 3, which a mutation-only test would not catch.
- `boundCollection_declaredTypeMaterialized` — `Set`→`Set`, `SortedSet`/`NavigableSet`→
  sorted, `Collection`→`List`, `T[]`→array.
- `collectionElements_traverseInputPolicyChain` — given a processing `objectProcessor` and
  two values, then **every** element is processed (today: none are).
**Green:** in `ParameterExtractor`, extract
`materializeCollection(List<Object> elements, Class<?> declaredType, Class<?> componentType)`
from `coerceCollection:445-456`, returning read-only collections; apply per-element policy
processing inside `coerceCollection`; and route the zero-value case through the state
machine in decision 2 instead of `coerceString(default)`.
Commit: `fix(rest-jaxrs): conform collection param binding to the Jakarta REST contract`

### S5 — multi-value `@FormParam` collections  *(critical)*
**Red tests** — new `FormParamCollectionBindTest` (mirroring `SetAndArrayQueryParamBindTest`),
plus FORM cases in `ResolveComponentTypeRecognizesSetSortedSetAndArrayTest`:
- `formParamList_bindsAllValues` / `formParamSet_bindsAllValuesAsSet` /
  `formParamSortedSet_bindsSorted` / `formParamArray_bindsArray` /
  `formParamCollection_bindsList` — given `a=1&a=2`, each declared shape binds both values
  (today: HTTP 500).
- `formParamCollection_declaredTypePreservedInParamMeta` — `type()` is the declared type and
  `genericType()` stays `null` (today: `List.class`).
- `formParamCollection_absentWithDefault_yieldsSingleEntry` — the emptiness-before-
  materialization requirement in decision 2.
- `formParamCollection_elementsTraverseInputPolicyChain` — no regression vs the FORM scalar rule.
- `nativeMultipartListShapes_unaffected` — `FileUpload`, `EntityPart`, `List<FileUpload>`,
  `List<EntityPart>` still bind natively.
- `nativeMultipartLists_areReadOnly` — given `List<FileUpload>` / `List<EntityPart>` (FORM)
  and the `ENTITY_PARTS` aggregate, when mutation is attempted, then
  `UnsupportedOperationException` (decision 3; today mutable). `FILE_UPLOADS` is asserted
  read-only too, pinning its existing `List.copyOf` behavior.
- `nativeMultipartNonListShape_rejectedAtStartup` — given `@FormParam Set<FileUpload>`, when
  the router is created, then `RouteRegistrationException` naming
  `UNSUPPORTED_MULTIPART_COLLECTION_SHAPE` (decision 6).
- `formParamSet_generatedAndReflective_agree` — parity; guards the F5 latent CCE.
**Green:** drop the `List.class` normalization in `ResourceScanner.java:440-454` (keep
`genericType = null`); key the native guards in `extractFormParam:739-780` on
`componentType` **with** `type == List.class`; add the startup rejection in `RouteValidator`
+ the new `ViolationType`; add a collection branch reading
`ctx.request().formAttributes().getAll(pm.name())` that tests emptiness first, then converts,
policy-processes, and materializes via S4's helper.
Commit: `feat(rest-jaxrs): bind multi-value @FormParam collections`

### S6 — ADR + docs  *(routine)*
ADR **0190** (§7) in the meta repo; the doc corrections in §8; bump the stale
`adr/product/README.md` marker to 0191.
**Proof obligations** (mechanical, not JUnit — this slice ships prose):
- `grep -rn 'binds only the first value' .` over both repos returns nothing (F10's stale
  claim gone).
- `grep -n 'List<T>/Set<T>/T\[\]' vertique-rest/vertique-rest-jaxrs/src/main/resources/META-INF/vertique/module.md`
  — the `:231` coercion claim now scopes itself correctly instead of implying FORM.
- `scripts/verify-module-docs.sh` exits 0 (BOM/document/index parity, packaged-link
  containment, no legacy paths).
- `adr/product/0191-jaxrs-collection-parameter-binding.md` exists with
  `Status: Accepted`, and `adr/product/README.md` says "Next ADR number: 0191".
Commits: `docs: add ADR 0191 …` / `docs(rest-jaxrs,codegen-jaxrs): correct param-shape documentation`

## 7. ADRs to write

**ADR-0191 — JAX-RS collection parameter binding model** (S6; decisions frozen in §4).
Records: the `componentType != null` multiplicity gate as the single trigger across all
bindable sources; the absent/default/present state machine and read-only materialization as
Jakarta REST 4.0 conformance (F12), including the two places the framework deliberately
**extends** the spec (`Collection<T>`/`NavigableSet<T>`) and the two it **decides** where the
spec is silent (array absence/default, array mutability); declared-type preservation for FORM
and why codegen never needed it; array FQN *source* form as the descriptor wire format plus
the `Array.newInstance` resolution rule and its `initialize` asymmetry; the
`byte[]`/`char[]`-stay-BODY carve-out proven by parity test; per-element input-policy
processing as a contract restoration; the `List`-only native multipart restriction; and
ordering as explicitly unspecified (F8).

One ADR, not six: these are facets of one binding model, and splitting them would scatter
rationale a future reader needs together.

## 8. Artifact manifest

Paths are repo-relative. Code repo = `sources/vertique`; meta repo = `vertique-dev`.

**New — code repo**
- `vertique-rest/vertique-rest-jaxrs/src/main/java/dev/vertique/rest/jaxrs/runtime/ArrayFqns.java`
- `vertique-rest/vertique-rest-jaxrs/src/test/java/dev/vertique/rest/jaxrs/runtime/ArrayFqnsTest.java`
- `vertique-rest/vertique-rest-jaxrs/src/test/java/dev/vertique/rest/jaxrs/CollectionParamStateMachineTest.java`
- `vertique-rest/vertique-rest-jaxrs/src/test/java/dev/vertique/rest/jaxrs/FormParamCollectionBindTest.java`
- `vertique-codegen/vertique-codegen-jaxrs/src/test/java/dev/vertique/codegen/jaxrs/JaxRsDescriptorArrayParamTest.java`
- `vertique-codegen/vertique-codegen-jaxrs/src/test/java/dev/vertique/codegen/jaxrs/GeneratedArrayParamParityTest.java`
- `docs/plans/feat-param-shape-parity.md` *(removed in the final docs commit)*

**New — meta repo**
- `adr/product/0191-jaxrs-collection-parameter-binding.md`

**Modified — code repo**
- `vertique-rest/vertique-rest-jaxrs/src/main/java/dev/vertique/rest/jaxrs/runtime/GeneratedJaxRsDescriptorSupport.java`
- `vertique-rest/vertique-rest-jaxrs/src/main/java/dev/vertique/rest/jaxrs/runtime/GeneratedJaxRsReflectiveAnnotations.java`
- `vertique-rest/vertique-rest-jaxrs/src/main/java/dev/vertique/rest/jaxrs/ResourceScanner.java`
- `vertique-rest/vertique-rest-jaxrs/src/main/java/dev/vertique/rest/jaxrs/ParameterExtractor.java`
- `vertique-rest/vertique-rest-jaxrs/src/main/java/dev/vertique/rest/jaxrs/RouteValidator.java`
- `vertique-rest/vertique-rest-jaxrs/src/main/java/dev/vertique/rest/jaxrs/RouteRegistrationViolation.java`
- `vertique-codegen/vertique-codegen-jaxrs/src/main/java/dev/vertique/codegen/jaxrs/EffectiveJaxRsContractResolver.java`
- `vertique-codegen/vertique-codegen-jaxrs/src/main/java/dev/vertique/codegen/jaxrs/emit/TypeMirrorFqn.java` *(S2, conditional)*
- `vertique-rest/vertique-rest-jaxrs/src/test/java/dev/vertique/rest/jaxrs/ResolveComponentTypeRecognizesSetSortedSetAndArrayTest.java`
- `vertique-rest/vertique-rest-jaxrs/src/test/java/dev/vertique/rest/jaxrs/RouteStartupValidationTest.java` *(pin bean-param collection loudness, F9)*

**Modified — meta repo**
- `adr/product/README.md` *(stale next-number marker, F11)*

**Module documentation** (same-slice contract; F10 makes both **corrections**)
- `vertique-rest/vertique-rest-jaxrs/src/main/resources/META-INF/vertique/module.md` —
  shapes table row 5 (FORM collections), the collection-coercion claim at `:231`, the new
  violation type, the collection state machine + read-only contract, input-policy scope,
  `List`-only native multipart, ADR-0191 reference.
- `vertique-codegen/vertique-codegen-jaxrs/src/main/resources/META-INF/vertique/module.md` —
  remove the false `T[]` "Known Gaps" entry (line 287), note `T[]` supported, ADR-0191 reference.
- `vertique-rest-validation`: **no documentation impact** — `WebValidationStrategy` is read
  as-is; no behavior or contract change in that module.
- `vertique-rest-core`: **no documentation impact** — `ConversionContexts` and the converter
  registry are unchanged; only callers change.
- `vertique-codegen-core`: **no documentation impact** — no new `Diagnostics` wording; the one
  new rejection is a runtime violation.

**Deleted**: none. `docs/modules.md` unchanged — no artifact inventory or canonical-path change.

## 9. Risks & edge cases

- **F4 is unverified.** S1's nested-array red test is the decision procedure; skipping S2 is
  recorded in §Amendments, never silent.
- **Read-only collections are the highest-blast-radius change.** Any existing code — framework
  or test — that mutates an injected collection begins throwing
  `UnsupportedOperationException`. Mitigation: the full-project `verify` is the detector, and
  S4 lands before the FORM work so failures are attributable.
- **Removing the FORM `List` normalization is the riskiest single edit**: the native multipart
  guards currently *depend* on it (a declared `Set<FileUpload>` reaches guard 3 today only
  because `type` was rewritten). Re-keying and the new rejection must land in the **same
  commit**, with `RouteStartupValidationTest.NativeMultipartResource` and
  `JaxRsRouteRegistrarTest:526-585` as the net.
- **Consumer-visible behavior changes**, all decided in §4, all strict improvements or spec
  conformance: `@FormParam` collections 500 → work; collection `@DefaultValue` 500 → single
  entry; absent `List`/`Set`/`SortedSet`/`NavigableSet`/`Collection` `null` → empty
  (absent `T[]` **stays** `null` — already conformant); injected collections, including
  native multipart lists, mutable → read-only; collection elements now traverse the
  input-policy chain (a request that previously smuggled a payload through an element will
  have it processed — the point of the fix); non-`List` native multipart shapes now rejected
  at startup; generated FORM `ParamMeta.type()` becomes the declared type. Pre-0.1.0, no
  external consumers.
- **Input-policy processing is a security surface** — S4/S5 are `critical` tier and must go
  through `/security-review`, not only the Codex loop. Element processing restores the
  framework's input-policy contract; it does not replace sink-specific output encoding.
- **No ordering guarantee** is added (F8, F12). Tests assert content, never element order, or
  they are flaky by construction.
- **Processor-harness limits**: `ProcessorTestHarness` may not reproduce app-server
  classloader boundaries or inaccessible nested user types; S1's nested case covers the
  compiler-visible half only.
- **Cross-worktree build contamination**: a concurrent session is active. Serialize Maven per
  worktree, never `install`, and never overlap a Codex review of this worktree with a build in
  it (handoff ruling 4).
- **IT-touching**: S3's e2e means the change touches integration-style tests — per
  `testing.md`, prove determinism with repeated local runs before review.

## 10. Verification

Per slice: `./mvnw -ntp -pl <module> -am verify` on the touched module, delegated to
`build-validator`. Full gate before reviews: `./mvnw -ntp clean verify` on the whole project
(both `vertique-rest-*` and `vertique-codegen-*` must run — the parity tests straddle them).

Mechanical completeness checks:
- `grep -n 'List.class' …/ResourceScanner.java` — no FORM normalization remains.
- `grep -n 'instanceof ArrayType' vertique-codegen/vertique-codegen-jaxrs/src/main/java/dev/vertique/codegen/jaxrs/emit/TypeMirrorFqn.java`
  — present if S2 ran. (Amendment 4: the original check asserted `erased.toString()` was
  *gone*, which is wrong — the primitive/`void`/other-non-declared fallback legitimately
  keeps it; only the array path stopped reaching it.)
- `grep -n 'erasedSourceFqn' vertique-codegen/vertique-codegen-jaxrs/src/main/java/dev/vertique/codegen/jaxrs/emit/*.java`
  — both emitters' `sourceTypeName` fallbacks use the source-form renderer, not the binary
  one, so generated `TypeReference` literals stay compilable (Amendment 3).
- `grep -rn 'binds only the first value' .` — the stale doc claim (F10) is gone.
- `grep -rn 'Class.forName' …/rest/jaxrs/runtime/` — every call site is array-aware or
  provably non-array.
- Every collection returned to a resource method is wrapped read-only: inspect each
  `return` in `ParameterExtractor` reachable from `extractParamValue` — the materializer,
  both native FORM branches, and `extractAllEntityParts` wrap; `FILE_UPLOADS` already uses
  `List.copyOf`. `grep -n 'unmodifiable\|List.copyOf' …/ParameterExtractor.java` enumerates
  them for the reviewer.
- `grep -n 'unmodifiableNavigableSet' …/ParameterExtractor.java` — the `NavigableSet`
  branch exists and precedes the `SortedSet` branch.

Acceptance, criterion by criterion:
- **#153** — a codegen'd resource with `String[]`/`Integer[]` query/header params binds all
  values identically to reflective dispatch (S3 matrix + parity); a codegen'd resource with a
  `byte[]` body **starts** (S1 descriptor test) and its generated **execution plan extracts
  and passes the bytes** (S3 regression pin, at `ExecutionPlan` level).
  *Amendment 6, user-approved:* the criterion originally said "and serves (S3 e2e)". A true
  HTTP-transport e2e is not constructible in `vertique-codegen-jaxrs` — `registerAll` needs
  ~20 collaborators absent from its test scope, and no server-bearing module carries the
  processor on its `annotationProcessorPaths`, so there is no running consumer to drive. Every
  `byte[]`-specific link *is* proven (descriptor resolution, array class-literal emission,
  BODY extraction — the last already covered reflectively by `AnnotationDrivenRoutingIT:429`
  over shared code). The unproven seam is generated-descriptor-plus-real-server, a
  pre-existing gap affecting every generated shape, routed to an issue in §11.
- **#155** — `@FormParam` `List`/`Set`/`SortedSet`/`NavigableSet`/`Collection`/`T[]` bind all
  submitted values into the declared type on both paths (S5); native `List` multipart shapes
  unchanged; non-`List` native shapes rejected at startup.
- **F6** — validation and extraction agree for FORM collections.
- **F7** — collection elements traverse the input-policy chain; collection `@DefaultValue`
  no longer 500s.
- **F12** — absent collections are empty (absent arrays stay `null`), defaults yield single
  entries, and injected collections — including native multipart lists — are read-only and
  preserve declared-type assignability (`NavigableSet` included), on every bindable source.
- **F9** — bean-param collection fields still fail loudly at startup (pinned by test).

Reviews: `/security-review` + `codex-reviewer` in parallel after the full build, then the Codex
loop to convergence. Expect multiple rounds — S4/S5 touch a security surface and a dual-path
contract.

## 11. Out of scope & deferral routing

Each item routes to a GitHub issue in `vertiquehq/vertique-dev` (no backing PRD), filed
**pre-merge** after the user sees the table (handoff ruling 5).

| Item | Why deferred | Follow-up |
|---|---|---|
| Bean-param / `@RequestParams` collection **fields** (`componentType` always `null`; `BeanParamModelEmitter`'s `TODO(CG-010-later)`) — broken for QUERY *and* FORM | Fails **loudly** at startup today (F9), so no silent defect; needs `componentType` resolution in both the reflective field resolver and the bean-param emitter — strictly larger than top-level binding | Issue: "Support collection-typed @BeanParam fields (query + form)" |
| A framework-level element **ordering guarantee** for multi-value params | Neither Vert.x nor Jakarta REST documents one (F8, F12); promising one would pin one version's observed behavior | Issue: "Decide whether multi-value param ordering is a framework guarantee" |
| Native multipart in non-`List` collection shapes (`Set<FileUpload>` …) | **Not deferred silently** — now rejected loudly at startup (§4 decision 6). Promoting it to supported would need native materialization, `@FilePart` validation, and security tests | Issue: "Decide whether non-List multipart collection shapes become supported" |
| Reflective QUERY/HEADER/COOKIE populate `genericType` although the record documents it as BODY-only | Pre-existing inconsistency, untouched by this change; FORM deliberately does **not** join it | Issue: "Reconcile ParamMeta.genericType contract with non-BODY sources" |
| **HTTP-transport e2e coverage for generated dispatch** (any shape, not just `byte[]`) | Not constructible in `vertique-codegen-jaxrs`; needs an invoker project under `vertique-codegen-integration-tests` that runs the processor and boots a server. Pre-existing gap, not introduced here (Amendment 6, user-approved) | Issue: "Add HTTP-transport e2e coverage for generated JAX-RS dispatch" |
| **BODY `componentType` diverges between paths** — the codegen resolver computes it source-independently while `ResourceScanner:474-481` hard-codes `null` for BODY, so a BODY `List<Foo>` already carries `componentType=Foo` generated vs `null` reflectively. S3 extends this to `String[]`/boxed-array bodies (primitive-array bodies stay `null`, so `byte[]` is unaffected) | Pre-existing and audited **inert**: `ResourceMethodMetaToDescriptorAdapter.mapParameters` skips non-bindable sources so no `ParamDescriptor` is produced and `wrapValues` never sees it; `mapFileParts`/`RouteValidator` only compare against `FileUpload`/`EntityPart`; both BODY paths pass only `type()`/`genericType()` into `deserializeBody`. Source-gating it would also change BODY *collection* `componentType` — a consumer-visible change to the public `ParamMeta` record that §4 does not decide | Issue: "Reconcile BODY componentType between generated and reflective paths" |
| **Generated `loadClass` resolution order** — now TCCL-first with the plan's own loader as fallback. Own-loader-*first* is arguably more correct, since the plan is compiled into the same artifact as the resource and multi-classloader deployments can resolve one FQN to two different `Class` objects | The S3 fix chose minimal blast radius: TCCL-first preserves resolution for everything that worked before. Reordering is a production-semantics change with no test pinning it | Issue: "Decide classloader precedence in generated loadClass helpers" |
| **Null converted `@DefaultValue` element** — `absentCollectionValue` wraps the converted default in `singletonList`; a converter returning `null` NPEs inside `new TreeSet<>(...)` | Design decision: the outcome is a 500 either way, and choosing the semantics for a null converted default (`[null]`, empty, or reject) is a policy §4 does not make. The same hazard exists for a converter returning null on a *present* value, so a real fix belongs in the materializer under a decided policy | Issue: "Decide the contract for a ParamConverter returning null for a collection element or @DefaultValue" |
| **Scalar policy ordering across sources** — FORM scalars process the raw value pre-conversion; QUERY/HEADER/COOKIE scalars process post-conversion and only while still a `String`, so non-`String` scalar targets skip the chain there | Out of scope: unifying it changes shipped behavior for every non-`String` scalar param on those three sources, on both dispatch paths — well beyond param-shape parity. Round 1 fixed only the *collection* half, per source | Issue: "Decide whether input policies run before or after scalar conversion, uniformly across sources" |
| **`@PathParam` collection shapes** | Out of scope: needs multi-value path extraction that `RoutingContext.pathParams()` (a `Map<String,String>`) does not expose. §4 decision 2 and Amendment 9 scope PATH out deliberately, and after F1 the limitation fails loudly on **both** paths rather than one | Issue: "Decide whether @PathParam collection shapes become supported (repeated path templates)" |
| **No compile-time mirror for either shape rule** — neither `UNSUPPORTED_MULTIPART_COLLECTION_SHAPE` nor `NON_COMPARABLE_SORTED_SET_ELEMENT` has a processor-side diagnostic | Both are runtime `RouteValidator` checks; the codegen module has no mirror for either. Adding one is a separate slice covering both rules together, not half of one | Issue: "Mirror the FORM shape rules as compile-time diagnostics" |
| **Inner class of a parameterized owner** — `List<Outer<String>.Inner>` is a `ParameterizedType` reflectively (empty args, parameterized owner) so the reflective scanner resolves no element type, while the codegen mirror accepts it | Near-zero reachability: a non-static member class of a generic outer, used as a String-convertible collection element. Two-line fix plus matrix rows if it ever matters | Issue: "Mirror inner-classes-of-parameterized-owners in the codegen element-type gate" |
| **RFC 6265 cookie names are case-sensitive** while the framework now matches them case-insensitively on both halves, so `@CookieParam("session")` is satisfiable by a wire cookie named `Session`, and two cookies differing only in case collapse (last wins) | Pre-existing in the map keying; the security fix only made the descriptor half agree with it, which is what was actually broken. Changing the semantic is a separate decision | Issue: "Decide whether cookie name matching should be case-sensitive per RFC 6265" |
| **`raw.toString()` double-conversion** in the multiplicity fallback, lossy for a converter whose round-trip is not `toString`-symmetric | Its only legal trigger was the duplicate-name declaration now rejected at startup; the remaining triggers are a custom `BoundRequest`, a codegen divergence, or path collection support | Issue: "Avoid toString round-tripping in the collection multiplicity fallback" |
| **Top-level collection param colliding with a `@BeanParam` field of the same name** — same mis-bind shape as the rejected duplicate, but bean fields are not in `meta.params()` and hard-code a null component type, so the new guard cannot see it | Belongs with the already-routed bean-param collection-field work rather than here | Folded into: "Support collection-typed @BeanParam fields (query + form)" |
| **Startup rejection for a non-self-comparable sorted-set element** | Removed after five review rounds (Amendment 14): every attempt either refused legitimate shapes or missed unsafe ones, and a correct version needs type-variable substitution machinery that does not exist in-tree | Issue: "Decide whether a non-self-comparable SortedSet element should be rejected at startup" |
| **Runtime-side proof that a generated BODY descriptor registers cleanly** — the only such assertion in `vertique-rest-jaxrs` lived in a test framed around the removed guard and went with it. The codegen side of the divergence is still covered | Small coverage residual on an already-routed divergence, not a defect | Folded into: "Reconcile BODY componentType between generated and reflective paths" |
| Legacy tracker hygiene | Post-merge per handoff ruling 2 | Close legacy #153 and #155 with pointers once merged |

## Review round 1 — outcomes

Codex review + `triage` adjudication on HEAD `25fce8f`, after a green full reactor
(21,666 tests). Eleven findings; one Critical was a regression this branch introduced.

| # | Finding | Verdict | Landed in |
|---|---|---|---|
| F1 | Generated `componentType` not source-gated → codegen `@PathParam String[]` registers and 500s per request where reflective rejects at startup | **fix now** (Critical) | `da942f0` |
| F6 | Parity matrix had no source dimension — 10 rows, all query/body; F1's missing proof | **fix now** | `da942f0` (19 rows) |
| F3 | `CollectionParamStateMachineTest`'s cookie row asserted a `BoundRequest` shape the real binder never produces — a test that could not fail | **fix now** | `7c0538d` |
| F2 | `bindCookies` always wrapped a scalar, so a *present* `@CookieParam` collection 500d while absence was conformant and the doc claimed support | **fix now** | `7c0538d` |
| F9 | Absence coverage was `List` + array only; the "default is not policy-processed" guarantee had no test | **fix now** | `7c0538d` |
| F4 | FORM elements processed post-conversion while FORM *scalars* process the raw value pre-conversion — asymmetry inside one source | **fix now**, user-signed-off (§4 decision 5) | `8b77008` |
| F5 | `SortedSet`/`NavigableSet` of a non-`Comparable` element type passed startup and threw `ClassCastException` per request; javadoc overclaimed | **fix now**, user-signed-off (new `ViolationType`) | `8b77008` |
| F7 | Comment claimed bounded type variables are not scalar elements; erasure makes them so | **fix now** (comment) | this commit |
| F8 | `ArrayFqns.resolve` documented only `ClassNotFoundException`, can throw `IllegalArgumentException` | **fix now** (javadoc) | this commit |
| F10 | `@FilePart @FormParam Set<FileUpload>` yields two violations | **false positive** — two independent defects, both accurate, both fail startup; the "reported once" javadoc is scoped to the converter probe in the same sentence | — |
| F11 | `module.md` shapes table: only the `@FormParam` row lists collection shapes; `GeneratedFormParamParityTest` covers only `Set<String>` | **fix now** | docs pass (pending) |

Three new deferrals were routed to §11 by the adjudication: the null converted
`@DefaultValue` element, scalar policy ordering across sources, and `@PathParam`
collection support.

## Review rounds 2–3 and the security pass — outcomes

| Round | Finding | Verdict | Landed in |
|---|---|---|---|
| Security | **MEDIUM** — `findDescriptor` matched header/cookie names case-sensitively while the extractor lower-cases them, so a collection-shaped `@HeaderParam` bound as a scalar and 500d. RFC 9113 mandates lowercase header names on HTTP/2, so it was broken for **every** HTTP/2 client while passing HTTP/1.1 tests | fix now | `edcf5a1` |
| Security | **LOW** — the `Comparable` guard tested raw assignability, so `Comparable<OtherType>` slipped through | fix now | `edcf5a1` |
| Security | **LOW** — generated resolver accepted wildcard/type-variable collection elements where reflective rejects them. The obvious mirror (accept only `DeclaredType`) is **wrong** — reflection reifies `List<Inner[]>`'s argument as a plain `Class`, so it reverses the divergence | fix now | `edcf5a1` |
| Security | **INFO** — schema validation observes raw transport values; the input-policy chain runs afterwards | documented | `edcf5a1` |
| Codex 2 | **CRITICAL** — the sorted-set guard was source-blind and the generated path resolves a component type for body params, so a codegen'd `SortedSet<Pojo>` body failed startup while the reflective twin mounted | fix now | `e86afe7` |
| Codex 2 | **WARNING** — three cookie tests could not fail: the defence-in-depth fallback reconstructed the asserted value, so reverting the binder left them green | fix now | `e86afe7` |
| Codex 2 | **WARNING** — that fallback masked binder/metadata disagreement, the very class of defect that made the header-casing bug discoverable | fix now (WARN) | `e86afe7` |
| Codex 2 | **WARNING** — `isSelfComparable` failed open on a forwarded type argument | fix now | `e86afe7` |
| Codex 3 | **CRITICAL** — the round-2 narrowing **over-rejected** working element types (forwarded-self, the self-bounded idiom), breaking startup for a functioning app. Erasure goes to the leftmost bound, not to "unknown" | fix now | `ae33fd5` |
| Codex 3 | **CRITICAL** — a round-2 test asserted a **safe** shape must be refused: its fixture's unbounded type variable erases to `compareTo(Object)`, so no bridge and no cast are generated | fix now | `ae33fd5` |
| Codex 3 | **WARNING** — the body justification was factually wrong; Jackson's concrete type for `SortedSet` is `TreeSet`, so a non-comparable body element does fail per request. Scoping still right, reason was not | fix now | `ae33fd5` |
| Codex 3 | **WARNING** — the multiplicity WARN fired per request on static metadata and was unasserted | fix now | `ae33fd5` |
| Codex 3 | escalated deferral — `findDescriptor` is first-match, so same-name params with conflicting multiplicity have no correct binding | fix now, user-signed-off | `25ad7fd` |
| Codex 1 | **F10** — two violations for `@FilePart @FormParam Set<FileUpload>` | false positive; **Codex conceded in round 2** | — |

Method note worth keeping: the sorted-set reasoning defeated inspection twice — once in the
round-2 fix and once in the test that ratified it. Round 3 settled it by *executing* a shape
matrix against real `TreeSet` behavior, and that matrix is now in-tree asserting both the runtime
truth and the validator verdict per row, so a stale row fails rather than misleading.

## Amendments

Entries 1–5, 7–9 and 12 are as-built notes and corrections of verified facts, so they neither
re-freeze a contract nor change scope — no sign-off required (`planning.md` § Mid-flight
amendments). **Entry 6 reduces scope and was user-approved before execution continued.**
The Contract Appendix (§4) is untouched throughout.

1. **2026-07-28 — S1 test sequencing (as-built).** The plan listed
   `arrayFqns_referenceBase_initializationSemanticsPreserved` / `ArrayFqnsTest` among S1's
   *red* tests, but that test targets `ArrayFqns`, which does not exist before the green
   step — including it in the red commit would have broken compilation, violating the
   buildable-slice rule. The behavioral red proof is the four `JaxRsDescriptorArrayParamTest`
   cases (commit `63c4972`, all four confirmed red with the predicted
   `IllegalStateException`/`ClassNotFoundException`); `ArrayFqnsTest` landed with the green
   commit (`35f1367`). No coverage lost.

2. **2026-07-28 — F4 resolved: real, S2 ran (verified fact).** After S1, three of four
   descriptor tests passed and `nestedTypeArrayParam_describe_resolves` still failed with
   `ClassNotFoundException: dev.vertique.test.Outer.Inner`. S2 was therefore required and
   executed (`a9a8168`) — it was **not** skipped, so S2's conditional-skip branch never
   applied.

3. **2026-07-28 — S2 manifest expanded (correction of a verified fact).** Making
   `TypeMirrorFqn.erasedFqn` emit binary base names for arrays would have broken a second,
   unrelated consumer: both emitters' `sourceTypeName` fall back to `erasedFqn` for
   non-declared mirrors, and that string is interpolated into generated Java *source* as a
   Jackson `TypeReference` literal, where `$` is a compile error (a `List<Outer.Inner[]>`
   body param would have emitted uncompilable code). `erasedSourceFqn` was added for those
   positions, preserving the previous rendering exactly. **Added to §8 Modified:**
   `vertique-codegen/vertique-codegen-jaxrs/src/main/java/dev/vertique/codegen/jaxrs/emit/JaxRsDescriptorEmitter.java`
   and `.../emit/ExecutionPlanEmitter.java`. Both are internal to `vertique-codegen-jaxrs`,
   already a documented module in §8, so no module-doc decision changes.

4. **2026-07-28 — §10 mechanical check corrected (correction of a verified fact).** The
   `erased.toString()`-is-gone check was wrong; see the replacement checks in §10.

5. **2026-07-28 — build note (as-built).** Omitting `-am` in this worktree resolves a stale
   `vertique-rest-jaxrs` SNAPSHOT from the shared `~/.m2` and produces spurious failures —
   the cross-worktree contamination §9 warns about. Every Maven invocation in this branch
   uses `-am`.

6. **2026-07-28 — #153 acceptance criterion narrowed (USER-APPROVED; scope reduction).**
   Unlike amendments 1–5 this one reduces scope, so it was signed off before execution
   continued. The `byte[]`-body "and serves" criterion is discharged at `ExecutionPlan` level
   rather than over HTTP; see §10 for the reasoning and §11 for the routed coverage gap. Two
   further findings from S3 execution were routed to §11 at the same time: the pre-existing
   BODY `componentType` divergence (audited inert; not source-gated because that would change
   BODY collection `componentType`, a public-record change §4 does not decide) and the
   generated `loadClass` classloader precedence question.

7. **2026-07-28 — S3 adjacent defect fixed in-slice (as-built).** Making `componentType`
   non-null for arrays exposed a real defect in the emitted `loadClass` helper: it resolved
   only through the thread context classloader, so a component type nested inside the resource
   failed at plan class-init. Not test-only — the hello example already emits a nested BODY
   type through the same helper. Fixed additively in `cfbb05d` per the Adjacent Defects Rule.
   **Added to §8 Modified:**
   `vertique-codegen/vertique-codegen-jaxrs/src/main/java/dev/vertique/codegen/jaxrs/EffectiveParamContract.java`
   (javadoc only, widened `componentType` contract); `emit/ExecutionPlanEmitter.java` was
   already added by amendment 3.
8. **2026-07-28 — ADR renumbered 0190 → 0191 (correction of a verified fact).** A concurrent
   session on this machine claimed `0190` for
   `0190-service-client-companion-selection-and-create-time-fail-fast.md` (CG-015) while this
   branch was in flight — the collision that memory `adr-number-collisions-parallel-sessions`
   predicts. This plan's ADR is **0191**, and the renumber covered the source javadoc references
   in `ParameterExtractor`, `ResourceScanner`, and `RouteValidator` as well as this plan file, not
   just the ADR filename. The `adr/product/README.md` marker was stale at `0189` and is now
   `0192`; a missing `0189` index row was backfilled in the same edit.

9. **2026-07-28 — §4 decision 2's source list is right; a doc instruction was not (as-built).**
   While correcting `vertique-rest-jaxrs`'s `module.md` it emerged that `@PathParam` never resolves
   a component type at all (`ResourceScanner:397-406` does not call `resolveComponentType`), so the
   collection state machine covers QUERY/HEADER/COOKIE/FORM only. §4 decision 2 and the S4 tests
   already scoped it that way; the error was in a delegated instruction that said "path/query/
   header/cookie/form", and it was caught rather than propagated into the doc.
10. **2026-07-29 — §4 decision 5 refined per source (USER-APPROVED).** Element policy processing is
   positioned per source: FORM processes the raw value before conversion, mirroring its own scalar
   rule; the other three keep post-conversion, String-only. Decision 5's original text cited the
   QUERY scalar rule for all sources, which left FORM under-specified.

11. **2026-07-29 — `NON_COMPARABLE_SORTED_SET_ELEMENT` added (USER-APPROVED).** New public
   `ViolationType`; see the appendix. Its predicate was replaced twice before settling — see the
   review-rounds table for why, and prefer the executed shape matrix over reasoning when changing it.

12. **2026-07-29 — the sorted-set guard is source-scoped (as-built).** It applies to the sources that
   can carry a non-null `componentType` and are materialized element-wise. A body parameter's
   validity belongs to the selected `RequestBodyDecoder`; PATH is safe only because both paths
   guarantee a null component type for it, and the guard's javadoc records that implementing path
   collection support must add PATH explicitly.

13. **2026-07-29 — `DUPLICATE_PARAM_NAME_MULTIPLICITY_CONFLICT` added (USER-APPROVED; consumer-visible).**
   Rejects at startup a declaration that has no correct binding. An application that mounts today
   stops booting; the repo was grepped and no in-tree declaration is newly rejected.
14. **2026-07-29 — `NON_COMPARABLE_SORTED_SET_ELEMENT` REMOVED (USER-APPROVED; scope reduction).**
   The guard failed five review rounds. It over-rejected four ordinary shapes that a real `TreeSet`
   orders fine — so applications booting on `main` would have stopped booting, a new consumer-visible
   break — while still under-rejecting two shapes that reach `ClassCastException`. Deciding every case
   needs generic type-variable substitution machinery that does not exist in-tree, and the finding it
   addressed was the lowest-severity item in the security review. Removed in `8b191f4`, leaving the
   codebase exactly as safe as `main`. The surviving half is the honest documentation: a sorted shape
   with a non-self-comparable element type throws at request time, is not checked at startup, and is
   the application's responsibility. **Method note:** the predicate was rewritten three times and each
   version was falsified by *execution*, never by inspection. When a check's correctness depends on
   Java erasure subtleties, build the executed shape matrix before the check — and be willing to
   conclude the check is not worth its cost.

