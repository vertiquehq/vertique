// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.vertique.rest.jaxrs.publication.InputBinding.Origin;
import dev.vertique.rest.jaxrs.publication.InputBinding.Requiredness;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorRegistry;
import dev.vertique.rest.openapi.docs.InputTestComponents.FrozenComponent;
import dev.vertique.rest.openapi.docs.InputTestComponents.PatternsComponent;
import dev.vertique.rest.openapi.docs.InputTestComponents.TwinsComponent;
import dev.vertique.rest.openapi.docs.InputTestComponents.TwoApplicationsComponent;
import dev.vertique.rest.openapi.docs.InputTestComponents.VerbatimComponent;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.input.GeneratedBodies;
import dev.vertique.rest.openapi.docs.fixture.input.InputProjection;
import dev.vertique.rest.openapi.docs.fixture.input.PublishedParameter;
import dev.vertique.rest.openapi.docs.fixture.input.RecordingSchemaSource;
import dev.vertique.rest.openapi.docs.fixture.input.a.CatalogResource;
import dev.vertique.rest.openapi.docs.fixture.input.a.PublicApi;
import dev.vertique.rest.openapi.docs.fixture.input.b.OrdersResource;
import dev.vertique.rest.openapi.docs.fixture.input.b.PartnerApi;
import dev.vertique.rest.openapi.docs.fixture.input.it.ExpressionRequest;
import dev.vertique.rest.openapi.docs.fixture.input.it.FrozenApi;
import dev.vertique.rest.openapi.docs.fixture.input.it.FrozenResource;
import dev.vertique.rest.openapi.docs.fixture.input.it.GeneratedSearchResource;
import dev.vertique.rest.openapi.docs.fixture.input.it.GeneratedTwinApi;
import dev.vertique.rest.openapi.docs.fixture.input.it.ReflectedSearchResource;
import dev.vertique.rest.openapi.docs.fixture.input.it.ReflectedTwinApi;
import dev.vertique.rest.openapi.docs.fixture.input.it.TwinInputs;
import dev.vertique.rest.openapi.docs.fixture.input.it.VerbatimApi;
import dev.vertique.rest.openapi.docs.fixture.input.it.VerbatimResource;
import dev.vertique.rest.openapi.docs.fixture.input.patterns.FlaggedRequest;
import dev.vertique.rest.openapi.docs.fixture.input.patterns.FoldedRequest;
import dev.vertique.rest.openapi.docs.fixture.input.patterns.PatternsApi;
import dev.vertique.rest.openapi.docs.fixture.input.patterns.PatternsResource;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Serves documents of declared applications under {@code web-validation} over a real,
 * loopback-bound {@code HttpVerticle} deployment and checks the inputs they publish: Parameter
 * Objects against the binding inventory on both resource discovery paths, authored patterns and
 * their dialect, that every pattern value and {@code patternProperties} key the gate received is
 * published unchanged at its mapped position, that publication never writes to, or re-reads, the
 * schemas the gate received, and that two applications publish separate documents, each holding only
 * its own operations and components.
 *
 * <p>Expected values are fixed literals, or are read from what the test's own recorders kept: the
 * binding inventory the recording sink projected during the publication call, and the deep copies
 * the recording schema source took when it returned each schema. One Vert.x instance and one client
 * serve the class; each test deploys its own component's verticle and undeploys it, clearing the
 * {@code vertique} local map, before the next.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class InputAssemblyIT {

    private static final String HOST = "127.0.0.1";

    /** The name of the root member that records the document's validation facts. */
    private static final String VALIDATION_MEMBER = "x-vertique-validation";

    /** What a public document's root {@value #VALIDATION_MEMBER} member must be, exactly. */
    private static final JsonObject PUBLIC_VALIDATION = new JsonObject().put("patternDialect", "java.util.regex");

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

    // --- Composite inputs on both discovery paths ---

    @Test
    @DisplayName(
            "Both twin documents publish one Parameter Object per inventory entry, method parameters first, required only when certain, composite fields unenforced, and equal lists")
    void compositeInputsMatchTheBindingInventoryOnBothPaths() throws Exception {
        // Given: one twin described by its generated companion, the other by the reflective scanner.
        GeneratedJaxRsDescriptorRegistry registry = GeneratedJaxRsDescriptorRegistry.shared();
        assertTrue(
                registry.lookup(GeneratedSearchResource.class).isPresent(),
                "the generated twin must have a generated descriptor companion");
        assertTrue(
                registry.lookup(ReflectedSearchResource.class).isEmpty(),
                "the reflected twin must have no generated descriptor companion");
        TwinsComponent component = DaggerInputTestComponents_TwinsComponent.factory()
                .create(webValidationConfig(GeneratedTwinApi.NAME, ReflectedTwinApi.NAME));

        Deployment deployment = deploy(component::httpVerticle);
        try {
            // When: both documents are requested.
            JsonObject generated = fetchJsonDocument(deployment.port(), GeneratedTwinApi.NAME);
            JsonObject reflected = fetchJsonDocument(deployment.port(), ReflectedTwinApi.NAME);

            // Then: each operation's parameters match its own inventory, and the twins agree.
            List<PublishedParameter> generatedParameters = assertParametersMatchInventory(
                    component, generated, GeneratedTwinApi.NAME, GeneratedSearchResource.OPERATION_ID);
            List<PublishedParameter> reflectedParameters = assertParametersMatchInventory(
                    component, reflected, ReflectedTwinApi.NAME, ReflectedSearchResource.OPERATION_ID);
            assertEquals(
                    generatedParameters,
                    reflectedParameters,
                    "the generated and reflected twins must publish equal parameter lists");
        } finally {
            undeploy(deployment);
        }
    }

    /**
     * Checks one twin document's search operation: its Parameter Objects equal the recorded
     * inventory's expected parameters as one list; the order, locations, requiredness, description,
     * and schemas are the fixed facts of the twin bindings; and the only validation member is the
     * root's pattern dialect.
     *
     * @return the published parameters, for comparing the twins
     */
    private static List<PublishedParameter> assertParametersMatchInventory(
            InputTestComponents.InputProvisions component,
            JsonObject document,
            String application,
            String operationId) {
        String label = application + " " + operationId;
        JsonObject operation = operation(document, TwinInputs.ROUTE, "get");
        assertEquals(operationId, operation.getString("operationId"), () -> label + ": operation id");
        JsonArray parameters = operation.getJsonArray("parameters");
        assertNotNull(parameters, () -> label + ": the operation must publish its parameters");

        List<InputProjection> inventory = component.recordingSink().inventory(application, operationId);
        System.out.println(label + " inventory projection: " + inventory);
        System.out.println(label + " published parameters: " + parameters.encode());

        // The inventory is the oracle: one list equality, entry by entry, in published order.
        List<PublishedParameter> published = PublishedParameter.fromAll(parameters);
        assertEquals(
                InputProjection.expectedPublished(inventory),
                published,
                () -> label + ": the Parameter Objects must equal the inventory's, method parameters first");

        // The inventory is not vacuous: each input has the origin and requiredness its binding implies.
        assertInventoryEntry(inventory, "id", Origin.PARAMETER, Requiredness.REQUIRED, label);
        assertInventoryEntry(inventory, "sku", Origin.PARAMETER, Requiredness.REQUIRED, label);
        assertInventoryEntry(inventory, "verbose", Origin.PARAMETER, Requiredness.UNKNOWN, label);
        assertInventoryEntry(inventory, "page", Origin.PARAMETER, Requiredness.NOT_REQUIRED, label);
        assertInventoryEntry(inventory, "q", Origin.COMPOSITE_FIELD, Requiredness.UNKNOWN, label);
        assertInventoryEntry(
                inventory, TwinInputs.TENANT_HEADER, Origin.COMPOSITE_FIELD, Requiredness.NOT_REQUIRED, label);
        assertInventoryEntry(inventory, "limit", Origin.COMPOSITE_FIELD, Requiredness.NOT_REQUIRED, label);
        assertInventoryEntry(inventory, "sort", Origin.COMPOSITE_FIELD, Requiredness.NOT_REQUIRED, label);

        // Fixed order and locations: method parameters in declaration order, then composite fields.
        assertEquals(
                List.of("id", "sku", "verbose", "page", "q", TwinInputs.TENANT_HEADER, "limit", "sort"),
                published.stream().map(PublishedParameter::name).toList(),
                () -> label + ": parameter order");
        assertEquals(
                List.of("path", "query", "query", "query", "query", "header", "query", "query"),
                published.stream().map(PublishedParameter::in).toList(),
                () -> label + ": parameter locations");

        Map<String, JsonObject> byName = byName(parameters);
        for (String required : List.of("id", "sku")) {
            assertEquals(
                    Boolean.TRUE,
                    byName.get(required).getValue("required"),
                    () -> label + ": '" + required + "' must carry required: true");
        }
        for (String notCertain : List.of("verbose", "page", "q", TwinInputs.TENANT_HEADER, "limit", "sort")) {
            assertFalse(
                    byName.get(notCertain).containsKey("required"),
                    () -> label + ": '" + notCertain + "' must carry no required key");
        }
        assertEquals(
                TwinInputs.Q_DESCRIPTION,
                byName.get("q").getString("description"),
                () -> label + ": 'q' carries its description");

        // Composite fields are unenforced: only a raw default, never a schema derived from @Size.
        assertEquals(
                new JsonObject().put("default", TwinInputs.LIMIT_DEFAULT),
                byName.get("limit").getJsonObject("schema"),
                () -> label + ": 'limit' publishes exactly its raw default");
        for (String unenforced : List.of("q", TwinInputs.TENANT_HEADER, "sort")) {
            assertEquals(
                    new JsonObject(),
                    byName.get(unenforced).getJsonObject("schema"),
                    () -> label + ": '" + unenforced + "' publishes the empty schema");
        }

        // Enforced method parameters publish the schema the gate received, unchanged.
        RecordingSchemaSource.Recording recording = component.recordingSource().recording(operationId);
        assertEquals(
                recording.parameterCopy(ParamLocation.PATH, "id"),
                byName.get("id").getJsonObject("schema"),
                () -> label + ": 'id' schema");
        for (String query : List.of("sku", "verbose", "page")) {
            assertEquals(
                    recording.parameterCopy(ParamLocation.QUERY, query),
                    byName.get(query).getJsonObject("schema"),
                    () -> label + ": '" + query + "' schema");
        }
        assertFalse(
                byName.get("page").getJsonObject("schema").containsKey("default"),
                () -> label + ": an enforced parameter's schema shows no default");

        // The only validation member is the root's, and it records only the pattern dialect.
        assertEquals(
                List.of("/" + VALIDATION_MEMBER),
                pointersOfMember(document, VALIDATION_MEMBER),
                () -> label + ": only the root may carry " + VALIDATION_MEMBER);
        assertEquals(
                PUBLIC_VALIDATION,
                document.getJsonObject(VALIDATION_MEMBER),
                () -> label + ": the root validation member records only the pattern dialect");
        return published;
    }

    private static void assertInventoryEntry(
            List<InputProjection> inventory, String name, Origin origin, Requiredness requiredness, String label) {
        List<InputProjection> matches =
                inventory.stream().filter(input -> name.equals(input.name())).toList();
        assertEquals(1, matches.size(), () -> label + ": the inventory must hold exactly one '" + name + "'");
        assertEquals(origin, matches.get(0).origin(), () -> label + ": origin of '" + name + "'");
        assertEquals(requiredness, matches.get(0).requiredness(), () -> label + ": requiredness of '" + name + "'");
    }

    // --- Authored patterns ---

    @Test
    @DisplayName(
            "An authored body pattern with flags and an authored parameter pattern are published exactly as captured, with the pattern dialect, in JSON and YAML")
    void authoredPatternsArePublishedVerbatimWithTheirDialect() throws Exception {
        // Given: a body member and a query parameter with authored patterns, through the canonical source.
        assertTrue(
                GeneratedBodies.manifestIsEmpty(ExpressionRequest.class, GeneratedBodies.DEFAULT_PROFILE),
                "the body type must name no reserved member, so redaction removes nothing from it");
        VerbatimComponent component =
                DaggerInputTestComponents_VerbatimComponent.factory().create(webValidationConfig(VerbatimApi.NAME));

        Deployment deployment = deploy(component::httpVerticle);
        try {
            // When: the document's JSON and YAML forms are requested.
            byte[] jsonBytes = bodyBytes(fetch(deployment.port(), documentPath(VerbatimApi.NAME, JSON_FORM)));
            byte[] yamlBytes = bodyBytes(fetch(deployment.port(), documentPath(VerbatimApi.NAME, YAML_FORM)));
            JsonObject document = new JsonObject(Buffer.buffer(jsonBytes));

            // Then: the expected texts are what the gate received.
            RecordingSchemaSource.Recording recording =
                    component.recordingSource().recording(VerbatimResource.OPERATION_ID);
            assertNotNull(recording.bodyCopy(), "the source must have returned a body schema");
            JsonObject capturedProperties = recording.bodyCopy().getJsonObject("properties");
            assertNotNull(capturedProperties, "the captured body must describe its properties");
            JsonObject capturedExpression = capturedProperties.getJsonObject("expression");
            assertNotNull(capturedExpression, "the captured body must describe the member");
            String capturedMember = capturedExpression.getString("pattern");
            assertNotNull(capturedMember, "the captured body member must carry a pattern");
            assertTrue(
                    capturedMember.contains(ExpressionRequest.EXPRESSION_PATTERN),
                    () -> "the captured member pattern must hold the authored expression; was: " + capturedMember);
            assertHasDotallAndCommentsGroup(capturedMember, "the captured member pattern");
            String capturedParameter = recording
                    .parameterCopy(ParamLocation.QUERY, VerbatimResource.CODE)
                    .getString("pattern");
            assertNotNull(capturedParameter, "the captured parameter must carry a pattern");
            System.out.println("captured body member pattern: " + capturedMember);
            System.out.println("captured parameter pattern: " + capturedParameter);

            String bodyKey = VerbatimResource.OPERATION_ID + ".request";
            JsonObject bodyComponent = schemaComponent(document, bodyKey);
            JsonObject publishedProperties = bodyComponent.getJsonObject("properties");
            assertNotNull(publishedProperties, "the body component must publish its properties");
            JsonObject publishedMember = publishedProperties.getJsonObject("expression");
            assertNotNull(publishedMember, "the body component must publish the member");
            assertEquals(
                    capturedMember,
                    publishedMember.getString("pattern"),
                    "the body member's pattern must be published character for character");

            JsonObject operation = operation(document, "/expressions", "post");
            JsonObject code = byName(operation.getJsonArray("parameters")).get(VerbatimResource.CODE);
            assertNotNull(code, "the operation must publish the 'code' parameter");
            JsonObject codeSchema = code.getJsonObject("schema");
            assertNotNull(codeSchema, "the 'code' parameter must publish a schema");
            assertEquals(
                    capturedParameter,
                    codeSchema.getString("pattern"),
                    "the parameter's pattern must be published as captured");

            assertEquals(PUBLIC_VALIDATION, document.getJsonObject(VALIDATION_MEMBER), "JSON: root validation member");

            ObjectMapper json = new ObjectMapper();
            JsonNode jsonTree = json.readTree(jsonBytes);
            JsonNode yamlTree = new ObjectMapper(new YAMLFactory()).readTree(yamlBytes);
            assertEquals(jsonTree, yamlTree, "the YAML form must parse to the JSON form's tree");
            assertEquals(
                    json.readTree(PUBLIC_VALIDATION.encode()),
                    yamlTree.get(VALIDATION_MEMBER),
                    "YAML: root validation member");
            assertEquals(
                    capturedMember,
                    yamlTree.at("/components/schemas/" + bodyKey + "/properties/expression/pattern")
                            .asText(),
                    "YAML: the body member's pattern must be published character for character");
        } finally {
            undeploy(deployment);
        }
    }

    /**
     * Checks that a captured pattern opens with an inline modifier group carrying the {@code DOTALL}
     * ({@code s}) and {@code COMMENTS} ({@code x}) modifiers before its colon, so a publication that
     * stripped inline flag groups would publish different text.
     *
     * @param pattern the captured pattern text
     * @param what    what the pattern is, for the failure message
     */
    private static void assertHasDotallAndCommentsGroup(String pattern, String what) {
        assertTrue(pattern.startsWith("(?"), () -> what + " must open with an inline modifier group; was: " + pattern);
        int colon = pattern.indexOf(':');
        assertTrue(colon > 2, () -> what + " must name its modifiers before a colon; was: " + pattern);
        String modifiers = pattern.substring(2, colon);
        assertTrue(
                modifiers.contains("s") && modifiers.contains("x"),
                () -> what + " must carry the s and x modifiers before its colon; was: " + pattern);
    }

    // --- The gate's schemas stay untouched and the document stays frozen ---

    @Test
    @DisplayName(
            "Publication leaves every schema the source returned as it was returned, and later writes to them never reach the served bytes or entity tag")
    void publicationNeverWritesToTheGateSchemas() throws Exception {
        // Given: two operations with body and parameter schemas, one body carrying root local definitions.
        FrozenComponent component =
                DaggerInputTestComponents_FrozenComponent.factory().create(webValidationConfig(FrozenApi.NAME));
        RecordingSchemaSource source = component.recordingSource();

        Deployment deployment = deploy(component::httpVerticle);
        try {
            // When: the server has started; the document is fetched, which also proves assembly is done.
            HttpResponse<Buffer> first = fetch(deployment.port(), documentPath(FrozenApi.NAME, JSON_FORM));
            byte[] firstBytes = bodyBytes(first);
            String firstTag = first.getHeader("ETag");
            assertNotNull(firstTag, "the document must carry an entity tag");

            // Then: before any mutation, every object the source returned equals its copy.
            JsonObject treeBody = source.recording(FrozenResource.PLANT_TREE).bodyCopy();
            assertNotNull(treeBody, "the source must have returned the tree body");
            assertTrue(
                    treeBody.containsKey("$defs"),
                    () -> "the tree body must carry root local definitions; was: " + treeBody.encode());
            assertNotNull(source.recording(FrozenResource.RECORD_NOTE).bodyCopy(), "the note body");
            List<JsonObject> returned = source.returned();
            List<JsonObject> originals = source.originals();
            assertEquals(originals.size(), returned.size(), "every returned object has a copy");
            assertTrue(returned.size() >= 4, () -> "two bodies and two parameters at least; was " + returned.size());
            for (int index = 0; index < returned.size(); index++) {
                int at = index;
                assertEquals(
                        originals.get(index),
                        returned.get(index),
                        () -> "returned object " + at + " must still equal the copy taken when it was returned");
            }

            // When: the source writes into every object it returned, and the document is fetched again.
            int written = mutateEverySourceSchema(source);
            assertTrue(written >= 4, () -> "the mutation must reach at least four objects; was " + written);
            HttpResponse<Buffer> second = fetch(deployment.port(), documentPath(FrozenApi.NAME, JSON_FORM));

            // Then: the served document is the frozen one.
            assertArrayEquals(firstBytes, bodyBytes(second), "both fetches must return identical bytes");
            assertEquals(firstTag, second.getHeader("ETag"), "both fetches must return the same entity tag");
            for (byte[] bytes : List.of(firstBytes, bodyBytes(second))) {
                String text = new String(bytes, StandardCharsets.UTF_8);
                assertFalse(
                        text.contains(RecordingSchemaSource.ADDED_MEMBER),
                        "no fetch may contain the member added to the source's objects");
                assertFalse(
                        text.contains(RecordingSchemaSource.ADDED_PATTERN),
                        "no fetch may contain the pattern written into the source's objects");
            }
        } finally {
            undeploy(deployment);
        }
    }

    /**
     * Writes into every schema object the source has returned: adds a member and sets a {@code
     * pattern}.
     *
     * @return the number of objects written to
     */
    private static int mutateEverySourceSchema(RecordingSchemaSource source) {
        return source.mutateEverything();
    }

    // --- Two applications, two documents ---

    /** The HTTP methods of a Path Item, in Path Item order. */
    private static final List<String> PATH_ITEM_METHODS =
            List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    /**
     * The fixed facts of one of the two applications: its document name, mount path, the one route
     * its resource serves, the operation ids at that route, and the list operation's query parameter.
     */
    private record ApplicationFacts(
            String name, String mountPath, String route, String listId, String createId, String page) {

        /** Returns the key of the create operation's body component. */
        String bodyKey() {
            return createId + ".request";
        }

        /** Returns both operation ids. */
        List<String> operationIds() {
            return List.of(listId, createId);
        }
    }

    @Test
    @DisplayName(
            "Two applications publish separate documents, each with only its own paths, server, operation ids, parameters, and components")
    void twoApplicationsPublishSeparateDocumentsEachWithItsOwnOperationId() throws Exception {
        // Given: two documented applications whose bodies share a simple name in two packages.
        ApplicationFacts publicApplication = new ApplicationFacts(
                PublicApi.NAME,
                PublicApi.PATH,
                CatalogResource.ROUTE,
                CatalogResource.LIST,
                CatalogResource.CREATE,
                CatalogResource.PAGE);
        ApplicationFacts partnerApplication = new ApplicationFacts(
                PartnerApi.NAME,
                PartnerApi.PATH,
                OrdersResource.ROUTE,
                OrdersResource.LIST,
                OrdersResource.CREATE,
                OrdersResource.PAGE);
        TwoApplicationsComponent component = DaggerInputTestComponents_TwoApplicationsComponent.factory()
                .create(webValidationConfig(PublicApi.NAME, PartnerApi.NAME));

        // When: the composition starts (the deployment succeeds) and both documents are requested.
        Deployment deployment = deploy(component::httpVerticle);
        try {
            JsonObject publicDocument = fetchJsonDocument(deployment.port(), PublicApi.NAME);
            JsonObject partnerDocument = fetchJsonDocument(deployment.port(), PartnerApi.NAME);

            // Then: each document describes its own application only.
            assertDescribesOnlyItself(component, publicDocument, publicApplication);
            assertDescribesOnlyItself(component, partnerDocument, partnerApplication);
            assertHoldsNothingOf(publicDocument, publicApplication, partnerDocument, partnerApplication);
            assertHoldsNothingOf(partnerDocument, partnerApplication, publicDocument, publicApplication);
        } finally {
            undeploy(deployment);
        }
    }

    /**
     * Checks that one document has exactly its application's path, server, operations, Parameter
     * Objects, and components (body and relocated definitions keyed under its own operation ids), no
     * {@code $id}, and validates as OpenAPI 3.1.
     */
    private static void assertDescribesOnlyItself(
            InputTestComponents.InputProvisions component, JsonObject document, ApplicationFacts application) {
        String label = "document " + application.name();
        System.out.println(label + ": " + document.encode());

        assertEquals(
                Set.of(application.route()),
                document.getJsonObject("paths").fieldNames(),
                () -> label + ": the document must publish only its own path");
        JsonArray servers = document.getJsonArray("servers");
        assertNotNull(servers, () -> label + ": the document must name its server");
        assertEquals(
                application.mountPath(),
                servers.getJsonObject(0).getString("url"),
                () -> label + ": servers[0].url is the application's mount");
        assertEquals(
                Map.of(
                        application.listId(), "get " + application.route(),
                        application.createId(), "post " + application.route()),
                operationsById(document),
                () -> label + ": operation ids and where they are published");

        // Its own Parameter Objects: the list operation's one query parameter, as its own capture.
        JsonArray parameters = operation(document, application.route(), "get").getJsonArray("parameters");
        Map<String, JsonObject> byName = byName(parameters);
        assertEquals(List.of(application.page()), List.copyOf(byName.keySet()), () -> label + ": parameter names");
        JsonObject page = byName.get(application.page());
        assertEquals("query", page.getString("in"), () -> label + ": 'page' location");
        assertEquals(
                component
                        .recordingSource()
                        .recording(application.listId())
                        .parameterCopy(ParamLocation.QUERY, application.page()),
                page.getJsonObject("schema"),
                () -> label + ": 'page' publishes the schema captured for its own operation");

        // Its own components: the body and the body's relocated definitions, keyed by its own ids.
        JsonObject capturedBody =
                component.recordingSource().recording(application.createId()).bodyCopy();
        assertNotNull(capturedBody, () -> label + ": the source must have returned the create body");
        JsonObject capturedDefinitions = capturedBody.getJsonObject("$defs");
        assertNotNull(
                capturedDefinitions,
                () -> label + ": the captured body must carry root local definitions; was: " + capturedBody.encode());
        assertFalse(capturedDefinitions.isEmpty(), () -> label + ": the captured root local definitions are empty");
        Set<String> expectedKeys = new TreeSet<>();
        expectedKeys.add(application.bodyKey());
        for (String definition : capturedDefinitions.fieldNames()) {
            expectedKeys.add(componentKey(application.bodyKey() + "." + definition));
        }
        Set<String> keys = componentKeys(document);
        assertEquals(expectedKeys, keys, () -> label + ": the component keys");
        for (String key : keys) {
            assertTrue(
                    application.operationIds().stream().anyMatch(id -> key.startsWith(id + ".")),
                    () -> label + ": component '" + key + "' must start with one of its own operation ids");
        }
        assertEquals(
                "#/components/schemas/" + application.bodyKey(),
                operation(document, application.route(), "post")
                        .getJsonObject("requestBody")
                        .getJsonObject("content")
                        .getJsonObject("application/json")
                        .getJsonObject("schema")
                        .getString("$ref"),
                () -> label + ": the body references its own component");

        assertEquals(List.of(), pointersOfMember(document, "$id"), () -> label + ": no '$id' anywhere");
        OpenApi31Toolchain.assertValid(document.copy());
    }

    /**
     * Checks that a document holds none of another application's paths, operation ids, or component
     * keys.
     */
    private static void assertHoldsNothingOf(
            JsonObject document, ApplicationFacts application, JsonObject other, ApplicationFacts otherApplication) {
        String label = "document " + application.name() + " against " + otherApplication.name();
        assertFalse(
                document.getJsonObject("paths").containsKey(otherApplication.route()),
                () -> label + ": the other application's path");
        Map<String, String> operations = operationsById(document);
        for (String otherId : otherApplication.operationIds()) {
            assertFalse(
                    operations.containsKey(otherId), () -> label + ": the other application's operation " + otherId);
        }
        Set<String> shared = new TreeSet<>(componentKeys(document));
        shared.retainAll(componentKeys(other));
        assertEquals(Set.of(), shared, () -> label + ": component keys the two documents share");
        for (String key : componentKeys(document)) {
            for (String otherId : otherApplication.operationIds()) {
                assertFalse(
                        key.startsWith(otherId + "."),
                        () -> label + ": component '" + key + "' is keyed by the other application's operation");
            }
        }
        String text = document.encode();
        List<String> foreignTexts =
                List.of(otherApplication.route(), otherApplication.listId(), otherApplication.createId());
        for (String foreign : foreignTexts) {
            assertFalse(text.contains(foreign), () -> label + ": the document's text must not contain " + foreign);
        }
    }

    /**
     * Returns where each operation of a document is published, by operation id: {@code "<method>
     * <path>"}. Fails on a repeated operation id.
     *
     * @param document the document
     * @return the operation locations by id
     */
    static Map<String, String> operationsById(JsonObject document) {
        Map<String, String> byId = new LinkedHashMap<>();
        JsonObject paths = document.getJsonObject("paths");
        assertNotNull(paths, "the document must have paths");
        for (String path : paths.fieldNames()) {
            JsonObject pathItem = paths.getJsonObject(path);
            for (String method : PATH_ITEM_METHODS) {
                JsonObject operation = pathItem.getJsonObject(method);
                if (operation != null) {
                    String id = operation.getString("operationId");
                    String previous = byId.put(id, method + " " + path);
                    assertTrue(previous == null, () -> "operation id " + id + " is published twice");
                }
            }
        }
        return byId;
    }

    /**
     * Returns the keys of a document's {@code components.schemas}, empty when it has none.
     *
     * @param document the document
     * @return the keys, sorted
     */
    static Set<String> componentKeys(JsonObject document) {
        JsonObject components = document.getJsonObject("components");
        if (components == null || components.getJsonObject("schemas") == null) {
            return Set.of();
        }
        return new TreeSet<>(components.getJsonObject("schemas").fieldNames());
    }

    /**
     * Applies the component-key rule: every character outside {@code [A-Za-z0-9._-]} becomes
     * {@code _}.
     *
     * @param raw the key before the rule
     * @return the published key
     */
    static String componentKey(String raw) {
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // --- Every captured pattern ---

    /** The keywords whose values are JSON data, never schemas, so a walk never enters them. */
    private static final Set<String> LITERAL_KEYWORDS = Set.of("const", "enum", "default", "examples", "example");

    /** The keywords whose object members are names, each member a schema whatever its name. */
    private static final Set<String> NAMED_MEMBER_KEYWORDS =
            Set.of("properties", "patternProperties", "$defs", "dependentSchemas");

    /** The portable end anchor that ends every generator-built case-fold pattern. */
    private static final String PORTABLE_END_ANCHOR = "(?![\\s\\S])";

    /** The end anchor a published pattern must never carry: it is not portable across dialects. */
    private static final String JAVA_END_ANCHOR = "\\z";

    /** The route of the case-insensitively bound body. */
    private static final String FOLDED_ROUTE = "/patterns/folded";

    /** The route of the body with an authored flagged pattern. */
    private static final String FLAGGED_ROUTE = "/patterns/flagged";

    /** The route of the operation with authored parameter patterns. */
    private static final String CODES_ROUTE = "/patterns/codes/{id}";

    /**
     * One regular expression in a schema: a {@code pattern} value, or a {@code patternProperties} key
     * (then {@code pointer} is that {@code patternProperties} object's).
     *
     * @param schema  which schema holds it: a component key, or {@code parameter <in> <name>}
     * @param pointer its JSON Pointer within that schema
     * @param text    the regular expression's text
     */
    record PatternPair(String schema, String pointer, String text) implements Comparable<PatternPair> {

        private static final Comparator<PatternPair> ORDER = Comparator.comparing(PatternPair::schema)
                .thenComparing(PatternPair::pointer)
                .thenComparing(PatternPair::text);

        @Override
        public int compareTo(PatternPair other) {
            return ORDER.compare(this, other);
        }
    }

    @Test
    @DisplayName(
            "Every pattern value and patternProperties key the gate received, generator-built or authored, is published at its mapped position with identical text, in JSON and YAML")
    void everyCapturedPatternIsPublishedUnchanged() throws Exception {
        // Given: precondition first, the generator names no reserved member of either body type, so
        // redaction would remove no pattern from them.
        for (Class<?> bodyType : List.of(FoldedRequest.class, FlaggedRequest.class)) {
            assertTrue(
                    GeneratedBodies.manifestIsEmpty(bodyType, GeneratedBodies.DEFAULT_PROFILE),
                    () -> bodyType.getSimpleName() + " must name no reserved member, so redaction removes nothing");
        }
        PatternsComponent component =
                DaggerInputTestComponents_PatternsComponent.factory().create(webValidationConfig(PatternsApi.NAME));

        Deployment deployment = deploy(component::httpVerticle);
        try {
            // When: the document's JSON and YAML forms are requested.
            byte[] jsonBytes = bodyBytes(fetch(deployment.port(), documentPath(PatternsApi.NAME, JSON_FORM)));
            byte[] yamlBytes = bodyBytes(fetch(deployment.port(), documentPath(PatternsApi.NAME, YAML_FORM)));
            JsonObject document = new JsonObject(Buffer.buffer(jsonBytes));
            JsonObject yamlDocument = yamlAsJson(yamlBytes);

            // Then: each operation id is published once, where its route says.
            assertEquals(
                    Map.of(
                            PatternsResource.SUBMIT_FOLDED, "post " + FOLDED_ROUTE,
                            PatternsResource.SUBMIT_FLAGGED, "post " + FLAGGED_ROUTE,
                            PatternsResource.FIND_CODE, "get " + CODES_ROUTE),
                    operationsById(document),
                    "operation ids and where they are published");

            RecordingSchemaSource source = component.recordingSource();
            Map<String, List<PatternPair>> expected = new LinkedHashMap<>();
            Map<String, List<PatternPair>> published = new LinkedHashMap<>();
            Map<String, List<PatternPair>> publishedYaml = new LinkedHashMap<>();

            // The bodies: each component and its relocated definitions against the gate's copy.
            Map<String, String> bodyRoutes = new LinkedHashMap<>();
            bodyRoutes.put(PatternsResource.SUBMIT_FOLDED, FOLDED_ROUTE);
            bodyRoutes.put(PatternsResource.SUBMIT_FLAGGED, FLAGGED_ROUTE);
            for (Map.Entry<String, String> body : bodyRoutes.entrySet()) {
                String operationId = body.getKey();
                JsonObject captured = source.recording(operationId).bodyCopy();
                assertNotNull(captured, () -> "the source must have returned a body schema for " + operationId);
                String bodyKey = componentKey(operationId + ".request");
                assertEquals(
                        "#/components/schemas/" + bodyKey,
                        operation(document, body.getValue(), "post")
                                .getJsonObject("requestBody")
                                .getJsonObject("content")
                                .getJsonObject("application/json")
                                .getJsonObject("schema")
                                .getString("$ref"),
                        () -> operationId + ": the body references its component");
                assertFalse(
                        schemaComponent(document, bodyKey).containsKey("$defs"),
                        () -> bodyKey + ": the published component keeps no local definitions");
                expected.put(bodyKey, expectedBodyPairs(bodyKey, captured));
                published.put(bodyKey, publishedBodyPairs(document, bodyKey));
                publishedYaml.put(bodyKey, publishedBodyPairs(yamlDocument, bodyKey));
            }
            JsonObject flaggedCapture =
                    source.recording(PatternsResource.SUBMIT_FLAGGED).bodyCopy();
            JsonObject flaggedDefinitions = flaggedCapture.getJsonObject("$defs");
            assertTrue(
                    flaggedDefinitions != null && !flaggedDefinitions.isEmpty(),
                    () -> "the flagged body's capture must carry root local definitions, so the relocation"
                            + " mapping is exercised; was: " + flaggedCapture.encode());

            // The parameters: each published inline, against the gate's copy.
            RecordingSchemaSource.Recording codes = source.recording(PatternsResource.FIND_CODE);
            JsonArray parameters = operation(document, CODES_ROUTE, "get").getJsonArray("parameters");
            JsonArray yamlParameters =
                    operation(yamlDocument, CODES_ROUTE, "get").getJsonArray("parameters");
            for (ParameterInput input : List.of(
                    new ParameterInput(ParamLocation.PATH, "path", PatternsResource.ID),
                    new ParameterInput(ParamLocation.QUERY, "query", PatternsResource.NAME))) {
                String where = "parameter " + input.in() + " " + input.name();
                JsonObject captured = codes.parameterCopy(input.location(), input.name());
                assertFalse(captured.containsKey("$defs"), () -> where + ": the capture carries no local definitions");
                assertEquals(
                        List.of(),
                        pointersOfMember(captured, "$ref"),
                        () -> where + ": the capture carries no reference, so it is published inline");
                expected.put(where, patternPairs(where, captured));
                published.put(where, publishedParameterPairs(parameters, input, where));
                publishedYaml.put(where, publishedParameterPairs(yamlParameters, input, where));
            }

            for (String input : expected.keySet()) {
                System.out.println(input + ": " + expected.get(input).size() + " captured pattern pairs, "
                        + published.get(input).size() + " published (JSON), "
                        + publishedYaml.get(input).size() + " published (YAML): " + published.get(input));
            }

            // Not vacuous: every input has a pattern; the generator-built and flagged patterns are there,
            // and one pattern sits in a relocated definition.
            for (Map.Entry<String, List<PatternPair>> input : expected.entrySet()) {
                assertFalse(input.getValue().isEmpty(), () -> input.getKey() + ": the capture must carry a pattern");
            }
            String foldedKey = componentKey(PatternsResource.SUBMIT_FOLDED + ".request");
            assertTrue(
                    expected.get(foldedKey).stream()
                            .anyMatch(pair -> pair.text().contains(PORTABLE_END_ANCHOR)),
                    () -> "the folded body's capture must carry a generator-built case-fold pattern; was: "
                            + expected.get(foldedKey));
            String flaggedKey = componentKey(PatternsResource.SUBMIT_FLAGGED + ".request");
            List<PatternPair> flaggedMember = expected.get(flaggedKey).stream()
                    .filter(pair ->
                            pair.schema().equals(flaggedKey) && pair.pointer().equals("/properties/expression/pattern"))
                    .toList();
            assertEquals(
                    1,
                    flaggedMember.size(),
                    () -> "the flagged body's capture must carry the member pattern; was: " + expected.get(flaggedKey));
            String flagged = flaggedMember.get(0).text();
            assertTrue(
                    flagged.contains(FlaggedRequest.EXPRESSION_PATTERN),
                    () -> "the flagged member pattern must hold the authored expression; was: " + flagged);
            assertHasDotallAndCommentsGroup(flagged, "the flagged member pattern");
            assertTrue(
                    expected.get(flaggedKey).stream()
                            .anyMatch(pair -> !pair.schema().equals(flaggedKey)),
                    () -> "a flagged-body pattern must sit in a relocated definition; was: "
                            + expected.get(flaggedKey));

            // Each input publishes exactly the captured pairs, at their mapped positions, in both forms.
            for (String input : expected.keySet()) {
                assertEquals(
                        expected.get(input),
                        published.get(input),
                        () -> input + ": the published pattern pairs must equal the captured ones (JSON)");
                assertEquals(
                        expected.get(input),
                        publishedYaml.get(input),
                        () -> input + ": the published pattern pairs must equal the captured ones (YAML)");
            }

            // No published pattern was re-anchored to the Java-only end anchor.
            for (Map<String, List<PatternPair>> form : List.of(published, publishedYaml)) {
                for (List<PatternPair> pairs : form.values()) {
                    for (PatternPair pair : pairs) {
                        assertFalse(
                                pair.text().contains(JAVA_END_ANCHOR),
                                () -> "a published pattern must not contain " + JAVA_END_ANCHOR + ": " + pair);
                    }
                }
            }
        } finally {
            undeploy(deployment);
        }
    }

    /**
     * One parameter of the operation with authored parameter patterns.
     *
     * @param location the parameter's location, as the source keys it
     * @param in       the published {@code in} value
     * @param name     the parameter's name
     */
    private record ParameterInput(ParamLocation location, String in, String name) {}

    /**
     * Returns the pattern pairs a captured body schema is expected to publish: a pair under {@code
     * /$defs/<def>/<p>} moves to {@code /<p>} of component {@code <component>.<def>} (through the
     * component-key rule); every other pair stays at its pointer of the body component.
     *
     * @param component the body component's key
     * @param captured  the gate's copy of the body schema
     * @return the expected pairs, sorted
     */
    private static List<PatternPair> expectedBodyPairs(String component, JsonObject captured) {
        String definitions = "/$defs/";
        List<PatternPair> mapped = new ArrayList<>();
        for (PatternPair pair : patternPairs(component, captured)) {
            if (pair.pointer().startsWith(definitions)) {
                String rest = pair.pointer().substring(definitions.length());
                int slash = rest.indexOf('/');
                assertTrue(slash > 0, () -> "a pattern sits below a definition, never at it: " + pair);
                String definition = unescapePointerSegment(rest.substring(0, slash));
                mapped.add(new PatternPair(
                        componentKey(component + "." + definition), rest.substring(slash), pair.text()));
            } else {
                mapped.add(pair);
            }
        }
        mapped.sort(null);
        return mapped;
    }

    /**
     * Returns the pattern pairs a document publishes for one body: those of the body component and of
     * every component keyed under it (its relocated definitions).
     *
     * @param document  the published document
     * @param component the body component's key
     * @return the published pairs, sorted
     */
    private static List<PatternPair> publishedBodyPairs(JsonObject document, String component) {
        schemaComponent(document, component);
        JsonObject schemas = document.getJsonObject("components").getJsonObject("schemas");
        List<PatternPair> pairs = new ArrayList<>();
        for (String key : new TreeSet<>(schemas.fieldNames())) {
            if (key.equals(component) || key.startsWith(component + ".")) {
                pairs.addAll(patternPairs(key, schemas.getJsonObject(key)));
            }
        }
        pairs.sort(null);
        return pairs;
    }

    /**
     * Returns the pattern pairs of one Parameter Object's inline schema.
     *
     * @param parameters the operation's published parameters
     * @param input      the parameter
     * @param where      the pairs' schema label
     * @return the published pairs, sorted
     */
    private static List<PatternPair> publishedParameterPairs(JsonArray parameters, ParameterInput input, String where) {
        JsonObject parameter = byName(parameters).get(input.name());
        assertNotNull(parameter, () -> where + ": the operation must publish the parameter");
        assertEquals(input.in(), parameter.getString("in"), () -> where + ": location");
        JsonObject schema = parameter.getJsonObject("schema");
        assertNotNull(schema, () -> where + ": the parameter must publish a schema");
        return patternPairs(where, schema);
    }

    /**
     * Walks every schema position of a schema and returns every {@code pattern} value and every
     * {@code patternProperties} key it carries. The walk never enters {@code const}, {@code enum},
     * {@code default}, {@code examples}, or {@code example} (JSON data, not schemas), and the members
     * of {@code properties}, {@code patternProperties}, {@code $defs}, and {@code dependentSchemas} are
     * names: each is a schema whatever its name.
     *
     * @param schema the label every returned pair carries
     * @param root   the schema's root
     * @return the pairs, sorted
     */
    static List<PatternPair> patternPairs(String schema, Object root) {
        List<PatternPair> pairs = new ArrayList<>();
        walkPatterns(schema, root, "", false, pairs);
        pairs.sort(null);
        return pairs;
    }

    private static void walkPatterns(
            String schema, Object node, String pointer, boolean membersAreNames, List<PatternPair> pairs) {
        if (node instanceof JsonObject object) {
            for (String field : object.fieldNames()) {
                Object member = object.getValue(field);
                String memberPointer = pointer + "/" + escapePointerSegment(field);
                if (membersAreNames) {
                    walkPatterns(schema, member, memberPointer, false, pairs);
                    continue;
                }
                if (LITERAL_KEYWORDS.contains(field)) {
                    continue;
                }
                if ("pattern".equals(field) && member instanceof String text) {
                    pairs.add(new PatternPair(schema, memberPointer, text));
                    continue;
                }
                if ("patternProperties".equals(field) && member instanceof JsonObject byPattern) {
                    for (String key : byPattern.fieldNames()) {
                        pairs.add(new PatternPair(schema, memberPointer, key));
                    }
                }
                boolean names = NAMED_MEMBER_KEYWORDS.contains(field) && member instanceof JsonObject;
                walkPatterns(schema, member, memberPointer, names, pairs);
            }
        } else if (node instanceof JsonArray array) {
            for (int index = 0; index < array.size(); index++) {
                walkPatterns(schema, array.getValue(index), pointer + "/" + index, false, pairs);
            }
        }
    }

    private static String escapePointerSegment(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private static String unescapePointerSegment(String segment) {
        return segment.replace("~1", "/").replace("~0", "~");
    }

    /**
     * Parses a document's YAML form into a JSON object, through the JSON text of its tree.
     *
     * @param yamlBytes the YAML form's bytes
     * @return the parsed document
     */
    private static JsonObject yamlAsJson(byte[] yamlBytes) throws Exception {
        JsonNode tree = new ObjectMapper(new YAMLFactory()).readTree(yamlBytes);
        return new JsonObject(new ObjectMapper().writeValueAsString(tree));
    }

    // --- Shared helpers: configuration, documents, and trees ---

    /** The JSON form's file name under a document's path. */
    static final String JSON_FORM = "openapi.json";

    /** The YAML form's file name under a document's path. */
    static final String YAML_FORM = "openapi.yaml";

    /**
     * Returns the loopback configuration with the {@code web-validation} strategy and an {@code info}
     * for each named document.
     *
     * @param documentNames the documents (application names) to configure
     * @return a fresh configuration
     */
    static JsonObject webValidationConfig(String... documentNames) {
        JsonObject config = DocsConfigs.loopback();
        config.getJsonObject("jaxrs").put("validationStrategy", "web-validation");
        for (String name : documentNames) {
            DocsConfigs.withDocumentInfo(config, name, name, "1");
        }
        return config;
    }

    /**
     * Returns the URL path of one form of a document under the default documentation prefix.
     *
     * @param documentName the document (application) name
     * @param form         {@link #JSON_FORM} or {@link #YAML_FORM}
     * @return the path
     */
    static String documentPath(String documentName, String form) {
        return DocsConfigs.DEFAULT_APIDOCS_PATH + "/" + documentName + "/" + form;
    }

    /**
     * Fetches a document's JSON form and parses it.
     *
     * @param port         the server's port
     * @param documentName the document (application) name
     * @return the parsed document
     */
    static JsonObject fetchJsonDocument(int port, String documentName) throws Exception {
        return new JsonObject(Buffer.buffer(bodyBytes(fetch(port, documentPath(documentName, JSON_FORM)))));
    }

    /**
     * Sends {@code GET} and checks the answer is {@code 200}.
     *
     * @param port the server's port
     * @param uri  the request URI
     * @return the response
     */
    static HttpResponse<Buffer> fetch(int port, String uri) throws Exception {
        HttpResponse<Buffer> response = await(client.get(port, HOST, uri).send());
        assertEquals(200, response.statusCode(), () -> "GET " + uri + " must answer 200");
        return response;
    }

    /**
     * Returns one operation of a document.
     *
     * @param document the document
     * @param route    the path key, relative to the mount
     * @param method   the lowercase HTTP method
     * @return the operation object
     */
    static JsonObject operation(JsonObject document, String route, String method) {
        JsonObject paths = document.getJsonObject("paths");
        assertNotNull(paths, "the document must have paths");
        JsonObject pathItem = paths.getJsonObject(route);
        assertNotNull(pathItem, () -> "the document must publish path " + route + "; paths: " + paths.fieldNames());
        JsonObject operation = pathItem.getJsonObject(method);
        assertNotNull(operation, () -> "path " + route + " must publish " + method);
        return operation;
    }

    /**
     * Returns one entry of a document's {@code components.schemas}.
     *
     * @param document the document
     * @param key      the component key
     * @return the component schema
     */
    static JsonObject schemaComponent(JsonObject document, String key) {
        JsonObject components = document.getJsonObject("components");
        assertNotNull(components, "the document must publish components");
        JsonObject schemas = components.getJsonObject("schemas");
        assertNotNull(schemas, "the document must publish component schemas");
        JsonObject component = schemas.getJsonObject(key);
        assertNotNull(component, () -> "the document must publish component " + key + "; has: " + schemas.fieldNames());
        return component;
    }

    /**
     * Indexes a {@code parameters} array by each Parameter Object's {@code name}, failing on a
     * repeated name.
     *
     * @param parameters the published array
     * @return the Parameter Objects by name, in published order
     */
    static Map<String, JsonObject> byName(JsonArray parameters) {
        assertNotNull(parameters, "the operation must publish parameters");
        Map<String, JsonObject> byName = new LinkedHashMap<>();
        for (int index = 0; index < parameters.size(); index++) {
            JsonObject parameter = parameters.getJsonObject(index);
            JsonObject previous = byName.put(parameter.getString("name"), parameter);
            assertTrue(previous == null, () -> "parameter names must be distinct here: " + parameters.encode());
        }
        return byName;
    }

    /**
     * Returns the JSON Pointer of every object member with the given name, anywhere in the tree.
     *
     * @param root   the tree's root
     * @param member the member name
     * @return the pointers, in document order
     */
    static List<String> pointersOfMember(Object root, String member) {
        List<String> pointers = new ArrayList<>();
        collectPointers(root, "", member, pointers);
        return pointers;
    }

    private static void collectPointers(Object node, String pointer, String member, List<String> pointers) {
        if (node instanceof JsonObject object) {
            for (String key : object.fieldNames()) {
                String child = pointer + "/" + key.replace("~", "~0").replace("/", "~1");
                if (key.equals(member)) {
                    pointers.add(child);
                }
                collectPointers(object.getValue(key), child, member, pointers);
            }
        } else if (node instanceof JsonArray array) {
            for (int index = 0; index < array.size(); index++) {
                collectPointers(array.getValue(index), pointer + "/" + index, member, pointers);
            }
        }
    }

    // --- Deployment helpers ---

    /**
     * One deployment of a component's verticle.
     *
     * @param id   the deployment id
     * @param port the port the server bound
     */
    record Deployment(String id, int port) {}

    /**
     * Deploys one verticle after clearing the {@code vertique} local map, and reads its port.
     *
     * @param verticles the component's verticle supplier
     * @return the deployment
     */
    static Deployment deploy(Supplier<Verticle> verticles) throws Exception {
        vertx.sharedData().getLocalMap("vertique").clear();
        String id = await(vertx.deployVerticle(verticles, new DeploymentOptions()));
        Integer port = (Integer) vertx.sharedData().getLocalMap("vertique").get("http.port");
        assertNotNull(port, "the deployment must publish its port");
        return new Deployment(id, port);
    }

    /**
     * Undeploys a deployment and clears the {@code vertique} local map.
     *
     * @param deployment the deployment
     */
    static void undeploy(Deployment deployment) throws Exception {
        try {
            await(vertx.undeploy(deployment.id()));
        } finally {
            vertx.sharedData().getLocalMap("vertique").clear();
        }
    }

    static byte[] bodyBytes(HttpResponse<Buffer> response) {
        Buffer body = response.body();
        return body == null ? new byte[0] : body.getBytes();
    }

    static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }
}
