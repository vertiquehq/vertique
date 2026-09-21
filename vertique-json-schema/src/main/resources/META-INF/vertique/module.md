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
- `forInputProfile(JsonMapperProfile, jakarta.validation.Validator)` — identical to
  `forInputProfile(JsonMapperProfile)`, except that value-schema constraints are additionally
  sourced from Bean Validation metadata — the annotation walk still runs first, as the floor every
  generation mode shares; the metadata source only supplements or, for a bounded set of shapes,
  corrects it. See "Constraint sources" below. Pass `null` (or use the single-argument overload)
  when no `Validator` is available; a `null` validator is exactly the single-argument overload's
  behavior, not a degraded mode.
- `forOutputProfile(JsonMapperProfile)` — property discovery and external property names use the
  output-direction Jackson introspection of `profile.mapper()`; the same mapper metadata applies,
  so write-only properties are not advertised as emitted output; only the profile's `OUTPUT`- and
  `BOTH`-direction schema-type overrides apply. There is no validator-accepting overload for this
  direction: constraint sourcing applies to input generation only.

All three modes install the Jackson module, the Jakarta Validation module
(`NOT_NULLABLE_FIELD_IS_REQUIRED`, `INCLUDE_PATTERN_EXPRESSIONS`), and the Swagger 2 module —
**unconditionally, including a validator-backed input generator**. The Jakarta Validation module is
the floor: whatever it renders for a scoped field or getter is rendered whether or not a `Validator`
is supplied, so a document generated with a validator still renders every keyword a document
generated without one would have. A supplied `Validator` only ever *supplements* that floor — see
"Constraint sources" below — it never disables or replaces it. The one documented exception is
several `@Pattern` constraints in the default group on one member (see "Rendering" below): the
annotation walk cannot see more than one of them at all, so a supplied `Validator` there changes what
is rendered rather than only adding to or correcting it.

### Constraint sources: the floor, and the Bean Validation supplement

The input direction's value-schema constraints (`minLength`, `maximum`, `pattern`, `required`, ...)
always start from a floor that is present whether or not a `Validator` is supplied, and gain a
supplement on top when one is:

