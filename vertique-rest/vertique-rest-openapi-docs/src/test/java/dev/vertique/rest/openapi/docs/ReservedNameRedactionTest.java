// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.publication.InputBinding.Requiredness;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.openapi.docs.DisclosureDocuments.Rendering;
import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.AliasedGuardZx;
import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.FlatZx;
import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.FoldCopyGuardZx;
import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.NodeZx;
import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.NotesZx;
import dev.vertique.rest.openapi.docs.fixture.disclosure.profile.TagsProfileModule;
import dev.vertique.rest.openapi.docs.fixture.disclosure.unit.DisclosurePublications;
import dev.vertique.rest.openapi.docs.fixture.input.GeneratedBodies;
import dev.vertique.rest.openapi.docs.fixture.input.GeneratedBodies.GeneratedBody;
import dev.vertique.rest.openapi.docs.fixture.input.Publications;
import dev.vertique.rest.openapi.docs.fixture.input.UnitDocumentedApi;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Unit proofs that a request body is published without the reserved-name assertions its redaction
 * manifest lists, and only those, and that a parameter or form-field schema holding the {@code
 * propertyNames} keyword fails publication.
 *
 * <p>Every body schema and its manifest come from the real input-direction generator's {@code
 * describe(type)}: the generated schema is the captured body copy and the manifest its provenance,
 * and the body binding's type is the described class. Each synthetic publication is assembled in
 * protected and in public mode, and both rendered forms (JSON and YAML bytes) are inspected. The
 * parameter schemas are hand-written literals.
 *
 * <p>A reserved-name assertion ("guard") is a {@code propertyNames} value of the form {@code
 * {"not": {"enum": [...]}}}, or an element of {@code propertyNames.allOf} of that form or of the form
 * {@code {"not": {"pattern": ...}}} whose pattern is not the non-ASCII refusal. The non-ASCII refusal
 * and a {@code propertyNames} without {@code not} are never guards. Expected counts and names are
 * fixed literals; absence of a name is always checked over the full JSON and YAML bytes of every
 * rendering and paired with a positive control on the generator's original schema.
 */
@DisplayName("Reserved-name redaction of request bodies and the parameter propertyNames refusal")
class ReservedNameRedactionTest {

    /** The registered mount path of every publication. */
    private static final String MOUNT = "/api/redaction/*";

    /** The application name of every publication. */
    private static final String APPLICATION = "redaction";

    /** The message fragment naming the mount. */
    private static final String MOUNT_FRAGMENT = "mount '" + MOUNT + "'";

    /** The pattern of the non-ASCII refusal, as a JSON string value. */
    private static final String NON_ASCII_PATTERN = "[^\\x00-\\x7F]";

    /** The root {@code x-vertique-validation} of every public document. */
    private static final String PUBLIC_ROOT = "{\"patternDialect\":\"java.util.regex\"}";

    /** The schema-hidden member of the guarded child and of the recursive node. */
    private static final String SECRET = "secretZx";

    /** The schema-hidden member of the notes body. */
    private static final String INTERNAL_NOTE = "internalNoteZx";

    /** The ignored member of the notes body. */
    private static final String AUDIT_TRAIL = "auditTrailZx";

    /** The guard copies of the alias-expanded body: one per accepted name of its guarded member. */
    private static final int ALIASED_GUARD_COPIES = 3;

    /** The guard copies of the fold-copied body: the property and its case-fold branch. */
    private static final int FOLD_COPY_GUARD_COPIES = 2;

    /** The non-ASCII refusals of the alias-expanded body, which is bound case-sensitively. */
    private static final int ALIASED_NON_ASCII_REFUSALS = 0;

    /** The non-ASCII refusals of the fold-copied body: one at its root. */
    private static final int FOLD_COPY_NON_ASCII_REFUSALS = 1;

    /** The manifest of the notes body under the {@code tags-zx} profile: the root guard only. */
    private static final List<String> NOTES_POINTERS = List.of("/propertyNames");

    /** The query parameter name of the parameter-schema cases. */
    private static final String PARAMETER = "selector";

    /** The operation of the parameter-schema cases. */
    private static final String FIND_OPERATION = "findItems";

