<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Codegen Cron Build-Time Validation Module

> **Status:** Beta
> **Package:** `dev.vertique.codegen.cron.processor`
> **Artifact:** `vertique-codegen-cron`
> **Depends on:** `vertique-codegen-core` (compile), `vertique-job-cron` (compile — the processor invokes `new CronExpression(expr)` at processor-runtime to reuse the runtime parser)

`vertique-codegen-cron` is an annotation processor that shifts four `@CronJob` validation concerns from application startup to `mvn compile`. Errors that would previously surface as startup exceptions now surface as build errors with precise source locations.

The processor generates no code. It is pure validation. No Dagger modules, no class files beyond the processor itself.

---

## Overview

`CronJobRegistrar` (runtime) discovers `@CronJob` annotations by scanning `ServiceContractRegistry.entries()` on startup. Four categories of error are detectable earlier, from the annotation mirror alone:

| Concern | Runtime timing | Compile-time |
|---------|---------------|--------------|
| Cron expression syntax | `CronExpression` constructor in registrar | `CronExpressionValidator` |
| Service contract coupling + `@ServiceOperation` | `ContractDiscovery.findContract` + `OperationIdResolver` | `ServiceCouplingValidator` |
| Policy value bounds and contradictions | `CronScheduler` / `CronJobDefinition` construction | `PolicyValueValidator` |
| Duplicate `id` within the same class | `CronJobRegistrar` id collision | `DuplicateIdValidator` |

Config-only cron jobs (`cron.jobs.{id}.target = "service:..."`) are not visible to APT and remain startup-validated.

---

## Key Classes

### `CronJobProcessor`

`AbstractProcessor` registered via `META-INF/services/javax.annotation.processing.Processor`.

```
@SupportedAnnotationTypes("dev.vertique.job.cron.CronJob")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
```

`init()` constructs one `CodegenContext` and one instance of each validator. `process()` groups annotated `ExecutableElement`s by enclosing `TypeElement` and dispatches to the four validators. Two early rejections occur before grouping:

- `@CronJob` on an interface method → ERROR (mirrors `CronJobRegistrar.java:159-162`)
- `@CronJob` on an abstract class method → ERROR (mirrors `getDeclaredMethods()` non-recursive scan)

The `@CronJob` annotation mirror is resolved once per method (via `AnnotationMirrors.findByFqn`) and carried in a `MethodMirror` record so attribute reads are not repeated across validators.

Returns `false` from `process()` so Dagger, Lombok, and other processors see the same elements unmodified.

### `CronAnnotations`

`public final` constants class. Holds FQN strings for all four annotation types referenced by the validators:

| Constant | Value |
|----------|-------|
| `CRON_JOB` | `dev.vertique.job.cron.CronJob` |
| `SERVICE_HANDLER` | `dev.vertique.services.ServiceHandler` |
| `SERVICE_CONTRACT` | `dev.vertique.services.ServiceContract` |
| `SERVICE_OPERATION` | `dev.vertique.services.ServiceOperation` |

Centralising FQNs here means a package relocation in another module shows up as one edit, not five.

### `CronExpressionValidator`

Calls `new CronExpression(expr)` inside a `try/catch(IllegalArgumentException)`. On failure, emits ERROR with the runtime parser's exact message, prefixed `@CronJob.cron:`. Users see the same error text at build time and at runtime.

### `ServiceCouplingValidator`

Mirrors `ContractDiscovery.findContract` exactly. Resolution order:

1. If the owner implements `ServiceHandler<C>`, resolve `C` via `TypeResolver.resolveTypeArgument`. Then verify:
   - `C` is annotated `@ServiceContract`
   - The owner does NOT also directly implement `C` (handler+direct ambiguity)
   - The owner has no additional `@ServiceContract` interfaces beyond `C`
2. Otherwise (direct-implementation pattern), collect all transitive interfaces annotated `@ServiceContract`. Require exactly one.

After a contract is resolved, finds the contract method by name only (mirroring `CronJobRegistrar.findMetaForMethod` — parameter signatures are not compared). Then:

- Requires `@ServiceOperation` on the matched contract method, unconditionally. The runtime rejects all annotation-discovered cron jobs whose contract method lacks `@ServiceOperation` regardless of `mode` or `tracked`.
- Rejects a blank `@ServiceOperation("")` value (mirrors `OperationIdResolver.resolveStableOperationId`).

Uses `ctx.typeResolver().allSupertypes(...)` and `resolveTypeArgument(...)` — no hand-rolled BFS.

### `PolicyValueValidator`

Validates four attributes from the pre-resolved `@CronJob` mirror:

| Attribute | Check | Severity |
|-----------|-------|----------|
| `id` | must not be blank | ERROR |
| `maxAttempts` | must be `>= 1` | ERROR |
| `overlapPolicy` + `mode` | `QUEUE_ONE` requires `EVERY_INSTANCE`; `SINGLE_INSTANCE` requires `SKIP` | ERROR |
| `timezone` | `ZoneId.of(tz)` must not throw | WARNING |

The timezone check is a warning, not an error, because the value may be overridden by external config at runtime.

### `DuplicateIdValidator`

Takes the `List<MethodMirror>` for a single class (mirrors already resolved by the processor). Groups methods by `id` attribute. For any group with more than one method, emits ERROR on the second and subsequent occurrences, identifying the first declaration in the message.

