// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.jaxrs.publication.InputBinding;
import dev.vertique.rest.jaxrs.publication.InputBinding.Requiredness;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.openapi.docs.DisclosureDocuments.Rendering;
import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.FlatZx;
import dev.vertique.rest.openapi.docs.fixture.disclosure.sources.CanonicalSubclassSchemaSource;
import dev.vertique.rest.openapi.docs.fixture.disclosure.sources.PassThroughSchemaSource;
import dev.vertique.rest.openapi.docs.fixture.disclosure.unit.DisclosurePublications;
import dev.vertique.rest.openapi.docs.fixture.input.GeneratedBodies;
import dev.vertique.rest.openapi.docs.fixture.input.Publications;
import dev.vertique.rest.openapi.docs.fixture.input.UnitDocumentedApi;
import dev.vertique.rest.validation.AnnotationSchemaSource;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit proofs that only a protected document discloses how request inputs are validated: the
 * configured strategy, whether the schemas come from the framework's own generator, whether
 * validation is enforced, and which inputs no schema guards.
 *
 * <p>Every case builds a synthetic publication of one application, assembles it once with a
 * protected and once with a public document, and compares the root {@code x-vertique-validation}
 * object, the Parameter Objects, and the request bodies with hand-written literals. Absence claims
 * are checked over the full JSON and YAML bytes; the protected rendering of the same publication is
 * their positive control.
 */
@DisplayName("Validation authority disclosure by document access")
class ValidationDisclosureTest {

    /** The application whose documents every case assembles. */
    private static final String APPLICATION = "orders";

    /** The registered mount path of the application. */
    private static final String MOUNT = "/api/orders/*";

    /** The id of the strategy that validates every input with its captured schema. */
    private static final String WEB_VALIDATION = "web-validation";

    /** The id of the strategy that validates nothing. */
    private static final String NONE = "none";

    /** A custom strategy that installs no schema gate, so no input is schema-enforced. */
    private static final String CUSTOM_SIGNATURE = "custom-signature-zx";

    /** A custom strategy whose installed gate received every captured schema. */
    private static final String CUSTOM_SCHEMA = "custom-schema-zx";

    /** The operation of the disclosure matrix. */
    private static final String UPDATE_ITEM = "updateItem";

    /** The path of {@value #UPDATE_ITEM} in the document. */
    private static final String ITEM_PATH = "/items/{id}";

    /** The root {@code x-vertique-validation} of every public document. */
    private static final String PUBLIC_ROOT = "{\"patternDialect\":\"java.util.regex\"}";

    /** The per-input member of an input no schema guards, in a protected document. */
    private static final String UNENFORCED_MARKER = "{\"enforcedSchema\":false}";

    /** The marker's member name, counted in the rendered bytes. */
    private static final String ENFORCED_SCHEMA = "enforcedSchema";

    /** The member order of every Parameter Object without a marker but with {@code required}. */
    private static final List<String> REQUIRED_PARAMETER_ORDER = List.of("name", "in", "required", "schema");

    /** The member order of a Parameter Object without a marker. */
    private static final List<String> PLAIN_PARAMETER_ORDER = List.of("name", "in", "schema");

    /** The member order of a marked Parameter Object: the marker last. */
    private static final List<String> MARKED_PARAMETER_ORDER = List.of("name", "in", "schema", "x-vertique-validation");

    /** The enforced path parameter, as every document publishes it. */
    private static final String PATH_PARAMETER =
            "{\"name\":\"id\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\"}}";

    /** The path parameter of the publication that captured no schema at all. */
    private static final String PATH_PARAMETER_WITHOUT_SCHEMA =
            "{\"name\":\"id\",\"in\":\"path\",\"required\":true,\"schema\":{}}";

    /** The query parameter without a captured schema, unmarked. */
    private static final String QUERY_PARAMETER = "{\"name\":\"verbose\",\"in\":\"query\",\"schema\":{}}";

    /** The query parameter without a captured schema, marked. */
    private static final String MARKED_QUERY_PARAMETER =
            "{\"name\":\"verbose\",\"in\":\"query\",\"schema\":{},\"x-vertique-validation\":{\"enforcedSchema\":false}}";

