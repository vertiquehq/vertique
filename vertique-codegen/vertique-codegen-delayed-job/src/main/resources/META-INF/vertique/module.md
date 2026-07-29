<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Codegen Delayed-Job Static Proxies Module

> **Status:** Beta
> **Package:** `dev.vertique.codegen.delayed.processor`
> **Artifact:** `vertique-codegen-delayed-job`
> **Depends on:** codegen-core, job-delayed (processor classpath only)

Annotation processor that eliminates per-call reflection in the delayed-job enqueue hot path. For each `@DelayedJobContract` interface it generates a static `{Contract}_DelayedJobProxy` class that replaces the JDK dynamic proxy built by `DelayedJobClientFactory`, removing `InvocationHandler` dispatch and baking the six enqueue overloads directly into concrete methods.

Runtime selection is transparent: `DelayedJobClientFactory.create` tries `Class.forName` for the generated proxy first, falls back to the JDK `DelayedJobClientProxy` on `ClassNotFoundException`, and throws `IllegalStateException` when a generated class is present but broken. No Dagger graph changes are required.

In addition to the performance win, the processor lifts contract-shape validation to compile time: an unresolvable payload type, duplicate contract names, or extra instance methods on the contract all surface as build errors rather than startup failures or silent misbehavior.

---

## Key Classes

### `DelayedJobContractProcessor`

`AbstractProcessor` registered via `META-INF/services/javax.annotation.processing.Processor`. Entry point for the round-based processing lifecycle.

```
@SupportedAnnotationTypes("dev.vertique.job.delayed.DelayedJobContract")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions({
    "vertique.codegen.package",                          // inherited from codegen-core; ignored for proxy placement (see below)
    "vertique.codegen.delayedjob.requireExecutor"        // default: false
})
```

Lifecycle:
1. `init(env)` — instantiates `CodegenContext`, `DelayedJobContractScanner`, `DelayedJobValidator`, `DelayedJobProxyEmitter`.
2. `process(annotations, round)`:
   - Collects `@DelayedJobContract`-annotated `TypeElement`s.
   - For each: scans into `DelayedJobContractModel`, runs all validators, emits proxy for valid models.
3. Returns `false` so other processors (Dagger, Lombok) see the same elements.

### `DelayedJobContractScanner` / `DelayedJobContractModel`

`DelayedJobContractScanner.scan(TypeElement)` reads the `@DelayedJobContract` annotation attributes and resolves the payload type parameter `P` from `DelayedJobClient<P>` via `CodegenContext.typeResolver()`. The result is an immutable `DelayedJobContractModel` record:

```java
record DelayedJobContractModel(
    TypeElement contractType,
    String name,
    int maxAttempts,
    String queue,
    int priority,
    TypeMirror payloadType   // null when P cannot be resolved
) {}
```

### `DelayedJobValidator`

Enforces contract shape within a single compilation unit.

| Check | Diagnostic | Effect on emission |
|-------|-----------|-------------------|
| Contract must extend `DelayedJobClient<P>` | `ERROR` | Blocks emission |
| Payload type `P` must be resolvable | `ERROR` | Blocks emission |
| Duplicate `@DelayedJobContract.name()` in one compilation unit | `ERROR` | Blocks emission of the duplicate |
| Contract must expose no instance method other than `DelayedJobClient`'s six `enqueue` overloads (declared, inherited, abstract, or default — Object/static methods allowed) | `ERROR` | Blocks emission |
| No same-unit `DelayedJobExecutor` for the contract | `WARNING` (default) or `ERROR` (opt-in) | Does not block emission |
| Same-unit executor payload type mismatch | `ERROR` | Does not block emission of the contract's proxy |

The full-member method check (the fourth row) uses `Elements.getAllMembers`, not `getEnclosedElements`, so it catches abstract methods inherited through intermediate interfaces and `default` methods added to an intermediate superinterface. Both diverge between the generated and reflective routes if not caught here.

