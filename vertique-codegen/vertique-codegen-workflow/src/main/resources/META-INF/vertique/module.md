<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Codegen Workflow Client Proxy Module

> **Status:** Stable
> **Package:** `dev.vertique.codegen.workflow.processor`, `dev.vertique.codegen.workflow.signal`
> **Artifact:** `vertique-codegen-workflow`
> **Depends on:** codegen-core; workflow-core (test-scope in this processor module — but a consuming app needs it on its normal runtime classpath)

Annotation processor that eliminates per-call reflection in the workflow client hot path. For each `@WorkflowContract` interface it generates a static `{Contract}_WorkflowClientProxy` class that replaces the JDK dynamic proxy built by `WorkflowClientFactory`, dispatching every contract method directly to `WorkflowOperations` with zero reflection.

In addition to the performance win, the processor lifts contract-shape validation to compile time: a missing `@WorkflowStart` method, a signal missing a dedup source, conflicting operation-role annotations, or a disallowed `default` method all surface as build errors rather than startup failures. The STRUCTURAL subset of `WorkflowProxyValidator`'s rules is reproduced using the same `Diagnostics` wording so a violation reads identically at compile time and at runtime.

The processor generates no Dagger providers of typed clients. An application obtains a client from `WorkflowClientFactory` with `create(DefinitionClass)` or `create(Contract.class, definitionId)`; the factory selects the generated proxy when one is on the classpath and hands it the client binding. Applications use the shared parent-or-facade processor boundary described below.

The processor also generates a `GeneratedWorkflowTypeCatalogModule` that lists the `@WorkflowContract` interfaces and `@WorkflowType` classes of the compilation. A document loaded at runtime can name only the classes such a catalog lists (see the workflow-core and workflow-definition guides).

`WorkflowClientFactory` and `WorkflowProxyValidator` are preserved as the runtime fallback and the authoritative validation path. A generated proxy found on the classpath but impossible to instantiate causes a loud `WorkflowClientProxyLinkageException` rather than a silent fallback to the reflective proxy.

---

## Key Classes

### `OperationRole` and `ParamRole`

A `@WorkflowContract` method may be declared directly on the annotated interface or inherited from a super-interface — both are scanned for operation-role annotations.

**`OperationRole`** — the operation kind declared on a contract method:

| Constant | Annotation |
|----------|-----------|
| `START` | `@WorkflowStart` |
| `SIGNAL` | `@WorkflowSignal` |
| `QUERY` | `@WorkflowQuery` |

A method may carry more than one annotation, which `ContractShapeValidator` catches as a shape violation.

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
| An explicit `@WorkflowContract.value()` is neither blank, nor longer than 255 code points, nor holds a control character | Contract | ERROR |
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

### Generated `{Contract}_WorkflowClientProxy`

Generates `{Contract}_WorkflowClientProxy` in the contract's own package. Key properties of the generated class:

- `public final`, implements the contract interface, annotated `@Generated("...WorkflowContractProcessor")`.
- Public constructor `(WorkflowOperations ops, WorkflowClientBinding binding)` without `@Inject` — stores both in `private final` fields. `WorkflowClientFactory` supplies both; the binding carries the definition id, the contract name, the optional version and the scopes the client was created with.
- `@WorkflowStart` methods: call `this.ops.start(this.binding.startCommand(payload, idempotencyKey, businessKey, subjectRef))`. The proxy embeds no definition id or version literal and never names the `StartCommand` constructor, so the record can change shape without regenerating any proxy. Key sources mirror the reflective handler exactly, distinguishing **required** from **optional**:
  - **Idempotency key (required):** explicit `@IdempotencyKey` parameter wins; otherwise the payload is **cast** to `IdempotencyKeyed` — validation guarantees the declared payload implements it (or carries the param), so the cast is statically safe.
  - **Business key / subject ref (optional):** an explicit `@BusinessKey`/`@SubjectRef` parameter wins; otherwise the value is extracted by a **runtime `instanceof`** check (`(Object) payload instanceof BusinessKeyed bk ? bk.businessKey() : null`, and likewise for `SubjectReferenced`), yielding `null` when the runtime payload does not implement the marker. A compile-time cast on the declared type would silently drop the key when a caller passes a runtime *subtype* that implements the marker; the `(Object)` operand cast also keeps the `instanceof` legal for a `final` payload type that does not implement the marker.