- **The floor.** A field or getter with a Victools member scope is described by Victools' own Jackson
  and Jakarta Validation modules, unconditionally. A creator parameter, setter, or builder method has
  no such scope; its constraints are read directly from Jackson's merged annotation map (which
  already carries the same-named field's and getter's annotations — Jackson's own statement that they
  are one logical property, never a name coincidence the walk goes looking for), and a builder method
  borrows the built type's Jackson-introspected property of that same wire name — also
  unconditionally, whether or not a validator is supplied — **when the borrow is sound**. **Builder
  borrow assumption (owner ruling, `spike/deserializer-driven-schema`, round 2).** A builder method is
  assumed to set the built property of that same wire name. The borrow now splits on whether that
  built-type property has a getter:
  - **Getter-backed property: borrowed unconditionally, for any builder.** This is exactly what
    `main`'s field walk always did — it published every getter-backed field's constraints regardless
    of what any builder method's body did with the value before assigning it — so the round-2 ruling
    restores that behavior rather than gate it on the builder's own shape. A hand-written builder that
    transforms the value before assigning it (dividing cents into whole units, say) still has its
    getter-backed built field's constraint borrowed, exactly as a genuine Lombok builder's does.
  - **Getter-less property (the private, no-accessor case): bounded to the shape `BuilderBorrowDetector`
    can vouch for.** Here the borrow is guaranteed by construction only for a Lombok `@Builder` setter,
    which this framework's own configuration types use throughout; a hand-written builder that does
    not reproduce that shape carries no such guarantee — the walk has no way to read a method body —
    so the borrow is bounded to the shape `BuilderBorrowDetector` detects entirely through
    `java.lang.reflect` over Jackson's own `RUNTIME`-retained annotations: the built type carries
    `@JsonDeserialize(builder = ...)` naming a `static` nested class of the built type named
    `<Type>Builder`; that class carries `@JsonPOJOBuilder(withPrefix = "", buildMethodName = "build")`
    — the exact values `@Jacksonized` generates — and a zero-argument `build()` returning the built
    type; and, for the property in question, a builder method with exactly one parameter whose type
    and name both match the built field exactly. Detection deliberately does not read
    `@lombok.Generated`: that annotation carries `RetentionPolicy.CLASS`, so recognizing it would mean
    parsing the compiled class file directly (as JaCoCo's own coverage exclusion does) rather than
    through ordinary reflection — disproportionate surface for a detection hint in a module whose
    compile dependencies are deliberately frozen. A hand-written builder that reproduces the whole
    shape by hand resolves too — the ruling tolerates that: it says a hand-written builder *may* go
    unresolved, not that it must; one that does not reproduce it gets no borrowed constraint at all,
    and its property is published by type only.

  The Bean Validation supplement's own, independent join for a builder method follows this same
  two-branch rule (round 2 correction): when the built type's Jackson-introspected property for that
  wire name has a getter and a backing field, `MetadataConstraintSource` joins unconditionally, exactly
  as the floor does — so a constraint invisible to every annotation-reflection path (an XML-mapped one,
  an inherited or interface one, a composed constraint's leaf) still supplements a getter-backed
  builder property once a `Validator` is supplied, the same as it already does for a field or getter
  with a schema-library member scope. Only for the getter-less property does the supplement still
  consult `BuilderBorrowDetector`'s soundness check (`MetadataConstraintSource` reflects over the built
  class directly, so without this gate it would silently re-add a hand-written builder's constraint the
  floor never publishes for that shape, whenever a `Validator` happens to be supplied) — an ordinary
  setter's join is unaffected either way, since a setter's own field is never in question the way a
  builder's built-type field is. A creator parameter's own equivalent — the
  compiled Java *parameter* name coinciding with an unrelated field's name — is not this kind of
  assumption and is never joined on: a creator parameter joins a field only by wire name, which is
  Jackson's own statement that the two are one logical property; nothing here reads a constructor body
  either, so nothing here is ever "guaranteed by construction" for a creator the way a Lombok builder's
  setters are. This is what keeps a `@JsonCreator` static-factory parameter's own constraint from being
  dropped even under a validator: Bean Validation itself can join a creator parameter only through a
  constructor, but the floor reads the parameter's own annotation directly and does not care which kind
  of creator it belongs to.
- **The Bean Validation supplement** (`Validator.getConstraintsForClass`; consulted only when a
  `Validator` is supplied to `forInputProfile`, and only *in addition to* the floor above). Unlike
  the floor, it sees constraints that cannot be joined by wire name or reflective annotation
  presence at all: a constructor-parameter constraint whose wire name differs from every field's Java
  name (the floor never joins a creator parameter to a field except by that exact wire-name match), a
  `List`/array container-element constraint, a constraint inherited through a superclass or an
  implemented interface, a composed constraint's leaves, and a constraint declared entirely through
  an XML mapping. Every keyword it proposes is either an **addition** — merged onto the floor's own
  rendering only where the floor left that keyword unset — or a **correction**, for the one named
  set of shapes the floor is known to render incorrectly or not at all (`@Range`, `@Length`, `@URL`,
  and a `@Pattern` flag — vertiquehq/vertique-dev#606): those replace the floor's rendering for that
  keyword unconditionally. The supplement never removes a keyword the floor already rendered
  correctly, including one declared in a non-`Default` Bean Validation group — the floor has no
  notion of validation groups at all, so a `@NotNull(groups = Admin.class)` on a plain field still
  renders `required`, exactly as generation without a validator would; only what the supplement
  itself would *add* is filtered by group. A member the floor and the supplement both render the
  same constraint for is expected to agree — see `SchemaCorpusMetadataCrossCheckTest`'s byte-identical
  cross-check.

**The join.** A field- or getter/setter-backed property joins to a `PropertyDescriptor` by the
member's Java bean name — the field name, or the name a getter/setter implies by stripping its
`get`/`is`/`set`/`with` prefix — **never** by the wire name; a builder method joins the same way, on
the built type, following the same two-branch rule as the floor's own borrow (see "Builder borrow
assumption" above): when the built type's Jackson-introspected property for that wire name has a
getter and a backing field, the supplement joins unconditionally; only for the getter-less property
does it still require `BuilderBorrowDetector` to judge the join sound. For the getter-less shape this
matches the floor exactly: an unresolved hand-written builder's property contributes no supplement,
exactly as it gets no borrow from the floor. For a getter-backed shape, the two sources both join, but
they are not redundant: the floor's own borrow only ever sees what the schema library's own Jakarta
Validation module can reach reflectively, so the supplement's own addition/correction merge over that
same property still adds a constraint invisible to every annotation-reflection path — inherited
through an interface, composed, or declared entirely through an XML mapping — once a `Validator` is
supplied, exactly as it already does for a field or getter with a schema-library member scope.
**Setter-only field-borrow fallback (owner ruling,
`spike/deserializer-driven-schema`).** The floor itself joins a setter to its backing field through
Jackson's own `BeanPropertyDefinition#getField()` — the field Jackson associates with the same
wire-named property, guaranteed by construction to be the one the setter's value corresponds to. That
accessor is `null` whenever Jackson's property definition carries no field member at all — for
example a private field with a setter and no getter and no public field, or a `transient` field
regardless of whether a getter is also present; other mapper settings can produce the same absence.
Bounded to exactly "no field member" (the getter's own presence or absence is
irrelevant — a transient field with both a getter and a setter still has no `getField()` to join
through, so it falls back the same as the getter-less shape; a *non*-transient field with a getter
still joins through `getField()` normally and never reaches this fallback), the floor falls back to
the field whose Java name equals the setter's own implied name — stripping only its own `set`/`with`
prefix (`impliedFieldName`), narrower than the `get`/`is`/`set`/`with` convention named above, since
this fallback only ever sees a setter, never a getter — which still carries the constraints the
developer wrote for the value even though Jackson's own property metadata cannot join it directly. A creator-parameter property joins to a `ParameterDescriptor` by its declaring
constructor and parameter index (`SettableBeanProperty.getCreatorIndex()`), never by name; a
static-factory creator's parameters join to nothing in Bean Validation (constrained constructors
only), which is exactly why the floor's own annotation read — not the supplement — is what renders
that shape. Where a property matches nothing in the supplement, it contributes no addition and no
correction — a silent no-op, not a failure. A `List`/array value's container-element constraints
(`getConstrainedContainerElementTypes()`, type-argument index 0) merge onto the property's `items`
subschema when that subschema is inline, as an addition; a `Map` value's element position is not
described by the generator at all today, so it is unaffected either way.

**The value-position kind** (`minLength` vs. `minItems` vs. `minProperties` for the same `@Size`
shape) is derived from the member's **declared Java type**, never from the schema's own rendered
`type` keyword — that keyword is unavailable at the point a `Map`, a bean, or an `Optional` value's
constraints are applied. Deriving it from the schema's `type` instead was tried and reverted: it
silently misrendered `@Size` on a `Map` as `maxLength`. The declared type is resolved as Jackson
itself sees it for the built type's own parameterization — never a raw `java.lang.reflect.Field` or
`Method`'s reflected type, which erases a generic holder's type variable to its bound regardless of
what a particular embedding binds it to. A member whose resolved kind is neither `STRING`, `ARRAY`,
nor `MAP` (a number, or an unrecognized kind) contributes no `@Size`/`@NotEmpty`/`@NotBlank` size
keyword at all: none of those three keyword families applies to it, so the correct render is
nothing, matching what the annotation walk renders for the same member kind.

**The group filter.** Only a constraint whose declared groups are empty or contain
`jakarta.validation.groups.Default` is proposed by the supplement (as either an addition or a
correction). `@Valid` cascades are never consulted, because the generator already descends into
nested types on its own.

**Rendering.** `@Size` renders `minLength`/`maxLength`, `minItems`/`maxItems`, or
`minProperties`/`maxProperties` depending on the value's kind; `@Min`/`@Max`/`@DecimalMin`/
`@DecimalMax` (respecting `inclusive`), `@Positive`/`@PositiveOrZero`/`@Negative`/`@NegativeOrZero`;
`@NotNull`/`@NotBlank`/`@NotEmpty` mark the property `required` — matching Victools'
`NOT_NULLABLE_FIELD_IS_REQUIRED` exactly, which treats all three identically; `@NotBlank`/
`@NotEmpty` additionally floor the size keyword at 1; `@Email` renders `format: email`; Hibernate's
`@Length`, `@Range`, and `@URL` render as corrections (recognized by fully-qualified annotation class
name — never by simple name alone, so an application-defined constraint whose own simple name happens
to collide with one of these, or with a plain Jakarta Validation type, is never mistaken for it — and
never by importing `hibernate-validator`'s constraint classes, so the metadata source stays usable
with any Jakarta Validation provider). A `@Pattern`'s flags are embedded as an inline Java regex
modifier group (`(?i:...)`, ...) as a correction — measured against the real `io.vertx.json.schema`
5.1.6 validator, which compiles the `pattern` keyword with plain `java.util.regex.Pattern` and honors
this — except `CANON_EQ`, which has no embeddable modifier character and fails generation with a
bounded diagnostic naming the property. Two or more `@Pattern` constraints in the default group on one
member — Jakarta Validation's own `@Pattern.List` repetition — render as an `allOf` of one
single-`pattern` subschema per constraint, sorted for deterministic output, rather than the second
silently overwriting the first; a composed constraint's own leaves render the same way as if declared
directly, recursively, *in addition to* — never instead of — the composing annotation's own rendering
when it is itself a recognized type (Hibernate's `@Range` is itself composed of `@Min` + `@Max` with
the same bounds, confirmed by disassembly). An unrecognized constraint type is skipped with a
`DEBUG`-level `System.Logger` log naming the type and the property (this module carries no
logging-facade dependency; see "Dependencies"). Without a `Validator` supplied, the annotation walk
does not see a repeated `@Pattern` at all — the member's actual reflective annotation is the
`@Pattern.List` container, not a repeated `@Pattern` — so this shape is one of the few where a
validator changes the rendered document rather than only supplementing it silently.

**Correction timing.** A correction is not applied at the moment Victools hands back a scoped
member's schema: measured, the library's own Jakarta Validation module does not finish writing every
attribute (`pattern` under `INCLUDE_PATTERN_EXPRESSIONS` in particular) by that point, and an
immediate write there made the library's own later write treat the correction as a conflicting value
and wrap both into `allOf` instead of the correction ever winning. Corrections and additions for a
scoped member are therefore recorded and applied once the whole document is finished generating,
before nullability and alias expansion run — the same "must wait for the finished document"
technique this module already uses for nullability and alias expansion.

**A correction never loosens a stricter bound the floor rendered from a different annotation.** A
correction is keyed by annotation type but applied by keyword: `@Range(min = 10, max = 20)` beside a
separate `@Min(15)` on the same member would otherwise let the `@Range` correction overwrite the
floor's own `minimum: 15` with `minimum: 10` — looser than both the floor and the binder, since Bean
Validation enforces the conjunction of every constraint on a member, not only the last one rendered.
When both the floor and a correction set the same bound keyword (`minimum`/`maximum`/
`exclusiveMinimum`/`exclusiveMaximum`/`minLength`/`maxLength`/`minItems`/`maxItems`/`minProperties`/
`maxProperties`), the stricter of the two wins — the larger value for a "min" keyword, the smaller for
a "max" one. For `pattern`, where "stricter" has no total order, a correction whose value genuinely
differs from the floor's is combined with it as an `allOf` of two single-`pattern` subschemas instead
of replacing it, so a client must satisfy both. The one exception is `#606`'s own flagged-`@Pattern`
rendering: when the correction's pattern is exactly the floor's own regexp wrapped in an inline
modifier group, it is the *same* `@Pattern` annotation rendered twice at different fidelity, not two
annotations in conflict, and the flag-aware rendering still replaces the floor's outright.

**Bootstrapping a `Validator`.** `HibernateValidator.configure().messageInterpolator(new
ParameterMessageInterpolator())` avoids an expression-language dependency; the default message
interpolator does not. This module never constructs a `Validator` itself
(`Validation.byDefaultProvider()` is never called here) — the caller always supplies one.

### Which properties the input direction describes

`forInputProfile(JsonMapperProfile)` describes a walked property when Jackson reports it
deserializable **or** it has a backing field, and its access is not `READ_ONLY`. A backing field
counts because Jackson populates a private field through reflection wherever the mapper infers
property mutators — Jackson's default, and the setting every built-in profile leaves alone — so the
commonest DTO shape of all, a private field reachable only through a getter, is described with its
type and format. So are a field-backed getter-only `List<String>` or `Map<String, String>` and a
type holding such a shape as a property.

A builder type is filled through its builder rather than through the field. A builder method's own
constraint is borrowed from the built type's Jackson-introspected property of the same wire name —
never a raw field-name scan. When that property has a getter, the borrow is unconditional, for any
builder — a Lombok `@Builder @Jacksonized` type, a hand-written builder that reproduces its shape, or
a hand-written builder that does not (round 2, owner ruling). When the property has no getter, the
borrow stays bounded to the shape the builder borrow detector can vouch for: a Lombok
`@Builder @Jacksonized` type, or the exact shape it generates by construction. **Closed (BG1):** a
constrained private field with no `@Getter` at all is invisible to Jackson's own introspection, so
the floor has nothing to join the builder method to — that one property still publishes, by type
only, with no borrowed constraint; it is not the whole document that stays unconstrained. When a
`Validator` supplement is active, it renders the constraint for that property anyway, by its own
field-name join, independent of whether the floor could borrow it.

The backing storage of a `@JsonAnySetter` or `@JsonAnyGetter` is never described as a named
property, because the keys those accessors collect are extra keys rather than members of the
object's property set. The storage is identified **by member alone** — a field annotated
`@JsonAnySetter`, the record component whose field that is, a field annotated `@JsonAnyGetter`, and
the field a method `@JsonAnyGetter` returns — so a real property is never hidden merely because its
name matches one an accessor method implies: a constrained `attribute` property beside an any-setter
`setAttribute(String, Object)` stays described with its constraint. For a type Jackson deserializes
as map-like or collection-like, the any-setter is ignored, as Jackson itself ignores it.

A property marked `@JsonIgnore` or read-only stays absent from the input document, and a write-only
property is described with `writeOnly: true`. This rule applies to the input direction only:
`forOutputProfile(JsonMapperProfile)` describes a property Jackson reports serializable whose access
is not `WRITE_ONLY`, unchanged.

This rule decides which walked properties are *described*; it does not make every key the binder
accepts a described property. A key the schema does not describe is left to the binder and to Bean
Validation.

**A type whose resolved deserializer is not a bean deserializer** is refused with a bounded
diagnostic when the type's own class carries an explicit type-level
`@JsonDeserialize(using = ...)` (or equivalent) — genuinely bean-like structure whose deserializer was
swapped out — **or** when the mapper's own reflective introspection still reports a settable property
(a field, a setter, or a creator parameter) for the type even though its deserializer was attached some
other way: a profile module's `SimpleModule.addDeserializer(...)`, or a `BeanDeserializerModifier`
wrapper, carries no class-level annotation to detect, so an application DTO shaped this way is still
bean-like and still has a field walk this refusal protects. A type with no settable property at all —
a scalar, container, node, or Vert.x-style opaque wrapper such as `JsonObject`, `JsonArray`, or
`Buffer`, none of which ever had bean properties to begin with — is instead described as accepting any
JSON value, exactly like `Object.class`/`JsonNode.class`, both at the root and nested as a member;
there is no field walk such a refusal could be protecting there. The `io.vertx.*` package is excluded
from the settable-property check outright: a no-argument getter such as `Buffer#getBytes()` or a
mutable-collection getter such as `JsonArray#getList()` makes plain reflective introspection report a
property for these well-known wrapper types even though neither is ever bound as a bean.

**A `DelegatingDeserializer` wrapper is unwrapped before this decision, bounded to a pure forwarder
(W2 owner ruling; W-1, round 5 review finding, `spike/deserializer-driven-schema`).** A mapper-wide
`BeanDeserializerModifier` that wraps *every* bean deserializer in a forwarding `DelegatingDeserializer`
subclass is a legitimate, if unusual, module shape — but the generator does not simply trust that the
wrapper forwards; it checks. Unwrapping proceeds through `getDelegatee()` only while the wrapper's own
class overrides none of `deserialize(JsonParser, DeserializationContext)`,
`deserialize(JsonParser, DeserializationContext, Object)`, or `deserializeWithType(...)` itself — i.e.
`DelegatingDeserializer` remains the declaring class of all three, so every operation is guaranteed to
reach the original bean deserializer unchanged. For that bounded shape the generator unwraps the chain
down to its ultimate delegate, mirroring the existing `TypeWrappedDeserializer` unwrap, and describes
the wrapped bean from the delegate rather than refusing it as an opaque type-level override; the same
bound applies at all three seams the generator unwraps a `DelegatingDeserializer` at — the
root/nested-reference seat, the member-level case-insensitive inline path (S-1, round 5 review
finding), and the `@JsonUnwrapped` unwrapped-child loop (C-1, round 6 finding): an unwrapped child's
own root deserializer is unwrapped the same bounded way before the loop asks Jackson for its
unwrapping deserializer, so a mapper-wide forwarding wrapper no longer leaves the child's own bean
deserializer hidden behind it. A `DelegatingDeserializer` subclass that overrides any of the three
methods — accepting a wire form of its own before, or instead of, ever reaching the delegate — is *not*
unwrapped: it is treated as a replaced deserializer and refused exactly like the shape described above,
with the same diagnostic, since unwrapping straight to the delegate's plain bean description would
silently drop whatever the override actually does. This is deliberately narrower than the refusal it
exempts from: a third-party type with a settable property whose deserializer a profile module attaches
directly (`SimpleModule.addDeserializer(...)`, or a `BeanDeserializerModifier` that returns something
other than a pure-forwarding `DelegatingDeserializer` wrapping the original bean deserializer) is not a
`DelegatingDeserializer` chain to unwrap, and stays refused exactly as described above — the remedy for
that shape is still a `JsonSchemaTypeOverride`. **An `@JsonUnwrapped` child's own
`unwrappingDeserializer(...)` result decides its fate, matching Jackson's own bound (C-1, round 7
finding).** When that call returns the very same deserializer instance back, that is Jackson's own
documented signal that the deserializer does not support unwrapping at all — `MapDeserializer`, the
abstract-type-id-resolving deserializer, the untyped-`Object`/`JsonNode` deserializers,
`OptionalDeserializer`, and others all take this path — and `BeanDeserializerBase.resolve()` then
leaves the member bound as an ordinary nested property, which the describer's own first
(non-unwrapped) property loop already publishes under the member's own name; the unwrapped-child loop
leaves it there rather than refusing it. Only when the call returns a *different* instance that does
not resolve to a bean deserializer — a genuine custom deserializer changing the wire shape, not
Jackson declining to unwrap — is the member refused, with the same bounded custom-deserializer
diagnostic the root seam throws, naming the unwrapped member alongside its type so a parent with
several unwrapped members can tell which one was refused. This bound is deliberately narrower than the
root seam's own refusal shape immediately above: the root seam separately describes a map-like type,
returns nothing for a polymorphic base (left to the Jackson module's own subtype resolver), and
describes an opaque foreign type as unconstrained — none of which the unwrapped-child loop repeats,
since the parent's own first property loop already publishes any of those shapes left un-unwrapped as
an ordinary nested property, and repeating them here would duplicate rather than protect that path. A
prior round 6 fix (C-1) refused every such child outright regardless of which of the two instances
`unwrappingDeserializer(...)` returned, so generation failed with a diagnostic for every DTO with an
unwrapped `Map`, abstract `@JsonTypeInfo` base, `Object`, `JsonNode`, or `Optional` member; this is the
corrected bound.

**A case-insensitively bound `@JsonUnwrapped` child is refused, at every seam a wrapper can hide it
behind.** `requireCaseSensitive` runs immediately once an unwrapped child resolves to a bean
deserializer, so the C-1 unwrap fix above also restores this refusal under a mapper-wide forwarding
wrapper: before the fix, the wrapper left the child's root deserializer un-unwrapped, the loop's own
`instanceof BeanDeserializerBase` check failed first, and generation silently succeeded with the
child's property simply missing instead of refusing the genuinely case-insensitive binding.

**A delegating `@JsonCreator`** — object-delegating (`Mode.DELEGATING` over a single non-array-like
parameter) or array-delegating (the same mode over a `List`/array-shaped parameter) — is refused with
a bounded diagnostic, in either shape: the whole value is bound through the delegate type, so no named
property of the creator's own type is ever read from the wire, and a document describing the delegate
type's shape honestly would open the boundary to keys the binder never accepted through a named
property and leave any constraint on the creator's own type's fields dead on input. Declare a
`JsonSchemaTypeOverride` for the type on the profile, or bind it through a property-based creator.



### How an any-setter's extra keys are described

A type with a `@JsonAnySetter` describes its extra keys through `additionalProperties`, typed by the
any-setter's value type: the map value type of a field-level any-setter, or the second parameter of
a method-level one. A field's map value type is the content type Jackson resolves for the field, so a
map subclass declares it correctly however its own type parameters are written — a
`StringKeys<Integer>` over `LinkedHashMap<String, V>`, a `Reversed<Integer, String>` over
`LinkedHashMap<K, V>`, and a non-generic `IntMap extends LinkedHashMap<String, Integer>` all describe
integer extras — and a generic value type such as `List<Integer>` keeps its arguments. The value type is published as the generator's own definition of that type, so a
profile override, a format, and a shared definition apply to an extra value exactly as they do to a
named property — a `Map<String, LocalDate>` any-setter's extras carry `format: date`, and under
`vertique-strict` a `Map<String, BigDecimal>` any-setter's extras carry that profile's decimal
fragment.

An unconstrained value type — `Object`, `JsonNode`, `TreeNode`, or a wildcard or raw form resolving
to one — is described as the empty schema `{}`, which accepts every JSON value. A class-level
`@Schema(additionalProperties = FALSE)`, declared or inherited, keeps the object closed and is never
overridden. A class-level `@Schema(additionalProperties = TRUE)` says only that extras are allowed,
which the typed description already says more precisely, so the description wins. A type Jackson
deserializes as map-like or collection-like is described exactly as if it declared no any-setter:
Jackson never routes a key to that any-setter, so describing it would reject legal map entries.

### Reserved names beside described extras

Where extra keys are described, the document also carries

```json
"propertyNames": {"not": {"enum": ["id", "role"]}}
```

which lists one reserved set, computed as a difference rather than as a list of categories: every
name Jackson binds on input for the type, minus every name the document publishes under
`properties`, minus every name whose Jackson property definition carries no member at all. Without
it, a name the document never published would be accepted as an ordinary extra key and bound
straight into the member it names. Its members are therefore a consequence of the rule rather than
separate cases:

- a name marked `@JsonIgnore`, and a name a class-level `@JsonIgnoreProperties` ignores;
- a name whose access is read-only, or that is otherwise invisible on input;
- the storage field a method `@JsonAnyGetter` returns, which Jackson fills through that getter;
- a name bound only through a setter with no field, through an accessor pair over a differently
  named field, through a `@Schema(hidden = true)` field, or through a `transient` field;
- a `@JsonAlias` spelling more than one property claims, and a spelling of a property the document
  does not publish under its own wire name — neither is published, so neither is subtracted;
- a `@JsonUnwrapped` child's own alias spelling, and its own hidden, ignored, or bound-but-unpublished
  name, keyed by the child's wire name with any unwrapped prefix or suffix applied — folded in beside
  the parent's own names, scoped to when the parent actually has an any-setter (the only case where an
  unrecognized key binds anywhere at all). A nested `@JsonUnwrapped` chain under an any-setter type —
  where this one-level fold cannot itself be sound — is refused with a bounded diagnostic instead.

