// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static dev.vertique.rest.openapi.docs.ResponseDocuments.DEFAULT_PROFILE;
import static dev.vertique.rest.openapi.docs.ResponseDocuments.SCHEMA_REF_PREFIX;
import static dev.vertique.rest.openapi.docs.ResponseDocuments.STRICT_PROFILE;
import static dev.vertique.rest.openapi.docs.ResponseDocuments.assemble;
import static dev.vertique.rest.openapi.docs.ResponseDocuments.assertValidates;
import static dev.vertique.rest.openapi.docs.ResponseDocuments.component;
import static dev.vertique.rest.openapi.docs.ResponseDocuments.componentKeys;
import static dev.vertique.rest.openapi.docs.ResponseDocuments.contentRefs;
import static dev.vertique.rest.openapi.docs.ResponseDocuments.generatedOutputSchema;
import static dev.vertique.rest.openapi.docs.ResponseDocuments.hasComponent;
import static dev.vertique.rest.openapi.docs.ResponseDocuments.mountPath;
import static dev.vertique.rest.openapi.docs.ResponseDocuments.propertyNames;
import static dev.vertique.rest.openapi.docs.ResponseDocuments.registry;
import static dev.vertique.rest.openapi.docs.ResponseDocuments.response;
import static dev.vertique.rest.openapi.docs.ResponseDocuments.responseKeys;
import static dev.vertique.rest.openapi.docs.ResponseDocuments.responses;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.json.schema.JsonSchemaGenerationException;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.openapi.docs.DisclosureDocuments.Rendering;
import dev.vertique.rest.openapi.docs.MetadataDocuments.WarningCapture;
import dev.vertique.rest.openapi.docs.ResponseDocuments.ResponseOperation;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.BadView;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.CatalogItem;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.GoodView;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.ItemView;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.Part;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.Problem;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.Thing;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.ViewA;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.ViewB;
import dev.vertique.rest.openapi.docs.fixture.responses.unit.ClassDeclares404And500Resource;
import dev.vertique.rest.openapi.docs.fixture.responses.unit.ClassDeclares404TwiceResource;
import dev.vertique.rest.openapi.docs.fixture.responses.unit.ClassWithoutResponsesResource;
import dev.vertique.rest.openapi.docs.fixture.responses.unit.ComponentNamingResource;
import dev.vertique.rest.openapi.docs.fixture.responses.unit.DeclaredSuccessResource;
import dev.vertique.rest.openapi.docs.fixture.responses.unit.GeneratorFailureResource;
import dev.vertique.rest.openapi.docs.fixture.responses.unit.HonoredAttributesResource;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit proofs that the document assembler publishes each operation's Responses Object from its
 * declared {@code @ApiResponse}s and its return type: explicit schemas become components named per
 * status, only honored attributes publish, class-level responses merge with the method's, a
 * generator failure fails publication naming the status, and a declared success status without
 * content keeps the inferred content.
 *
 * <p>Every case builds a synthetic publication with {@link ResponseDocuments}: one operation per
 * fixture method, whose stub descriptor carries the method's and its resource class's real
 * annotations, and whose response facts are built from that method and class as the runtime
 * captures them. Each assembly gets a fresh warning guard, and the warnings it logs are captured on
 * the documentation module's warning logger.
 *
 * <p>Expected values are hand-written literals: ordered response-key lists, exact component-key
 * sets, exact descriptions, exact media-type-to-component maps, and exact JSON trees compared as
 * values (member order is not compared). A published component equals the schema a fresh
 * output-direction generator of the operation's profile produces for its type. Failure messages and
 * warnings are checked by fragment; an operation id or a status is matched as a whole word. String
 * attributes the document must not publish carry the sentinel {@value #SENTINEL}, which no failure
 * message or warning may contain.
 */
@DisplayName("Response objects and output schemas of assembled documents")
class ResponseAssemblyTest {

    /** The sentinel every unpublished string attribute of the fixtures carries. */
    private static final String SENTINEL = "ZX";

    /** The JSON media type. */
    private static final String JSON_MEDIA_TYPE = "application/json";

    /** The document of the component-naming and generator-failure cases. */
    private static final String REPORTS = "reports";

    /** The document of the honored-attribute cases. */
    private static final String THINGS = "things";

    /** The document of the class-level merge cases. */
    private static final String CLASSES = "classes";

    /** The document of the declared-success cases. */
    private static final String CATALOG = "catalog";

    /** The operation every honored-attribute case stands for. */
    private static final String GET_THING = "getThing";

    /** The attribute texts an omitted-attribute warning names, each followed by its status. */
    private static final String RESPONSE_REF = "@ApiResponse.ref";

    private static final String RESPONSE_LINKS = "@ApiResponse.links";

    private static final String CONTENT_ENCODING = "@Content.encoding";

    private static final String SCHEMA_MAX_PROPERTIES = "@Schema.maxProperties";

    private static final String ARRAY_SCHEMA_MIN_ITEMS = "@ArraySchema.minItems";

    private static final String HEADER_REF = "@Header.ref";

    private static final String RESPONSE_EXTENSIONS = "@ApiResponse.extensions";

    /** Every attribute an omitted-attribute warning may name. */
    private static final List<String> OMITTED_ATTRIBUTES = List.of(
            RESPONSE_REF,
            RESPONSE_LINKS,
            CONTENT_ENCODING,
            SCHEMA_MAX_PROPERTIES,
            ARRAY_SCHEMA_MIN_ITEMS,
            HEADER_REF,
            RESPONSE_EXTENSIONS);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WarningCapture capture = ResponseDocuments.warningCapture();

    @BeforeEach
    void attachWarningCapture() {
        capture.attach();
    }

    @AfterEach
    void detachWarningCapture() {
        capture.detach();
    }

    // ---------------------------------------------------------------------------------------------
    // Component names
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("explicit schemas become components named per status, one per implementation")
    void explicitSchemasGetStatusScopedComponentNames() {
        // Given: getReport declares ViewA twice and ViewB once on 200, Problem and an X-Rate header on 404
        ResponseOperation getReport = ResponseOperation.of("getReport", ComponentNamingResource.class, "getReport");
        // and two operations whose ids sanitize to one component name
        ResponseOperation colon = ResponseOperation.of("get:report", ComponentNamingResource.class, "getReportColon");
        ResponseOperation underscore =
                ResponseOperation.of("get_report", ComponentNamingResource.class, "getReportUnderscore");

        // When: each document is assembled
        Rendering rendering = assemble(REPORTS, getReport).rendering();
        RestConfigurationException collision =
                assemble(REPORTS, colon, underscore).failure();

        // Then: 200's two implementations are numbered in declaration order, 404's one is not
        JsonNode document = rendering.jsonTree();
        assertAll(
                "the component names of getReport",
                () -> assertEquals(List.of("200", "404"), responseKeys(responses(document, "getReport"))),
                () -> assertEquals(
                        Map.of(
                                JSON_MEDIA_TYPE,
                                "getReport.response.200.1",
                                "application/vnd.b+json",
                                "getReport.response.200.2",
                                "application/vnd.a+json",
                                "getReport.response.200.1"),
                        contentRefs(response(document, "getReport", "200"))),
                () -> assertEquals(
                        Map.of(JSON_MEDIA_TYPE, "getReport.response.404"),
                        contentRefs(response(document, "getReport", "404"))),
                () -> assertEquals(
                        json(refJson("getReport.response.404.header.X-Rate")),
                        response(document, "getReport", "404")
                                .path("headers")
                                .path("X-Rate")
                                .path("schema")),
                () -> assertEquals(
                        Set.of(
                                "getReport.response.200.1",
                                "getReport.response.200.2",
                                "getReport.response.404",
                                "getReport.response.404.header.X-Rate"),
                        componentKeys(document)),
                () -> assertComponentIsGenerated(document, "getReport.response.200.1", DEFAULT_PROFILE, ViewA.class),
                () -> assertComponentIsGenerated(document, "getReport.response.200.2", DEFAULT_PROFILE, ViewB.class),
                () -> assertComponentIsGenerated(document, "getReport.response.404", DEFAULT_PROFILE, Problem.class),
                () -> assertComponentIsGenerated(
                        document, "getReport.response.404.header.X-Rate", DEFAULT_PROFILE, Integer.class),
                () -> assertNoComponentHasId(document),
                () -> assertValidates(rendering));
        // and the sanitized collision fails naming both operations
        assertAll(
                "the collision of get:report and get_report",
                () -> assertTrue(
                        containsWord(collision.getMessage(), "get:report"),
                        () -> "the failure must name get:report: " + collision.getMessage()),
                () -> assertTrue(
                        containsWord(collision.getMessage(), "get_report"),
                        () -> "the failure must name get_report: " + collision.getMessage()));
    }

    // ---------------------------------------------------------------------------------------------
    // Honored and omitted attributes
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("attributeCases")
    @DisplayName("only honored response attributes publish; omitted ones warn once, invalid ones fail")
    void onlyHonoredResponseAttributesPublish(AttributeCase attributeCase) {
        // Given: operation getThing producing application/json, its responses declared by the case's method
        ResponseOperation getThing = ResponseOperation.of(
                        GET_THING, HonoredAttributesResource.class, attributeCase.methodName())
                .withProduces(JSON_MEDIA_TYPE);

        // When: the document is assembled with the warning logger captured
        MetadataDocuments.Outcome outcome = assemble(THINGS, getThing);

        // Then: the case's expectation holds
        switch (attributeCase.kind()) {
            case HONORED -> assertHonored(outcome.rendering());
            case OMITTED -> assertOmitted(outcome.rendering(), attributeCase);
            case FAILING -> assertFailing(outcome.failure(), attributeCase);
        }
    }

    static Stream<Arguments> attributeCases() {
        return Stream.of(
                attributeCase("(h) every honored attribute publishes", AttributeCase.honored("honored")),
                attributeCase(
                        "(o1) @ApiResponse.ref is omitted with a warning",
                        AttributeCase.omitted("omittedRef", false, RESPONSE_REF)),
                attributeCase(
                        "(o2) @ApiResponse.links is omitted with a warning",
                        AttributeCase.omitted("omittedLinks", false, RESPONSE_LINKS)),
                attributeCase(
                        "(o3) @Content.encoding is omitted with a warning",
                        AttributeCase.omitted("omittedEncoding", false, CONTENT_ENCODING)),
                attributeCase(
                        "(o4) @Schema.maxProperties is omitted with a warning",
                        AttributeCase.omitted("omittedSchemaMaxProperties", false, SCHEMA_MAX_PROPERTIES)),
                attributeCase(
                        "(o5) @ArraySchema.minItems is omitted with a warning",
                        AttributeCase.omitted("omittedArraySchemaMinItems", true, ARRAY_SCHEMA_MIN_ITEMS)),
                attributeCase(
                        "(o6) @Header.ref is omitted with a warning",
                        AttributeCase.omitted("omittedHeaderRef", false, HEADER_REF)),
                attributeCase(
                        "(o7) an extension not named x- is omitted with a warning",
                        AttributeCase.omitted("omittedExtensionName", false, RESPONSE_EXTENSIONS)),
                attributeCase(
                        "(o8) all seven omitted attributes share one warning",
                        AttributeCase.omitted("omittedAll", true, OMITTED_ATTRIBUTES.toArray(String[]::new))),
                attributeCase(
                        "(f1) a blank example name fails",
                        AttributeCase.failing("failingBlankExampleName", "200", "@ExampleObject.name")),
                attributeCase(
                        "(f2) useReturnTypeSchema on a Response return fails",
                        AttributeCase.failing(
                                "failingUseReturnTypeSchemaOnResponse", "201", "@ApiResponse.useReturnTypeSchema")),
                attributeCase(
                        "(f3) an example reference fails",
                        AttributeCase.failing("failingExampleRef", "200", "@ExampleObject.ref")));
    }

    private static Arguments attributeCase(String label, AttributeCase attributeCase) {
        return Arguments.of(Named.of(label, attributeCase));
    }

    /** Asserts the honored case: one assertion block per response, then components and warnings. */
    private void assertHonored(Rendering rendering) {
        JsonNode document = rendering.jsonTree();
        assertEquals(List.of("200", "201", "202", "206"), responseKeys(responses(document, GET_THING)));
        assertHonored200(document, false);
        assertAll(
                "201 publishes the inferred content",
                () -> assertEquals(
                        "Created",
                        response(document, GET_THING, "201").path("description").asText()),
                () -> assertEquals(
                        json("{\"" + JSON_MEDIA_TYPE + "\": {\"schema\": " + refJson("getThing.response") + "}}"),
                        response(document, GET_THING, "201").path("content")));
        assertAll(
                "202 publishes its reason phrase and the inferred content",
                () -> assertEquals(
                        "Accepted",
                        response(document, GET_THING, "202").path("description").asText()),
                () -> assertEquals(
                        json("{\"" + JSON_MEDIA_TYPE + "\": {\"schema\": " + refJson("getThing.response") + "}}"),
                        response(document, GET_THING, "202").path("content")));
        assertAll(
                "206 publishes an array of documented references",
                () -> assertEquals(
                        "Some parts",
                        response(document, GET_THING, "206").path("description").asText()),
                () -> assertEquals(
                        json("{\"" + JSON_MEDIA_TYPE + "\": {\"schema\": {\"type\": \"array\", \"items\": {"
                                + "\"$ref\": \"" + SCHEMA_REF_PREFIX + "getThing.response.206\","
                                + " \"description\": \"One part\"}}}}"),
                        response(document, GET_THING, "206").path("content")));
        assertAll(
                "the components of the honored case",
                () -> assertEquals(
                        Set.of(
                                "getThing.response",
                                "getThing.response.200",
                                "getThing.response.200.header.X-Rate",
                                "getThing.response.206"),
                        componentKeys(document)),
                () -> assertEquals(Set.of("id", "name"), propertyNames(component(document, "getThing.response"))),
                () -> assertEquals(
                        Set.of("partNumber", "quantity"), propertyNames(component(document, "getThing.response.206"))),
                () -> assertComponentIsGenerated(document, "getThing.response", DEFAULT_PROFILE, Thing.class),
                () -> assertComponentIsGenerated(document, "getThing.response.200", DEFAULT_PROFILE, Thing.class),
                () -> assertComponentIsGenerated(
                        document, "getThing.response.200.header.X-Rate", DEFAULT_PROFILE, Integer.class),
                () -> assertComponentIsGenerated(document, "getThing.response.206", DEFAULT_PROFILE, Part.class),
                () -> assertNoComponentHasId(document));
        assertAll(
                "the honored case warns of nothing and validates",
                () -> assertEquals(List.of(), capture.warnings()),
                () -> assertValidates(rendering));
    }

    /**
     * Asserts the honored {@code 200}: its description, documented header, documented content schema
     * with its example, and its {@code x-} extension, and no other member.
     *
     * @param document the document
     * @param withArrayCopy whether the case adds a second media type, {@code application/vnd.a+json},
     *     holding an array of the same implementation
     */
    private static void assertHonored200(JsonNode document, boolean withArrayCopy) {
        JsonNode ok = response(document, GET_THING, "200");
        JsonNode jsonMediaType = ok.path("content").path(JSON_MEDIA_TYPE);
        Set<String> mediaTypes =
                withArrayCopy ? Set.of(JSON_MEDIA_TYPE, "application/vnd.a+json") : Set.of(JSON_MEDIA_TYPE);
        List<Executable> checks = new ArrayList<>(List.<Executable>of(
                () -> assertEquals(Set.of("description", "headers", "content", "x-rate-limited"), fieldNames(ok)),
                () -> assertEquals("The thing", ok.path("description").asText()),
                () -> assertEquals(Set.of("X-Rate"), fieldNames(ok.path("headers"))),
                () -> assertEquals(
                        json("{\"description\": \"Remaining\", \"required\": true, \"deprecated\": true,"
                                + " \"schema\": " + refJson("getThing.response.200.header.X-Rate") + "}"),
                        ok.path("headers").path("X-Rate")),
                () -> assertEquals(mediaTypes, fieldNames(ok.path("content"))),
                () -> assertEquals(Set.of("schema", "examples"), fieldNames(jsonMediaType)),
                () -> assertEquals(
                        json("{\"$ref\": \"" + SCHEMA_REF_PREFIX + "getThing.response.200\","
                                + " \"description\": \"The thing body\", \"title\": \"Thing\"}"),
                        jsonMediaType.path("schema")),
                () -> assertEquals(Set.of("one"), fieldNames(jsonMediaType.path("examples"))),
                () -> assertEquals(
                        json("{\"summary\": \"One\", \"value\": {\"id\": 1}}"),
                        jsonMediaType.path("examples").path("one")),
                () -> assertEquals(json("{\"limit\": \"10\"}"), ok.path("x-rate-limited"))));
        if (withArrayCopy) {
            checks.add(() -> assertEquals(
                    json("{\"schema\": {\"type\": \"array\", \"items\": " + refJson("getThing.response.200") + "}}"),
                    ok.path("content").path("application/vnd.a+json")));
        }
        assertAll("the honored 200", checks.stream());
    }

    /** Asserts an omitted case: the honored 200 alone, its components, and exactly one warning. */
    private void assertOmitted(Rendering rendering, AttributeCase attributeCase) {
        JsonNode document = rendering.jsonTree();
        assertEquals(List.of("200"), responseKeys(responses(document, GET_THING)));
        assertHonored200(document, attributeCase.withArrayCopy());
        assertEquals(Set.of("getThing.response.200", "getThing.response.200.header.X-Rate"), componentKeys(document));
        List<String> warnings = capture.warnings();
        assertEquals(1, warnings.size(), () -> "expected exactly one warning, got " + warnings);
        String warning = warnings.get(0);
        List<Executable> checks = new ArrayList<>(List.<Executable>of(
                () -> assertTrue(
                        warning.startsWith("apidocs.documents." + THINGS),
                        () -> "the warning must start with the document's configuration path: " + warning),
                () -> assertTrue(
                        warning.contains(mountPath(THINGS)), () -> "the warning must name the mount: " + warning),
                () -> assertTrue(
                        containsWord(warning, GET_THING), () -> "the warning must name the operation: " + warning),
                () -> assertFalse(warning.contains(SENTINEL), () -> "the warning quotes a value: " + warning)));
        for (String attribute : OMITTED_ATTRIBUTES) {
            String named = attribute + " on status 200";
            if (attributeCase.attributes().contains(attribute)) {
                checks.add(() ->
                        assertTrue(warning.contains(named), () -> "the warning must name " + named + ": " + warning));
            } else {
                checks.add(() -> assertFalse(
                        warning.contains(attribute), () -> "the warning must not name " + attribute + ": " + warning));
            }
        }
        assertAll("the omitted-attribute warning", checks.stream());
    }

    /** Asserts a failing case: the failure names the operation, the status, and the attribute. */
    private static void assertFailing(RestConfigurationException failure, AttributeCase attributeCase) {
        String message = String.valueOf(failure.getMessage());
        String attribute = attributeCase.attributes().get(0);
        assertAll(
                "the failure of " + attributeCase.methodName(),
                () -> assertTrue(
                        containsWord(message, GET_THING), () -> "the failure must name the operation: " + message),
                () -> assertTrue(
                        containsWord(message, attributeCase.status()),
                        () -> "the failure must name status " + attributeCase.status() + ": " + message),
                () -> assertTrue(
                        message.contains(attribute), () -> "the failure must name " + attribute + ": " + message),
                () -> assertFalse(message.contains(SENTINEL), () -> "the failure quotes a value: " + message));
    }

    // ---------------------------------------------------------------------------------------------
    // Class-level merge
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("class-level responses merge with the method's, the method winning a shared status")
    void classLevelResponsesMergeWithMethodLevel() {
        // Given: a class declaring 404 and 500, method a declaring 404 and 200, method b declaring none
        ResponseOperation a = ResponseOperation.of("a", ClassDeclares404And500Resource.class, "a");
        ResponseOperation b = ResponseOperation.of("b", ClassDeclares404And500Resource.class, "b");
        // and a class declaring 404 twice, and a control class declaring nothing
        ResponseOperation twice = ResponseOperation.of("readTwice", ClassDeclares404TwiceResource.class, "c");
        ResponseOperation d = ResponseOperation.of("d", ClassWithoutResponsesResource.class, "d");

        // When: each document is assembled, (a) twice
        Rendering first = assemble(CLASSES, a).rendering();
        Rendering second = assemble(CLASSES, a).rendering();
        JsonNode documentA = first.jsonTree();
        JsonNode documentB = assemble(CLASSES, b).rendering().jsonTree();
        RestConfigurationException duplicate = assemble(CLASSES, twice).failure();
        JsonNode documentD = assemble(CLASSES, d).rendering().jsonTree();

        // Then
        assertAll(
                "(a) the method's 404 wins, the class's 500 joins, the method's 200 keeps the inferred content",
                () -> assertEquals(List.of("200", "404", "500"), responseKeys(responses(documentA, "a"))),
                () -> assertEquals(
                        "Method OK",
                        response(documentA, "a", "200").path("description").asText()),
                () -> assertEquals(
                        "Method missing",
                        response(documentA, "a", "404").path("description").asText()),
                () -> assertEquals(
                        "Class failure",
                        response(documentA, "a", "500").path("description").asText()),
                () -> assertEquals(Map.of(JSON_MEDIA_TYPE, "a.response"), contentRefs(response(documentA, "a", "200"))),
                () -> assertFalse(response(documentA, "a", "404").has("content"), "404 must carry no content"),
                () -> assertFalse(response(documentA, "a", "500").has("content"), "500 must carry no content"),
                () -> assertEquals(Set.of("a.response"), componentKeys(documentA)),
                () -> assertComponentIsGenerated(documentA, "a.response", DEFAULT_PROFILE, Thing.class));
        assertAll(
                "(b) the class's responses replace the inferred one",
                () -> assertEquals(List.of("404", "500"), responseKeys(responses(documentB, "b"))),
                () -> assertEquals(
                        "Class missing",
                        response(documentB, "b", "404").path("description").asText()),
                () -> assertEquals(
                        "Class failure",
                        response(documentB, "b", "500").path("description").asText()),
                () -> assertFalse(response(documentB, "b", "404").has("content"), "404 must carry no content"),
                () -> assertFalse(response(documentB, "b", "500").has("content"), "500 must carry no content"),
                () -> assertFalse(hasComponent(documentB, "b.response"), "no b.response component may exist"),
                () -> assertEquals(Set.of(), componentKeys(documentB)));
        assertAll(
                "(c) a status declared twice at the class level fails",
                () -> assertTrue(
                        containsWord(duplicate.getMessage(), "readTwice"),
                        () -> "the failure must name the operation: " + duplicate.getMessage()),
                () -> assertTrue(
                        containsWord(duplicate.getMessage(), "404"),
                        () -> "the failure must name 404: " + duplicate.getMessage()));
        assertAll(
                "(d) without declared responses the inferred 200 publishes",
                () -> assertEquals(List.of("200"), responseKeys(responses(documentD, "d"))),
                () -> assertEquals(
                        Map.of(JSON_MEDIA_TYPE, "d.response"), contentRefs(response(documentD, "d", "200"))));
        assertAll(
                "two assemblies of (a) give identical bytes",
                () -> assertArrayEquals(first.json(), second.json(), "the JSON bytes differ"),
                () -> assertArrayEquals(first.yaml(), second.yaml(), "the YAML bytes differ"));
    }

    // ---------------------------------------------------------------------------------------------
    // Generator failures
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a generator failure fails publication naming the operation and the status")
    void generatorFailureFailsPublicationNamingTheStatus() {
        // Given: under the strict profile, BadView, which its output generator refuses, inferred and declared on 409
        ResponseOperation getBad = ResponseOperation.of("getBad", GeneratorFailureResource.class, "getBad")
                .withProfile(STRICT_PROFILE);
        ResponseOperation getBadExplicit = ResponseOperation.of(
                        "getBadExplicit", GeneratorFailureResource.class, "getBadExplicit")
                .withProfile(STRICT_PROFILE);
        // and the control GoodView, which it generates
        ResponseOperation getGood = ResponseOperation.of("getGood", GeneratorFailureResource.class, "getGood")
                .withProfile(STRICT_PROFILE);

        // When: each document is assembled
        RestConfigurationException inferred = assemble(REPORTS, getBad).failure();
        RestConfigurationException declared = assemble(REPORTS, getBadExplicit).failure();
        JsonNode good = assemble(REPORTS, getGood).rendering().jsonTree();

        // Then: each failure names the document, mount, operation, and status, quotes no schema, keeps its cause
        assertGeneratorFailure(inferred, "getBad", "200");
        assertGeneratorFailure(declared, "getBadExplicit", "409");
        assertAll(
                "the control publishes its inferred 200",
                () -> assertEquals(List.of("200"), responseKeys(responses(good, "getGood"))),
                () -> assertEquals(
                        Map.of(JSON_MEDIA_TYPE, "getGood.response"), contentRefs(response(good, "getGood", "200"))),
                () -> assertComponentIsGenerated(good, "getGood.response", STRICT_PROFILE, GoodView.class));
    }

    private static void assertGeneratorFailure(RestConfigurationException failure, String operationId, String status) {
        String message = String.valueOf(failure.getMessage());
        assertAll(
                "the generator failure of " + operationId + " (" + BadView.class.getSimpleName() + ")",
                () -> assertTrue(
                        message.contains("apidocs.documents." + REPORTS),
                        () -> "the failure must name the document's configuration path: " + message),
                () -> assertTrue(
                        message.contains(mountPath(REPORTS)), () -> "the failure must name the mount: " + message),
                () -> assertTrue(
                        containsWord(message, operationId),
                        () -> "the failure must name " + operationId + ": " + message),
                () -> assertTrue(
                        containsWord(message, status), () -> "the failure must name status " + status + ": " + message),
                () -> assertFalse(message.contains("{"), () -> "the failure quotes schema text: " + message),
                () -> assertFalse(message.contains("\"type\""), () -> "the failure quotes schema text: " + message),
                () -> assertInstanceOf(JsonSchemaGenerationException.class, failure.getCause()));
    }

    // ---------------------------------------------------------------------------------------------
    // Declared success statuses without content
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a declared success status without content keeps the inferred content")
    void declaredSuccessStatusWithoutContentKeepsInferredContent() {
        // Given: one operation per declared-status case of the catalog
        ResponseOperation getItem =
                ResponseOperation.of("getItem", DeclaredSuccessResource.class, "entityDeclaring200And404");
        ResponseOperation listItems =
                ResponseOperation.of("listItems", DeclaredSuccessResource.class, "futureOfListDeclaring2XX");
        ResponseOperation readNotes = ResponseOperation.of(
                        "readNotes", DeclaredSuccessResource.class, "stringDeclaring200")
                .withProduces("text/plain");
        ResponseOperation deleteItem =
                ResponseOperation.of("deleteItem", DeclaredSuccessResource.class, "voidDeclaring200");
        ResponseOperation getDynamic =
                ResponseOperation.of("getDynamic", DeclaredSuccessResource.class, "responseDeclaring200");
        ResponseOperation getView =
                ResponseOperation.of("getView", DeclaredSuccessResource.class, "entityDeclaring200WithContent");
        ResponseOperation touchItem =
                ResponseOperation.of("touchItem", DeclaredSuccessResource.class, "entityDeclaring204");

        // When / Then: each document is assembled, one Responses Object asserted per case
        assertAll(
                () -> {
                    Rendering rendering = assemble(CATALOG, getItem).rendering();
                    JsonNode document = rendering.jsonTree();
                    assertAll(
                            "(a) a plain entity's 200 keeps the inferred content, its 404 none",
                            () -> assertEquals(List.of("200", "404"), responseKeys(responses(document, "getItem"))),
                            () -> assertEquals(
                                    "The catalog item",
                                    response(document, "getItem", "200")
                                            .path("description")
                                            .asText()),
                            () -> assertEquals(
                                    Map.of(JSON_MEDIA_TYPE, "getItem.response"),
                                    contentRefs(response(document, "getItem", "200"))),
                            () -> assertEquals(
                                    "Missing",
                                    response(document, "getItem", "404")
                                            .path("description")
                                            .asText()),
                            () -> assertFalse(
                                    response(document, "getItem", "404").has("content"), "404 must carry no content"),
                            () -> assertEquals(Set.of("getItem.response"), componentKeys(document)),
                            () -> assertEquals(
                                    Set.of("sku", "title"), propertyNames(component(document, "getItem.response"))),
                            () -> assertComponentIsGenerated(
                                    document, "getItem.response", DEFAULT_PROFILE, CatalogItem.class),
                            () -> assertValidates(rendering));
                },
                () -> {
                    Rendering rendering = assemble(CATALOG, listItems).rendering();
                    JsonNode document = rendering.jsonTree();
                    assertAll(
                            "(b) the 2XX range keeps the inferred list content",
                            () -> assertEquals(List.of("2XX"), responseKeys(responses(document, "listItems"))),
                            () -> assertEquals(
                                    "Some items",
                                    response(document, "listItems", "2XX")
                                            .path("description")
                                            .asText()),
                            () -> assertEquals(
                                    Map.of(JSON_MEDIA_TYPE, "listItems.response"),
                                    contentRefs(response(document, "listItems", "2XX"))),
                            () -> assertEquals(Set.of("listItems.response"), componentKeys(document)),
                            () -> assertComponentIsGenerated(
                                    document, "listItems.response", DEFAULT_PROFILE, listOfCatalogItems()),
                            () -> assertValidates(rendering));
                },
                () -> {
                    Rendering rendering = assemble(CATALOG, readNotes).rendering();
                    JsonNode document = rendering.jsonTree();
                    JsonNode ok = response(document, "readNotes", "200");
                    assertAll(
                            "(c) a String's 200 keeps its raw-text media type without a schema",
                            () -> assertEquals(List.of("200"), responseKeys(responses(document, "readNotes"))),
                            () -> assertEquals("Notes", ok.path("description").asText()),
                            () -> assertEquals(List.of("text/plain"), orderedFieldNames(ok.path("content"))),
                            () -> assertFalse(
                                    ok.path("content").path("text/plain").has("schema"),
                                    "text/plain must carry no schema"),
                            () -> assertEquals(Set.of(), componentKeys(document)),
                            () -> assertValidates(rendering));
                },
                () -> assertDeclaredWithoutContent(
                        assemble(CATALOG, deleteItem).rendering(),
                        "deleteItem",
                        "200",
                        "Deleted",
                        "(d) a void return's 200 publishes without content"),
                () -> assertDeclaredWithoutContent(
                        assemble(CATALOG, getDynamic).rendering(),
                        "getDynamic",
                        "200",
                        "Dynamic",
                        "(e) a Response return's 200 publishes without content"),
                () -> {
                    Rendering rendering = assemble(CATALOG, getView).rendering();
                    JsonNode document = rendering.jsonTree();
                    assertAll(
                            "(f) declared content wins over the inferred content",
                            () -> assertEquals(List.of("200"), responseKeys(responses(document, "getView"))),
                            () -> assertEquals(
                                    "A view",
                                    response(document, "getView", "200")
                                            .path("description")
                                            .asText()),
                            () -> assertEquals(
                                    Map.of(JSON_MEDIA_TYPE, "getView.response.200"),
                                    contentRefs(response(document, "getView", "200"))),
                            () -> assertEquals(Set.of("getView.response.200"), componentKeys(document)),
                            () -> assertEquals(
                                    Set.of("viewSku", "viewTitle"),
                                    propertyNames(component(document, "getView.response.200"))),
                            () -> assertComponentIsGenerated(
                                    document, "getView.response.200", DEFAULT_PROFILE, ItemView.class),
                            () -> assertFalse(
                                    hasComponent(document, "getView.response"), "no getView.response may exist"),
                            () -> assertValidates(rendering));
                },
                () -> {
                    Rendering rendering = assemble(CATALOG, touchItem).rendering();
                    assertDeclaredWithoutContent(
                            rendering, "touchItem", "204", "Touched", "(g) 204 on an inferable return gets no content");
                    assertFalse(
                            hasComponent(rendering.jsonTree(), "touchItem.response"),
                            "no touchItem.response component may exist");
                });
    }

    /** Asserts a document whose one operation publishes one declared status without content. */
    private static void assertDeclaredWithoutContent(
            Rendering rendering, String operationId, String status, String description, String heading) {
        JsonNode document = rendering.jsonTree();
        assertAll(
                heading,
                () -> assertEquals(List.of(status), responseKeys(responses(document, operationId))),
                () -> assertEquals(
                        description,
                        response(document, operationId, status)
                                .path("description")
                                .asText()),
                () -> assertFalse(
                        response(document, operationId, status).has("content"), status + " must carry no content"),
                () -> assertEquals(Set.of(), componentKeys(document)),
                () -> assertValidates(rendering));
    }

    /** Returns {@code List<CatalogItem>}, the type argument of the list case's {@code Future}. */
    private static Type listOfCatalogItems() {
        Type future = ResponseDocuments.method(DeclaredSuccessResource.class, "futureOfListDeclaring2XX")
                .getGenericReturnType();
        return ((ParameterizedType) future).getActualTypeArguments()[0];
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Asserts that a component equals the schema the profile's output generator produces for a type. */
    private static void assertComponentIsGenerated(JsonNode document, String name, String profileId, Type type) {
        assertEquals(
                generatedOutputSchema(registry(), profileId, type),
                component(document, name),
                () -> "component '" + name + "' must equal the generated output schema of " + type.getTypeName());
    }

    private static void assertNoComponentHasId(JsonNode document) {
        for (String name : componentKeys(document)) {
            assertFalse(component(document, name).has("$id"), () -> "component '" + name + "' carries $id");
        }
    }

    private static String refJson(String componentName) {
        return "{\"$ref\": \"" + SCHEMA_REF_PREFIX + componentName + "\"}";
    }

    private static Set<String> fieldNames(JsonNode node) {
        return new LinkedHashSet<>(orderedFieldNames(node));
    }

    private static List<String> orderedFieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid expected JSON: " + text, e);
        }
    }

    private static boolean containsWord(String text, String word) {
        return text != null
                && Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(word) + "(?![A-Za-z0-9_])")
                        .matcher(text)
                        .find();
    }

    // ---------------------------------------------------------------------------------------------
    // Cases
    // ---------------------------------------------------------------------------------------------

    /** What an attribute case expects of its document. */
    enum Kind {
        /** The document publishes every honored attribute and warns of nothing. */
        HONORED,
        /** The document publishes the honored 200 without the omitted attributes and warns once. */
        OMITTED,
        /** Publication fails naming the operation, the status, and the attribute. */
        FAILING
    }

    /**
     * One honored-attribute case.
     *
     * @param methodName the fixture method of {@link HonoredAttributesResource}
     * @param kind what the case expects
     * @param status the status a failure names; {@code 200} otherwise
     * @param attributes the attributes the warning or failure names; empty for the honored case
     * @param withArrayCopy whether the case adds the {@code application/vnd.a+json} array content
     */
    record AttributeCase(String methodName, Kind kind, String status, List<String> attributes, boolean withArrayCopy) {

        static AttributeCase honored(String methodName) {
            return new AttributeCase(methodName, Kind.HONORED, "200", List.of(), false);
        }

        static AttributeCase omitted(String methodName, boolean withArrayCopy, String... attributes) {
            return new AttributeCase(methodName, Kind.OMITTED, "200", List.of(attributes), withArrayCopy);
        }

        static AttributeCase failing(String methodName, String status, String attribute) {
            return new AttributeCase(methodName, Kind.FAILING, status, List.of(attribute), false);
        }
    }
}
