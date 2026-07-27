<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# JSON Module

> **Status:** Experimental
> **Package:** `dev.vertique.json`
> **Artifact:** `vertique-json`
> **Depends on:** vertique-core, vertx-core, jackson-databind, jackson-datatype-jsr310,
> jackson-datatype-jdk8, dagger

Runtime implementation of the Vertique JSON mapper profile system. **Contracts** (`JsonProfileId`,
`JsonMapperProfile`, `JsonMapperProfileRegistry`, `JsonProfileConfigurationException`, `@JsonProfile`)
live in `dev.vertique.core.json` (in `vertique-core`) so that boundary modules can reference them
without a runtime dependency on this module. The runtime — registry implementation, two built-in
profiles (`vertx` and `vertique`), opinionated defaults helper, opt-in serdes, Vert.x JSON support
helper, profile factory, and Dagger module — lives here.

---

## Overview

The JSON mapper profile system enables named, code-owned `ObjectMapper` configurations that
framework boundary modules (`rest-jaxrs`, `rest-client`, `kafka-json`) can select per resource
method, REST client, or Kafka binding. The zero-config default is the `vertx` profile, which
delegates to `DatabindCodec.mapper()` — the same shared mapper Vert.x uses internally —
preserving full backward compatibility for applications that do not opt into profiling.

The framework ships a second built-in profile, `vertique`, that delivers the broadly-safe
opinionated defaults defined by `JacksonDefaults` on an independent `ObjectMapper`. Both built-ins
are seeded directly in the registry, separate from the application-contributed set, and are
**probe-exempt** — neither is subjected to the structural round-trip probe.

Applications contribute profiles via Dagger `@IntoSet` multibinding; the registry validates
uniqueness and runs a structural round-trip probe for each application-contributed profile at
construction time (startup failure rather than silent data corruption at runtime). Neither the
`vertx` nor the `vertique` reserved id may be used by application profiles.

---

## Key Classes

| Class | Kind | Visibility | Description |
|---|---|---|---|
| `DefaultJsonMapperProfileRegistry` | `class` | Internal | `@Singleton` registry impl; seeds `vertx` and `vertique` built-ins separately (probe-exempt); validates uniqueness + round-trip probe for each application-contributed profile at `@Inject` construction |
| `VertxJsonMapperProfile` | `class` | Internal | Built-in `vertx` profile; delegates to `DatabindCodec.mapper()`; not overridable |
| `VertiqueJsonMapperProfile` | `class` | Internal | Built-in `vertique` profile; owns an independent `ObjectMapper` configured via `JacksonDefaults.apply(new ObjectMapper())`; not overridable; probe-exempt |
| `JacksonDefaults` | `class` (helper) | API | Applies the framework's broadly-safe opinionated defaults to any `ObjectMapper`; the `vertique` profile is exactly its output; see [The `vertique` Profile Defaults](#the-vertique-profile-defaults) |
| `JsonConfig` | record | API | Typed model of the `json` config section; carries `jsonProfile` (key `json.jsonProfile`), the global default profile id; `null`/blank means the `vertx` floor. Parsed at the `JsonRuntimeModule` boundary via `ConfigParser`. |
| `JsonDefaultProfileValidator` | `class` | API | `@Singleton ComposeValidator` contributed to the `VALIDATE`-phase multibinding; resolves `JsonConfig.jsonProfile()` through the registry at `@Inject` construction — an unknown id throws `JsonProfileConfigurationException` immediately, failing startup even when the global default is shadowed by a more-specific per-binding value or when no boundary has active bindings. |
| `BigDecimalAsStringSerializer` | `class` | API | Opt-in serializer: writes `BigDecimal` as a JSON string via `toPlainString()` (no scientific notation); not registered by `JacksonDefaults`; intended as a matched pair with `BigDecimalStrictStringDeserializer` |
| `BigDecimalStrictStringDeserializer` | `class` | API | Opt-in deserializer: accepts only `VALUE_STRING` holding a plain decimal (`-?[0-9]+(\.[0-9]+)?`, ≤ 100 chars) → `BigDecimal`; rejects JSON numbers, exponent forms, and any other token with `MismatchedInputException`; not registered by `JacksonDefaults` |
| `StrictStringDeserializer` | `class` | API | Opt-in deserializer: rejects scalar→`String` coercion; only `VALUE_STRING` accepted; other scalars (number, boolean) fail with `MismatchedInputException`; not registered by `JacksonDefaults` |
| `JsonMapperProfiles` | `class` (factory) | API | Factory: `of(JsonProfileId, ObjectMapper)` — validates non-null, does not mutate mapper |
| `VertxJsonSupport` | `class` (helper) | API | Re-exports Vert.x `VertxModule` so callers can register it without importing the class name |
| `JsonRuntimeModule` | Dagger `@Module` | API (wiring) | Declares `@Multibinds Set<JsonMapperProfile>`; binds `JsonMapperProfileRegistry` to `DefaultJsonMapperProfileRegistry`; provides `JsonConfig` (parsed from the `json` config section); contributes `JsonDefaultProfileValidator` `@IntoSet` |
| `KeyedCollectionModule` | `class` | API | Jackson `SimpleModule` (package `dev.vertique.json.keyed`) — registers the `BeanDeserializerModifier` that activates `KeyedCollectionDeserializer` for `@KeyedBy`-annotated fields; installed by `DefaultConfigMapper` in `vertique-config-core` |
| `KeyedCollectionDeserializer` | `class` | API | Contextual `JsonDeserializer<List<?>>` that reads a keyed JSON object and injects each entry's key into the `@KeyedBy`-named property of the element; reads `@KeyedBy` from `dev.vertique.core.json`; exposes `injectKey(String, JsonNode, String, Map)` as a `public static` helper used by `DefaultConfigParser` so both paths apply identical rules |