A field carrying both `@JsonAnyGetter` and `@JsonAnySetter` reserves no storage name, because
Jackson stores a key named after it as an ordinary entry of the map. The published-name subtraction
is by member and never by spelling, so a property the document publishes under some other name is
not reserved. The memberless subtraction fails open for the one shape whose member identity cannot
be recovered — a `@JsonCreator` parameter renamed away from the field it populates — which therefore
keeps accepting the traffic it already accepted; constrain that shape with Bean Validation. An
application-declared `propertyNames` is never displaced: the reserved set is combined with it under
`allOf`.

**Case-insensitive binding and Unicode code folding.** A type bound case-insensitively (mapper-wide,
class-level, or member-level `@JsonFormat`) is described with `patternProperties` — one ASCII
case-folding pattern per bound name (`name` folds to `^[nN][aA][mM][eE]\z`), since Jackson's own
case-insensitive lookup measurably uses `String#toLowerCase()`/`toUpperCase()` with no explicit
`Locale`, which a fold pinned to any one locale could silently drift from. The fold is anchored with
`\z`, not `$`: `io.vertx.json.schema` 5.1.6 compiles the `pattern` keyword with plain
`java.util.regex.Pattern`, whose `$` — without `Pattern.MULTILINE` — still matches immediately before
a single trailing line terminator, not only at the true end of input; a key ending in a newline would
otherwise wrongly match the fold. Every case-insensitively bound type additionally carries a
`propertyNames` rule refusing any key containing a non-ASCII code unit, **unconditionally** — where
extras are also described, folded together with the reserved-name pattern; where they are not, on its
own. This closes a real gap: a non-ASCII code point can fold to an ASCII letter under Java's
locale-independent Unicode case mapping regardless of locale (U+212A KELVIN SIGN folds to ASCII `k`),
so a key spelled with it binds at the *binder* to the same member an ASCII spelling would. Where
extras are described, the ASCII-only `patternProperties` fold and the reserved-name pattern both miss
it at the *schema*, so without this rule the key would fall through to `additionalProperties` and
validate as a permissive extra instead of against the real member's own constraint. A **closed** type
(no any-setter) at the REST gate has no other closure at all — REST has no hardener, and a closed type
publishes no `additionalProperties` — so without this rule the key would simply be accepted and bound;
the rule is emitted unconditionally for exactly this reason, not only where extras are described. A
closed type still relies on Bean Validation, when one is supplied, as its own backstop after binding.

