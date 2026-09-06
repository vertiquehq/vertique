<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# JSON Module

> **Status:** Alpha
> **Package:** `dev.vertique.json`
> **Artifact:** `vertique-json`
> **Depends on:** core

Runtime of the Vertique JSON mapper profile system: named, code-owned `ObjectMapper` configurations
that a framework boundary selects per resource method, REST client, or Kafka binding. The zero-config
default is the reserved `system` profile — a **copy** of Vert.x's `DatabindCodec.mapper()` carrying the
framework's baseline recipe, so it behaves like the stock Vert.x mapper for everything that mapper
could already handle, while adding `Optional` and `java.time` support.

> **Renamed:** the reserved raw profile was called `vertx`; it is now `system`. The id `vertx` is
> retired — configuring it, or contributing an application profile under it, fails startup with a
> message naming the rename. Change `jsonProfile: vertx` to `jsonProfile: system`. This is not a pure
> rename in effect: see [the `system` recipe](#the-system-profile) for the one wire-shape delta.

The profile *contracts* (`JsonProfileId`, `JsonMapperProfile`, `JsonMapperProfileRegistry`,
`JsonProfileConfigurationException`, `@JsonProfile`, `@KeyedBy`) live in `dev.vertique.core.json`, in
`dev.vertique:vertique-core`, so a boundary module can reference them without depending on this
artifact. This module supplies the registry, the three built-in profiles, the opinionated-defaults
helper, the opt-in strict serdes, the keyed-collection Jackson support, the Jackson-backed
wire-name projection every Jackson-bound transport hands to input processing, and the Dagger wiring.

---

## When To Use It

Install `dev.vertique:vertique-json` when the application needs a JSON wire shape other than Vert.x's
stock mapper — ISO-8601 `java.time`, JDK8 `Optional` support, or a strict decimal/string posture — or
when it needs to contribute its own named mapper profile.

Most applications never add the dependency directly. The boundary modules
(`dev.vertique:vertique-rest-jaxrs`, `dev.vertique:vertique-rest-client`,
`dev.vertique:vertique-kafka-json`) install `JsonRuntimeModule` through their own Dagger modules,
`dev.vertique:vertique-config-core` depends on this artifact for keyed-collection config parsing, and
`dev.vertique:vertique-rest-websocket` depends on it for `JacksonFieldNameResolver` alone (it binds
messages through Vert.x's shared mapper and installs no profile registry).

---

## Core Concepts

### Profiles

A **profile** is an id paired with a fully configured `ObjectMapper`. The registry holds every
profile discovered at startup and resolves an id to its mapper in constant time. A profile mapper is
exposed exactly as supplied — never copied, wrapped, or mutated by the framework.

Three profiles are always registered:

| Id | Recipe | Wire posture |
|---|---|---|
| `system` | `JacksonDefaults.applySystem(DatabindCodec.mapper().copy())` | The baseline every other profile layers on. Byte-identical to the raw Vert.x mapper for every type that mapper already handled, **except `java.util.Date`/`Calendar`** (epoch millis → ISO-8601); `Optional` and `java.time` start working. Inherits Vert.x's `ALLOW_COMMENTS` and its `vertx.jackson.defaultRead*` stream-read limits. No inclusion, number, or enum opinion. The shared mapper itself is never exposed or mutated |
| `vertique` | `JacksonDefaults.applySystem(new ObjectMapper())` then `JacksonDefaults.applyOpinionated(mapper)` — i.e. `JacksonDefaults.apply(new ObjectMapper())` | The baseline recipe plus the opinions (see below); `BigDecimal` and `String` stay stock. Seeded from a **fresh** mapper, so unlike `system` it does **not** accept JSON comments |
| `vertique-strict` | `vertique` plus the strict overlay | `BigDecimal` on the wire as a bounded JSON string; no scalar→`String` coercion |

All three ids are **reserved**: an application profile that claims one fails startup. So does the
retired `vertx` id.

Each profile's `mapper()` returns **one stable instance** — the same reference on every call.

### Selecting a profile

A profile is selected by the `@JsonProfile` annotation, by a per-binding configuration value, or by a
default configuration key. Each boundary resolves the first non-blank tier and falls through to
`system`:

| Boundary | Precedence, highest first |
|---|---|
| JAX-RS request + response | method `@JsonProfile` → class `@JsonProfile` → `jaxrs.jsonProfile` → `json.jsonProfile` → `system` |
| REST client | explicit `objectMapper` → `restClient.<name>.jsonProfile` → builder `jsonProfile(...)` → interface `@JsonProfile` → `restClient.defaults.jsonProfile` → `json.jsonProfile` → `system` |
| Kafka consumer | `kafka.consumers.<n>.jsonProfile` → `@JsonProfile` on the `@KafkaListener` type / `KafkaConsumerBinding.jsonProfile(...)` → `kafka.jsonProfile` → `json.jsonProfile` → `system` |
| Kafka producer | `kafka.producers.<n>.methods.<m>.jsonProfile` → `kafka.producers.<n>.jsonProfile` → `@JsonProfile` on the `@KafkaProducer` type → `kafka.jsonProfile` → `json.jsonProfile` → `system` |

`json.jsonProfile` is the **global default tier** — the floor applied at every boundary when no
more-specific value is configured.

---

## Configuration

| Key | Type | Default | Description |
|---|---|---|---|
| `json.jsonProfile` | string | *(unset)* | Global default profile id for every JSON boundary. `null` or blank means the `system` profile. An id that names no registered profile — including the retired `vertx` — fails startup. |
| `json.systemProfile` | string | *(unset)* | Profile id for the process JSON codec role, read through `JsonConfig.effectiveSystemProfile()`. `null` or blank means the reserved `system` profile. |

```json
{
  "json": {
    "jsonProfile": "vertique",
    "systemProfile": "system"
  }
}
```

`JsonConfig` carries both keys verbatim; the floors live in its accessors, which never return `null`
and never validate: `effectiveProfile()` floors at `vertique` and `effectiveSystemProfile()` floors at
`system`. The one-argument constructor is a convenience for `(jsonProfile, null)`.

Setting `json.jsonProfile` activates that profile as the global default everywhere. Per-boundary and
per-binding overrides remain fully operational. Any registered id is valid here — the three built-ins
and every application-contributed id alike.

The value is validated unconditionally during the `VALIDATE` startup phase, so a typo fails fast even
when every active binding overrides the global default, and even when no boundary has any active
bindings at all. Each boundary validates its own default key (`jaxrs.jsonProfile`,
`restClient.defaults.jsonProfile`, `kafka.jsonProfile`) the same way.

---

## The `system` Profile

The reserved baseline, and the zero-config default. `JacksonDefaults.applySystem(mapper)` applies its
recipe; the `system` profile is exactly its output on `DatabindCodec.mapper().copy()`:

| # | Baseline behavior | Mechanism |
|---|---|---|
| 1 | `java.time` → ISO-8601, original offset/zone preserved | Register `JavaTimeModule`; disable `WRITE_DATES_AS_TIMESTAMPS`; disable `ADJUST_DATES_TO_CONTEXT_TIME_ZONE`; re-register Vert.x's Jackson module **last** so Vert.x's `Instant` serializer stays authoritative |
| 2 | JDK8 `Optional` / `OptionalInt` / `OptionalLong` / `OptionalDouble` → contained value, both directions | Register `Jdk8Module` **before** the Vert.x re-registration. `java.util.stream` types are **serialize-only** |
| 3 | Operator-tuned read limits survive | Copy the raw Vert.x factory's `StreamReadConstraints` (`vertx.jackson.defaultRead*`) onto the mapper's factory |

That is the whole recipe: no inclusion, number, or enum opinion is applied. Everything the raw Vert.x
mapper already handled — `JsonObject`, `JsonArray`, `Buffer`, `byte[]`, `Instant`, plain POJOs
including their `null`-valued properties — serializes byte-identically under `system`.

**The one delta from the raw Vert.x mapper.** `java.util.Date` and `java.util.Calendar` move from
epoch millis to ISO-8601:

| Value | raw Vert.x mapper | `system` (and `vertique`) |
|---|---|---|
| `new Date(0L)` | `0` | `"1970-01-01T00:00:00.000+00:00"` |

Disabling `WRITE_DATES_AS_TIMESTAMPS` is what makes `java.time` render ISO-8601, and it reaches the
legacy date types too. Date support is the point of the recipe, so the delta is accepted and pinned by
a compatibility-matrix test. A consumer that must keep reading epoch millis is unaffected on the read
side — Jackson still accepts a numeric timestamp.

**Comment leniency is inherited.** Because `system` is a `copy()` of Vert.x's mapper, it keeps
`JsonParser.Feature.ALLOW_COMMENTS`, exactly as the raw mapper did. `vertique` is seeded from a fresh
`ObjectMapper` instead, so it still rejects JSON comments at the body-binding boundary.

**`system` is a snapshot, not the live shared mapper.** The copy is taken when the profile registry is
first constructed. Anything registered on `DatabindCodec.mapper()` before that moment is inherited;
anything after it is not — the shared mapper and `system` never share state. The registry refuses to
seed `system` when the copied mapper carries Jackson default typing, so a classpath library that
activated it on the shared mapper fails the boot instead of feeding a polymorphic mapper into every
profile role.

**Where `system` binds today.** Through the registry (`registry.mapper(JsonProfileId.SYSTEM)`, the cache
stores, `@JsonProfile("system")` resolved by the registry) it is the recipe above. The REST request
body, rest-client, and Kafka JSON edges still short-circuit the reserved id to the raw Vert.x mapper
without consulting the registry; those edges move onto the registry's `system`/`vertique` mappers in
the follow-up slices that retune their defaults, and until then `Optional`/`java.time` do not work at
those edges under `system` and `java.util.Date` renders as epoch millis there.

## The `vertique` Profile

Opt in by selecting `vertique` at any tier above. `JacksonDefaults.apply(mapper)` is exactly
`applySystem(mapper)` followed by `applyOpinionated(mapper)`, and the `vertique` profile is exactly its
output on a fresh `ObjectMapper`. Rows 1–2 below come from the baseline half, rows 3–6 from the
opinionated half:

| # | Default | Mechanism |
|---|---------|-----------|
| 1 | `java.time` → ISO-8601, original offset/zone preserved | Register `JavaTimeModule`; disable `WRITE_DATES_AS_TIMESTAMPS`; disable `ADJUST_DATES_TO_CONTEXT_TIME_ZONE`; re-register Vert.x's Jackson module **last** so Vert.x's `Instant` serializer stays authoritative |
| 2 | JDK8 `Optional` / `OptionalInt` / `OptionalLong` / `OptionalDouble` → contained value, both directions | Register `Jdk8Module` **before** the Vert.x re-registration. `java.util.stream` types are **serialize-only** — a `Stream` field writes as a JSON array; deserialization is deliberately not provided, because a stream is one-shot |
| 3 | Empty optionals omitted from serialized bean/record properties | Per-type `configOverride(...).setInclude(NON_ABSENT)` on all four `Optional*` types, with the value-inclusion slot left at `USE_DEFAULTS` so the mapper-wide `NON_NULL` still governs contained values |
| 4 | Unknown enum string → `@JsonEnumDefaultValue` constant | Enable `READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE`; an enum without that annotation still rejects unknown values |
| 5 | Decimal JSON numbers → `BigDecimal` | Enable `USE_BIG_DECIMAL_FOR_FLOATS` |
| 6 | Null-valued fields omitted on serialization | `setSerializationInclusion(NON_NULL)` |

`BigDecimal` serialization stays at Jackson's default (a JSON number) and `String` coercion is left
stock, so both round-trip through this profile with no wire-shape change.

`applyOpinionated` alone is rows 3–6; layer it on `applySystem` to get `vertique`'s configuration on a
mapper you seeded yourself.

### Invariants & Gotchas

`apply` mutates and returns the mapper it is given; the six defaults above plus the
`StreamReadConstraints` copy are its only persistent changes. It is safe to call on a mapper that already has Vert.x's Jackson module registered — the
Vert.x serializers still end up authoritative.

Empty-optional **omission** is narrowed to bean and record *properties*:

- an empty optional held in a bean or record property is **omitted** from the output;
- a **root-level** empty optional serializes as JSON `null` — there is no property to omit;
- an empty optional **inside a collection or as a map value** serializes as a `null` element or
  value; element inclusion is not governed by the property-level override;
- a **missing** JSON property binds to `Optional.empty()` only through creator/record binding. On a
  mutable POJO an unset setter or field is left at its Java `null` default. An **explicit** JSON
  `null` binds to `Optional.empty()` on both shapes;
- an explicit `@JsonInclude` on a property **wins** over the override — `@JsonInclude(ALWAYS)` makes
  an empty optional serialize as JSON `null`.

---

## The `vertique-strict` Profile

`vertique-strict` is the `vertique` defaults plus a strict decimal and string wire posture. Select it
the same way — `@JsonProfile("vertique-strict")`, a per-binding value, or `json.jsonProfile`.

**Wire contract.**

| Target | Written as | Read from |
|---|---|---|
| Typed `BigDecimal` property | Quoted JSON string via `toPlainString()`, never scientific notation | JSON string only — a bare JSON number raises `MismatchedInputException` |
| `Map<BigDecimal, ?>` key | The same bounded plain form (`"0.0000001"`, not `"1E-7"`) | The same bounded grammar, so the profile always reads back its own output |
| `String` property | Unchanged | `VALUE_STRING` only — a JSON number or boolean fails instead of becoming `"42"` / `"true"` |
| Untyped decimal (`Object`, `Map<String, Object>`, `JsonObject`) | JSON number | JSON number, bound as `double` |

The accepted decimal grammar is bounded on **both** sides: plain decimals matching
`-?[0-9]+(\.[0-9]+)?`, at most **100 characters**. Exponent forms (`"1e5"`), a leading `+`, `".5"`,
and `"1."` are rejected on read; a value whose plain form would exceed the bound is rejected with a
`JsonMappingException` before serialization, however that value was constructed. Because the paired
serializer never emits exponent notation, the bound costs no round-trip fidelity.

Numerical equality always survives a round trip. **Scale** is preserved exactly for a non-negative
scale (`"1.50"` re-reads with scale 2). A **negative**-scale value — including a negative-scale zero
— is written in expanded plain form (`new BigDecimal("1E+2")` writes `"100"`) and re-reads with scale
0.

Clients of a `vertique-strict` endpoint must expect decimals as JSON **strings**, not numbers.

**Declared JSON Schema override.** `vertique-strict` declares exactly one entry from
`JsonMapperProfile#jsonSchemaTypeOverrides()`: a `BigDecimal` override applying to both the input and
output construction directions, whose fragment is

```json
{"format":"decimal","maxLength":100,"pattern":"^-?[0-9]+(\\.[0-9]+)?$","type":"string"}
```

The fragment is built from the same `BigDecimalStrictStringDeserializer` grammar constants the
deserializer itself enforces — the bound and the pattern are never duplicated as a second literal —
so a schema generator consuming this profile's overrides cannot drift from the wire grammar above
(FR-JSON-089). The `system` and `vertique` built-in profiles declare no overrides.

### Invariants & Gotchas

**Untyped decimals deliberately stay JSON numbers.** `USE_BIG_DECIMAL_FOR_FLOATS` is disabled on this
profile so documents the application merely relays keep their wire shape — number in, number out. Were
it enabled, an untyped decimal would bind to `BigDecimal` and be re-serialized as a string, silently
restringing pass-through payloads. The cost is a precision boundary: untyped decimals bind as
`double`, so a value beyond IEEE-754 double precision loses digits when relayed. Declare the property
as `BigDecimal` whenever arbitrary precision matters.

**Rejection messages never echo the submitted value.** Every bound violation this profile raises —
value or key, read or write — names only the bound and the offending length, scale, or precision. The
one boundary outside the profile's control is Jackson's own `WRAP_EXCEPTIONS` reference-chain
wrapping, which appends a rejected map key's `toString()` form to the mapping exception's message. In
this framework that message reaches logs only; the REST response body is replaced with a fixed
fallback.

**Request-body validation must agree with the wire shape.** The default `web-validation` strategy
synthesizes request schemas from the Java types and is profile-agnostic, so it types a `BigDecimal`
property as a JSON **number** and rejects the strict string form with a 400 before this profile's
deserializer runs. This applies only to **request bodies**: response serialization is unaffected, and
a `BigDecimal` bound as a query, path, header, or cookie **parameter** is already typed as a string.
Three ways to accept strict decimal strings in a body, in ascending order of blast radius:

| Option | Effect | Cost |
|---|---|---|
| `@Schema(implementation = String.class)` on the field | The runtime synthesizer types that one property as a string | The annotation misstates the Java type and carries no decimal grammar, so the deserializer remains the enforcing side. `@Schema(type = "string")` does **not** work — the runtime synthesizer reads `implementation`, not `type` |
| The `openapi-contract` strategy (`dev.vertique:vertique-rest-openapi-validation`) with `BigDecimalModelConverter` in the spec build | Validates against the generated spec, which carries the grammar and length bound | Application-wide change of validation strategy |
| Opt out of body validation | No schema check | No schema check |

The build-time spec generator in `dev.vertique:vertique-rest-openapi-plugin` is a different generator
with different annotation support. The runtime schema synthesizer takes no input from the active JSON
profile at all; `BigDecimal` is simply its most visible consequence.

---

## Key Classes

### JacksonDefaults

Configures any `ObjectMapper`, in two composable halves.

| Method | What it applies |
|---|---|
| `applySystem(mapper)` | The **baseline** recipe: `Jdk8Module`, `JavaTimeModule`, ISO-8601 dates, Vert.x's Jackson module re-registered last, and the raw Vert.x factory's `StreamReadConstraints`. No opinions. |
| `applyOpinionated(mapper)` | The **opinions**: `NON_NULL` inclusion, `NON_ABSENT` for the four `Optional*` types, `USE_BIG_DECIMAL_FOR_FLOATS`, `READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE`. Registers no module. |
| `apply(mapper)` | `applySystem` then `applyOpinionated` — the definition of the `vertique` profile. |

Each mutates and returns the mapper it is given.

**The sanctioned seed for an application profile is `JacksonDefaults.applySystem(new ObjectMapper())`**,
then whatever the application needs. It gives the profile the baseline type support and the operator's
stream-read limits without inheriting any framework opinion. Do not seed a profile from
`DatabindCodec.mapper()` directly — that bypasses the baseline.

```java
// Baseline + the application's own posture.
ObjectMapper mapper = JacksonDefaults.applySystem(new ObjectMapper());
mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

// Or: the vertique posture plus customizations.
ObjectMapper opinionated = JacksonDefaults.apply(new ObjectMapper());
```

### JsonMapperProfiles

Factory pairing an id with a mapper: `JsonMapperProfiles.of(JsonProfileId, ObjectMapper)`. Both
arguments must be non-null. The mapper is exposed as-is — the factory never copies or mutates it.

An overload additionally declares JSON Schema type overrides:
`JsonMapperProfiles.of(JsonProfileId, ObjectMapper, Collection<JsonSchemaTypeOverride>)`. All three
arguments must be non-null and the collection must not contain a `null` element; the collection is
defensively copied into an immutable list, so the caller's collection may be freely mutated after the
call returns, and the returned profile's `jsonSchemaTypeOverrides()` is unmodifiable and stable —
the same list instance on every call. The two-argument overload's behavior is unchanged: it declares
no overrides.

### VertxJsonSupport

`VertxJsonSupport.module()` returns a fresh Vert.x Jackson module. Register it on a custom profile
mapper so `JsonObject` and `JsonArray` round-trip with their values intact rather than binding to an
empty object. This helper exists so an application references a stable framework symbol instead of the
Vert.x class name directly.

### Opt-in serdes

Three public serdes are available for an application-owned profile mapper. **None** is registered by
`JacksonDefaults` or the `vertique` profile. All three are already active on `vertique-strict` — use
them standalone when only one is wanted, or when they must sit on a mapper carrying other
customizations.

| Class | Behavior |
|---|---|
| `BigDecimalAsStringSerializer` | Writes `BigDecimal` as a quoted JSON string via `toPlainString()`, bounded to 100 characters; rejects an over-length value with a value-free `JsonMappingException` |
| `BigDecimalStrictStringDeserializer` | Accepts only a JSON string matching `-?[0-9]+(\.[0-9]+)?` of at most 100 characters; every other token raises `MismatchedInputException` naming only the rejected length |
| `StrictStringDeserializer` | Rejects scalar→`String` coercion: only `VALUE_STRING` is accepted |

The two `BigDecimal` serdes are a **matched pair** — activating either alone produces an asymmetric
wire shape. The 100-character bound is deliberate: Jackson's `StreamReadConstraints` number limits do
not apply to string tokens, so a short exponent literal would otherwise select an enormous scale that
`toPlainString()` expands into gigabytes on the way out. Registering the pair changes the wire shape,
so clients must expect a string rather than a number.

```java
SimpleModule strict = new SimpleModule("payments-strict");
strict.addSerializer(BigDecimal.class, new BigDecimalAsStringSerializer());
strict.addDeserializer(BigDecimal.class, new BigDecimalStrictStringDeserializer());
strict.addDeserializer(String.class, new StrictStringDeserializer());
mapper.registerModule(strict);
```

### Keyed-collection support (`dev.vertique.json.keyed`)

`KeyedCollectionModule` is a Jackson `SimpleModule` that deserializes a `@KeyedBy`-annotated
`List<T>` field from a keyed JSON object (`{"orders": {…}, "refunds": {…}}`), injecting each entry key
into the named identity property of the element. The `@KeyedBy` annotation itself lives in
`dev.vertique.core.json`, in `dev.vertique:vertique-core`. Register the module on any mapper that must
bind keyed collections — `dev.vertique:vertique-config-core` does so automatically on the config
mapper, so typed config records need no application wiring:

```java
mapper.registerModule(new KeyedCollectionModule());
```

Entry keys must be non-blank and entry values must be JSON objects. If an element already declares the
identity property with a **different** value, a `ConfigurationException` is raised naming the entry key
and property — never the conflicting values; an equal value is accepted.

`KeyedCollectionDeserializer.injectKey(String key, JsonNode value, String identityProp,
Map<String, Object> fixedProps)` is public so a caller can apply the same injection and conflict rules
outside Jackson binding; it returns the prepared element node.

### JacksonFieldNameResolver

Projects the **wire** property names a body is keyed by onto the **Java** property names the
input-processing engine keys its per-field policies on. Implements
`dev.vertique.core.sanitization.InputFieldNameResolver`.

Input processing (`@Canonicalize` / `@Sanitize`) resolves each field's declared chain by the Java
property name, while an intermediate parsed from the wire is keyed by whatever Jackson published —
`@JsonProperty("user_name")`, a `SNAKE_CASE` naming strategy, `@JsonNaming`, a mix-in, a
`@JsonAlias`, or a name an `AnnotationIntrospector` a registered module installed produced. Without
the projection, a policy declared on a renamed field silently never runs. The resolver lives here
because the projection is a pure function of the `ObjectMapper` that binds the body, so REST and
WebSocket share one implementation rather than each deriving their own. Because the contract is
declared in `vertique-core`, implementing it adds no dependency on `vertique-input-processing` —
this module never sees the processing engine.

```java
// The mapper that materializes the body decides the projection.
JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(mapper);

// forRoute(null) means "no profile selected": the raw Vert.x mapper, DatabindCodec.mapper().
JacksonFieldNameResolver unprofiledRoute = JacksonFieldNameResolver.forRoute(null);

// Compose one owner type's projection at registration, never on the request path.
resolver.precompute(RenamedDto.class);

// In practice a transport does not call precompute itself. From its own module — which does depend
// on the engine — it hands the declared body or message type over, and the engine calls precompute
// once per owner type it may consult:
//     objectProcessor.precomputeFieldNameResolution(orderBodyType, resolver);

resolver.logicalName(RenamedDto.class, "user_name"); // -> "userName"
resolver.logicalName(RenamedDto.class, "unknown");   // -> "unknown" (the projection is total)
```

| Behavior | Contract |
|---|---|
| Source of names | `DeserializationConfig.introspect(JavaType)` — never an inference about how the mapper is configured |
| Primary vs alias | A primary name always claims its key; an alias fills only keys no primary claims — matching what Jackson itself binds |
| Colliding primary names | `ConfigurationException` naming the type, the wire name, and both Java properties |
| Two properties claiming one alias | `ConfigurationException` naming the type, the alias, and both Java properties. Jackson resolves such a collision in hash order while a projection built from `findProperties()` resolves it in declaration order, so the property whose policies are applied and the property Jackson binds could differ non-deterministically. An alias colliding with another property's *primary* name is **not** this case: the primary claims the key and the alias is silently unclaimed, exactly as Jackson binds it |
| Unknown wire name | Returned unchanged — the projection is total and never throws for an unrecognized key |
| Caching | One `ClassValue`-cached projection per `(mapper, type)`; entries are collected with the DTO's classloader |
| When the projection is composed | `precompute(Class)` composes it for **one** owner type at registration. It is the `InputFieldNameResolver` SPI hook the input-processing engine calls — once per owner type it may pass to `logicalName` while processing a declared body or message type — from `InputObjectProcessor.precomputeFieldNameResolution(Type, InputFieldNameResolver)`. So `logicalName` neither introspects nor raises anything on the request path, and a name collision fails startup instead of failing every request that touches the type |
| Which types get composed | Decided by the engine, not by this module: the resolver composes exactly the class it is handed and walks no type graph of its own. See the `vertique-input-processing` reference for the owner set `precomputeFieldNameResolution` prepares and the shapes it cannot reach statically |
| Identity short circuit | When the *computed* projection maps every wire name onto itself, `logicalName` returns the wire name directly and no per-field lookup happens |

Nested types are covered, but that coverage is the engine's postcondition rather than this module's:
the engine consults the projection of the owner of every nested fragment, and it is
`precomputeFieldNameResolution` that enumerates those owners and calls `precompute` for each. A type
reached only through a shape the declared types do not name — a `Map`- or `Object`-typed field's
runtime value, or a `@JsonTypeInfo` subtype — is still composed lazily on first use, because no
static walk can name it.

Instances are immutable and safe for concurrent use from several event-loop threads. A mapper
selected for a mounted boundary is treated as immutable afterwards: an application that mutates a
process-global mapper must rebuild the router.

Boundaries that process a bare `String` (a query, header, path, or form parameter) use
`InputFieldNameResolver.IDENTITY` instead — there is no object whose fields could be renamed.

---

## Extension Points

### JsonMapperProfile

Contribute a profile by declaring an `@IntoSet JsonMapperProfile` binding in a Dagger `@Module`.

```java
@Module
abstract class PaymentsModule {

    @Provides
    @Singleton
    @IntoSet
    static JsonMapperProfile strictPaymentsProfile() {
        // Sanctioned seed: the baseline recipe on a fresh mapper (this also registers Vert.x's
        // JSON type support, so JsonObject and Buffer serialize correctly).
        ObjectMapper mapper = JacksonDefaults.applySystem(new ObjectMapper());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        return JsonMapperProfiles.of(JsonProfileId.of("strict-payments"), mapper);
    }
}
```

The profile id must not be `system`, `vertique`, `vertique-strict`, or the retired `vertx`, and the
mapper must not have Jackson default typing active — the registry rejects both at construction.

Select it anywhere a profile id is accepted — `@JsonProfile("strict-payments")`, a per-binding config
value, or `json.jsonProfile`.

Consumer modules install `JsonRuntimeModule` via `@Module(includes = JsonRuntimeModule.class)`, so an
application using a REST, REST-client, or Kafka boundary does not add it to the component itself.

---

## Startup Validation

All profile validation happens eagerly, when the registry is first constructed. Every failure raises
`JsonProfileConfigurationException` and prevents the application from serving traffic.

| Check | Failure |
|---|---|
| Reserved id | An application profile claiming `system`, `vertique`, or `vertique-strict` is rejected. |
| Retired id | An application profile claiming `vertx` is rejected with a message naming the rename to `system`; so is a configured `vertx` id at any tier. |
| Default typing | An application profile whose mapper has `activateDefaultTyping(...)` active is rejected — every profile binds untrusted input. Annotation-driven `@JsonTypeInfo` stays allowed. |
| Uniqueness | Two application profiles sharing an id are rejected. |
| Round-trip probe | Each **application-contributed** mapper serializes and deserializes a representative `JsonObject`/`JsonArray` (nested object, nested array, string/number/boolean/null fields) and must preserve structure. The three built-ins are exempt. |
| Configured default | A non-blank `json.jsonProfile` (or a per-boundary default key) that names no registered profile is rejected during the `VALIDATE` phase. |

Resolving an unknown id at runtime throws `JsonProfileConfigurationException` with a message listing
every registered id.

### Forcing validation without a JSON boundary

The registry is a Dagger `@Singleton`, so it is constructed on first access. An application with a
REST, REST-client, or Kafka boundary gets that access during startup automatically. An application
with **none** of those boundaries must force it, alongside the existing Jackson-configurer step:

```java
@Override
public Future<Void> start() {
    AppComponent c = DaggerAppComponent.create();
    c.jacksonConfigurer().configure();      // configure the shared Vert.x mapper
    c.jsonMapperProfileRegistry();          // force eager validation of profiles
    // ... deploy HttpVerticle, etc.
}
```

A validation failure then propagates out of `start()` and the verticle never becomes live.

---

## Module Dagger Bindings

`JsonRuntimeModule` provides:

| Binding | Notes |
|---|---|
| `Set<JsonMapperProfile>` | `@Multibinds` seed — the application-contributed profile set, possibly empty. The built-ins are not members. |
| `JsonMapperProfileRegistry` | Bound to the validating default implementation. |
| `JsonConfig` | Parsed from the `json` config section through the injected `ConfigParser` — both `jsonProfile` and `systemProfile`. |
| `ComposeValidator` (`@IntoSet`) | The `json.jsonProfile` validator, forced during the `VALIDATE` phase. It is a pure delegate to `JsonMapperProfileRegistry.validateConfigured(...)`, which owns every message. |

---

## Dependencies

| Artifact | Scope | Purpose |
|----------|-------|---------|
| `dev.vertique:vertique-core` | compile | `JsonProfileId`, `JsonMapperProfile`, `JsonMapperProfileRegistry`, `JsonProfileConfigurationException`, `@JsonProfile`, `@KeyedBy`, `ConfigurationException`, `ConfigParser`, `ComposeValidator`, `InputFieldNameResolver` — the codec-neutral projection contract `JacksonFieldNameResolver` implements |
| `io.vertx:vertx-core` | compile | `DatabindCodec.mapper()`, the Vert.x Jackson module, `JsonObject`, `JsonArray` |
| `com.fasterxml.jackson.core:jackson-databind` | compile | `ObjectMapper`, `Module`, `BeanDeserializerModifier`, `ContextualDeserializer` |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | compile | `JavaTimeModule` — ISO-8601 `java.time` support in the `vertique` defaults |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | compile | `Jdk8Module` — `Optional*` support, and serialize-only `java.util.stream` support, in the `vertique` defaults |
| `com.google.dagger:dagger` | compile | `@Module`, `@Multibinds`, `@Binds`, `@Provides` |
| `jakarta.inject:jakarta.inject-api` | compile | `@Singleton`, `@Inject` |
