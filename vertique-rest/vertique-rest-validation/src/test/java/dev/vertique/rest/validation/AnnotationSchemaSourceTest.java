// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.swagger.v3.oas.annotations.media.Schema;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link AnnotationSchemaSource}: body schema synthesis via victools (required fields,
 * string constraints, nested objects), parameter schema synthesis from constraint annotations (list
 * params, size/pattern), the swagger-2 {@code ##default} sentinel strip, and that distinct operations
 * sharing an operationId across mounts get distinct schemas (no operationId-keyed cache collision).
 */
class AnnotationSchemaSourceTest {

    // --- Test fixtures ---

    /** Body DTO with a required (non-nullable) field. */
    static class NotNullDto {
        @NotNull
        public String name;
    }

    /** Body DTO exercising {@code @Size(min)} and Swagger {@code @Schema(pattern)}. */
    static class ConstrainedDto {
        @Size(min = 2)
        public String code;

        @Schema(pattern = "[A-Z]+")
        public String category;
    }

    /** Nested object whose own field is required. */
    static class Address {
        @NotBlank
        public String city;
    }

    /** Body DTO with a nested object field. */
    static class WithNested {
        public Address address;
    }

    /** Body DTO with a swagger {@code ##default} sentinel default value. */
    static class SentinelDto {
        @Schema(defaultValue = "##default")
        public String label;
    }

    /** Body DTO with a required field, used as the element type of a generic-collection body. */
    static class ItemDto {
        @NotNull
        public String sku;

        public int quantity;
    }

    /** {@link ParameterizedType} representing {@code List<ItemDto>} for a generic body. */
    private static final Type LIST_OF_ITEM_DTO = new ParameterizedType() {
        @Override
        public Type[] getActualTypeArguments() {
            return new Type[] {ItemDto.class};
        }

        @Override
        public Type getRawType() {
            return List.class;
        }

        @Override
        public Type getOwnerType() {
            return null;
        }
    };

    // --- Descriptor stub helpers ---

    private static JaxRsOperationDescriptor op(
            String operationId, List<ParamDescriptor> params, Optional<BodyDescriptor> body) {
        StubDescriptors.Builder builder = StubDescriptors.builder()
                .operationId(operationId)
                .httpMethod("POST")
                .routeTemplate("/things")
                .parameters(params);
        body.ifPresent(builder::body);
        return builder.build();
    }

    private static JaxRsOperationDescriptor bodyOp(String operationId, Class<?> bodyType) {
        return op(operationId, List.of(), Optional.of(new BodyDescriptor(bodyType, null, List.of())));
    }

    private static JaxRsOperationDescriptor genericBodyOp(String operationId, Class<?> rawType, Type genericType) {
        return op(operationId, List.of(), Optional.of(new BodyDescriptor(rawType, genericType, List.of())));
    }

    /** Reflectively reads the declared field's annotations for use in a {@link ParamDescriptor}. */
    private static List<Annotation> annotationsOf(Class<?> holder, String field) {
        try {
            return List.of(holder.getDeclaredField(field).getAnnotations());
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("Body schema marks a @NotNull field as required")
    void annotationSchemaSourceSynthesizesNotNullField() {
        AnnotationSchemaSource source = new AnnotationSchemaSource();
        JsonObject body = source.schemasFor(bodyOp("createNotNull", NotNullDto.class))
                .bodySchema()
                .orElseThrow();

        JsonArray required = body.getJsonArray("required");
        assertTrue(required != null && required.contains("name"), "required must contain 'name'");
    }

    @Test
    @DisplayName("Parameter schemas carry minLength (from @Size) and pattern (from @Schema)")
    void annotationSchemaSourceSynthesizesMinLengthAndPattern() {
        ParamDescriptor codeParam = new ParamDescriptor(
                "code",
                ParamLocation.QUERY,
                String.class,
                null,
                null,
                null,
                annotationsOf(ConstrainedDto.class, "code"));
        ParamDescriptor categoryParam = new ParamDescriptor(
                "category",
                ParamLocation.QUERY,
                String.class,
                null,
                null,
                null,
                annotationsOf(ConstrainedDto.class, "category"));

        AnnotationSchemaSource source = new AnnotationSchemaSource();
        OperationSchemas schemas =
                source.schemasFor(op("constrained", List.of(codeParam, categoryParam), Optional.empty()));

        JsonObject codeSchema =
                schemas.parameterSchema(ParamLocation.QUERY, "code").orElseThrow();
        JsonObject categorySchema =
                schemas.parameterSchema(ParamLocation.QUERY, "category").orElseThrow();

        assertEquals(2, codeSchema.getInteger("minLength"));
        assertEquals("[A-Z]+", categorySchema.getString("pattern"));
    }

    @Test
    @DisplayName("Body schema synthesizes a nested object with its own required fields")
    void annotationSchemaSourceSynthesizesNestedObject() {
        AnnotationSchemaSource source = new AnnotationSchemaSource();
        JsonObject body = source.schemasFor(bodyOp("createNested", WithNested.class))
                .bodySchema()
                .orElseThrow();

        // The nested Address schema lives either inline under properties.address or in a $defs entry
        // referenced by properties.address.$ref. Locate it and assert 'city' is required.
        JsonObject addressSchema = resolveNested(body, "address");
        assertTrue(addressSchema != null, "nested address schema must be present");
        JsonArray required = addressSchema.getJsonArray("required");
        assertTrue(required != null && required.contains("city"), "nested schema must require 'city'");
    }

    @Test
    @DisplayName("A List<String> query parameter yields an array-of-string schema")
    void annotationSchemaSourceSynthesizesListParam() {
        ParamDescriptor tagsParam =
                new ParamDescriptor("tags", ParamLocation.QUERY, List.class, String.class, null, null, List.of());

        AnnotationSchemaSource source = new AnnotationSchemaSource();
        JsonObject tagsSchema = source.schemasFor(op("listParam", List.of(tagsParam), Optional.empty()))
                .parameterSchema(ParamLocation.QUERY, "tags")
                .orElseThrow();

        assertEquals("array", tagsSchema.getString("type"));
        assertEquals("string", tagsSchema.getJsonObject("items").getString("type"));
    }

    @Test
    @DisplayName("An Integer[] query parameter yields an array-of-integer schema (matches the binder's componentType)")
    void annotationSchemaSourceSynthesizesArrayParam() {
        // DefaultBoundRequest binds any param with a non-null componentType as a JsonArray, including a
        // Java array (Integer[]). The param schema must therefore be an array schema, not a scalar one,
        // or a valid repeated request is wrongly rejected (400).
        ParamDescriptor idsParam =
                new ParamDescriptor("ids", ParamLocation.QUERY, Integer[].class, Integer.class, null, null, List.of());

        AnnotationSchemaSource source = new AnnotationSchemaSource();
        JsonObject idsSchema = source.schemasFor(op("arrayParam", List.of(idsParam), Optional.empty()))
                .parameterSchema(ParamLocation.QUERY, "ids")
                .orElseThrow();

        assertEquals("array", idsSchema.getString("type"), "an Integer[] param must be an array schema");
        assertEquals(
                "integer", idsSchema.getJsonObject("items").getString("type"), "items must reflect the component type");
    }

    @Test
    @DisplayName("A Set<String> query parameter yields an array-of-string schema")
    void annotationSchemaSourceSynthesizesSetParam() {
        ParamDescriptor codesParam = new ParamDescriptor(
                "codes", ParamLocation.QUERY, java.util.Set.class, String.class, null, null, List.of());

        AnnotationSchemaSource source = new AnnotationSchemaSource();
        JsonObject codesSchema = source.schemasFor(op("setParam", List.of(codesParam), Optional.empty()))
                .parameterSchema(ParamLocation.QUERY, "codes")
                .orElseThrow();

        assertEquals("array", codesSchema.getString("type"));
        assertEquals("string", codesSchema.getJsonObject("items").getString("type"));
    }

    @Test
    @DisplayName("A SortedSet<Integer> query parameter yields an array-of-integer schema")
    void annotationSchemaSourceSynthesizesSortedSetParam() {
        ParamDescriptor scoresParam = new ParamDescriptor(
                "scores", ParamLocation.QUERY, java.util.SortedSet.class, Integer.class, null, null, List.of());

        AnnotationSchemaSource source = new AnnotationSchemaSource();
        JsonObject scoresSchema = source.schemasFor(op("sortedSetParam", List.of(scoresParam), Optional.empty()))
                .parameterSchema(ParamLocation.QUERY, "scores")
                .orElseThrow();

        assertEquals("array", scoresSchema.getString("type"));
        assertEquals("integer", scoresSchema.getJsonObject("items").getString("type"));
    }

    @Test
    @DisplayName("A List<ItemDto> body synthesizes an array schema whose items reflect ItemDto's fields")
    void annotationSchemaSourceSynthesizesGenericCollectionBody() {
        AnnotationSchemaSource source = new AnnotationSchemaSource();
        JsonObject body = source.schemasFor(genericBodyOp("createItems", List.class, LIST_OF_ITEM_DTO))
                .bodySchema()
                .orElseThrow();

        assertEquals("array", body.getString("type"), "a List<ItemDto> body must be an array schema");

        JsonObject items = resolveItems(body);
        assertTrue(items != null, "the array schema must carry an items schema");
        assertEquals("object", items.getString("type"), "the item schema must be the ItemDto object schema");
        JsonObject properties = items.getJsonObject("properties");
        assertTrue(
                properties != null && properties.containsKey("sku") && properties.containsKey("quantity"),
                "the item schema must reflect ItemDto's fields (sku, quantity), not a raw List");
        JsonArray required = items.getJsonArray("required");
        assertTrue(required != null && required.contains("sku"), "the item schema must mark @NotNull 'sku' required");
    }

    @Test
    @DisplayName("A non-generic ItemDto body synthesizes an object schema (contrast with the array path)")
    void annotationSchemaSourceSynthesizesNonGenericObjectBody() {
        AnnotationSchemaSource source = new AnnotationSchemaSource();
        JsonObject body = source.schemasFor(bodyOp("createItem", ItemDto.class))
                .bodySchema()
                .orElseThrow();

        assertEquals("object", body.getString("type"), "a non-generic ItemDto body must be an object schema");
        JsonObject properties = body.getJsonObject("properties");
        assertTrue(
                properties != null && properties.containsKey("sku"),
                "the object schema must reflect ItemDto's fields directly");
    }

    @Test
    @DisplayName("The swagger-2 ##default sentinel is stripped from the produced schema")
    void annotationSchemaSourceStripsHashDefaultSentinel() {
        AnnotationSchemaSource source = new AnnotationSchemaSource();
        JsonObject body = source.schemasFor(bodyOp("sentinel", SentinelDto.class))
                .bodySchema()
                .orElseThrow();

        assertFalse(containsDefaultSentinel(body), "no 'default' entry may equal the '##default' sentinel");
    }

    @Test
    @DisplayName("Two operations sharing an operationId but with different param shapes get distinct schemas")
    void annotationSchemaSourceDistinguishesSameOperationIdDifferentShapes() {
        // A single @Singleton AnnotationSchemaSource is shared across all mounts, but duplicate
        // operationIds are rejected only WITHIN a mount (JaxRsRouteRegistrar.checkDuplicateOperationId).
        // So two different mounts may legitimately reuse the same operationId for operations with
        // different parameters/body. A cache keyed by operationId alone would hand the second mount the
        // FIRST mount's schema — validating against the wrong contract. Distinct descriptors must yield
        // distinct schemas regardless of a shared operationId.
        ParamDescriptor codeParam = new ParamDescriptor(
                "code",
                ParamLocation.QUERY,
                String.class,
                null,
                null,
                null,
                annotationsOf(ConstrainedDto.class, "code"));
        ParamDescriptor tagsParam =
                new ParamDescriptor("tags", ParamLocation.QUERY, List.class, String.class, null, null, List.of());

        AnnotationSchemaSource source = new AnnotationSchemaSource();
        OperationSchemas first = source.schemasFor(op("sharedId", List.of(codeParam), Optional.empty()));
        OperationSchemas second = source.schemasFor(op("sharedId", List.of(tagsParam), Optional.empty()));

        // First mount has a 'code' string param; second mount has a 'tags' array param. If the cache
        // returned the first schema for the second call, the 'tags' lookup would be empty and 'code'
        // would be present in the second mount's schema — the wrong contract.
        assertTrue(
                first.parameterSchema(ParamLocation.QUERY, "code").isPresent(),
                "first operation's schema must carry its own 'code' param");
        assertTrue(
                second.parameterSchema(ParamLocation.QUERY, "tags").isPresent(),
                "second operation's schema must carry its own 'tags' param, not the first operation's 'code'");
        assertFalse(
                second.parameterSchema(ParamLocation.QUERY, "code").isPresent(),
                "second operation's schema must NOT carry the first operation's 'code' param (cross-mount cache collision)");

        JsonObject tagsSchema =
                second.parameterSchema(ParamLocation.QUERY, "tags").orElseThrow();
        assertEquals("array", tagsSchema.getString("type"), "the second operation's 'tags' schema must be an array");
    }

    @Test
    @DisplayName("schemasFor synthesizes body schema afresh per call — victools generation runs once per call")
    void annotationSchemaSourceSynthesizesPerCall() {
        AtomicInteger generateCalls = new AtomicInteger();
        AnnotationSchemaSource source = new AnnotationSchemaSource() {
            @Override
            protected JsonNode generateBodySchema(Type type) {
                generateCalls.incrementAndGet();
                return super.generateBodySchema(type);
            }
        };

        JaxRsOperationDescriptor d = bodyOp("perCall", NotNullDto.class);
        OperationSchemas first = source.schemasFor(d);
        OperationSchemas second = source.schemasFor(d);

        // The registrar calls schemasFor exactly once per operationId per mount, so there is no
        // within-mount dedup to preserve; each call synthesizes its own correct schema.
        assertTrue(first.bodySchema().isPresent());
        assertTrue(second.bodySchema().isPresent());
        assertEquals(2, generateCalls.get(), "each schemasFor call synthesizes the body schema for its own descriptor");
    }

    // --- Assertion helpers ---

    /** Resolves the array {@code items} schema of {@code root}, following a local {@code $ref} if present. */
    private static JsonObject resolveItems(JsonObject root) {
        JsonObject items = root.getJsonObject("items");
        if (items == null) {
            return null;
        }
        String ref = items.getString("$ref");
        if (ref == null) {
            return items;
        }
        String[] segments = ref.replaceFirst("^#/", "").split("/");
        JsonObject node = root;
        for (String segment : segments) {
            node = node.getJsonObject(segment);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    /** Locates the nested object schema for {@code property}, resolving a local {@code $ref} if present. */
    private static JsonObject resolveNested(JsonObject root, String property) {
        JsonObject properties = root.getJsonObject("properties");
        if (properties == null) {
            return null;
        }
        JsonObject propSchema = properties.getJsonObject(property);
        if (propSchema == null) {
            return null;
        }
        String ref = propSchema.getString("$ref");
        if (ref == null) {
            return propSchema;
        }
        // Resolve a local ref like "#/$defs/Address" or "#/definitions/Address".
        String[] segments = ref.replaceFirst("^#/", "").split("/");
        JsonObject node = root;
        for (String segment : segments) {
            node = node.getJsonObject(segment);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    /** Recursively checks whether any {@code default} entry equals the swagger {@code ##default} sentinel. */
    private static boolean containsDefaultSentinel(Object value) {
        if (value instanceof JsonObject obj) {
            if ("##default".equals(obj.getValue("default"))) {
                return true;
            }
            return obj.fieldNames().stream().anyMatch(k -> containsDefaultSentinel(obj.getValue(k)));
        }
        if (value instanceof JsonArray arr) {
            return arr.stream().anyMatch(AnnotationSchemaSourceTest::containsDefaultSentinel);
        }
        return false;
    }
}