### The `vertique` Profile Defaults

The `vertique` profile is opt-in: select it explicitly with `@JsonProfile("vertique")` on a
resource class or method. The `vertx` profile remains the zero-config default and is never altered.

`JacksonDefaults.apply(mapper)` configures the mapper in this order:

| # | Default | Mechanism |
|---|---------|-----------|
| 1 | `java.time` → ISO-8601, original offset/zone preserved | Register `JavaTimeModule`; disable `WRITE_DATES_AS_TIMESTAMPS`; disable `ADJUST_DATES_TO_CONTEXT_TIME_ZONE`; re-register `VertxModule` **last** so Vert.x's `Instant` serializer remains authoritative (NFR-JSON-012) |
| 2 | JDK8 `Optional` / `OptionalInt` / `OptionalLong` / `OptionalDouble` → contained value (both directions) | Register `Jdk8Module` — **before** the `VertxModule` re-registration, so Vert.x's `Instant` serializer still wins. `java.util.stream` types are **serialize-only** (a `Stream` field writes as a JSON array; deserialization is deliberately not provided — streams are one-shot) |
| 3 | Empty optionals omitted from serialized bean/record properties | Per-type `configOverride(...).setInclude(NON_ABSENT)` on all four `Optional*` types (value-inclusion slot left at `USE_DEFAULTS`, so the mapper-wide `NON_NULL` still governs contained values). `NON_ABSENT` — not `NON_NULL` — is what reaches inside the reference type |
| 4 | Unknown enum string → `@JsonEnumDefaultValue` fallback constant | Enable `READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE`; enums without that annotation still reject unknown values |
| 5 | Decimal JSON numbers → `BigDecimal` | Enable `USE_BIG_DECIMAL_FOR_FLOATS` |
| 6 | Null-valued fields omitted on serialization | `setSerializationInclusion(NON_NULL)` |

`BigDecimal` serialization stays at Jackson's default (a JSON number), and `String` coercion is
left stock. The profile is therefore fully symmetric for those types: a `BigDecimal` round-trips as
a JSON number with no wire-shape change.