### `DelayedJobProxyEmitter`

Generates `{Contract}_DelayedJobProxy` in the contract's own package. Key properties of the generated class:

- `public final`, implements the contract interface, annotated `@Generated("...DelayedJobContractProcessor")`.
- Constructor `(DelayedJobService, DelayedJobContract, JsonObject)` — resolves `effectiveMaxAttempts`, `effectiveQueue`, `effectivePriority` from the config object with annotation-default fallback (same merge order as `DelayedJobClientProxy`).
- Each of the six `enqueue` overloads delegates to a private `doEnqueue(P, Instant, SqlClient, DelayedJobOptions)` helper; `Duration`-bearing overloads normalize to `Instant.now().plus(delay)`. Null-`SqlClient` transactional overloads fail fast with `NullPointerException`.
- `doEnqueue` applies `DelayedJobOptions` overrides in the same order as the reflective proxy, and routes through `DelayedJobService.enqueuePremerged` — public specifically so a generated proxy in the contract's own package can call it — when `options.premergedMetadata()` is non-null.
- `toString` returns `"DelayedJobClient[name]"`; `equals`/`hashCode` use identity semantics — identical to the JDK proxy's `Object`-method handling.

Example generated class for a contract with a single payload type:

```java
@Generated("dev.vertique.codegen.delayed.processor.DelayedJobContractProcessor")
public final class DeliverWebhookJob_DelayedJobProxy implements DeliverWebhookJob {

    private final DelayedJobService jobService;
    private final String handlerName;
    private final int effectiveMaxAttempts;
    private final String effectiveQueue;
    private final int effectivePriority;

    public DeliverWebhookJob_DelayedJobProxy(
            DelayedJobService jobService,
            DelayedJobContract annotation,
            JsonObject contractConfig) {
        this.jobService = jobService;
        this.handlerName = annotation.name();
        this.effectiveMaxAttempts = contractConfig.getInteger("maxAttempts", annotation.maxAttempts());
        this.effectiveQueue = contractConfig.getString("queue", annotation.queue());
        this.effectivePriority = contractConfig.getInteger("priority", annotation.priority());
    }

    @Override
    public Future<UUID> enqueue(WebhookPayload payload) {
        return doEnqueue(payload, null, null, null);
    }

    @Override
    public Future<UUID> enqueue(WebhookPayload payload, Instant runAt) {
        return doEnqueue(payload, runAt, null, null);
    }

    // ... remaining four overloads omitted for brevity

    @Override
    public String toString() { return "DelayedJobClient[" + handlerName + "]"; }

    @Override
    public boolean equals(Object o) { return this == o; }

    @Override
    public int hashCode() { return System.identityHashCode(this); }
}
```

---

## Runtime Integration

### `Class.forName` Discovery Contract

`DelayedJobClientFactory.create` locates the generated proxy via `GeneratedNames.companionFqn(contractInterface, "_DelayedJobProxy")` (in `vertique-core`). This helper replaces the `$` nested-class separator in `Class.getName()` with `_` to match the processor-emitted name:

```java
// Prefer the generated static proxy when present (zero reflection); fall back to the JDK
// dynamic proxy otherwise. A present-but-broken generated class fails loudly.
String generatedFqn = GeneratedNames.companionFqn(contractInterface, "_DelayedJobProxy");
try {
    Class<?> generated = Class.forName(generatedFqn, true, contractInterface.getClassLoader());
    Constructor<?> ctor = generated.getDeclaredConstructor(
            DelayedJobService.class, DelayedJobContract.class, JsonObject.class);
    return contractInterface.cast(ctor.newInstance(jobService, annotation, contractConfig));
} catch (ClassNotFoundException notGenerated) {
    // No generated proxy — fall back to the JDK dynamic proxy. This is the pre-codegen behavior.
} catch (ReflectiveOperationException | LinkageError broken) {
    throw new IllegalStateException(
            "Generated proxy %s is present but could not be instantiated".formatted(generatedFqn), broken);
}
// fall through to JDK proxy...
```

