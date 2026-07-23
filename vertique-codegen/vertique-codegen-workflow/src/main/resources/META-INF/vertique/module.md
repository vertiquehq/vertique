<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Codegen Workflow Client Proxy Module

> **Status:** Beta
> **Package:** `dev.vertique.codegen.workflow.processor`
> **Artifact:** `vertique-codegen-workflow`
> **Depends on:** codegen-core; workflow-core (test-scope in this processor module — but a consuming app needs it on its normal runtime classpath)

Annotation processor that eliminates per-call reflection in the workflow client hot path. For each `@WorkflowContract` interface it generates a static `{Contract}_WorkflowClientProxy` class that replaces the JDK dynamic proxy built by `WorkflowClientFactory`, dispatching every contract method directly to `WorkflowOperations` with zero reflection.

In addition to the performance win, the processor lifts contract-shape validation to compile time: a missing `@WorkflowStart` method, a signal missing a dedup source, conflicting operation-role annotations, or a disallowed `default` method all surface as build errors rather than startup failures. The STRUCTURAL subset of `WorkflowProxyValidator`'s rules is reproduced using the same `Diagnostics` wording so a violation reads identically at compile time and at runtime.

The processor also generates a single aggregate `GeneratedWorkflowClientsModule` Dagger module whose `@Provides @Singleton` bindings each delegate to `WorkflowClientFactory.create({Contract}.class)`, so registry and plan validation (the four runtime-only rules) still run at application startup regardless of which path is taken. An application opts in by adding this leaf to `annotationProcessorPaths` and listing `GeneratedWorkflowClientsModule` in its `@Component` (ADR-0025 inclusion model).

`WorkflowClientFactory` and `WorkflowProxyValidator` are preserved as the runtime fallback and the authoritative validation path. A generated proxy found on the classpath but impossible to instantiate causes a loud `WorkflowClientProxyLinkageException` rather than a silent fallback to the reflective proxy.

See ADR-0073 for the factory-selection mechanism, generated-name convention, and loud-fail rationale.

---

## Package Layout

| Package | Contents |
|---------|----------|
| `dev.vertique.codegen.workflow.processor` | `WorkflowContractProcessor` |
| `dev.vertique.codegen.workflow.processor.scan` | `ContractScanner`, `ContractModel`, `OperationModel`, `OperationRole`, `ParamRole`, `ParamRoleModel` |
| `dev.vertique.codegen.workflow.processor.validate` | `ContractShapeValidator`, `ReturnTypeValidator`, `ParamAnnotationValidator` |
| `dev.vertique.codegen.workflow.processor.emit` | `WorkflowProxyEmitter`, `WorkflowClientsModuleEmitter` |

---

## Key Classes

### `WorkflowContractProcessor`

`AbstractProcessor` registered via `META-INF/services/javax.annotation.processing.Processor`. Entry point for the round-based processing lifecycle.

```
@SupportedAnnotationTypes("dev.vertique.workflow.contract.WorkflowContract")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions("vertique.codegen.package")               // aggregate-module package override (see WorkflowClientsModuleEmitter)
```

Lifecycle:
1. `init(env)` — instantiates `CodegenContext`, `ContractScanner`, `ContractShapeValidator`, `ReturnTypeValidator`, `ParamAnnotationValidator`, `WorkflowProxyEmitter`, `WorkflowClientsModuleEmitter`.
2. `process(annotations, round)` (runs exactly once — the first non-final round):
   - Collects `@WorkflowContract`-annotated `TypeElement`s.
   - For each: scans into a `ContractModel`, runs all three validators with `&`-combination so every diagnostic surfaces in a single compile cycle.
   - Emits a proxy for each valid contract.
   - Emits one `GeneratedWorkflowClientsModule` covering all valid contracts in the compilation unit.
3. Returns `false` so other processors (Dagger, Lombok) see the same elements.

### `ContractScanner` / `ContractModel`

`ContractScanner.scan(TypeElement)` reads `@WorkflowContract` annotation attributes (`definitionId`, `definitionVersion`) and scans every non-static, non-`Object` method — including methods inherited from super-interfaces via `Elements.getAllMembers` (mirrors `Class.getMethods()` semantics).

The scanner is **tolerant**: it classifies even malformed methods and emits no diagnostics. All shape validation is deferred to the validator slice so a single compilation cycle surfaces the complete error picture.

The result is an immutable `ContractModel` record:

```java
record ContractModel(
    TypeElement contractType,
    String definitionId,
    long definitionVersion,
    List<OperationModel> operations   // List.copyOf; immutable
) {}
```