    /** The form operation of the form-field case. */
    private static final String SUBMIT_OPERATION = "submitItems";

    /** The media type of a URL-encoded form body. */
    private static final String URL_ENCODED = "application/x-www-form-urlencoded";

    /** A parameter schema holding the keyword in a property's schema. */
    private static final String KEYWORD_IN_PROPERTY = """
            {"type": "object", "properties": {"a": {"propertyNames": {"maxLength": 3}}}}""";

    /** A parameter schema holding the text {@code propertyNames} as data. */
    private static final String NAME_AS_DATA = """
            {"enum": ["propertyNames"]}""";

    /** A parameter schema holding {@code propertyNames} as a property name. */
    private static final String NAME_AS_PROPERTY = """
            {"type": "object", "properties": {"propertyNames": {"type": "string"}}}""";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<ApiDocs.Access> MODES = List.of(ApiDocs.Access.PROTECTED, ApiDocs.Access.PUBLIC);

    // ---------------------------------------------------------------------------------------------
    // Reserved-name redaction of request bodies
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Manifest pointers alone decide what is removed from a published request body")
    void manifestPointersAloneDecideWhatIsRemoved() {
        assertAll(
                "manifest-driven redaction",
                ReservedNameRedactionTest::flatBodyIsPublishedUnchanged,
                ReservedNameRedactionTest::aliasCopiedGuardsAreAllRemoved,
                ReservedNameRedactionTest::foldCopiedGuardsAreAllRemovedAndTheNonAsciiRefusalStays,
                ReservedNameRedactionTest::userDeclaredPropertyNamesStays,
                ReservedNameRedactionTest::recursiveBodyIsRedactedAndItsReferencesResolve);
    }

    /**
     * (a) A flat body without reserved names: empty manifest, component equal to the captured copy,
     * no {@code reservedNamesRefused} in protected mode.
     */
    private static void flatBodyIsPublishedUnchanged() {
        // Given: a flat body; its manifest is empty and it holds no $defs.
        String operationId = "createFlat";
        GeneratedBody body = GeneratedBodies.describe(FlatZx.class);
        assertTrue(body.manifest().pointers().isEmpty(), () -> "flat body precondition: manifest " + body.manifest());
        assertFalse(body.schema().containsKey("$defs"), () -> "flat body precondition: $defs in " + body.schema());
        JsonObject expected = body.schema().copy();
        Publications.Built built = bodyPublication(operationId, FlatZx.class, body, null);

        // When: the document is assembled in protected and in public mode.
        List<Executable> checks = new ArrayList<>();
        for (ApiDocs.Access mode : MODES) {
            Rendering rendering = DisclosureDocuments.render(built, mode, DisclosureDocuments.noSource());
            String label = "flat body " + mode;

            // Then: the component equals the captured copy as a JSON value.
            checks.add(() -> assertEquals(
                    expected,
                    component(rendering, operationId),
                    () -> label + ": the component differs from the captured copy"));
            if (mode == ApiDocs.Access.PROTECTED) {
                checks.add(() -> {
                    JsonObject root = rendering.rootValidation();
                    assertNotNull(root, () -> label + ": no root x-vertique-validation");
                    assertFalse(
                            root.containsKey("reservedNamesRefused"),
                            () -> label + ": reservedNamesRefused present: " + root.encode());
                });
            }
        }
        assertAll("flat body", checks);
    }

    /** (b) A body whose aliased member's type carries a guard: every guard copy is removed. */
    private static void aliasCopiedGuardsAreAllRemoved() {
        guardCopiesAreAllRemoved(
                "aliased body",
                "createAliased",
                AliasedGuardZx.class,
                ALIASED_GUARD_COPIES,
                ALIASED_NON_ASCII_REFUSALS);
    }

    /**
     * (c) A case-insensitive body whose fold publication copies a guarded child: every guard copy is
     * removed and the non-ASCII refusal stays.
     */
    private static void foldCopiedGuardsAreAllRemovedAndTheNonAsciiRefusalStays() {
        guardCopiesAreAllRemoved(
                "fold-copy body",
                "createFoldCopy",
                FoldCopyGuardZx.class,
                FOLD_COPY_GUARD_COPIES,
                FOLD_COPY_NON_ASCII_REFUSALS);
    }