**Member-level case-insensitive binding.** A member bound case-insensitively only through its own
contextual `@JsonFormat(with = ACCEPT_CASE_INSENSITIVE_PROPERTIES)` is described inline at that
position rather than by reference to the type's ordinary (case-sensitive) shared definition, since the
same class used elsewhere without the annotation stays case-sensitive there. Before describing it
inline, the same delegating/array-delegating refusal the type would face at any other position still
runs, and a profile override declared for the member's type still applies — resolved through the
ordinary reference lookup, which re-enters the full provider chain — in preference to the inline
description.

### How an alias spelling is described

Every `@JsonAlias` spelling of a visible input property that does not back an any-accessor is listed
under `properties` with a copy of the schema that property is published with, so the same
constraints apply under either spelling. Without it an alias reaches a gate undescribed: `{"qty":
999}` binds a property declared `@Max(10)`, and an unknown enum value under its alias binds the
default constant.

```json
"properties": {
  "quantity": {"maximum": 10, "type": "integer"},
  "qty":      {"maximum": 10, "type": "integer"}
}
```

A spelling is listed exactly where its property's own wire name is published, because listing copies
that entry: a spelling of a property the document never publishes — a `@Schema(hidden = true)`
field, an accessor pair with no same-named field, a property published under some other name — is
listed nowhere and stays a reserved name. A spelling that is already another property's name is not
listed either — whether or not the document publishes that property, because the type still binds
that name — and neither is a spelling more than one property of the type claims: the generator
cannot predict which claimant Jackson binds such a key to, so publishing it would attach one
claimant's schema to another claimant's value. A contested spelling is therefore published nowhere,
named in no rule, and reserved where extras are described; constrain that shape with Bean
Validation.

