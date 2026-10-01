// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.publication.InputBinding.Requiredness;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.openapi.docs.DisclosureDocuments.Rendering;
import dev.vertique.rest.openapi.docs.MetadataDocuments.Outcome;
import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.AccountHiddenFieldZx;
import dev.vertique.rest.openapi.docs.fixture.input.GeneratedBodies;
import dev.vertique.rest.openapi.docs.fixture.input.GeneratedBodies.GeneratedBody;
import dev.vertique.rest.openapi.docs.fixture.input.Publications;
import dev.vertique.rest.openapi.docs.fixture.metadata.dto.ItemDto;
import dev.vertique.rest.openapi.docs.fixture.metadata.unit.MetadataPublications;
import dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment.AgreementResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment.ExampleResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment.FormResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment.HiddenContractResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment.HiddenFirstResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment.RequestBodySourceResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment.SummaryOnlyResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment.TaggedResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment.WarningScopeResource;
import io.vertx.core.json.JsonObject;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit proofs that Swagger annotations enrich an assembled document only where they agree with the
 * runtime, that tags and examples follow their rules, and that hidden operations and hidden inputs
 * are removed before any check of the document reads them.
 *
 * <p>Every case builds a synthetic publication of application {@value MetadataPublications#APPLICATION}
 * at {@value MetadataPublications#MOUNT_PATH} with {@link Publications}, reads real annotation
 * instances onto its operations and bindings from a fixture method with {@link MetadataPublications},
 * and assembles the public document through {@link MetadataDocuments}, which takes the descriptor
 * facts and detaches the publication as the documentation sink does. Each assembly gets a fresh
 * warning guard, and the warnings it logs are captured on the documentation module's warning logger.
 *
 * <p>Expected fragments are hand-written literals. Failure messages and warnings are checked by
 * fragment only: the mount, the operation, and the attribute or member path; an input name is matched
 * as a whole word, whatever quoting surrounds it. String annotation members carry the sentinel
 * {@value #SENTINEL}, which no failure message or warning may contain. A published schema is compared,
 * as a JSON value, with the schema object captured for the binding.
 */
@DisplayName("Swagger metadata enrichment of assembled documents")
class MetadataEnrichmentTest {

    /** The mount, as every failure message and warning names it. */
    private static final String MOUNT = "/api/meta";

    /** The configuration path every warning of the document starts with. */
    private static final String WARNING_PREFIX = "apidocs.documents." + MetadataPublications.APPLICATION;

    /** The sentinel every string annotation member carries; never echoed in a message or warning. */
    private static final String SENTINEL = "VALUEZX";

    /** Other annotation values no failure message may echo. */
    private static final List<String> UNECHOED = List.of(SENTINEL, "DESCAZX", "DESCBZX");

    /** The JSON media type. */
    private static final String JSON_MEDIA_TYPE = "application/json";

    /** The members a Request Body Object may carry. */
    private static final Set<String> REQUEST_BODY_MEMBERS = Set.of("description", "content", "required");

    /** The operation of the parameter cases: {@code GET /search}. */
    private static final String SEARCH = "searchItems";

    /** The operation without inputs: {@code GET /items}. */
    private static final String LIST = "listItems";

    /** The operation reading one item by its path parameter: {@code GET /items/{id}}. */
    private static final String READ = "readItem";

    /** The operation of the body cases: {@code POST /items}. */
    private static final String CREATE = "createItem";

    /** The component key of {@value #CREATE}'s body. */
    private static final String CREATE_COMPONENT = CREATE + ".request";

    /** The second operation with a query parameter: {@code GET /lookup}. */
    private static final String LOOKUP = "lookupItems";

    /** The operation checked first of a refused document: {@code GET /a}, which would warn. */
    private static final String WARNED = "readFirst";

    /** The operation that refuses the document: {@code GET /b}, checked after {@value #WARNED}. */
    private static final String REFUSED = "readSecond";

    /** The operation of the form cases: {@code POST /forms}, whose inputs are form fields. */
    private static final String SUBMIT = "submitForm";

    /** The media type of a form request body when the operation declares none. */
    private static final String FORM_MEDIA_TYPE = "application/x-www-form-urlencoded";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MetadataDocuments.WarningCapture capture = new MetadataDocuments.WarningCapture();

    @BeforeEach
    void attachWarningCapture() {
        capture.attach();
    }

    @AfterEach
    void detachWarningCapture() {
        capture.detach();
    }

    // ---------------------------------------------------------------------------------------------
    // Expected outcomes
    // ---------------------------------------------------------------------------------------------

    /** The expected outcome of one assembly. */
    sealed interface Expected permits Fails, Publishes {}

    /**
     * Publication fails, and no warning is logged.
     *
     * @param fragments texts the message must contain, besides the mount
     * @param words names the message must contain as whole words
     */
    record Fails(List<String> fragments, List<String> words) implements Expected {}

    /**
     * The document publishes with exactly the given warnings, in any order, and the document passes
     * the given assertions.
     *
     * @param warnings one entry per expected warning
     * @param document the assertions on the rendering
     */
    record Publishes(List<WarningText> warnings, Consumer<Rendering> document) implements Expected {}

    /**
     * What one warning must contain, besides the configuration path prefix and the mount.
     *
     * @param fragments texts the warning must contain
     * @param words names the warning must contain as whole words
     */
    record WarningText(List<String> fragments, List<String> words) {}

    private static Fails fails(List<String> fragments, String... words) {
        return new Fails(fragments, List.of(words));
    }

    private static Publishes publishesWithoutWarning(Consumer<Rendering> document) {
        return new Publishes(List.of(), document);
    }

    private static Publishes publishesWithOneWarning(
            List<String> fragments, List<String> words, Consumer<Rendering> document) {
        return new Publishes(List.of(new WarningText(fragments, words)), document);
    }

    /**
     * Assembles the public document of a publication with a fresh warning guard, after forgetting
     * every warning captured before.
     */
    private Outcome assemble(MountPublication publication) {
        capture.clear();
        return MetadataDocuments.assemble(publication, ApiDocs.Access.PUBLIC, MetadataDocuments.context());
    }

    /** Asserts an assembly's outcome and the warnings it logged. */
    private void assertOutcome(String label, Outcome outcome, Expected expected) {
        List<String> warnings = capture.warnings();
        for (String warning : warnings) {
            for (String value : UNECHOED) {
                assertFalse(warning.contains(value), () -> label + ": a warning echoes '" + value + "': " + warning);
            }
        }
        switch (expected) {
            case Fails failing -> {
                RestConfigurationException failure = outcome.failure();
                assertFailureNames(label, failure, failing.fragments(), failing.words());
                assertTrue(warnings.isEmpty(), () -> label + ": a refused document logged warnings: " + warnings);
            }
            case Publishes publishing -> {
                Rendering rendering = outcome.rendering();
                assertWarnings(label, warnings, publishing.warnings());
                publishing.document().accept(rendering);
            }
        }
    }

    private static void assertFailureNames(
            String label, RestConfigurationException failure, List<String> fragments, List<String> words) {
        String message = failure.getMessage();
        assertNotNull(message, () -> label + ": the failure has no message");
        assertTrue(message.contains(MOUNT), () -> label + ": the mount is not named: " + message);
        for (String fragment : fragments) {
            assertTrue(message.contains(fragment), () -> label + ": '" + fragment + "' is not named: " + message);
        }
        for (String word : words) {
            assertTrue(containsWord(message, word), () -> label + ": '" + word + "' is not named: " + message);
        }
        for (String value : UNECHOED) {
            assertFalse(message.contains(value), () -> label + ": the message echoes '" + value + "': " + message);
        }
    }

    /**
     * Asserts that the captured warnings are exactly as many as expected, that each starts with the
     * document's configuration path and names the mount, and that each expectation is met by a
     * distinct warning.
     */
    private static void assertWarnings(String label, List<String> actual, List<WarningText> expected) {
        assertEquals(expected.size(), actual.size(), () -> label + ": the warnings logged: " + actual);
        for (String warning : actual) {
            assertTrue(
                    warning.startsWith(WARNING_PREFIX),
                    () -> label + ": the warning does not start with '" + WARNING_PREFIX + "': " + warning);
            assertTrue(warning.contains(MOUNT), () -> label + ": the warning does not name the mount: " + warning);
        }
        if (!matchesDistinctly(expected, new ArrayList<>(actual))) {
            fail(label + ": the warnings " + actual + " do not each match one of " + expected);
        }
    }

    private static boolean matchesDistinctly(List<WarningText> expected, List<String> remaining) {
        if (expected.isEmpty()) {
            return true;
        }
        WarningText first = expected.get(0);
        for (int i = 0; i < remaining.size(); i++) {
            String candidate = remaining.get(i);
            if (matches(first, candidate)) {
                List<String> rest = new ArrayList<>(remaining);
                rest.remove(i);
                if (matchesDistinctly(expected.subList(1, expected.size()), rest)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matches(WarningText expected, String warning) {
        return expected.fragments().stream().allMatch(warning::contains)
                && expected.words().stream().allMatch(word -> containsWord(warning, word));
    }

    private static boolean containsWord(String text, String word) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(word) + "(?![A-Za-z0-9_])")
                .matcher(text)
                .find();
    }

    // ---------------------------------------------------------------------------------------------
    // Document navigation
    // ---------------------------------------------------------------------------------------------

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode json(JsonObject object) {
        return json(object.encode());
    }

    private static JsonNode operation(Rendering rendering, String path, String method) {
        return rendering.jsonTree().path("paths").path(path).path(method);
    }

    /** Returns the Parameter Object of the given name, or a missing node when none is published. */
    private static JsonNode parameter(Rendering rendering, String path, String method, String name) {
        JsonNode pathItem = rendering.jsonTree().path("paths").path(path);
        for (JsonNode holder : List.of(pathItem, pathItem.path(method))) {
            for (JsonNode parameter : holder.path("parameters")) {
                if (name.equals(parameter.path("name").asText(null))) {
                    return parameter;
                }
            }
        }
        return MissingNode.getInstance();
    }

    private static JsonNode searchParameter(Rendering rendering, String name) {
        return parameter(rendering, "/search", "get", name);
    }

    private static JsonNode createRequestBody(Rendering rendering) {
        return operation(rendering, "/items", "post").path("requestBody");
    }

    private static JsonNode submitRequestBody(Rendering rendering) {
        return operation(rendering, "/forms", "post").path("requestBody");
    }

    private static JsonNode component(Rendering rendering, String key) {
        return rendering.jsonTree().path("components").path("schemas").path(key);
    }

    private static JsonNode rootTags(Rendering rendering) {
        return rendering.jsonTree().path("tags");
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }

    /** Asserts that neither rendered form contains a text. */
    private static void assertAbsent(Rendering rendering, String text) {
        assertFalse(rendering.jsonText().contains(text), () -> "'" + text + "' in JSON: " + rendering.jsonText());
        assertFalse(rendering.yamlText().contains(text), () -> "'" + text + "' in YAML: " + rendering.yamlText());
    }

    private static void assertNoMember(JsonNode node, String member, String where) {
        assertTrue(node.isObject(), () -> where + " is not published: " + node);
        assertFalse(node.has(member), () -> where + " carries '" + member + "': " + node);
    }

    private static void assertMembersWithin(JsonNode node, Set<String> allowed, String where) {
        assertTrue(node.isObject(), () -> where + " is not published: " + node);
        for (String name : fieldNames(node)) {
            assertTrue(allowed.contains(name), () -> where + " carries '" + name + "': " + node);
        }
    }

    /** Asserts that the item body's generator bound no redaction pointer, so its published component is the captured schema. */
    private static void assertNothingToRedact(GeneratedBody body) {
        assertTrue(body.manifestIsEmpty(), () -> "the item body's manifest lists pointers: " + body.manifest());
    }

    // ---------------------------------------------------------------------------------------------
    // Synthetic publications
    // ---------------------------------------------------------------------------------------------

    private static JsonObject stringSchema() {
        return new JsonObject("{\"type\": \"string\"}");
    }

    private static JsonObject stringArraySchema() {
        return new JsonObject("{\"type\": \"array\", \"items\": {\"type\": \"string\"}}");
    }

    /** {@code GET /search} with one query parameter {@code q}, annotated from a fixture method. */
    private static Supplier<MountPublication> search(
            Class<?> fixture, String method, Requiredness requiredness, JsonObject captured) {
        return () -> MetadataPublications.from(MetadataPublications.mount()
                        .operation("GET", "/search", SEARCH)
                        .param(ParamLocation.QUERY, "q", requiredness)
                        .schema(captured)
                        .build())
                .annotate(SEARCH, fixture, method)
                .build();
    }

    /** {@code GET /items} without inputs, annotated from a fixture method. */
    private static Supplier<MountPublication> list(String method) {
        return () -> MetadataPublications.from(MetadataPublications.mount()
                        .operation("GET", "/items", LIST)
                        .build())
                .annotate(LIST, AgreementResource.class, method)
                .build();
    }

    /** {@code POST /items} consuming only JSON, with a generated body, annotated from a fixture method. */
    private static Supplier<MountPublication> create(String method, GeneratedBody body) {
        return create(AgreementResource.class, method, body);
    }

    /**
     * {@code POST /items} consuming only JSON, with a generated body, annotated from a method of the
     * given fixture class.
     */
    private static Supplier<MountPublication> create(Class<?> fixture, String method, GeneratedBody body) {
        return () -> MetadataPublications.from(MetadataPublications.mount()
                        .operation("POST", "/items", CREATE)
                        .consumes(JSON_MEDIA_TYPE)
                        .body(body)
                        .build())
                .annotate(CREATE, fixture, method)
                .build();
    }

    /**
     * {@code POST /forms} declaring no consumed media type, whose only input is the form field {@code
     * note}, annotated from a {@link FormResource} method.
     */
    private static Supplier<MountPublication> submit(String method) {
        return () -> MetadataPublications.from(MetadataPublications.mount()
                        .operation("POST", "/forms", SUBMIT)
                        .formField("note")
                        .schema(stringSchema())
                        .build())
                .annotate(SUBMIT, FormResource.class, method)
                .build();
    }

    // ---------------------------------------------------------------------------------------------
    // Behavioral attributes must agree with the runtime
    // ---------------------------------------------------------------------------------------------

    /**
     * One assembly and its expected outcome.
     *
     * @param publication builds the publication with descriptors attached
     * @param expected the expected outcome
     */
    record MetadataCase(Supplier<MountPublication> publication, Expected expected) {}

    private static Arguments row(String name, Supplier<MountPublication> publication, Expected expected) {
        return Arguments.of(Named.of(name, new MetadataCase(publication, expected)));
    }

    static Stream<Arguments> agreementCases() {
        return Stream.of(
                // Failing cases.
                row(
                        "(a) an operation id other than the runtime's fails",
                        list("otherOperationId"),
                        fails(List.of(LIST, "@Operation.operationId"))),
                row(
                        "(b) a parameter name other than the binding's fails",
                        search(AgreementResource.class, "renamedParameter", Requiredness.NOT_REQUIRED, stringSchema()),
                        fails(List.of(SEARCH, "@Parameter.name"), "q")),
                row(
                        "(c) a header location on a query parameter fails",
                        search(
                                AgreementResource.class,
                                "relocatedParameter",
                                Requiredness.NOT_REQUIRED,
                                stringSchema()),
                        fails(List.of(SEARCH, "@Parameter.in"), "q")),
                row(
                        "(d) a requirement on a query parameter the runtime does not require fails",
                        search(
                                AgreementResource.class,
                                "requiredOptionalParameter",
                                Requiredness.NOT_REQUIRED,
                                stringSchema()),
                        fails(List.of(SEARCH, "@Parameter.required"), "q")),
                row(
                        "(e) a requirement on a body whose schema accepts null fails",
                        create("requiredNullableBody", GeneratedBodies.describe(Object.class)),
                        fails(List.of(CREATE, "@RequestBody.required"))),
                row(
                        "(f) a body implementation other than the bound type fails",
                        create("otherBodyImplementation", GeneratedBodies.describe(ItemDto.class)),
                        fails(List.of(CREATE, "@RequestBody.content.schema.implementation"))),
                row(
                        "(k) a body media type the operation does not consume fails",
                        create("unconsumedBodyMediaType", GeneratedBodies.describe(ItemDto.class)),
                        fails(List.of(CREATE, "@RequestBody.content.mediaType"))),
                row(
                        "(l) parameter content fails",
                        search(AgreementResource.class, "parameterContent", Requiredness.NOT_REQUIRED, stringSchema()),
                        fails(List.of(SEARCH, "@Parameter.content"), "q")),
                row(
                        "(r) an element implementation other than the bound element type fails",
                        search(
                                AgreementResource.class,
                                "otherElementImplementation",
                                Requiredness.NOT_REQUIRED,
                                stringArraySchema()),
                        fails(List.of(SEARCH, "@Parameter.array"), "q")),
                // Warning cases.
                parameterMaxLengthIsIgnoredWithAWarning(),
                arrayMinItemsIsIgnoredWithAWarning(),
                requirementOfUnknownRequirednessIsWarnedAndNotPublished(),
                ignoredMembersOfTwoParametersShareOneWarning(),
                bodyMinPropertiesIsIgnoredWithAWarning(),
                // Controls.
                row(
                        "(g) the binding's own name and location publish",
                        search(
                                AgreementResource.class,
                                "agreeingNameAndLocation",
                                Requiredness.NOT_REQUIRED,
                                stringSchema()),
                        publishesWithoutWarning(rendering -> assertEquals(
                                json("{\"name\": \"q\", \"in\": \"query\", \"schema\": {\"type\": \"string\"}}"),
                                searchParameter(rendering, "q"),
                                "(g) the Parameter Object"))),
                requirementOnAPathParameterPublishes(),
                row(
                        "(i) the runtime's own operation id publishes",
                        list("sameOperationId"),
                        publishesWithoutWarning(rendering -> assertEquals(
                                LIST,
                                operation(rendering, "/items", "get")
                                        .path("operationId")
                                        .asText(null),
                                "(i) the operation id"))),
                row(
                        "(j) the bound type as the parameter's implementation publishes",
                        search(
                                AgreementResource.class,
                                "sameImplementation",
                                Requiredness.NOT_REQUIRED,
                                stringSchema()),
                        publishesWithoutWarning(rendering -> assertEquals(
                                json("{\"name\": \"q\", \"in\": \"query\", \"schema\": {\"type\": \"string\"}}"),
                                searchParameter(rendering, "q"),
                                "(j) the Parameter Object"))),
                schemaDocumentationFillsTheParameterObject(),
                parameterDescriptionWinsOverTheSchemaDescription(),
                bodySchemaDocumentationFillsTheRequestBodyAndMediaType(),
                bodyMediaTypeExamplesWinOverTheSchemaExample(),
                parameterExamplesWinOverTheSchemaExample(),
                row(
                        "(o) a requirement on a body whose schema rejects null publishes as required",
                        create("requiredBody", GeneratedBodies.describe(ItemDto.class)),
                        publishesWithoutWarning(rendering -> assertEquals(
                                json("true"),
                                createRequestBody(rendering).path("required"),
                                () -> "(o) the request body: " + createRequestBody(rendering)))),
                row(
                        "(p) a requirement on a required query parameter publishes as required",
                        search(
                                AgreementResource.class,
                                "requiredRequiredParameter",
                                Requiredness.REQUIRED,
                                stringSchema()),
                        publishesWithoutWarning(rendering -> assertEquals(
                                json("{\"name\": \"q\", \"in\": \"query\", \"required\": true,"
                                        + " \"schema\": {\"type\": \"string\"}}"),
                                searchParameter(rendering, "q"),
                                "(p) the Parameter Object"))),
                row(
                        "(q) the bound element type as the element implementation publishes",
                        search(
                                AgreementResource.class,
                                "sameElementImplementation",
                                Requiredness.NOT_REQUIRED,
                                stringArraySchema()),
                        publishesWithoutWarning(rendering -> assertEquals(
                                json("{\"name\": \"q\", \"in\": \"query\","
                                        + " \"schema\": {\"type\": \"array\", \"items\": {\"type\": \"string\"}}}"),
                                searchParameter(rendering, "q"),
                                "(q) the Parameter Object"))),
                // Warning scope.
                ignoredMembersOfTwoOperationsWarnOncePerOperation(),
                requirementsOfTwoUnknownParametersWarnOncePerParameter(),
                failureBesideAWarnedOperationLogsNoWarning(),
                // Request body sources.
                row(
                        "a method's request body description publishes",
                        create(
                                RequestBodySourceResource.class,
                                "methodDescription",
                                GeneratedBodies.describe(ItemDto.class)),
                        publishesWithoutWarning(rendering -> assertEquals(
                                "mZX",
                                createRequestBody(rendering).path("description").asText(null),
                                () -> "the request body: " + createRequestBody(rendering)))),
                row(
                        "an operation's request body description publishes",
                        create(
                                RequestBodySourceResource.class,
                                "operationDescription",
                                GeneratedBodies.describe(ItemDto.class)),
                        publishesWithoutWarning(rendering -> assertEquals(
                                "oZX",
                                createRequestBody(rendering).path("description").asText(null),
                                () -> "the request body: " + createRequestBody(rendering)))),
                row(
                        "the body parameter's request body wins over the method's, whose description appears nowhere",
                        create(
                                RequestBodySourceResource.class,
                                "parameterAndMethodDescriptions",
                                GeneratedBodies.describe(ItemDto.class)),
                        publishesWithoutWarning(rendering -> {
                            assertEquals(
                                    "pZX",
                                    createRequestBody(rendering)
                                            .path("description")
                                            .asText(null),
                                    () -> "the request body: " + createRequestBody(rendering));
                            assertAbsent(rendering, "mZX");
                        })),
                // Form fields and the form request body.
                row(
                        "a query location on a form field fails",
                        submit("fieldQueryLocation"),
                        fails(List.of(SUBMIT, "@Parameter.in", "form field note"))),
                row(
                        "a schema implementation on a form request body fails",
                        submit("bodyImplementation"),
                        fails(List.of(SUBMIT, "@RequestBody.content.schema.implementation", "request body"))),
                row(
                        "a requirement on a form request body fails",
                        submit("requiredBody"),
                        fails(List.of(SUBMIT, "@RequestBody.required", "request body"))),
                requirementOnAFormFieldOfUnknownRequirednessIsWarnedAndNotPublished(),
                row(
                        "a form request body's description publishes, and no example is published on it",
                        submit("describedBody"),
                        publishesWithoutWarning(rendering -> {
                            JsonNode requestBody = submitRequestBody(rendering);
                            assertEquals(
                                    "Form body",
                                    requestBody.path("description").asText(null),
                                    () -> "the request body: " + requestBody);
                            assertTrue(
                                    requestBody
                                            .path("content")
                                            .path(FORM_MEDIA_TYPE)
                                            .isObject(),
                                    () -> "the form media type is not published: " + requestBody);
                            assertNoExample(requestBody, "the form request body");
                        })));
    }

    /** Asserts that no member named {@code example} or {@code examples} appears anywhere in a node. */
    private static void assertNoExample(JsonNode node, String where) {
        assertTrue(node.findValues("example").isEmpty(), () -> where + " carries an example: " + node);
        assertTrue(node.findValues("examples").isEmpty(), () -> where + " carries examples: " + node);
    }

    private static Arguments ignoredMembersOfTwoOperationsWarnOncePerOperation() {
        // Given: two operations, each with an ignored schema member on its query parameter q.
        JsonObject capturedSearch = stringSchema();
        JsonObject capturedLookup = stringSchema();
        Supplier<MountPublication> publication = () -> MetadataPublications.from(MetadataPublications.mount()
                        .operation("GET", "/search", SEARCH)
                        .param(ParamLocation.QUERY, "q", Requiredness.NOT_REQUIRED)
                        .schema(capturedSearch)
                        .operation("GET", "/lookup", LOOKUP)
                        .param(ParamLocation.QUERY, "q", Requiredness.NOT_REQUIRED)
                        .schema(capturedLookup)
                        .build())
                .annotate(SEARCH, AgreementResource.class, "parameterMaxLength")
                .annotate(LOOKUP, AgreementResource.class, "parameterMaxLength")
                .build();
        return row(
                "ignored members in two operations are warned about once per operation",
                publication,
                new Publishes(
                        List.of(
                                new WarningText(List.of(SEARCH, "@Parameter.schema.maxLength"), List.of("q")),
                                new WarningText(List.of(LOOKUP, "@Parameter.schema.maxLength"), List.of("q"))),
                        rendering -> {
                            assertEquals(
                                    json(capturedSearch),
                                    searchParameter(rendering, "q").path("schema"),
                                    "q of /search");
                            assertEquals(
                                    json(capturedLookup),
                                    parameter(rendering, "/lookup", "get", "q").path("schema"),
                                    "q of /lookup");
                            assertAbsent(rendering, "maxLength");
                        }));
    }

    private static Arguments requirementsOfTwoUnknownParametersWarnOncePerParameter() {
        // Given: one operation whose primitive int query parameters q and r both have unknown requiredness.
        JsonObject capturedQ = new JsonObject("{\"type\": \"integer\"}");
        JsonObject capturedR = new JsonObject("{\"type\": \"integer\"}");
        Supplier<MountPublication> publication = () -> MetadataPublications.from(MetadataPublications.mount()
                        .operation("GET", "/search", SEARCH)
                        .param(ParamLocation.QUERY, "q", Requiredness.UNKNOWN)
                        .schema(capturedQ)
                        .param(ParamLocation.QUERY, "r", Requiredness.UNKNOWN)
                        .schema(capturedR)
                        .build())
                .annotate(SEARCH, WarningScopeResource.class, "twoUnknownRequirements")
                .build();
        return row(
                "requirements on two parameters of unknown requiredness are warned about once per parameter",
                publication,
                new Publishes(
                        List.of(
                                new WarningText(List.of(SEARCH, "@Parameter.required"), List.of("q")),
                                new WarningText(List.of(SEARCH, "@Parameter.required"), List.of("r"))),
                        rendering -> {
                            assertNoMember(searchParameter(rendering, "q"), "required", "the Parameter Object q");
                            assertNoMember(searchParameter(rendering, "r"), "required", "the Parameter Object r");
                        }));
    }

    private static Arguments failureBesideAWarnedOperationLogsNoWarning() {
        // Given: GET /a would warn about an ignored member of q; GET /b, checked after it, renames q.
        Supplier<MountPublication> publication = () -> MetadataPublications.from(MetadataPublications.mount()
                        .operation("GET", "/a", WARNED)
                        .param(ParamLocation.QUERY, "q", Requiredness.NOT_REQUIRED)
                        .schema(stringSchema())
                        .operation("GET", "/b", REFUSED)
                        .param(ParamLocation.QUERY, "q", Requiredness.NOT_REQUIRED)
                        .schema(stringSchema())
                        .build())
                .annotate(WARNED, AgreementResource.class, "parameterMaxLength")
                .annotate(REFUSED, WarningScopeResource.class, "otherParameterName")
                .build();
        return row(
                "a document refused after another operation's warning logs no warning",
                publication,
                fails(List.of(REFUSED, "@Parameter.name"), "q"));
    }

    private static Arguments requirementOnAFormFieldOfUnknownRequirednessIsWarnedAndNotPublished() {
        // Given: a primitive int form field, whose requiredness the runtime leaves unknown.
        JsonObject captured = new JsonObject("{\"type\": \"integer\"}");
        Supplier<MountPublication> publication = () -> MetadataPublications.from(MetadataPublications.mount()
                        .operation("POST", "/forms", SUBMIT)
                        .formField("count", Requiredness.UNKNOWN)
                        .schema(captured)
                        .build())
                .annotate(SUBMIT, FormResource.class, "requiredUnknownField")
                .build();
        return row(
                "a requirement on a form field of unknown requiredness is warned about and not published",
                publication,
                publishesWithOneWarning(
                        List.of(SUBMIT, "@Parameter.required", "form field count"), List.of(), rendering -> {
                            JsonNode requestBody = submitRequestBody(rendering);
                            assertNoMember(requestBody, "required", "the form request body");
                            JsonNode schema = requestBody
                                    .path("content")
                                    .path(FORM_MEDIA_TYPE)
                                    .path("schema");
                            assertFalse(schema.has("required"), () -> "the form schema requires a field: " + schema);
                            assertEquals(
                                    json(captured), schema.path("properties").path("count"), "the count property");
                        }));
    }

    private static Arguments parameterMaxLengthIsIgnoredWithAWarning() {
        // Given: q's captured schema has its own maxLength, which the annotation contradicts.
        JsonObject captured = new JsonObject("{\"type\": \"string\", \"maxLength\": 12}");
        return row(
                "(m) a schema-shaping member of a parameter's schema is ignored with one warning",
                search(AgreementResource.class, "parameterMaxLength", Requiredness.NOT_REQUIRED, captured),
                publishesWithOneWarning(List.of(SEARCH, "@Parameter.schema.maxLength"), List.of("q"), rendering -> {
                    assertEquals(
                            json(captured),
                            searchParameter(rendering, "q").path("schema"),
                            "(m) q's published schema, maxLength 12 included");
                    assertFalse(
                            rendering.jsonText().replace(" ", "").contains("\"maxLength\":5"),
                            () -> "(m) maxLength 5 is published: " + rendering.jsonText());
                }));
    }

    private static Arguments arrayMinItemsIsIgnoredWithAWarning() {
        JsonObject captured = stringArraySchema();
        return row(
                "(s) an array member other than its element schema is ignored with one warning",
                search(AgreementResource.class, "arrayMinItems", Requiredness.NOT_REQUIRED, captured),
                publishesWithOneWarning(List.of(SEARCH, "@Parameter.array.minItems"), List.of(), rendering -> {
                    assertEquals(json(captured), searchParameter(rendering, "q").path("schema"), "(s) q's schema");
                    assertAbsent(rendering, "minItems");
                }));
    }

    private static Arguments requirementOfUnknownRequirednessIsWarnedAndNotPublished() {
        // Given: a primitive int query parameter, whose requiredness the runtime leaves unknown.
        JsonObject captured = new JsonObject("{\"type\": \"integer\"}");
        return row(
                "(t) a requirement on a parameter of unknown requiredness is warned about and not published",
                search(AgreementResource.class, "requiredUnknownParameter", Requiredness.UNKNOWN, captured),
                publishesWithOneWarning(List.of(SEARCH, "@Parameter.required"), List.of("q"), rendering -> {
                    JsonNode q = searchParameter(rendering, "q");
                    assertNoMember(q, "required", "(t) the Parameter Object");
                    assertEquals(json(captured), q.path("schema"), "(t) q's schema");
                }));
    }

    private static Arguments ignoredMembersOfTwoParametersShareOneWarning() {
        // Given: one operation with ignored schema members on its query parameters q and r.
        JsonObject capturedQ = stringSchema();
        JsonObject capturedR = stringSchema();
        Supplier<MountPublication> publication = () -> MetadataPublications.from(MetadataPublications.mount()
                        .operation("GET", "/search", SEARCH)
                        .param(ParamLocation.QUERY, "q", Requiredness.NOT_REQUIRED)
                        .schema(capturedQ)
                        .param(ParamLocation.QUERY, "r", Requiredness.NOT_REQUIRED)
                        .schema(capturedR)
                        .build())
                .annotate(SEARCH, AgreementResource.class, "twoIgnoredMembers")
                .build();
        return row(
                "(u) ignored members of two parameters of one operation share one warning",
                publication,
                publishesWithOneWarning(
                        List.of(SEARCH, "@Parameter.schema.maxLength", "@Parameter.schema.format"),
                        List.of("q", "r"),
                        rendering -> {
                            assertEquals(
                                    json(capturedQ),
                                    searchParameter(rendering, "q").path("schema"),
                                    "(u) q");
                            assertEquals(
                                    json(capturedR),
                                    searchParameter(rendering, "r").path("schema"),
                                    "(u) r");
                            assertAbsent(rendering, "maxLength");
                            assertAbsent(rendering, "format");
                        }));
    }

    private static Arguments bodyMinPropertiesIsIgnoredWithAWarning() {
        GeneratedBody body = GeneratedBodies.describe(ItemDto.class);
        JsonObject captured = body.schema().copy();
        return row(
                "(v) a schema-shaping member of the body's schema is ignored with one warning",
                create("bodyMinProperties", body),
                publishesWithOneWarning(
                        List.of(CREATE, "@RequestBody.content.schema.minProperties"), List.of(), rendering -> {
                            assertNothingToRedact(body);
                            assertEquals(json(captured), component(rendering, CREATE_COMPONENT), "(v) the component");
                            assertAbsent(rendering, "minProperties");
                        }));
    }

    private static Arguments requirementOnAPathParameterPublishes() {
        Supplier<MountPublication> publication = () -> MetadataPublications.from(MetadataPublications.mount()
                        .operation("GET", "/items/{id}", READ)
                        .param(ParamLocation.PATH, "id", Requiredness.REQUIRED)
                        .schema(stringSchema())
                        .build())
                .annotate(READ, AgreementResource.class, "requiredPathParameter")
                .build();
        return row(
                "(h) a requirement on a path parameter publishes",
                publication,
                publishesWithoutWarning(rendering -> assertEquals(
                        json("{\"name\": \"id\", \"in\": \"path\", \"required\": true,"
                                + " \"schema\": {\"type\": \"string\"}}"),
                        parameter(rendering, "/items/{id}", "get", "id"),
                        "(h) the Parameter Object")));
    }

    private static Arguments schemaDocumentationFillsTheParameterObject() {
        JsonObject captured = stringSchema();
        return row(
                "(n) the parameter schema's description and example fill the Parameter Object, its title is not published",
                search(AgreementResource.class, "parameterSchemaDocumentation", Requiredness.NOT_REQUIRED, captured),
                publishesWithoutWarning(rendering -> {
                    JsonNode q = searchParameter(rendering, "q");
                    assertEquals("dVALUEZX", q.path("description").asText(null), () -> "(n) description: " + q);
                    assertEquals(json("\"eVALUEZX\""), q.path("example"), () -> "(n) example: " + q);
                    assertEquals(json(captured), q.path("schema"), "(n) q's schema");
                    assertAbsent(rendering, "tVALUEZX");
                }));
    }

    private static Arguments parameterDescriptionWinsOverTheSchemaDescription() {
        JsonObject captured = stringSchema();
        return row(
                "(w) the parameter's description wins over its schema's, and the schema's deprecation fills the Parameter Object",
                search(AgreementResource.class, "parameterDescriptionWins", Requiredness.NOT_REQUIRED, captured),
                publishesWithoutWarning(rendering -> {
                    JsonNode q = searchParameter(rendering, "q");
                    assertEquals("pVALUEZX", q.path("description").asText(null), () -> "(w) description: " + q);
                    assertEquals(json("true"), q.path("deprecated"), () -> "(w) deprecated: " + q);
                    assertEquals(json(captured), q.path("schema"), "(w) q's schema");
                    assertAbsent(rendering, "dVALUEZX");
                    assertAbsent(rendering, "xVALUEZX");
                }));
    }

    private static Arguments bodySchemaDocumentationFillsTheRequestBodyAndMediaType() {
        GeneratedBody body = GeneratedBodies.describe(ItemDto.class);
        JsonObject captured = body.schema().copy();
        return row(
                "(x) the body schema's description fills the request body and its example the media type, never a deprecation",
                create("bodySchemaDocumentation", body),
                publishesWithoutWarning(rendering -> {
                    JsonNode requestBody = createRequestBody(rendering);
                    JsonNode mediaType = requestBody.path("content").path(JSON_MEDIA_TYPE);
                    assertEquals(
                            "bVALUEZX",
                            requestBody.path("description").asText(null),
                            () -> "(x) description: " + requestBody);
                    assertEquals(json("\"beVALUEZX\""), mediaType.path("example"), () -> "(x) example: " + mediaType);
                    assertNoMember(requestBody, "deprecated", "(x) the request body");
                    assertNoMember(mediaType, "deprecated", "(x) the media type");
                    assertMembersWithin(requestBody, REQUEST_BODY_MEMBERS, "(x) the request body");
                    assertNothingToRedact(body);
                    assertEquals(json(captured), component(rendering, CREATE_COMPONENT), "(x) the component");
                    assertAbsent(rendering, "btVALUEZX");
                }));
    }

    private static Arguments bodyMediaTypeExamplesWinOverTheSchemaExample() {
        GeneratedBody body = GeneratedBodies.describe(ItemDto.class);
        JsonObject captured = body.schema().copy();
        return row(
                "(z) a content entry's examples win over its schema's example on the media type",
                create("bodyExamplesWin", body),
                publishesWithoutWarning(rendering -> {
                    JsonNode requestBody = createRequestBody(rendering);
                    JsonNode mediaType = requestBody.path("content").path(JSON_MEDIA_TYPE);
                    assertEquals(
                            json("{\"b1\": {\"value\": {\"a\": 1}}}"),
                            mediaType.path("examples"),
                            () -> "(z) examples: " + mediaType);
                    assertNoMember(mediaType, "example", "(z) the media type");
                    assertMembersWithin(requestBody, REQUEST_BODY_MEMBERS, "(z) the request body");
                    assertNothingToRedact(body);
                    assertEquals(json(captured), component(rendering, CREATE_COMPONENT), "(z) the component");
                    assertAbsent(rendering, "ezVALUEZX");
                }));
    }

    private static Arguments parameterExamplesWinOverTheSchemaExample() {
        JsonObject captured = stringSchema();
        return row(
                "(y) a parameter's examples win over its schema's example",
                search(AgreementResource.class, "parameterExamplesWin", Requiredness.NOT_REQUIRED, captured),
                publishesWithoutWarning(rendering -> {
                    JsonNode q = searchParameter(rendering, "q");
                    assertEquals(json("{\"e1\": {\"value\": 1}}"), q.path("examples"), () -> "(y) examples: " + q);
                    assertNoMember(q, "example", "(y) the Parameter Object");
                    assertEquals(json(captured), q.path("schema"), "(y) q's schema");
                    assertAbsent(rendering, "eVALUEZX");
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("agreementCases")
    @DisplayName("Behavioral attributes must agree with the runtime; other schema members are ignored with a warning")
    void behavioralAttributesMustAgreeWithTheRuntime(MetadataCase row) {
        // Given: the case's publication, its bindings carrying the fixture method's annotations.
        MountPublication publication = row.publication().get();

        // When: the public document is assembled.
        Outcome outcome = assemble(publication);

        // Then: the expected failure, or the document with exactly the expected warnings.
        assertOutcome("the case", outcome, row.expected());
    }

    // ---------------------------------------------------------------------------------------------
    // Conflicting tags fail and examples follow their rules
    // ---------------------------------------------------------------------------------------------

    /** Two operations without inputs, {@code GET /first} and {@code GET /second}, each with tags. */
    private static Supplier<MountPublication> twoTagged(
            String firstOperation, String firstMethod, String secondOperation, String secondMethod) {
        return () -> MetadataPublications.from(MetadataPublications.mount()
                        .operation("GET", "/first", firstOperation)
                        .operation("GET", "/second", secondOperation)
                        .build())
                .annotate(firstOperation, TaggedResource.class, firstMethod)
                .annotate(secondOperation, TaggedResource.class, secondMethod)
                .build();
    }

    static Stream<Arguments> tagAndExampleCases() {
        return Stream.of(
                row(
                        "(a) one tag declared with two different descriptions fails",
                        twoTagged("listA", "sharedFirst", "listB", "sharedSecond"),
                        fails(List.of("shared", "listA", "listB"))),
                row(
                        "(b) a tag declared once without and once with a description publishes the description",
                        twoTagged("listPlain", "plainUndescribed", "listPlainDescribed", "plainDescribed"),
                        publishesWithoutWarning(rendering -> assertEquals(
                                json("[{\"name\": \"plain\", \"description\": \"Plain tag\"}]"),
                                rootTags(rendering),
                                "(b) the root tags"))),
                row(
                        "(c) an example text that is JSON publishes as JSON",
                        search(ExampleResource.class, "jsonExample", Requiredness.NOT_REQUIRED, stringSchema()),
                        publishesWithoutWarning(rendering -> assertEquals(
                                json("{\"a\": 1}"),
                                searchParameter(rendering, "q").path("example"),
                                "(c) the example"))),
                row(
                        "(d) an example text that is not JSON publishes as a string",
                        search(ExampleResource.class, "textExample", Requiredness.NOT_REQUIRED, stringSchema()),
                        publishesWithoutWarning(rendering -> assertEquals(
                                json("\"plain text\""),
                                searchParameter(rendering, "q").path("example"),
                                "(d) the example"))),
                row(
                        "(e) named examples win over an example",
                        search(ExampleResource.class, "exampleAndExamples", Requiredness.NOT_REQUIRED, stringSchema()),
                        publishesWithoutWarning(rendering -> {
                            JsonNode q = searchParameter(rendering, "q");
                            assertEquals(
                                    json("{\"n\": {\"summary\": \"Sum\", \"value\": [1, 2]}}"),
                                    q.path("examples"),
                                    () -> "(e) examples: " + q);
                            assertNoMember(q, "example", "(e) the Parameter Object");
                        })),
                row(
                        "(f) a named example with a blank name fails",
                        search(ExampleResource.class, "blankExampleName", Requiredness.NOT_REQUIRED, stringSchema()),
                        fails(List.of(SEARCH, "@ExampleObject.name"), "q")),
                row(
                        "(g) a named example with both a value and an external value fails",
                        search(
                                ExampleResource.class,
                                "valueAndExternalValue",
                                Requiredness.NOT_REQUIRED,
                                stringSchema()),
                        fails(List.of(SEARCH, "@ExampleObject.value", "externalValue"), "q")),
                row(
                        "(h) two named examples sharing a name fail",
                        search(
                                ExampleResource.class,
                                "duplicateExampleNames",
                                Requiredness.NOT_REQUIRED,
                                stringSchema()),
                        fails(List.of(SEARCH, "@ExampleObject.name"), "q")),
                row(
                        "a form field's named example referencing a reusable example fails",
                        submit("fieldExampleReference"),
                        fails(List.of(SUBMIT, "@ExampleObject.ref", "form field note"))),
                row(
                        "a form request body's named example with a blank name fails",
                        submit("bodyBlankExampleName"),
                        fails(List.of(SUBMIT, "@ExampleObject.name", "request body"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tagAndExampleCases")
    @DisplayName("Conflicting tag declarations fail, and examples are parsed, preferred, and validated")
    void conflictingTagsFailAndExamplesFollowTheirRules(MetadataCase row) {
        // Given: the case's publication, its operations and bindings carrying the fixture annotations.
        MountPublication publication = row.publication().get();

        // When: the public document is assembled.
        Outcome outcome = assemble(publication);

        // Then: the expected failure, or the expected tags and examples.
        assertOutcome("the case", outcome, row.expected());
    }

    // ---------------------------------------------------------------------------------------------
    // Hidden content is removed before every check of the document
    // ---------------------------------------------------------------------------------------------

    /**
     * Hidden content paired with the check it would fail if it were published.
     *
     * @param hidden builds the publication carrying the hidden marker
     * @param hiddenDocument the assertions on the hidden case's rendering
     * @param control builds the same publication without the hidden marker
     * @param controlExpected the control's expected outcome
     */
    record HiddenPair(
            Supplier<MountPublication> hidden,
            Consumer<Rendering> hiddenDocument,
            Supplier<MountPublication> control,
            Expected controlExpected) {}

    private static Arguments pair(
            String name,
            Supplier<MountPublication> hidden,
            Consumer<Rendering> hiddenDocument,
            Supplier<MountPublication> control,
            Expected controlExpected) {
        return Arguments.of(Named.of(name, new HiddenPair(hidden, hiddenDocument, control, controlExpected)));
    }

    /**
     * Builds a publication whose operation {@code operationId} is annotated from the given fixture
     * method, after the builder's operations are added.
     */
    private static Supplier<MountPublication> annotated(
            Supplier<Publications.Built> source, String operationId, Class<?> fixture, String method) {
        return () -> MetadataPublications.from(source.get())
                .annotate(operationId, fixture, method)
                .build();
    }

    /** Asserts that an operation id appears nowhere and its path is not published. */
    private static void assertOperationAbsent(Rendering rendering, String operationId, String path) {
        assertAbsent(rendering, operationId);
        assertFalse(
                rendering.jsonTree().path("paths").has(path),
                () -> "the path '" + path + "' is published: " + rendering.jsonText());
    }

    static Stream<Arguments> hiddenFirstCases() {
        return Stream.of(
                renderedPathCollision(),
                componentKeyCollision(),
                bodyWithoutProvenance(),
                parameterPropertyNames(),
                externalReference(),
                hiddenPathParameter(),
                operationIdDisagreement(),
                tagConflict(),
                hiddenInputDisagreement(),
                hiddenBodyMember(),
                warnings(),
                interfaceHiddenOperation());
    }

    private static Arguments renderedPathCollision() {
        String hiddenId = "getItemByNumberZx";
        String visibleId = "getItemBySlug";
        Supplier<Publications.Built> source = () -> MetadataPublications.mount()
                .operation("GET", "/items/{id: \\d+}", hiddenId)
                .operation("GET", "/items/{id: [a-z]+}", visibleId)
                .build();
        return pair(
                "(a) a hidden operation whose path renders like a visible operation's",
                annotated(source, hiddenId, HiddenFirstResource.class, "renderedPathHidden"),
                rendering -> {
                    assertAbsent(rendering, hiddenId);
                    assertEquals(
                            visibleId,
                            operation(rendering, "/items/{id}", "get")
                                    .path("operationId")
                                    .asText(null),
                            "(a) the operation under /items/{id}");
                },
                annotated(source, hiddenId, HiddenFirstResource.class, "renderedPathVisible"),
                fails(List.of(hiddenId, visibleId, "'/items/{id}'")));
    }

    private static Arguments componentKeyCollision() {
        String hiddenId = "get:item";
        String visibleId = "get_item";
        Supplier<Publications.Built> source = () -> MetadataPublications.mount()
                .operation("POST", "/items/a", hiddenId)
                .consumes(JSON_MEDIA_TYPE)
                .body(GeneratedBodies.describe(ItemDto.class))
                .operation("POST", "/items/b", visibleId)
                .consumes(JSON_MEDIA_TYPE)
                .body(GeneratedBodies.describe(ItemDto.class))
                .build();
        return pair(
                "(b) a hidden operation whose body component key equals a visible operation's",
                annotated(source, hiddenId, HiddenFirstResource.class, "componentKeyHidden"),
                rendering -> {
                    assertOperationAbsent(rendering, hiddenId, "/items/a");
                    assertEquals(
                            visibleId,
                            operation(rendering, "/items/b", "post")
                                    .path("operationId")
                                    .asText(null),
                            "(b) the visible operation");
                    assertTrue(
                            component(rendering, visibleId + ".request").isObject(),
                            () -> "(b) the visible operation's component is missing: " + rendering.jsonText());
                },
                annotated(source, hiddenId, HiddenFirstResource.class, "componentKeyVisible"),
                fails(List.of(hiddenId, visibleId)));
    }

    private static Arguments bodyWithoutProvenance() {
        String hiddenId = "createNoteZx";
        Supplier<Publications.Built> source = () -> MetadataPublications.mount()
                .operation("POST", "/notes", hiddenId)
                .consumes(JSON_MEDIA_TYPE)
                .bodySchema(GeneratedBodies.describe(ItemDto.class).schema(), null)
                .build();
        return pair(
                "(c) a hidden operation whose captured body has no provenance",
                annotated(source, hiddenId, HiddenFirstResource.class, "unverifiedBodyHidden"),
                rendering -> assertOperationAbsent(rendering, hiddenId, "/notes"),
                annotated(source, hiddenId, HiddenFirstResource.class, "unverifiedBodyVisible"),
                fails(List.of(hiddenId, "request body", "carries no redaction manifest matching its content")));
    }

    private static Arguments parameterPropertyNames() {
        String hiddenId = "filterItemsZx";
        Supplier<Publications.Built> source = () -> MetadataPublications.mount()
                .operation("GET", "/filter", hiddenId)
                .param(ParamLocation.QUERY, "filter", Requiredness.NOT_REQUIRED)
                .schema(new JsonObject("{\"type\": \"object\", \"propertyNames\": {\"maxLength\": 3}}"))
                .build();
        return pair(
                "(d) a hidden operation whose query parameter's schema holds propertyNames",
                annotated(source, hiddenId, HiddenFirstResource.class, "propertyNamesHidden"),
                rendering -> {
                    assertOperationAbsent(rendering, hiddenId, "/filter");
                    assertAbsent(rendering, "propertyNames");
                },
                annotated(source, hiddenId, HiddenFirstResource.class, "propertyNamesVisible"),
                fails(List.of(hiddenId, "'filter'", "propertyNames")));
    }

    private static Arguments externalReference() {
        String hiddenId = "fetchItemsZx";
        Supplier<Publications.Built> source = () -> MetadataPublications.mount()
                .operation("GET", "/fetch", hiddenId)
                .param(ParamLocation.QUERY, "source", Requiredness.NOT_REQUIRED)
                .schema(new JsonObject("{\"$ref\": \"https://schemas.example.test/a.json\"}"))
                .build();
        return pair(
                "(e) a hidden operation whose query parameter's schema references another document",
                annotated(source, hiddenId, HiddenFirstResource.class, "externalReferenceHidden"),
                rendering -> {
                    assertOperationAbsent(rendering, hiddenId, "/fetch");
                    assertAbsent(rendering, "schemas.example.test");
                },
                annotated(source, hiddenId, HiddenFirstResource.class, "externalReferenceVisible"),
                fails(List.of(hiddenId, "'source'", "has a '$ref' that is not fragment-only")));
    }

    private static Arguments hiddenPathParameter() {
        String hiddenId = "readSecretZx";
        Supplier<Publications.Built> source = () -> MetadataPublications.mount()
                .operation("GET", "/secret/{id}", hiddenId)
                .param(ParamLocation.PATH, "id", Requiredness.REQUIRED)
                .schema(stringSchema())
                .build();
        Supplier<MountPublication> hidden = () -> MetadataPublications.from(source.get())
                .annotate(hiddenId, HiddenFirstResource.class, "hiddenPathHidden")
                .hidden(hiddenId, ParamLocation.PATH, "id")
                .build();
        Supplier<MountPublication> control = () -> MetadataPublications.from(source.get())
                .annotate(hiddenId, HiddenFirstResource.class, "hiddenPathVisible")
                .hidden(hiddenId, ParamLocation.PATH, "id")
                .build();
        return pair(
                "(f) a hidden operation whose path parameter is flagged hidden",
                hidden,
                rendering -> assertOperationAbsent(rendering, hiddenId, "/secret/{id}"),
                control,
                fails(List.of(hiddenId, "hides its path parameter 'id'")));
    }

    private static Arguments operationIdDisagreement() {
        String hiddenId = "listReportsZx";
        Supplier<Publications.Built> source = () -> MetadataPublications.mount()
                .operation("GET", "/reports", hiddenId)
                .build();
        return pair(
                "(g) a hidden operation declaring an operation id other than the runtime's",
                annotated(source, hiddenId, HiddenFirstResource.class, "otherOperationIdHidden"),
                rendering -> assertOperationAbsent(rendering, hiddenId, "/reports"),
                annotated(source, hiddenId, HiddenFirstResource.class, "otherOperationIdVisible"),
                fails(List.of(hiddenId, "@Operation.operationId")));
    }

    private static Arguments tagConflict() {
        String hiddenId = "listHiddenTagZx";
        String visibleId = "listVisibleTag";
        Supplier<Publications.Built> source = () -> MetadataPublications.mount()
                .operation("GET", "/tags/hidden", hiddenId)
                .operation("GET", "/tags/visible", visibleId)
                .build();
        Supplier<MountPublication> hidden = () -> MetadataPublications.from(source.get())
                .annotate(hiddenId, HiddenFirstResource.class, "sharedTagHidden")
                .annotate(visibleId, HiddenFirstResource.class, "sharedTagOther")
                .build();
        Supplier<MountPublication> control = () -> MetadataPublications.from(source.get())
                .annotate(hiddenId, HiddenFirstResource.class, "sharedTagVisible")
                .annotate(visibleId, HiddenFirstResource.class, "sharedTagOther")
                .build();
        return pair(
                "(h) a hidden operation declaring a tag a visible operation declares differently",
                hidden,
                rendering -> {
                    assertOperationAbsent(rendering, hiddenId, "/tags/hidden");
                    assertEquals(
                            json("[{\"name\": \"shared\", \"description\": \"B\"}]"),
                            rootTags(rendering),
                            "(h) the root tags carry only the visible declaration");
                },
                control,
                fails(List.of("shared", hiddenId, visibleId)));
    }

    private static Arguments hiddenInputDisagreement() {
        String operationId = "inspectItems";
        Supplier<Publications.Built> source = () -> MetadataPublications.mount()
                .operation("GET", "/inspect", operationId)
                .param(ParamLocation.QUERY, "debug", Requiredness.NOT_REQUIRED)
                .schema(stringSchema())
                .build();
        Supplier<MountPublication> hidden = () -> MetadataPublications.from(source.get())
                .annotate(operationId, HiddenFirstResource.class, "hiddenInputDisagrees")
                .hidden(operationId, ParamLocation.QUERY, "debug")
                .build();
        Supplier<MountPublication> control =
                annotated(source, operationId, HiddenFirstResource.class, "hiddenInputDisagrees");
        return pair(
                "(i) a hidden query parameter of a visible operation declaring another name and location",
                hidden,
                rendering -> {
                    assertEquals(
                            operationId,
                            operation(rendering, "/inspect", "get")
                                    .path("operationId")
                                    .asText(null),
                            "(i) the visible operation");
                    assertAbsent(rendering, "debug");
                },
                control,
                fails(List.of(operationId, "@Parameter."), "debug"));
    }

    private static Arguments hiddenBodyMember() {
        String hiddenId = "createAccountZx";
        Supplier<Publications.Built> source = () -> MetadataPublications.mount()
                .operation("POST", "/accounts", hiddenId)
                .consumes(JSON_MEDIA_TYPE)
                .body(GeneratedBodies.describe(AccountHiddenFieldZx.class))
                .build();
        return pair(
                "(j) a hidden operation whose body type has a @Hidden member",
                annotated(source, hiddenId, HiddenFirstResource.class, "hiddenMemberHidden"),
                rendering -> {
                    assertOperationAbsent(rendering, hiddenId, "/accounts");
                    assertAbsent(rendering, "backdoorZx");
                },
                annotated(source, hiddenId, HiddenFirstResource.class, "hiddenMemberVisible"),
                fails(List.of(hiddenId, "'backdoorZx'", "@Hidden")));
    }

    private static Arguments warnings() {
        String hiddenId = "countItemsZx";
        JsonObject captured = new JsonObject("{\"type\": \"integer\"}");
        Supplier<Publications.Built> source = () -> MetadataPublications.mount()
                .operation("GET", "/count", hiddenId)
                .param(ParamLocation.QUERY, "q", Requiredness.UNKNOWN)
                .schema(captured)
                .build();
        return pair(
                "(k) a hidden operation whose parameter would be warned about twice",
                annotated(source, hiddenId, HiddenFirstResource.class, "warnedHidden"),
                rendering -> assertOperationAbsent(rendering, hiddenId, "/count"),
                annotated(source, hiddenId, HiddenFirstResource.class, "warnedVisible"),
                new Publishes(
                        List.of(
                                new WarningText(List.of(hiddenId, "@Parameter.schema.maxLength"), List.of("q")),
                                new WarningText(List.of(hiddenId), List.of("q"))),
                        rendering -> {
                            JsonNode q = parameter(rendering, "/count", "get", "q");
                            assertNoMember(q, "required", "(k) the Parameter Object");
                            assertEquals(json(captured), q.path("schema"), "(k) q's schema");
                        }));
    }

    private static Arguments interfaceHiddenOperation() {
        String hiddenId = "getReportByNumberZx";
        String visibleId = "getReportBySlug";
        Supplier<Publications.Built> source = () -> MetadataPublications.mount()
                .operation("GET", "/reports/{id: \\d+}", hiddenId)
                .operation("GET", "/reports/{id: [a-z]+}", visibleId)
                .build();
        return pair(
                "an implementation's own @Operation does not unhide an operation its interface method hides",
                annotated(source, hiddenId, HiddenContractResource.class, "report"),
                rendering -> {
                    assertAbsent(rendering, hiddenId);
                    assertEquals(
                            visibleId,
                            operation(rendering, "/reports/{id}", "get")
                                    .path("operationId")
                                    .asText(null),
                            "the operation under /reports/{id}");
                },
                annotated(source, hiddenId, SummaryOnlyResource.class, "report"),
                fails(List.of(hiddenId, visibleId, "'/reports/{id}'")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hiddenFirstCases")
    @DisplayName("Hidden operations and inputs are removed before every check, and the same content visible fails it")
    void hiddenContentIsRemovedBeforeDocumentChecks(HiddenPair row) {
        assertAll(
                () -> {
                    // Given: the publication with the hidden marker.
                    MountPublication publication = row.hidden().get();

                    // When: the public document is assembled.
                    Outcome outcome = assemble(publication);

                    // Then: it publishes without any warning, and the hidden content appears nowhere.
                    assertOutcome("the hidden case", outcome, publishesWithoutWarning(rendering -> {
                        assertAbsent(rendering, SENTINEL);
                        row.hiddenDocument().accept(rendering);
                    }));
                },
                () -> {
                    // Given: the same publication without the hidden marker.
                    MountPublication publication = row.control().get();

                    // When: the public document is assembled.
                    Outcome outcome = assemble(publication);

                    // Then: the check the content exercises fails publication, or warns.
                    assertOutcome("the control", outcome, row.controlExpected());
                });
    }
}