**Optional support and the narrowed omission contract.** Optional-typed record/creator properties
also round-trip symmetrically, but empty-optional *omission* applies only to bean/record
**properties**. Precisely:

- an empty optional held in a bean or record property is **omitted** from the output;
- a **root-level** empty optional serializes as JSON `null` (there is no property to omit);
- an empty optional **inside a collection or as a map value** serializes as a `null` element/value —
  element inclusion is not governed by the property-level override;
- a **missing** JSON property binds to `Optional.empty()` only through creator/record binding; on a
  mutable POJO an unset setter/field is left at its Java `null` default. An **explicit** JSON `null`
  binds to `Optional.empty()` on both shapes;
- an explicit `@JsonInclude` on a property **wins** over the config override — e.g.
  `@JsonInclude(ALWAYS)` makes an empty optional serialize as JSON `null`.

`java.util.stream` types are the one deliberately asymmetric case: serialize-only, with no
deserialization, because a stream is one-shot.

The `VertxModule` re-registration must always win over `JavaTimeModule`'s `Instant` serializer.
Because `MapperFeature.IGNORE_DUPLICATE_MODULE_REGISTRATIONS` is ON by default, `JacksonDefaults`
temporarily disables that flag for the single `VertxModule` re-registration, then restores it — so
the final Vert.x serializer stays authoritative regardless of whether the caller had already
registered `VertxModule` before calling `apply()`.

#### Opt-in Serdes (not in `vertique`)

Applications that need strict wire-shape control can register these building blocks on their own
profile mapper via a `SimpleModule`. None are registered by `JacksonDefaults` or `vertique`:

- **`BigDecimalAsStringSerializer` + `BigDecimalStrictStringDeserializer`** — a matched pair. The
  serializer writes `BigDecimal` as a quoted JSON string (`toPlainString()` — no scientific
  notation, trailing zeros preserved). The deserializer accepts only `VALUE_STRING`; JSON numbers
  and any other token cause `MismatchedInputException`. Its string grammar is bounded: only plain
  decimals matching `-?[0-9]+(\.[0-9]+)?` and at most 100 characters long are accepted, so exponent
  forms (`"1e5"`, `"1e-2000000000"`), a leading `+`, `".5"`, and `"1."` are all rejected. The bound
  is deliberate — Jackson's `StreamReadConstraints` number limits do not apply to string tokens, and
  a short exponent literal would otherwise select an enormous scale that `toPlainString()` expands
  into gigabytes on the way back out. It costs no round-trip fidelity, because the paired serializer
  never emits exponent notation. Use the pair when clients cannot safely represent large decimals as
  IEEE-754 floats. Note: activating this pair changes the wire shape — clients must expect a string,
  not a number.
- **`StrictStringDeserializer`** — rejects scalar-to-`String` coercion. Jackson's default silently
  coerces a JSON number `123` or `true` targeting a `String` field to `"123"` or `"true"`.
  `StrictStringDeserializer` disables that: only `VALUE_STRING` is accepted; any other scalar
  raises `MismatchedInputException`.

### Keyed-Collection Support (`dev.vertique.json.keyed`)

`vertique-json` hosts the Jackson mechanism that deserializes `@KeyedBy`-annotated `List<T>` fields from keyed JSON objects (`{key:{...}}`). The `@KeyedBy` annotation itself lives in `dev.vertique.core.json` (in `vertique-core`), which `vertique-json` depends on; this placement lets `vertique-config-core` depend only on `vertique-json` for the deserializer without pulling in an extra layer.

`KeyedCollectionModule` is a Jackson `SimpleModule`. When registered on an `ObjectMapper`, it installs a `BeanDeserializerModifier` that replaces the standard `List<T>` deserializer with a `KeyedCollectionDeserializer` for every bean property annotated with `@KeyedBy`. It is registered by `DefaultConfigMapper` in `vertique-config-core` as one of the four mandatory config modules.

