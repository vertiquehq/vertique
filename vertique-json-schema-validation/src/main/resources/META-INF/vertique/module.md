<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# JSON Schema Validation Module

> **Status:** Stable
> **Package:** `dev.vertique.json.schema.validation`
> **Artifact:** `vertique-json-schema-validation`
> **Depends on:** core, `io.vertx:vertx-json-schema`

Framework-support code for validating payloads against generated JSON Schema documents with the
`vertx-json-schema` engine. It bounds the input that schema regular expressions may see, reports
schema patterns that do not compile without echoing them, names the keyword vocabulary those steps
share, and turns engine output into value-free `ViolationDetail` entries. It is transport-neutral and
has no dependency on REST, MCP, Vert.x Web, Dagger, Micrometer, or OpenTelemetry.

**Applications do not use this artifact directly.** Install a framework module that validates
payloads (for example `vertique-rest-validation`) instead. Every public type in
`dev.vertique.json.schema.validation` is an INTERNAL framework seam — outside the application
maturity promise — even though this module's `Status` is Stable for the validation behavior those
consumers rely on.

This module is not a schema generator, a validation engine, or a general-purpose validation DSL. It
supplies the bounding, pattern-checking, and error-shaping steps around an engine that framework
consumers drive themselves; they own schema generation, caching, and the response a client sees.

---

## When To Use It

Do **not** add this dependency from application code. Use it only when authoring or extending a
Vertique framework module that validates payloads against generated schemas with `vertx-json-schema`
and must (a) bound the work a schema's regular expressions can do on untrusted input, (b) fail fast at
registration time on a schema pattern that does not compile, or (c) report violations without ever
placing instance text into a message.

---

## Core Concepts

### Two bound dimensions

`PatternInputGuard` limits the text that regular-expression-backed checks evaluate, in UTF-16 code
units:

- **Per string** — a single string longer than the limit is rejected before any expression runs.
  The default is `PatternInputGuard.DEFAULT_MAX_CHARS` (4,096).
- **Per validation window** — the running sum of every bounded string within one window is limited as
  a whole. The default is `PatternInputGuard.DEFAULT_MAX_TOTAL_CHARS` (262,144).

Both apply at the same positions: the instance string at a `pattern` keyword, the property names
under a non-empty `patternProperties`, and the instance string under the three regex-backed formats
that are not measured as linear-time — `idn-hostname`, `idn-email`, and `regex`. A string at any
other position is neither bounded nor counted. The guard also decides the formats `uri`,
`uri-reference`, `url`, `uri-template`, `json-pointer`, `relative-json-pointer`, and
`json-pointer-uri-fragment` without regular expressions, and a rejection of one of them carries a
fixed message that never contains the instance.

### Value-free by construction

No method in this module places instance text into a message. A violation message is built from the
failed keyword and its schema arguments, or it is the fixed text `"does not satisfy the schema"`
(`ViolationDetails.VALUE_FREE_MESSAGE`). A raw engine message reaches a detail only as the fallback for
a `required` violation, which names a property, never a value.

### Pattern walk at registration time

