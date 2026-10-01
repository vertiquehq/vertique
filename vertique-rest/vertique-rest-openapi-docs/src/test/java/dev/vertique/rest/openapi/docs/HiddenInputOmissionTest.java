// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.publication.InputBinding;
import dev.vertique.rest.jaxrs.publication.InputBinding.Requiredness;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.AccountHiddenFieldZx;
import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.NotesZx;
import dev.vertique.rest.openapi.docs.fixture.disclosure.unit.DisclosurePublications;
import dev.vertique.rest.openapi.docs.fixture.input.GeneratedBodies;
import dev.vertique.rest.openapi.docs.fixture.input.Publications;
import dev.vertique.rest.openapi.docs.fixture.input.UnitDocumentedApi;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit proofs that inputs the inventory flags hidden publish nothing, that a hidden path parameter
 * fails publication instead, and that hidden inputs are removed before any verification or check
 * reads them.
 *
 * <p>Every case builds a synthetic application-mount publication with {@link Publications}, flags
 * bindings hidden with {@link DisclosurePublications}, and assembles a public document through the
 * package-private assembler entry point. Each hidden-content assertion is an absence over the full
 * JSON and YAML bytes, paired with a positive control showing the name exists in the publication's
 * inventory or in the captured schema. Failure messages are checked by fragment only.
 */
@DisplayName("Hidden inputs are omitted from the document")
class HiddenInputOmissionTest {

    /** The name of every case's application. */
    private static final String APPLICATION = "disclosure";

    /** The registered mount path of every case's application. */
    private static final String MOUNT = "/api/disclosure/*";

    /** The operation of the item read cases. */
    private static final String GET_ITEM = "getItemZx";

    /** The captured schema of the item read's path parameter; failure messages must never quote it. */
    private static final String ID_SCHEMA = "{\"type\":\"string\",\"minLength\":1}";

    /** The media type of a multipart form body. */
    private static final String MULTIPART = "multipart/form-data";

