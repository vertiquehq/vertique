// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.schema.AnnotationJsonSchemaGenerator;
import dev.vertique.json.schema.CanonicalSchema;
import dev.vertique.json.schema.RedactionManifest;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.validation.corpus.CorpusFixture;
import dev.vertique.rest.validation.corpus.SchemaCorpus;
import io.swagger.v3.oas.annotations.media.Schema;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Proves that {@link AnnotationSchemaSource} attaches the {@code vertique-json-schema} generator's
 * {@link RedactionManifest} to every body schema it produces (C-CARRIER, FR-014, AC-014.3), that the
 * attached manifest still matches the body after every {@link JsonObject} round trip and the
 * {@code ##default} sentinel strip, and that a source which replaces or edits the body — including a
 * decorator using {@link OperationSchemas#toBuilder()} or the one-argument builder form — carries no
 * provenance, or carries a manifest that no longer matches.
 *
 * <p>TP-002 to TP-004 of
 * {@code docs/specs/rest-022-application-scoped-openapi/tasks/T003-manifest-carrier.md}.
 */
class AnnotationSchemaSourceManifestTest {

    // --- Shared fixtures (TP-002 to TP-004) ---

    /**
     * Case-sensitively bound, with a published member and a hidden member that is therefore reserved,
     * and extras described through a method-level any-setter over an unannotated private map. Reserved
     * set: {@code {secret}}.
     */
    static final class GuardedBody {

        public String name;

        @Schema(hidden = true)
        public String secret;

        private final Map<String, Object> extras = new LinkedHashMap<>();

        @JsonAnySetter
        private void putExtra(String key, Object value) {
            extras.put(key, value);
        }
    }

    /** Case-sensitively bound and closed: no any-setter, so nothing is reserved. */
    static final class PlainBody {

        public String name;
    }

    /**
     * Carries decimal, exponent, and long-extreme numeric constraints, so the generated body's
     * canonical document holds number forms a naive round trip could mis-render.
     */
    static final class NumberBody {

        @DecimalMin("0.10")
        public double minDecimal;

        @DecimalMax("1E+3")
        public double maxDecimal;

        @DecimalMin("1E-7")
        public double tinyDecimal;

        @Min(Long.MIN_VALUE)
        public long minLong;

        @Max(Long.MAX_VALUE)
        public long maxLong;

        @DecimalMax("99999999999999999999")
        public double hugeDecimal;
    }

    /**
     * Overrides the INTERNAL generation seam to re-insert the swagger-2 {@code ##default} sentinel
     * into one property schema of {@code super}'s document, keeping {@code super}'s manifest — proves
     * the manifest still matches after {@link AnnotationSchemaSource}'s sentinel strip runs on top of
     * this source's own document.
     */
    static final class SentinelReinsertingSource extends AnnotationSchemaSource {

        @Override
        protected CanonicalSchema generateBodySchema(Type type, JsonMapperProfile profile) {
            CanonicalSchema original = super.generateBodySchema(type, profile);
            JsonObject document = new JsonObject(original.json());
            document.getJsonObject("properties").getJsonObject("name").put("default", "##default");
            return new CanonicalSchema(document.encode(), original.redactionManifest());
        }
    }

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

    private static JaxRsOperationDescriptor corpusBodyOp(CorpusFixture fixture) {
        return op(
                "corpus_" + fixture.name(),
                List.of(),
                Optional.of(new BodyDescriptor(fixture.rawType(), fixture.genericType(), List.of())));
    }

    private static JaxRsOperationDescriptor paramOnlyOp() {
        ParamDescriptor a = new ParamDescriptor("a", ParamLocation.QUERY, String.class, null, null, null, List.of());
        ParamDescriptor b = new ParamDescriptor("b", ParamLocation.QUERY, String.class, null, null, null, List.of());
        return op("paramOnly", List.of(a, b), Optional.empty());
    }

    /** The reserved {@code vertique} floor profile. */
    private static JsonMapperProfile vertiqueProfile() {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique"));
    }

    // --- TP-002 ---

    @TestFactory
    @DisplayName("Each body schema carries the generator's manifest")
    Stream<DynamicTest> bodySchemaCarriesTheGeneratorsManifest() {
        JsonMapperProfile profile = vertiqueProfile();
        AnnotationSchemaSource source = new AnnotationSchemaSource();
        AnnotationJsonSchemaGenerator oracle = AnnotationJsonSchemaGenerator.forInputProfile(profile);

        return Stream.of(
                dynamicTest(
                        "GuardedBody's provenance equals the oracle's manifest (pointers [/propertyNames]) and"
                                + " matches its body",
                        () -> {
                            CanonicalSchema expected = oracle.describe(GuardedBody.class);
                            OperationSchemas schemas = source.schemasFor(bodyOp("guarded", GuardedBody.class), profile);

                            JsonObject body = schemas.bodySchema()
                                    .orElseThrow(() -> new AssertionError("GuardedBody must produce a body schema"));
                            RedactionManifest manifest = schemas.bodySchemaProvenance(RedactionManifest.class)
                                    .orElseThrow(
                                            () -> new AssertionError("GuardedBody's schema must carry a manifest"));

                            assertAll(
                                    () -> assertEquals(List.of("/propertyNames"), manifest.pointers()),
                                    () -> assertEquals(expected.redactionManifest(), manifest),
                                    () -> assertEquals(new JsonObject(expected.json()), body),
                                    () -> assertTrue(manifest.matches(body.encode())));
                        }),
                dynamicTest(
                        "PlainBody's provenance is present with empty pointers and matches its body (nothing is"
                                + " reserved)",
                        () -> {
                            OperationSchemas schemas = source.schemasFor(bodyOp("plain", PlainBody.class), profile);

                            JsonObject body = schemas.bodySchema()
                                    .orElseThrow(() -> new AssertionError("PlainBody must produce a body schema"));
                            RedactionManifest manifest = schemas.bodySchemaProvenance(RedactionManifest.class)
                                    .orElseThrow(() -> new AssertionError("PlainBody's schema must carry a manifest"));

                            assertAll(
                                    () -> assertEquals(List.of(), manifest.pointers()),
                                    () -> assertTrue(manifest.matches(body.encode())));
                        }),
                dynamicTest(
                        "A parameter-only operation has no body and no provenance, with its parameter schemas"
                                + " present as before",
                        () -> {
                            OperationSchemas schemas = source.schemasFor(paramOnlyOp(), profile);

                            assertAll(
                                    () -> assertTrue(schemas.bodySchema().isEmpty()),
                                    () -> assertTrue(schemas.bodySchemaProvenance(Object.class)
                                            .isEmpty()),
                                    () -> assertTrue(schemas.parameterSchema(ParamLocation.QUERY, "a")
                                            .isPresent()),
                                    () -> assertTrue(schemas.parameterSchema(ParamLocation.QUERY, "b")
                                            .isPresent()));
                        }));
    }

    // --- TP-003 ---

    /**
     * Runs {@code source.schemasFor(op, profile)} and asserts the body's manifest matches every
     * rendering a later copy or re-encoding could produce.
     */
    private static void assertManifestSurvivesRoundTrips(
            AnnotationSchemaSource source, JaxRsOperationDescriptor op, JsonMapperProfile profile) {
        OperationSchemas schemas = source.schemasFor(op, profile);
        JsonObject body = schemas.bodySchema().orElseThrow(() -> new AssertionError("expected a body schema"));
        RedactionManifest manifest = schemas.bodySchemaProvenance(RedactionManifest.class)
                .orElseThrow(() -> new AssertionError("expected a redaction manifest"));

        assertAll(
                () -> assertTrue(manifest.matches(body.encode()), "encode()"),
                () -> assertTrue(manifest.matches(body.copy().encode()), "copy().encode()"),
                () -> assertTrue(
                        manifest.matches(new JsonObject(body.encode()).encode()), "new JsonObject(encode()).encode()"),
                () -> assertTrue(manifest.matches(body.encodePrettily()), "encodePrettily()"),
                () -> assertTrue(manifest.matches(body.toBuffer().toString()), "toBuffer().toString()"));
    }

    @TestFactory
    @DisplayName("The manifest survives JsonObject round trips and the sentinel strip")
    Stream<DynamicTest> manifestSurvivesJsonObjectRoundTrips() {
        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of());
        List<JsonMapperProfile> profiles = Stream.of("system", "vertique", "vertique-strict")
                .map(id -> registry.profile(JsonProfileId.of(id)))
                .toList();
        AnnotationSchemaSource source = new AnnotationSchemaSource();

        Stream<DynamicTest> corpusCases = SchemaCorpus.FIXTURES.stream().flatMap(fixture -> profiles.stream()
                .map(profile -> dynamicTest(
                        fixture.name() + " under " + profile.id().value(),
                        () -> assertManifestSurvivesRoundTrips(source, corpusBodyOp(fixture), profile))));

        Stream<DynamicTest> numberBodyCases = profiles.stream()
                .map(profile -> dynamicTest(
                        "NumberBody under " + profile.id().value(),
                        () -> assertManifestSurvivesRoundTrips(
                                source, bodyOp("numberBody", NumberBody.class), profile)));

        DynamicTest sentinelStripCase = dynamicTest(
                "a source re-inserting the ##default sentinel keeps the manifest matching after the strip", () -> {
                    AnnotationSchemaSource sentinelSource = new SentinelReinsertingSource();
                    JaxRsOperationDescriptor sentinelOp = bodyOp("sentinelReinsert", PlainBody.class);
                    JsonMapperProfile sentinelProfile = vertiqueProfile();

                    assertManifestSurvivesRoundTrips(sentinelSource, sentinelOp, sentinelProfile);

                    JsonObject nameSchema = sentinelSource
                            .schemasFor(sentinelOp, sentinelProfile)
                            .bodySchema()
                            .orElseThrow(() -> new AssertionError("expected a body schema"))
                            .getJsonObject("properties")
                            .getJsonObject("name");
                    assertFalse(
                            nameSchema.containsKey("default"),
                            () -> "the ##default sentinel must be stripped from the returned body, but"
                                    + " properties.name is " + nameSchema.encode());
                });

        return Stream.concat(Stream.concat(corpusCases, numberBodyCases), Stream.of(sentinelStripCase));
    }

    // --- TP-004 ---

    @TestFactory
    @DisplayName("A replaced, edited, or unbound body no longer matches a manifest")
    Stream<DynamicTest> replacedOrEditedBodyNoLongerMatches() {
        JsonMapperProfile profile = vertiqueProfile();
        AnnotationJsonSchemaGenerator oracle = AnnotationJsonSchemaGenerator.forInputProfile(profile);

        return Stream.of(
                dynamicTest(
                        "AC-014.3(a): a borrowed manifest carries through but no longer matches the substituted"
                                + " JSON",
                        () -> {
                            AnnotationSchemaSource borrowing = new AnnotationSchemaSource() {
                                @Override
                                protected CanonicalSchema generateBodySchema(Type type, JsonMapperProfile p) {
                                    CanonicalSchema substituted = super.generateBodySchema(PlainBody.class, p);
                                    CanonicalSchema ownManifest = super.generateBodySchema(type, p);
                                    return new CanonicalSchema(substituted.json(), ownManifest.redactionManifest());
                                }
                            };

                            OperationSchemas schemas =
                                    borrowing.schemasFor(bodyOp("borrowed", GuardedBody.class), profile);
                            JsonObject body = schemas.bodySchema()
                                    .orElseThrow(() -> new AssertionError("expected a substituted body schema"));
                            RedactionManifest manifest = schemas.bodySchemaProvenance(RedactionManifest.class)
                                    .orElseThrow(() -> new AssertionError("expected the borrowed manifest"));
                            RedactionManifest guardedManifest =
                                    oracle.describe(GuardedBody.class).redactionManifest();
                            JsonObject plainDocument = new JsonObject(
                                    oracle.describe(PlainBody.class).json());

                            assertAll(
                                    () -> assertEquals(plainDocument, body),
                                    () -> assertEquals(guardedManifest, manifest),
                                    () -> assertFalse(manifest.matches(body.encode())));
                        }),
                dynamicTest("AC-014.3(b): an in-place edit of the body breaks the match", () -> {
                    AnnotationSchemaSource source = new AnnotationSchemaSource();
                    OperationSchemas schemas = source.schemasFor(bodyOp("editedInPlace", GuardedBody.class), profile);
                    JsonObject body = schemas.bodySchema().orElseThrow();
                    RedactionManifest manifest = schemas.bodySchemaProvenance(RedactionManifest.class)
                            .orElseThrow();

                    assertTrue(manifest.matches(body.encode()), "must match before the edit");
                    body.put("allOf", new JsonArray().add(new JsonObject()));
                    assertFalse(manifest.matches(body.encode()), "must not match after the in-place edit");
                }),
                dynamicTest(
                        "AC-014.3(c): a custom OperationSchemaSource built with the one-argument builder form"
                                + " carries no provenance",
                        () -> {
                            OperationSchemaSource custom = (op, p) -> OperationSchemas.builder()
                                    .bodySchema(new JsonObject().put("type", "object"))
                                    .build();

                            OperationSchemas schemas = custom.schemasFor(bodyOp("custom", PlainBody.class), profile);

                            assertTrue(schemas.bodySchemaProvenance(RedactionManifest.class)
                                    .isEmpty());
                            assertTrue(
                                    schemas.bodySchemaProvenance(Object.class).isEmpty());
                        }),
                dynamicTest(
                        "AC-014.3(d): a decorator delegating and rebuilding with the one-argument form carries no"
                                + " provenance",
                        () -> {
                            AnnotationSchemaSource delegate = new AnnotationSchemaSource();
                            OperationSchemaSource decorator = (op, p) -> {
                                OperationSchemas result = delegate.schemasFor(op, p);
                                return OperationSchemas.builder()
                                        .bodySchema(result.bodySchema().orElseThrow())
                                        .build();
                            };

                            OperationSchemas schemas =
                                    decorator.schemasFor(bodyOp("decoratedOneArg", GuardedBody.class), profile);

                            assertTrue(schemas.bodySchemaProvenance(RedactionManifest.class)
                                    .isEmpty());
                        }),
                dynamicTest(
                        "AC-014.3(e): a decorator replacing the body through toBuilder()'s one-argument form"
                                + " carries no provenance",
                        () -> {
                            AnnotationSchemaSource delegate = new AnnotationSchemaSource();
                            JsonObject replaced = new JsonObject().put("type", "string");
                            OperationSchemaSource decorator = (op, p) -> {
                                OperationSchemas result = delegate.schemasFor(op, p);
                                return result.toBuilder().bodySchema(replaced).build();
                            };

                            OperationSchemas schemas = decorator.schemasFor(
                                    bodyOp("decoratedToBuilderOneArg", GuardedBody.class), profile);

                            assertAll(
                                    () -> assertTrue(schemas.bodySchemaProvenance(RedactionManifest.class)
                                            .isEmpty()),
                                    () -> assertEquals(
                                            replaced, schemas.bodySchema().orElseThrow()));
                        }),
                dynamicTest(
                        "AC-014.3(f): a decorator adding a parameter through toBuilder() keeps the default"
                                + " source's manifest, which still matches its unchanged body",
                        () -> {
                            AnnotationSchemaSource delegate = new AnnotationSchemaSource();
                            AtomicReference<RedactionManifest> originalManifest = new AtomicReference<>();
                            JsonObject extraParamSchema = new JsonObject().put("type", "string");
                            OperationSchemaSource decorator = (op, p) -> {
                                OperationSchemas result = delegate.schemasFor(op, p);
                                originalManifest.set(result.bodySchemaProvenance(RedactionManifest.class)
                                        .orElseThrow());
                                return result.toBuilder()
                                        .parameterSchema(ParamLocation.QUERY, "extra", extraParamSchema)
                                        .build();
                            };

                            OperationSchemas schemas =
                                    decorator.schemasFor(bodyOp("decoratedAddParam", GuardedBody.class), profile);
                            RedactionManifest manifest = schemas.bodySchemaProvenance(RedactionManifest.class)
                                    .orElseThrow();
                            JsonObject body = schemas.bodySchema().orElseThrow();

                            assertAll(
                                    () -> assertSame(originalManifest.get(), manifest),
                                    () -> assertTrue(schemas.parameterSchema(ParamLocation.QUERY, "extra")
                                            .isPresent()),
                                    () -> assertTrue(manifest.matches(body.encode())));
                        }));
    }
}
