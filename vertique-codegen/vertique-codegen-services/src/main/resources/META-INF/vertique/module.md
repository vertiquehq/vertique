<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Codegen Service Contract Module

> **Status:** Beta
> **Package:** `dev.vertique.codegen.services.processor`
> **Artifact:** `vertique-codegen-services`
> **Depends on:** `vertique-codegen-core` (compile), `vertique-security-core` (compile)

`vertique-codegen-services` is an annotation processor that generates `ServiceContractContributor`
implementations at compile time, turning structural service-contract mistakes — wrong return types,
blank `@ServiceOperation` values, payload overloads, handler-pattern mismatches, multi-implementation
layout violations — into build errors instead of startup failures.

It is the generated service-registration path. Its output replaces the reflective contract-discovery
and validation pass that `dev.vertique:vertique-services` would otherwise run at boot; hand-written
contributors and `@Services` objects remain supported alongside it.

---

## When To Use It

Add this processor to any application module that declares `@ServiceContract` implementations.
Applications inheriting `vertique-app-parent` receive the complete processor facade automatically by
declaring `vertique-services` as a runtime dependency. Custom-parent applications import
`vertique-bom` and configure the versionless Dagger and `vertique-codegen-all` processor paths — see
`docs/packaging.md`.

The processor has no opt-in flag: its presence on `<annotationProcessorPaths>` is the opt-in. Per-type
opt-out is `@NoAutoWire`.

---

## Core Concepts

### Two implementation patterns

The processor scans **concrete implementation types** in the compilation round, not annotated
interfaces, so a contract interface compiled in another module is handled correctly.

| Shape | Pattern | Result |
|---|---|---|
| Any supertype carries `@ServiceContract` | DIRECT | Contract method and invoked method are the same |
| Implements `ServiceHandler<C>` where `C` carries `@ServiceContract` | HANDLER | Contract method and handler method are separate; the handler may add injectable parameters |
| Both of the above | — | Compile error |
| Neither | — | Skipped silently |
| Carries `@NoAutoWire` | — | Skipped silently |

Interfaces and abstract classes are never candidates.

### Contract groups and implementation selection

All candidates for the same contract interface form one **contract group**, and a group emits exactly
one contributor. Each candidate is injected as a `Provider`, so an implementation that is not selected
is never instantiated.

Selection happens at startup, against the application config, with these rules:

| Group shape | Behavior at startup |
|---|---|
| One implementation, unconditional | Always registers. |
| One implementation, conditional | Registers when its conditions match; otherwise contributes **nothing** — it does not fail. |
| Several implementations | Every conditional candidate is evaluated. Exactly one match registers. No match falls back to the unconditional default when the group has one. No match and no default, or more than one match, throws `ServiceRegistrationException`. |

Conditions come from `dev.vertique.codegen.ConditionalOnProperty` (repeatable via
`ConditionalOnProperties`) and are evaluated by
`dev.vertique.core.config.PropertyCondition.matchesAll(config, conditions)`.

```java
package com.example.app;

import dev.vertique.codegen.ConditionalOnProperty;
import jakarta.inject.Inject;

@ConditionalOnProperty(name = "sandboxEnabled")
public class UserServiceSandbox implements UserService {

    @Inject
    public UserServiceSandbox() {}

    // ...
}
```

`@ConditionalOnProperty` has `name()` (required), `havingValue()` (default `"true"`), and
`matchIfMissing()` (default `false`).

---

## Adoption Recipe

Two steps. Leaving either one out produces a detectable failure.

### Step 1 — put the processor on the annotation-processor path

See **When To Use It** above.

