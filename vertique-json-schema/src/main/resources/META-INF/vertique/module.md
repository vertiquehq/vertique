<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# JSON Schema Module

> **Status:** Alpha
> **Package:** `dev.vertique.json.schema`
> **Artifact:** `vertique-json-schema`
> **Depends on:** core

Generates deterministic, annotation-driven Draft 2020-12 JSON Schema documents from resolved Java
`Type` values, through Victools configured with the Jackson, Jakarta Validation, and Swagger 2
annotation modules. The module is transport-neutral: it has no dependency on REST, MCP, Vert.x
Web, `vertx-json-schema`, Dagger, Micrometer, or OpenTelemetry, so any consumer that already
resolves a Java type and, optionally, a JSON mapper profile can generate a schema for it without
pulling in a transport framework.

This module is not a schema registry, a validation engine, or a general-purpose schema DSL. It
produces one canonical document per call; consumers own caching, validator compilation, and
runtime value validation.

---

## When To Use It

Install `dev.vertique:vertique-json-schema` when a consumer needs a deterministic JSON Schema for
a resolved Java type from its Jackson, Jakarta Validation, and Swagger annotations — for example a
REST framework synthesizing request-body schemas, or a tool-protocol server publishing input and
output schemas for generated types. It pairs naturally with `dev.vertique:vertique-json` when the
consumer already resolves an effective `JsonMapperProfile` and wants schema generation aligned
with that profile's mapper and declared wire-shape overrides.

---

## Core Concepts

### Three construction modes

`AnnotationJsonSchemaGenerator` is constructed through exactly one of three static factories, each
selecting how Victools discovers Jackson properties and which profile-declared schema-type
overrides apply:

- `withVictoolsDefaults()` — Victools' own default mapper, with no profile override applied. This
  is the mode a consumer without a resolved `JsonMapperProfile` uses.
- `forInputProfile(JsonMapperProfile)` — property discovery and external property names use the
  input-direction Jackson introspection of `profile.mapper()`; mapper mix-ins, explicit names,
  naming strategies, and write-only/read-only access are honored, so read-only properties are not
  advertised as accepted input; only the profile's `INPUT`- and `BOTH`-direction schema-type
  overrides apply.
- `forOutputProfile(JsonMapperProfile)` — property discovery and external property names use the
  output-direction Jackson introspection of `profile.mapper()`; the same mapper metadata applies,
  so write-only properties are not advertised as emitted output; only the profile's `OUTPUT`- and
  `BOTH`-direction schema-type overrides apply.

All three modes install the same Victools modules and options — the Jackson module, the Jakarta
Validation module with `NOT_NULLABLE_FIELD_IS_REQUIRED` and `INCLUDE_PATTERN_EXPRESSIONS`, and the
Swagger 2 module — so only the mapper source and the selected profile overrides differ between
modes.

### Canonical output

`generateCanonical(Type)` returns a fresh, compact JSON document with every object member whose
key is `default` and whose value is exactly the string `##default` removed, and every object's
keys recursively ordered by `String.compareTo` UTF-16 code-unit order; arrays are never reordered.
The document is emitted through a generator-owned neutral writer, never through the profile
mapper, so the valid-document guarantee does not depend on the mapper's own serialization
configuration. Equal resolved types, annotations, construction mode, mapper configuration,
selected profile direction, and canonical override fragments produce byte-identical documents
across independent instances and repeated calls. Calls on one instance are safe from multiple
threads; the complete generation and canonicalization operation is serialized per instance.

Any failure — an unrepresentable `Type`, an invalid or conflicting profile override declaration,
a detected structural conflict, or an unexpected Victools failure — is normalized to
`JsonSchemaGenerationException`, a single bounded exception type with no Victools type in its
signature. Stack exhaustion during generation, which is how a pathologically deep type graph fails
inside the generator's recursive descent, is normalized the same way, so a deep type does not
bypass the bounded failure contract merely because the JVM reports it as an `Error`. A VM-level
error such as `OutOfMemoryError` is deliberately not normalized: it describes the runtime rather
than the requested type, and propagates unchanged.