    /** The composite-field parameter, unmarked. */
    private static final String COMPOSITE_PARAMETER = "{\"name\":\"page\",\"in\":\"query\",\"schema\":{}}";

    /** The composite-field parameter, marked. */
    private static final String MARKED_COMPOSITE_PARAMETER =
            "{\"name\":\"page\",\"in\":\"query\",\"schema\":{},\"x-vertique-validation\":{\"enforcedSchema\":false}}";

    /** The root member order of a protected document with nothing refused or hidden. */
    private static final List<String> PROTECTED_ROOT_ORDER =
            List.of("patternDialect", "strategy", "inputSchemaSource", "enforcement");

    /** The media type of a URL-encoded form body. */
    private static final String URL_ENCODED = "application/x-www-form-urlencoded";

    /** The media type of a multipart form body. */
    private static final String MULTIPART = "multipart/form-data";

    // ---------------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * One case of the disclosure matrix.
     *
     * @param built        the publication
     * @param context      the assembly context holding the bound schema source
     * @param expectedRoot the expected root {@code x-vertique-validation} of the protected document
     * @param marked       whether the protected document marks the query and composite-field
     *     parameters
     * @param schemas      whether the publication captured the path parameter's schema
     */
    record DisclosureCase(
            Publications.Built built, AssemblyContext context, String expectedRoot, boolean marked, boolean schemas) {}

    /**
     * Builds the matrix publication with captured schemas: {@code PUT /items/{id}} with the path
     * parameter {@code id} (captured schema), the query parameter {@code verbose} (no captured
     * schema), the composite field {@code page} (query), and a {@link FlatZx} body described by the
     * real generator with its manifest. Every binding's {@code schemaEnforced} flag follows the
     * strategy and gate: enforced exactly when the gate is installed and a schema was captured, a
     * composite field never.
     *
     * @param strategy      the mount's strategy id
     * @param gateInstalled whether the operation's gate was installed
     * @return the publication, its body binding typed {@link FlatZx}
     */
    private static Publications.Built itemPublication(String strategy, boolean gateInstalled) {
        Publications.Built built = Publications.mount(MOUNT)
                .application(APPLICATION, UnitDocumentedApi.class)
                .strategy(strategy)
                .operation("PUT", ITEM_PATH, UPDATE_ITEM)
                .gateInstalled(gateInstalled)
                .consumes("application/json")
                .param(ParamLocation.PATH, "id", Requiredness.REQUIRED)
                .schema(new JsonObject("{\"type\":\"string\"}"))
                .param(ParamLocation.QUERY, "verbose", Requiredness.UNKNOWN)
                .compositeField(ParamLocation.QUERY, "page", Requiredness.NOT_REQUIRED)
                .body(GeneratedBodies.describe(FlatZx.class))
                .build();
        return DisclosurePublications.from(built)
                .bodyType(UPDATE_ITEM, FlatZx.class)
                .build();
    }

    /**
     * Builds the matrix publication that captured no schema: the same inputs under strategy
     * {@code none} with no gate installed, the body bound without a schema.
     *
     * @return the publication, its body binding typed {@link FlatZx}
     */
    private static Publications.Built itemPublicationWithoutSchemas() {
        Publications.Built built = Publications.mount(MOUNT)
                .application(APPLICATION, UnitDocumentedApi.class)
                .strategy(NONE)
                .operation("PUT", ITEM_PATH, UPDATE_ITEM)
                .gateInstalled(false)
                .consumes("application/json")
                .param(ParamLocation.PATH, "id", Requiredness.REQUIRED)
                .param(ParamLocation.QUERY, "verbose", Requiredness.UNKNOWN)
                .compositeField(ParamLocation.QUERY, "page", Requiredness.NOT_REQUIRED)
                .bodyBindingWithoutSchema()
                .build();
        return DisclosurePublications.from(built)
                .bodyType(UPDATE_ITEM, FlatZx.class)
                .build();
    }

    /**
     * Returns an assembly context binding the framework's canonical schema source.
     *
     * @return the context
     */
    private static AssemblyContext canonical() {
        return DisclosureDocuments.withSource(new AnnotationSchemaSource());
    }