    /**
     * Proves cases (b) and (c): the original holds the expected guard copies, the manifest lists
     * exactly their locations, and no rendering holds a guard or the hidden name, while every
     * non-ASCII refusal is kept.
     *
     * @param label            the case label
     * @param operationId      the operation id
     * @param type             the body type
     * @param guardCopies      the expected guard copies in the original
     * @param nonAsciiRefusals the expected non-ASCII refusals in the original and every rendering
     */
    private static void guardCopiesAreAllRemoved(
            String label, String operationId, Class<?> type, int guardCopies, int nonAsciiRefusals) {
        // Given: the generated body holds the guard copies, each listed in its manifest, and names the
        // hidden member (positive control).
        GeneratedBody body = GeneratedBodies.describe(type);
        JsonNode original = tree(body.schema());
        List<String> guards = guardPointers(original);
        assertTrue(guards.size() >= 2, () -> label + " precondition: fewer than two guard copies " + guards);
        assertEquals(guardCopies, guards.size(), () -> label + " precondition: guard copies " + guards);
        assertEquals(
                guardCopies,
                body.manifest().pointers().size(),
                () -> label + " precondition: manifest pointers "
                        + body.manifest().pointers());
        assertEquals(
                guards.stream().sorted().toList(),
                body.manifest().pointers(),
                () -> label + " precondition: the manifest does not list exactly the guard copies");
        assertEquals(
                nonAsciiRefusals,
                nonAsciiRefusals(original),
                () -> label + " precondition: non-ASCII refusals in " + body.schema());
        assertTrue(body.schema().encode().contains(SECRET), () -> label + " precondition: no " + SECRET);
        Publications.Built built = bodyPublication(operationId, type, body, null);

        // When: the document is assembled in protected and in public mode.
        List<Executable> checks = new ArrayList<>();
        for (ApiDocs.Access mode : MODES) {
            Rendering rendering = DisclosureDocuments.render(built, mode, DisclosureDocuments.noSource());
            String at = label + " " + mode;

            // Then: every non-ASCII refusal is kept in both forms.
            checks.add(() -> assertEquals(
                    nonAsciiRefusals,
                    nonAsciiRefusals(rendering.jsonTree()),
                    () -> at + ": non-ASCII refusals in the JSON form"));
            checks.add(() -> assertEquals(
                    nonAsciiRefusals,
                    nonAsciiRefusals(rendering.yamlTree()),
                    () -> at + ": non-ASCII refusals in the YAML form"));
            // Then: no guard remains anywhere in either form, and the hidden name is in no byte.
            checks.add(() -> assertEquals(
                    List.of(), guardPointers(rendering.jsonTree()), () -> at + ": guards in the JSON form"));
            checks.add(() -> assertEquals(
                    List.of(), guardPointers(rendering.yamlTree()), () -> at + ": guards in the YAML form"));
            checks.add(() -> assertFalse(rendering.contains(SECRET), () -> at + ": " + SECRET + " is published"));
            if (mode == ApiDocs.Access.PROTECTED) {
                checks.add(() -> {
                    JsonObject root = rendering.rootValidation();
                    assertNotNull(root, () -> at + ": no root x-vertique-validation");
                    assertEquals(
                            Boolean.TRUE,
                            root.getValue("reservedNamesRefused"),
                            () -> at + ": reservedNamesRefused in " + root.encode());
                });
            } else {
                // Then (public): the root records the pattern dialect only, although a guard was removed.
                checks.add(() -> {
                    JsonObject root = rendering.rootValidation();
                    assertNotNull(root, () -> at + ": no root x-vertique-validation");
                    assertEquals(new JsonObject(PUBLIC_ROOT), root, () -> at + ": the public root " + root.encode());
                });
                checks.add(() -> assertEquals(
                        tree(new JsonObject(PUBLIC_ROOT)),
                        rendering.yamlTree().get("x-vertique-validation"),
                        () -> at + ": the public YAML root"));
            }
        }
        assertAll(label + " guard copies", checks);
    }

