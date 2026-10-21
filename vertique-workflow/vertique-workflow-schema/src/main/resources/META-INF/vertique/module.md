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
declares for its decisions and start payload, checks them when the definition is registered, and
validates a submitted decision or start payload against the registered schema before the engine sees
it.

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
- Endpoints that want to reject a malformed decision or start payload with value-free, pointer-based
  violation details before submitting it to the engine.

Not in this module:

- **No persistence.** Nothing is stored; the cache lives in memory and is rebuilt after a restart.
- **No REST.** The module adds no endpoint and no HTTP surface; an application exposes the descriptors
  and maps validation results to responses itself.

---

## Wiring

The module is Dagger-only and is never discovered automatically. List it explicitly next to the
modules it builds on:

| Module | Required | Why |
|---|---|---|
| `WorkflowCoreModule` | yes | Owns the registry that collects definition validators |
| `WorkflowSchemaModule` | yes | Contributes the payload validator to the registry and binds `WorkflowPayloadSchemas` and `WorkflowStateCompatibility` |
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

## State Type Comparison

`WorkflowSchemaModule` also binds `WorkflowStateCompatibility`, which the workflow registry and the engine use to decide
whether one state class extends another. It compares the canonical JSON Schemas of the two classes under the process JSON
profile (the same generator as the payload check, without the bean-validation supplement): every property of the source
class must exist in the target class with the same type, and the target may add properties. Nested objects compare
property by property by the same rule, collection items and map values compare their item type, and an enum target must
contain every constant of the source. `firstIncompatibility(source, target)` answers the first incompatible schema path,
such as `amount`, `address.city` or `items[].sku`, or an empty `Optional`; a path never carries a value.

The registry applies it when a scope-owned definition and the default scope's definition of the same id and contract name
different state classes, and the engine applies it before a migration to a definition whose state class differs. Without
this module those cases are refused rather than accepted, so install it when a scope may define a state class that differs
from the default scope's. A state class evolves by adding properties only; the comparison does not check that a property
added to a class tolerates stored state JSON that lacks it, so give an added property a default (a nullable component or a
default value).

---

## What Registration Checks

The validator is a `WorkflowDefinitionValidator` contributed to the workflow registry. It runs for
every candidate definition, whether registered at bootstrap or activated at runtime. It reads the
start payload type first, then the decision payload types of every human task in plan order,
including tasks inside fork branches. For each distinct type name it does this once:

1. **Skip `void`.** A `void` or `java.lang.Void` payload means no payload and needs no schema.
   A primitive decision type other than `void`, such as `int`, is not skipped: it is rejected with
   the message "cannot be loaded", the same wording the engine's class lookup gives.
2. **Reuse the cache.** A type already resolved under the same profile is not generated again.
3. **Find the class.** The payload type name is looked up in the `WorkflowTypeNames` index, where a class
   named with `@WorkflowType` is found by its logical name. A name the index does not know is loaded as a
   binary class name without initializing the class.
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

The cache is a process-wide singleton keyed by the logical payload type name and profile id. The logical
name is the `@WorkflowType` value, or the binary class name for a class without the annotation, so a start
payload and a decision payload are both keyed the way the plan names them. `WorkflowPayloadSchemas` finds a
schema through the same logical name. A type is described
once per profile however many definitions or versions use it. The cache is populated even when a
later step rejects the definition; that is harmless because nothing reads a cached schema as proof
that a definition is registered.

### Pattern bounds

Patterns are compiled under a guard that limits each string checked by a pattern to 4,096 UTF-16 code
units and the total pattern input of one validation window to 262,144 UTF-16 code units. These are
the shared defaults of `vertique-json-schema-validation`; this module has no configuration key for
them. If you tuned `jaxrs.validationPatternMaxChars` for REST, that value does not apply here.

The bound applies to keyed validation (see Validating Payloads). The engine's own bean-validation
path is not bounded this way.

The bound caps input length only. It is not a defence against catastrophic backtracking: a pattern
that backtracks badly can still take long on an input within the limit. Write patterns that are
linear-safe, or review them before they enter a payload type.

### Threading and class loading

- Validators run synchronously on the registering thread. Call `activate` off the event loop.
- Payload classes the type-name index does not know are loaded with this module's own class loader. Other workflow modules resolve
  classes differently, so in an application with split class loaders the payload classes must be
  visible from the loader that loads `vertique-workflow-schema`.

---

## Reading Payload Schemas

`WorkflowPayloadSchemas` is bound by `WorkflowSchemaModule`. It returns the schemas generated at
registration as plain descriptors, so a client can render a form for a decision or a start payload
without knowing the Java types.

### Key Classes

| Class | Role |
|---|---|
| `WorkflowPayloadSchemas` | `describe(key)` for the whole definition; `decision(key, stepId, decisionName)` for one decision; `validateDecision` and `validateStart` to validate a submitted payload. The key is the `DefinitionKey` of the pin: scope, definition id and version |
| `WorkflowDefinitionDescriptor` | Definition id and version, the start payload schema (`null` for a `void` start) and the human task steps in plan order |
| `HumanTaskStepDescriptor` | A step id and its decisions in declaration order |
| `DecisionDescriptor` | Decision name, the step it leads to and the payload schema (`null` for a `void` decision) |
| `PayloadSchema` | The canonical JSON Schema document and the `propertyOrder` list |
| `PayloadValidationResult` | The outcome of a validation: `valid()` and the `violations()` list of `ViolationDetail` |

