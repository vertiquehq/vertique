<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Workflow Schema Module

> **Status:** Beta
> **Package:** `dev.vertique.workflow.schema`
> **Artifact:** `vertique-workflow-schema`
> **Depends on:** workflow-core, json, json-schema, json-schema-validation, core

`vertique-workflow-schema` generates a JSON Schema for the payload types a workflow definition
declares for its decisions and start payload, and checks them when the definition is registered.

Beta means the registration behavior described here is implemented and tested, but the module is new
and its surface may still change in a minor release. Pin the version you test against.

---

## When To Use It

Install this module next to `vertique-workflow-core` when payload types should be checked as part of
definition registration rather than at the first request that uses them.

Good fits:

- A definition that names decision payload types by class name, such as a document-backed
  definition, where a typo or an unsuitable type would otherwise surface at the first decision.
- Applications that activate definitions at runtime and want a bad definition refused before it
  becomes resolvable.

Not in this module:

- **No persistence.** Nothing is stored; the cache lives in memory and is rebuilt after a restart.
- **No REST.** The module adds no endpoint and no HTTP surface.

---

## Wiring

The module is Dagger-only and is never discovered automatically. List it explicitly next to the
modules it builds on:

| Module | Required | Why |
|---|---|---|
| `WorkflowCoreModule` | yes | Owns the registry that collects definition validators |
| `WorkflowSchemaModule` | yes | Contributes the payload validator to the registry |
| `JsonRuntimeModule` | yes | Binds `JsonMapperProfileRegistry` and `JsonConfig`, which the validator injects |
| `ValidationModule` | optional | Binds a `jakarta.validation.Validator` so schemas reflect bean-validation constraints |

```java
@Component(modules = {
    WorkflowCoreModule.class,
    WorkflowSchemaModule.class,
    JsonRuntimeModule.class,
    ValidationModule.class
})
interface AppComponent { /* ... */ }
```

Without `WorkflowSchemaModule` the registry has no payload validator and registration performs no
payload check. Without the JSON runtime bindings the component does not compile, because the
validator cannot be constructed.

The constraint supplement is off unless a `jakarta.validation.Validator` is bound. Install
`ValidationModule` alongside so that the generated schema and the engine's bean-validation
enforcement agree; wiring only one of them makes the schema looser than the engine. The module logs
once whether the supplement is enabled.

---

## What Registration Checks

The validator is a `WorkflowDefinitionValidator` contributed to the workflow registry. It runs for
every candidate definition, whether registered at bootstrap or activated at runtime. It reads the
start payload type first, then the decision payload types of every human task in plan order,
including tasks inside fork branches. For each distinct type name it does this once:

1. **Skip `void`.** A `void` or `java.lang.Void` payload means no payload and needs no schema.
2. **Reuse the cache.** A type already resolved under the same profile is not generated again.
3. **Load the class.** The payload type name is loaded without initializing the class.
4. **Generate the schema.** The canonical Draft 2020-12 document is produced with the input-profile
   schema generator under the process system profile (`JsonConfig.effectiveSystemProfile`).
5. **Require an object.** The document must be object-shaped (see below).
6. **Precompile the patterns.** Every `pattern` in the document must compile.
7. **Compile the validator.** The schema is compiled under the bounded pattern guard and cached.

The first failure ends validation of that definition. A failure is never cached; types resolved
before it stay cached.

### The object-shape rule

A payload type must produce a schema whose root is an object, or be `void`. The root counts as an
object when the root, every `allOf` branch and the target of every local `$ref` (`#` or `#/...`,
followed through at most 16 references) declare `type` as exactly `object`, at least once and with
no other `type`. `anyOf` and `oneOf` are not entered, so a record with a required-one-of presence
rule that still states `type: object` passes.

What this means in practice:

- Records, plain beans and `Map<String, Object>` pass.
- `String`, numbers, booleans, `List<X>`, arrays and records annotated as a single scalar value
  fail.