    /**
     * Returns the root member map of a protected document as a literal.
     *
     * @param strategy          the strategy id
     * @param inputSchemaSource the expected {@code inputSchemaSource}
     * @param enforcement       the expected {@code enforcement}
     * @return the JSON text, members in the expected order
     */
    private static String protectedRoot(String strategy, String inputSchemaSource, String enforcement) {
        return "{\"patternDialect\":\"java.util.regex\",\"strategy\":\"" + strategy + "\",\"inputSchemaSource\":\""
                + inputSchemaSource + "\",\"enforcement\":\"" + enforcement + "\"}";
    }

    /**
     * The disclosure matrix: each case names its strategy, gate, and bound source, and carries the
     * expected root member map of its protected document.
     *
     * @return the named cases
     */
    static Stream<Named<DisclosureCase>> disclosureCases() {
        return Stream.of(
                Named.of(
                        "web-validation, gate installed, canonical source: generated, active, two markers",
                        new DisclosureCase(
                                itemPublication(WEB_VALIDATION, true),
                                canonical(),
                                protectedRoot(WEB_VALIDATION, "generated", "active"),
                                true,
                                true)),
                Named.of(
                        "none, no gate, canonical source: generated, disabled, no marker",
                        new DisclosureCase(
                                itemPublication(NONE, false),
                                canonical(),
                                protectedRoot(NONE, "generated", "disabled"),
                                false,
                                true)),
                Named.of(
                        "custom-signature-zx, no schema gate, canonical source: generated, unknown, no marker",
                        new DisclosureCase(
                                itemPublication(CUSTOM_SIGNATURE, false),
                                canonical(),
                                protectedRoot(CUSTOM_SIGNATURE, "generated", "unknown"),
                                false,
                                true)),
                Named.of(
                        "custom-schema-zx, gate received every schema, canonical source: generated, unknown, no marker",
                        new DisclosureCase(
                                itemPublication(CUSTOM_SCHEMA, true),
                                canonical(),
                                protectedRoot(CUSTOM_SCHEMA, "generated", "unknown"),
                                false,
                                true)),
                Named.of(
                        "none, no source bound, nothing captured: absent, disabled, no marker",
                        new DisclosureCase(
                                itemPublicationWithoutSchemas(),
                                DisclosureDocuments.noSource(),
                                protectedRoot(NONE, "absent", "disabled"),
                                false,
                                false)),
                Named.of(
                        "web-validation, gate installed, pass-through source: custom, active, two markers",
                        new DisclosureCase(
                                itemPublication(WEB_VALIDATION, true),
                                DisclosureDocuments.withSource(new PassThroughSchemaSource()),
                                protectedRoot(WEB_VALIDATION, "custom", "active"),
                                true,
                                true)),
                Named.of(
                        "web-validation, gate installed, subclass of the canonical source: custom, active, two markers",
                        new DisclosureCase(
                                itemPublication(WEB_VALIDATION, true),
                                DisclosureDocuments.withSource(new CanonicalSubclassSchemaSource()),
                                protectedRoot(WEB_VALIDATION, "custom", "active"),
                                true,
                                true)));
    }

    /**
     * Returns the operation published under a path and a lowercase method, failing when absent.
     *
     * @param doc    the parsed document
     * @param path   the path key
     * @param method the lowercase method key
     * @return the operation object
     */
    private static JsonObject operation(JsonObject doc, String path, String method) {
        JsonObject paths = doc.getJsonObject("paths");
        assertNotNull(paths, () -> "the document has no paths: " + doc);
        JsonObject pathItem = paths.getJsonObject(path);
        assertNotNull(pathItem, () -> "no path item " + path + " in " + paths);
        JsonObject operation = pathItem.getJsonObject(method);
        assertNotNull(operation, () -> "no " + method + " operation under " + path + " in " + pathItem);
        return operation;
    }

    /**
     * Returns the request body of an operation, failing when absent.
     *
     * @param doc    the parsed document
     * @param path   the path key
     * @param method the lowercase method key
     * @return the request body object
     */
    private static JsonObject requestBody(JsonObject doc, String path, String method) {
        JsonObject operation = operation(doc, path, method);
        JsonObject body = operation.getJsonObject("requestBody");
        assertNotNull(body, () -> "no request body in " + operation);
        return body;
    }