The records are immutable. `PayloadSchema.document()` returns a copy on every call, so a caller can
modify what it receives without affecting later reads. `PayloadValidationResult.violations()` is an
unmodifiable copy.

### Property order

The canonical document lists `properties` alphabetically, which is rarely the order a form should
show. `propertyOrder` gives the presentation order:

1. The properties in the order the profile's JSON mapper deserializes them, under the wire names
   the profile publishes. This honors `@JsonPropertyOrder` and creator order; a record's components
   come in declaration order.
2. Then every remaining key of the document's top-level `properties`, in the document's own
   order. Alias spellings, such as a `@JsonAlias` name, are keys of the document that the mapper
   does not list as properties, so they follow every primary name.

Each key appears exactly once. A name the mapper knows but the document does not publish is
dropped. The list is empty for `JsonObject` and `ObjectNode` payloads, which are objects by
definition. Only the top-level properties are ordered; nested objects keep the document's order.

An any-setter does not add an entry. Its storage is not published as a property key, so it is neither
in the document's `properties` nor in `propertyOrder`; extra keys are described through
`additionalProperties`, not listed.

### Behavior of reads

- **Unknown returns empty on `describe` and `decision`.** An unknown definition id, version, step or
  decision gives `Optional.empty()`. The validation methods answer the same situation with a
  violation instead (see Validating Payloads).
- **No class loading on read.** Descriptors are assembled from the registry's stored plan and the
  cache filled at registration. A read loads no class, generates nothing and touches no storage.
- **Non-blocking and event-loop safe.** `describe` and `decision` are plain in-memory reads and may
  run on the event loop.
- **A missing schema is a wiring fault.** If a definition is registered but one of its payload
  types has no cached schema, the registry was built without this module's validator. `describe`,
  `decision` and the validation methods then throw `IllegalStateException` naming the type and saying
  to install `WorkflowSchemaModule` in the component that provides `WorkflowRegistry`.
- **One profile per process.** Cache entries are keyed by type and profile, and a process has one
  system profile. If entries for the same type exist under more than one profile, a read or a
  validation fails with `IllegalStateException`.
- **No access control.** The service reports what a definition declares. Authorize access to the
  definition before returning a descriptor or a validation result to a client (see Security).

### Client path

A task record carries the workflow instance id and the step id but no definition id or version, so
a client needs two hops to reach the schema of a task's decisions:

1. Read the task record and take its workflow id and step id.
2. Query the instance through `WorkflowOperations.query`; the returned instance names the
   definition id and the pinned definition version.
3. Call `describe` (or `decision`) with that id and version, and pick the step.

---

## Validating Payloads

`WorkflowPayloadSchemas` validates a submitted payload against the schema registered for one pinned
definition version:

```java
PayloadValidationResult result =
    schemas.validateDecision(key, "review", "approve", payload);
if (!result.valid()) {
  List<ViolationDetail> violations = result.violations();
}
PayloadValidationResult start = schemas.validateStart(key, startPayload);
```

`validateDecision(key, stepId, decisionName, payload)` and
`validateStart(key, payload)` take the payload as a nullable `JsonObject`. The key names the scope, the definition id and
the version, for example `new DefinitionKey(WorkflowScopes.DEFAULT, "order-review", 2)`, so two scopes that hold one id
and version are described and validated by their own plans. A key that no registered definition holds describes as empty
and validates as one `definition` violation.
`PayloadValidationResult.valid()` is true when `violations()` is empty. A mistake by the caller, such
as an unknown id or a payload that does not fit, is answered with a violation and never an exception.

### Violation details

Each `ViolationDetail` has a `path`, a `message`, a `type` and `args`:

| Field | Content |
|---|---|
| `path` | An RFC 6901 JSON Pointer such as `/comment` or `/items/0/name`; the root is the empty string. A segment uses the payload's wire name; it is never a map key or other caller data. An omitted required member is named, as `/comment` or `/items/0/name`, when the schema confirms the name; where the schema offers alternatives or cannot confirm it, the path is its parent's |
| `type` | The failing schema keyword, or one of `definition`, `decision`, `payload`, `patternInputLength`, `patternInputTotalLength`, `truncated` |
| `message` | The canonical message for the violation |
| `args` | The keyword's constraint arguments, such as a limit |

**No submitted value is ever returned.** A detail never carries a submitted value, and never the name
or value of an unknown property; a location the schema does not declare is cut back to its declared
parent.

### Violation cap

At most 100 violations are returned, in the order they were produced. When more were produced, one
final detail follows with an empty `path`, the message `further violations omitted` and the type
`truncated`, so a result has at most 101 entries. Exactly 100 violations carry no truncation detail; 101
carry the first 100 and the truncation detail. The cap bounds the answer, not the request: the
transport must still cap the request body before it calls the validation methods. A single
bounded-input violation is unaffected.

