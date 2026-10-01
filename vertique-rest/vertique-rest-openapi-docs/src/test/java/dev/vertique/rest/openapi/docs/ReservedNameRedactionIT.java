// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.json.schema.RedactionManifest;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.openapi.docs.DisclosureDocuments.Rendering;
import dev.vertique.rest.openapi.docs.RedactionTestComponents.RecordingProvisions;
import dev.vertique.rest.openapi.docs.RedactionTestComponents.RenderingProvisions;
import dev.vertique.rest.openapi.docs.RedactionTestComponents.VerticleProvisions;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.disclosure.it.redaction.FoldsApi;
import dev.vertique.rest.openapi.docs.fixture.disclosure.it.redaction.FoldsResource;
import dev.vertique.rest.openapi.docs.fixture.disclosure.it.redaction.NotesApi;
import dev.vertique.rest.openapi.docs.fixture.disclosure.it.redaction.NotesResource;
import dev.vertique.rest.openapi.docs.fixture.disclosure.it.redaction.OrderZx;
import dev.vertique.rest.openapi.docs.fixture.disclosure.it.redaction.OrdersApi;
import dev.vertique.rest.openapi.docs.fixture.disclosure.it.redaction.OrdersResource;
import dev.vertique.rest.openapi.docs.fixture.disclosure.sources.ForeignProvenanceSchemaSource;
import dev.vertique.rest.openapi.docs.fixture.disclosure.sources.InPlaceEditingSchemaSource;
import dev.vertique.rest.openapi.docs.fixture.disclosure.sources.ManifestFreeSchemaSource;
import dev.vertique.rest.openapi.docs.fixture.disclosure.sources.ReplacingSchemaSource;
import dev.vertique.rest.openapi.docs.fixture.input.RecordingSchemaSource;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupDeployments;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupDeployments.Outcome;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

