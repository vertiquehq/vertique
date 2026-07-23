<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST OpenAPI Plugin Module

> **Status:** Implemented
> **Package:** `dev.vertique.openapi`
> **Artifact:** `rest-openapi-plugin`

A thin build-time module that provides a custom Swagger ModelConverter for the swagger-maven-plugin. Its sole purpose is to unwrap `Future<T>` return types to `T` in the generated OpenAPI spec, so the spec reflects the actual response type rather than the Vert.x `Future` wrapper.

---

## Model Converters

### FutureModelConverter

```java
public class FutureModelConverter extends ModelResolverUtils implements ModelConverter {
    @Override
    public Schema resolve(AnnotatedType type, ModelConverterContext context,
                          Iterator<ModelConverter> chain) {
        // If the type is Future<T>, unwrap to T and continue resolution
        // Otherwise, delegate to the next converter in the chain
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
public class SseModelConverter extends ModelResolverUtils implements ModelConverter {
    @Override
    public Schema resolve(AnnotatedType type, ModelConverterContext context,
                          Iterator<ModelConverter> chain) {
        // If the type is ReadStream<SseEvent>, resolve to a string schema
        // (SSE responses are text/event-stream, not a structured JSON schema)
        // Otherwise, delegate to the next converter in the chain
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
- `modelConverterClasses`: Must include `dev.vertique.openapi.FutureModelConverter`
- `outputPath`: Set to `${project.build.directory}/classes` so the generated spec is on the runtime classpath
- `outputFormat`: `JSONANDYAML` generates both `openapi.json` and `openapi.yaml`

---

## Dependencies

This module has deliberately minimal dependencies:
- `swagger-core-jakarta` (for `ModelConverter` interface)
- `vertx-core` (for `Future` class reference)

It is not a runtime dependency of applications. It is only used as a `<dependency>` of the swagger-maven-plugin during the build.

---

## Version History

| Date | Change |
|------|--------|
| 2026-03 | Initial implementation — `FutureModelConverter` for build-time `Future<T>` unwrapping in OpenAPI spec generation |
| 2026-04-11 | `SseModelConverter` added — resolves `ReadStream<SseEvent>` return types to a `string` schema in the generated OpenAPI spec |

---

## Troubleshooting

**Generated spec shows `Future` instead of the response type:**
- Verify `modelConverterClasses` includes the fully qualified class name
- Verify the `rest-openapi-plugin` dependency is listed under the swagger-maven-plugin `<dependencies>` section (not the project dependencies)

**operationId mismatch:**
- Check the generated `target/classes/openapi.json` to see what operationIds were generated
- The operationId comes from `@Operation(operationId = "...")` or defaults to the method name
- At runtime, `JaxRsRouteRegistrar` derives routing directly from JAX-RS annotations; the generated `openapi.json` is documentation-only on the default `web-validation` path and is loaded only by the opt-in `openapi-contract` strategy