- `@WorkflowSignal` methods: extract the **required** dedup key (explicit `@SignalDedupKey` param wins, else **casts** payload to `SignalDedupKeyed` — validation-guaranteed, same as the idempotency key) and call `ops.signal(instanceId, signalName, payload, dedupKey)`.
- `@WorkflowQuery` methods: call `ops.query(instanceId)`.
- `toString()` returns `"WorkflowProxy[" + Contract.class.getName() + "]"` — identical to the JDK reflective proxy.

The proxy is always emitted into the contract's own package (`ctx.packageNameOf(contract)`). The runtime lookup derives the class name from the contract's binary name via `GeneratedNames.companionFqn(contract, "_WorkflowClientProxy")`, so relocating the proxy would break discovery. `-Avertique.codegen.package` does **not** control proxy placement.

#### Invariants & Gotchas

**Emitter↔runtime lockstep risk.** The generated proxy reproduces the runtime handler semantics of `WorkflowClientFactory`'s JDK proxy. If the runtime handler logic changes (e.g. a changed `ops.signal` signature or a new way to source a key), the emitter must change in lockstep. `WorkflowProxySnapshotTest` is the tripwire: it compares generated proxy source against a checked-in golden snapshot and fails the build if they diverge.

**Nested-contract FQN translation.** `Class.getName()` uses `$` as the nested-type separator (`com.example.Outer$Inner`). `GeneratedNames.companionFqn` translates this to `com.example.Outer_Inner_WorkflowClientProxy`, matching the name the emitter produces for a nested contract interface.

### `GeneratedWorkflowTypeCatalogModule`

Generates a single concrete `@Module` for every compilation unit that declares at least one valid `@WorkflowContract` interface or `@WorkflowType` class, including a unit that declares only `@WorkflowType` classes. It is not generated when the unit declares neither. The module has one `@Provides @IntoSet` method that returns a `WorkflowTypeCatalog`:

```java
@Provides
@IntoSet
static WorkflowTypeCatalog workflowTypeCatalog() {
    return () -> List.of(
            new WorkflowTypeCatalogEntry(Kind.CONTRACT, "orders", OrderWorkflow.class),
            new WorkflowTypeCatalogEntry(Kind.TYPE, "order", OrderState.class));
}
```

- **Names.** A contract entry is named by the non-empty `@WorkflowContract.value()`, else by the interface's binary name. `@WorkflowContract` has no other attribute: a contract that sets `definitionId` or `definitionVersion` fails compilation. A type entry is named by `@WorkflowType.value()`. An invalid contract (one that fails the shape checks above) is not listed.
- **Order.** Entries are sorted by kind (contracts first) and then by name, so the generated source does not depend on the order the compiler visits the classes in.
- **Package.** The module is generated in the package given by `-Avertique.codegen.package` when set and non-blank, else the longest common package prefix of all the listed classes, else `vertique.generated.workflow`. The package is resolved from all the classes before any is left out.
- **Accessibility.** A class the module cannot name is left out of the catalog with a *mandatory* compiler warning that names the class, the module package and the `vertique.codegen.package` option; the build still compiles and the class is not mentioned in the generated source. A class is unreferenceable when it is not `public` (or is nested in a non-public type) and lives outside the module's package, when it is `private` nested, or when it is in the unnamed package. A left-out class stays usable in code definitions and in packaged documents; a document loaded through the definition service cannot name it and is refused with an unknown-name error. Make the class and its enclosing types public, point `vertique.codegen.package` at a package it is visible from, or list it in a hand-written catalog contributed with a `@Provides @IntoSet WorkflowTypeCatalog` method.
- **Name collision.** When a class with the module's name already resolves when the processor runs (a dependency generated a catalog module in the same package, or a source of the compilation declares one), the build fails with an error that names the module and the `vertique.codegen.package` option, and the processor writes no source. A module that the compilation's own class or source output directory already holds, left by an earlier build that was not cleaned, is not a collision: the processor regenerates it. Set `vertique.codegen.package` to a package that is unique to the module.
- **Inclusion in the component.** Add `GeneratedWorkflowTypeCatalogModule` to the application `@Component` next to `WorkflowCoreModule`, which declares the (initially empty) `Set<WorkflowTypeCatalog>`. An application whose classes live in several modules adds each module's catalog module; the catalogs are merged at startup, and two catalogs that list different classes under one name fail startup with `WorkflowTypeNameConflictException`.

---

## Runtime Integration

`WorkflowClientFactory` is the single entry point for both paths. Each time a client is created:

1. The contract's methods are validated first, unconditionally — a malformed contract fails identically with or without a generated proxy.
2. `GeneratedNames.companionFqn(contract, "_WorkflowClientProxy")` computes the expected generated class FQN.
3. `Class.forName(fqn, true, contract.getClassLoader())` attempts to load it:
   - `ClassNotFoundException` → silent fallback to the JDK reflective proxy (pre-codegen behavior).
   - `ReflectiveOperationException | LinkageError` → loud `WorkflowClientProxyLinkageException` (present-but-broken class; indicates a build/codegen inconsistency).
