// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import com.github.victools.jsonschema.module.swagger2.Swagger2Module;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.swagger.v3.oas.annotations.media.Schema;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;

/**
 * Runtime {@link OperationSchemaSource} that synthesizes JSON schemas from JAX-RS operation
 * annotations using the victools JSON-schema generator (DRAFT 2020-12).
 *
 * <p>Two synthesis paths feed the produced {@link OperationSchemas}:
 *
 * <ul>
 *   <li><strong>Body</strong> — the body type is handed to victools (Jackson + Jakarta-validation +
 *       Swagger-2 modules), producing a complete object schema (nested objects, required fields,
 *       constraints) which is bridged to vertx-json-schema JSON via {@link JsonObject}.
 *   <li><strong>Parameters</strong> — victools introspects types and fields, not loose method
 *       parameters, so each {@link ParamDescriptor} is mapped to a small {@link JsonObject} from its
 *       declared type, optional collection component type, and its constraint annotations
 *       ({@code @Size}, {@code @Pattern}, {@code @Min}/{@code @Max}, Swagger {@code @Schema}).
 * </ul>
 *
 * <p>The swagger-2 module emits a {@code "default": "##default"} sentinel for unset annotation
 * defaults; that sentinel is stripped recursively from every produced schema.
 *
 * <p><strong>No per-operation schema cache.</strong> This is a {@link Singleton} shared across every
 * route mount, but duplicate-operationId is enforced only <em>within</em> a single registration (by
 * {@code JaxRsRouteRegistrar.checkDuplicateOperationId}). The registrar calls {@link #schemasFor} once
 * per operation at registration, so an operationId-keyed cache would never dedup within a mount; across
 * mounts it would hand a second operation the first operation's schema — validating against the wrong
 * contract. Re-introducing a cache requires a content-aware or mount-aware key, not the operationId
 * alone. The underlying {@link SchemaGenerator} is built once and reused (it is thread-safe with a
 * fixed configuration).
 */
@Singleton
public class AnnotationSchemaSource implements OperationSchemaSource {

    /** Swagger-2 sentinel emitted for an unset {@code @Schema} default value. */
    private static final String DEFAULT_SENTINEL = "##default";

    private final SchemaGenerator generator;