Each element of `operations` is an `OperationModel` carrying the APT `ExecutableElement`, its classified parameter list, payload-interface assignability flags, and a convenience `isCleanSingleRoleOp()` predicate (exactly one role annotation and not `default`) that role-specific validators and the emitter gate on.

### `OperationRole` and `ParamRole`

**`OperationRole`** — the operation kind declared on a contract method:

| Constant | Annotation |
|----------|-----------|
| `START` | `@WorkflowStart` |
| `SIGNAL` | `@WorkflowSignal` |
| `QUERY` | `@WorkflowQuery` |

A method may carry more than one annotation (a shape violation caught by `ContractShapeValidator`); `OperationModel.declaredRoles()` lists all.

**`ParamRole`** — the role assigned to each parameter in declaration order. Classification precedence (annotation-based beats type-based beats catch-all):

| Role | Trigger |
|------|---------|
| `IDEMPOTENCY_KEY` | `@IdempotencyKey` annotation present |
| `BUSINESS_KEY` | `@BusinessKey` annotation present |
| `SUBJECT_REF` | `@SubjectRef` annotation present |
| `SIGNAL_DEDUP_KEY` | `@SignalDedupKey` annotation present |
| `INSTANCE_ID` | parameter type is `WorkflowInstanceId` (erased comparison) |
| `PAYLOAD` | catch-all |

### `ContractShapeValidator`

Enforces contract-level and per-method structural shape rules. Only acts on the complete contract model; does not interact with the emit phase.

| Check | Scope | Level |
|-------|-------|-------|
| Annotated type must be an `interface` | Contract | ERROR |
| Exactly one `@WorkflowStart` method | Contract | ERROR |
| Signal names unique across clean `@WorkflowSignal` methods | Contract | ERROR |
| No `default` methods | Per-method | ERROR |
| Exactly one operation-role annotation | Per-method | ERROR |
| At least one operation-role annotation | Per-method | ERROR |
| `@WorkflowStart`: no `INSTANCE_ID` parameter | Per-method | ERROR |
| `@WorkflowStart`: exactly one `PAYLOAD` parameter | Per-method | ERROR |
| `@WorkflowStart`: idempotency source present (`@IdempotencyKey` param or `IdempotencyKeyed` payload) | Per-method | ERROR |
| `@WorkflowSignal`: exactly one `INSTANCE_ID` parameter, and it must be first | Per-method | ERROR |
| `@WorkflowSignal`: exactly one `PAYLOAD` parameter | Per-method | ERROR |
| `@WorkflowSignal`: dedup source present (`@SignalDedupKey` param or `SignalDedupKeyed` payload) | Per-method | ERROR |
| `@WorkflowQuery`: exactly one parameter of role `INSTANCE_ID` | Per-method | ERROR |

All errors are emitted before returning so the developer receives the full diagnostic picture in one compile cycle.

### `ReturnTypeValidator`