    /**
     * Returns the member names of an object in written order.
     *
     * @param object the object
     * @return the member names
     */
    private static List<String> members(JsonObject object) {
        return new ArrayList<>(object.fieldNames());
    }

    /**
     * Asserts that a published object equals a literal, members in the given order.
     *
     * @param expected      the expected JSON text
     * @param expectedOrder the expected member order
     * @param actual        the published object
     * @param what          what the object is, for the failure message
     */
    private static void assertObject(String expected, List<String> expectedOrder, JsonObject actual, String what) {
        assertEquals(new JsonObject(expected), actual, () -> what);
        assertEquals(expectedOrder, members(actual), () -> what + " member order: " + actual);
    }

    /**
     * Counts the occurrences of a text in another.
     *
     * @param text   the text searched
     * @param needle the text counted
     * @return the number of non-overlapping occurrences
     */
    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    /**
     * Asserts that both rendered forms of a document contain a text a given number of times.
     *
     * @param rendering the rendered document
     * @param needle    the text counted
     * @param expected  the expected number of occurrences in each form
     * @param what      which document, for the failure message
     */
    private static void assertOccurrences(Rendering rendering, String needle, int expected, String what) {
        String json = rendering.jsonText();
        String yaml = rendering.yamlText();
        assertEquals(expected, occurrences(json, needle), () -> what + " JSON occurrences of " + needle + ":\n" + json);
        assertEquals(expected, occurrences(yaml, needle), () -> what + " YAML occurrences of " + needle + ":\n" + yaml);
    }

    /**
     * Asserts that the JSON form of a document contains none of the given texts.
     *
     * @param rendering the rendered document
     * @param what      which document, for the failure message
     * @param texts     the texts
     */
    private static void assertJsonLacks(Rendering rendering, String what, String... texts) {
        String json = rendering.jsonText();
        for (String text : texts) {
            assertFalse(json.contains(text), () -> what + " JSON contains " + text + ":\n" + json);
        }
    }

    /**
     * Asserts that the YAML form of a document contains none of the given texts.
     *
     * @param rendering the rendered document
     * @param what      which document, for the failure message
     * @param texts     the texts
     */
    private static void assertYamlLacks(Rendering rendering, String what, String... texts) {
        String yaml = rendering.yamlText();
        for (String text : texts) {
            assertFalse(yaml.contains(text), () -> what + " YAML contains " + text + ":\n" + yaml);
        }
    }

    /**
     * Asserts that a document is public in what it discloses: its root {@code x-vertique-validation}
     * is exactly the pattern dialect, and neither form names a strategy, a schema source, an
     * enforcement state, a per-input marker, or a custom strategy id.
     *
     * @param rendering the rendered public document
     * @param what      which document, for the failure message
     */
    private static void assertDisclosesNothing(Rendering rendering, String what) {
        JsonObject root = rendering.rootValidation();
        assertNotNull(root, () -> what + " has no root x-vertique-validation");
        assertObject(PUBLIC_ROOT, List.of("patternDialect"), root, what + " root x-vertique-validation");
        assertEquals(
                rendering.jsonTree().get("x-vertique-validation"),
                rendering.yamlTree().get("x-vertique-validation"),
                () -> what + " YAML root x-vertique-validation differs from JSON");
        assertJsonLacks(
                rendering,
                what,
                ENFORCED_SCHEMA,
                "inputSchemaSource",
                "\"enforcement\"",
                "\"strategy\"",
                CUSTOM_SIGNATURE,
                CUSTOM_SCHEMA);
        assertYamlLacks(
                rendering,
                what,
                ENFORCED_SCHEMA,
                "inputSchemaSource",
                "enforcement:",
                "strategy:",
                CUSTOM_SIGNATURE,
                CUSTOM_SCHEMA);
    }