    /**
     * Creates a schema source backed by the spike-proven victools configuration (DRAFT 2020-12,
     * Jackson + Jakarta-validation + Swagger-2 modules).
     */
    @Inject
    public AnnotationSchemaSource() {
        SchemaGeneratorConfigBuilder builder = new SchemaGeneratorConfigBuilder(
                        SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                .with(new JacksonModule())
                .with(new JakartaValidationModule(
                        JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED,
                        JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS))
                .with(new Swagger2Module());
        this.generator = new SchemaGenerator(builder.build());
    }

    @Override
    public OperationSchemas schemasFor(JaxRsOperationDescriptor op) {
        return synthesize(op);
    }

    /**
     * Synthesizes the body and parameter schemas for the operation. The registrar invokes this once
     * per operation at registration; there is deliberately no cache (see the class javadoc), so each
     * distinct operation — even one sharing an operationId with an operation on another mount — gets
     * its own correct schema.
     *
     * @param op the operation descriptor whose body and parameters are introspected
     * @return the freshly synthesized schemas
     */
    private OperationSchemas synthesize(JaxRsOperationDescriptor op) {
        OperationSchemas.Builder schemas = OperationSchemas.builder();

        op.body().ifPresent(body -> schemas.bodySchema(synthesizeBody(body)));

        for (ParamDescriptor param : op.parameters()) {
            schemas.parameterSchema(param.location(), param.name(), synthesizeParam(param));
        }

        return schemas.build();
    }

    // --- Body path (victools) ---

    /**
     * Generates the body schema via victools and strips the swagger-2 sentinel. The full generic type
     * is passed when present (e.g. {@code List<MyDto>}) so victools resolves the element type and
     * produces an array-of-{@code MyDto} schema rather than a raw-{@code List} schema; otherwise the
     * raw class is used.
     *
     * @param body the body descriptor whose type (generic when available) drives generation
     * @return the body schema as vertx-json-schema JSON
     */
    private JsonObject synthesizeBody(BodyDescriptor body) {
        JsonNode node = generateBodySchema(body.genericType() != null ? body.genericType() : body.type());
        JsonObject schema = new JsonObject(node.toString());
        stripDefaultSentinel(schema);
        return schema;
    }

    /**
     * Runs victools schema generation for the given body type. Package-protected and overridable so
     * tests can count generation invocations.
     *
     * @param type the body type to generate a schema for
     * @return the victools-generated schema node
     */
    protected JsonNode generateBodySchema(Type type) {
        return generator.generateSchema(type);
    }

    // --- Parameter path (constraint mapping) ---

    /**
     * Builds a JSON schema for a single parameter from its declared type, optional collection
     * component type, and constraint annotations.
     *
     * @param param the parameter descriptor
     * @return the parameter schema as vertx-json-schema JSON
     */
    private JsonObject synthesizeParam(ParamDescriptor param) {
        JsonObject schema;
        // The array-shape decision is keyed off componentType != null to agree with the binder:
        // DefaultBoundRequest binds ANY param with a non-null componentType as a JsonArray (covering
        // List/Set/SortedSet/NavigableSet/Collection AND Java arrays like Integer[]). Gating on
        // isCollection(type) instead would miss arrays — Collection.isAssignableFrom is false for an
        // array type — producing a scalar schema for a JsonArray-bound value and rejecting a valid
        // repeated request with a 400.
        boolean isArray = param.componentType() != null;
        if (isArray) {
            schema = new JsonObject().put("type", "array").put("items", scalarSchema(param.componentType()));
        } else {
            schema = scalarSchema(param.type());
        }
        applyConstraints(schema, isArray, param.annotations());
        stripDefaultSentinel(schema);
        return schema;
    }

    /**
     * Maps a scalar Java type to its JSON-schema {@code type} keyword.
     *
     * @param type the scalar (non-collection) type
     * @return a schema object with a single {@code type} entry
     */
    private static JsonObject scalarSchema(Class<?> type) {
        return new JsonObject().put("type", jsonType(type));
    }

    /**
     * Returns the JSON-schema {@code type} keyword for a scalar Java type.
     *
     * @param type the Java type
     * @return {@code "integer"}, {@code "number"}, {@code "boolean"}, or {@code "string"}
     */
    private static String jsonType(Class<?> type) {
        if (type == Integer.class || type == int.class || type == Long.class || type == long.class) {
            return "integer";
        }
        if (type == Float.class || type == float.class || type == Double.class || type == double.class) {
            return "number";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        }
        return "string";
    }

    /**
     * Applies the supported constraint annotations to a parameter schema as JSON-schema keywords.
     *
     * @param schema      the schema being populated
     * @param isArray     whether the schema is an array (selects {@code minItems}/{@code maxItems} vs
     *                    {@code minLength}/{@code maxLength})
     * @param annotations the parameter's declared annotations
     */
    private static void applyConstraints(JsonObject schema, boolean isArray, Collection<Annotation> annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof Size size) {
                if (isArray) {
                    schema.put("minItems", size.min());
                    if (size.max() != Integer.MAX_VALUE) {
                        schema.put("maxItems", size.max());
                    }
                } else {
                    schema.put("minLength", size.min());
                    if (size.max() != Integer.MAX_VALUE) {
                        schema.put("maxLength", size.max());
                    }
                }
            } else if (annotation instanceof Pattern pattern) {
                schema.put("pattern", pattern.regexp());
            } else if (annotation instanceof Min min) {
                schema.put("minimum", min.value());
            } else if (annotation instanceof Max max) {
                schema.put("maximum", max.value());
            } else if (annotation instanceof DecimalMin decimalMin) {
                schema.put("minimum", parseNumber(decimalMin.value()));
            } else if (annotation instanceof DecimalMax decimalMax) {
                schema.put("maximum", parseNumber(decimalMax.value()));
            } else if (annotation instanceof Schema swaggerSchema) {
                applySwaggerSchema(schema, swaggerSchema);
            }
        }
    }

    /**
     * Applies the keyword-bearing members of a Swagger {@code @Schema} annotation, ignoring the
     * swagger {@code ##default} sentinel and unset members.
     *
     * @param schema        the schema being populated
     * @param swaggerSchema the Swagger annotation
     */
    private static void applySwaggerSchema(JsonObject schema, Schema swaggerSchema) {
        if (!swaggerSchema.pattern().isEmpty()) {
            schema.put("pattern", swaggerSchema.pattern());
        }
        if (!swaggerSchema.minimum().isEmpty() && !DEFAULT_SENTINEL.equals(swaggerSchema.minimum())) {
            schema.put("minimum", parseNumber(swaggerSchema.minimum()));
        }
        if (!swaggerSchema.maximum().isEmpty() && !DEFAULT_SENTINEL.equals(swaggerSchema.maximum())) {
            schema.put("maximum", parseNumber(swaggerSchema.maximum()));
        }
        if (swaggerSchema.minLength() > 0) {
            schema.put("minLength", swaggerSchema.minLength());
        }
        if (swaggerSchema.maxLength() != Integer.MAX_VALUE) {
            schema.put("maxLength", swaggerSchema.maxLength());
        }
    }

    /**
     * Parses a numeric constraint string into a {@link Number}, preferring a long when integral.
     *
     * @param value the numeric string from the annotation
     * @return a {@link Long} for integral values, otherwise a {@link Double}
     */
    private static Number parseNumber(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException notLong) {
            return Double.parseDouble(value);
        }
    }

    // --- ##default sentinel strip ---

    /**
     * Recursively removes every {@code "default"} entry whose value is the swagger {@code ##default}
     * sentinel from the schema tree.
     *
     * @param value the current node (object, array, or scalar)
     */
    private static void stripDefaultSentinel(Object value) {
        if (value instanceof JsonObject obj) {
            if (DEFAULT_SENTINEL.equals(obj.getValue("default"))) {
                obj.remove("default");
            }
            // Snapshot field names: fieldNames() is a live view and we mutate the map during the walk.
            for (String field : List.copyOf(obj.fieldNames())) {
                stripDefaultSentinel(obj.getValue(field));
            }
        } else if (value instanceof JsonArray arr) {
            arr.forEach(AnnotationSchemaSource::stripDefaultSentinel);
        }
    }
}