4. On success, the `(WorkflowOperations, WorkflowClientBinding)` constructor is invoked and the instance returned. Subsequent contract calls have zero reflection.

**Definition checks are not made at client creation.** Client creation validates the contract's methods only; it does not resolve a plan or compare payload types with it. A payload that does not match the registered definition is reported when the client starts or signals. The factory always validates the contract before selecting either path, so the generated proxy cannot bypass the runtime rules.

---

## Compile-Time Validation Summary

| Violation | Level | Example message |
|-----------|-------|----------------|
| `@WorkflowContract` not on an interface | ERROR | `OrderWorkflow must be an interface` |
| Blank, over-long or control-character `@WorkflowContract.value()` | ERROR | `The contract name of com.acme.OrderWorkflow is invalid: it is blank` (an over-long name is reported as `longer than 255 code points` and one with a control character as `not free of control characters`; the name is never echoed) |
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
| `@WorkflowContract` or `@WorkflowType` class not accessible from the catalog module's package | MANDATORY WARNING | `@WorkflowType com.acme.hidden.Hidden is not accessible from package 'com.acme', where GeneratedWorkflowTypeCatalogModule is generated, so it is left out of the catalog ...` |
| A class named like the catalog module already exists | ERROR | `Cannot generate the workflow type catalog module com.acme.GeneratedWorkflowTypeCatalogModule: a class with that name already exists ... Set -Avertique.codegen.package ...` |

---

## Signal source processors

Package `dev.vertique.codegen.workflow.signal` holds the compile-time side of the annotated signal source forms. It is registered next to the contract processor, so one processor path entry serves both.

- **`KafkaSignalListenerProcessor`** generates `<Interface>_SignalSource` for each interface annotated `@KafkaSignalListener` (package `dev.vertique.workflow.kafka`, in `vertique-workflow-kafka`) whose methods carry `@WorkflowSignalTarget`. The class is a `KafkaSignalSource<T>` with an `@Inject` constructor and no other dependency; its `define` calls the same builder as a source written by hand: the topic, group and name the annotation states, the `dedupBy` accessor and, per target, the signal, the key accessor and the no-match policy. It names no key value and builds no payload mapping, and every accessor is a method reference, so the generated class compiles only when the message type has it. The annotation carries `@SignalSourceForm`, so `AutoWireProcessor` of `vertique-codegen-dagger` binds the generated class by name into `Set<KafkaSignalSource<?>>`; this processor names no Dagger type. A `@KafkaListener` interface or class that carries a target is an error that names `@KafkaSignalListener`, because the Kafka listener processor would leave its target without a source. `SignalListenerValidator` adds the Kafka-only rules: a blank `name`, `topic` or `groupId`, a `@JsonProfile` on the interface or a method, and a `@KafkaHandler` method beside a target.
- **`SignalTargetScan`** is the one scan of `@WorkflowSignalTarget` and its container on the methods of an interface, and the compile rules every transport shares: an undeclared signal, an accessor the message type lacks or one that does not return `String` (the no-argument method named like the property, else `getX()` or `isX()`), a generic message type, a message type that differs from the signal's type, targets that disagree on `dedupBy`, a target inherited from a super-interface, two message types on one interface, a target method with other than one parameter, both or neither of `key` and `businessKey`, and targets on a concrete class. Each is reported on the offending element, naming it. Its `DedupRequirement` parameter states whether `dedupBy` is required on a business-key target only (Kafka) or on every target (a transport with no offset to default to). It is an **INTERNAL** seam, outside the maturity promise of this module, public only because the Camel processor of another repository calls it, so that every transport applies one rule set and none copies one. The annotations are identified by fully qualified name.
- **`SignalTargetHostProcessor`** reports, on its type, a type that declares a target and that is neither annotated `@KafkaListener` nor annotated with an annotation that carries `@SignalSourceForm`, because no processor turns it into a source and its targets would never run.

The name of a generated source class comes from `SignalSourceNaming` in `vertique-codegen-core`, the one rule the generating processors and `AutoWireProcessor` both call.