    // ---------------------------------------------------------------------------------------------
    // The disclosure matrix
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("disclosureCases")
    @DisplayName(
            "Only a protected document names the strategy, the schema source, and enforcement, and marks unguarded inputs only under web-validation")
    void onlyProtectedDocumentsDiscloseValidationAuthority(DisclosureCase disclosure) {
        // Given: the case's publication of 'PUT /items/{id}' with an enforced path parameter, a query
        // parameter without a captured schema, a composite field, and a body, and its bound source.
        Publications.Built built = disclosure.built();

        // When: it is assembled once with a protected and once with a public document.
        Rendering protectedDocument = DisclosureDocuments.renderProtected(built, disclosure.context());
        Rendering publicDocument = DisclosureDocuments.renderPublic(built, disclosure.context());

        // Then (protected): the root x-vertique-validation is exactly the case's member map, in the
        // order pattern dialect, strategy, input schema source, enforcement, in both forms.
        JsonObject protectedDoc = protectedDocument.document();
        JsonObject root = protectedDocument.rootValidation();
        assertNotNull(root, () -> "the protected document has no root x-vertique-validation");
        assertEquals(new JsonObject(disclosure.expectedRoot()), root, "protected root x-vertique-validation");
        assertEquals(PROTECTED_ROOT_ORDER, members(root), () -> "protected root member order: " + root);
        assertEquals(
                protectedDocument.jsonTree().get("x-vertique-validation"),
                protectedDocument.yamlTree().get("x-vertique-validation"),
                "protected YAML root x-vertique-validation differs from JSON");

        // Then (protected): the query parameter and the composite field carry the marker as their last
        // member exactly when the strategy is web-validation; the path parameter and the body never
        // do, and the marker appears nowhere else in either form.
        JsonArray protectedParameters =
                operation(protectedDoc, ITEM_PATH, "put").getJsonArray("parameters");
        assertNotNull(protectedParameters, "the protected operation has no parameters");
        assertEquals(3, protectedParameters.size(), () -> "protected parameters: " + protectedParameters);
        assertObject(
                disclosure.schemas() ? PATH_PARAMETER : PATH_PARAMETER_WITHOUT_SCHEMA,
                REQUIRED_PARAMETER_ORDER,
                protectedParameters.getJsonObject(0),
                "protected path parameter");
        if (disclosure.marked()) {
            assertObject(
                    MARKED_QUERY_PARAMETER,
                    MARKED_PARAMETER_ORDER,
                    protectedParameters.getJsonObject(1),
                    "protected query parameter");
            assertObject(
                    MARKED_COMPOSITE_PARAMETER,
                    MARKED_PARAMETER_ORDER,
                    protectedParameters.getJsonObject(2),
                    "protected composite-field parameter");
            assertOccurrences(protectedDocument, ENFORCED_SCHEMA, 2, "protected");
        } else {
            assertObject(
                    QUERY_PARAMETER,
                    PLAIN_PARAMETER_ORDER,
                    protectedParameters.getJsonObject(1),
                    "protected query parameter");
            assertObject(
                    COMPOSITE_PARAMETER,
                    PLAIN_PARAMETER_ORDER,
                    protectedParameters.getJsonObject(2),
                    "protected composite-field parameter");
            assertOccurrences(protectedDocument, ENFORCED_SCHEMA, 0, "protected");
            // No strategy other than web-validation is presented as actively enforcing.
            assertNotEquals("active", root.getString("enforcement"), "protected enforcement");
        }
        JsonObject protectedBody = requestBody(protectedDoc, ITEM_PATH, "put");
        assertFalse(
                protectedBody.containsKey("x-vertique-validation"),
                () -> "the protected body carries a marker: " + protectedBody);

        // Then (public): the root x-vertique-validation is exactly the pattern dialect, no Parameter
        // Object carries a marker, and neither form names any disclosed member or custom strategy id.
        // The protected rendering of the same publication above is the positive control.
        assertDisclosesNothing(publicDocument, "public");
        JsonArray publicParameters =
                operation(publicDocument.document(), ITEM_PATH, "put").getJsonArray("parameters");
        assertNotNull(publicParameters, "the public operation has no parameters");
        assertEquals(3, publicParameters.size(), () -> "public parameters: " + publicParameters);
        assertObject(
                QUERY_PARAMETER, PLAIN_PARAMETER_ORDER, publicParameters.getJsonObject(1), "public query parameter");
        assertObject(
                COMPOSITE_PARAMETER,
                PLAIN_PARAMETER_ORDER,
                publicParameters.getJsonObject(2),
                "public composite-field parameter");
    }

