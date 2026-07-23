<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Codegen Kafka Consumer Metadata Module

> **Status:** Beta
> **Package:** `dev.vertique.codegen.kafka.processor`
> **Artifact:** `vertique-codegen-kafka`
> **Depends on:** codegen-core, kafka (processor classpath only)

Annotation processor that eliminates boot-time reflective scan and classify for Kafka consumers. For each `@KafkaListener` type (Model 3 routing interface or Model 4 `KafkaRecordHandler` class) and each class carrying `@KafkaSource` methods (Model 1), it generates a `{Consumer}_BindingMeta` companion holding `public static final List<KafkaBindingMeta> METAS` of compile-time-knowable binding metadata. `KafkaConsumerScanner` consults a `GeneratedBindingMetaLoader` at each scan hook and skips the reflective annotation read, parameter classification, and generic resolution for any consumer whose companion is present.

In addition to the performance win, the processor lifts consumer-shape validation to compile time: blank topic, conflicting match rules, duplicate route selectors, unsupported handler parameters, and mismatched direct-handler value types all surface as build errors rather than startup failures.

The reflective `KafkaConsumerScanner` is retained as the runtime fallback; an application opts in by adding this processor to `annotationProcessorPaths`. No Dagger graph changes are required.

See ADR-0072 for the metadata shape decisions, the three-way `Kind` discriminator rationale, and the dual-hook integration design.

---

## Package Layout

| Package | Contents |
|---------|----------|
| `dev.vertique.codegen.kafka.processor` | `KafkaConsumerProcessor`, `KafkaCodegenAnnotations` |
| `dev.vertique.codegen.kafka.processor.scan` | `KafkaListenerScanner`, `KafkaSourceScanner`, `KafkaParamClassifier`, `KafkaParamModel`, `KafkaAttrs`, `ListenerModel`, `RouteModel`, `KafkaSourceModel`, `KafkaSourceMethodModel` |
| `dev.vertique.codegen.kafka.processor.validate` | `KafkaConsumerValidator`, `KafkaSourceValidator`, `ListenerTopicValidator`, `HandlerMatchValidator`, `HandlerParamValidator`, `HandlerReturnTypeValidator`, `DirectHandlerValidator` |
| `dev.vertique.codegen.kafka.processor.emit` | `BindingMetaEmitter` |

---

## Key Classes

### `KafkaConsumerProcessor`

`AbstractProcessor` registered via `META-INF/services/javax.annotation.processing.Processor`. Entry point for the round-based processing lifecycle.

```
@SupportedAnnotationTypes({
    "dev.vertique.kafka.KafkaListener",
    "dev.vertique.kafka.KafkaSource"
})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions("vertique.codegen.package")   // inherited from codegen-core; does not control companion placement (see below)
```

Lifecycle:
1. `init(env)` — instantiates `CodegenContext`, a shared `KafkaParamClassifier`, `KafkaListenerScanner`, `KafkaConsumerValidator`, `KafkaSourceScanner`, `KafkaSourceValidator`, `BindingMetaEmitter`.
2. `process(annotations, round)` (runs exactly once — the first non-final round):
   - Collects `@KafkaListener`-annotated `TypeElement`s; scans each into a `ListenerModel`; validates all.
   - Collects `@KafkaSource`-annotated `ExecutableElement`s; groups by enclosing `TypeElement`; scans each group into a `KafkaSourceModel`; validates all.
   - Iterates the union of origin types; calls `BindingMetaEmitter.emit(listenerModel, sourceModel)` — for a type carrying both a `@KafkaListener` and `@KafkaSource` methods, the emitter aggregates all metas into one companion.
3. Returns `false` so other processors (Dagger, Lombok) see the same elements.

**Dual-annotation aggregation:** A class that is both a `@KafkaListener` direct-handler (Model 4) and carries `@KafkaSource` methods produces one companion. The `METAS` list contains the HANDLER entry followed by all SOURCE entries. The runtime loader's `scanKafkaSources` hook filters to SOURCE metas; `scanListeners` takes HANDLER (or ROUTER) metas. See ADR-0072.

### Scanners

**`KafkaListenerScanner`** scans a `@KafkaListener`-annotated `TypeElement` into a `ListenerModel`. It reads annotation attributes via `KafkaAttrs`, inspects the type's kind (interface → Model 3 router; class implementing `KafkaRecordHandler<V>` → Model 4 direct handler), and extracts `@KafkaHandler` methods into `RouteModel` entries. A shared `KafkaParamClassifier` determines payload type by walking method parameters.

**`KafkaSourceScanner`** scans all `@KafkaSource`-annotated methods on one impl class into a `KafkaSourceModel` (one `KafkaSourceMethodModel` per method).

### Validators

`KafkaConsumerValidator` orchestrates the listener validators; `KafkaSourceValidator` orchestrates the source validators.

**Listener validators:**

