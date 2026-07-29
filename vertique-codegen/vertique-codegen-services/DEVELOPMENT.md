<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Developing the Codegen Service Contract Module

> **Audience:** Vertique framework contributors and source agents
> **Public contract:** `src/main/resources/META-INF/vertique/module.md`

This document covers the annotation-processor internals of `vertique-codegen-services`: how
`ServiceContractProcessor` scans, classifies, validates, and emits — the machinery an application
developer never touches directly. The public contract describes what gets generated and what fails
the build; this document describes how that happens and which invariants a contributor must
preserve when changing it.

---

## Source Map

| Package | Contents |
|---------|----------|
| `dev.vertique.codegen.services.processor` | `ServiceContractProcessor` (the `AbstractProcessor` entry point) and `ServiceAnnotations` (FQN string constants for every framework type the processor references, used instead of class literals so the processor jar has no compile-time dependency on `vertique-services`/`vertique-core`/`vertique-security`) |
| `dev.vertique.codegen.services.processor.scan` | The extraction pipeline: `ImplCandidateScanner`/`ImplCandidate` classify root-element impls; `DirectImplExtractor`/`HandlerImplExtractor`/`ClientContractExtractor` turn a candidate (or a bare contract interface) into a `ContractModel`/`ClientContractModel`; `OperationModel`/`ParamModel` are the shared per-operation shape; `MethodExtraction` holds return-type-unwrapping and public-method-enumeration helpers shared by all three extractors; `AptOperationIdResolver`/`AptParamClassifier` are APT-side replicas of the runtime resolution logic (see [Generated and Hand-Written Boundaries](#generated-and-hand-written-boundaries)) |
| `dev.vertique.codegen.services.processor.validate` | One stateless validator class per structural rule — nine per-impl validators plus two per-contract-group validators (CG-011). Full catalog and runtime-parity mapping in [Generated and Hand-Written Boundaries](#generated-and-hand-written-boundaries) |
| `dev.vertique.codegen.services.processor.emit` | `ContributorEmitter`, `ContributorModuleEmitter`, `ClientProxyEmitter` — the three JavaPoet-based source emitters |

**Cross-module collaborators (owned by `vertique-codegen-core`, consumed here — not this module's to
document in full):**

| Type | Used by | Role |
|------|---------|------|
| `dev.vertique.codegen.CodegenContext` | everywhere | Shared `Elements`/`Types`/`Filer`/`Messager` + helper facade (`typeResolver()`, `annotations()`, `diagnostics()`, `packageNameOf(...)`, `asTypeElement(...)`) |
| `dev.vertique.codegen.AnnotationMirrors` | scan, validate | Mirror-based annotation presence/attribute reads (handles `@Repeatable` containers correctly, unlike `Element.getAnnotation()`) |
| `dev.vertique.codegen.Conditions` | `ContributorEmitter` | Reads `@ConditionalOnProperty`/`@ConditionalOnProperties` mirrors and emits `PropertyCondition[]` array initializers. **Not** in this module's own `scan`/`emit` packages — it is shared with `GeneratedJaxRsResourcesModuleEmitter` in `vertique-codegen-jaxrs` so both codegen modules construct `PropertyCondition` identically |
| `dev.vertique.codegen.validate.InjectConstructorValidator` | `ServiceContractProcessor` | Enforces exactly one `@Inject` constructor (0 or >1 → `ERROR`); internally calls `dev.vertique.codegen.Constructors.findInjectConstructors(TypeElement)` |
| `dev.vertique.codegen.support.Identifiers` | `ClientProxyEmitter` | `generatedClassName(TypeElement, String suffix)` — flattens nested-type names (`Outer.Inner` → `Outer_Inner`) for the generated companion's simple name |
| `dev.vertique.codegen.support.Identifiers.constantName` (referenced via `Conditions.constantName`/direct call) | `ContributorEmitter` | `SCREAMING_SNAKE_CASE` constant-name derivation, used both for `_CONDITIONS` constants and to cross-check identifier uniqueness (see [Load-Bearing Invariants](#load-bearing-invariants)) |

`TypeResolver` (also in `vertique-codegen-core`) is used throughout via `ctx.typeResolver()` — e.g.
`ImplCandidateScanner.findHandlerContract`/`findDirectContract` call
`resolveTypeArgument(...)`/`allSupertypes(...)` on it — without a named import, since callers never
declare a local variable of that type.

**Correcting a stale claim:** an earlier revision of the packaged module doc stated that
`ContributorModuleEmitter` resolves its output package "via `PackageResolver`" and that it generates
the module "using `DaggerModuleWriter`". Neither is true of the current source: `PackageResolver`
(in `vertique-codegen-core`) is not referenced anywhere in this module, and `DaggerModuleWriter` is
named only in a javadoc comparison comment (`ContributorModuleEmitter.java:208`), not called.
`ContributorModuleEmitter` resolves its package with its own inline longest-common-prefix algorithm
and builds the `GeneratedServicesModule` `TypeSpec` directly with JavaPoet. See
[Load-Bearing Invariants](#load-bearing-invariants).

---

## Runtime or Build Flow

`ServiceContractProcessor` (`@SupportedAnnotationTypes("*")`, impl-rooted discovery) runs both of its
scans in the same round:

1. `init()` constructs one `CodegenContext`, one instance of every scanner/extractor (including
   `ClientContractExtractor`), all eleven validators, and all three emitters.
2. `process()` returns immediately (`false`) once `roundEnv.processingOver()` or the one-shot
   `emitted` guard is already set — both scans below run only in the first eligible round.
3. **Impl-rooted (contributor) pass:** `ImplCandidateScanner.scan(roundEnv)` walks
   `roundEnv.getRootElements()` and returns `List<ImplCandidate>`. For each candidate:
   - `DOUBLE_PATTERN` is rejected immediately (`ERROR`, contract untouched) and skipped.
   - `DIRECT` candidates go through `DirectImplExtractor`; every failure it can report is
     contract-rooted (return-type unwrapping or contract-parameter classification), so its
     `ExtractionResult.valid()` doubles as the contract-shape signal.
   - `HANDLER` candidates go through `HandlerImplExtractor`, whose `ExtractionResult` splits
     `contractShapeValid` from `implSideValid` — a missing/overloaded/mis-parameterised handler
     method is impl-rooted and must **not** suppress client-proxy emission; a contract return-type
     or payload-parameter defect is contract-rooted and must.
   - Extracted models run through five contract-shape validators and four impl-side validators,
     combined with bitwise `&` (not `&&`) so every validator runs and reports its own diagnostic in
     one compile pass, rather than short-circuiting on the first failure.
   - A contract whose *shape* failed in this loop is recorded in `contractShapeFailedFqns` — this is
     what step 4 uses to avoid double-reporting the same five errors from the client-proxy pass.
4. Valid models are grouped by contract FQN (`LinkedHashMap`, insertion-ordered for deterministic
   output), then each group runs the two CG-011 per-contract-group validators
   (`MultipleUnconditionalImplValidator`, `ConditionalRequiredOnNonDefaultValidator`), again combined
   with `&`. Groups that pass go to `ContributorEmitter.emit(group)`; one representative model per
   group goes to `ContributorModuleEmitter.add(...)`. If any group was accumulated,
   `ContributorModuleEmitter.emit()` writes the single `GeneratedServicesModule` for the round.
5. **Annotation-rooted (client-proxy) pass**, `emitClientProxies(roundEnv, contractShapeFailedFqns)`:
   `roundEnv.getElementsAnnotatedWith(ServiceContract.class)`, filtered to `INTERFACE` and sorted by
   FQN for determinism, minus anything already in `contractShapeFailedFqns`. Each surviving contract
   goes through `ClientContractExtractor.extract(...)`; an empty `Optional` (non-generatable NOTE or
   an extraction error) is skipped. A successful extraction is re-validated against the same five
   contract-shape validators (again `&`-combined) before checking
   `ClientProxyEmitter.reservedIdentifierCollision(model)` — a collision emits an informational
   `NOTE` and skips emission; otherwise `ClientProxyEmitter.emit(model)` runs.
6. `emitted = true`; `process()` always returns `false` so Dagger, Lombok, and any other processor on
   the path still see unmodified elements.

Extraction internally always follows the same three-stage pipeline — resolve operation name/stable id
(`AptOperationIdResolver`) → unwrap `Future<T>` (`MethodExtraction.unwrapReturnType`) → classify
parameters (`AptParamClassifier`) — whether driven from an impl (`DirectImplExtractor`/
`HandlerImplExtractor`) or from a bare contract interface (`ClientContractExtractor`). The
contract-only path additionally resolves every candidate method's signature as a member of the
contract type (`Types.asMemberOf(DeclaredType, Element)`) before running the pipeline, so a concrete
contract inheriting a generic super-interface (`Child extends Parent<String>`) still yields
substituted, non-generic signatures.

---

## Load-Bearing Invariants

**Record shapes** (verify against source before citing — these have drifted before):

```java
public record ImplCandidate(ImplKind kind, TypeElement implType, TypeElement contractType) {
    public enum ImplKind { DIRECT, HANDLER, DOUBLE_PATTERN }
}

public record ContractModel(
        TypeElement contractType, TypeElement implType, ImplKind kind, List<OperationModel> operations) {}

public record ClientContractModel(TypeElement contractType, List<OperationModel> operations) {}

public record OperationModel(
        ExecutableElement contractMethod, ExecutableElement handlerMethod,
        String operationName, String stableOperationId,
        TypeMirror returnType, TypeMirror payloadType,
        List<ParamModel> params, List<ParamModel> handlerParams,
        boolean oneWay) {}
```

`ContractModel` carries **no** `String contractName`/namespace fields — `ContributorEmitter` reads
`@ServiceContract#namespace()`/`#value()` itself, directly off `AnnotationMirrors`, at emission time.
For direct-impl and contract-only extraction, `handlerMethod == contractMethod` and
`handlerParams == params` (the same object references) — the aliasing convention every
non-handler-pattern extractor uses; `ClientProxyEmitter` and `ContributorEmitter`'s direct-impl path
read only the contract side and never notice the alias.

**Dual overload pattern (element-declared vs. resolved-type).** `MethodExtraction.unwrapReturnType`
and `AptParamClassifier.classifyContractParams` each have two overloads: one reading the
element-declared type (`method.getReturnType()` / `param.asType()`), used by `DirectImplExtractor`;
one reading a resolved `ExecutableType`/`TypeMirror` produced by `Types.asMemberOf(...)`, used by
`ClientContractExtractor`. Both overloads share one private implementation, so the "at most one
payload parameter" rule and the `Future<T>`-unwrap rule are enforced **identically** on both paths —
a multi-payload contract method fails extraction (`errorSink[0] = true`) before a `ClientContractModel`
is ever produced, so `PayloadParamValidator` never actually encounters a multi-payload contract on the
client-proxy path in practice; it still re-runs there as a defensive check against whatever *did*
extract.

**`@DispatchContextValue` is handler-only.** `AptParamClassifier.classifyContractParams` never checks
for `@DispatchContextValue` — only `classifyHandlerParams` does. A type carrying that annotation on a
*contract* method parameter falls straight through to `PAYLOAD` classification; there is no contract-
side special case for it (mirrors `ParameterClassifier.java:151-185`'s handler-only scope, and the
annotation itself is `@Target(TYPE)`, so `AnnotationMirrors.isPresent(param, ...)` would always be
`false` regardless — the check must resolve the parameter's *type* to a `TypeElement` first,
`AptParamClassifier.hasDispatchContextValueAnnotation`).

**`ContributorEmitter` three-tier identifier disambiguation.** Provider fields, condition constants,
per-op metadata fields, and `buildEntry_*` helper names are all derived from a per-impl "unique key"
(`buildUniqueImplKeys`). Three escalation tiers, tried in order, the first whose *derived* forms are
pairwise unique wins:

1. Simple class name (`UserServiceSandbox`).
2. Package-prefixed, applied uniformly to every impl in the group (`a_UserServiceSandbox`) —
   disambiguates same-simple-name-different-package collisions.
3. Positional (`impl0_UserServiceSandbox`, `impl1_...`) — always unique by construction; the fallback
   of last resort.

`areKeysUnique` checks pairwise distinctness of the raw keys **and** of every downstream identifier
transform (`providerFieldName` — decapitalized + `Provider` — and
`Identifiers.constantName` — `SCREAMING_SNAKE_CASE`), not just the raw strings. This catches
case-only collisions a naive string-equality check would miss (`Foo` vs `foo` both decapitalize to
`fooProvider`). Multi-impl groups also emit a shared `EMPTY_CONDITIONS` constant for any unconditional
candidate, rather than a per-candidate empty array.

**`ContributorEmitter.contribute()` size-aware branching.** Single-impl unconditional → always
returns the entry. Single-impl conditional → returns the entry when the condition matches, else
`List.of()` (never throws — FR-CG011-011). Multi-impl → collects every matching conditional
candidate into a `matches` list; `>1` matches throws `ServiceRegistrationException` naming every
matched impl's FQN; exactly `1` match returns that candidate's entry; `0` matches falls back to the
unconditional default if one exists, otherwise throws. The namespace segment in the generated
`deploymentOptions(config, "services", "contracts", namespaceSegment, name)` call substitutes the
literal `"_"` sentinel when `@ServiceContract#namespace()` is blank — this mirrors
`ServicesConfig.fromConfig`'s own empty-namespace addressing convention (`vertique-services`) and must
stay in lockstep with it.

**`ContributorModuleEmitter`** dedupes by contract FQN with a `LinkedHashSet` guard before emitting
each `@Provides @IntoSet` binding — a defensive check against accidental duplicate models from mixed
rounds, since the processor now always passes one representative model per contract group. Package
resolution order: `-Avertique.codegen.package` option → longest-common-prefix of all contributor
contract packages (its own inline `longestCommonPrefix(String, String)`, not a shared helper) →
`vertique.generated.services` fallback. The `@Provides` method name uses
`Introspector.decapitalize(simpleName)` (so `URLProvider`-style acronym-leading names decapitalize
correctly, unlike a naive `Character.toLowerCase(charAt(0))`), with a trailing `_` appended if the
result collides with a Java keyword/reserved word (`SourceVersion.isName(name)` check) — mirroring
`DaggerModuleWriter.bindingMethodName`'s convention without calling that class.

**`ClientProxyEmitter` reserved-identifier families.** Two families, checked by
`reservedIdentifierCollision`, both causing a skip-with-`NOTE` (not an `ERROR`) when they collide:

| Where | Reserved names | Why |
|-------|-----------------|-----|
| Contract **parameter** names | `_args_`, `_payload_`, `_overrides_`, `_envelope_`, `_dispatch_` | Method-locals in every generated dispatch body; a same-named parameter would shadow one |
| Contract **method** names | `_payloadIndex_`, `_securityContextIndex_` | Names of the generated private static index helpers, each `private static int helper(ServiceMethodMeta)` |

The method-name check compares **erasure**, not just the name: a contract method only collides when
its erased parameter list is exactly `(ServiceMethodMeta)` — any other overload (different arity or
parameter type, e.g. `_payloadIndex_(String)`) compiles fine alongside the generated helper and is
never flagged. Generated fields and constructor locals need no check at all: every field read in a
dispatch body is `this.`-qualified, and the constructor sees no contract-declared identifier.

**`ClientProxyEmitter` metadata ownership.** Only the method universe (one override per
`OperationModel`) and the operation-id string constants passed to `entry.operations().get(...)` are
baked at annotation-processing time. `oneWay`, the payload-parameter index, and the
`SecurityContext`-parameter index are all derived from the runtime `ServiceMethodMeta` **at
construction**, never from the source signature — baking them would silently diverge from a registry
built by a different mechanism. The constructor throws `IllegalStateException` on the first missing
operation id, with the message always starting with the pinned literal
`"Service client contract mismatch: "` — this exact prefix is duplicated (not shared as a symbol,
since no cross-package constant may leak into user packages) with `ServiceClientFactory`'s
package-private constant in `vertique-services`, and kept in sync by tests on both sides. The
generated `SecurityContext`-override cast is unconditional (not an `instanceof` guard) so a
runtime-metadata bug that marks a non-`SecurityContext` parameter as the SC slot throws
`ClassCastException` identically on both the generated and reflective dispatch paths, rather than one
path silently forwarding a wrong-typed value.

**One-shot emission guard shared by both scans.** The `emitted` boolean gates *both* the contributor
scan and the client-proxy scan together — they run in the same round or not at all. A
`@ServiceContract` interface that only becomes visible in a later round (e.g. generated by another
processor after this one already ran) gets no client proxy in that compilation unit.

---

## Generated and Hand-Written Boundaries

Everything under `.emit` produces JavaPoet output written into the **consuming module's** generated
sources (never into this module's own build output): `{Contract}_ContractContributor`,
`GeneratedServicesModule`, and `{Contract}_ServiceClientProxy` — see the packaged module doc's
[Generated Code Shape](src/main/resources/META-INF/vertique/module.md#generated-code-shape) for the exact emitted shapes.
Everything else in `.scan`/`.validate`/`ServiceContractProcessor` is hand-written APT front-end code
that produces the models the emitters consume.

**Parity obligation — codegen-time validation vs. runtime discovery/dispatch.** Several classes here
are *compile-time replicas* of runtime classes in `vertique-services`. A behavioral change to any
runtime class below must be mirrored in its APT counterpart (and vice versa), or the codegen path and
the reflective `ServiceRegistrar`/`ServiceClientFactory` path will silently diverge on what gets
accepted, what a parameter classifies as, or what operation id a method resolves to:

| APT-side (this module) | Runtime mirror (`vertique-services`) |
|---|---|
| `ImplCandidateScanner` | `ContractDiscovery.java:31-105` |
| `AptOperationIdResolver` | `OperationIdResolver.java:37-68` |
| `AptParamClassifier` | `ParameterClassifier.java:58-70,90-132,151-185` |

**Per-impl validators** (run against each `ImplCandidate`'s extracted `ContractModel`; five
contract-shape validators take explicit `(TypeElement contractType, List<OperationModel> operations)`
inputs — `ContractOverloadValidator` takes just `TypeElement`, since it rescans the contract's members
directly — specifically so `ServiceContractProcessor` can reuse them against a `ClientContractModel`
with no impl in scope at all; only these five gate client-proxy emission):

| Validator | Runtime mirror | Rule | Gates client-proxy emission |
|---|---|---|---|
| `ReturnTypeValidator` | `ReturnTypeResolver` | Contract method must return parameterized `Future<T>`; raw `Future` rejected | Yes |
| `PayloadParamValidator` | `ParameterClassifier.java:90-132` | At most one PAYLOAD parameter per contract method; `DispatchEnvelope<?>` rejected outright | Yes |
| `ContractOverloadValidator` | `MethodValidator.java:27-41` | No method-name overloads on the contract interface | Yes |
| `HandlerOverloadValidator` | `MethodValidator.java:80,106,191` | No method-name overloads on the handler class — full-stop reject, no best-overload heuristic | No — impl-coupled |
| `OperationValueValidator` | `OperationIdResolver.java:62` | `@ServiceOperation` value must not be blank | Yes |
| `OperationCollisionValidator` | `ServiceRegistrar` | No duplicate resolved operation names within a contract | Yes |
| `InjectConstructorValidator` (codegen-core) | (stricter than CG-002) | Exactly one `@Inject` constructor: 0 → ERROR, >1 → ERROR | No — impl-coupled |
| `HandlerContractValidator` | `ContractDiscovery.java:35-76` | `ServiceHandler<C>`: `C` must be `@ServiceContract`; double-pattern rejected; handler must not implement additional `@ServiceContract` interfaces | No — impl-coupled |
| `HandlerMatchValidator` | `MethodValidator.java:54-160` | Each contract method needs exactly one matching handler method (same name; payload params identical in order/type; extra params must be `SecurityContext` or `@DispatchContextValue`-annotated; same `Future<T>` return type) | No — impl-coupled |

**Per-contract-group validators** (run after grouping, CG-011 — see
`MultipleUnconditionalImplValidator`/`ConditionalRequiredOnNonDefaultValidator` for the full
diagnostic-message construction):

| Validator | Rule |
|---|---|
| `MultipleUnconditionalImplValidator` | At most one unconditional (no `@ConditionalOnProperty`) implementation per contract group; violation → per-impl ERROR naming every other unconditional impl |
| `ConditionalRequiredOnNonDefaultValidator` | In a multi-impl group with an unconditional default, every non-default impl must carry `@ConditionalOnProperty`; violation → per-impl ERROR |

---

## Testing

All tests live under `src/test/java/dev/vertique/codegen/services/processor/`, driven by compile
testing (`ServiceContractTestFixtures` — shared fixtures/harness, not a test itself).

| Behavior | Test classes |
|---|---|
| Basic scaffolding, direct-impl / handler-impl positive & smoke paths | `ServiceContractProcessorScaffoldTest`, `ServiceContractProcessorDirectImplSmokeTest`, `ServiceContractProcessorDirectImplPositiveTest`, `ServiceContractProcessorHandlerPositiveTest` |
| `@NoAutoWire` + `@ConditionalOnProperty` no-op warning | `ServiceContractProcessorNoAutoWireConditionalTest` |
| Per-impl validator negative paths | `ServiceContractProcessorContractOverloadTest`, `ServiceContractProcessorHandlerOverloadTest`, `ServiceContractProcessorReturnTypeTest`, `ServiceContractProcessorPayloadParamTest`, `ServiceContractProcessorOperationValueTest`, `ServiceContractProcessorOperationCollisionTest`, `ServiceContractProcessorInjectConstructorTest`, `ServiceContractProcessorHandlerContractTypeTest`, `ServiceContractProcessorHandlerMatchTest`, `ServiceContractProcessorContextParamTest` |
| CG-011 multi-impl / per-contract-group validators | `ServiceContractProcessorMultiImplTest`, `ServiceContractProcessorMultiImplRuntimeTest` |
| Deterministic output / round-trip compilation | `ServiceContractProcessorRoundtripTest`, `ContributorModuleEmitterBindingMethodNameTest` |
| Client-proxy emission (CG-015 Track D) — happy path, impl-failure independence, dynamic-proxy parity, reserved-identifier skip, round-trip | `ServiceClientProxyEmissionTest`, `ServiceClientProxyImplFailureEmissionTest`, `ServiceClientProxyParityTest`, `ServiceClientProxyReservedIdentifierTest`, `ServiceClientProxyRoundtripTest` |

Smallest useful command for this module:

```bash
./mvnw -ntp -pl vertique-codegen/vertique-codegen-services -am test
```

Always follow up with the full-project verification before considering a change complete:

```bash
./mvnw -ntp clean verify
```

---

## Related ADRs

- ADR-0026: REST Client Static Proxies via APT — established the `Class.forName`-first,
  JDK-proxy-fallback companion-selection doctrine that ADR-0190 extends to services; the actual
  selection logic lives in `ServiceClientFactory`/`GeneratedCompanions` (`vertique-services`), not
  here, but `ClientProxyEmitter`'s output is the companion that doctrine selects.
- ADR-0070: Delayed-Job Static Proxy Codegen — established the reflective-factory-instantiation (no
  Dagger, no generated module) shape for a per-contract static proxy in `vertique-codegen-delayed-job`.
  Contrast this module: the services contributor **is** Dagger-provided (`@Inject` constructor,
  `GeneratedServicesModule`) because services, unlike delayed jobs, are DI-managed singletons.
- ADR-0072: Kafka `KafkaBindingMeta` Shape and Registrar Integration — established the
  compile-time-metadata-only / runtime-resolves-targets split this module's contributor emission also
  follows: `PropertyCondition` selection and event-bus targets are resolved at startup from generated
  static metadata, never baked as literals.
- ADR-0073: Workflow Client Proxy Codegen — Discovery and Loud-Fail — established the `@Inject`-
  constructor client-proxy shape, `toString()` parity with the JDK proxy, and loud-fail-on-broken-
  companion doctrine that `ClientProxyEmitter`'s generated `{Contract}_ServiceClientProxy` mirrors for
  services (services additionally derive dispatch metadata from `ServiceMethodMeta` at construction
  rather than baking it — see ADR-0190).
- ADR-0104: Typed Config Architecture — governs the `services.contracts.{namespace}.{name}`
  typed-config path that generated contributors' `deploymentOptions(...)` calls navigate; the
  `namespaceSegment` `"_"` sentinel in `ContributorEmitter.buildEntryHelper` addresses this path's
  empty-namespace case.
- ADR-0190: Service Client Companion Selection and Create-Time Fail-Fast — the decision record for
  this module's own `ClientProxyEmitter` output and the `ServiceClientFactory` companion-first
  selection it feeds; the fail-fast mismatch-prefix convention baked into the generated constructor is
  this ADR's contract.