A required aliased property leaves the top-level `required` list, because one rule per aliased
property states which spellings may appear instead. Which rule depends on the selected profile's own
mapper, and on nothing else: a profile whose mapper enables
`JsonParser.Feature.STRICT_DUPLICATE_DETECTION` — of the built-ins, `vertique-strict` alone — gets
the strict form.

| Profile | Required property | Optional property |
| --- | --- | --- |
| Lenient (`system`, `vertique`) | at least one spelling: `anyOf` of `{"required": [spelling]}` | no rule |
| Strict (`vertique-strict`) | exactly one spelling: `oneOf` of the same branches | at most one: the same branches plus `{"not": {"anyOf": [...]}}` |

```json
"oneOf": [
  {"required": ["quantity"]},
  {"required": ["qty"]},
  {"not": {"anyOf": [{"required": ["quantity"]}, {"required": ["qty"]}]}}
]
```

The rules are appended to one `allOf`, or stand alone when there is one rule and its keyword is
free, and their branches carry only `required` or `not`, never `properties`, so a consumer that
closes an object carrying `properties` does not close a branch. The rule is the schema's alone: a
Jackson binder accepts several spellings of one property under every profile, so the strict form is
stricter than the binder rather than a description of it.

Listing runs over the finished document, after generation, so every reference a copied schema
carries is already resolved. The plan is carried in the document under one generator-private
keyword, `x-vertique-alias-plan`, which that pass removes; a type publishing a property under that
exact wire name — including one listing would publish under it, so an alias spelling equal to the
keyword is refused exactly as a property named for it is — is therefore refused at generation with a
bounded diagnostic naming the type and the name, rather than being published stripped of its
constraints. Rename such a property, or such a spelling, on the wire — for example with
`@JsonProperty` or `@JsonAlias`.