| Validator | Check | Level |
|-----------|-------|-------|
| `ListenerTopicValidator` | Blank `@KafkaListener#topic()` | ERROR |
| `HandlerMatchValidator` | `matchHeader`, `matchProperty`, `defaultHandler` mutual exclusivity; at most one `defaultHandler` per router; duplicate route selectors (same `matchHeader`+`matchValue` or `matchProperty`+`matchValue`) | ERROR |
| `HandlerParamValidator` | Payload parameter arity and type | ERROR |
| `HandlerReturnTypeValidator` | Return type must be `void` or `Future<Void>` | ERROR |
| `DirectHandlerValidator` | `@KafkaListener` class must implement `KafkaRecordHandler`; `@KafkaListener#valueType()` must be assignable to the handler's `V` parameter; raw-handler with unresolved `V` rejected | ERROR |

**Source validators:**

| Validator | Check | Level |
|-----------|-------|-------|
| `KafkaSourceValidator` | `@KafkaSource` on a service-impl method, not a contract interface method | ERROR |

A consumer with any ERROR-level violation is not emitted.

### `BindingMetaEmitter`

Generates `{Consumer}_BindingMeta` in the origin type's own package (`ctx.packageNameOf(origin)`), never in the `-Avertique.codegen.package` override. Key properties of the generated class:

- `public final`, annotated `@Generated("...KafkaConsumerProcessor")`.
- `public static final List<KafkaBindingMeta> METAS` — an unmodifiable list constructed as `List.of(...)` at class initialization.
- Each list element is a `new KafkaBindingMeta(...)` call with all compile-time-knowable fields inlined as literals or `.class` references. Parameterized payload types are erased before emission (`Envelope<X>.class` → `Envelope.class`), matching what the reflective path reads from `Method` parameter types.
- A `@KafkaSource` impl is emitted as `Kind.SOURCE` with `null` `valueType` and the impl **method name** in `targetOperation`; the **service class is NOT embedded** (there is no top-level `targetService` field). The method name keys the `ServiceMethodMeta` lookup in the live service registry at boot, where `ServiceTargetResolver` resolves the value type and address — embedding the service class would couple the companion to the contract class by name and still require runtime resolution. (The per-route `RouteMeta` of a `ROUTER` binding does carry both `targetService` and `targetOperation` for its `@DispatchTo` target.)

#### Companion origin-package pinning

The companion always lands in the origin type's package. The runtime lookup uses `GeneratedNames.companionFqn(consumerClass, "_BindingMeta")`, which reconstructs the same FQN. Do not set `-Avertique.codegen.package` expecting it to control companion placement.

#### Nested-type FQN translation

`GeneratedNames.companionFqn` flattens `Outer$Inner` → `Outer_Inner` before appending `_BindingMeta`. The emitter generates the companion with the same flattened name. Discovery works for both top-level and nested consumer types.

---

## Runtime Integration

`GeneratedBindingMetaLoader` (package-private in `dev.vertique.kafka`) is the bridge between the generated companions and `KafkaConsumerScanner`. The loader is invoked at two scan hooks:

| Hook | Triggered by | Metas consumed |
|------|-------------|---------------|
| `scanKafkaSources` | Model 1 (service registry iteration) | `Kind.SOURCE` metas only |
| `scanListeners` | Models 3/4 (`@KafkaConsumers` contributions) | `Kind.ROUTER` for a contributed `Class<?>`, `Kind.HANDLER` for a `KafkaRecordHandler` instance |

At each hook:

1. `GeneratedBindingMetaLoader.load(consumerClass)` attempts `Class.forName` for the companion. Returns `null` on `ClassNotFoundException` → reflective fallback. Throws `KafkaRegistrationException` if the companion is present but malformed.
2. On a non-null result, the hook filters to its own `Kind`, calls `toSourceEntry` / `toRouterEntry` / `toHandlerEntry`, and skips the reflective scan for that consumer.
3. The conversion methods use the same `ServiceTargetResolver` and `KafkaConsumerValidation.validateAndBuild` helper that the reflective scanner uses, ensuring identical `ConsumerEntry` output.

**What the generated path eliminates:** annotation reads, `KafkaParamClassifier` generic resolution, `TypeResolver.resolveTypeArgument` for direct handlers. **What the generated path does not eliminate:** runtime target resolution via `ServiceTargetResolver` (event-bus address, stable target id, one-way flag — these require the live `ServiceContractRegistry`).

The NFR's "≥50% lower scan time" is a scan/classify-fraction claim. Measuring the actual fraction requires a dedicated benchmark (deferred; see ADR-0072 Consequences).

**Loud-fail contract:** if a generated companion is found but has no `METAS` field, a wrong type, or an inaccessible field, `load` throws `KafkaRegistrationException` immediately. The loader never silently falls back to reflective scanning when a companion is present but broken.

**Parity guard:** `KafkaGeneratedVsReflectiveParityTest` asserts that the generated and reflective paths produce identical `ConsumerEntry`/`RouteEntry` output for Model 1, 3, and 4 fixtures, including generic-payload and inherited-handler cases.

---

