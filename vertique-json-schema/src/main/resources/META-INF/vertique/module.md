<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# JSON Schema Module

> **Status:** Experimental
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
- `forInputProfile(JsonMapperProfile)` — property discovery uses `profile.mapper()`; only the
  profile's `INPUT`- and `BOTH`-direction schema-type overrides apply.
- `forOutputProfile(JsonMapperProfile)` — property discovery uses `profile.mapper()`; only the
  profile's `OUTPUT`- and `BOTH`-direction schema-type overrides apply.

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
signature.

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

- **`@Schema(implementation = ...)` on a property whose declared type graph carries a profile
  override.** The Swagger module redirects the property's resolved type before the profile's
  override is consulted, so the override fragment would be dropped without a trace. Generation
  therefore fails with a bounded `JsonSchemaGenerationException` naming the property. Declare the
  wire shape through the profile override *or* through `implementation`, not both. The detection
  reads the property's declared type plus, recursively, its type arguments and array component
  types; a map **key** position is excluded, since a map key is never fragment-bearing. A
  `@Schema` on either the field or its accessor counts, matching how the Swagger module resolves
  the annotation.
- **A conjunction of disjoint explicit `type` keywords.** After generation, every conjunctive
  location — an object node, its direct `allOf` branches, and its locally resolved `$ref`
  targets — must have a non-empty intersection of the explicit `type` sets declared there. An
  empty intersection is an unsatisfiable contract and fails generation. `anyOf` and `oneOf`
  branches are alternatives, not conjunctions, so a nullable overridden property remains valid.

`@Schema(type = ...)` has no effect in this module; `implementation` is the supported way for a
property to contribute a type shape.

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