A profile override fragment that carries the keyword as a member of a schema object, at any depth,
is refused when the generator is constructed for a direction the fragment applies to: expansion would
otherwise strip it without a trace, or execute it as a plan against the enclosing schema. Literal
data is exempt. The listing pass and the refusal never enter the value of `const`, `enum`,
`default`, `examples`, or `example`, so a fragment `{"const": {"x-vertique-alias-plan": "mandatory",
"value": "ok"}}` is published exactly as written and still accepts only that object. A property whose
own name is one of those keywords is a schema like any other and is still expanded.

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

**An INPUT override for a bean-like class must declare `properties` or `additionalProperties`.**
This is the documented remedy for a generator refusal (the custom-deserializer, delegating-creator,
and case-insensitive refusals above), so nothing else validates that a declared fragment actually
describes or closes anything. A bare `{"type":"object"}` — the natural one-line remedy — publishes
as a non-root object with no `properties` and no `additionalProperties`, which the MCP hardener
deliberately leaves open (it only closes an object that already declares a non-empty `properties`),
and as an unconstrained body member at REST. Construction fails with a bounded diagnostic instead.
"Bean-like" is decided the same way the custom-deserializer refusal decides it (a settable property
by the mapper's own reflective introspection, with the same `java.`/`javax.`/`jakarta.`/
`com.fasterxml.jackson.`/`io.vertx.` package exclusions), and a fragment that fully constrains its
own wire shape some other way is exempt — it carries no open-object risk this rule exists to catch
even though it declares neither `properties` nor `additionalProperties`. Exempt shapes: a `type`
that wholesale replaces the object shape — a single non-`object` string, or an array not containing
`"object"`; an `enum`; a `const`; and a `oneOf`/`anyOf`/`allOf` whose every branch itself qualifies
by this same rule, recursively. A bare `{"type":"object"}` satisfies none of these and still fails
construction. OUTPUT-direction overrides are unaffected.

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