- A type whose root has no `type` of its own, such as a polymorphic root with `@JsonTypeInfo`,
  fails.
- A nullable root (`type` of `object` and `null`) fails.

`io.vertx.core.json.JsonObject` and Jackson `ObjectNode` are objects by definition. They skip
generation and the shape rule and use a fixed `{"type":"object"}` document, which is still
compiled and cached. `Object` and array-typed roots are not exempt.

### Profile and cache

Schemas are generated under one profile, the process system profile. The profile id is fixed when
the validator is created; the profile itself is resolved at the first generation, so an unknown
profile name fails there as a configuration error and is not wrapped.

The cache is a process-wide singleton keyed by payload type name and profile id. A type is described
once per profile however many definitions or versions use it. The cache is populated even when a
later step rejects the definition; that is harmless because nothing reads a cached schema as proof
that a definition is registered.

### Pattern bounds

Patterns are compiled under a guard that limits each string checked by a pattern to 4,096
characters and the total pattern input of one validation window to 262,144 characters. These are
the shared defaults of `vertique-json-schema-validation`; this module has no configuration key for
them. If you tuned `jaxrs.validationPatternMaxChars` for REST, that value does not apply here.

### Threading and class loading

- Validators run synchronously on the registering thread. Call `activate` off the event loop.
- Payload classes are loaded with this module's own class loader. Other workflow modules resolve
  classes differently, so in an application with split class loaders the payload classes must be
  visible from the loader that loads `vertique-workflow-schema`.

---

## Failures

A rejected definition throws `WorkflowDefinitionException` and is left unresolvable, at bootstrap and
on activation alike. Every message starts with the definition, the declaration site and the payload
type; no payload value is ever included.

| Failure | Example message |
|---|---|
| Cannot be loaded | `definition 'order-review' v2 step 'review' decision 'approve' payload type 'com.acme.ApprovePaylod' cannot be loaded` |
| Not object-shaped | `definition 'order-review' v2 step 'review' decision 'approve' payload type 'java.lang.String' is not object-shaped (root type string); wrap the value in a record` |
| Uncompilable pattern | `definition 'order-review' v2 start payload payload type 'com.acme.Start' has a pattern at /properties/code/pattern that does not compile: Unclosed group near index 3` |
| Cannot be described | `definition 'order-review' v2 step 'review' decision 'approve' payload type 'com.acme.ApprovePayload' cannot be described: <reason>` |

The pattern message names the first failing pattern in document order. "Cannot be described" also
covers a schema that fails to compile and a generator that cannot be built, such as an invalid
profile override; the original exception is chained as the cause. The start payload site reads
`start payload`.

---

## Dependencies

- `vertique-workflow-core` for the definition validator contract and the definition exception.
- `vertique-json` and `vertique-json-schema` for the JSON profile registry and schema generation.
- `vertique-json-schema-validation` for the bounded pattern input and the pattern walk.
- `vertique-core` for the shared JSON and validation types.

---

## Common Mistakes

- **Not installing `WorkflowSchemaModule`.** Registration then succeeds without any payload check,
  and an unsuitable type surfaces only when a decision is submitted.
- **Installing it without the JSON runtime module.** The validator needs `JsonMapperProfileRegistry`
  and `JsonConfig`; the component fails to build without them.
- **Leaving out `ValidationModule`.** The module works without it, but the schema then ignores
  bean-validation constraints that the engine still enforces.
- **Declaring a scalar or array payload type.** `String`, `int`, `List<X>` and single-value records
  are rejected at registration. Wrap the value in a record.
- **Calling `activate` on the event loop.** Schema generation and pattern compilation block the
  calling thread.
- **Expecting a pattern limit on the engine path.** A `@Pattern` constraint checked by the engine's
  bean-validation path runs on strings of any length; only the schema path is bounded. Do not rely
  on this module to cap pattern input that the engine evaluates.
