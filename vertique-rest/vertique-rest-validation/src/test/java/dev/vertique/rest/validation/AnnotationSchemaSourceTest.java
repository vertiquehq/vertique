// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.json.schema.JsonSchemaGenerationException;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.validation.corpus.CorpusFixture;
import dev.vertique.rest.validation.corpus.RootDecimalDto;
import dev.vertique.rest.validation.corpus.SchemaCorpus;
import dev.vertique.rest.validation.corpus.SchemaCorpusGenerator;
import io.swagger.v3.oas.annotations.media.Schema;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies {@link AnnotationSchemaSource}: body schema synthesis via the shared
 * {@code AnnotationJsonSchemaGenerator} (required fields, string constraints, nested objects,
 * canonical key ordering, bounded startup failure for an unrepresentable body type), parameter schema
 * synthesis from constraint annotations (list params, size/pattern), the swagger-2 {@code ##default}
 * sentinel strip, and that distinct operations sharing an operationId across mounts get distinct
 * schemas (no operationId-keyed cache collision).
 *
 * <p>It additionally carries this task's profiled-synthesis proof family: that the single
 * two-argument generation seam is invoked once per body under every profile (TP-002), that the
 * generated corpora are pinned (TP-003), that the {@code vertique-strict} override lands on the
 * right positions with the right bounds (TP-004), and that at most one generator is built per
 * profile instance, including under concurrent router builds (TP-009).
 *
 * <p><strong>The corpus proofs pin bytes; they do not discriminate.</strong> At this task's baseline
 * the pre-change documents and both built-in profiles' documents are byte-identical for all fifteen
 * corpus fixtures — verified by generating all three corpora and diffing them both ways. So
 * {@link #systemDocumentsMatchTheSystemCorpus} and {@link #vertiqueDocumentsMatchTheVertiqueCorpus}
 * are <em>pinning</em> proofs: they lock the bytes those two profiles produce so that a later
 * generator or option drift is caught, and they are expected to pass whether or not profiled
 * synthesis is wired correctly. Only the golden files' existence is new. The discriminating unit
 * proof for profiled synthesis is
 * {@link #strictOverrideAppliesToRootNestedAndElementButNotMapKey} and its two siblings, whose
 * strict positions change shape only when the effective profile actually reaches the generator.
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

    /**
     * An unresolved {@link java.lang.reflect.TypeVariable} ({@code E} of {@link List}) used as a body
     * type. It is outside the shared generator's accepted type grammar, so it exercises the startup
     * generation-failure path.
     */
    private static final Type UNRESOLVED_TYPE_VARIABLE = List.class.getTypeParameters()[0];

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

    /**
     * The reserved {@code vertique} floor profile the pre-existing proofs below pass as the seam's
     * effective profile. It declares no schema type override, so the documents those proofs assert on
     * are the same under profiled synthesis as before it.
     */
    private static JsonMapperProfile vertiqueProfile() {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique"));
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
        JsonObject body = source.schemasFor(bodyOp("createNotNull", NotNullDto.class), vertiqueProfile())
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
        OperationSchemas schemas = source.schemasFor(
                op("constrained", List.of(codeParam, categoryParam), Optional.empty()), vertiqueProfile());

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
        JsonObject body = source.schemasFor(bodyOp("createNested", WithNested.class), vertiqueProfile())
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
        JsonObject tagsSchema = source.schemasFor(
                        op("listParam", List.of(tagsParam), Optional.empty()), vertiqueProfile())
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
        JsonObject idsSchema = source.schemasFor(
                        op("arrayParam", List.of(idsParam), Optional.empty()), vertiqueProfile())
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
        JsonObject codesSchema = source.schemasFor(
                        op("setParam", List.of(codesParam), Optional.empty()), vertiqueProfile())
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
        JsonObject scoresSchema = source.schemasFor(
                        op("sortedSetParam", List.of(scoresParam), Optional.empty()), vertiqueProfile())
                .parameterSchema(ParamLocation.QUERY, "scores")
                .orElseThrow();

        assertEquals("array", scoresSchema.getString("type"));
        assertEquals("integer", scoresSchema.getJsonObject("items").getString("type"));
    }

    @Test
    @DisplayName("A List<ItemDto> body synthesizes an array schema whose items reflect ItemDto's fields")
    void annotationSchemaSourceSynthesizesGenericCollectionBody() {
        AnnotationSchemaSource source = new AnnotationSchemaSource();
        JsonObject body = source.schemasFor(
                        genericBodyOp("createItems", List.class, LIST_OF_ITEM_DTO), vertiqueProfile())
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
        JsonObject body = source.schemasFor(bodyOp("createItem", ItemDto.class), vertiqueProfile())
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
        JsonObject body = source.schemasFor(bodyOp("sentinel", SentinelDto.class), vertiqueProfile())
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
        OperationSchemas first =
                source.schemasFor(op("sharedId", List.of(codeParam), Optional.empty()), vertiqueProfile());
        OperationSchemas second =
                source.schemasFor(op("sharedId", List.of(tagsParam), Optional.empty()), vertiqueProfile());

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
        // Migrated to the two-argument seam; the profile is threaded straight through to super so the
        // per-call counting this proof has always asserted is unchanged.
        AnnotationSchemaSource source = new AnnotationSchemaSource() {
            @Override
            protected JsonNode generateBodySchema(Type type, JsonMapperProfile profile) {
                generateCalls.incrementAndGet();
                return super.generateBodySchema(type, profile);
            }
        };

        JaxRsOperationDescriptor d = bodyOp("perCall", NotNullDto.class);
        OperationSchemas first = source.schemasFor(d, vertiqueProfile());
        OperationSchemas second = source.schemasFor(d, vertiqueProfile());

        // The registrar calls schemasFor exactly once per operationId per mount, so there is no
        // within-mount dedup to preserve; each call synthesizes its own correct schema.
        assertTrue(first.bodySchema().isPresent());
        assertTrue(second.bodySchema().isPresent());
        assertEquals(2, generateCalls.get(), "each schemasFor call synthesizes the body schema for its own descriptor");
    }

    @Test
    @DisplayName("A body type outside the accepted grammar fails startup with RestConfigurationException")
    void startupGenerationFailureSurfacesAsRestConfigurationException() {
        // Body synthesis runs at route registration, so an unrepresentable body type is a startup
        // failure. It surfaces as this module's configuration exception naming the operation, with the
        // shared generator's single bounded exception type (PRD FR-JSON-076) preserved as a cause, so
        // the router build fails and the mount is never installed.
        AnnotationSchemaSource source = new AnnotationSchemaSource();
        JaxRsOperationDescriptor descriptor = genericBodyOp("unrepresentable", List.class, UNRESOLVED_TYPE_VARIABLE);

        RestConfigurationException failure =
                assertThrows(RestConfigurationException.class, () -> source.schemasFor(descriptor, vertiqueProfile()));

        String message = String.valueOf(failure.getMessage());
        assertTrue(
                message.contains("unrepresentable"),
                "the configuration exception must name the failing operation; message was: " + message);

        List<Throwable> chain = causeChainOf(failure);
        assertTrue(
                chain.stream().anyMatch(link -> link instanceof JsonSchemaGenerationException),
                "the generator's JsonSchemaGenerationException must be preserved as a cause; chain was: " + chain);

        // Only the text this module authors is bounded: the quoted detail is the generator's own
        // message, which the generator already bounds and which this module deliberately does not
        // sanitize. The authored prefix must carry the operation identity and nothing else - no schema
        // fragment JSON, no pattern text.
        String authored = restAuthoredPrefix(message);
        assertFalse(
                authored.contains("{") || authored.contains("}"),
                "the REST-authored text must not disclose fragment JSON; authored text was: " + authored);
        assertFalse(
                authored.contains("pattern"),
                "the REST-authored text must not disclose pattern text; authored text was: " + authored);
    }

    /**
     * Returns the portion of a synthesis-failure message this module authors: everything up to and
     * including the closing quote of the operation identity. Anything after the {@code ': '} separator
     * is the wrapped generator failure's own message.
     *
     * @param message the full failure message
     * @return the module-authored prefix
     */
    private static String restAuthoredPrefix(String message) {
        int detail = message.indexOf("': ");
        return detail < 0 ? message : message.substring(0, detail + 1);
    }

    /**
     * Collects a throwable and every throwable reachable through its cause chain, root first.
     *
     * @param root the failure to walk
     * @return every throwable in the cause chain
     */
    private static List<Throwable> causeChainOf(Throwable root) {
        List<Throwable> chain = new ArrayList<>();
        Throwable current = root;
        while (current != null && !chain.contains(current)) {
            chain.add(current);
            current = current.getCause();
        }
        return chain;
    }

    @Test
    @DisplayName("Body schema object keys are recursively ordered canonically")
    void bodySchemaKeysAreCanonicallyOrdered() {
        // The shared generator canonicalizes key order; ItemDto's document differs from insertion
        // order at both levels ($schema/type/properties/required at the root, sku before quantity
        // among the properties), so an insertion-ordered document fails this assertion.
        AnnotationSchemaSource source = new AnnotationSchemaSource();
        JsonObject body = source.schemasFor(bodyOp("canonicalOrder", ItemDto.class), vertiqueProfile())
                .bodySchema()
                .orElseThrow();

        assertCanonicallyOrdered(body, "$");
    }

    // --- Profiled proof helpers (TP-002, TP-003, TP-004, TP-009) ---

    /** The reserved built-in profile ids the profiled proofs drive, in one fixed order. */
    private static final List<String> BUILT_IN_PROFILE_IDS = List.of("system", "vertique", "vertique-strict");

    /** Number of repetitions of the concurrent router-build race (TP-009). */
    private static final int CONCURRENT_RUNS = 50;

    /** Number of concurrent router builds driven per repetition (TP-009). */
    private static final int CONCURRENT_BUILDERS = 2;

    /** Number of body operations each concurrent router build synthesizes (TP-009). */
    private static final int OPERATIONS_PER_BUILD = 10;

    /** Reads canonical JSON text (a golden document, a declared fragment) back into a node. */
    private static final ObjectMapper DOCUMENT_READER = new ObjectMapper();

    /**
     * A registry holding no application profiles. It hands out <strong>one stable instance per
     * built-in id</strong>, which is what makes the per-profile-instance generator count observable:
     * a second registry would hand out different instances and legitimately build more generators.
     */
    private static DefaultJsonMapperProfileRegistry builtInRegistry() {
        return new DefaultJsonMapperProfileRegistry(Set.of());
    }

    /** Resolves one built-in profile instance from a registry. */
    private static JsonMapperProfile profileOf(DefaultJsonMapperProfileRegistry registry, String id) {
        return registry.profile(JsonProfileId.of(id));
    }

    /** Resolves the three built-in profile instances from one registry, in {@link #BUILT_IN_PROFILE_IDS} order. */
    private static List<JsonMapperProfile> builtInProfiles(DefaultJsonMapperProfileRegistry registry) {
        return BUILT_IN_PROFILE_IDS.stream().map(id -> profileOf(registry, id)).toList();
    }

    /**
     * Builds a profile instance from an explicit id, mapper, and override list — the fresh-instance
     * case FR-011 bounds generator retention against.
     */
    private static JsonMapperProfile profileWithId(
            String id, ObjectMapper mapper, List<JsonSchemaTypeOverride> overrides) {
        return JsonMapperProfiles.of(JsonProfileId.of(id), mapper, overrides);
    }

    /**
     * Looks one subject up in the frozen corpus set. The set is never re-declared here: a proof that
     * built its own fixture would assert against a subject no golden document pins.
     */
    private static CorpusFixture fixture(String name) {
        return SchemaCorpus.byName(name)
                .orElseThrow(() -> new IllegalStateException("the frozen corpus declares no fixture named " + name));
    }

    /** Builds a body operation descriptor for one corpus fixture, carrying its generic type when it has one. */
    private static JaxRsOperationDescriptor corpusOp(CorpusFixture fixture) {
        return op(
                "corpus_" + fixture.name(),
                List.of(),
                Optional.of(new BodyDescriptor(fixture.rawType(), fixture.genericType(), List.of())));
    }

    /**
     * Captures the node the protected two-argument seam produced for the most recent body synthesis,
     * so a proof can assert on the seam's own document rather than on the vertx-json bridge's
     * re-rendering of it.
     */
    private static final class CapturingSource extends AnnotationSchemaSource {

        private JsonNode lastDocument;

        @Override
        protected JsonNode generateBodySchema(Type type, JsonMapperProfile profile) {
            lastDocument = super.generateBodySchema(type, profile);
            return lastDocument;
        }

        /** Synthesizes {@code fixture} under {@code profile} and returns the document the seam produced. */
        JsonNode documentFor(CorpusFixture fixture, JsonMapperProfile profile) {
            lastDocument = null;
            schemasFor(corpusOp(fixture), profile);
            assertNotNull(lastDocument, "the seam must have produced a document for " + fixture.name());
            return lastDocument;
        }
    }

    /** Counts invocations of the single two-argument generation seam. */
    private static final class CountingSource extends AnnotationSchemaSource {

        private final AtomicInteger generateCalls = new AtomicInteger();

        @Override
        protected JsonNode generateBodySchema(Type type, JsonMapperProfile profile) {
            generateCalls.incrementAndGet();
            return super.generateBodySchema(type, profile);
        }
    }

    /**
     * Reads a golden corpus document exactly as it sits on disk, from the test classpath. A missing
     * file fails naming the resource path, because the profiled corpora are generated only after this
     * proof exists.
     */
    private static String goldenDocument(String corpusDirectory, CorpusFixture fixture) {
        String resource = "/" + SchemaCorpusGenerator.CORPUS_ROOT + "/" + corpusDirectory + "/" + fixture.fileName();
        try (InputStream stream = AnnotationSchemaSourceTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "golden corpus document " + resource + " must exist");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /**
     * Asserts that {@code fixture}'s document under {@code profile}, taken from the seam and rendered
     * in the corpus's on-disk form, is byte-identical to its golden file, and that its object keys are
     * canonically ordered.
     */
    private static void assertCorpus(String profileDirectory, CorpusFixture fixture, JsonMapperProfile profile) {
        JsonNode document = new CapturingSource().documentFor(fixture, profile);

        assertEquals(
                goldenDocument(profileDirectory, fixture),
                SchemaCorpusGenerator.render(document),
                "the " + profileDirectory + " document for " + fixture.name()
                        + " must equal its golden file byte for byte");
        assertCanonicallyOrdered(new JsonObject(document.toString()), "$");
    }

    /**
     * Reads the {@code vertique-strict} profile's declared {@code BigDecimal} fragment through its
     * public override list. The bound and the grammar are package-private constants in
     * {@code dev.vertique.json} that {@code VertiqueStrictProfileOverrideTest} already pins; this
     * module reads them through the public path and never restates them as literals.
     */
    private static JsonNode strictBigDecimalFragment(JsonMapperProfile strict) {
        String canonical = strict.jsonSchemaTypeOverrides().get(0).fragment().canonicalJson();
        try {
            return DOCUMENT_READER.readTree(canonical);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException("the strict BigDecimal fragment is not readable JSON", unreadable);
        }
    }

    /**
     * Asserts that the node at {@code pointer} carries the strict fragment's own {@code type},
     * {@code format}, {@code maxLength}, and {@code pattern}. Every expected value comes from the
     * fragment, so a change to the deserializer's bound or grammar moves both sides of the assertion
     * together instead of breaking it.
     */
    private static void assertFragmentAt(JsonNode document, String pointer, JsonNode fragment, String position) {
        JsonNode node = document.at(pointer);
        assertFalse(node.isMissingNode(), "the " + position + " position " + pointer + " must exist in the document");
        for (String member : List.of("type", "format", "maxLength", "pattern")) {
            assertEquals(
                    fragment.get(member),
                    node.get(member),
                    "the " + position + " position " + pointer + " must carry the strict fragment's " + member);
        }
    }

    /** Reports whether any object in the tree carries a textual {@code pattern} member equal to {@code pattern}. */
    private static boolean containsPattern(JsonNode node, String pattern) {
        if (node.isObject()) {
            JsonNode own = node.get("pattern");
            if (own != null && own.isTextual() && pattern.equals(own.textValue())) {
                return true;
            }
        }
        for (JsonNode child : node) {
            if (containsPattern(child, pattern)) {
                return true;
            }
        }
        return false;
    }

    /** The frozen corpus set as parameterized-test arguments, each named by its corpus identity. */
    static Stream<Arguments> corpusFixtures() {
        return SchemaCorpus.FIXTURES.stream().map(fixture -> Arguments.of(Named.of(fixture.name(), fixture)));
    }

    /** Ten distinct body operations, so a cache keyed by operation id or Java type would be visible. */
    private static List<JaxRsOperationDescriptor> tenBodyOperations() {
        List<JaxRsOperationDescriptor> operations = new ArrayList<>(OPERATIONS_PER_BUILD);
        for (int index = 0; index < OPERATIONS_PER_BUILD; index++) {
            operations.add(bodyOp("cacheOp" + index, RootDecimalDto.class));
        }
        return operations;
    }

    /** Synthesizes the ten operations round-robin across {@code profiles} — one router build's worth of work. */
    private static void runTenOperations(AnnotationSchemaSource source, List<JsonMapperProfile> profiles) {
        List<JaxRsOperationDescriptor> operations = tenBodyOperations();
        for (int index = 0; index < operations.size(); index++) {
            source.schemasFor(operations.get(index), profiles.get(index % profiles.size()));
        }
    }

    // --- TP-002: the single seam is invoked once per body under every profile ---

    @Test
    @DisplayName("The two-argument seam is invoked exactly once per body synthesis, under every profile")
    void twoArgumentSeamIsInvokedOncePerBodyUnderEveryProfile() {
        DefaultJsonMapperProfileRegistry registry = builtInRegistry();
        CountingSource source = new CountingSource();

        source.schemasFor(bodyOp("seamSystem", RootDecimalDto.class), profileOf(registry, "system"));
        assertEquals(1, source.generateCalls.get(), "the system body must reach the seam exactly once");

        source.schemasFor(bodyOp("seamVertique", RootDecimalDto.class), profileOf(registry, "vertique"));
        assertEquals(2, source.generateCalls.get(), "the vertique body must reach the seam exactly once more");

        source.schemasFor(bodyOp("seamStrict", RootDecimalDto.class), profileOf(registry, "vertique-strict"));
        assertEquals(3, source.generateCalls.get(), "the vertique-strict body must reach the seam exactly once more");

        // A retained one-argument path would let a caller bypass the profiled seam entirely while the
        // counts above still read 1, 2, 3, so the seam's uniqueness is asserted structurally.
        assertThrows(
                NoSuchMethodException.class,
                () -> AnnotationSchemaSource.class.getDeclaredMethod("generateBodySchema", Type.class),
                "the one-argument generateBodySchema(Type) seam must no longer be declared");
    }

    // --- TP-003: the corpora are pinned (see the class javadoc on what these do and do not prove) ---

    @ParameterizedTest
    @MethodSource("corpusFixtures")
    @DisplayName("Each fixture's system document equals its pinned system corpus file")
    void systemDocumentsMatchTheSystemCorpus(CorpusFixture fixture) {
        assertCorpus("system", fixture, profileOf(builtInRegistry(), "system"));
    }

    @ParameterizedTest
    @MethodSource("corpusFixtures")
    @DisplayName("Each fixture's vertique document equals its pinned vertique corpus file")
    void vertiqueDocumentsMatchTheVertiqueCorpus(CorpusFixture fixture) {
        assertCorpus("vertique", fixture, profileOf(builtInRegistry(), "vertique"));
    }

    @ParameterizedTest
    @MethodSource("corpusFixtures")
    @DisplayName("The legacy corpus still equals a direct pre-change generator run")
    void legacyCorpusIsUnchangedFromTheT001Baseline(CorpusFixture fixture) {
        // The pre-change baseline every corpus delta is measured against. It is generated straight
        // from withVictoolsDefaults(), not through the schema source, so it stays comparable after the
        // source's own generation path becomes profiled. The trailing line feed is the corpus's
        // declared on-disk form.
        assertEquals(
                goldenDocument(SchemaCorpusGenerator.LEGACY_DIRECTORY, fixture),
                SchemaCorpusGenerator.document(fixture) + "\n",
                "the legacy golden document for " + fixture.name()
                        + " must still equal a direct withVictoolsDefaults() run");
    }

    // --- TP-004: override positions, fragment bounds, and multi-profile isolation ---

    @Test
    @DisplayName("The strict override applies at root, nested, and element positions but not at a map key")
    void strictOverrideAppliesToRootNestedAndElementButNotMapKey() {
        JsonMapperProfile strict = profileOf(builtInRegistry(), "vertique-strict");
        JsonNode fragment = strictBigDecimalFragment(strict);
        CapturingSource source = new CapturingSource();

        // No definitions block or $ref appears anywhere in this corpus — nested objects are inlined —
        // so these are direct property pointers rather than resolved references.
        assertFragmentAt(source.documentFor(fixture("RootDecimalDto"), strict), "/properties/amount", fragment, "root");
        assertFragmentAt(
                source.documentFor(fixture("NestedDecimalDto"), strict),
                "/properties/nested/properties/amount",
                fragment,
                "nested");
        assertFragmentAt(
                source.documentFor(fixture("DecimalListDto"), strict),
                "/properties/amounts/items",
                fragment,
                "element");

        // The map-key half of AC-003.1. A Map<BigDecimal, String> property emits no constraint member
        // at all — no propertyNames, under strict and non-strict alike — so the only assertion that can
        // be made is that the fragment is absent from the whole document. A pointer into a
        // propertyNames member would assert against a node that exists under no profile, and would
        // pass even if the override had leaked into the key position under a different keyword.
        JsonNode mapKeyDocument = source.documentFor(fixture("DecimalMapKeyDto"), strict);
        JsonNode mapProperty = mapKeyDocument.at("/properties/labels");
        assertEquals(
                "object",
                mapProperty.path("type").textValue(),
                "the map property must still be present as an object, so the negative below is not vacuous");
        assertFalse(
                containsPattern(mapKeyDocument, fragment.get("pattern").textValue()),
                "the strict fragment's pattern must appear nowhere in the map-key document");
    }

    @Test
    @DisplayName("The strict value position's bounds are read from the profile's declared fragment")
    void strictFragmentBoundsEqualTheDeserializerConstants() {
        JsonMapperProfile strict = profileOf(builtInRegistry(), "vertique-strict");
        JsonNode fragment = strictBigDecimalFragment(strict);

        JsonNode amount = new CapturingSource()
                .documentFor(fixture("RootDecimalDto"), strict)
                .at("/properties/amount");

        assertEquals(
                fragment.get("maxLength"),
                amount.get("maxLength"),
                "the synthesized maxLength must be the profile's declared bound, not a REST-side literal");
        assertEquals(
                fragment.get("pattern"),
                amount.get("pattern"),
                "the synthesized pattern must be the profile's declared grammar, not a REST-side literal");
    }

    @Test
    @DisplayName("Three profiles yield three independent documents for one body type")
    void threeProfilesYieldThreeIndependentDocuments() {
        DefaultJsonMapperProfileRegistry registry = builtInRegistry();
        AnnotationSchemaSource source = new AnnotationSchemaSource();
        JaxRsOperationDescriptor descriptor = corpusOp(fixture("RootDecimalDto"));

        JsonObject system = decimalProperty(source, descriptor, profileOf(registry, "system"));
        JsonObject vertique = decimalProperty(source, descriptor, profileOf(registry, "vertique"));
        JsonObject strict = decimalProperty(source, descriptor, profileOf(registry, "vertique-strict"));

        assertNotEquals(system, strict, "the strict document must differ from the system document at the BigDecimal");
        assertNotEquals(
                vertique, strict, "the strict document must differ from the vertique document at the BigDecimal");

        // AC-011.2: a shared cache across profiles would hand the second and third call the first
        // call's document; a per-operation cache would hand the second call the first call's instance.
        JsonObject firstBody = bodySchema(source, descriptor, profileOf(registry, "vertique-strict"));
        JsonObject secondBody = bodySchema(source, descriptor, profileOf(registry, "vertique-strict"));
        assertNotSame(firstBody, secondBody, "each call must return its own document instance");
        assertEquals(firstBody, secondBody, "two calls under the same profile must agree on content");
    }

    /** Synthesizes {@code descriptor} under {@code profile} and returns its body schema. */
    private static JsonObject bodySchema(
            AnnotationSchemaSource source, JaxRsOperationDescriptor descriptor, JsonMapperProfile profile) {
        return source.schemasFor(descriptor, profile).bodySchema().orElseThrow();
    }

    /** Returns the {@code amount} property schema of {@code descriptor}'s body document under {@code profile}. */
    private static JsonObject decimalProperty(
            AnnotationSchemaSource source, JaxRsOperationDescriptor descriptor, JsonMapperProfile profile) {
        return bodySchema(source, descriptor, profile)
                .getJsonObject("properties")
                .getJsonObject("amount");
    }

    // --- TP-009: one generator per profile instance ---

    @Test
    @DisplayName("Ten operations across three profiles build exactly three generators")
    void tenOperationsAcrossThreeProfilesBuildThreeGenerators() {
        DefaultJsonMapperProfileRegistry registry = builtInRegistry();
        AnnotationSchemaSource source = new AnnotationSchemaSource();

        runTenOperations(source, builtInProfiles(registry));

        assertEquals(
                3,
                source.generatorConstructionCount(),
                "exactly one generator may be constructed per distinct profile instance");
        assertEquals(3, source.cachedGeneratorCount(), "the cache must retain one entry per distinct profile instance");
    }

    @Test
    @DisplayName("A fresh profile instance carrying the same id builds its own generator")
    void freshProfileInstanceWithSameIdBuildsItsOwnGenerator() {
        DefaultJsonMapperProfileRegistry registry = builtInRegistry();
        JsonMapperProfile strict = profileOf(registry, "vertique-strict");
        AnnotationSchemaSource source = new AnnotationSchemaSource();
        runTenOperations(source, builtInProfiles(registry));

        // A cache keyed on the profile id alone would reuse the registry's strict generator and still
        // read 3 here. This case does NOT distinguish reference-identity keying from equals keying:
        // the registry's strict profile and this record are unequal under both, which is acceptable
        // because equal records share a mapper and an override list.
        JsonMapperProfile freshStrict =
                profileWithId("vertique-strict", strict.mapper(), strict.jsonSchemaTypeOverrides());
        OperationSchemas schemas = source.schemasFor(bodyOp("freshInstance", RootDecimalDto.class), freshStrict);

        assertTrue(schemas.bodySchema().isPresent(), "the fresh profile instance must synthesize successfully");
        assertEquals(
                4, source.generatorConstructionCount(), "a fresh profile instance must construct its own generator");
        assertEquals(4, source.cachedGeneratorCount(), "a fresh profile instance must occupy its own cache entry");
    }

    @Test
    @DisplayName("Concurrent router builds construct each profile's generator exactly once")
    void concurrentRouterBuildsConstructEachGeneratorOnce() throws Exception {
        DefaultJsonMapperProfileRegistry registry = builtInRegistry();
        List<JsonMapperProfile> profiles = builtInProfiles(registry);
        ExecutorService builders = Executors.newFixedThreadPool(CONCURRENT_BUILDERS);

        try {
            for (int run = 0; run < CONCURRENT_RUNS; run++) {
                AnnotationSchemaSource source = new AnnotationSchemaSource();
                CountDownLatch start = new CountDownLatch(1);

                List<Future<?>> builds = new ArrayList<>(CONCURRENT_BUILDERS);
                for (int builder = 0; builder < CONCURRENT_BUILDERS; builder++) {
                    builds.add(builders.submit(() -> {
                        start.await();
                        runTenOperations(source, profiles);
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> build : builds) {
                    build.get(60, TimeUnit.SECONDS);
                }

                // The CONSTRUCTION count, never the retained-entry count, carries this assertion: a
                // get-then-construct-then-putIfAbsent cache builds duplicate generators under the race
                // and keeps one of them, leaving exactly three entries behind. The run is repeated
                // because the losing interleaving is probabilistic, not certain.
                assertEquals(
                        3,
                        source.generatorConstructionCount(),
                        "run " + run + " must construct exactly three generators across two concurrent builds");
            }
        } finally {
            builders.shutdownNow();
        }
    }

    // --- Assertion helpers ---

    /** Asserts that every object in the tree rooted at {@code value} has its keys in sorted order. */
    private static void assertCanonicallyOrdered(Object value, String path) {
        if (value instanceof JsonObject obj) {
            List<String> actual = List.copyOf(obj.fieldNames());
            List<String> expected = actual.stream().sorted().toList();
            assertEquals(expected, actual, "object keys at " + path + " must be canonically ordered");
            actual.forEach(field -> assertCanonicallyOrdered(obj.getValue(field), path + "." + field));
        } else if (value instanceof JsonArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                assertCanonicallyOrdered(arr.getValue(i), path + "[" + i + "]");
            }
        }
    }

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