Three annotation combinations fail generation rather than producing a schema that quietly
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
- **A `@Schema(name = ...)` rename that publishes two members under one name.** In the
  profile-aware modes, a walked field that Jackson does not attach to any property of its own, and
  that is renamed onto a property backed by another field the schema library walks, would publish
  both fields under one name, the renamed one with the other's input or output visibility and wire
  name. Generation instead fails with a bounded `JsonSchemaGenerationException` naming the type, the
  renamed member, and the wire name of the property it collides with. Rename one of the two
  properties, or name them apart on the wire with `@JsonProperty`. A rename onto a property no other
  walked field backs is unaffected and keeps its schema: the Lombok-style `@Schema(name = "active")
  boolean isActive` behind `isActive()` and `setActive(...)`, or an `mName` field behind
  `getName()` and `setName(...)`, publishes `active` or `name` as before. So is a rename to the
  member's own property name, or to a name no property carries. Where a field name and its accessor
  property differ, `@JsonProperty("active")` on the field joins the two for Jackson as well.

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
| `jakarta.validation:jakarta.validation-api` | compile | Jakarta Validation constraint annotations Victools' Jakarta Validation module introspects; also the `Validator`/constraint-metadata types `forInputProfile(profile, Validator)`'s metadata constraint source reads. No `hibernate-validator` (or any other provider implementation) dependency — Hibernate's `@Length`/`@Range`/`@URL` are recognized by fully-qualified annotation class name, never by simple name alone, keeping this module provider-agnostic |
| `io.swagger.core.v3:swagger-annotations-jakarta` | compile | `@Schema` / `@ArraySchema` annotations Victools' Swagger 2 module introspects |
| `com.github.victools:jsonschema-generator` | compile | The Draft 2020-12 schema generation engine |
| `com.github.victools:jsonschema-module-jackson` | compile | Jackson property discovery module |
| `com.github.victools:jsonschema-module-jakarta-validation` | compile | Jakarta Validation constraint mapping module |
| `com.github.victools:jsonschema-module-swagger-2` | compile | Swagger 2 annotation mapping module |