`SchemaPatterns.precompile` walks a schema once, at registration time, and reports every `pattern`
and every `patternProperties` key that `java.util.regex` cannot compile. The walk never enters the
value of a literal keyword (`const`, `enum`, `default`, `examples`, `example`), and it reads every
member of `properties`, `patternProperties`, `$defs`, and `dependentSchemas` as a schema whatever
its name. A `patternProperties` key is itself a regular expression, so a failure there, or beneath
it, reports the key as `[key-N]` (its ordinal among that object's keys) and never as the expression.
The failure carries the JSON Pointer of the position, the JDK's description of the syntax error, and
its index — never the pattern text.

### Violation detail shape

`ViolationDetails.collect` renders engine output as `dev.vertique.core.validation.ViolationDetail`
entries:

- **`path`** — the engine's instance location after cut-back to a declared location, in the
  `PathForm` the caller requests: `URI_FRAGMENT` is the engine's own form, verbatim, including its
  percent-encoding (`#/a%20b`); `RFC6901` is the JSON Pointer form (`/a b`), with the document root as
  the empty string. A missing location is the empty string.
- **`type`** — the violated constraint keyword (`maxLength`, `minimum`, `required`, ...), or `null`
  when no actionable keyword exists.
- **`message`** — a value-free message for that keyword, rendered from the schema's own arguments.
- **`args`** — the constraint arguments resolved from the schema (for example `{maxLength: 3}`), or
  `null` when there are none. Resolution follows at most 16 `$ref` hops, 64 composed subschemas, and
  10 index digits; beyond those it returns no arguments rather than guessing.

A keyword that only traverses structure (`properties`, `items`, `allOf`, `anyOf`, `additionalProperties`,
and the like — `SchemaKeywords.STRUCTURAL_KEYWORDS`) is a wrapper, not a violation: `collect` looks
through wrappers to the concrete constraint, and when every error was a wrapper it returns one
value-free detail instead. A violation with no usable keyword location likewise yields a value-free
detail at its own instance location.

Pattern-bound rejections have their own two types, `patternInputLength` and
`patternInputTotalLength`, each with `path ""` and `args` `{limit: <n>}`;
`ViolationDetails.patternInputBound` maps the guard's exceptions to them.

### `rfc6901` conversion

`ViolationDetails.rfc6901` converts an engine location to RFC 6901 form: it strips a leading `#`,
splits on `/`, percent-decodes each segment as UTF-8 (an invalid escape is left as it is), and keeps
`~0` and `~1` untouched. `#`, the empty string, `/`, and `null` all become the empty string.

### No configuration

This module has no configuration properties and reads no configuration source. The bounds are
constructor arguments of `PatternInputGuard`; a consuming module decides where its own limits come
from.

---

## Key Classes

### PatternInputGuard

Compiles a schema into a `Validator` whose pattern positions are bounded.

- `new PatternInputGuard(maxChars, maxTotalChars)` — the two limits, in UTF-16 code units; the
  `DEFAULT_MAX_CHARS` and `DEFAULT_MAX_TOTAL_CHARS` constants hold the defaults.
- `static JsonObject rewrite(JsonObject schema)` — returns a copy of the schema in which each bounded
  position gains one entry `{"format": "x-vertique-pattern-input-bound"}` appended to its `allOf`
  (`BOUND_FORMAT`), and each non-empty `patternProperties` gains a `propertyNames` entry carrying the
  same bound. Entries are appended, never prepended, so every existing `allOf` index and reported
  keyword location stays valid against the original schema. The argument is never modified.
- `Validator compile(JsonObject schema, JsonSchemaOptions options)` — rewrites and compiles in one
  step; the guard is the compiled copy's format validator.
- `Window openWindow()` — opens the calling thread's per-validation window; `Window.close()` ends it.
  Open one around each validation and close it in `finally`.
- `PatternInputGuard.PatternInputTooLong` (`maxChars()`) and
  `PatternInputGuard.PatternInputTotalExceeded` (`maxTotalChars()`) — thrown out of
  `Validator.validate` when a bound is exceeded.

```java
PatternInputGuard guard = new PatternInputGuard(
        PatternInputGuard.DEFAULT_MAX_CHARS, PatternInputGuard.DEFAULT_MAX_TOTAL_CHARS);
Validator validator = guard.compile(schema, JsonSchemaValidationOptions.draft202012());
try (PatternInputGuard.Window window = guard.openWindow()) {
    OutputUnit result = validator.validate(payload);
    List<ViolationDetail> details = ViolationDetails.collect(result, schema, false, PathForm.RFC6901);
} catch (PatternInputGuard.PatternInputTooLong e) {
    ViolationDetail detail = ViolationDetails.patternInputBound(e).orElseThrow();
}
```

### SchemaKeywords

The keyword vocabulary shared by the walk, the rewrite, and the detail builder: `PATTERN`,
`PATTERN_PROPERTIES`, `LITERAL_KEYWORDS`, `NAMED_MEMBER_KEYWORDS`, and `STRUCTURAL_KEYWORDS`, plus
`extractKeyword(String)` (the last segment of a keyword location), `isStructuralLocation(String)`, and
`constraintKeyword(OutputUnit)` (the violated keyword, or `null` for a structural wrapper or an absent
location).

### JsonSchemaValidationOptions

`draft202012()` returns the `JsonSchemaOptions` every consumer uses to compile a Draft 2020-12 schema,
so that guard-compiled validators and directly compiled ones agree on dialect and output format.

### SchemaPatterns and PatternCompileFailure

`SchemaPatterns.precompile(JsonObject)` returns the list of `PatternCompileFailure(pointer,
description, index)` records described under "Pattern walk at registration time"; an empty list means
every pattern compiles.

### PathForm

`URI_FRAGMENT` (the engine's own instance-location form, verbatim) or `RFC6901`. The caller chooses
per call to `ViolationDetails.collect`.

### ViolationDetails

- `collect(OutputUnit result, JsonObject schema, boolean failFast, PathForm form)` — the detail list
  described under "Violation detail shape". With `failFast` true it stops after the first
  non-structural violation.
- `valueFree(String path)` — a detail with the fixed message and `null` `type` and `args`.
- `safeDetail(String keyword, Map<String, Object> args, String rawMessage)` — the keyword-canonical,
  value-free message for a violated keyword.
- `patternInputBound(Throwable)` — an `Optional<ViolationDetail>` for a guard exception found in the
  throwable or its cause chain, empty for anything else.
- `rfc6901(String)` — the conversion described above.
- Constants `VALUE_FREE_MESSAGE`, `PATTERN_INPUT_LENGTH_TYPE`, and `PATTERN_INPUT_TOTAL_LENGTH_TYPE`.

---

## Module Dagger Bindings

None. The module declares no Dagger `@Module`, `@Component`, or injectable type; consumers construct
`PatternInputGuard` themselves and own its lifetime.

---

## Dependencies

- `vertique-core` — `dev.vertique.core.validation.ViolationDetail`, the detail type this module
  produces.
- `io.vertx:vertx-json-schema` — the schema engine whose `Validator`, `OutputUnit`, and
  `JsonSchemaOptions` the guard and the detail builder wrap.

Nothing else is a compile dependency; in particular the module depends on no REST module, no
`vertique-json`, and not on `vertique-json-schema`.