`KeyedCollectionDeserializer` is a contextual `JsonDeserializer<List<?>>`. For each `(key, value)` entry in the keyed object it injects the key into the `@KeyedBy#value()` property of the element's JSON before deserializing to the element type. Conflict detection: if the element's JSON already declares that property with a different value, a `ConfigurationException` is raised; equal values are accepted.

The static helper `KeyedCollectionDeserializer.injectKey(String key, JsonNode node, String identityProp, Map<String,Object> fixedProps)` is **public** specifically so that `DefaultConfigParser.parseKeyedObject` (in `vertique-config-core`) reuses the same injection and conflict-detection logic — both paths enforce identical rules: non-blank keys, object-valued entries, and conflict detection.

### Global Default Profile (`JsonConfig`)

`JsonConfig` (record, `dev.vertique.json`) is the typed model of the `json` config section. Its single field, `jsonProfile` (key `json.jsonProfile`), is the **global default profile id** — the floor applied at every JSON boundary (JAX-RS, REST client, Kafka) when no more-specific per-binding or per-boundary default is configured. A `null` or blank value means the built-in `vertx` profile remains in effect, preserving zero-config backward compatibility.

**Effective precedence at each boundary (global tier is the second-to-last tier, above `vertx`):**

- **JAX-RS (request + response):** method `@JsonProfile` → class `@JsonProfile` → `jaxrs.jsonProfile` → **`json.jsonProfile`** → `vertx`
- **REST client:** explicit `objectMapper` → per-client config `jsonProfile` → builder `jsonProfile(...)` → `@JsonProfile` (interface, TYPE-level) → `restClient.defaults.jsonProfile` → **`json.jsonProfile`** → `vertx`
- **Kafka consumer:** `kafka.consumers.<n>.jsonProfile` (per-consumer config wins) → `@JsonProfile` (on the `@KafkaListener` type) / `KafkaConsumerBinding.jsonProfile(...)` → `kafka.jsonProfile` → **`json.jsonProfile`** → `vertx`
- **Kafka producer:** `kafka.producers.<n>.methods.<m>.jsonProfile` → `kafka.producers.<n>.jsonProfile` → `@JsonProfile` (on the `@KafkaProducer` type) → `kafka.jsonProfile` → **`json.jsonProfile`** → `vertx`

`json.jsonProfile` is resolved and validated once at the `JsonRuntimeModule` boundary via the injected `ConfigParser`. The value is pre-resolved into a `JsonConfig` instance available throughout the application via Dagger.

#### Fail-Fast Validation via `ComposeValidator`

`JsonDefaultProfileValidator` is a `@Singleton ComposeValidator` contributed to `JsonRuntimeModule`'s `@IntoSet Set<ComposeValidator>`. The `ComposeValidationStep` (VALIDATE phase) materializes all `ComposeValidator` instances unconditionally at startup, so a misconfigured `json.jsonProfile` fails fast — even when:
- the configured default is shadowed by a more-specific per-binding or per-boundary override on every active binding, or
- no boundary has any active bindings (inert boundary).

Each JSON boundary contributes its own parallel validator (`JaxRsDefaultProfileValidator`, `RestClientDefaultProfileValidator`, `KafkaDefaultProfileValidator`) for its own boundary-level default key, using the same `ComposeValidator` seam.

```json
{
  "json": {
    "jsonProfile": "vertique"
  }
}
```

Setting `json.jsonProfile = "vertique"` activates the opinionated `vertique` profile as the global default at every boundary. Per-binding and per-boundary overrides remain fully operational. An unknown id fails startup immediately with a `JsonProfileConfigurationException`.

### Contracts in `vertique-core`

The following types are **not in this module** — they live in `dev.vertique.core.json`:

| Type | Kind | Description |
|---|---|---|
| `JsonProfileId` | record | Trimmed, non-blank string value type; `VERTX` constant; `of(String)` factory |
| `JsonMapperProfile` | interface (SPI) | `id()` + `mapper()` |
| `JsonMapperProfileRegistry` | interface (API) | `mapper(JsonProfileId)`, `profile(JsonProfileId)`, `profileIds()` |
| `JsonProfileConfigurationException` | class | Thrown on unknown profile id or round-trip probe failure; extends `ConfigurationException` |
| `@JsonProfile` | annotation | `@Retention(RUNTIME) @Target({TYPE, METHOD})` — selects a profile by name on a resource class or method |

---

## Extension Points

Applications contribute profiles by declaring `@Provides @Singleton @IntoSet JsonMapperProfile`
bindings in a Dagger `@Module`, providing a mapper configured via `JsonMapperProfiles.of(id, mapper)`.

```java
@Module
abstract class PaymentsModule {

    @Provides
    @Singleton
    @IntoSet
    static JsonMapperProfile strictPaymentsProfile(JacksonConfigurer configurer) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(VertxJsonSupport.module()); // register Vert.x Jackson module
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        configurer.configure(mapper);                     // apply application-level customizations
        return JsonMapperProfiles.of(JsonProfileId.of("strict-payments"), mapper);
    }
}
```

Consumer modules (`RestModule`, `RestClientModule`, `KafkaJsonModule`) install `JsonRuntimeModule`
via `@Module(includes = JsonRuntimeModule.class)` — applications do not need to add it directly.

---

## Eager Validation at Startup

`DefaultJsonMapperProfileRegistry` performs all validation in its `@Inject` constructor (NFR-JSON-002A):

1. **Built-in seeding** — the `vertx` and `vertique` profiles are seeded directly, separate from
   the application set, and are **not** probed. `vertx` delegates to Vert.x's trusted shared
   mapper; `vertique` sets `NON_NULL` inclusion which would make the null-bearing probe payload
   falsely fail.
2. **Reserved id check** — applications may not supply a profile with id `"vertx"` or
   `"vertique"`; both are reserved and their violation raises `JsonProfileConfigurationException`
   immediately.
3. **Uniqueness** — duplicate application-supplied profile ids fail immediately.
4. **Round-trip probe** — each **application-contributed** mapper is probed with a
   `JsonObject`/`JsonArray` payload (nested object, nested array, string/number/boolean/null
   fields) to verify structural equality after a serialize→deserialize cycle. Failure raises
   `JsonProfileConfigurationException` at startup rather than silently producing corrupt data.

### Bootstrap seam

Because Dagger constructs `@Singleton` instances **lazily** — the first accessor call on the
component forces construction — the registry's validation does not run automatically at component
creation time. Construction (and therefore validation) is forced at the first call to the
component accessor that exposes the registry. The framework exploits this in two ways:

**For apps with a REST, REST-client, or Kafka boundary:** the consumer modules install
`JsonRuntimeModule` via `@Module(includes = JsonRuntimeModule.class)` and force the registry
themselves during startup (router build / consumer deployment). No application step is needed.

**For apps with none of those boundaries:** call the `jsonMapperProfileRegistry()` component
accessor alongside `jacksonConfigurer().configure()` in `MainVerticle.start()`. This mirrors the
existing Jackson-configurer step and forces the registry before the verticle serves traffic:

```java
@Override
public Future<Void> start() {
    AppComponent c = DaggerAppComponent.create();
    c.jacksonConfigurer().configure();      // configure the shared Vert.x mapper
    c.jsonMapperProfileRegistry();          // force eager validation of profiles
    // ... deploy HttpVerticle, etc.
}
```

If profiles fail validation (duplicate ids, a reserved id, or a failing round-trip probe),
the `JsonProfileConfigurationException` propagates out of `start()` and prevents the verticle
from becoming live. `EagerValidationTest` in `vertique-json` proves this contract end-to-end
through the Dagger graph.

---

## Dependencies