| Violation | Level | Example message |
|-----------|-------|----------------|
| A signal the contract does not declare, an accessor the message type lacks or one that does not return `String`, a generic message type, a message type that is not the signal's, a business-key target with no `dedupBy`, targets that disagree on `dedupBy`, a target inherited from a super-interface, a target method without exactly one parameter, both or neither of `key` and `businessKey`, two message types on one interface | ERROR | `CarrierSignals.delivered names signal 'shipment-delivred', which contract OrdersContract does not declare` |
| `@KafkaSignalListener` on a class, or a blank `name`, `topic` or `groupId` | ERROR | `CarrierSignals sets a blank groupId on @KafkaSignalListener, but the signal source generated for it names its consumer by it; write a non-blank groupId` |
| A `@JsonProfile` or a `@KafkaHandler` method on a `@KafkaSignalListener` interface | ERROR | `CarrierSignals.onOther is a @KafkaHandler method, but CarrierSignals is a @KafkaSignalListener: the signal source generated for it consumes the topic, so a @KafkaHandler method beside a target would never receive a record` |
| A `@KafkaListener` type that carries a target | ERROR | `CarrierSignals carries @WorkflowSignalTarget but is annotated @KafkaListener, which generates no signal source, so its targets would never run; annotate the interface @KafkaSignalListener instead` |
| A target on a type that is neither `@KafkaListener` nor annotated with a `@SignalSourceForm` annotation | ERROR | `PlainClass carries @WorkflowSignalTarget, but it is not annotated with an annotation that carries @SignalSourceForm, such as @KafkaSignalListener, so no signal source is generated for it and its targets would never run` |

---

## Adoption

### Step 1 — use the application processor boundary

Applications inheriting `vertique-app-parent` declare the workflow runtime capabilities they use
and receive the complete processor facade automatically. Custom-parent applications import
`vertique-bom` and configure only the versionless Dagger and `vertique-codegen-all` processor
paths. See `docs/packaging.md`.

### Step 2 — Create clients from the factory

Typed clients are not provided by Dagger. Inject `WorkflowClientFactory` and create a client from the class of a registered definition, `factory.create(OrderDefinition.class)`, or from a contract and a definition id, `factory.create(OrderWorkflow.class, "orders")`. Either form returns the generated proxy when the processor is on the build path and falls back to the reflective proxy otherwise; removing the processor is safe. `WorkflowClientFactory` is retained and not deprecated. `WorkflowProxyValidator` is an internal framework seam that the factory calls; applications do not depend on it.

Also add `GeneratedWorkflowTypeCatalogModule` when the application loads documents at runtime or ships packaged documents that name annotated types by logical name.

### Processor Options

| Option | Default | Description |
|--------|---------|-------------|
| `vertique.codegen.package` | — | Package for `GeneratedWorkflowTypeCatalogModule`; overrides LCP resolution |

> The definition a client starts is **not** known at compile time — the processor cannot evaluate a
> `WorkflowDefinition`'s `definitionId()` method body, and the definition normally lives in a different
> module. The generated proxy therefore embeds no definition id or version: both come from the client binding
> at runtime.

---

## Module Dagger Bindings

The processor emits one Dagger module, `GeneratedWorkflowTypeCatalogModule`, placed in the package determined by package-resolution order: one `@Provides @IntoSet` method contributes the compilation's `WorkflowTypeCatalog`. The application must add it to its `@Component` for the catalog to take effect.

Generated proxies are discovered at runtime via `Class.forName` inside `WorkflowClientFactory` and need no Dagger graph participation.

---

## Dependencies

- `dev.vertique:vertique-codegen-core` — `CodegenContext`, `TypeResolver`, `AnnotationMirrors`, `Diagnostics`, `DaggerModuleWriter`, `Identifiers`, `PackageResolver`, `SignalSourceNaming`
- `dev.vertique:vertique-workflow-core` — `@WorkflowContract`, `@WorkflowStart`, `@WorkflowSignal`, `@WorkflowQuery`, `@IdempotencyKey`, `@BusinessKey`, `@SubjectRef`, `@SignalDedupKey`, `IdempotencyKeyed`, `BusinessKeyed`, `SubjectReferenced`, `SignalDedupKeyed`, `WorkflowInstanceId`, `WorkflowView`, `WorkflowSubjectRef`, `WorkflowOperations`, `WorkflowClientBinding`. **Test-scope in this processor module** (the processor only needs these types to compile its own tests, not at processing time). A **consuming application** needs `vertique-workflow-core` on its normal compile/runtime classpath regardless — it owns the contract annotations the app authors against, and the generated proxy references `WorkflowOperations`/`WorkflowClientBinding` — so adding the processor never introduces a new runtime dependency.
- `com.palantir.javapoet:javapoet` — source generation (compile-only; not on runtime classpath)
- `javax.annotation.processing` APIs — part of the JDK; not a separate Maven dependency