## Compile-Time Validation Summary

| Violation | Level | Example message |
|-----------|-------|----------------|
| Blank `@KafkaListener#topic()` | ERROR | `@KafkaListener on OrderEventRouter must have a non-blank topic` |
| `matchHeader` and `matchProperty` both set | ERROR | `@KafkaHandler on onOrder: matchHeader and matchProperty are mutually exclusive` |
| `defaultHandler` with `matchHeader` or `matchProperty` | ERROR | `@KafkaHandler on onFallback: defaultHandler must not set matchHeader or matchProperty` |
| More than one `defaultHandler` per router | ERROR | `@KafkaListener OrderEventRouter: at most one @KafkaHandler may have defaultHandler=true` |
| Duplicate route selector within a router | ERROR | `@KafkaListener OrderEventRouter has multiple @KafkaHandler methods with the same header selector 'event-type'='created'; route selectors must be unique` — selector uniqueness, not payload-type uniqueness; the reflective runtime (`KafkaConsumerValidation.validateAndBuild`) enforces the same rule with the same wording |
| More than one payload parameter | ERROR | `@KafkaHandler method must declare exactly one payload parameter` — only **>1** payload is rejected; a **no-arg** handler is allowed (both paths use `Void`), and an unrecognized parameter type is treated as the payload. When parameters are present, parameter 0 must be the payload (see next row) — a context-only handler (e.g. `(KafkaRecordContext ctx)` with no payload) is therefore rejected |
| Payload parameter not first | ERROR | `@KafkaHandler method must declare its payload as the first parameter; context parameters must come after` — guarantees the generated route value type matches the reflective `params[0]` |
| Return type not `void` or `Future<Void>` | ERROR | `@KafkaHandler on onOrder: return type must be void or Future<Void>` |
| `@KafkaListener` class does not implement `KafkaRecordHandler` | ERROR | `@KafkaListener OrderEventHandler must implement KafkaRecordHandler<V>` |
| `@KafkaListener#valueType()` not assignable to handler `V` | ERROR | `@KafkaListener on OrderEventHandler: declared valueType OtherEvent is not assignable to handler type OrderEvent` |
| Raw-handler with unresolvable `V` | ERROR | `@KafkaListener on RawHandler: KafkaRecordHandler<V> type parameter V cannot be resolved; declare valueType` |
| `@KafkaSource` on a contract interface method | ERROR | `@KafkaSource on OrderService.processOrder must be on a service-implementation method, not a contract interface` |

---

## Adoption

Add the processor to `<annotationProcessorPaths>`. Use `combine.children="append"` when the parent POM already declares processor paths (e.g., for Dagger or Lombok):

```xml
<annotationProcessorPaths combine.children="append">
    <path>
        <groupId>dev.vertique</groupId>
        <artifactId>vertique-codegen-kafka</artifactId>
    </path>
</annotationProcessorPaths>
```

No `@Component` changes are required. `KafkaConsumerScanner` automatically uses `GeneratedBindingMetaLoader` when a companion is present. Removing the processor reverts all consumers to reflective scanning — the existing reflective path is retained and is not deprecated.

---

## Module Dagger Bindings

None. The processor emits no Dagger binding modules. Generated companions are discovered at runtime via `Class.forName` inside `GeneratedBindingMetaLoader` and do not require any Dagger graph participation. CG-002 (`vertique-codegen-dagger`) retains ownership of the `@KafkaConsumers` multibinding wiring.

---

## Dependencies

- `dev.vertique:vertique-codegen-core` — `CodegenContext`, `TypeResolver`, `AnnotationMirrors`, `Diagnostics`, `Identifiers`, `PackageResolver`
- `dev.vertique:vertique-kafka-core` — `@KafkaListener`, `@KafkaHandler`, `@KafkaSource`, `KafkaRecordHandler`, `KafkaBindingMeta`, `ErrorStrategy`, `CommitStrategy`. **Test-scope in this processor module** (the processor only needs these types to compile its own tests, not at processing time). A **consuming application** needs `vertique-kafka-core` on its normal compile/runtime classpath — it carries the consumer annotations the app authors against, and the generated `{Consumer}_BindingMeta` references `KafkaBindingMeta`/`ErrorStrategy`/`CommitStrategy` — so adding the processor never pulls in a new runtime dependency. Applications also include at least one format module (`vertique-kafka-json` and/or `vertique-kafka-avro`) for serialization.
- `com.palantir.javapoet:javapoet` — source generation (compile-only; not on runtime classpath)
- `javax.annotation.processing` APIs — part of the JDK; not a separate Maven dependency

---

## Related ADRs

- ADR-0072: Kafka `KafkaBindingMeta` Shape and Registrar Integration — why `KafkaBindingMeta` carries only compile-time-knowable data (runtime target resolution deferred to boot), the three-way `Kind` discriminator, companion origin-package pinning, dual-hook load-prefer-with-reflective-fallback, erased value types in generated code, and the shared `KafkaConsumerValidation` extraction.
