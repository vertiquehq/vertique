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

Runtime selection is transparent: `DelayedJobClientFactory.create` tries `Class.forName` for the generated proxy first, falls back to the JDK `DelayedJobClientProxy` on `ClassNotFoundException`, and throws `IllegalStateException` when a generated class is present but broken.

The processor also generates a single aggregate `GeneratedDelayedJobClientsModule` Dagger module whose `@Provides @Singleton` bindings each delegate to `DelayedJobClientFactory.create({Contract}.class)`, so an application binds every typed client by listing one module in its `@Component` instead of hand-writing a provider per contract.

In addition to the performance win, the processor lifts contract-shape validation to compile time: an unresolvable payload type, duplicate contract names, or extra instance methods on the contract all surface as build errors rather than startup failures or silent misbehavior.

---

## Key Classes

### `DelayedJobContractProcessor`

`AbstractProcessor` registered via `META-INF/services/javax.annotation.processing.Processor`. Entry point for the round-based processing lifecycle.

```
@SupportedAnnotationTypes("dev.vertique.job.delayed.DelayedJobContract")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions({
    "vertique.codegen.package",                          // package for GeneratedDelayedJobClientsModule; ignored for proxy placement (see below)
    "vertique.codegen.delayedjob.requireExecutor"        // default: false
})
```

Lifecycle:
1. `init(env)` — instantiates `CodegenContext`, `DelayedJobContractScanner`, `DelayedJobValidator`, `DelayedJobProxyEmitter`, `DelayedJobClientsModuleEmitter`.
2. `process(annotations, round)`:
   - Collects `@DelayedJobContract`-annotated `TypeElement`s.
   - For each: scans into `DelayedJobContractModel`, runs all validators, emits proxy for valid models.
   - Emits one `GeneratedDelayedJobClientsModule` covering all valid models in the unit.
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
| Contract must not declare type parameters | `ERROR` | Blocks emission |
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

### `DelayedJobClientsModuleEmitter`

Generates a single `GeneratedDelayedJobClientsModule` covering all valid contracts in the compilation unit. Nothing is emitted when the unit has no valid contract, so an application without delayed jobs has no empty module to install.

**Delegate to the factory, never return the proxy directly.** Every generated binding is:

```java
@Generated("dev.vertique.codegen.delayed.processor.DelayedJobContractProcessor")
@Module
public class GeneratedDelayedJobClientsModule {
    @Provides
    @Singleton
    static DeliverWebhookJob provideDeliverWebhookJobClient(DelayedJobClientFactory factory) {
        return factory.create(DeliverWebhookJob.class);
    }
}
```

The body delegates to `DelayedJobClientFactory.create({Contract}.class)` and never constructs the generated proxy directly. The factory owns the contract checks and the per-contract config merge (annotation defaults overlaid with `delayedJob.contracts.{name}.*`); a binding that constructed the proxy directly would bypass both. The factory selects the generated proxy internally via `Class.forName`, so the injected instance is still the zero-reflection proxy.

**Package resolution order:**
1. `-Avertique.codegen.package` when set and non-blank.
2. Longest-common-package-prefix of all contract packages.
3. `vertique.generated.delayedjob` when the LCP is empty.

This option moves the module only — proxies stay pinned to their contract's package (see [Origin-Package Pinning](#origin-package-pinning)).

**Simple-name disambiguation.** Two contracts with the same simple name in different packages would produce two `provide{Name}Client` methods differing only in return type, which does not compile. The second and later bindings are suffixed with the contract's flattened fully-qualified name (`provideJobClient_com_foo_Job`) instead, so the collision never fails the build. Bindings are emitted in contract-FQN order, so for a given set of contracts the names are stable; adding a contract that sorts earlier moves the unsuffixed name to it and renames the incumbent's method. Nothing references these names by hand, so that is cosmetic.

**Unreferenceable contracts are skipped, not bound.** A contract the module's package cannot name — a package-private contract (or one nested in a non-public type) outside the module's own package, a `private` nested contract even within it, or a contract in the unnamed package — is left unbound with a *mandatory* compiler warning naming the reason (mandatory so `-nowarn` cannot turn the skip silent). Emitting the binding anyway would produce a module that does not compile, and because javac compiles generated sources in the same task, that would break the application's build merely by putting the processor on the annotation-processor path, whether or not the application installs the module. A skipped contract keeps working through a hand-written `@Provides`; make the contract and its enclosing types public, or point `-Avertique.codegen.package` at a package it is visible from, to have it bound.

**One module per compilation unit — pin the package in multi-module builds.** The module's simple name is fixed, so two Maven modules whose contracts resolve to the same longest common prefix each emit `{lcp}.GeneratedDelayedJobClientsModule` into their own jar, and only one survives on the application's classpath. The symptom is a Dagger `MissingBinding` error for the shadowed module's contracts — fail-closed, never a wrong binding, but the error is far from the cause. Give each such module its own output package:

```xml
<compilerArgs>
    <arg>-Avertique.codegen.package=com.acme.orders.generated</arg>
</compilerArgs>
```

