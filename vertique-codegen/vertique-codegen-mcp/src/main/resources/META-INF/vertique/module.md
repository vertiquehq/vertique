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

This processor is **not** part of the `vertique-codegen-all` facade, so neither
`vertique-app-parent` nor a `vertique-codegen-all` entry activates it. Every application that uses
`@McpTool` adds `vertique-codegen-mcp` explicitly to its `annotationProcessorPaths` alongside
Dagger (the version comes from `vertique-bom`); without that entry no MCP source is generated and
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
through `JsonProfileId`, and emits the normalized id as a nullable string literal on the invoker.
A blank annotation value is a compile error. Resolving the remaining tail of the precedence chain — the MCP boundary default, the
global default, then the reserved `vertx` profile — and rejecting an unknown id belongs to
composition in `vertique-mcp-server`.

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
component, the server composes with an empty tool set and no error is reported by the compiler.

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