A failed call leaves the generator fully reusable: it restores the per-generation state the
underlying generator holds before propagating, so a rejected type never changes what a later call
on the same instance publishes. Reuse a generator freely after a failure — there is no need to
discard and rebuild one.

### Profile overrides consumption

A profile's `jsonSchemaTypeOverrides()` (declared in `dev.vertique.core.json.JsonMapperProfile`,
`dev.vertique:vertique-core`) lets a profile describe an exact-class wire shape that Jackson
inspection cannot infer — for example a `BigDecimal` field a matched custom serializer/deserializer
pair represents as a bounded decimal string rather than a JSON number. The generator applies a
profile's override fragment as the baseline wire contract for that exact class wherever it is
reached — the mapped root type, a property, or a collection element — and never to a map key.
Property-level Swagger schema metadata and applicable Jakarta constraints then narrow that
baseline through explicit conjunction; neither contributor overwrites the other's declared
keyword.

A constraint that does not apply to the substituted wire type is not published as if it did. The
numeric-domain keywords `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, and
`multipleOf` are suppressed at a property whose effective declared type excludes both `number` and
`integer` — so `@DecimalMin("0.01")` or `@Schema(multipleOf = 0.01)` on a `BigDecimal` the profile
republishes as a decimal string emits no numeric keyword against that string schema. Which
contributor supplied the keyword is irrelevant; the effective wire type alone decides. Bean
Validation still enforces the constraint against the materialized Java value — only the published,
wire-facing keyword is dropped.

Suppression is per-property and never rewrites a shared `$ref` target, because several properties
may reference the same generated definition and one property's effective type is not the others'
to narrow. A shared definition is cleaned only on its own terms — when the definition itself
declares a type that excludes both numeric types. So when an override fragment contributes a
numeric keyword *without* declaring a type, that keyword stays visible at a property whose
effective type is not numeric. It is inert there rather than wrong: JSON Schema applies a numeric
keyword only to a number instance. Declare a `type` in an override fragment that carries numeric
bounds if you want those bounds confined to numeric referrers.

### Accepted type grammar

`generateCanonical(Type)` accepts a *resolved* type, recursively: a non-null `Class` (including a
primitive class, an array class, and a raw generic class), a `ParameterizedType` whose optional
owner type, raw type, and arguments are themselves accepted, and a `GenericArrayType` whose
component type is accepted. It rejects `null`, a `TypeVariable`, a `WildcardType`, any nested
occurrence of either unresolved form, and an unknown custom `Type` implementation. The rejection is
eager — it happens before Victools is invoked — so an unrepresentable type never produces a
partially built document. A recursive object graph is fully supported; it is not an unresolved
type.

### Constraints and common mistakes

Two annotation combinations fail generation rather than producing a schema that quietly
misdescribes the wire:

- **An `implementation = ...` redirect on a property whose declared type graph carries a profile
  override.** The Swagger module redirects the property's resolved type before the profile's
  override is consulted, so the override fragment would be dropped without a trace. Generation
  therefore fails with a bounded `JsonSchemaGenerationException` naming the property. Declare the
  wire shape through the profile override *or* through `implementation`, not both. Every form the
  Swagger module reads the redirect from is covered: a direct `@Schema(implementation = ...)`,
  `@ArraySchema(schema = @Schema(implementation = ...))` on a container's element, and
  `@ArraySchema(arraySchema = @Schema(implementation = ...))`. An annotation on either the field or
  its accessor counts, matching how the Swagger module resolves it. The detection reads the
  property's declared type — resolved against its declaring context, so a member inherited from a
  generic supertype is checked against the binding subtype's actual class — plus, recursively, its
  type arguments and array element types, including the element or payload type a subclass or
  implementor binds in its `extends`/`implements` clause rather than declaring itself; a map **key**
  position is excluded, since a map key is never fragment-bearing. This search is deliberately wider
  than the set of positions an override fragment is actually published at: it rejects the
  combination whenever an override is *reachable* from the declared type, not only where the
  fragment would have applied, because a rejection is visible and resolvable while a dropped
  fragment is neither. A declared type graph nesting deeper than 64 levels also fails, because
  past that bound the absence of an override has not been proven.
  The search follows the declared type's own parameterization — its type arguments, array element,
  and inherited container or wrapper bindings — and does **not** descend into the *members* of the
  types it finds. A redirect on a property whose declared type is a DTO therefore succeeds even when
  that DTO's own fields carry profile-overridden types: the redirect replaces the DTO's schema
  wholesale, exactly as asked, and no fragment the profile publishes elsewhere is contradicted. Use
  `implementation` only where you intend the declared type's schema — including anything nested
  inside it — to be replaced.
- **A conjunction of disjoint explicit `type` keywords.** After generation, every conjunctive
  location — a subschema node, its direct `allOf` branches, and its locally resolved `$ref`
  targets — must admit at least one explicit `type` across the declarations found there. The
  types are intersected, refined by the one subtype relation JSON Schema's type vocabulary
  carries: `integer` is the integral subset of `number`, so conjoining the two narrows to
  `integer` rather than emptying — `@Schema(allOf = {Integer.class})` on a `double` property
  generates normally. An empty result is an unsatisfiable contract and fails generation. `anyOf`
  and `oneOf` branches are alternatives, not conjunctions, so a nullable overridden property
  remains valid.
  Only a `$ref` this module can resolve inside the document itself — `"#"` or a `"#/"`-rooted JSON
  pointer — is followed; a `$anchor` reference such as `@Schema(ref = "#anchorName")`, an external
  URI, or an unresolvable pointer is skipped, so it never fails generation. A resolvable pointer is
  followed only when its target is itself a schema position (see below): `#/$defs/Money` and
  `#/properties/amount` are conjoined, while a pointer at data such as `#/default`, or at the
  container object under `#/$defs/Money/properties`, contributes nothing.