    /**
     * (d) A member whose type the operation's profile overrides with a fragment declaring its own
     * {@code propertyNames}: the fragment is published unchanged and is not in the manifest.
     */
    private static void userDeclaredPropertyNamesStays() {
        // Given: the notes body described under the tags-zx profile; the override fragment sits at
        // /properties/tags, the manifest lists the root guard only, and the original names both
        // unpublished members (positive control).
        String operationId = "createNotes";
        GeneratedBody body = GeneratedBodies.describe(NotesZx.class, TagsProfileModule.profile());
        JsonObject fragment = new JsonObject(TagsProfileModule.TAGS_FRAGMENT);
        assertEquals(
                fragment,
                body.schema().getJsonObject("properties").getJsonObject("tags"),
                () -> "user-declared propertyNames precondition: the original's tags schema in " + body.schema());
        assertEquals(
                NOTES_POINTERS,
                body.manifest().pointers(),
                () -> "user-declared propertyNames precondition: the manifest");
        String originalText = body.schema().encode();
        assertTrue(
                originalText.contains(INTERNAL_NOTE),
                () -> "user-declared propertyNames precondition: no " + INTERNAL_NOTE);
        assertTrue(
                originalText.contains(AUDIT_TRAIL),
                () -> "user-declared propertyNames precondition: no " + AUDIT_TRAIL);
        Publications.Built built = bodyPublication(operationId, NotesZx.class, body, TagsProfileModule.TAGS_PROFILE);

        // Then: the manifest lists nothing inside the tags schema.
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertTrue(
                body.manifest().pointers().stream().noneMatch(pointer -> pointer.startsWith("/properties/tags")),
                () -> "user-declared propertyNames: the manifest lists the user-declared propertyNames: "
                        + body.manifest()));