### Fixed answers

Five situations produce one violation at the root with a fixed message. The caller's ids and names
are never echoed:

| Situation | `type` | `message` |
|---|---|---|
| The definition is unknown, or the version is not registered | `definition` | `definition is not registered` |
| The step is unknown, or the decision is not declared on that step | `decision` | `decision is not declared for this task step` |
| A payload is submitted for a payload-less decision | `payload` | `payload is not accepted for a decision without a payload type` |
| A payload is submitted for a payload-less start | `payload` | `payload is not accepted for a start type without a payload` |
| A required payload is null | `payload` | `payload is required` |

An unknown step and an unknown decision give the same answer. The unknown definition answer is
distinct from them, which matters for Security below.

### Void payloads

A `void` decision accepts only null; so does a start declared `void` or `java.lang.Void`. Any non-null
payload, an empty object included, is one `payload` violation. A decision or start with a payload type
rejects null as required.

A definition that declares no start type at all has nothing to validate: `validateStart` accepts any
payload, null, an empty object or a filled one, without a violation. The engine passes the payload
through for such a definition, so the two agree.

### Bounded input

Keyed validation limits the strings a pattern checks to 4,096 UTF-16 code units each and to 262,144
UTF-16 code units per validation. These are the shared defaults; there is no configuration key. A
payload over either limit produces one `patternInputLength` or `patternInputTotalLength` violation at
the root with the argument `limit`, and no regular expression runs on the rejected input. The
engine's own bean-validation path is not bounded this way.

### Schema acceptance and binding

Validation checks the payload against the schema. Binding to the Java type is a separate step in the
engine, and the two are not identical:

- **Declared keys bind.** A payload the schema accepts binds under the process profile mapper for
  the declared properties.
- **An undeclared key passes the schema and fails binding.** The generated schema does not forbid
  extra keys, but the strict process profile rejects them when the engine binds the payload, and the
  engine answers that with a binding 400. A profile that disables the strict feature would bind such
  a key instead. The engine logs the decode cause at DEBUG unsanitized; it can quote submitted text, so
  keep DEBUG off for the engine loggers in production or sanitize at the log sink.
- **The engine may still reject a payload the schema accepts.** Class-level and custom constraints
  that a schema cannot express are enforced only by the engine.
- **Document payload types accept any object.** A `JsonObject` or `ObjectNode` payload type has the
  schema `{"type":"object"}`, so preflight accepts any JSON object and the decision applicator owns
  its validation.

### Behavior of validation

- **No class loading, generation or compilation per call.** Validation runs the validator compiled at
  registration, so it is non-blocking and may run on the event loop.
- **Catalog coherence.** A definition rejected at registration, whether as a duplicate or by another
  validator, is neither describable nor validatable.

---

## Security

The descriptors and the validation answers reveal which definitions, steps and decisions exist: an
unknown definition and an unknown decision produce different violations. Place every `describe`,
`decision`, `validateDecision` and `validateStart` call behind a definition-read action, and answer an
unknown definition and a definition the caller may not read with the same status. Otherwise a caller
can enumerate definition, step and decision names from the difference.

Validation details are value-free by construction, so they can be returned to the caller without
echoing the submitted payload.

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
- `vertique-json-schema-validation` for the bounded pattern input, the pattern walk and the violation
  details.
- `vertique-core` for the shared JSON and validation types, including `ViolationDetail`.

---

## Common Mistakes

- **Not installing `WorkflowSchemaModule`.** Registration then succeeds without any payload check,
  and an unsuitable type surfaces only when a decision is submitted. A scope-owned definition whose state class
  differs from the default scope's is refused, because no state comparison is available.
- **Installing it without the JSON runtime module.** The validator needs `JsonMapperProfileRegistry`
  and `JsonConfig`; the component fails to build without them.
- **Reading descriptors without the module in the registry's component.** `describe` and the
  validation methods then throw `IllegalStateException` for any definition that declares a payload
  type.
- **Leaving out `ValidationModule`.** The module works without it, but the schema then ignores
  bean-validation constraints that the engine still enforces.
- **Declaring a scalar or array payload type.** `String`, `int`, `List<X>` and single-value records
  are rejected at registration. Wrap the value in a record.
- **Calling `activate` on the event loop.** Schema generation and pattern compilation block the
  calling thread.
- **Expecting a pattern limit on the engine path.** A `@Pattern` constraint checked by the engine's
  bean-validation path runs on strings of any length; only keyed validation is bounded. Do not rely
  on this module to cap pattern input that the engine evaluates.
- **Treating a valid result as proof the engine accepts the payload.** An undeclared key passes the
  schema and fails binding, and class-level or custom constraints are enforced only by the engine.
- **Sending an empty object for a payload-less decision.** A `void` decision or start accepts only
  null; `{}` is a violation.
- **Answering an unknown and a not-permitted definition differently.** The distinct statuses let a
  caller enumerate definition, step and decision names.
- **Returning violation text or paths built from the request.** Return the details as given; they
  are value-free, and rebuilding them from the submitted payload would leak values.