/**
 * Deploys declared applications under {@code web-validation} over real, loopback-bound {@code
 * HttpVerticle} deployments and checks that the reserved names a body's guard refuses never reach a
 * document while the gate still refuses them, and that a body schema whose redaction manifest cannot
 * be verified fails publication.
 *
 * <p>Public documents are fetched over HTTP from a component that serves them; protected documents
 * are read from a component that renders them without serving (its rendering sink), deployed and
 * undeployed before the serving one. The gate's original body schema, its manifest, and the body
 * object the gate received are read from the serving component's recording schema source. Every
 * expected value is a fixed literal; the search strings for a case-folded name are derived from the
 * name by one helper.
 *
 * <p>One Vert.x instance and one client serve the class; every deployment clears the {@code
 * vertique} local map before it starts and after it is undeployed.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ReservedNameRedactionIT {

    private static final String HOST = "127.0.0.1";

    /** The name of the root member that records the document's validation facts. */
    private static final String VALIDATION_MEMBER = "x-vertique-validation";

    /** The root {@value #VALIDATION_MEMBER} of every public document: the pattern dialect only. */
    private static final JsonObject PUBLIC_ROOT = new JsonObject().put("patternDialect", "java.util.regex");

    /** The schema-hidden member of the notes body. */
    private static final String INTERNAL_NOTE = "internalNoteZx";

    /** The ignored member of the notes body. */
    private static final String AUDIT_TRAIL = "auditTrailZx";

    /** The schema-hidden member of the case-insensitively bound folds body. */
    private static final String SECRET_TOKEN = "secretTokenZx";

    /** The developer-declared {@code propertyNames} of the notes body's {@code tags} member, verbatim. */
    private static final JsonObject DECLARED_TAGS_NAMES = new JsonObject().put("pattern", "^[a-z]+$");

    /** The pattern refusing every key carrying a non-ASCII code unit, as a string value. */
    private static final String NON_ASCII_REFUSAL = "[^\\x00-\\x7F]";

    /** The status a {@code void} resource method answers with. */
    private static final int NO_CONTENT = 204;

    /** The status the gate answers a refused body with. */
    private static final int BAD_REQUEST = 400;

    /** The mount path of the application {@code orders}. */
    private static final String ORDERS_MOUNT_PATH = OrdersApi.PATH + "/*";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Vertx vertx;
    private static WebClient client;

    @BeforeAll
    static void startClient(Vertx sharedVertx) {
        vertx = sharedVertx;
        client = WebClient.create(vertx);
    }

    @AfterAll
    static void closeClient() {
        client.close();
    }

    // ---------------------------------------------------------------------------------------------
    // The case-sensitive guard
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "The case-sensitive guard naming a hidden and an ignored member is removed from public and protected documents, a declared propertyNames stays, and the gate still refuses both names")
    void caseSensitiveGuardIsRemovedAndTheGateStillRefuses() throws Exception {
        // Given: POST /notes taking a body with a schema-hidden member, an ignored member, a published
        // member, a member described by a profile fragment declaring its own propertyNames, and extras;
        // web-validation with the canonical source wrapped by a recording source; a public document
        // served, and the same application's protected document rendered without serving
        RedactionTestComponents.ProtectedNotesComponent rendering =
                DaggerRedactionTestComponents_ProtectedNotesComponent.factory()
                        .create(InputAssemblyIT.webValidationConfig());
        RedactionTestComponents.NotesComponent serving = DaggerRedactionTestComponents_NotesComponent.factory()
                .create(InputAssemblyIT.webValidationConfig(NotesApi.NAME));
        String bodyKey = NotesResource.ADD_NOTE + ".request";
        String uri = NotesApi.PATH + NotesResource.ROUTE;

        // When: the public JSON and YAML and the protected rendering are read, then three bodies posted
        Renderings renderings = renderings(rendering, serving, NotesApi.NAME);
        try {
            int port = renderings.port();
            Answer hiddenName = post(port, uri, new JsonObject().put(INTERNAL_NOTE, "x"));
            Answer ignoredName = post(port, uri, new JsonObject().put(AUDIT_TRAIL, "x"));
            Answer extra = post(port, uri, new JsonObject().put("title", "t").put("extraKey", 1));

            // Then (positive control): the gate's original refuses both names in its root guard and
            // carries the declared propertyNames of the tags member
            JsonObject original = originalBody(serving, NotesResource.ADD_NOTE);
            JsonObject guard = original.getJsonObject("propertyNames");
            assertNotNull(guard, () -> "the gate's original carries a root propertyNames: " + original.encode());
            JsonObject not = guard.getJsonObject("not");
            assertNotNull(not, () -> "the gate's original guard is a refusal: " + guard.encode());
            JsonArray reserved = not.getJsonArray("enum");
            assertNotNull(reserved, () -> "the gate's original guard lists names: " + guard.encode());
            assertTrue(reserved.contains(INTERNAL_NOTE), () -> "the guard refuses " + INTERNAL_NOTE + ": " + reserved);
            assertTrue(reserved.contains(AUDIT_TRAIL), () -> "the guard refuses " + AUDIT_TRAIL + ": " + reserved);
            assertEquals(
                    DECLARED_TAGS_NAMES,
                    tagsPropertyNames(original),
                    "the gate's original carries the profile's declared propertyNames for tags");

            // Then: neither name occurs in any rendering; the guard is gone where the original had it;
            // the declared propertyNames is published unchanged; the protected root counts the removal;
            // and the gate refuses both names
            Rendering publicForm = renderings.publicForm();
            Rendering protectedForm = renderings.protectedForm();
            Map<String, JsonObject> documents = new LinkedHashMap<>();
            documents.put("public JSON", publicForm.document());
            documents.put("public YAML", treeAsJson(publicForm.yamlTree()));
            documents.put("protected JSON", protectedForm.document());
            documents.put("protected YAML", treeAsJson(protectedForm.yamlTree()));

            List<Executable> checks = new ArrayList<>();
            for (String name : List.of(INTERNAL_NOTE, AUDIT_TRAIL)) {
                checks.add(() -> assertFalse(publicForm.jsonText().contains(name), "public JSON holds no " + name));
                checks.add(() -> assertFalse(publicForm.yamlText().contains(name), "public YAML holds no " + name));
                checks.add(
                        () -> assertFalse(protectedForm.jsonText().contains(name), "protected JSON holds no " + name));
                checks.add(
                        () -> assertFalse(protectedForm.yamlText().contains(name), "protected YAML holds no " + name));
            }
            for (Map.Entry<String, JsonObject> document : documents.entrySet()) {
                String form = document.getKey();
                checks.add(() -> {
                    JsonObject component = InputAssemblyIT.schemaComponent(document.getValue(), bodyKey);
                    assertFalse(
                            component.containsKey("propertyNames"),
                            () -> form + ": the body component carries no root propertyNames: " + component.encode());
                });
                checks.add(() -> assertEquals(
                        DECLARED_TAGS_NAMES,
                        tagsPropertyNames(InputAssemblyIT.schemaComponent(document.getValue(), bodyKey)),
                        form + ": the declared propertyNames of tags is published unchanged"));
            }
            checks.add(() -> {
                JsonObject validation = protectedForm.rootValidation();
                assertNotNull(validation, "the protected root carries " + VALIDATION_MEMBER);
                assertEquals(
                        Boolean.TRUE,
                        validation.getValue("reservedNamesRefused"),
                        () -> "the protected root records the removal: " + validation.encode());
            });
            checks.add(() -> assertEquals(
                    PUBLIC_ROOT,
                    publicForm.rootValidation(),
                    "the public JSON root records the pattern dialect only, although a guard was removed"));
            checks.add(() -> assertEquals(
                    PUBLIC_ROOT,
                    documents.get("public YAML").getJsonObject(VALIDATION_MEMBER),
                    "the public YAML root records the pattern dialect only, although a guard was removed"));
            checks.add(
                    () -> assertEquals(BAD_REQUEST, hiddenName.status(), "the hidden name is refused: " + hiddenName));
            checks.add(() ->
                    assertEquals(BAD_REQUEST, ignoredName.status(), "the ignored name is refused: " + ignoredName));
            checks.add(() -> assertEquals(NO_CONTENT, extra.status(), "an undeclared extra is accepted: " + extra));
            checks.add(() -> assertFalse(
                    hiddenName.body().contains(INTERNAL_NOTE), "the refusal does not echo " + INTERNAL_NOTE));
            checks.add(() ->
                    assertFalse(ignoredName.body().contains(AUDIT_TRAIL), "the refusal does not echo " + AUDIT_TRAIL));
            assertAll("the redacted notes documents and the gate's answers", checks.stream());
        } finally {
            undeploy(renderings.serving());
        }
    }

    /** Returns {@code properties.tags.propertyNames} of a body schema, asserting each step exists. */
    private static JsonObject tagsPropertyNames(JsonObject body) {
        JsonObject properties = body.getJsonObject("properties");
        assertNotNull(properties, () -> "the body describes its properties: " + body.encode());
        JsonObject tags = properties.getJsonObject("tags");
        assertNotNull(tags, () -> "the body describes tags: " + properties.encode());
        return tags.getJsonObject("propertyNames");
    }

    // ---------------------------------------------------------------------------------------------
    // The case-insensitive fold
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "The case-insensitive fold of a hidden name is removed from public and protected documents, the non-ASCII refusal stays, the gate answers as before, and the gate's body still matches its manifest")
    void caseInsensitiveFoldIsRemovedAndTheNonAsciiRefusalStays() throws Exception {
        // Given: a case-insensitively bound body with a schema-hidden member, a published member, and
        // extras; a public document served and the protected document rendered without serving
        RedactionTestComponents.ProtectedFoldsComponent rendering =
                DaggerRedactionTestComponents_ProtectedFoldsComponent.factory()
                        .create(InputAssemblyIT.webValidationConfig());
        RedactionTestComponents.FoldsComponent serving = DaggerRedactionTestComponents_FoldsComponent.factory()
                .create(InputAssemblyIT.webValidationConfig(FoldsApi.NAME));
        String uri = FoldsApi.PATH + FoldsResource.ROUTE;
        String foldRun = foldRun(SECRET_TOKEN, 3);
        String lowerName = SECRET_TOKEN.toLowerCase(Locale.ROOT);
        String lowerFoldRun = foldRun.toLowerCase(Locale.ROOT);

        // When: the public JSON and YAML and the protected rendering are read, then three bodies posted
        Renderings renderings = renderings(rendering, serving, FoldsApi.NAME);
        try {
            int port = renderings.port();
            Answer upperCase = post(port, uri, new JsonObject().put(SECRET_TOKEN.toUpperCase(Locale.ROOT), "x"));
            Answer nonAscii = post(port, uri, new JsonObject().put(String.valueOf((char) 0xE9), "x"));
            Answer extra = post(port, uri, new JsonObject().put("label", "l").put("extraKey", 1));

            // Then (positive control): the gate's original holds the fold run once, its manifest lists
            // exactly as many pointers, and the original holds the non-ASCII refusal once
            RecordingSchemaSource.Recording recording =
                    serving.recordingSource().recording(FoldsResource.SUBMIT_FOLD);
            JsonObject original = originalBody(serving, FoldsResource.SUBMIT_FOLD);
            JsonNode originalTree = JSON.readTree(original.encode());
            RedactionManifest manifest = assertInstanceOf(
                    RedactionManifest.class,
                    recording.bodyProvenance(),
                    "the gate's original carries the generator's manifest");
            int foldAssertions = countStrings(originalTree, true, text -> text.contains(foldRun));
            assertEquals(1, foldAssertions, () -> "the gate's original holds the fold run " + foldRun + " once");
            assertEquals(
                    foldAssertions,
                    manifest.pointers().size(),
                    () -> "the manifest lists one pointer per fold assertion: " + manifest.pointers());
            assertEquals(
                    1,
                    countStrings(originalTree, false, NON_ASCII_REFUSAL::equals),
                    "the gate's original holds the non-ASCII refusal once");

            // Then: no rendering holds the name or its fold run in any letter case, each keeps the
            // non-ASCII refusal exactly as often as the original, and the gate answers as before
            Map<String, Rendering> forms = new LinkedHashMap<>();
            forms.put("public", renderings.publicForm());
            forms.put("protected", renderings.protectedForm());
            List<Executable> checks = new ArrayList<>();
            for (Map.Entry<String, Rendering> form : forms.entrySet()) {
                String label = form.getKey();
                Rendering rendered = form.getValue();
                String json = rendered.jsonText().toLowerCase(Locale.ROOT);
                String yaml = rendered.yamlText().toLowerCase(Locale.ROOT);
                checks.add(() -> assertFalse(json.contains(lowerName), label + " JSON holds no " + lowerName));
                checks.add(() -> assertFalse(yaml.contains(lowerName), label + " YAML holds no " + lowerName));
                checks.add(() -> assertFalse(json.contains(lowerFoldRun), label + " JSON holds no " + lowerFoldRun));
                checks.add(() -> assertFalse(yaml.contains(lowerFoldRun), label + " YAML holds no " + lowerFoldRun));
                checks.add(() -> assertEquals(
                        0,
                        countStrings(rendered.yamlTree(), true, text -> text.toLowerCase(Locale.ROOT)
                                .contains(lowerFoldRun)),
                        label + " YAML holds no string with the fold run"));
                checks.add(() -> assertEquals(
                        1,
                        countStrings(rendered.jsonTree(), false, NON_ASCII_REFUSAL::equals),
                        label + " JSON holds the non-ASCII refusal once"));
                checks.add(() -> assertEquals(
                        1,
                        countStrings(rendered.yamlTree(), false, NON_ASCII_REFUSAL::equals),
                        label + " YAML holds the non-ASCII refusal once"));
            }
            checks.add(() -> assertEquals(
                    BAD_REQUEST, upperCase.status(), "the hidden name in upper case is refused: " + upperCase));
            checks.add(() -> assertEquals(BAD_REQUEST, nonAscii.status(), "a non-ASCII key is refused: " + nonAscii));
            checks.add(() -> assertEquals(NO_CONTENT, extra.status(), "an undeclared extra is accepted: " + extra));
            checks.add(() -> assertTrue(
                    manifest.matches(recording.body().encode()),
                    "after the requests, the body the source returned still matches its manifest"));
            assertAll("the redacted folds documents and the gate's answers", checks.stream());
        } finally {
            undeploy(renderings.serving());
        }
    }

    /**
     * Derives the case-fold run of a name's first characters as the generator spells it, one
     * character class per letter holding its lower- and upper-case form, for example {@code
     * [sS][eE][cC]} for {@code secretTokenZx} and length three.
     *
     * @param name   an ASCII-letter name
     * @param length how many leading characters the run covers
     * @return the search string
     */
    private static String foldRun(String name, int length) {
        StringBuilder run = new StringBuilder();
        for (int index = 0; index < length; index++) {
            char letter = name.charAt(index);
            run.append('[')
                    .append(Character.toLowerCase(letter))
                    .append(Character.toUpperCase(letter))
                    .append(']');
        }
        return run.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // Unverifiable body schemas
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "A body schema without a manifest, a replaced or edited body, or a foreign provenance fails publication naming mount, operation, and source; without an enabled document the application answers as without the docs module")
    void unverifiableBodySchemasFailPublication() throws Exception {
        // Given: the application orders with POST /orders, an enabled public document, and one source
        // per composition; and, as controls, the manifest-free source with the document switched off
        // and the same source without the documentation module
        JsonObject documented = InputAssemblyIT.webValidationConfig(OrdersApi.NAME);
        List<Refusal> refusals = List.of(
                new Refusal(
                        "a body schema without provenance",
                        ManifestFreeSchemaSource.class,
                        () -> DaggerRedactionTestComponents_OrdersManifestFreeComponent.factory()
                                .create(documented.copy())),
                new Refusal(
                        "a replaced body carrying the canonical manifest",
                        ReplacingSchemaSource.class,
                        () -> DaggerRedactionTestComponents_OrdersReplacingComponent.factory()
                                .create(documented.copy())),
                new Refusal(
                        "a string as provenance",
                        ForeignProvenanceSchemaSource.class,
                        () -> DaggerRedactionTestComponents_OrdersForeignProvenanceComponent.factory()
                                .create(documented.copy())),
                // Last: this source edits the object its delegate returned.
                new Refusal(
                        "the canonical body edited in place",
                        InPlaceEditingSchemaSource.class,
                        () -> DaggerRedactionTestComponents_OrdersInPlaceEditingComponent.factory()
                                .create(documented.copy())));
        Supplier<VerticleProvisions> withoutDocs =
                () -> DaggerRedactionTestComponents_OrdersWithoutDocsComponent.factory()
                        .create(InputAssemblyIT.webValidationConfig());
        Supplier<VerticleProvisions> documentOff =
                () -> DaggerRedactionTestComponents_OrdersManifestFreeComponent.factory()
                        .create(DocsConfigs.withDocumentEnabled(documented.copy(), OrdersApi.NAME, false));

        // When: each composition is deployed; the controls also answer a valid and an invalid body
        // and the document URL
        Observed reference = observe("without the documentation module", withoutDocs);
        Observed switchedOff = observe("the document switched off", documentOff);
        Map<Refusal, Outcome> outcomes = new LinkedHashMap<>();
        for (Refusal refusal : refusals) {
            Outcome outcome = deploy(refusal.component().get()::httpVerticle);
            undeploy(outcome);
            outcomes.put(refusal, outcome);
            // Evidence: the refusal message alone, which by design quotes no schema value.
            Throwable failure = outcome.failure();
            System.out.println("FAILURE " + refusal.label() + ": " + (failure == null ? null : failure.getMessage()));
        }

        // Then: each unverifiable body fails deployment before a port is published, with a message
        // naming mount, operation, and source class and echoing no schema content or member name
        List<Executable> checks = new ArrayList<>();
        for (Map.Entry<Refusal, Outcome> entry : outcomes.entrySet()) {
            Refusal refusal = entry.getKey();
            Outcome outcome = entry.getValue();
            checks.add(() -> assertRefused(refusal, outcome));
        }

        // Then: without an enabled document the composition deploys and answers as without the
        // documentation module
        checks.add(() -> assertEquals(NO_CONTENT, reference.valid().status(), "reference: a valid order is accepted"));
        checks.add(() ->
                assertEquals(BAD_REQUEST, reference.invalid().status(), "reference: an invalid order is refused"));
        checks.add(() -> assertEquals(404, reference.document().status(), "reference: no document is served"));
        checks.add(() -> assertEquals(reference.valid(), switchedOff.valid(), "document off: a valid order"));
        checks.add(() -> assertEquals(reference.invalid(), switchedOff.invalid(), "document off: an invalid order"));
        checks.add(() -> assertEquals(reference.document(), switchedOff.document(), "document off: the document URL"));
        assertAll("the orders compositions", checks.stream());
    }

    private static void assertRefused(Refusal refusal, Outcome outcome) {
        String label = refusal.label();
        assertAll(
                label + ": deployment is refused before a port is published",
                () -> assertNotNull(outcome.failure(), label + ": the composition deployed"),
                () -> assertNull(outcome.port(), label + ": no port is published"));
        Throwable failure = outcome.failure();
        String message = failure.getMessage();
        assertNotNull(message, () -> label + ": the failure has a message: " + failure);
        String source = refusal.source().getName();
        assertAll(
                label + ": the message " + message,
                () -> assertInstanceOf(
                        RestConfigurationException.class, failure, () -> "is a configuration failure: " + failure),
                () -> assertTrue(
                        message.contains("'" + ORDERS_MOUNT_PATH + "'"), "names the mount " + ORDERS_MOUNT_PATH),
                () -> assertTrue(
                        message.contains("'" + OrdersResource.CREATE_ORDER + "'"),
                        "names the operation " + OrdersResource.CREATE_ORDER),
                () -> assertTrue(message.contains(source), "names the source " + source),
                () -> assertFalse(message.contains("{"), "echoes no structured value"),
                () -> assertFalse(message.contains("\"type\""), "echoes no schema keyword"),
                () -> assertFalse(message.contains(OrderZx.ITEM_CODE), "echoes no member " + OrderZx.ITEM_CODE),
                () -> assertFalse(message.contains(OrderZx.QUANTITY), "echoes no member " + OrderZx.QUANTITY));
    }

    /**
     * Deploys a control composition, which must deploy, sends it a valid and an invalid order and a
     * request for the document, and undeploys it.
     */
    private static Observed observe(String label, Supplier<VerticleProvisions> component) throws Exception {
        Outcome outcome = deploy(component.get()::httpVerticle);
        try {
            assertNull(
                    outcome.failure(), () -> label + ": the composition deploys; it failed with " + outcome.failure());
            assertNotNull(outcome.port(), () -> label + ": a port is published");
            int port = outcome.port();
            String uri = OrdersApi.PATH + OrdersResource.ROUTE;
            Answer valid = post(
                    port, uri, new JsonObject().put(OrderZx.ITEM_CODE, "A1").put(OrderZx.QUANTITY, 2));
            Answer invalid = post(
                    port, uri, new JsonObject().put(OrderZx.ITEM_CODE, "A1").put(OrderZx.QUANTITY, "many"));
            Answer document = answer(
                    client.get(port, HOST, InputAssemblyIT.documentPath(OrdersApi.NAME, InputAssemblyIT.JSON_FORM))
                            .send());
            return new Observed(valid, invalid, document);
        } finally {
            undeploy(outcome);
        }
    }

    /**
     * One composition that must refuse publication.
     *
     * @param label     what the bound source does
     * @param source    the bound source's class, which the message must name
     * @param component creates the composition's component
     */
    private record Refusal(String label, Class<?> source, Supplier<VerticleProvisions> component) {}

    /**
     * What a control composition answered.
     *
     * @param valid    a valid order
     * @param invalid  an order whose integer member is a string
     * @param document the document's JSON URL
     */
    private record Observed(Answer valid, Answer invalid, Answer document) {}

    // ---------------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Both renderings of one application and the serving deployment, which the caller undeploys.
     *
     * @param publicForm    the public document fetched over HTTP
     * @param protectedForm the protected document the rendering component kept
     * @param serving       the live serving deployment
     */
    private record Renderings(Rendering publicForm, Rendering protectedForm, Outcome serving) {

        int port() {
            return serving.port();
        }
    }

    /**
     * Reads the protected JSON and YAML bytes by deploying and undeploying the rendering component,
     * then deploys the serving component and fetches the public JSON and YAML bytes. The serving
     * deployment stays up for requests; the caller undeploys it.
     *
     * @param rendering   the component rendering the protected document without serving it
     * @param serving     the component serving the public document
     * @param application the application (and document) name
     * @return the renderings and the serving deployment
     */
    private static Renderings renderings(RenderingProvisions rendering, RecordingProvisions serving, String application)
            throws Exception {
        Outcome rendered = deploy(rendering::httpVerticle);
        Optional<String> failure;
        try {
            assertNull(
                    rendered.failure(),
                    () -> "the rendering composition deploys; it failed with " + rendered.failure());
            failure = rendering.protectedRendering().failure(application);
        } finally {
            undeploy(rendered);
        }
        Optional<String> assemblyFailure = failure;
        assertEquals(Optional.empty(), assemblyFailure, () -> "the protected document assembles: " + assemblyFailure);
        Rendering protectedForm = new Rendering(
                rendering.protectedRendering().json(application),
                rendering.protectedRendering().yaml(application));

        Outcome deployment = deploy(serving::httpVerticle);
        try {
            assertNull(
                    deployment.failure(),
                    () -> "the serving composition deploys; it failed with " + deployment.failure());
            assertNotNull(deployment.port(), "the serving composition publishes its port");
            int port = deployment.port();
            byte[] json = fetch(port, InputAssemblyIT.documentPath(application, InputAssemblyIT.JSON_FORM));
            byte[] yaml = fetch(port, InputAssemblyIT.documentPath(application, InputAssemblyIT.YAML_FORM));
            return new Renderings(new Rendering(json, yaml), protectedForm, deployment);
        } catch (Throwable failed) {
            undeploy(deployment);
            throw failed;
        }
    }

    /** Returns the gate's original body schema: the copy the recording source took when returning it. */
    private static JsonObject originalBody(RecordingProvisions component, String operationId) {
        JsonObject original = component.recordingSource().recording(operationId).bodyCopy();
        assertNotNull(original, () -> "the source returned a body schema for " + operationId);
        return original;
    }

    /**
     * Counts the string values of a tree a predicate accepts, and, when asked, the object member names
     * it accepts.
     */
    private static int countStrings(JsonNode node, boolean memberNames, Predicate<String> accepted) {
        if (node.isTextual()) {
            return accepted.test(node.asText()) ? 1 : 0;
        }
        int count = 0;
        if (node.isObject() && memberNames) {
            for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
                if (accepted.test(names.next())) {
                    count++;
                }
            }
        }
        for (JsonNode child : node) {
            count += countStrings(child, memberNames, accepted);
        }
        return count;
    }

    private static JsonObject treeAsJson(JsonNode tree) throws Exception {
        return new JsonObject(JSON.writeValueAsString(tree));
    }

    /**
     * One answer as the proofs compare it.
     *
     * @param status      the status code
     * @param contentType the {@code Content-Type} header, or {@code null}
     * @param body        the body as text, empty when there is none
     */
    private record Answer(int status, String contentType, String body) {}

    private static Answer post(int port, String uri, JsonObject body) throws Exception {
        return answer(client.post(port, HOST, uri).sendJsonObject(body));
    }

    private static byte[] fetch(int port, String uri) throws Exception {
        HttpResponse<Buffer> response = await(client.get(port, HOST, uri).send());
        assertEquals(200, response.statusCode(), () -> "GET " + uri + " answers 200");
        Buffer body = response.body();
        return body == null ? new byte[0] : body.getBytes();
    }

    private static Answer answer(Future<HttpResponse<Buffer>> response) throws Exception {
        HttpResponse<Buffer> received = await(response);
        Buffer body = received.body();
        return new Answer(
                received.statusCode(), received.getHeader("Content-Type"), body == null ? "" : body.toString());
    }

    private static <T> T await(Future<T> future) throws Exception {
        try {
            return future.toCompletionStage()
                    .toCompletableFuture()
                    .get(StartupDeployments.BOUND.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException failed) {
            throw new AssertionError("the request failed", failed.getCause());
        }
    }

    /**
     * Clears the {@code vertique} local map and deploys a verticle supplier.
     *
     * @param verticles the component's verticle supplier
     * @return the failure, or the published port
     */
    private static Outcome deploy(Supplier<Verticle> verticles) throws Exception {
        vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).clear();
        return StartupDeployments.deploy(vertx, verticles);
    }

    /** Undeploys a successful deployment, if any, and clears the {@code vertique} local map. */
    private static void undeploy(Outcome outcome) throws Exception {
        try {
            StartupDeployments.undeploy(vertx, outcome);
        } finally {
            vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).clear();
        }
    }
}