Methods with blank or missing `id` values are skipped here (already caught by `PolicyValueValidator`).

Cross-class duplicate detection is out of scope — it would require cross-compilation-unit visibility (Maven plugin territory).

---

## Diagnostic Messages

All error messages are worded to match the runtime violation strings where applicable.

| Trigger | Kind | Message pattern |
|---------|------|-----------------|
| `@CronJob` on interface method | ERROR | `Place @CronJob on the implementation method, not the contract interface: {Class}.{method}()` |
| `@CronJob` on abstract class method | ERROR | `@CronJob must be declared on a concrete service implementation, not on the abstract superclass {FQN}; runtime scans only declared methods of the registered service instance` |
| Invalid cron expression | ERROR | `@CronJob.cron: {parser message}` |
| No `@ServiceContract` interface | ERROR | `{FQN} does not implement any @ServiceContract-annotated interface` |
| Multiple `@ServiceContract` interfaces | ERROR | `{FQN} implements multiple @ServiceContract interfaces: {names}` |
| `ServiceHandler<C>` but `C` is not `@ServiceContract` | ERROR | `{FQN} implements ServiceHandler<{C}> but {C} is not annotated with @ServiceContract` |
| Handler + direct both implemented | ERROR | `{FQN} implements both ServiceHandler<{C}> and {C} directly — use one pattern, not both` |
| Handler + extra `@ServiceContract`(s) | ERROR | `{FQN} implements ServiceHandler<{C}> but also implements additional @ServiceContract interface(s): {names}` |
| No contract method matching impl method name | ERROR | `@CronJob method {FQN}.{method}() has no matching method named '{method}' on @ServiceContract {contract}` |
| Contract method missing `@ServiceOperation` | ERROR | `@CronJob on {FQN}.{method}() requires the corresponding service contract method to have @ServiceOperation — cron jobs must have a stable service target` |
| `@ServiceOperation("")` blank value | ERROR | `Method '{method}' on {Contract} has @ServiceOperation with a blank value — the operation id must be non-blank` |
| `id` blank | ERROR | `@CronJob.id must not be blank` |
| `maxAttempts < 1` | ERROR | `@CronJob.maxAttempts must be >= 1, got {value}` |
| `overlapPolicy=QUEUE_ONE` + `mode=SINGLE_INSTANCE` | ERROR | `@CronJob.overlapPolicy=QUEUE_ONE is only valid with mode=EVERY_INSTANCE; SINGLE_INSTANCE requires SKIP` |
| Unresolvable `timezone` string | WARNING | `@CronJob.timezone '{tz}' did not resolve as a Java ZoneId; will be validated at runtime (config override may apply)` |
| Duplicate `id` within same class | ERROR | `@CronJob.id '{id}' is duplicated; a previous declaration on {FQN}.{method}() already uses this id` |

---

## Pitfalls

### `@CronJob` must go on the concrete implementation method

The runtime `CronJobRegistrar` calls `implClass.getDeclaredMethods()` — non-recursive. Two incorrect placements are rejected at compile time:

- On the `@ServiceContract` interface method — the registrar skips interface methods entirely.
- On an abstract superclass method — `getDeclaredMethods()` does not walk up the hierarchy; the annotation is silently invisible to the registrar at runtime.

Place `@CronJob` on the method in the concrete, non-abstract class that implements the contract.

### `@ServiceOperation` is required unconditionally

The runtime rejects every annotation-discovered cron job whose contract method lacks `@ServiceOperation`, regardless of `mode` or `tracked`. The processor enforces this unconditionally. A blank `@ServiceOperation("")` value is also rejected (the runtime would throw `OperationIdResolver` failure at startup).

### Timezone warning is not suppressable

The processor always emits `WARNING` for unresolvable timezone strings. There is no `@SuppressWarnings` suppression mechanism. This is deferred behaviour — if the timezone is always supplied via external config, the annotation value is effectively a placeholder and the warning is expected.

### Contract method matching is name-only

`ServiceCouplingValidator` matches the `@CronJob`-annotated impl method to the contract method by simple name only, mirroring `CronJobRegistrar.findMetaForMethod`. If the contract has overloads with the same name, the first one found wins. A mismatch in parameter count does not produce a separate error at compile time.

---

## Enabling the Processor

Applications inheriting `vertique-app-parent` declare `vertique-job-cron` as a runtime dependency
and receive the complete processor facade automatically. Custom-parent applications import
`vertique-bom` and configure only the versionless Dagger and `vertique-codegen-all` processor
paths. The facade supplies `vertique-codegen-core` transitively. See `docs/packaging.md`.

The processor generates no classes, so no `@Component` changes are required.

---

## Module Dagger Bindings

None. `vertique-codegen-cron` is a compile-time annotation processor with no runtime Dagger module.

---

## Dependencies

| Artifact | Scope | Purpose |
|----------|-------|---------|
| `vertique-codegen-core` | compile | `CodegenContext`, `TypeResolver`, `AnnotationMirrors`, `Diagnostics` |
| `vertique-job-cron` | compile | `CronExpression` parser — invoked at processor-runtime via `new CronExpression(expr)` to reuse the runtime parser logic. The transitive Vert.x runtime classes pulled along are unused at processor time. |

Test-only dependencies: `vertique-codegen-test`, `vertique-services`.