    // ---------------------------------------------------------------------------------------------
    // Request bodies
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A body whose operation installed no gate is marked unenforced in a protected document only")
    void ungatedBodyIsMarkedUnenforcedInProtectedDocuments() {
        // Given: strategy web-validation with two operations, each with a generated FlatZx body:
        // 'createNote' installed no gate, so its body binding is not schema-enforced; the control
        // 'createItem' installed the gate, so its body is enforced and, rejecting null, required.
        Publications.Built built = DisclosurePublications.from(Publications.mount(MOUNT)
                        .application(APPLICATION, UnitDocumentedApi.class)
                        .strategy(WEB_VALIDATION)
                        .operation("POST", "/notes", "createNote")
                        .gateInstalled(false)
                        .consumes("application/json")
                        .body(GeneratedBodies.describe(FlatZx.class))
                        .operation("POST", "/items", "createItem")
                        .consumes("application/json")
                        .body(GeneratedBodies.describe(FlatZx.class))
                        .build())
                .bodyType("createNote", FlatZx.class)
                .bodyType("createItem", FlatZx.class)
                .build();
        assertFalse(bodyBinding(built, "createNote").schemaEnforced(), "the ungated body binding is enforced");
        assertTrue(bodyBinding(built, "createItem").schemaEnforced(), "the gated body binding is not enforced");

        // When: it is assembled with a protected and with a public document.
        Rendering protectedDocument = DisclosureDocuments.renderProtected(built, canonical());
        Rendering publicDocument = DisclosureDocuments.renderPublic(built, canonical());

        // Then (protected): the ungated body carries the marker as its last member; the gated body
        // carries none, and the marker appears once in each form.
        JsonObject protectedDoc = protectedDocument.document();
        JsonObject ungated = requestBody(protectedDoc, "/notes", "post");
        assertEquals(
                List.of("content", "x-vertique-validation"),
                members(ungated),
                () -> "the ungated body's members: " + ungated);
        assertEquals(
                new JsonObject(UNENFORCED_MARKER),
                ungated.getJsonObject("x-vertique-validation"),
                () -> "the ungated body's marker: " + ungated);
        JsonObject gated = requestBody(protectedDoc, "/items", "post");
        assertEquals(List.of("content", "required"), members(gated), () -> "the gated body's members: " + gated);
        assertOccurrences(protectedDocument, ENFORCED_SCHEMA, 1, "protected");

        // Then (public): neither body carries a marker and nothing is disclosed.
        JsonObject publicDoc = publicDocument.document();
        JsonObject publicUngated = requestBody(publicDoc, "/notes", "post");
        assertEquals(List.of("content"), members(publicUngated), () -> "the public ungated body: " + publicUngated);
        JsonObject publicGated = requestBody(publicDoc, "/items", "post");
        assertEquals(
                List.of("content", "required"), members(publicGated), () -> "the public gated body: " + publicGated);
        assertDisclosesNothing(publicDocument, "public");
    }