Relatedly, the module is regenerated from whatever contracts the current compilation unit contains. An IDE compiling a single changed file can therefore rewrite it from a subset; a full module rebuild (which Maven always does) restores the complete set.

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

The generated proxy is always emitted into the contract's own package (`ctx.packageNameOf(contract)`), never the `-Avertique.codegen.package` override. The runtime lookup derives the class name from the contract's binary name, so relocating the proxy would break discovery without a corresponding change to the lookup logic. Do not set `-Avertique.codegen.package` expecting it to control proxy placement — it controls only where `GeneratedDelayedJobClientsModule` is written, which is safe because that module is referenced by name from the application `@Component` rather than discovered reflectively.

### Nested-Contract FQN Translation

`Class.getName()` uses `$` as the nested-type separator (`com.example.Outer$Inner`). `GeneratedNames.companionFqn` translates this to `com.example.Outer_Inner_DelayedJobProxy`, which is the name the emitter produces for a nested contract interface. `Class.forName` receives this translated name, so discovery works correctly for both top-level and nested contracts.

---

## Compile-Time Validation Summary

| Violation | Level | Example message |
|-----------|-------|----------------|
| Contract declares type parameters | ERROR | `DeliverWebhookJob must not declare type parameters — a @DelayedJobContract takes its payload type from DelayedJobClient<P>` |
| Contract not referenceable from the generated module's package | MANDATORY_WARNING | `@DelayedJobContract com.foo.HiddenJob is not accessible from package 'com', where GeneratedDelayedJobClientsModule is generated, so it is left unbound` |
| Contract does not extend `DelayedJobClient<P>` | ERROR | `DeliverWebhookJob must extend DelayedJobClient<P>` |
| Payload type `P` cannot be resolved | ERROR | `DeliverWebhookJob: could not resolve DelayedJobClient<P> payload type` |
| Duplicate contract `name()` in same unit | ERROR | `Duplicate @DelayedJobContract name "deliver-webhook": ... and ...` |
| Extra instance method on contract | ERROR | `@DelayedJobContract DeliverWebhookJob declares method customMethod() — contracts may only expose DelayedJobClient enqueue overloads` |
| No same-unit `DelayedJobExecutor` (default) | WARNING | `No DelayedJobExecutor for DeliverWebhookJob found in this compilation unit` |
| No same-unit `DelayedJobExecutor` (opt-in) | ERROR | Same message |
| Same-unit executor payload mismatch | ERROR | `DeliverWebhookJobImpl payload ... does not match contract DeliverWebhookJob payload ...` |

---

## Adoption

### Step 1 — use the application processor boundary

Applications inheriting `vertique-app-parent` declare `vertique-job-delayed` as a runtime
dependency and receive the complete processor facade automatically. Custom-parent applications
import `vertique-bom` and configure only the versionless Dagger and `vertique-codegen-all`
processor paths. See `docs/packaging.md`.

### Step 2 — include the generated module

Add `GeneratedDelayedJobClientsModule` to the application `@Component`. It provides one `@Singleton` binding per `@DelayedJobContract` in the compilation unit:

```java
@Component(modules = {
    // ... existing modules ...
    GeneratedDelayedJobClientsModule.class
})
interface AppComponent { ... }
```

Remove any hand-written `@Provides` for a contract when adopting the module — keeping both makes the binding a Dagger duplicate and fails graph validation. Skipping this step leaves the generated proxies on the classpath unbound, so every `@Inject` site for a contract fails Dagger's compile-time graph validation rather than silently resolving to something unexpected.

Both steps are individually reversible: removing the processor reverts all contracts to the JDK reflective proxy — the existing `DelayedJobClientProxy` is retained and is not deprecated — and removing the module from the `@Component` means contracts must be provided manually again.

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

The processor emits one Dagger module: `GeneratedDelayedJobClientsModule` (placed in the package determined by package-resolution order). Each `@Provides @Singleton` method returns `factory.create({Contract}.class)`. The application must add this module to its `@Component` for the bindings to take effect.

Generated proxies themselves are discovered at runtime via `Class.forName` in `DelayedJobClientFactory` and require no further Dagger graph participation beyond that binding. The server-side executor bindings are a separate concern owned by `vertique-codegen-dagger`'s `GeneratedDelayedJobsModule`.

---

## Dependencies

- `dev.vertique:vertique-codegen-core` — `CodegenContext`, `TypeResolver`, `AnnotationMirrors`, `Diagnostics`, `DaggerModuleWriter`, `Identifiers`, `PackageResolver`
- `dev.vertique:vertique-job-delayed` — `@DelayedJobContract`, `DelayedJobClient`, `DelayedJobClientFactory`, `DelayedJobExecutor`, `DelayedJobService`, `DelayedJobOptions` (processor classpath only; not on runtime classpath). A **consuming application** already carries `vertique-job-delayed` on its normal compile/runtime classpath — it owns the contract annotation the app authors against — so the generated module's reference to `DelayedJobClientFactory` introduces no new runtime dependency.
- `com.palantir.javapoet:javapoet` — source generation (compile-only; not on runtime classpath)
- `javax.annotation.processing` APIs — part of the JDK; not a separate Maven dependency