    // ---------------------------------------------------------------------------------------------
    // A hidden path parameter fails publication
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "A hidden path parameter fails publication, whatever its origin, while a hidden query parameter is omitted")
    void hiddenPathParameterFailsPublication() {
        assertAll(
                "hidden path parameter",
                () -> hiddenPathParameterBoundAsMethodParameterFails(),
                () -> hiddenQueryParameterBesideVisiblePathParameterIsOmitted(),
                () -> hiddenPathParameterBoundAsCompositeFieldFails());
    }

    /** The {@code id} path parameter, a method parameter, is flagged hidden. */
    private static void hiddenPathParameterBoundAsMethodParameterFails() {
        // Given the item read whose path parameter 'id' is flagged hidden
        Publications.Built built = DisclosurePublications.from(itemRead())
                .hidden(GET_ITEM, ParamLocation.PATH, "id")
                .build();
        assertHiddenInInventory(built, GET_ITEM, ParamLocation.PATH, "id");

        // When the document is assembled
        RestConfigurationException failure =
                DisclosureDocuments.failure(built, ApiDocs.Access.PUBLIC, DisclosureDocuments.noSource());

        // Then publication fails naming the mount, the operation, and the parameter, quoting no schema
        assertHiddenPathFailure(failure);
    }

    /** The control: {@code id} stays visible and the query parameter {@code debugZx} is hidden. */
    private static void hiddenQueryParameterBesideVisiblePathParameterIsOmitted() {
        // Given the item read whose query parameter 'debugZx' is flagged hidden
        Publications.Built built = DisclosurePublications.from(itemRead())
                .hidden(GET_ITEM, ParamLocation.QUERY, "debugZx")
                .build();
        assertHiddenInInventory(built, GET_ITEM, ParamLocation.QUERY, "debugZx");

        // When the document is assembled
        DisclosureDocuments.Rendering rendering =
                DisclosureDocuments.renderPublic(built, DisclosureDocuments.noSource());

        // Then the path parameter is described, the hidden query parameter appears nowhere,
        // and the document is a valid OpenAPI 3.1 document
        for (JsonNode tree : List.of(rendering.jsonTree(), rendering.yamlTree())) {
            List<JsonNode> parameters = parameters(tree, "/items/{id}", "get");
            assertTrue(
                    parameters.stream()
                            .anyMatch(p -> "id".equals(p.path("name").asText(null))
                                    && "path".equals(p.path("in").asText(null))),
                    () -> "no path parameter 'id' in " + parameters);
        }
        assertAbsent(rendering, "debugZx");
        OpenApi31Toolchain.assertValid(rendering.document());
    }

    /**
     * The {@code id} path parameter is instead the {@code @PathParam("id")} field of a hidden
     * {@code @BeanParam} parameter, so every field of that composite is flagged hidden.
     */
    private static void hiddenPathParameterBoundAsCompositeFieldFails() {
        // Given the item read whose 'id' is a field of a hidden composite parameter
        Publications.Built source = mount().operation("GET", "/items/{id}", GET_ITEM)
                .compositeField(ParamLocation.PATH, "id", Requiredness.REQUIRED)
                .compositeField(ParamLocation.QUERY, "verboseZx", Requiredness.UNKNOWN)
                .build();
        Publications.Built built = DisclosurePublications.from(source)
                .hidden(GET_ITEM, ParamLocation.PATH, "id")
                .hidden(GET_ITEM, ParamLocation.QUERY, "verboseZx")
                .build();
        assertHiddenInInventory(built, GET_ITEM, ParamLocation.PATH, "id");
        assertEquals(
                InputBinding.Origin.COMPOSITE_FIELD,
                binding(built, GET_ITEM, ParamLocation.PATH, "id").origin());

        // When the document is assembled
        RestConfigurationException failure =
                DisclosureDocuments.failure(built, ApiDocs.Access.PUBLIC, DisclosureDocuments.noSource());

        // Then publication fails exactly as for a hidden path method parameter
        assertHiddenPathFailure(failure);
    }

    /** The item read: a required path parameter 'id' with a captured schema and a query parameter. */
    private static Publications.Built itemRead() {
        return mount().operation("GET", "/items/{id}", GET_ITEM)
                .param(ParamLocation.PATH, "id", Requiredness.REQUIRED)
                .schema(new JsonObject(ID_SCHEMA))
                .param(ParamLocation.QUERY, "debugZx", Requiredness.UNKNOWN)
                .build();
    }

    private static void assertHiddenPathFailure(RestConfigurationException failure) {
        String message = failure.getMessage();
        assertNotNull(message);
        assertTrue(message.contains(MOUNT), message);
        assertTrue(message.contains(GET_ITEM), message);
        assertTrue(message.contains("'id'"), message);
        assertFalse(message.contains("{\""), message);
        assertFalse(message.contains("\"type\""), message);
        assertFalse(message.contains("minLength"), message);
    }

    // ---------------------------------------------------------------------------------------------
    // Hidden inputs are removed before every verification and check
    // ---------------------------------------------------------------------------------------------

    /**
     * One input that would fail a check if it were published: the publication with it hidden, the
     * same publication without the hidden marker, the fragments the control's failure must hold, and
     * the assertions on the hidden case's document.
     *
     * @param operationId     the operation whose input is hidden
     * @param hidden          the publication with the input flagged hidden
     * @param control         the same publication with no input flagged hidden
     * @param controlFailure  the fragments of the control's failure message
     * @param hiddenDocument  the assertions on the hidden case's rendering
     */
    record OmissionCase(
            String operationId,
            Publications.Built hidden,
            Publications.Built control,
            List<String> controlFailure,
            Consumer<DisclosureDocuments.Rendering> hiddenDocument) {}

    static Stream<Arguments> omissionCases() {
        return Stream.of(
                Arguments.of(Named.of("a hidden body without a redaction manifest", hiddenBodyWithoutManifest())),
                Arguments.of(Named.of(
                        "a hidden query parameter whose schema holds propertyNames", hiddenPropertyNamesParameter())),
                Arguments.of(Named.of("a hidden query parameter sharing a visible one's name", hiddenDuplicate())),
                Arguments.of(Named.of(
                        "a hidden query parameter whose schema has an external reference", hiddenExternalReference())),
                Arguments.of(Named.of("a hidden body whose type has a @Hidden member", hiddenBodyWithHiddenMember())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("omissionCases")
    @DisplayName("A hidden input is omitted before any check reads it, and the same input visible fails that check")
    void hiddenInputsAreRemovedBeforeEveryCheck(OmissionCase row) {
        assertAll(
                row.operationId(),
                () -> {
                    // Given the publication with the input flagged hidden
                    // When the document is assembled
                    DisclosureDocuments.Rendering rendering =
                            DisclosureDocuments.renderPublic(row.hidden(), DisclosureDocuments.noSource());
                    // Then it publishes and the hidden input appears nowhere
                    row.hiddenDocument().accept(rendering);
                },
                () -> {
                    // Given the same publication with the input visible
                    // When the document is assembled
                    RestConfigurationException failure = DisclosureDocuments.failure(
                            row.control(), ApiDocs.Access.PUBLIC, DisclosureDocuments.noSource());
                    // Then publication fails with the check the input exercises, naming mount and operation
                    String message = failure.getMessage();
                    assertNotNull(message);
                    assertTrue(message.contains(MOUNT), message);
                    assertTrue(message.contains("'" + row.operationId() + "'"), message);
                    for (String fragment : row.controlFailure()) {
                        assertTrue(message.contains(fragment), () -> "no '" + fragment + "' in: " + message);
                    }
                });
    }

    /** A body captured with no provenance at all, which manifest verification refuses. */
    private static OmissionCase hiddenBodyWithoutManifest() {
        String operationId = "createNoteZx";
        Supplier<Publications.Built> source = () -> mount().operation("POST", "/notes", operationId)
                .consumes("application/json")
                .bodySchema(GeneratedBodies.describe(NotesZx.class).schema(), null)
                .build();
        Publications.Built hidden = DisclosurePublications.from(source.get())
                .bodyType(operationId, NotesZx.class)
                .hiddenBody(operationId)
                .build();
        Publications.Built control = DisclosurePublications.from(source.get())
                .bodyType(operationId, NotesZx.class)
                .build();
        assertNotNull(capturedBody(hidden, operationId));
        assertTrue(capturedBody(hidden, operationId).encode().contains("internalNoteZx"));
        return new OmissionCase(
                operationId,
                hidden,
                control,
                List.of("request body", "carries no redaction manifest matching its content"),
                rendering -> {
                    assertNoRequestBody(rendering, "/notes", "post");
                    assertNoComponent(rendering, operationId + ".request");
                    assertAbsent(rendering, "internalNoteZx");
                });
    }

    /** A query parameter whose captured schema holds {@code propertyNames}, which parameters may not. */
    private static OmissionCase hiddenPropertyNamesParameter() {
        String operationId = "searchZx";
        Supplier<Publications.Built> source = () -> mount().operation("GET", "/search", operationId)
                .param(ParamLocation.QUERY, "filterZx", Requiredness.UNKNOWN)
                .schema(new JsonObject("{\"type\":\"object\",\"propertyNames\":{\"maxLength\":3}}"))
                .param(ParamLocation.QUERY, "page", Requiredness.UNKNOWN)
                .build();
        Publications.Built hidden = DisclosurePublications.from(source.get())
                .hidden(operationId, ParamLocation.QUERY, "filterZx")
                .build();
        assertHiddenInInventory(hidden, operationId, ParamLocation.QUERY, "filterZx");
        return new OmissionCase(
                operationId, hidden, source.get(), List.of("query", "'filterZx'", "propertyNames"), rendering -> {
                    assertAbsent(rendering, "filterZx");
                    assertAbsent(rendering, "propertyNames");
                    assertParameterNames(rendering, "/search", "get", List.of("page"));
                });
    }

    /**
     * A hidden query parameter {@code q} beside a visible one, which the input collision check refuses
     * when both are visible; the hidden one carries a description so the published one is identified.
     */
    private static OmissionCase hiddenDuplicate() {
        String operationId = "listZx";
        String hiddenDescription = "hiddenQueryDescriptionZx";
        Supplier<Publications.Built> source = () -> mount().operation("GET", "/list", operationId)
                .param(ParamLocation.QUERY, "q", Requiredness.UNKNOWN)
                .description(hiddenDescription)
                .param(ParamLocation.QUERY, "q", Requiredness.UNKNOWN)
                .schema(new JsonObject("{\"type\":\"string\",\"maxLength\":20}"))
                .build();
        Publications.Built hidden = DisclosurePublications.from(source.get())
                .hiddenAt(operationId, 0)
                .build();
        assertTrue(inputs(hidden, operationId).get(0).hidden());
        assertFalse(inputs(hidden, operationId).get(1).hidden());
        return new OmissionCase(
                operationId, hidden, source.get(), List.of("binds more than one input named", "'q'"), rendering -> {
                    assertAbsent(rendering, hiddenDescription);
                    assertParameterNames(rendering, "/list", "get", List.of("q"));
                    for (JsonNode tree : List.of(rendering.jsonTree(), rendering.yamlTree())) {
                        JsonNode q = parameters(tree, "/list", "get").get(0);
                        assertEquals("query", q.path("in").asText(null), q::toString);
                        assertEquals(20, q.path("schema").path("maxLength").asInt(-1), q::toString);
                    }
                });
    }

    /** A query parameter whose captured schema references an external document, which is refused. */
    private static OmissionCase hiddenExternalReference() {
        String operationId = "fetchZx";
        Supplier<Publications.Built> source = () -> mount().operation("GET", "/fetch", operationId)
                .param(ParamLocation.QUERY, "sourceZx", Requiredness.UNKNOWN)
                .schema(new JsonObject("{\"$ref\":\"https://schemas.example.test/a.json\"}"))
                .param(ParamLocation.QUERY, "page", Requiredness.UNKNOWN)
                .build();
        Publications.Built hidden = DisclosurePublications.from(source.get())
                .hidden(operationId, ParamLocation.QUERY, "sourceZx")
                .build();
        assertHiddenInInventory(hidden, operationId, ParamLocation.QUERY, "sourceZx");
        return new OmissionCase(
                operationId,
                hidden,
                source.get(),
                List.of("query parameter 'sourceZx'", "has a '$ref' that is not fragment-only"),
                rendering -> {
                    assertAbsent(rendering, "sourceZx");
                    assertAbsent(rendering, "schemas.example.test");
                    assertParameterNames(rendering, "/fetch", "get", List.of("page"));
                });
    }

    /**
     * A generated body whose manifest verifies but whose type has a member carrying {@code @Hidden},
     * which the hidden-member refusal refuses.
     */
    private static OmissionCase hiddenBodyWithHiddenMember() {
        String operationId = "createAccountZx";
        Supplier<Publications.Built> source = () -> mount().operation("POST", "/accounts", operationId)
                .consumes("application/json")
                .body(GeneratedBodies.describe(AccountHiddenFieldZx.class))
                .build();
        Publications.Built hidden = DisclosurePublications.from(source.get())
                .bodyType(operationId, AccountHiddenFieldZx.class)
                .hiddenBody(operationId)
                .build();
        Publications.Built control = DisclosurePublications.from(source.get())
                .bodyType(operationId, AccountHiddenFieldZx.class)
                .build();
        assertNotNull(capturedBody(hidden, operationId));
        assertTrue(capturedBody(hidden, operationId).encode().contains("backdoorZx"));
        return new OmissionCase(
                operationId,
                hidden,
                control,
                List.of("'backdoorZx'", AccountHiddenFieldZx.class.getName(), "@Hidden"),
                rendering -> {
                    assertNoRequestBody(rendering, "/accounts", "post");
                    assertNoComponent(rendering, operationId + ".request");
                    assertAbsent(rendering, "backdoorZx");
                });
    }

    // ---------------------------------------------------------------------------------------------
    // Hidden form fields
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Hidden form fields and file parts are left out of the form body, and an all-hidden form has no body")
    void hiddenFormFieldsAreOmittedFromTheFormBody() {
        // Given a multipart form with a hidden field and a hidden named file part beside visible ones,
        // and a multipart form whose only field and file part are both hidden
        Publications.Built source = mount().operation("POST", "/forms", "submitFormZx")
                .consumes(MULTIPART)
                .formField("title")
                .formField("secretZx")
                .namedFilePart("photo")
                .namedFilePart("attachmentZx")
                .operation("POST", "/uploads", "uploadZx")
                .consumes(MULTIPART)
                .formField("noteZx")
                .namedFilePart("blobZx")
                .build();
        Publications.Built built = DisclosurePublications.from(source)
                .hidden("submitFormZx", ParamLocation.FORM, "secretZx")
                .hidden("submitFormZx", ParamLocation.FORM, "attachmentZx")
                .hidden("uploadZx", ParamLocation.FORM, "noteZx")
                .hidden("uploadZx", ParamLocation.FORM, "blobZx")
                .build();
        assertHiddenInInventory(built, "submitFormZx", ParamLocation.FORM, "secretZx");
        assertHiddenInInventory(built, "submitFormZx", ParamLocation.FORM, "attachmentZx");
        assertHiddenInInventory(built, "uploadZx", ParamLocation.FORM, "noteZx");
        assertHiddenInInventory(built, "uploadZx", ParamLocation.FORM, "blobZx");

        // When the document is assembled
        DisclosureDocuments.Rendering rendering =
                DisclosureDocuments.renderPublic(built, DisclosureDocuments.noSource());

        // Then the form body describes only the visible field and file part, in both renderings
        for (JsonNode tree : List.of(rendering.jsonTree(), rendering.yamlTree())) {
            JsonNode properties = operation(tree, "/forms", "post")
                    .path("requestBody")
                    .path("content")
                    .path(MULTIPART)
                    .path("schema")
                    .path("properties");
            assertTrue(properties.isObject(), () -> "no form properties in " + tree);
            assertEquals(Set.of("title", "photo"), fieldNames(properties), properties::toString);
        }
        assertAbsent(rendering, "secretZx");
        assertAbsent(rendering, "attachmentZx");

        // and the form whose every binding is hidden publishes no request body
        assertNoRequestBody(rendering, "/uploads", "post");
        assertAbsent(rendering, "noteZx");
        assertAbsent(rendering, "blobZx");
    }

    // ---------------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------------

    /** Starts a mount of the case application, declared by {@link UnitDocumentedApi}. */
    private static Publications mount() {
        return Publications.mount(MOUNT).application(APPLICATION, UnitDocumentedApi.class);
    }

    private static List<InputBinding> inputs(Publications.Built built, String operationId) {
        for (OperationPublication operation : built.publication().operations()) {
            if (operation.operationId().equals(operationId)) {
                return Objects.requireNonNull(operation.detail(), "detail").inputs();
            }
        }
        throw new AssertionError("no operation " + operationId);
    }

    private static JsonObject capturedBody(Publications.Built built, String operationId) {
        for (OperationPublication operation : built.publication().operations()) {
            if (operation.operationId().equals(operationId)) {
                return Objects.requireNonNull(operation.detail(), "detail")
                        .schemas()
                        .body();
            }
        }
        throw new AssertionError("no operation " + operationId);
    }

    private static InputBinding binding(
            Publications.Built built, String operationId, ParamLocation location, String name) {
        for (InputBinding binding : inputs(built, operationId)) {
            if (binding.location() == location && name.equals(binding.name())) {
                return binding;
            }
        }
        throw new AssertionError("no " + location + " binding '" + name + "' in operation " + operationId);
    }

    /** The positive control: the binding exists in the inventory and is flagged hidden. */
    private static void assertHiddenInInventory(
            Publications.Built built, String operationId, ParamLocation location, String name) {
        assertTrue(
                binding(built, operationId, location, name).hidden(),
                () -> location + " '" + name + "' of " + operationId + " is not flagged hidden");
    }

    /** Asserts that neither rendering's bytes contain a text. */
    private static void assertAbsent(DisclosureDocuments.Rendering rendering, String text) {
        assertFalse(rendering.jsonText().contains(text), () -> "'" + text + "' in JSON: " + rendering.jsonText());
        assertFalse(rendering.yamlText().contains(text), () -> "'" + text + "' in YAML: " + rendering.yamlText());
    }

    private static JsonNode operation(JsonNode tree, String path, String method) {
        JsonNode operation = tree.path("paths").path(path).path(method);
        assertTrue(operation.isObject(), () -> "no " + method + " operation under " + path + " in " + tree);
        return operation;
    }

    private static List<JsonNode> parameters(JsonNode tree, String path, String method) {
        List<JsonNode> parameters = new ArrayList<>();
        for (JsonNode holder : List.of(tree.path("paths").path(path), operation(tree, path, method))) {
            JsonNode declared = holder.path("parameters");
            if (declared.isArray()) {
                declared.forEach(parameters::add);
            }
        }
        return parameters;
    }

    /** Asserts the exact Parameter Object names of an operation, in order, in both renderings. */
    private static void assertParameterNames(
            DisclosureDocuments.Rendering rendering, String path, String method, List<String> expected) {
        for (JsonNode tree : List.of(rendering.jsonTree(), rendering.yamlTree())) {
            List<String> names = new ArrayList<>();
            for (JsonNode parameter : parameters(tree, path, method)) {
                names.add(parameter.path("name").asText(null));
            }
            assertEquals(expected, names, () -> "parameters of " + method + " " + path + " in " + tree);
        }
    }

    private static void assertNoRequestBody(DisclosureDocuments.Rendering rendering, String path, String method) {
        for (JsonNode tree : List.of(rendering.jsonTree(), rendering.yamlTree())) {
            JsonNode operation = operation(tree, path, method);
            assertFalse(
                    operation.has("requestBody"),
                    () -> "a request body under " + method + " " + path + ": " + operation);
        }
    }

    /** Asserts that no component schema key equals or starts with the given key, in both renderings. */
    private static void assertNoComponent(DisclosureDocuments.Rendering rendering, String key) {
        for (JsonNode tree : List.of(rendering.jsonTree(), rendering.yamlTree())) {
            JsonNode schemas = tree.path("components").path("schemas");
            for (String name : fieldNames(schemas)) {
                assertFalse(name.startsWith(key), () -> "component '" + name + "' published");
            }
        }
        assertAbsent(rendering, key);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }
}