### Step 2 — add `GeneratedServicesModule` to the Dagger component

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    ConfigModule.class,
    RestModule.class,
    DispatchModule.class,
    GeneratedJaxRsResourcesModule.class,
    GeneratedServicesModule.class,
    AppModule.class,
})
interface AppComponent { ... }
```

Naming the generated class in the component is a compile-time guard: when the processor is
misconfigured the class does not exist and the build fails at the component with an unknown-symbol
error, instead of deploying with an empty service registry.

---

## Generated Artifacts

| Generated type | Package | Cardinality |
|---|---|---|
| `{Contract}_ContractContributor` | The **contract interface's** package | One per contract group |
| `{Contract}_ServiceClientProxy` | The **contract interface's** package | One per source-root `@ServiceContract` interface |
| `GeneratedServicesModule` | Longest common prefix of the emitted contract packages, or the `vertique.codegen.package` option when set | One per compilation unit that has a contributor or eligible client |

Each contributor is `public final`, annotated `@Generated` and `@Singleton`, implements
`dev.vertique.services.ServiceContractContributor`, and takes one `jakarta.inject.Provider<Impl>` per
candidate through an `@Inject` constructor. Its `contribute(JsonObject config)` builds entries through
the `ServiceContractEntries.deployable()` builder, resolving every `java.lang.reflect.Method`,
resilience annotation list, and annotation list once into `private static final` fields rather than
per call. Deployment options are read from `services.contracts.{namespace}.{name}`.

`GeneratedServicesModule` is an abstract `@Module` with two generated binding families. The
server-side family has one `@Provides @IntoSet ServiceContractContributor` method per emitted
contributor. The client-side family has one `@Provides @Singleton` method per eligible source-root
contract:

```java
@Generated("dev.vertique.codegen.services.processor.ServiceContractProcessor")
@Module
public abstract class GeneratedServicesModule {

    @Provides
    @IntoSet
    static ServiceContractContributor userService(UserService_ContractContributor c) {
        return c;
    }
}
```

```java
@Provides
@Singleton
static UserService provideUserServiceClient(ServiceClientFactory serviceClientFactory) {
    return serviceClientFactory.create(UserService.class);
}
```

The client binding is a typed Dagger binding, so application code can inject `UserService` directly.
It delegates construction to `ServiceClientFactory`; it never instantiates
`{Contract}_ServiceClientProxy` itself. This preserves generated-proxy selection, reflective
fallback, registry/metadata validation, context propagation, and transport ownership.

**A contract the module's package cannot name gets no client binding.** A contract that is not
`public` (or is nested in a non-public type) and lives outside the module's resolved package — or
one in the unnamed package — is skipped with a compiler *warning* naming the reason, because
emitting the binding would produce a module that does not compile. Since javac compiles generated
sources in the same task, that would break the application's build merely by putting the processor
on `annotationProcessorPaths`, whether or not the module is installed in a `@Component`. Make the
contract and its enclosing types public, point `-Avertique.codegen.package` at a package it is
visible from, or bind it with a hand-written `@Provides`.

Contributor bindings are unaffected by contract *visibility*: they reference the generated
`public {Contract}_ContractContributor`, which always sits in the contract's own package. The one
exception is a contract in the **unnamed package** — its contributor is generated there too, and a
named package can never reference it. That is a compile **error**, not a skip: dropping a
contributor unregisters the service, which would fail at runtime rather than at Dagger's
compile-time graph validation. Move such a contract into a named package.

Client-only compilation units still receive the unified `GeneratedServicesModule`, even when the
implementation is compiled in another application module. Provider names are deterministic and
collision-safe across contracts and across both binding families.

Applications never edit or subclass these types. They are referenced in exactly one place — the
`@Component` modules list — and are otherwise consumed by `DispatchModule`'s
`Set<ServiceContractContributor>` multibinding.

### Client Contract Discovery

`ServiceContractProcessor` runs a second, independent scan in the same round to emit client proxies:
an annotation-rooted scan over every `@ServiceContract`-annotated interface compiled in the round —
distinct from the impl-rooted scan in [Two implementation patterns](#two-implementation-patterns) —
processed in fully-qualified-name order for deterministic output.

This scan is independent of implementation presence: every source-root `@ServiceContract` interface
is a candidate, whether or not a concrete implementation is compiled in the same unit. A contract
with a compiled impl gets both a `{Contract}_ContractContributor` (from the impl-rooted scan above)
and a `{Contract}_ServiceClientProxy`; a contract with no impl in this compilation unit still gets the
client proxy.

It is equally independent of implementation validity. Only a failure rooted in the contract's own
shape — the same five contract-shape checks marked "Yes" in [Validation Failures](#validation-failures)
below — suppresses client-proxy emission. An impl-side rejection (double-pattern, a
missing/overloaded/mis-parameterised handler method, a missing `@Inject` constructor, or a
contract-group conflict) leaves the client proxy emitted, because the proxy is a function of the
contract alone. The compilation still fails on the impl-side error; the proxy is simply there once
that error is fixed.

### Service client proxy

For `com.example.DemoContract` (a source-root `@ServiceContract` interface) declaring
`Future<String> greet(SecurityContext sc, String name)`:

```java
@Generated("dev.vertique.codegen.services.processor.ServiceContractProcessor")
public final class DemoContract_ServiceClientProxy implements DemoContract {

