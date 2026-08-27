<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Codegen MCP Tool Processor

> **Status:** Alpha
> **Package:** `dev.vertique.codegen.mcp`
> **Artifact:** `vertique-codegen-mcp`
> **Depends on:** `vertique-codegen-core`, `vertique-mcp-core`, `vertique-core`, `vertique-security-core`, `com.palantir.javapoet:javapoet`

`vertique-codegen-mcp` is the annotation processor that turns every `@McpTool`-annotated method into
generated Model Context Protocol dispatch source: one package-private invoker per tool and one
explicit `GeneratedMcpToolsModule` Dagger module that multibinds them. Tool dispatch therefore needs
no classpath scanning and no reflective invocation — the generated invoker calls the application
method directly.

The processor is also the module's validation gate. A tool declaration the generated code could not
call directly, name unambiguously, or represent honestly in JSON is a **compile error**, not a
degraded runtime binding. There is no reflective fallback to fall back to.

---

## When To Use It

Add this processor whenever an application publishes MCP tools with `@McpTool`. It pairs with
`vertique-mcp-core` (the annotations and descriptor/invoker contracts it reads and the generated
source implements) and `vertique-mcp-server` (which injects the generated multibindings and composes
the runtime).

This processor is part of the `vertique-codegen-all` facade: it is activated automatically by a
`vertique-codegen-all` dependency and, transitively, by `vertique-app-parent`. An explicit
`vertique-codegen-mcp` entry in `annotationProcessorPaths` is only needed in an off-parent setup
that does not depend on `vertique-codegen-all` — for example a module that assembles its
annotation-processor path by hand (the version still comes from `vertique-bom`). Without the
processor on the path, whether via the facade or an explicit entry, no MCP source is generated and
the application composes with an empty tool set.

The processor emits no runtime Dagger module of its own and contributes nothing to the application
classpath beyond its generated source.

---

## Core Concepts

### One invoker per tool, one module for the registry

For every accepted tool the processor writes a package-private
`<DeclaringType>_<method>_McpToolInvoker`. The invoker is Dagger-constructed with the application's
tool bean, builds its immutable `McpToolDescriptor` once during composition, and calls the tool
method directly.

All invokers are then bound by one generated module:

```java
@Generated("dev.vertique.codegen.mcp.McpToolProcessor")
@Module
public abstract class GeneratedMcpToolsModule {

    @Provides
    @IntoSet
    static McpToolInvoker weatherTools_lookup_invoker(WeatherTools_lookup_McpToolInvoker invoker) {
        return invoker;
    }

    @Provides
    @IntoSet
    static McpToolDescriptor weatherTools_lookup_descriptor(WeatherTools_lookup_McpToolInvoker invoker) {
        return invoker.descriptor();
    }
}
```

Install `GeneratedMcpToolsModule` in the application's Dagger component. **This module is the whole
tool registry** — a tool that is not bound here does not exist at runtime. The descriptor binding is
derived from the invoker instance rather than rebuilt, so the published descriptor set and the
dispatchable invoker set cannot drift apart.

### Generated invoker constructor threads an optional Bean Validation `Validator` (R38/W7)

Every generated invoker's `@Inject` constructor additionally accepts
`java.util.Optional<jakarta.validation.Validator>`, resolved from the application's Dagger graph
through `vertique-mcp-server`'s `McpServerModule.@BindsOptionalOf Validator` — no source change is
required in `GeneratedMcpToolsModule` itself, since Dagger resolves the parameter directly when
constructing the invoker, the same way it already resolves `InputObjectProcessor`. `prepare()`'s
stage 4 Bean Validation call routes through it: `McpBeanValidation.validate(input, validator)`, which
falls back to `vertique-mcp-core`'s zero-config static default when the optional binding is absent —
so a composition that binds no `Validator` at all sees byte-for-byte unchanged behavior.

### Where the generated module lands

The module is emitted into the longest common package prefix of every tool's declaring type.
Because the generated invokers are package-private and always land in their declaring type's own
package, **every tool's declaring type must live in one single package** — the module's package.
Tools spread across sibling packages cannot compile, and no output-package override changes that:

```xml
<compilerArgs>
    <arg>-Avertique.codegen.package=com.example.app.mcp</arg>
</compilerArgs>
```

The override only pins which single package the module (and therefore every tool) must use. A tool
outside the resolved package is a compile error that names this option, rather than a generated
module that does not compile.

### Deterministic, all-or-nothing emission

Tools are grouped by declaring type and ordered by declaring type and method name, so repeated builds
of the same inputs produce byte-identical source. When any validation diagnostic is reported the
processor emits nothing; a package-resolution failure discovered during module emission can leave
already-written invoker sources behind, but the compilation still fails, so a partial registry can
never bind.

### Effective JSON profile is resolved at compile time

The processor resolves the tool's `@JsonProfile` method-over-type, validates and normalizes the id
through `JsonProfileId`, and emits the normalized id as a nullable **typed `JsonProfileId` literal**
(`JsonProfileId.of("…")`) on the invoker — not a raw string. A blank annotation value is a compile
error. Resolving the remaining tail of the precedence chain — the MCP boundary default, the global
default, then the reserved `vertx` profile — and rejecting an unknown id belongs to composition in
`vertique-mcp-server`.

### Generated parameter carriers and metadata

For every parameterized tool the processor emits:

- a private nested `Input` record whose components are named **positionally** —
  `argument0`, `argument1`, ... — so a carrier component can never collide with another regardless of
  the declared protocol names;
- `@JsonProperty(protocolName)` on each component, so the wire's declared argument name (which may be
  a Java keyword, contain hyphens, or otherwise not be a legal identifier) is preserved exactly
  without deriving a Java identifier from it;
- each parameter's resolved final REST-effective input-policy chain as normalized base
  `@Canonicalize`/`@Sanitize` annotations on the component (never `@Skip*`), resolved at compile time
  by the package-private `McpInputPolicyResolver` — method over declaring type, then the parameter's
  own override, mirroring `ParameterExtractor.resolveParamPolicies`'s precedence;
- a position-stable `List<McpToolParameterMetadata>` pairing each component name with its external
  protocol name and description.

`McpInputPolicyResolver` is MCP's transport-specific derivation: `vertique-input-processing` publishes
`EffectiveInputPolicies` but no annotation→policy resolver, so each transport derives its own.

### A handler's return type is adapted onto `McpToolResult`, never coerced to a partial shape

Exactly four handler return shapes are accepted: a plain `T`, a `Future<T>`, a handler-authored
`McpToolResult<T>`, and a `Future<McpToolResult<T>>`. Every other declared return type — `void`,
`Future<Void>`, a raw or wildcard result, an SDK or Reactor type, `RoutingContext` — is a compile
error, so the generated invoker always has an honest adaptation to emit.

The generated `invoke()` always returns `Future<McpToolResult<?>>`:

| Declared shape | Generated adaptation |
|---|---|
| `T` | `Future.succeededFuture(McpToolResult.text(value))` for a `String`, or `McpToolResult.structured(value)` for any other type |
| `Future<T>` | The same `text`/`structured` choice, applied via `.map(...)` on the resolved value; a failed `Future<T>` propagates its failure unchanged — no `McpToolResult` is ever built |
| `McpToolResult<T>` | Wrapped in an already-succeeded `Future`, passed through exactly as the handler built it, including `isError=true` |
| `Future<McpToolResult<T>>` | Passed through exactly as returned; a failed future again propagates its failure unchanged |

`McpToolResult` carries no partial or intermediate result state, and the emitter never fabricates
one: a settled invocation produces a complete `McpToolResult` or the future fails — there is no
third outcome for the caller to adapt.

### Access mode is derived from the annotations REST already uses

`@PermitAll`, `@DenyAll`, `@RolesAllowed`, and `@RequiresAction` resolve over the same ordered source
tiers REST uses: the concrete method, overridden superclass methods nearest-first, the unique
interface methods it overrides, the concrete type, superclass types nearest-first, then the type's
interfaces. The first tier that declares a policy wins, so a method-level source always overrides a
type-level one, and several interface candidates in one tier must agree.

