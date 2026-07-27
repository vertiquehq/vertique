<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST OpenAPI Plugin Module

> **Status:** Implemented
> **Package:** `dev.vertique.openapi`
> **Artifact:** `rest-openapi-plugin`

A thin build-time module that provides Swagger `ModelConverter`s and an `OpenAPIExtension` for the swagger-maven-plugin. `FutureModelConverter` unwraps `Future<T>` return types to `T`, `SseModelConverter` resolves SSE `ReadStream` return types to a string schema, `BigDecimalModelConverter` resolves `BigDecimal` types to the `vertique-strict` string wire-form schema, `ScalarOptionalModelConverter` resolves `OptionalInt`/`OptionalLong`/`OptionalDouble` to scalar schemas, and `RequestParamsExtension` expands `@RequestParams`-annotated parameter objects into individual OpenAPI parameters — so the generated spec reflects the actual JAX-RS contract rather than the framework's internal wrapper/aggregation types.

---

## Model Converters

Model converters are **not** auto-discovered — each application module's `pom.xml` must list the fully-qualified class name(s) under `swagger-maven-plugin`'s `<modelConverterClasses>` (see [Configuration](#configuration) below). Every converter must guard `chain.next()` with `chain.hasNext()`: a converter cannot know whether it is last in the configured chain, and an unconditional `chain.next()` throws `NoSuchElementException` when it is.

### FutureModelConverter

```java
public class FutureModelConverter implements ModelConverter {
    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context,
                          Iterator<ModelConverter> chain) {
        // If the type is Future<T>, unwrap to T and continue resolution
        // Otherwise, delegate to the next converter in the chain
        // Returns null (not a chain.next() call) when this is the last converter
    }
}
```

**What it does:**
- Intercepts Swagger model resolution during OpenAPI spec generation
- Detects `io.vertx.core.Future<T>` types
- Extracts the type parameter `T` and passes it to the next converter
- Result: `Future<GreetingResponse>` becomes `GreetingResponse` in the spec

**Why a separate module:**
The swagger-maven-plugin runs during the Maven `compile` phase and loads ModelConverters from its own classpath. Putting `FutureModelConverter` in the `rest` module would require the swagger-maven-plugin to depend on the entire `rest` module and all its transitive dependencies. This separate module has minimal dependencies (swagger-core-jakarta and vertx-core) to avoid classpath pollution.

### SseModelConverter

```java
public class SseModelConverter implements ModelConverter {
    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context,
                          Iterator<ModelConverter> chain) {
        // If the type is ReadStream<SseEvent>, resolve to a string schema
        // (SSE responses are text/event-stream, not a structured JSON schema)
        // Otherwise, delegate to the next converter in the chain
        // Returns null (not a chain.next() call) when this is the last converter
    }
}
```

**What it does:**
- Detects `io.vertx.core.streams.ReadStream<SseEvent>` return types
- Resolves them to a `string` schema in the OpenAPI spec (SSE is a plain-text streaming format)
- Prevents Swagger from trying to generate a JSON schema for the `ReadStream` wrapper

**Configuration** — add alongside `FutureModelConverter` in the swagger-maven-plugin config:

```xml
<modelConverterClasses>
    dev.vertique.openapi.FutureModelConverter,
    dev.vertique.openapi.SseModelConverter
</modelConverterClasses>
```

Both converters must be listed when SSE endpoints are present in the application.

### BigDecimalModelConverter

```java
public final class BigDecimalModelConverter implements ModelConverter {
    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context,
                          Iterator<ModelConverter> chain) {
        // If the type is java.math.BigDecimal, resolve to a string schema with
        // format "decimal", pattern "^-?[0-9]+(\.[0-9]+)?$", and maxLength 100
        // Otherwise, delegate to the next converter in the chain
        // Returns null (not a chain.next() call) when this is the last converter
    }
}
```

**What it does:**
- Detects `java.math.BigDecimal` return/field types
- Resolves them to a `string` schema (`format: decimal`) instead of Swagger's default `number` schema
- Sets `pattern: ^-?[0-9]+(\.[0-9]+)?$` and `maxLength: 100` so the generated spec's validation bounds describe the same plain-decimal grammar `dev.vertique.json.BigDecimalStrictStringDeserializer` (`vertique-json` module) enforces at runtime — the anchors (`^`/`$`) are explicit on the spec side because JSON Schema `pattern` matching has ECMA-262 *search* (unanchored) semantics, unlike the deserializer's `Matcher.matches()` call, which is implicitly anchored

**`vertique-strict` pairing contract:**
- This converter is **spec-global** — it applies to every `BigDecimal` occurrence in the document, producing one decimal wire-form policy per generated spec
- It is **required** when the application selects the `vertique-strict` JSON profile, so the spec's schema type (a decimal string) matches the actual runtime wire form
- Mixing string and number decimal wire forms within a single application's spec is **unsupported** by this converter — an application with a mixed-profile posture should not register it and instead annotate the string-form properties individually with `@Schema(type = "string", format = "decimal")`, which is the escape hatch for mixed-profile apps
- The `pattern` and `maxLength` values describe the same decimal grammar as `BigDecimalStrictStringDeserializer` (plain decimal literal, no exponent notation, ≤100 characters), but the spec-side pattern is anchored (`^...$`) while the deserializer's pattern is not — `Matcher.matches()` needs no anchors, an unanchored JSON Schema `pattern` would accept any string merely containing a digit run. Keep the *grammar* in lockstep if either changes; the anchor difference is deliberate and permanent. This is not a perfect substitute for the runtime check — a JVM-based ECMA/Java regex validator may still accept a value with a trailing newline (e.g. `"1.50\n"`) that the deserializer rejects with a 400, because Java's `$` matches before a trailing line terminator by default

**Configuration** — add alongside `FutureModelConverter` when the application uses the `vertique-strict` JSON profile:

```xml
<modelConverterClasses>
    dev.vertique.openapi.FutureModelConverter,
    dev.vertique.openapi.BigDecimalModelConverter
</modelConverterClasses>
```

### ScalarOptionalModelConverter

```java
public final class ScalarOptionalModelConverter implements ModelConverter {
    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context,
                          Iterator<ModelConverter> chain) {
        // OptionalInt    -> integer / int32
        // OptionalLong   -> integer / int64
        // OptionalDouble -> number  / double
        // Otherwise, delegate to the next converter in the chain
        // Returns null (not a chain.next() call) when this is the last converter
    }
}
```

**What it does:**
- Detects the JDK's three non-generic scalar optionals — `java.util.OptionalInt`, `java.util.OptionalLong`, `java.util.OptionalDouble`
- Resolves them to the scalar schema matching their real wire form instead of Swagger's default JavaBean schema (`{empty, present, asInt}`)
- Leaves generic `Optional<T>` untouched — swagger-core already unwraps it natively (see [Optional properties in generated specs](#optional-properties-in-generated-specs))

**Why it is needed:** swagger-core's Jackson-backed model resolver unwraps `java.util.Optional<T>` because Jackson registers it as a `ReferenceType`. The scalar optionals are not generic types and carry no such registration, so the resolver falls back to bean introspection and emits their accessor surface. At runtime, Jackson's `Jdk8Module` (registered by `dev.vertique.json.JacksonDefaults` in `vertique-json`) writes a present scalar optional as a plain JSON number and omits an empty one — so the bean schema is a pure spec/wire mismatch.

**Pairing contract:**
- Unlike `BigDecimalModelConverter`, this converter encodes **no profile-specific policy** — the scalar wire form it describes is what `Jdk8Module` produces under every profile built on `JacksonDefaults`, both `vertique` and `vertique-strict`
- Register it whenever any DTO in the scanned `resourcePackages` exposes an `OptionalInt`, `OptionalLong`, or `OptionalDouble` property

**Configuration** — add alongside `FutureModelConverter`:

```xml
<modelConverterClasses>
    <modelConverterClass>dev.vertique.openapi.FutureModelConverter</modelConverterClass>
    <modelConverterClass>dev.vertique.openapi.ScalarOptionalModelConverter</modelConverterClass>
</modelConverterClasses>
```

### Optional properties in generated specs

How an optional property surfaces in the generated spec depends on which optional type it uses:

| Java property type | Generated schema | Converter needed |
|---|---|---|
| `Optional<String>` | `string` | none — unwrapped natively |
| `Optional<List<String>>` | `array` of `string` | none — unwrapped natively |
| `Optional<BigDecimal>` | the `BigDecimal` schema for the app's profile | `BigDecimalModelConverter` only for the `vertique-strict` string form |
| `OptionalInt` | `integer` / `int32` | `ScalarOptionalModelConverter` |
| `OptionalLong` | `integer` / `int64` | `ScalarOptionalModelConverter` |
| `OptionalDouble` | `number` / `double` | `ScalarOptionalModelConverter` |

In every case the property is **neither `required` nor `nullable`**. That matches the runtime wire form: `JacksonDefaults` sets `NON_ABSENT` inclusion, so an empty optional is **omitted** from the payload rather than written as JSON `null`.

**Clients omit, they do not send `null`.** The generated schema is not nullable, so any consumer that validates against this spec — a generated client, an API gateway, a contract test's request-validation filter, or the opt-in `openapi-contract` strategy — rejects an explicit `"prop": null`. The default `web-validation` strategy does *not* read `openapi.json` (ADR-0121; `vertique-rest-validation` synthesizes its schemas from the Java types at runtime), so on that path an explicit `null` is accepted and bound to `Optional.empty()`. Omitting the property is therefore the portable form — accepted on every path, and binding identically.

---

## OpenAPI Extensions

Unlike model converters, `OpenAPIExtension`s are discovered automatically by swagger-core's `ServiceLoader` mechanism via `META-INF/services/io.swagger.v3.jaxrs2.ext.OpenAPIExtension`. Simply having this artifact on the swagger-maven-plugin classpath is enough — no `pom.xml` configuration entry is required, unlike `modelConverterClasses` above.

### RequestParamsExtension

```java
public class RequestParamsExtension extends AbstractOpenAPIExtension {
    @Override
    public ResolvedParameter extractParameters(List<Annotation> annotations, Type type,
            Set<Type> typesToSkip, Components components, Consumes classConsumes,
            Consumes methodConsumes, boolean includeRequestBody, JsonView jsonViewAnnotation,
            Iterator<OpenAPIExtension> chain) {
        // If the parameter type carries the framework's @RequestParams annotation, expand its
        // record components (or fields, for a regular POJO) into individual query/path/header/
        // cookie OpenAPI parameters. Otherwise, delegate to the next extension in the chain.
    }
}
```

**What it does:**
- Mirrors Swagger's built-in `@BeanParam` expansion for the framework's own `@RequestParams` class-level marker
- Expands each `@QueryParam`/`@PathParam`/`@HeaderParam`/`@CookieParam`-annotated record component (or field, for non-record POJOs) into its own OpenAPI `Parameter`
- Honors `@DefaultValue` (sets the schema default, marks the parameter optional) and `jakarta.annotation.@Nullable` (marks the schema `nullable` and the parameter optional)
- Registers complex member types in `components/schemas` and references them by `$ref`, rather than inlining

**Limitation:** `@FormParam` fields are not included in the generated spec because form parameters belong to a request body, not individual OpenAPI parameters. The runtime correctly binds `@FormParam` record components; only spec generation omits them.

**Registration** — declared via `ServiceLoader`, so no `pom.xml` change is required in consumer modules beyond including `rest-openapi-plugin` on the swagger-maven-plugin classpath (see [Configuration](#configuration)); the service file is packaged at `META-INF/services/io.swagger.v3.jaxrs2.ext.OpenAPIExtension`.

---

## Configuration

The plugin is configured in the application module's `pom.xml`:

```xml
<plugin>
    <groupId>io.swagger.core.v3</groupId>
    <artifactId>swagger-maven-plugin-jakarta</artifactId>
    <configuration>
        <outputFileName>openapi</outputFileName>
        <outputPath>${project.build.directory}/classes</outputPath>
        <outputFormat>JSONANDYAML</outputFormat>
        <resourcePackages>
            <package>dev.vertique.examples.hello.resource</package>
        </resourcePackages>
        <prettyPrint>true</prettyPrint>
        <modelConverterClasses>
            dev.vertique.openapi.FutureModelConverter
        </modelConverterClasses>
    </configuration>
    <dependencies>
        <dependency>
            <groupId>dev.vertique</groupId>
            <artifactId>vertique-rest-openapi-plugin</artifactId>
            <version>${project.version}</version>
        </dependency>
    </dependencies>
    <executions>
        <execution>
            <phase>compile</phase>
            <goals>
                <goal>resolve</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

**Key configuration points:**
- `resourcePackages`: The Java package(s) to scan for JAX-RS annotated classes
- `modelConverterClasses`: Must include `dev.vertique.openapi.FutureModelConverter` (and `SseModelConverter` when SSE endpoints are present, `BigDecimalModelConverter` when the application uses the `vertique-strict` JSON profile, and `ScalarOptionalModelConverter` when any DTO exposes an `OptionalInt`/`OptionalLong`/`OptionalDouble` property). With more than one entry, prefer the nested `<modelConverterClass>` element form
- `outputPath`: Set to `${project.build.directory}/classes` so the generated spec is on the runtime classpath
- `outputFormat`: `JSONANDYAML` generates both `openapi.json` and `openapi.yaml`
- `RequestParamsExtension` needs no entry here — it is picked up automatically via `ServiceLoader` once the `<dependency>` above is present

---

## Dependencies

This module has deliberately minimal dependencies:
- `swagger-core-jakarta` (for `ModelConverter`, `AnnotatedType`, `ModelConverterContext`)
- `swagger-jaxrs2-jakarta` (for `OpenAPIExtension`, `AbstractOpenAPIExtension`, `ResolvedParameter`)
- `jakarta.ws.rs-api` (for the `@QueryParam`/`@PathParam`/`@HeaderParam`/`@CookieParam`/`@DefaultValue`/`@Consumes` annotations inspected by `RequestParamsExtension`)
- `vertx-core` (for the `Future` class reference in `FutureModelConverter`)

It is not a runtime dependency of applications. It is only used as a `<dependency>` of the swagger-maven-plugin during the build.

---

## Related ADRs

- ADR-0121: `openapi.json` is documentation-only; binding is separate from validation — the spec this module's converters and extension shape is generated at build time for documentation/tooling only. The running framework builds routes directly from JAX-RS metadata and never loads `openapi.json`, so a defect in this module's output affects documentation accuracy, never runtime routing or validation.

---

## Troubleshooting

**Generated spec shows `Future` instead of the response type:**
- Verify `modelConverterClasses` includes the fully qualified class name
- Verify the `rest-openapi-plugin` dependency is listed under the swagger-maven-plugin `<dependencies>` section (not the project dependencies)

**operationId mismatch:**
- Check the generated `target/classes/openapi.json` to see what operationIds were generated
- The operationId comes from `@Operation(operationId = "...")` or defaults to the method name
- At runtime, `JaxRsRouteRegistrar` derives routing directly from JAX-RS annotations; the generated `openapi.json` is documentation-only on the default `web-validation` path and is loaded only by the opt-in `openapi-contract` strategy