`@Schema(type = ...)` has no effect in this module; `implementation` is the supported way for a
property to contribute a type shape.

### What counts as a schema position

Both post-generation passes — the disjoint-type check above and numeric-keyword suppression —
traverse the generated document through Draft 2020-12 **subschema positions only**, starting at the
document root. They descend into `not`, `if`, `then`, `else`, `items`, `contains`,
`additionalProperties`, `propertyNames`, `unevaluatedItems`, `unevaluatedProperties`,
`contentSchema`, the branches of `allOf`, `anyOf`, `oneOf`, and `prefixItems`, and the member values
of `properties`, `patternProperties`, `$defs`, and `dependentSchemas`. Nothing else is descended. The
object that is the *value* of `properties`, `patternProperties`, `$defs`, or `dependentSchemas` is a
container, not a schema: its keys are member names, so a property literally named `type` or `allOf` is
read as a name and never as a keyword. A boolean `true`/`false` schema is a legal subschema and is
reached, but neither pass has a keyword to read on one.

This matters when an override fragment carries JSON **data**. Draft 2020-12 treats an unrecognized
keyword as an annotation — arbitrary data — and the values of `default`, `const`, `enum`, and
`examples` are data even though the keywords are defined. Such a value is never read as a schema: an
object in a `default` position keeps every member it declares, including a `minimum` the numeric
filter would otherwise strip, and an object in a `const` position that happens to look like an
unsatisfiable schema does not fail generation. A `definitions` member is data for the same reason —
this generator publishes definitions under `$defs`, which a fragment may not declare, so a
`definitions` member can only have come from a fragment as annotation content.

The same rule decides which `$ref` targets are conjoined, because a `@Schema(ref = "#/...")` value
reaches the document verbatim and may point anywhere in it. A pointer is followed only when it
resolves to one of the positions listed above; a pointer at a data value or at a container object is
skipped exactly as an unresolvable one is. So `@Schema(ref = "#/$defs/Money")` and
`@Schema(ref = "#/properties/amount")` keep contributing their target's `type` to the referring
location, while `@Schema(ref = "#/default")` neither fails generation on the data's `type` nor lets
that `type` decide whether the referring location's numeric keywords are suppressed.