    @Test
    @DisplayName(
            "A form body is marked unenforced when any of its fields is unenforced or is a named file part, in a protected document only")
    void formBodyIsMarkedUnenforcedWhenAnyFormFieldIsUnenforcedOrANamedFilePart() {
        // Given: strategy web-validation with the gate installed and three form operations:
        // 'submitEnforced' whose two form fields both have a captured schema, so both are enforced;
        // 'submitPartly' whose field 'note' has no captured schema, so it alone is unenforced;
        // 'uploadFile' whose field 'caption' and named file part 'file' both have a captured schema, so
        // both are enforced, while the file part is published without its schema.
        Publications.Built built = Publications.mount(MOUNT)
                .application(APPLICATION, UnitDocumentedApi.class)
                .strategy(WEB_VALIDATION)
                .operation("POST", "/forms/enforced", "submitEnforced")
                .consumes(URL_ENCODED)
                .formField("title")
                .schema(new JsonObject("{\"type\":\"string\",\"maxLength\":80}"))
                .formField("note")
                .schema(new JsonObject("{\"type\":\"string\"}"))
                .operation("POST", "/forms/partly", "submitPartly")
                .consumes(URL_ENCODED)
                .formField("title")
                .schema(new JsonObject("{\"type\":\"string\",\"maxLength\":80}"))
                .formField("note")
                .operation("POST", "/forms/upload", "uploadFile")
                .consumes(MULTIPART)
                .formField("caption")
                .schema(new JsonObject("{\"type\":\"string\",\"minLength\":1}"))
                .namedFilePart("file")
                .schema(new JsonObject("{\"type\":\"string\"}"))
                .build();
        assertEquals(List.of(true, true), formEnforcement(built, "submitEnforced"), "submitEnforced form fields");
        assertEquals(List.of(true, false), formEnforcement(built, "submitPartly"), "submitPartly form fields");
        assertEquals(List.of(true, true), formEnforcement(built, "uploadFile"), "uploadFile form fields");
        assertEquals(List.of(), built.namedFileParts().get("submitEnforced"), "submitEnforced named file parts");
        assertEquals(List.of(), built.namedFileParts().get("submitPartly"), "submitPartly named file parts");
        assertEquals(List.of("file"), built.namedFileParts().get("uploadFile"), "uploadFile named file parts");

        // When: it is assembled with a protected and with a public document.
        Rendering protectedDocument = DisclosureDocuments.renderProtected(built, canonical());
        Rendering publicDocument = DisclosureDocuments.renderPublic(built, canonical());

        // Then (protected): the fully enforced form body carries no marker; the form body with an
        // unenforced field and the one with a named file part carry it as their last member.
        JsonObject protectedDoc = protectedDocument.document();
        JsonObject enforced = requestBody(protectedDoc, "/forms/enforced", "post");
        assertEquals(List.of("content"), members(enforced), () -> "the enforced form body: " + enforced);
        for (String path : List.of("/forms/partly", "/forms/upload")) {
            JsonObject marked = requestBody(protectedDoc, path, "post");
            assertEquals(
                    List.of("content", "x-vertique-validation"),
                    members(marked),
                    () -> "the form body of " + path + ": " + marked);
            assertEquals(
                    new JsonObject(UNENFORCED_MARKER),
                    marked.getJsonObject("x-vertique-validation"),
                    () -> "the marker of the form body of " + path + ": " + marked);
        }
        assertOccurrences(protectedDocument, ENFORCED_SCHEMA, 2, "protected");

        // Then (public): no form body carries a marker and nothing is disclosed.
        JsonObject publicDoc = publicDocument.document();
        for (String path : List.of("/forms/enforced", "/forms/partly", "/forms/upload")) {
            JsonObject body = requestBody(publicDoc, path, "post");
            assertEquals(List.of("content"), members(body), () -> "the public form body of " + path + ": " + body);
        }
        assertDisclosesNothing(publicDocument, "public");
    }

    /**
     * Returns the body binding of an operation.
     *
     * @param built       the publication
     * @param operationId the operation id
     * @return the body binding
     */
    private static InputBinding bodyBinding(Publications.Built built, String operationId) {
        return inputs(built, operationId).stream()
                .filter(binding -> binding.origin() == InputBinding.Origin.BODY)
                .findFirst()
                .orElseThrow(() -> new AssertionError("operation '" + operationId + "' has no body binding"));
    }

    /**
     * Returns the {@code schemaEnforced} flags of an operation's form bindings, in inventory order.
     *
     * @param built       the publication
     * @param operationId the operation id
     * @return the flags
     */
    private static List<Boolean> formEnforcement(Publications.Built built, String operationId) {
        return inputs(built, operationId).stream()
                .filter(binding -> binding.location() == ParamLocation.FORM)
                .map(InputBinding::schemaEnforced)
                .toList();
    }

    /**
     * Returns the binding inventory of an operation.
     *
     * @param built       the publication
     * @param operationId the operation id
     * @return the bindings
     */
    private static List<InputBinding> inputs(Publications.Built built, String operationId) {
        for (OperationPublication operation : built.publication().operations()) {
            if (operation.operationId().equals(operationId)) {
                assertNotNull(operation.detail(), () -> "operation '" + operationId + "' has no detail");
                return operation.detail().inputs();
            }
        }
        throw new AssertionError("no operation '" + operationId + "'");
    }
}