| Artifact | Scope | Purpose |
|----------|-------|---------|
| `dev.vertique:vertique-core` | compile | `JsonProfileId`, `JsonMapperProfile`, `JsonMapperProfileRegistry`, `JsonProfileConfigurationException`, `@JsonProfile`, `ConfigurationException`, `@KeyedBy` (read by `KeyedCollectionDeserializer`) |
| `io.vertx:vertx-core` | compile | `DatabindCodec.mapper()` (shared `ObjectMapper`), `VertxModule`, `JsonObject`, `JsonArray` |
| `com.fasterxml.jackson.core:jackson-databind` | compile | `ObjectMapper`, `com.fasterxml.jackson.databind.Module`, `BeanDeserializerModifier`, `ContextualDeserializer` |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | compile | `JavaTimeModule` — registered by `JacksonDefaults` to enable ISO-8601 `java.time` serialization in the `vertique` profile |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | compile | `Jdk8Module` — registered by `JacksonDefaults` to enable `Optional*` serialization/deserialization (and serialize-only `java.util.stream` support) in the `vertique` profile |
| `com.google.dagger:dagger` | compile | `@Module`, `@Multibinds`, `@Binds`, `@Singleton`, `@Inject` |
| `jakarta.inject:jakarta.inject-api` | compile | `@Singleton`, `@Inject` (JSR-330) |

---

## Related ADRs

- ADR-0125: JSON Mapper Profiles — records the SPI shape,
  `JsonProfileId` value type, registry contract + eager-validation convention, `vertx` reservation,
  the `vertique-core` / `vertique-json` split, and the application-001 config-mapper boundary.
- ADR-0126: REST Profile-Aware Request Body Binding —
  records the decision to make the rest-jaxrs binding layer (`DefaultBoundRequest`) own the request
  body's first parse with the selected profile mapper (vs a late decoder re-parse), the
  vertx-unchanged guarantee, and the profile-rejection → 400 path.
- ADR-0127: Kafka Profile Serde Threading — records the
  decision to thread the selected profile id through the existing Kafka `serdeConfig` bag and inject
  the registry into `JsonSerdeProvider`, the consumer precedence (config → binding/listener → `vertx`),
  the explicit-deserializer-still-wins rule, and the vertx-unchanged guarantee.
- ADR-0134: Injectable ConfigParser + Keyed-Collection Module Split —
  establishes `KeyedCollectionModule` and `KeyedCollectionDeserializer` as reusable Jackson
  infrastructure in `vertique-json` (`dev.vertique.json.keyed`), with `@KeyedBy` reading from
  `dev.vertique.core.json`; records the `injectKey` public-API decision that lets `vertique-config-core`
  reuse the key-injection logic without duplicating it.
- ADR-0135: Second Built-in `vertique` Profile + Probe Exemption + JacksonDefaults —
  records the decision to ship a second framework-owned built-in profile (`vertique`), seed it
  separately from application profiles and exempt it from the round-trip probe (compensated by a
  mandatory smoke test), and introduce `JacksonDefaults` as the single source of truth for the
  opinionated defaults. Explains why the opted-in serdes are not baked into `vertique`.
- ADR-0136: Global + per-boundary JSON default-profile config tiers —
  records the addition of the `json.jsonProfile` global default tier and the per-boundary defaults
  (`jaxrs.jsonProfile`, `restClient.defaults.jsonProfile`, `kafka.jsonProfile`); the inline
  `firstNonBlank` tail design (no shared resolver); the `ComposeValidator` seam for unconditional
  fail-fast validation; and why kafka-core remains format-agnostic (global tier applied in kafka-json).
- ADR-0137: Symmetric JAX-RS request + response JSON profiling —
  records the decision to extend profile-awareness to JAX-RS response bodies (success and
  `ProblemDetail`/error) via the existing `KEY_RESOLVED_BODY_MAPPER` stash; the fail-open contract
  for error-body serialization failures; and why the encoder gains no new constructor dependency.