Reaching a real subschema is unaffected: a genuine `type` conflict at any of the positions listed
above still fails generation.

---

## Key Classes

### AnnotationJsonSchemaGenerator

The single entry point. Construct with `withVictoolsDefaults()`, `forInputProfile(profile)`, or
`forOutputProfile(profile)`, then call `generateCanonical(Type)` for each type that needs a schema.

```java
AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.withVictoolsDefaults();
String schemaJson = generator.generateCanonical(MyRequestBody.class);
```

A profile-aware consumer supplies its already-resolved `JsonMapperProfile` and the direction that
matches the schema's role:

```java
AnnotationJsonSchemaGenerator inputGenerator =
        AnnotationJsonSchemaGenerator.forInputProfile(resolvedProfile);
String argumentSchema = inputGenerator.generateCanonical(toolArgumentType);
```

### JsonSchemaGenerationException

The one bounded failure type this module throws, with a value-free, length-bounded message and
the original cause preserved when one exists. Consumers catch and translate this exception without
depending on any Victools exception type.

`getMessage()` is safe to log verbatim. It is at most 512 UTF-16 code units — a hard bound, not an
approximation — and carries no code point that could terminate a log record, forge a second one, or
reorder the identity it renders: every Unicode `Cc` control (including the C1 block and `NEL`), `Cf`
format character (including the Trojan-Source bidirectional overrides and isolates, `SOFT HYPHEN`,
and the byte-order mark), `Zl`, `Zp`, and unpaired surrogate is replaced one-for-one with `?` before
the message is bounded, and an elision never splits a surrogate pair. Identity a type, property, or
profile contributed therefore stays readable while a hostile name can neither inject a line break
nor make the message read as naming a different type or profile than the one that failed. The one
stated limitation: a supplementary-plane `Cf` code point (U+110BD, U+1D173–U+1D17A) survives, since
none of them reorders or terminates rendered log text.

That guarantee covers the message only. The **attached cause is not sanitized or bounded** — it is
preserved raw, deliberately, because it is what makes a failure diagnosable. A conventional
`log.error("…", ex)` renders `Caused by: <cause message>`, which may be arbitrary third-party or
application text. Log the cause where that is acceptable; log `getMessage()` alone where it is not.

---

## Extension Points

None. This module exposes a closed generation surface — no schema-generator SPI, custom Victools
module registration, or configuration key. A consumer that needs a custom wire shape for an exact
Java class declares it once on its `JsonMapperProfile` through
`dev.vertique.core.json.JsonSchemaTypeOverride`, which this module's profile-aware construction
modes consume.

---

## Dependencies

| Artifact | Scope | Purpose |
|----------|-------|---------|
| `dev.vertique:vertique-core` | compile | `JsonMapperProfile`, `JsonSchemaFragment`, `JsonSchemaTypeOverride` — the stable JSON profile contracts this module consumes |
| `com.fasterxml.jackson.core:jackson-databind` | compile | `ObjectMapper` property discovery that Victools' Jackson module introspects |
| `jakarta.validation:jakarta.validation-api` | compile | Jakarta Validation constraint annotations Victools' Jakarta Validation module introspects |
| `io.swagger.core.v3:swagger-annotations-jakarta` | compile | `@Schema` / `@ArraySchema` annotations Victools' Swagger 2 module introspects |
| `com.github.victools:jsonschema-generator` | compile | The Draft 2020-12 schema generation engine |
| `com.github.victools:jsonschema-module-jackson` | compile | Jackson property discovery module |
| `com.github.victools:jsonschema-module-jakarta-validation` | compile | Jakarta Validation constraint mapping module |
| `com.github.victools:jsonschema-module-swagger-2` | compile | Swagger 2 annotation mapping module |