        // When: the document is assembled in protected and in public mode.
        for (ApiDocs.Access mode : MODES) {
            Rendering rendering = DisclosureDocuments.render(built, mode, DisclosureDocuments.noSource());
            String at = "user-declared propertyNames " + mode;

            // Then: the user-declared fragment is published unchanged in both forms.
            checks.add(() -> assertEquals(
                    fragment,
                    component(rendering, operationId)
                            .getJsonObject("properties")
                            .getJsonObject("tags"),
                    () -> at + ": the tags schema changed in the JSON form"));
            checks.add(() -> assertEquals(
                    tree(fragment),
                    rendering
                            .yamlTree()
                            .path("components")
                            .path("schemas")
                            .path(operationId + ".request")
                            .path("properties")
                            .path("tags"),
                    () -> at + ": the tags schema changed in the YAML form"));
            // Then: neither unpublished member is in any byte.
            checks.add(() ->
                    assertFalse(rendering.contains(INTERNAL_NOTE), () -> at + ": " + INTERNAL_NOTE + " is published"));
            checks.add(() ->
                    assertFalse(rendering.contains(AUDIT_TRAIL), () -> at + ": " + AUDIT_TRAIL + " is published"));
        }
        assertAll("user-declared propertyNames", checks);
    }

    /**
     * (e) A recursive body with a schema-hidden member: it publishes, the hidden name is in no
     * rendering, and every reference resolves within the document.
     */
    private static void recursiveBodyIsRedactedAndItsReferencesResolve() {
        // Given: the recursive body refers to its own root with "#", holds no $defs, and names the
        // hidden member (positive control).
        String operationId = "createNode";
        GeneratedBody body = GeneratedBodies.describe(NodeZx.class);
        assertFalse(body.schema().containsKey("$defs"), () -> "recursive body precondition: $defs in " + body.schema());
        assertEquals(
                "#",
                body.schema()
                        .getJsonObject("properties")
                        .getJsonObject("children")
                        .getJsonObject("items")
                        .getString("$ref"),
                () -> "recursive body precondition: the recursive reference in " + body.schema());
        assertTrue(body.schema().encode().contains(SECRET), () -> "recursive body precondition: no " + SECRET);
        Publications.Built built = bodyPublication(operationId, NodeZx.class, body, null);
        String relocatedRoot = "#/components/schemas/" + operationId + ".request";

        // When: the document is assembled in protected and in public mode.
        List<Executable> checks = new ArrayList<>();
        for (ApiDocs.Access mode : MODES) {
            String at = "recursive body " + mode;
            Rendering rendering = DisclosureDocuments.render(built, mode, DisclosureDocuments.noSource());

            // Then: the recursive reference names the relocated component, and every reference in
            // either form resolves within that form's document.
            checks.add(() -> assertEquals(
                    relocatedRoot,
                    component(rendering, operationId)
                            .getJsonObject("properties")
                            .getJsonObject("children")
                            .getJsonObject("items")
                            .getString("$ref"),
                    () -> at + ": the relocated recursive reference"));
            checks.add(() -> assertReferencesResolve(at + " JSON", rendering.jsonTree()));
            checks.add(() -> assertReferencesResolve(at + " YAML", rendering.yamlTree()));
            // Then: the hidden name is in no byte.
            checks.add(() -> assertFalse(rendering.contains(SECRET), () -> at + ": " + SECRET + " is published"));
        }
        assertAll("recursive body", checks);
    }

    // ---------------------------------------------------------------------------------------------
    // The parameter propertyNames refusal
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A parameter schema holding the propertyNames keyword fails publication")
    void parameterSchemaWithPropertyNamesFailsPublication() {
        assertAll(
                "parameter propertyNames",
                ReservedNameRedactionTest::keywordInParameterSchemaFails,
                ReservedNameRedactionTest::keywordInFormFieldSchemaFails,
                () -> textOrNameIsNotTheKeyword("the name as data", NAME_AS_DATA),
                () -> textOrNameIsNotTheKeyword("the name as a property name", NAME_AS_PROPERTY));
    }

    /** (a) The keyword inside a property's schema fails naming mount, operation, location, and name. */
    private static void keywordInParameterSchemaFails() {
        // Given: a query parameter whose captured schema holds the keyword below a property.
        Publications.Built built = queryPublication(new JsonObject(KEYWORD_IN_PROPERTY));

        List<Executable> checks = new ArrayList<>();
        for (ApiDocs.Access mode : MODES) {
            String at = "parameter schema " + mode;
            checks.add(() -> {
                // When: the document is assembled.
                RestConfigurationException failure =
                        DisclosureDocuments.failure(built, mode, DisclosureDocuments.noSource());

                // Then: the failure names the mount, the operation, the location, and the parameter,
                // and quotes no schema text.
                String message = failure.getMessage();
                assertNotNull(message, () -> at + ": the failure has no message");
                assertAll(
                        at,
                        () -> assertTrue(message.contains(MOUNT_FRAGMENT), () -> at + ": no mount: " + message),
                        () -> assertTrue(
                                message.contains("'" + FIND_OPERATION + "'"), () -> at + ": no operation: " + message),
                        () -> assertTrue(message.contains("query"), () -> at + ": no location: " + message),
                        () -> assertTrue(
                                message.contains("'" + PARAMETER + "'"), () -> at + ": no parameter: " + message),
                        () -> assertFalse(message.contains("{"), () -> at + ": schema text: " + message),
                        () -> assertFalse(message.contains("maxLength"), () -> at + ": schema text: " + message));
            });
        }
        assertAll("the keyword in a parameter schema", checks);
    }

    /** (d) The keyword inside a form field's schema fails naming mount, operation, and the form field. */
    private static void keywordInFormFieldSchemaFails() {
        // Given: a URL-encoded form field whose captured schema holds the keyword below a property.
        Publications.Built built = Publications.mount(MOUNT)
                .application(APPLICATION, UnitDocumentedApi.class)
                .operation("POST", "/items", SUBMIT_OPERATION)
                .consumes(URL_ENCODED)
                .formField(PARAMETER)
                .schema(new JsonObject(KEYWORD_IN_PROPERTY))
                .build();

        List<Executable> checks = new ArrayList<>();
        for (ApiDocs.Access mode : MODES) {
            String at = "form-field schema " + mode;
            checks.add(() -> {
                // When: the document is assembled.
                RestConfigurationException failure =
                        DisclosureDocuments.failure(built, mode, DisclosureDocuments.noSource());

                // Then: the failure names the mount, the operation, and the form field, and quotes no
                // schema text.
                String message = failure.getMessage();
                assertNotNull(message, () -> at + ": the failure has no message");
                assertAll(
                        at,
                        () -> assertTrue(message.contains(MOUNT_FRAGMENT), () -> at + ": no mount: " + message),
                        () -> assertTrue(
                                message.contains("'" + SUBMIT_OPERATION + "'"),
                                () -> at + ": no operation: " + message),
                        () -> assertTrue(
                                message.contains("form field '" + PARAMETER + "'"),
                                () -> at + ": no form field: " + message),
                        () -> assertFalse(message.contains("{"), () -> at + ": schema text: " + message));
            });
        }
        assertAll("the keyword in a form-field schema", checks);
    }

    /** (b), (c) The text {@code propertyNames} as data or as a property name publishes unchanged. */
    private static void textOrNameIsNotTheKeyword(String label, String schema) {
        // Given: a query parameter whose captured schema holds the text, not the keyword.
        Publications.Built built = queryPublication(new JsonObject(schema));

        List<Executable> checks = new ArrayList<>();
        for (ApiDocs.Access mode : MODES) {
            String at = label + " " + mode;
            checks.add(() -> {
                // When: the document is assembled.
                Rendering rendering = DisclosureDocuments.render(built, mode, DisclosureDocuments.noSource());

                // Then: the parameter is published with the captured schema, unchanged.
                JsonObject paths = rendering.document().getJsonObject("paths");
                assertNotNull(paths, () -> at + ": no paths");
                JsonArray parameters =
                        paths.getJsonObject("/items").getJsonObject("get").getJsonArray("parameters");
                assertNotNull(parameters, () -> at + ": no parameters");
                assertEquals(1, parameters.size(), () -> at + ": parameters " + parameters);
                assertEquals(
                        new JsonObject(schema),
                        parameters.getJsonObject(0).getJsonObject("schema"),
                        () -> at + ": the published parameter schema");
            });
        }
        assertAll(label, checks);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds a publication of one {@code POST} operation whose body is the generated schema with its
     * manifest as provenance and whose body binding has the described type.
     *
     * @param operationId the operation id
     * @param type        the described body type
     * @param body        the generated body
     * @param profileId   the operation's profile id, or {@code null} for the default
     * @return the built publication
     */
    private static Publications.Built bodyPublication(
            String operationId, Type type, GeneratedBody body, @Nullable String profileId) {
        Publications.OperationBuilder operation = Publications.mount(MOUNT)
                .application(APPLICATION, UnitDocumentedApi.class)
                .operation("POST", "/items", operationId)
                .consumes("application/json");
        if (profileId != null) {
            operation.profileId(profileId);
        }
        Publications.Built built = operation.body(body).build();
        return DisclosurePublications.from(built).bodyType(operationId, type).build();
    }

    /**
     * Builds a publication of {@code GET /items} with one query parameter capturing the given schema.
     *
     * @param schema the captured parameter schema
     * @return the built publication
     */
    private static Publications.Built queryPublication(JsonObject schema) {
        return Publications.mount(MOUNT)
                .application(APPLICATION, UnitDocumentedApi.class)
                .operation("GET", "/items", FIND_OPERATION)
                .param(ParamLocation.QUERY, PARAMETER, Requiredness.UNKNOWN)
                .schema(schema)
                .build();
    }

    /**
     * Returns the request-body component of an operation from the JSON form, failing when absent.
     *
     * @param rendering   the rendering
     * @param operationId the operation id
     * @return the component schema
     */
    private static JsonObject component(Rendering rendering, String operationId) {
        JsonObject doc = rendering.document();
        JsonObject components = doc.getJsonObject("components");
        assertNotNull(components, () -> "no components in " + operationId + "'s document");
        JsonObject schemas = components.getJsonObject("schemas");
        assertNotNull(schemas, () -> "no component schemas in " + operationId + "'s document");
        JsonObject component = schemas.getJsonObject(operationId + ".request");
        assertNotNull(component, () -> "no component " + operationId + ".request; components " + schemas.fieldNames());
        return component;
    }

    /**
     * Returns the JSON Pointer of every guard in a tree, in walk order: a {@code propertyNames} value
     * {@code {"not": {"enum": [...]}}} (the pointer ends in {@code /propertyNames}), or an element of
     * {@code propertyNames.allOf} that is such a value or {@code {"not": {"pattern": p}}} with {@code
     * p} other than the non-ASCII refusal (the pointer ends in {@code /propertyNames/allOf/<i>}).
     *
     * @param root the tree
     * @return the pointers
     */
    private static List<String> guardPointers(JsonNode root) {
        List<String> pointers = new ArrayList<>();
        collectGuards(root, "", pointers);
        return pointers;
    }

    private static void collectGuards(JsonNode node, String pointer, List<String> pointers) {
        if (node.isObject()) {
            JsonNode names = node.get("propertyNames");
            if (names != null && names.isObject()) {
                String at = pointer + "/propertyNames";
                if (isGuard(names)) {
                    pointers.add(at);
                }
                JsonNode allOf = names.get("allOf");
                if (allOf != null && allOf.isArray()) {
                    for (int i = 0; i < allOf.size(); i++) {
                        if (isGuard(allOf.get(i))) {
                            pointers.add(at + "/allOf/" + i);
                        }
                    }
                }
            }
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                collectGuards(field.getValue(), pointer + "/" + escape(field.getKey()), pointers);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectGuards(node.get(i), pointer + "/" + i, pointers);
            }
        }
    }

    /**
     * Reports whether a schema is a guard: {@code {"not": {"enum": [...]}}}, or {@code {"not":
     * {"pattern": p}}} with {@code p} other than the non-ASCII refusal.
     *
     * @param schema the schema
     * @return whether it is a guard
     */
    private static boolean isGuard(JsonNode schema) {
        JsonNode not = schema.get("not");
        if (not == null || !not.isObject()) {
            return false;
        }
        if (not.has("enum")) {
            return true;
        }
        JsonNode pattern = not.get("pattern");
        return pattern != null && pattern.isTextual() && !NON_ASCII_PATTERN.equals(pattern.asText());
    }

    /**
     * Counts the {@code pattern} members whose value is the non-ASCII refusal's pattern.
     *
     * @param node the tree
     * @return the count
     */
    private static int nonAsciiRefusals(JsonNode node) {
        int count = 0;
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if ("pattern".equals(field.getKey())
                        && field.getValue().isTextual()
                        && NON_ASCII_PATTERN.equals(field.getValue().asText())) {
                    count++;
                }
                count += nonAsciiRefusals(field.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                count += nonAsciiRefusals(element);
            }
        }
        return count;
    }

    /**
     * Asserts that the document holds at least one {@code $ref} and that every one is a fragment
     * reference whose JSON Pointer resolves against the document root.
     *
     * @param label    the case and form
     * @param document the document tree
     */
    private static void assertReferencesResolve(String label, JsonNode document) {
        List<String> references = new ArrayList<>();
        collectReferences(document, references);
        assertFalse(references.isEmpty(), () -> label + ": the document holds no reference");
        for (String reference : references) {
            assertTrue(reference.startsWith("#"), () -> label + ": a reference leaves the document: " + reference);
            String pointer = reference.substring(1);
            assertFalse(
                    document.at(pointer).isMissingNode(),
                    () -> label + ": the reference " + reference + " does not resolve");
        }
    }

    private static void collectReferences(JsonNode node, List<String> references) {
        if (node.isObject()) {
            JsonNode reference = node.get("$ref");
            if (reference != null && reference.isTextual()) {
                references.add(reference.asText());
            }
            for (JsonNode child : node) {
                collectReferences(child, references);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                collectReferences(element, references);
            }
        }
    }

    /**
     * Escapes a member name as an RFC 6901 reference token.
     *
     * @param name the member name
     * @return the token
     */
    private static String escape(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }

    /**
     * Parses a Vert.x JSON object into a Jackson tree.
     *
     * @param object the object
     * @return the tree
     */
    private static JsonNode tree(JsonObject object) {
        try {
            return JSON.readTree(object.encode());
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