Validates return types of clean single-role operations (skips `default` and conflicting/missing-role methods — those are `ContractShapeValidator`'s responsibility).

| Role | Required return type |
|------|---------------------|
| `START` | `Future<WorkflowInstanceId>` |
| `SIGNAL` | `Future<Void>` |
| `QUERY` | exactly `Future<WorkflowView>` (V1 — no supertype/wildcard) |

### `ParamAnnotationValidator`

Validates parameter-annotation type and cardinality for clean single-role operations. `QUERY` methods have no param-annotation checks.

| Annotation | Applies to | Required type | At-most-one |
|-----------|-----------|---------------|-------------|
| `@IdempotencyKey` | `START` | `String` | Yes |
| `@BusinessKey` | `START` | `String` | Yes |
| `@SubjectRef` | `START` | `WorkflowSubjectRef` | Yes |
| `@SignalDedupKey` | `SIGNAL` | `String` | Yes |

### `WorkflowProxyEmitter`

Generates `{Contract}_WorkflowClientProxy` in the contract's own package. Key properties of the generated class:

- `public final`, implements the contract interface, annotated `@Generated("...WorkflowContractProcessor")`.
- `@Inject` constructor `(WorkflowOperations ops)` — stores `ops` in a `private final` field.
- `@WorkflowStart` methods: construct a 6-arg `StartCommand(definitionId, payload, idempotencyKey, businessKey, subjectRef, definitionVersionL)` and call `ops.start(...)`. The `definitionId` and `definitionVersion` are baked as compile-time literals (`"order-fulfillment"` / `1L`). Key sources mirror the reflective handler exactly, distinguishing **required** from **optional**:
  - **Idempotency key (required):** explicit `@IdempotencyKey` parameter wins; otherwise the payload is **cast** to `IdempotencyKeyed` — validation guarantees the declared payload implements it (or carries the param), so the cast is statically safe.
  - **Business key / subject ref (optional):** an explicit `@BusinessKey`/`@SubjectRef` parameter wins; otherwise the value is extracted by a **runtime `instanceof`** check (`(Object) payload instanceof BusinessKeyed bk ? bk.businessKey() : null`, and likewise for `SubjectReferenced`), yielding `null` when the runtime payload does not implement the marker. A compile-time cast on the declared type would silently drop the key when a caller passes a runtime *subtype* that implements the marker; the `(Object)` operand cast also keeps the `instanceof` legal for a `final` payload type that does not implement the marker.
- `@WorkflowSignal` methods: extract the **required** dedup key (explicit `@SignalDedupKey` param wins, else **casts** payload to `SignalDedupKeyed` — validation-guaranteed, same as the idempotency key) and call `ops.signal(instanceId, signalName, payload, dedupKey)`.
- `@WorkflowQuery` methods: call `ops.query(instanceId)`.
- `toString()` returns `"WorkflowProxy[" + Contract.class.getName() + "]"` — identical to the JDK reflective proxy.

The proxy is always emitted into the contract's own package (`ctx.packageNameOf(contract)`). The runtime lookup derives the class name from the contract's binary name via `GeneratedNames.companionFqn(contract, "_WorkflowClientProxy")`, so relocating the proxy would break discovery. `-Avertique.codegen.package` does **not** control proxy placement.

#### Invariants & Gotchas

**Emitter↔runtime lockstep risk.** The generated proxy reproduces the runtime handler semantics of `WorkflowClientFactory`'s JDK proxy. If the runtime handler logic changes (e.g. a new `StartCommand` argument, a changed `ops.signal` signature), the emitter must change in lockstep. `WorkflowProxySnapshotTest` is the tripwire: it compares generated proxy source against a checked-in golden snapshot and fails the build if they diverge.

**Nested-contract FQN translation.** `Class.getName()` uses `$` as the nested-type separator (`com.example.Outer$Inner`). `GeneratedNames.companionFqn` translates this to `com.example.Outer_Inner_WorkflowClientProxy`, matching the name the emitter produces for a nested contract interface.

### `WorkflowClientsModuleEmitter`

Generates a single `GeneratedWorkflowClientsModule` covering all valid contracts in the compilation unit.

**Invariant D5 — delegate to the factory, never return the proxy directly.** Every generated `@Provides @Singleton` body is:

```java
@Provides
@Singleton
static OrderWorkflow provideOrderWorkflow(WorkflowClientFactory factory) {
    return factory.create(OrderWorkflow.class);
}
```

The body delegates to `WorkflowClientFactory.create({Contract}.class)` and never returns the generated proxy directly. Registry and plan validation live inside `WorkflowClientFactory.create`; a binding that returned the proxy directly would bypass them. The factory internally selects the generated proxy via `Class.forName`, so the injected instance is still the zero-reflection proxy and has passed validation.

**Package resolution order:**
1. `-Avertique.codegen.package` when set and non-blank.
2. Longest-common-package-prefix of all contract packages.
3. `vertique.generated.workflow` when the LCP is empty.

**Simple-name collision guard.** Two contracts whose simple names are identical would produce the same `provide{Name}` method in the module. The emitter detects this before writing, emits a compiler error per colliding contract, and skips module emission.

---

## Runtime Integration

`WorkflowClientFactory.create` is the single entry point for both paths. Per call:

1. `WorkflowProxyValidator.validate(contract, registry)` runs first, unconditionally — a malformed contract fails identically with or without a generated proxy, and the registry/plan checks are never bypassed.
2. `GeneratedNames.companionFqn(contract, "_WorkflowClientProxy")` computes the expected generated class FQN.
3. `Class.forName(fqn, true, contract.getClassLoader())` attempts to load it:
   - `ClassNotFoundException` → silent fallback to the JDK reflective proxy (pre-codegen behavior).
   - `ReflectiveOperationException | LinkageError` → loud `WorkflowClientProxyLinkageException` (present-but-broken class; indicates a build/codegen inconsistency).
4. On success, the single-arg `(WorkflowOperations)` constructor is invoked and the instance returned. Subsequent contract calls have zero reflection.

**Validation rules NOT reproduced at compile time.** The four registry/plan rules in `WorkflowProxyValidator` — that the definition id/version is registered, that the registered plan matches the contract's declared methods — require a live `WorkflowRegistry` and are runtime-only. These rules are not checked by the annotation processor and cannot be bypassed by the generated proxy because the factory always validates before selecting either path.

---

## Compile-Time Validation Summary

| Violation | Level | Example message |
|-----------|-------|----------------|
| `@WorkflowContract` not on an interface | ERROR | `OrderWorkflow must be an interface` |
| Zero or more than one `@WorkflowStart` method | ERROR | `OrderWorkflow must declare exactly one @WorkflowStart method; found 0` |
| Duplicate signal name across clean `@WorkflowSignal` methods | ERROR | `OrderWorkflow: duplicate @WorkflowSignal name "cancel"` |
| `default` method on contract | ERROR | `OrderWorkflow.helper() is a default method — @WorkflowContract methods must be abstract` |
| Multiple operation-role annotations on one method | ERROR | `OrderWorkflow.start(): conflicting workflow operation annotations` |
| No operation-role annotation on a method | ERROR | `OrderWorkflow.doSomething(): method declares no workflow operation annotation` |
| `@WorkflowStart` has a `WorkflowInstanceId` parameter | ERROR | `OrderWorkflow.start(): @WorkflowStart must not have a WorkflowInstanceId parameter` |
| `@WorkflowStart` has zero or more than one PAYLOAD parameter | ERROR | `OrderWorkflow.start(): @WorkflowStart must declare exactly one payload parameter` |
| `@WorkflowStart` has no idempotency source | ERROR | `OrderWorkflow.start(): @WorkflowStart must have an @IdempotencyKey parameter or an IdempotencyKeyed payload` |
| `@WorkflowSignal` INSTANCE_ID count ≠ 1 | ERROR | `OrderWorkflow.cancel(): @WorkflowSignal must declare exactly one WorkflowInstanceId parameter; found 0` |
| `@WorkflowSignal` INSTANCE_ID not first parameter | ERROR | `OrderWorkflow.cancel(): @WorkflowSignal WorkflowInstanceId parameter must be first` |
| `@WorkflowSignal` has zero or more than one PAYLOAD parameter | ERROR | `OrderWorkflow.cancel(): @WorkflowSignal must declare exactly one payload parameter` |
| `@WorkflowSignal` has no dedup source | ERROR | `OrderWorkflow.cancel(): @WorkflowSignal must have a @SignalDedupKey parameter or a SignalDedupKeyed payload` |
| `@WorkflowQuery` parameter count or role not exactly one INSTANCE_ID | ERROR | `OrderWorkflow.getStatus(): @WorkflowQuery must declare exactly one WorkflowInstanceId parameter; found 2` |
| `@WorkflowStart` return type ≠ `Future<WorkflowInstanceId>` | ERROR | `OrderWorkflow.start(): @WorkflowStart must return Future<WorkflowInstanceId>` |
| `@WorkflowSignal` return type ≠ `Future<Void>` | ERROR | `OrderWorkflow.cancel(): @WorkflowSignal must return Future<Void>` |
| `@WorkflowQuery` return type ≠ `Future<WorkflowView>` | ERROR | `OrderWorkflow.getStatus(): @WorkflowQuery but return type is not Future<WorkflowView>; got ...` |
| `@IdempotencyKey`/`@BusinessKey`/`@SignalDedupKey` param not `String` | ERROR | `OrderWorkflow.start(): @IdempotencyKey parameter must be of type String` |
| `@SubjectRef` param not `WorkflowSubjectRef` | ERROR | `OrderWorkflow.start(): @SubjectRef parameter must be of type WorkflowSubjectRef` |
| Duplicate `@IdempotencyKey`, `@BusinessKey`, `@SubjectRef`, or `@SignalDedupKey` | ERROR | `OrderWorkflow.start(): duplicate @IdempotencyKey annotation` |
| Two operations share an erased signature inherited from unrelated super-interfaces | ERROR | `OrderWorkflow inherits an ambiguous operation 'start(com.example.StartOrder)' from multiple unrelated super-interfaces; a workflow contract may not inherit the same method signature from sibling interfaces` |
| Two contracts with the same simple name in one compilation unit | ERROR | `@WorkflowContract com.a.OrderWorkflow collides with com.b.OrderWorkflow in GeneratedWorkflowClientsModule` |

---

## Adoption

### Step 1 — Add the processor

Add the processor to `<annotationProcessorPaths>`. Use `combine.children="append"` when the parent POM already declares processor paths (e.g., for Dagger or Lombok):

```xml
<annotationProcessorPaths combine.children="append">
    <path>
        <groupId>dev.vertique</groupId>
        <artifactId>vertique-codegen-workflow</artifactId>
    </path>
</annotationProcessorPaths>
```

### Step 2 — Include the generated module

Add `GeneratedWorkflowClientsModule` to the application `@Component`. The generated module provides one `@Singleton` binding per `@WorkflowContract` in the compilation unit. Without this step the generated proxy classes exist on the classpath but none of the contracts are bound in the Dagger graph.

```java
@Component(modules = {
    // ... existing modules ...
    GeneratedWorkflowClientsModule.class
})
public interface AppComponent { ... }
```

Removing either step is safe: removing the processor reverts all contracts to the JDK reflective proxy; removing the module from `@Component` means contracts must be provided manually. `WorkflowClientFactory` and `WorkflowProxyValidator` are retained and not deprecated.

### Processor Options

| Option | Default | Description |
|--------|---------|-------------|
| `vertique.codegen.package` | — | Package for `GeneratedWorkflowClientsModule`; overrides LCP resolution |

> A contract's `(definitionId, version)` is **not** verifiable at compile time — the processor cannot
> evaluate a `WorkflowDefinition`'s `definitionId()`/`definitionVersion()` method bodies, and the registry/plan
> normally lives in a different module. That check stays at runtime in `WorkflowProxyValidator`.

---

## Module Dagger Bindings

The processor emits one Dagger module: `GeneratedWorkflowClientsModule` (placed in the package determined by package-resolution order). Each `@Provides @Singleton` method returns `factory.create({Contract}.class)` so that registry/plan validation always runs. The application must add this module to its `@Component` for the bindings to take effect.

Generated proxies are discovered at runtime via `Class.forName` inside `WorkflowClientFactory` and require no further Dagger graph participation beyond the `GeneratedWorkflowClientsModule` binding.

---

## Dependencies

- `dev.vertique:vertique-codegen-core` — `CodegenContext`, `TypeResolver`, `AnnotationMirrors`, `Diagnostics`, `DaggerModuleWriter`, `Identifiers`, `PackageResolver`
- `dev.vertique:vertique-workflow-core` — `@WorkflowContract`, `@WorkflowStart`, `@WorkflowSignal`, `@WorkflowQuery`, `@IdempotencyKey`, `@BusinessKey`, `@SubjectRef`, `@SignalDedupKey`, `IdempotencyKeyed`, `BusinessKeyed`, `SubjectReferenced`, `SignalDedupKeyed`, `WorkflowInstanceId`, `WorkflowView`, `WorkflowSubjectRef`, `WorkflowOperations`, `StartCommand`. **Test-scope in this processor module** (the processor only needs these types to compile its own tests, not at processing time). A **consuming application** needs `vertique-workflow-core` on its normal compile/runtime classpath regardless — it owns the contract annotations the app authors against, and the generated proxy/module reference `WorkflowOperations`/`StartCommand`/`WorkflowClientFactory` — so adding the processor never introduces a new runtime dependency.
- `com.palantir.javapoet:javapoet` — source generation (compile-only; not on runtime classpath)
- `javax.annotation.processing` APIs — part of the JDK; not a separate Maven dependency

---

## Related ADRs

- ADR-0073: Workflow Client Proxy Codegen — Discovery and Loud-Fail — factory-selection mechanism (validate-first, `Class.forName` prefer-generated, `WorkflowClientProxyLinkageException` for present-but-broken proxy), generated-name convention (`_WorkflowClientProxy` suffix, `GeneratedNames.companionFqn`), origin-package pinning, `GeneratedWorkflowClientsModule` delegation-to-factory invariant, and `WorkflowProxySnapshotTest` tripwire.
- ADR-0025: Generated Dagger Module Inclusion — the explicit-inclusion model (`GeneratedWorkflowClientsModule` in `@Component`) that converts silent misconfiguration into a compile error.
- ADR-0070: Delayed-Job Static Proxy Codegen — sibling CG-012 Track B; the `Class.forName`-prefer-generated / loud-fail pattern is shared across Tracks A, B, and C.
- ADR-0072: Kafka `KafkaBindingMeta` Shape and Registrar Integration — sibling CG-012 Track A; companion pattern and origin-package pinning are analogous.