    private final ServiceRequestSender _sender_;
    private final DispatchEnvelopeBuilder _envelopeBuilder_;
    private final ResolvedServiceTarget greetTarget;
    private final int greetPayloadIndex;
    private final int greetSecurityContextIndex;
    private final boolean greetOneWay;

    public DemoContract_ServiceClientProxy(ServiceRequestSender _sender_,
            DispatchEnvelopeBuilder _envelopeBuilder_, ServiceContractRegistry.ContractEntry<?> _entry_) {
        this._sender_ = _sender_;
        this._envelopeBuilder_ = _envelopeBuilder_;
        ServiceMethodMeta _greetMeta_ = _entry_.operations().get("greet");
        if (_greetMeta_ == null) {
            throw new IllegalStateException("Service client contract mismatch: com.example.DemoContract"
                + " has no registered operation 'greet' for method greet");
        }
        this.greetTarget = ResolvedServiceTarget.of(DemoContract.class, _greetMeta_);
        this.greetPayloadIndex = _payloadIndex_(_greetMeta_);
        this.greetSecurityContextIndex = _securityContextIndex_(_greetMeta_);
        this.greetOneWay = _greetMeta_.oneWay();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Future<String> greet(SecurityContext sc, String name) {
        Object[] _args_ = new Object[] {sc, name};
        Object _payload_ = this.greetPayloadIndex >= 0 && this.greetPayloadIndex < _args_.length
            ? _args_[this.greetPayloadIndex] : null;
        Map<String, Object> _overrides_ = Map.of();
        if (this.greetSecurityContextIndex >= 0 && this.greetSecurityContextIndex < _args_.length
                && _args_[this.greetSecurityContextIndex] != null
                && ContextValues.current(SecurityContext.class).isEmpty()) {
            _overrides_ = Map.of(SecurityContext.class.getName(), _args_[this.greetSecurityContextIndex]);
        }
        DispatchEnvelope<?> _envelope_ =
            this._envelopeBuilder_.build(_payload_, _overrides_, DispatchBoundary.SERVICE_DISPATCH);
        Future<?> _dispatch_ = this.greetOneWay
            ? this._sender_.sendOneWay(this.greetTarget, _envelope_)
            : this._sender_.send(this.greetTarget, _envelope_).compose(Futures::toFuture);
        return (Future<String>) _dispatch_;
    }

    @Override
    public String toString() {
        return "ServiceProxy[DemoContract]";
    }

    // _payloadIndex_(ServiceMethodMeta) / _securityContextIndex_(ServiceMethodMeta) helpers omitted
}
```

The constructor is the one place the generated proxy can throw before any dispatch happens: every
baked operation id (`"greet"` here) is looked up in `_entry_.operations()`, and a missing entry throws
`IllegalStateException` whose message always starts with the literal
`Service client contract mismatch: `. Every other per-operation field — `greetPayloadIndex`,
`greetSecurityContextIndex`, `greetOneWay` — is derived from the resolved `ServiceMethodMeta`, not
from the source method signature, so the proxy always tracks whatever registered the contract, even
if that registration used a different mechanism than this emitter.

---

## Recognized Annotations and Types

| Symbol | Owner | Role |
|---|---|---|
| `dev.vertique.services.ServiceContract` | `vertique-services` | Marks the contract interface; supplies `namespace()` and `value()` |
| `dev.vertique.services.ServiceOperation` | `vertique-services` | Names an operation and creates its stable id; the value must not be blank |
| `dev.vertique.services.OneWay` | `vertique-services` | Marks a fire-and-forget operation |
| `dev.vertique.services.ServiceHandler` | `vertique-services` | The handler-pattern supertype |
| `dev.vertique.security.SecurityContext` | `vertique-security-core` | Recognized as a dispatch-context parameter on handler methods |
| `dev.vertique.core.eventbus.DispatchContextValue` | `vertique-core` | Marks a **handler**-method parameter as a dispatch-context value. On a contract-interface method it has no effect — the parameter is classified as an ordinary payload parameter like any other |
| `dev.vertique.core.eventbus.DispatchEnvelope` | `vertique-core` | Rejected as a contract parameter |
| `dev.vertique.codegen.NoAutoWire` | `vertique-codegen-core` | Excludes an implementation from server registration, or a contract from its generated typed-client binding |
| `dev.vertique.codegen.ConditionalOnProperty` | `vertique-codegen-core` | Config-driven implementation selection |

To place `@NoAutoWire` or `@ConditionalOnProperty` on a class, the owning module needs
`vertique-codegen-core` on its compile path (`provided` scope is enough — both annotations have
`SOURCE` retention).

---

## Processor Options

| Option | Effect |
|---|---|
| `-Avertique.codegen.package=<pkg>` | Overrides the output package of `GeneratedServicesModule`. Individual contributors always land in their contract's package and are unaffected. |

The processor is registered in `META-INF/services/javax.annotation.processing.Processor`, declares
`@SupportedAnnotationTypes("*")` and `@SupportedSourceVersion(RELEASE_21)`, and always returns `false`
from `process` so Dagger, Lombok, and other processors see unmodified elements.

---

## Validation Failures

Every rule below is a compile-time `ERROR` unless stated otherwise. All applicable validators run
before the processor gives up on a candidate, so one build surfaces the complete list rather than one
error per cycle.

The **client proxy for that contract?** column states whether a *different, otherwise-valid* contract
in the same compilation unit still gets its `{Contract}_ServiceClientProxy` (see
[Client Contract Discovery](#client-contract-discovery) above) — the client proxy is a function of the
contract's shape alone, so an impl-only defect never withholds it, while a contract-shape defect does
(both scans would otherwise report the same error twice).

**Per implementation:**

| Rule | Rejected because | Client proxy for that contract? |
|---|---|---|
| A contract method must return a parameterized `Future<T>` | A raw `Future`, `void`, or any other type has no dispatchable result shape | Yes |
| At most one payload parameter per contract method | The dispatch envelope carries a single payload | Yes |
| `DispatchEnvelope<?>` may not appear as a contract parameter | It is the transport wrapper, not application data | Yes |
| No overloaded method names on the contract interface | Operation names derive from method names, so overloads collide on one event-bus address | Yes |
| No overloaded **public** method names on a handler class | The runtime resolves handler methods by name only; private and package-private helpers sharing a name are ignored and do not trigger this | No — impl-only |
| `@ServiceOperation` value must not be blank | A blank value produces an empty operation id and malformed addresses | Yes |
| No two operations in a contract may resolve to the same operation name | One would silently shadow the other on the event bus | Yes |
| Exactly one `@Inject` constructor on the implementation | Zero means Dagger cannot construct it; more than one is ambiguous | No — impl-only |
| `ServiceHandler<C>`: `C` must be a `@ServiceContract` interface, and the handler must not implement `C` directly or any other `@ServiceContract` interface | Ambiguous registration shape | No — impl-only |
| Each contract method needs exactly one matching handler method: same name, identical payload parameters in order and type, identical `Future<T>` return type | Name-based matching cannot disambiguate anything looser | No — impl-only |
| Extra handler parameters must be `SecurityContext` subtypes or `@DispatchContextValue`-annotated types | Anything else cannot be supplied at dispatch time; the diagnostic names the offending parameter | No — impl-only |

**Per contract group:**

| Rule | Rejected because |
|---|---|
| At most one unconditional implementation per group | Two defaults make selection ambiguous. The error is emitted on every unconditional implementation and names all the others, so one pass shows the whole conflict. |
| When a group has an unconditional default, every other implementation must carry at least one `@ConditionalOnProperty` | An ungated non-default is indistinguishable from a second default |

A group that fails either group rule is excluded from emission. Client-proxy emission is unaffected
by group-level validation — it has no meaning without a compiled implementation.

**Warning (not an error):** a type carrying both `@NoAutoWire` and `@ConditionalOnProperty` compiles
with a warning — the condition has no effect, because manual wiring owns selection for opted-out
types.

### Generic contracts skip client-proxy emission

A `@ServiceContract` interface that declares its own type parameters, or whose method remains generic after resolving against the contract, produces no `{Contract}_ServiceClientProxy`. `ClientContractExtractor` detects the unresolved type variable and emits an informational `NOTE` rather than a compiler error — the compilation still succeeds. Callers of such a contract fall back to the reflective client proxy at runtime instead of the generated static one.

### Reserved-identifier collisions skip client-proxy emission

A contract that uses one of the identifiers the generated companion reserves for itself also produces no `{Contract}_ServiceClientProxy`. The same informational-`NOTE`-not-error treatment applies as for generic contracts above: the compilation still succeeds, and callers of such a contract fall back to the reflective client proxy at runtime instead of the generated static one. The `NOTE` names both the contract and the colliding identifier.

Two families are reserved, and only these two can collide:

| Where | Reserved names | Why |
|-------|----------------|-----|
| Contract **parameter** names | `_args_`, `_payload_`, `_overrides_`, `_envelope_`, `_dispatch_` | Declared as method-locals in every generated dispatch body; a same-named parameter would shadow one |
| Contract **method** names | `_payloadIndex_`, `_securityContextIndex_` | Names of the generated private static index helpers, each declared as `private static int helper(ServiceMethodMeta)` |

The method-name half checks the erasure, not just the name: a contract method only collides when its erased parameter list is exactly `(ServiceMethodMeta)` — the helper's own signature. Any other overload of the same name (different arity or parameter type, e.g. `_payloadIndex_(String)`) is a legal overload; it compiles fine alongside the generated helper and is emitted normally. Generated fields (`_sender_`, `_envelopeBuilder_`, and the per-operation `<method>Target` / `…PayloadIndex` / `…SecurityContextIndex` / `…OneWay` state) and constructor locals need no reservation: every field read in a dispatch body is `this.`-qualified, and the constructor sees no contract-declared identifier at all.

### Client-proxy emission is also one-shot per round

Client-proxy emission shares the same `emitted` guard as contributor emission: `ServiceContractProcessor.process()` runs both scans and emits both `{Contract}_ContractContributor` and `{Contract}_ServiceClientProxy` classes in the first non-`processingOver` round it sees, then sets `emitted = true`. A `@ServiceContract` interface that only becomes visible in a later round (for example, generated by another processor after this one has already run) does not get a client proxy in that compilation.

---

## Failures, Constraints, and Common Mistakes

- **Omitting `GeneratedServicesModule` from `@Component` fails silently.** The generated typed
  clients are not injectable and the `Set<ServiceContractContributor>` multibinding is empty by
  default, so no service registers. Step 2 of the adoption recipe exists to convert this into a
  compile error.
- **Listing `GeneratedServicesModule` with the processor missing fails loudly**, which is the intended
  direction: the generated class does not exist and `javac` reports an unknown type at the component.
- **Cross-module implementation collisions are not caught at compile time.** Group validation sees
  only the candidates in the current compilation unit. Two modules that each contribute an
  implementation of the same contract remain a runtime concern.
- **`@NoAutoWire` has separate contract and implementation meanings.** On a contract it suppresses
  only that contract's generated typed-client binding, allowing an application-owned client
  provider to replace it. On an implementation it retains the existing server-registration opt-out;
  it does not suppress a valid typed-client binding for the contract. `@NoAutoWire` plus
  `@ConditionalOnProperty` on an implementation still does nothing for generated server selection;
  choose manual wiring or conditional generation.
- **Conditions are evaluated against the resolved application config**, so a condition naming a key
  that no config source supplies matches only when `matchIfMissing = true`.
- **`@CronJob` targets are unaffected.** Cron targets are resolved by address at runtime, and both the
  generated and manual registration paths produce identical addresses.

---

## Dependencies

| Artifact | Scope | Purpose |
|---|---|---|
| `dev.vertique:vertique-codegen-core` | compile | `CodegenContext`, `TypeResolver`, `AnnotationMirrors`, `Diagnostics`, `PackageResolver`, `DaggerModuleWriter`, `Identifiers`, `Conditions`, `Constructors`, `InjectConstructorValidator`, and the `@NoAutoWire` / `@ConditionalOnProperty` annotations |
| `dev.vertique:vertique-security-core` | compile | `SecurityContext`, recognized as a dispatch-context parameter type |
| `com.palantir.javapoet:javapoet` | compile | Source generation; not on the application runtime classpath |

The processor itself contributes no Dagger bindings. It emits a `@Module` into the consuming
module's generated sources; that module is part of the application's graph, not this artifact's.

Test-only dependencies: `vertique-codegen-test`, `vertique-services`, `vertique-core`,
`vertique-config-core`, and Dagger.

---

## Examples

- `examples/vertique-example-services-codegen` — both patterns side by side: `BillingServiceImpl`
  (direct) and `ShippingServiceHandler implements ServiceHandler<ShippingService>` with
  `SecurityContext` injection.
- `examples/vertique-example-services` — conditional selection: `UserServiceSandbox` annotated
  `@ConditionalOnProperty(name = "sandboxEnabled")`, `UserServiceHandler` as the unconditional
  handler-pattern default, and `UserServiceImpl` annotated `@NoAutoWire` so it stays a manual
  reference rather than a candidate. `UserServiceIT` and `UserServiceSandboxIT` cover both selections.

Both examples list `GeneratedServicesModule` in their `AppComponent`.