| Resolved annotations | Published `McpAccessMode` |
|---|---|
| none | `PERMIT_ALL` (no roles, no action) |
| `@PermitAll` | `PERMIT_ALL` |
| `@DenyAll` | `DENY_ALL` |
| `@RolesAllowed` and/or `@RequiresAction` | `RESTRICTED`, composed with AND when both are present |

`@RequiresAction` conflicts with `@PermitAll` and `@DenyAll`, exactly as in REST. `@Authorized` is
REST-specific and is rejected on a tool.

---

## Validation Boundaries

Validation is fail-fast per declaration: the first boundary a tool violates produces exactly one
targeted diagnostic and no model, so a rejected tool never produces a second, derived complaint.

| Boundary | What is required |
|---|---|
| Direct invocability | The tool method is `public`, concrete, and an instance method on a Dagger-managed type with exactly one `@Inject` constructor |
| Protocol metadata | Tool names are unique across the compilation and match `[A-Za-z0-9_.-]{1,128}`; the description is non-blank and bounded; a blank title is omitted |
| Honest type contracts | No raw, wildcard, type-variable, or unresolved type in a parameter or result; no `void` result; no input member without a JSON schema representation |
| Effective JSON profile | Resolved method-over-type; a blank id is rejected at compile time rather than surfacing as an unresolvable mapper during composition |
| Input-policy conflicts | A `@Canonicalize`/`@Sanitize` declaration and its `@Skip*` counterpart on the same element (method, declaring type, or parameter) is a compile error |

Output-schema synthesis stays a composition-time concern: a result type that is structurally valid
here but that the shared runtime schema generator cannot synthesize fails startup, before the router
is mounted.

---

## Common Mistakes

### Expecting a reflective fallback

There is none. If the generated invoker cannot call the method directly — a non-public method, an
abstract or static method, a declaring type Dagger cannot construct — the build fails. That is the
design: an MCP tool either dispatches directly or does not ship.

### Tools spread across unrelated packages

Generated invokers are package-private and always land in their declaring type's package, so every
tool's declaring type must live in the one generated-module package. Keep tool types in a single
package; `-Avertique.codegen.package` only pins which package that is.

### Forgetting to install `GeneratedMcpToolsModule`

The processor writes the module but cannot install it. Without the module in the application's Dagger
component, the server composes with an empty tool set and no error is reported by the compiler —
`vertique-mcp-server` logs one unconditional WARN naming both this cause and the alternative
(`vertique-codegen-mcp` absent from the processor path in a pre-facade, off-parent setup).

### Assuming the carrier component name tells you the protocol name

It does not, by design. `argument0`, `argument1`, ... is a positional Java identifier with no
relationship to the declared `@McpToolParam` name; the protocol name lives only in
`@JsonProperty(...)` and in the emitted `McpToolParameterMetadata` list. Do not pattern-match on
component names in generated-source tooling.

---

## Module Dagger Bindings

None at processor scope. The processor's *output* — `GeneratedMcpToolsModule` — is the module the
application installs.

---

## Dependencies

| Artifact | Scope | Purpose |
|---|---|---|
| `vertique-codegen-core` | compile | `CodegenContext`, `TypeResolver`, `AnnotationMirrors`, `Diagnostics`, and the generated-name helpers |
| `vertique-mcp-core` | compile | `@McpTool` / `@McpToolParam`, and the `McpToolDescriptor`, `McpToolAccess`, `McpToolInvoker`, `McpPreparedToolCall` contracts the generated source implements |
| `vertique-core` | compile | `@JsonProfile` and `JsonProfileId` for effective-profile resolution and normalization |
| `vertique-security-core` | compile | `@RequiresAction` / `ActionRef`, from which the tool's access mode is derived |
| `com.palantir.javapoet:javapoet` | compile | Source emission |

Test-only dependencies: `vertique-codegen-test`, `jakarta.inject-api`, `jakarta.annotation-api`.