The one-time `Class.forName` is the only reflective call in the optimized path. Subsequent enqueue calls have zero reflection.

### Origin-Package Pinning

The generated proxy is always emitted into the contract's own package (`ctx.packageNameOf(contract)`), never the `-Avertique.codegen.package` override. The runtime lookup derives the class name from the contract's binary name, so relocating the proxy would break discovery without a corresponding change to the lookup logic. Do not set `-Avertique.codegen.package` expecting it to control proxy placement for this processor.

### Nested-Contract FQN Translation

`Class.getName()` uses `$` as the nested-type separator (`com.example.Outer$Inner`). `GeneratedNames.companionFqn` translates this to `com.example.Outer_Inner_DelayedJobProxy`, which is the name the emitter produces for a nested contract interface. `Class.forName` receives this translated name, so discovery works correctly for both top-level and nested contracts.

---

## Compile-Time Validation Summary

| Violation | Level | Example message |
|-----------|-------|----------------|
| Contract does not extend `DelayedJobClient<P>` | ERROR | `DeliverWebhookJob must extend DelayedJobClient<P>` |
| Payload type `P` cannot be resolved | ERROR | `DeliverWebhookJob: could not resolve DelayedJobClient<P> payload type` |
| Duplicate contract `name()` in same unit | ERROR | `Duplicate @DelayedJobContract name "deliver-webhook": ... and ...` |
| Extra instance method on contract | ERROR | `@DelayedJobContract DeliverWebhookJob declares method customMethod() — contracts may only expose DelayedJobClient enqueue overloads` |
| No same-unit `DelayedJobExecutor` (default) | WARNING | `No DelayedJobExecutor for DeliverWebhookJob found in this compilation unit` |
| No same-unit `DelayedJobExecutor` (opt-in) | ERROR | Same message |
| Same-unit executor payload mismatch | ERROR | `DeliverWebhookJobImpl payload ... does not match contract DeliverWebhookJob payload ...` |

---

## Adoption

Applications inheriting `vertique-app-parent` declare `vertique-job-delayed` as a runtime
dependency and receive the complete processor facade automatically. Custom-parent applications
import `vertique-bom` and configure only the versionless Dagger and `vertique-codegen-all`
processor paths. See `docs/packaging.md`.

No `@Component` changes are required. Removing the processor reverts all contracts to the JDK reflective proxy — the existing `DelayedJobClientProxy` is retained and is not deprecated.

### `requireExecutor` Option

```xml
<compilerArgs>
    <arg>-Avertique.codegen.delayedjob.requireExecutor=true</arg>
</compilerArgs>
```

Promotes the "no same-unit executor" diagnostic from WARNING to ERROR.

> **Single-module builds only.** The option false-positives when the contract interface and its `DelayedJobExecutor` live in different Maven modules: the annotation processor sees only the current compilation unit and cannot find the executor across module boundaries. Do not enable this option in multi-module builds where contracts and executors are split across modules.

---

## Module Dagger Bindings

None. The processor emits no Dagger binding modules. Generated proxies are discovered at runtime via `Class.forName` in `DelayedJobClientFactory` and do not require any Dagger graph participation.

---

## Dependencies

- `dev.vertique:vertique-codegen-core` — `CodegenContext`, `TypeResolver`, `AnnotationMirrors`, `Diagnostics`, `Identifiers`, `PackageResolver`
- `dev.vertique:vertique-job-delayed` — `@DelayedJobContract`, `DelayedJobClient`, `DelayedJobExecutor`, `DelayedJobService`, `DelayedJobOptions` (processor classpath only; not on runtime classpath)
- `com.palantir.javapoet:javapoet` — source generation (compile-only; not on runtime classpath)
- `javax.annotation.processing` APIs — part of the JDK; not a separate Maven dependency
