// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.publication.InputBinding.Requiredness;
import dev.vertique.rest.jaxrs.publication.RestApplications.ContractOrigin;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.openapi.docs.fixture.input.GeneratedBodies;
import dev.vertique.rest.openapi.docs.fixture.input.Publications;
import dev.vertique.rest.openapi.docs.fixture.input.Snapshots;
import dev.vertique.rest.openapi.docs.fixture.input.UnitDocumentedApi;
import dev.vertique.rest.openapi.docs.fixture.input.body.ItemBody;
import dev.vertique.rest.openapi.docs.fixture.input.body.TreeBody;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import io.vertx.json.schema.Validator;
import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit proofs of how the document assembler turns one application's detached mount publication into
 * the paths, servers, operations, and request inputs of its document.
 *
 * <p>Every case builds a synthetic application-mount publication with {@link Publications}, hands it
 * to the package-private assembler entry point together with the descriptor facts the documentation
 * sink would have taken before detaching it, and inspects the parsed document. Expected document
 * fragments are hand-written literals; a captured schema is compared with a deep copy taken before
 * assembly. Assembly failures are configuration failures whose messages are checked by fragment
 * only.
 */
@DisplayName("Document assembly of paths, servers, and request inputs")
class InputAssemblyTest {

    /** The name of the application whose document most cases assemble. */
    private static final String PUBLIC = "public";

    /** The registered mount path of application {@value #PUBLIC}. */
    private static final String PUBLIC_MOUNT = "/api/public/*";

    /** The {@code info} object of every assembled document. */
    private static final InfoConfig INFO = new InfoConfig("Input assembly", "1.0", null);

    /**
     * The enabled public document of application {@value #PUBLIC} at {@value #PUBLIC_MOUNT}, with
     * no configured server URL. The contract origin plays no part in assembly; any value serves.
     */
    private static final EnabledDocuments.EnabledDocument PUBLIC_DOCUMENT = new EnabledDocuments.EnabledDocument(
            PUBLIC, UnitDocumentedApi.class, ApiDocs.Access.PUBLIC, PUBLIC_MOUNT, ContractOrigin.GLOBAL, INFO);

    /** The JSON Schema dialect every document declares at its root. */
    private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    /** The root {@code x-vertique-validation} member of a public document. */
    private static final String VALIDATION_EXTENSION = "{\"patternDialect\":\"java.util.regex\"}";

    /** The media type of a URL-encoded form body. */
    private static final String URL_ENCODED = "application/x-www-form-urlencoded";

    /** The media type of a multipart form body. */
    private static final String MULTIPART = "multipart/form-data";

    // ---------------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Starts a synthetic publication of application {@value #PUBLIC}'s mount {@value #PUBLIC_MOUNT},
     * declared by {@link UnitDocumentedApi}; it matches {@link #PUBLIC_DOCUMENT}.
     *
     * @return the mount builder
     */
    private static Publications publicMount() {
        return Publications.mount(PUBLIC_MOUNT).application(PUBLIC, UnitDocumentedApi.class);
    }

    /**
     * Creates the enabled public document matching a built publication: its name is the
     * publication's application name and its mount path the publication's mount path.
     *
     * @param built     the built publication
     * @param serverUrl the configured server URL, or {@code null} when none is configured
     * @return the enabled document
     */
    private static EnabledDocuments.EnabledDocument documentFor(Publications.Built built, @Nullable String serverUrl) {
        return new EnabledDocuments.EnabledDocument(
                Objects.requireNonNull(built.publication().applicationName(), "applicationName"),
                Objects.requireNonNull(built.publication().declaringType(), "declaringType"),
                ApiDocs.Access.PUBLIC,
                built.publication().mountPath(),
                ContractOrigin.GLOBAL,
                INFO,
                serverUrl);
    }

    /**
     * Converts the descriptor facts of a built publication into the per-operation facts the
     * assembler receives, keyed by operation id.
     *
     * @param built the built publication
     * @return the facts of every operation
     */
    private static Map<String, OperationFacts> facts(Publications.Built built) {
        Map<String, OperationFacts> facts = new LinkedHashMap<>();
        for (String operationId : built.consumes().keySet()) {
            facts.put(
                    operationId,
                    new OperationFacts(
                            built.consumes().get(operationId),
                            built.namedFileParts().get(operationId)));
        }
        return facts;
    }

    /**
     * Assembles a document and parses its JSON form.
     *
     * @param document the enabled document
     * @param built    the built publication of the document's mount
     * @return the parsed document, members in written order
     */
    private static JsonObject assemble(EnabledDocuments.EnabledDocument document, Publications.Built built) {
        PublishedDocument published = DocumentAssembler.assemble(document, built.publication(), facts(built));
        return new JsonObject(new String(published.json(), StandardCharsets.UTF_8));
    }

    /**
     * Assembles a document that must fail publication.
     *
     * @param document the enabled document
     * @param built    the built publication of the document's mount
     * @return the configuration failure
     */
    private static RestConfigurationException assembleExpectingFailure(
            EnabledDocuments.EnabledDocument document, Publications.Built built) {
        return assertThrows(
                RestConfigurationException.class,
                () -> DocumentAssembler.assemble(document, built.publication(), facts(built)));
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
        JsonObject pathItem = doc.getJsonObject("paths").getJsonObject(path);
        assertTrue(pathItem != null, () -> "no path item " + path + " in " + doc.getJsonObject("paths"));
        JsonObject operation = pathItem.getJsonObject(method);
        assertTrue(operation != null, () -> "no " + method + " operation under " + path + " in " + pathItem);
        return operation;
    }

    /**
     * Asserts that no Parameter Object of a path item or its operation has location {@code form} or
     * names one of the given form inputs.
     *
     * @param doc        the parsed document
     * @param path       the path key
     * @param method     the lowercase method key
     * @param formInputs the form input names
     */
    private static void assertNoParameterForFormInputs(
            JsonObject doc, String path, String method, String... formInputs) {
        JsonObject pathItem = doc.getJsonObject("paths").getJsonObject(path);
        List<JsonObject> parameters = new ArrayList<>();
        for (JsonObject holder : List.of(pathItem, pathItem.getJsonObject(method))) {
            JsonArray declared = holder.getJsonArray("parameters");
            if (declared != null) {
                for (int i = 0; i < declared.size(); i++) {
                    parameters.add(declared.getJsonObject(i));
                }
            }
        }
        for (JsonObject parameter : parameters) {
            assertNotEquals("form", parameter.getString("in"), () -> "a form parameter was published: " + parameter);
            for (String formInput : formInputs) {
                assertNotEquals(
                        formInput,
                        parameter.getString("name"),
                        () -> "form input '" + formInput + "' was published as a parameter: " + parameter);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Paths and servers
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Paths are relative to the mount, templates render without their regex, and servers name the mount")
    void pathsAreRelativeToTheMountAndServersNameIt() {
        // Given: (a) application 'public' at /api/public/* with a list, a lookup whose template carries
        // a regular expression with spaces around the variable, and a create operation; (b) the same
        // mount with a configured server URL; (c) application 'api' at the root mount.
        Publications.Built catalog = publicMount()
                .operation("GET", "/catalog", "listCatalog")
                .operation("GET", "/catalog/{ id : \\d+ }", "getCatalogItem")
                .param(ParamLocation.PATH, "id", Requiredness.REQUIRED)
                .operation("POST", "/catalog", "createCatalogItem")
                .build();
        Publications.Built root = Publications.mount("/*")
                .application("api", UnitDocumentedApi.class)
                .operation("GET", "/{id}", "getById")
                .param(ParamLocation.PATH, "id", Requiredness.REQUIRED)
                .build();

        // When: each document is assembled.
        JsonObject mountServer = assemble(documentFor(catalog, null), catalog);
        JsonObject configuredServer = assemble(documentFor(catalog, "https://api.example.test/v1"), catalog);
        JsonObject rootServer = assemble(documentFor(root, null), root);

        // Then (a): the paths are the mount-relative rendered templates, each with its methods and the
        // runtime operation ids, and the only server is the mount path without its wildcard.
        JsonObject paths = mountServer.getJsonObject("paths");
        assertEquals(Set.of("/catalog", "/catalog/{id}"), paths.fieldNames());
        assertEquals(Set.of("get", "post"), paths.getJsonObject("/catalog").fieldNames());
        assertEquals(Set.of("get"), paths.getJsonObject("/catalog/{id}").fieldNames());
        assertEquals("listCatalog", operation(mountServer, "/catalog", "get").getString("operationId"));
        assertEquals(
                "createCatalogItem", operation(mountServer, "/catalog", "post").getString("operationId"));
        assertEquals(
                "getCatalogItem", operation(mountServer, "/catalog/{id}", "get").getString("operationId"));
        assertEquals(new JsonArray("[{\"url\":\"/api/public\"}]"), mountServer.getJsonArray("servers"));

        // Then (a): the root declares the schema dialect and the pattern dialect.
        assertEquals(DRAFT_2020_12, mountServer.getString("jsonSchemaDialect"));
        assertEquals(new JsonObject(VALIDATION_EXTENSION), mountServer.getJsonObject("x-vertique-validation"));

        // Then (b): the configured server URL replaces the mount path; the paths are unchanged.
        assertEquals(
                new JsonArray("[{\"url\":\"https://api.example.test/v1\"}]"), configuredServer.getJsonArray("servers"));
        assertEquals(
                Set.of("/catalog", "/catalog/{id}"),
                configuredServer.getJsonObject("paths").fieldNames());

        // Then (c): the root mount's server is '/', and its path is the template itself.
        assertEquals(new JsonArray("[{\"url\":\"/\"}]"), rootServer.getJsonArray("servers"));
        assertEquals(Set.of("/{id}"), rootServer.getJsonObject("paths").fieldNames());
        assertEquals(
                Set.of("get"),
                rootServer.getJsonObject("paths").getJsonObject("/{id}").fieldNames());
        assertEquals("getById", operation(rootServer, "/{id}", "get").getString("operationId"));
        assertEquals(DRAFT_2020_12, rootServer.getString("jsonSchemaDialect"));
        assertEquals(new JsonObject(VALIDATION_EXTENSION), rootServer.getJsonObject("x-vertique-validation"));
    }

    // ---------------------------------------------------------------------------------------------
    // Rendered-path equivalence
    // ---------------------------------------------------------------------------------------------

    /** The fragment naming application {@value #PUBLIC}'s mount in a failure message. */
    private static final String MOUNT_FRAGMENT = "mount '" + PUBLIC_MOUNT + "'";

    /** The rendered path both colliding routes share, as a failure message quotes it. */
    private static final String RENDERED_PATH_FRAGMENT = "'/items/{id}'";

    /** The regular expression of the numeric item template, which no message may echo. */
    private static final String NUMERIC_REGEX = "\\d+";

    /** The regular expression of the slug item template, which no message may echo. */
    private static final String SLUG_REGEX = "[a-z]+";

    /**
     * Returns the fragment naming one route of a rendered-path collision by method and operation id.
     *
     * @param method      the upper-case HTTP method
     * @param operationId the operation id
     * @return the fragment, for example {@code GET (operation 'getItem')}
     */
    private static String routeFragment(String method, String operationId) {
        return method + " (operation '" + operationId + "')";
    }

    /**
     * Asserts that a rendered-path collision message names the mount, both routes, and the rendered
     * path, and echoes neither regular expression.
     *
     * @param failure the failure
     * @param first   the fragment naming the first route
     * @param second  the fragment naming the second route
     */
    private static void assertRenderedPathCollision(RestConfigurationException failure, String first, String second) {
        String message = failure.getMessage();
        assertTrue(message.contains(MOUNT_FRAGMENT), () -> "the mount is not named: " + message);
        assertTrue(message.contains(first), () -> "the route " + first + " is not named: " + message);
        assertTrue(message.contains(second), () -> "the route " + second + " is not named: " + message);
        assertTrue(message.contains(RENDERED_PATH_FRAGMENT), () -> "the rendered path is not named: " + message);
        assertFalse(message.contains(NUMERIC_REGEX), () -> "a template regex was echoed: " + message);
        assertFalse(message.contains(SLUG_REGEX), () -> "a template regex was echoed: " + message);
    }

    @Test
    @DisplayName(
            "Routes rendering to equivalent paths fail publication unless they share variable names and differ in method")
    void equivalentRenderedPathsFailPublication() {
        // Given: (a) two GET routes whose templates differ only in their regular expressions;
        // (b) GET and DELETE routes whose templates name the variable differently;
        // (c) the control: DELETE and then GET routes with the same variable name, the GET template
        // carrying a regex.
        Publications.Built sameMethod = publicMount()
                .operation("GET", "/items/{id: \\d+}", "getItemByNumber")
                .operation("GET", "/items/{id: [a-z]+}", "getItemBySlug")
                .build();
        Publications.Built differentNames = publicMount()
                .operation("GET", "/items/{id}", "getItem")
                .operation("DELETE", "/items/{name}", "deleteItemByName")
                .build();
        Publications.Built control = publicMount()
                .operation("DELETE", "/items/{id}", "deleteItem")
                .operation("GET", "/items/{id: \\d+}", "getItem")
                .build();

        // When: each document is assembled.
        RestConfigurationException sameMethodFailure = assembleExpectingFailure(PUBLIC_DOCUMENT, sameMethod);
        RestConfigurationException differentNamesFailure = assembleExpectingFailure(PUBLIC_DOCUMENT, differentNames);
        JsonObject controlDocument = assemble(PUBLIC_DOCUMENT, control);

        // Then (a): the failure names the mount, both GET routes, and the rendered path, never a regex.
        assertRenderedPathCollision(
                sameMethodFailure, routeFragment("GET", "getItemByNumber"), routeFragment("GET", "getItemBySlug"));

        // Then (b): the failure names the mount, both routes, and the rendered path.
        assertRenderedPathCollision(
                differentNamesFailure, routeFragment("GET", "getItem"), routeFragment("DELETE", "deleteItemByName"));

        // Then (c): both routes share one path item, each under its own method, and the methods are
        // written in method order, GET before DELETE, although DELETE was declared first.
        JsonObject paths = controlDocument.getJsonObject("paths");
        assertEquals(Set.of("/items/{id}"), paths.fieldNames());
        assertEquals(
                List.of("get", "delete"),
                new ArrayList<>(paths.getJsonObject("/items/{id}").fieldNames()));
        assertEquals("getItem", operation(controlDocument, "/items/{id}", "get").getString("operationId"));
        assertEquals(
                "deleteItem",
                operation(controlDocument, "/items/{id}", "delete").getString("operationId"));
    }

    // ---------------------------------------------------------------------------------------------
    // Form inputs and file parts
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Form inputs and named file parts become the request body, never parameters")
    void formInputsBecomeTheRequestBody() {
        // Given: (a) 'submitForm' consuming a URL-encoded form with 'title' (captured schema), 'note'
        // (no schema), and 'tag' (no schema, default 'none'), in that inventory order;
        // (b) 'uploadForm' consuming a multipart form with 'caption' (captured schema) and the named
        // file part 'file', for which a schema was captured too.
        JsonObject titleSchema = new JsonObject("{\"type\":\"string\",\"maxLength\":80}");
        JsonObject captionSchema = new JsonObject("{\"type\":\"string\",\"minLength\":1}");
        JsonObject fileSchema = new JsonObject("{\"type\":\"string\"}");
        Publications.Built submit = publicMount()
                .operation("POST", "/forms/submit", "submitForm")
                .consumes(URL_ENCODED)
                .formField("title")
                .schema(titleSchema)
                .formField("note")
                .formField("tag")
                .defaultValue("none")
                .build();
        Publications.Built upload = publicMount()
                .operation("POST", "/forms/upload", "uploadForm")
                .consumes(MULTIPART)
                .formField("caption")
                .schema(captionSchema)
                .namedFilePart("file")
                .schema(fileSchema)
                .build();
        JsonObject titleBefore = titleSchema.copy();
        JsonObject captionBefore = captionSchema.copy();

        // When: each document is assembled.
        JsonObject submitDocument = assemble(PUBLIC_DOCUMENT, submit);
        JsonObject uploadDocument = assemble(PUBLIC_DOCUMENT, upload);

        // Then (a): the body is the one consumed media type, an object of the form inputs in inventory
        // order: the captured schema unchanged, an empty schema, and the raw default.
        JsonObject submitBody =
                operation(submitDocument, "/forms/submit", "post").getJsonObject("requestBody");
        assertEquals(Set.of(URL_ENCODED), submitBody.getJsonObject("content").fieldNames());
        JsonObject submitSchema =
                submitBody.getJsonObject("content").getJsonObject(URL_ENCODED).getJsonObject("schema");
        assertEquals(
                new JsonObject("{\"type\":\"object\",\"properties\":{"
                        + "\"title\":{\"type\":\"string\",\"maxLength\":80},"
                        + "\"note\":{},"
                        + "\"tag\":{\"default\":\"none\"}}}"),
                submitSchema);
        JsonObject submitProperties = submitSchema.getJsonObject("properties");
        assertEquals(List.of("title", "note", "tag"), new ArrayList<>(submitProperties.fieldNames()));
        assertEquals(titleBefore, submitProperties.getJsonObject("title"));
        assertEquals(new JsonObject("{}"), submitProperties.getJsonObject("note"));
        assertEquals(new JsonObject("{\"default\":\"none\"}"), submitProperties.getJsonObject("tag"));
        assertFalse(submitBody.containsKey("required"), () -> "a form body carries 'required': " + submitBody);
        assertNoParameterForFormInputs(submitDocument, "/forms/submit", "post", "title", "note", "tag");
        OpenApi31Toolchain.assertValid(submitDocument);

        // Then (b): the body is the multipart media type with the caption's captured schema, then the
        // file part as an empty schema although a schema was captured for it.
        JsonObject uploadBody =
                operation(uploadDocument, "/forms/upload", "post").getJsonObject("requestBody");
        assertEquals(Set.of(MULTIPART), uploadBody.getJsonObject("content").fieldNames());
        JsonObject uploadSchema =
                uploadBody.getJsonObject("content").getJsonObject(MULTIPART).getJsonObject("schema");
        assertEquals(
                new JsonObject("{\"type\":\"object\",\"properties\":{"
                        + "\"caption\":{\"type\":\"string\",\"minLength\":1},"
                        + "\"file\":{}}}"),
                uploadSchema);
        JsonObject uploadProperties = uploadSchema.getJsonObject("properties");
        assertEquals(List.of("caption", "file"), new ArrayList<>(uploadProperties.fieldNames()));
        assertEquals(captionBefore, uploadProperties.getJsonObject("caption"));
        assertEquals(new JsonObject("{}"), uploadProperties.getJsonObject("file"));
        assertFalse(uploadBody.containsKey("required"), () -> "a form body carries 'required': " + uploadBody);
        assertNoParameterForFormInputs(uploadDocument, "/forms/upload", "post", "caption", "file");
        OpenApi31Toolchain.assertValid(uploadDocument);
    }

    @Test
    @DisplayName(
            "A form operation without consumed media types publishes multipart with a named file part, else URL-encoded")
    void formInputsWithoutConsumesUseTheirDefaultMediaType() {
        // Given: two form operations that declare no consumed media type: 'plainForm' with one form
        // field, and 'filesForm' with a form field and a named file part.
        Publications.Built forms = publicMount()
                .operation("POST", "/forms/plain", "plainForm")
                .formField("title")
                .operation("POST", "/forms/files", "filesForm")
                .formField("caption")
                .namedFilePart("file")
                .build();

        // When: the document is assembled.
        JsonObject doc = assemble(PUBLIC_DOCUMENT, forms);

        // Then: the plain form's body is URL-encoded only, and the form with a file part's body is
        // multipart only, each listing its form inputs in inventory order.
        JsonObject plainContent = operation(doc, "/forms/plain", "post")
                .getJsonObject("requestBody")
                .getJsonObject("content");
        assertEquals(Set.of(URL_ENCODED), plainContent.fieldNames());
        assertEquals(
                new JsonObject("{\"type\":\"object\",\"properties\":{\"title\":{}}}"),
                plainContent.getJsonObject(URL_ENCODED).getJsonObject("schema"));

        JsonObject filesContent = operation(doc, "/forms/files", "post")
                .getJsonObject("requestBody")
                .getJsonObject("content");
        assertEquals(Set.of(MULTIPART), filesContent.fieldNames());
        JsonObject filesSchema = filesContent.getJsonObject(MULTIPART).getJsonObject("schema");
        assertEquals(new JsonObject("{\"type\":\"object\",\"properties\":{\"caption\":{},\"file\":{}}}"), filesSchema);
        assertEquals(
                List.of("caption", "file"),
                new ArrayList<>(filesSchema.getJsonObject("properties").fieldNames()));
        OpenApi31Toolchain.assertValid(doc);
    }

    // ---------------------------------------------------------------------------------------------
    // Inputs sharing a name and location
    // ---------------------------------------------------------------------------------------------

    /** The fragment explaining why inputs sharing a name and location fail publication. */
    private static final String ONE_PARAMETER_FRAGMENT = "one parameter per name and location";

    @Test
    @DisplayName("Two inputs sharing a name and location fail publication; one name in two locations publishes both")
    void duplicateInputsFailPublication() {
        // Given: (a) 'searchItems' binding the query parameter 'q' both as a method parameter and as a
        // composite field; (b) 'submitTitles' binding two form fields named 'title'; (c) the control:
        // 'getItem' binding 'id' once in the path and once in the query.
        Publications.Built queryTwice = publicMount()
                .operation("GET", "/items", "searchItems")
                .param(ParamLocation.QUERY, "q", Requiredness.NOT_REQUIRED)
                .compositeField(ParamLocation.QUERY, "q", Requiredness.NOT_REQUIRED)
                .build();
        Publications.Built formTwice = publicMount()
                .operation("POST", "/forms/titles", "submitTitles")
                .consumes(URL_ENCODED)
                .formField("title")
                .formField("title")
                .build();
        Publications.Built pathAndQuery = publicMount()
                .operation("GET", "/items/{id}", "getItem")
                .param(ParamLocation.PATH, "id", Requiredness.REQUIRED)
                .param(ParamLocation.QUERY, "id", Requiredness.NOT_REQUIRED)
                .build();

        // When: each document is assembled.
        RestConfigurationException queryFailure = assembleExpectingFailure(PUBLIC_DOCUMENT, queryTwice);
        RestConfigurationException formFailure = assembleExpectingFailure(PUBLIC_DOCUMENT, formTwice);
        JsonObject controlDocument = assemble(PUBLIC_DOCUMENT, pathAndQuery);

        // Then (a): the failure names the mount, the operation, the input, and its location, and says
        // a document describes one parameter per name and location.
        String queryMessage = queryFailure.getMessage();
        assertTrue(queryMessage.contains(MOUNT_FRAGMENT), () -> "(a): the mount is not named: " + queryMessage);
        assertTrue(queryMessage.contains("'searchItems'"), () -> "(a): the operation is not named: " + queryMessage);
        assertTrue(queryMessage.contains("'q'"), () -> "(a): the input is not named: " + queryMessage);
        assertTrue(queryMessage.contains("in query"), () -> "(a): the location is not named: " + queryMessage);
        assertTrue(queryMessage.contains(ONE_PARAMETER_FRAGMENT), () -> "(a): the rule is not stated: " + queryMessage);

        // Then (b): the failure names the mount, the operation, the form input, and the form location.
        String formMessage = formFailure.getMessage();
        assertTrue(formMessage.contains(MOUNT_FRAGMENT), () -> "(b): the mount is not named: " + formMessage);
        assertTrue(formMessage.contains("'submitTitles'"), () -> "(b): the operation is not named: " + formMessage);
        assertTrue(formMessage.contains("'title'"), () -> "(b): the input is not named: " + formMessage);
        assertTrue(formMessage.contains("in form"), () -> "(b): the location is not named: " + formMessage);
        assertTrue(formMessage.contains(ONE_PARAMETER_FRAGMENT), () -> "(b): the rule is not stated: " + formMessage);

        // Then (c): two Parameter Objects are published, the path 'id' and then the query 'id'.
        JsonArray parameters = operation(controlDocument, "/items/{id}", "get").getJsonArray("parameters");
        assertNotNull(parameters, () -> "(c) publishes no parameters: " + controlDocument.encode());
        List<String> published = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            JsonObject parameter = parameters.getJsonObject(i);
            published.add(parameter.getString("name") + " in " + parameter.getString("in"));
        }
        assertEquals(List.of("id in path", "id in query"), published);
    }

    // ---------------------------------------------------------------------------------------------
    // Request bodies
    // ---------------------------------------------------------------------------------------------

    /** The media type of a JSON body. */
    private static final String JSON = "application/json";

    /**
     * The options the request-validation gate compiles its schemas with: Draft 2020-12, base URI
     * {@code https://vertique.local/}, basic output.
     */
    private static final JsonSchemaOptions GATE_OPTIONS = new JsonSchemaOptions()
            .setDraft(Draft.DRAFT202012)
            .setBaseUri("https://vertique.local/")
            .setOutputFormat(OutputFormat.Basic);

    /**
     * Validates an absent body, {@code null}, against a schema the way the gate compiles it. The
     * engine writes into the object it compiles, so it compiles a copy.
     *
     * @param schema the schema, left untouched
     * @return the boxed verdict
     */
    private static Boolean validatesNull(JsonObject schema) {
        return Validator.create(JsonSchema.of(schema.copy()), GATE_OPTIONS)
                .validate((Object) null)
                .getValid();
    }

    /**
     * Returns the {@code components.schemas} object of a document, failing when absent.
     *
     * @param doc the parsed document
     * @return the component schemas
     */
    private static JsonObject componentSchemas(JsonObject doc) {
        JsonObject components = doc.getJsonObject("components");
        assertNotNull(components, () -> "no components in " + doc.encode());
        JsonObject schemas = components.getJsonObject("schemas");
        assertNotNull(schemas, () -> "no component schemas in " + doc.encode());
        return schemas;
    }

    /**
     * Returns the keys of a document's component schemas, empty when it publishes none.
     *
     * @param doc the parsed document
     * @return the component schema keys
     */
    private static Set<String> componentKeys(JsonObject doc) {
        JsonObject components = doc.getJsonObject("components");
        if (components == null || components.getJsonObject("schemas") == null) {
            return Set.of();
        }
        return components.getJsonObject("schemas").fieldNames();
    }

    /**
     * Returns the request body of an operation, failing when absent.
     *
     * @param doc    the parsed document
     * @param path   the path key
     * @param method the lowercase method key
     * @return the request body object
     */
    private static JsonObject requestBodyOf(JsonObject doc, String path, String method) {
        JsonObject requestBody = operation(doc, path, method).getJsonObject("requestBody");
        assertNotNull(requestBody, () -> "no request body for " + method + " " + path + " in " + doc.encode());
        return requestBody;
    }

    /**
     * Asserts that no object anywhere in a tree, the root included, has a member of the given name.
     *
     * @param tree   the tree to search
     * @param member the member name, for example {@code "$id"}
     * @param where  names the tree in a failure
     */
    private static void assertNoMemberAnywhere(Object tree, String member, String where) {
        if (tree instanceof JsonObject object) {
            assertFalse(object.containsKey(member), () -> where + " carries '" + member + "': " + object.encode());
            for (Map.Entry<String, Object> entry : object) {
                assertNoMemberAnywhere(entry.getValue(), member, where);
            }
        } else if (tree instanceof JsonArray array) {
            for (Object element : array) {
                assertNoMemberAnywhere(element, member, where);
            }
        }
    }

    /**
     * Returns the request body media types referencing one component, as a literal.
     *
     * @param component  the component key
     * @param mediaTypes the media types, in order
     * @return the expected {@code content} object
     */
    private static JsonObject contentReferencing(String component, String... mediaTypes) {
        JsonObject content = new JsonObject();
        for (String mediaType : mediaTypes) {
            content.put(
                    mediaType,
                    new JsonObject().put("schema", new JsonObject().put("$ref", "#/components/schemas/" + component)));
        }
        return content;
    }

    @Test
    @DisplayName(
            "A captured request body becomes one component referenced from every consumed media type, its definitions relocated")
    void bodyBecomesAComponentPerConsumedMediaType() {
        // Given: generator-made bodies, each body binding's requiredness UNKNOWN as the publication
        // fixes it. (a) 'createItem', no consumed media type, gate installed, a body without $defs;
        // (b) 'update:item' consuming JSON and merge-patch JSON; (c) 'get:item' and 'get_item', whose
        // component keys coincide; (d) 'createTree', whose body holds a self-referential map;
        // (e) 'patchItem', gate installed, a body whose schema accepts null; (f) (a)'s body type with no
        // gate installed; (g) 'noteItem' consuming JSON and plain text, with no captured body schema.
        GeneratedBodies.GeneratedBody itemBody = GeneratedBodies.describe(ItemBody.class);
        GeneratedBodies.GeneratedBody updateBody = GeneratedBodies.describe(ItemBody.class);
        GeneratedBodies.GeneratedBody treeBody = GeneratedBodies.describe(TreeBody.class);
        // (e): JsonNode, the example the contract names, describes as {"$schema": ..., "type": "object"}
        // and rejects null, so it cannot stand for a body that accepts null. Object is chosen instead:
        // the generator leaves java.* types to the schema library, which describes java.lang.Object as
        // the empty schema, so the captured body is expected to be
        // {"$schema": "https://json-schema.org/draft/2020-12/schema"} with no 'type', and to accept
        // null. The precondition below checks the acceptance itself rather than that literal.
        GeneratedBodies.GeneratedBody openBody = GeneratedBodies.describe(Object.class);
        GeneratedBodies.GeneratedBody ungatedBody = GeneratedBodies.describe(ItemBody.class);

        Publications.Built created = publicMount()
                .operation("POST", "/items", "createItem")
                .body(itemBody)
                .build();
        Publications.Built updated = publicMount()
                .operation("PUT", "/items", "update:item")
                .consumes(JSON, "application/merge-patch+json")
                .body(updateBody)
                .build();
        Publications.Built colliding = publicMount()
                .operation("POST", "/items/a", "get:item")
                .body(GeneratedBodies.describe(ItemBody.class))
                .operation("POST", "/items/b", "get_item")
                .body(GeneratedBodies.describe(ItemBody.class))
                .build();
        Publications.Built tree = publicMount()
                .operation("POST", "/trees", "createTree")
                .body(treeBody)
                .build();
        Publications.Built patched = publicMount()
                .operation("PATCH", "/items", "patchItem")
                .body(openBody)
                .build();
        Publications.Built ungated = publicMount()
                .operation("POST", "/items", "createItem")
                .gateInstalled(false)
                .body(ungatedBody)
                .build();
        Publications.Built noted = publicMount()
                .operation("POST", "/notes", "noteItem")
                .consumes(JSON, "text/plain")
                .bodyBindingWithoutSchema()
                .build();

        // Preconditions: the body binding is UNKNOWN; (a)'s body has no $defs and rejects null; (e)'s
        // body accepts null; (d)'s body has exactly the definition RecursiveMap, referenced from the
        // property 'tree' and from itself.
        assertEquals(
                Requiredness.UNKNOWN,
                created.publication()
                        .operations()
                        .get(0)
                        .detail()
                        .inputs()
                        .get(0)
                        .requiredness(),
                "the body binding's requiredness");
        assertFalse(itemBody.schema().containsKey("$defs"), () -> "ItemBody carries $defs: " + itemBody.schema());
        assertEquals(
                Boolean.FALSE,
                validatesNull(itemBody.schema()),
                () -> "the schema of ItemBody must reject null: " + itemBody.schema());
        assertEquals(
                Boolean.TRUE,
                validatesNull(openBody.schema()),
                () -> "the schema of java.lang.Object must accept null: " + openBody.schema());
        JsonObject treeSchema = treeBody.schema();
        assertNotNull(treeSchema.getJsonObject("$defs"), () -> "TreeBody has no root $defs: " + treeSchema);
        assertEquals(
                Set.of("RecursiveMap"),
                treeSchema.getJsonObject("$defs").fieldNames(),
                () -> "TreeBody's root $defs: " + treeSchema);
        assertEquals(
                "#/$defs/RecursiveMap",
                treeSchema.getJsonObject("properties").getJsonObject("tree").getString("$ref"),
                () -> "TreeBody's property 'tree': " + treeSchema);
        assertEquals(
                "#/$defs/RecursiveMap",
                treeSchema
                        .getJsonObject("$defs")
                        .getJsonObject("RecursiveMap")
                        .getJsonObject("additionalProperties")
                        .getString("$ref"),
                () -> "RecursiveMap's own reference: " + treeSchema);

        // The expected components: (a), (b), (e), and (f) are their captured bodies unchanged. (d)'s
        // component is the captured body with its root $defs removed and the reference at
        // /properties/tree/$ref rewritten to the relocated definition; that definition is the captured
        // $defs/RecursiveMap with the reference at /additionalProperties/$ref rewritten the same way.
        JsonObject expectedItem = itemBody.schema().copy();
        JsonObject expectedUpdate = updateBody.schema().copy();
        JsonObject expectedOpen = openBody.schema().copy();
        JsonObject expectedUngated = ungatedBody.schema().copy();
        String relocatedRecursiveMap = "#/components/schemas/createTree.request.RecursiveMap";
        JsonObject expectedTree = treeSchema.copy();
        expectedTree.remove("$defs");
        expectedTree.getJsonObject("properties").getJsonObject("tree").put("$ref", relocatedRecursiveMap);
        JsonObject expectedRecursiveMap =
                treeSchema.getJsonObject("$defs").getJsonObject("RecursiveMap").copy();
        expectedRecursiveMap.getJsonObject("additionalProperties").put("$ref", relocatedRecursiveMap);

        Snapshots.Snapshot createdBefore = Snapshots.of(created.publication());
        Snapshots.Snapshot updatedBefore = Snapshots.of(updated.publication());
        Snapshots.Snapshot collidingBefore = Snapshots.of(colliding.publication());
        Snapshots.Snapshot treeBefore = Snapshots.of(tree.publication());
        Snapshots.Snapshot patchedBefore = Snapshots.of(patched.publication());
        Snapshots.Snapshot ungatedBefore = Snapshots.of(ungated.publication());

        // When: each document is assembled.
        JsonObject createdDoc = assemble(PUBLIC_DOCUMENT, created);
        JsonObject updatedDoc = assemble(PUBLIC_DOCUMENT, updated);
        RestConfigurationException collision = assembleExpectingFailure(PUBLIC_DOCUMENT, colliding);
        JsonObject treeDoc = assemble(PUBLIC_DOCUMENT, tree);
        JsonObject patchedDoc = assemble(PUBLIC_DOCUMENT, patched);
        JsonObject ungatedDoc = assemble(PUBLIC_DOCUMENT, ungated);
        JsonObject notedDoc = assemble(PUBLIC_DOCUMENT, noted);

        // Then (a): the component is the captured body exactly, with no $id; the body is required
        // although its binding is UNKNOWN; the only media type is JSON; the root names the dialect.
        assertEquals(DRAFT_2020_12, createdDoc.getString("jsonSchemaDialect"));
        JsonObject createdSchemas = componentSchemas(createdDoc);
        assertEquals(Set.of("createItem.request"), createdSchemas.fieldNames());
        assertEquals(expectedItem, createdSchemas.getJsonObject("createItem.request"));
        assertNoMemberAnywhere(createdSchemas.getJsonObject("createItem.request"), "$id", "createItem.request");
        JsonObject createdRequestBody = requestBodyOf(createdDoc, "/items", "post");
        assertEquals(contentReferencing("createItem.request", JSON), createdRequestBody.getJsonObject("content"));
        assertEquals(Boolean.TRUE, createdRequestBody.getValue("required"), () -> "(a): " + createdRequestBody);

        // Then (b): the key replaces ':' with '_', and both media types reference the one component.
        JsonObject updatedSchemas = componentSchemas(updatedDoc);
        assertEquals(Set.of("update_item.request"), updatedSchemas.fieldNames());
        assertEquals(expectedUpdate, updatedSchemas.getJsonObject("update_item.request"));
        JsonObject updatedRequestBody = requestBodyOf(updatedDoc, "/items", "put");
        assertEquals(
                contentReferencing("update_item.request", JSON, "application/merge-patch+json"),
                updatedRequestBody.getJsonObject("content"));

        // Then (c): publication fails naming the mount and both operations.
        String collisionMessage = collision.getMessage();
        assertTrue(collisionMessage.contains(MOUNT_FRAGMENT), () -> "the mount is not named: " + collisionMessage);
        assertTrue(collisionMessage.contains("'get:item'"), () -> "'get:item' is not named: " + collisionMessage);
        assertTrue(collisionMessage.contains("'get_item'"), () -> "'get_item' is not named: " + collisionMessage);

        // Then (d): the body and its relocated definition are published, neither with $defs or $id,
        // and the body references the relocated definition.
        JsonObject treeSchemas = componentSchemas(treeDoc);
        assertEquals(Set.of("createTree.request", "createTree.request.RecursiveMap"), treeSchemas.fieldNames());
        assertEquals(expectedTree, treeSchemas.getJsonObject("createTree.request"));
        assertEquals(expectedRecursiveMap, treeSchemas.getJsonObject("createTree.request.RecursiveMap"));
        for (String key : treeSchemas.fieldNames()) {
            assertNoMemberAnywhere(treeSchemas.getJsonObject(key), "$defs", key);
            assertNoMemberAnywhere(treeSchemas.getJsonObject(key), "$id", key);
        }
        assertEquals(
                contentReferencing("createTree.request", JSON),
                requestBodyOf(treeDoc, "/trees", "post").getJsonObject("content"));

        // Then (e): the body and its component are published, but a body accepting null is not required.
        JsonObject patchedSchemas = componentSchemas(patchedDoc);
        assertEquals(Set.of("patchItem.request"), patchedSchemas.fieldNames());
        assertEquals(expectedOpen, patchedSchemas.getJsonObject("patchItem.request"));
        JsonObject patchedRequestBody = requestBodyOf(patchedDoc, "/items", "patch");
        assertEquals(contentReferencing("patchItem.request", JSON), patchedRequestBody.getJsonObject("content"));
        assertFalse(patchedRequestBody.containsKey("required"), () -> "(e) carries 'required': " + patchedRequestBody);

        // Then (f): without a gate the component is still published, and the body is not required.
        JsonObject ungatedSchemas = componentSchemas(ungatedDoc);
        assertEquals(Set.of("createItem.request"), ungatedSchemas.fieldNames());
        assertEquals(expectedUngated, ungatedSchemas.getJsonObject("createItem.request"));
        JsonObject ungatedRequestBody = requestBodyOf(ungatedDoc, "/items", "post");
        assertEquals(contentReferencing("createItem.request", JSON), ungatedRequestBody.getJsonObject("content"));
        assertFalse(ungatedRequestBody.containsKey("required"), () -> "(f) carries 'required': " + ungatedRequestBody);

        // Then (g): both media types carry the empty schema, no component is published, and the body is
        // not required.
        JsonObject notedRequestBody = requestBodyOf(notedDoc, "/notes", "post");
        assertEquals(
                new JsonObject("{\"application/json\":{\"schema\":{}},\"text/plain\":{\"schema\":{}}}"),
                notedRequestBody.getJsonObject("content"));
        assertFalse(
                componentKeys(notedDoc).contains("noteItem.request"),
                () -> "(g) published a component: " + notedDoc.encode());
        assertFalse(notedRequestBody.containsKey("required"), () -> "(g) carries 'required': " + notedRequestBody);

        // Then: every captured body still deep-equals its pre-assembly copy.
        createdBefore.assertUnchanged();
        updatedBefore.assertUnchanged();
        collidingBefore.assertUnchanged();
        treeBefore.assertUnchanged();
        patchedBefore.assertUnchanged();
        ungatedBefore.assertUnchanged();
    }

    // ---------------------------------------------------------------------------------------------
    // Refused constructs and relocation of a parameter schema
    // ---------------------------------------------------------------------------------------------

    /** The fragment naming operation 'findItem' in a failure message. */
    private static final String FIND_ITEM_FRAGMENT = "'findItem'";

    /** The fragment naming parameter 'code' in a failure message. */
    private static final String CODE_FRAGMENT = "'code'";

    /** Values of the refused schemas that no failure message may echo. */
    private static final List<String> UNECHOED_VALUES = List.of(
            "schemas.example.test",
            "Missing",
            "missing-anchor",
            "urn:example",
            "urn:vertique",
            "item.json",
            "example.test",
            "zq7node",
            "Zq7");

    /**
     * Builds the publication of operation {@code GET /items} 'findItem' whose query parameter 'code'
     * (requiredness UNKNOWN) has the given captured schema.
     *
     * @param schema the captured schema, kept by reference
     * @return the built publication
     */
    private static Publications.Built findItemWithCode(JsonObject schema) {
        return publicMount()
                .operation("GET", "/items", "findItem")
                .param(ParamLocation.QUERY, "code", Requiredness.UNKNOWN)
                .schema(schema)
                .build();
    }

    /**
     * Returns the single Parameter Object of 'findItem', failing unless there is exactly one.
     *
     * @param doc the parsed document
     * @return the Parameter Object
     */
    private static JsonObject findItemParameter(JsonObject doc) {
        JsonArray parameters = operation(doc, "/items", "get").getJsonArray("parameters");
        assertNotNull(parameters, () -> "findItem publishes no parameters: " + doc.encode());
        assertEquals(1, parameters.size(), () -> "findItem's parameters: " + parameters);
        return parameters.getJsonObject(0);
    }

    /**
     * Asserts that a refusal names the mount, operation 'findItem', parameter 'code', and the
     * offending keyword's JSON Pointer, and echoes no refused value.
     *
     * @param name    the case name, for failures
     * @param failure the failure
     * @param pointer the JSON Pointer of the offending keyword member
     */
    private static void assertRefusalOfCode(String name, RestConfigurationException failure, String pointer) {
        String message = failure.getMessage();
        assertTrue(message.contains(MOUNT_FRAGMENT), () -> name + ": the mount is not named: " + message);
        assertTrue(message.contains(FIND_ITEM_FRAGMENT), () -> name + ": the operation is not named: " + message);
        assertTrue(message.contains(CODE_FRAGMENT), () -> name + ": the parameter is not named: " + message);
        assertTrue(
                message.contains("'" + pointer + "'"),
                () -> name + ": the pointer '" + pointer + "' is not named: " + message);
        for (String value : UNECHOED_VALUES) {
            assertFalse(message.contains(value), () -> name + ": the message echoes '" + value + "': " + message);
        }
    }

    /**
     * One captured schema that must fail publication.
     *
     * @param name    the case name
     * @param schema  the captured schema
     * @param pointer the JSON Pointer of the offending keyword member the message must name
     */
    private record RefusedSchema(String name, String schema, String pointer) {}

    @Test
    @DisplayName(
            "Identifiers, anchors, nested definitions, and references leaving the captured schema fail publication")
    void refusedConstructsFailPublication() {
        // Given: captured schemas of findItem's query parameter 'code'. Each failing row names the
        // pointer of the keyword member at fault. Identifiers, anchors, and nested $defs are found
        // before any reference is checked, so (e) names its $anchor, not the reference to it, although
        // the reference is written first.
        List<RefusedSchema> refused = List.of(
                new RefusedSchema("(a) network reference", """
                        {"$ref": "https://schemas.example.test/a.json"}""", "/$ref"),
                new RefusedSchema("(b) unresolved definition", """
                        {"$ref": "#/$defs/Missing"}""", "/$ref"),
                new RefusedSchema("(c) anchor-name fragment", """
                        {"$ref": "#missing-anchor"}""", "/$ref"),
                new RefusedSchema("(e) anchor", """
                        {"$ref": "#item", "$anchor": "item"}""", "/$anchor"),
                new RefusedSchema("(g) identifier inside a definition", """
                        {"$defs": {"Item": {"$id": "urn:example:item", "type": "string"}},
                         "$ref": "#/$defs/Item"}""", "/$defs/Item/$id"),
                new RefusedSchema("(h) relative reference", """
                        {"$defs": {"Code": {"type": "string"}}, "$ref": "item.json"}""", "/$ref"),
                new RefusedSchema("(i) absolute reference with a fragment", """
                        {"$defs": {"Code": {"type": "string"}},
                         "$ref": "urn:vertique:apidocs:public:findItem.query.code#/$defs/Code"}""", "/$ref"),
                new RefusedSchema("(j) root identifier equal to a component name", """
                        {"$id": "urn:vertique:apidocs:public:findItem.query.code", "type": "string"}""", "/$id"),
                new RefusedSchema("(k) root network identifier", """
                        {"$id": "https://example.test/s", "type": "string"}""", "/$id"),
                new RefusedSchema("(l) dynamic anchor", """
                        {"$dynamicAnchor": "zq7node", "type": "string"}""", "/$dynamicAnchor"),
                new RefusedSchema("(m) dynamic reference", """
                        {"$dynamicRef": "#zq7node"}""", "/$dynamicRef"),
                new RefusedSchema("(n) definitions below the root", """
                        {"allOf": [{"$defs": {"Zq7": {}}}]}""", "/allOf/0/$defs"));
        // (d) a root definition referenced by fragment; (f) "$ref" as data inside enum and default, and
        // an $id member inside const: data is neither a reference nor a resource.
        JsonObject relocatable = new JsonObject("""
                {"$defs": {"Code": {"type": "string", "pattern": "^[a-z]+$"}}, "$ref": "#/$defs/Code"}""");
        JsonObject data = new JsonObject("""
                {"enum": ["$ref", "code"], "default": "$ref", "const": {"$id": "data"}}""");
        Publications.Built relocated = findItemWithCode(relocatable);
        Publications.Built literal = findItemWithCode(data);
        JsonObject dataBefore = data.copy();
        Snapshots.Snapshot relocatedBefore = Snapshots.of(relocated.publication());
        Snapshots.Snapshot literalBefore = Snapshots.of(literal.publication());

        for (RefusedSchema row : refused) {
            Publications.Built built = findItemWithCode(new JsonObject(row.schema()));
            Snapshots.Snapshot before = Snapshots.of(built.publication());

            // When: the document is assembled.
            RestConfigurationException failure = assembleExpectingFailure(PUBLIC_DOCUMENT, built);

            // Then: the failure names the mount, the operation, the parameter, and the pointer, and
            // echoes no value of the schema; the captured schema is unchanged.
            assertRefusalOfCode(row.name(), failure, row.pointer());
            before.assertUnchanged();
        }

        // When: the controls are assembled.
        JsonObject relocatedDoc = assemble(PUBLIC_DOCUMENT, relocated);
        JsonObject literalDoc = assemble(PUBLIC_DOCUMENT, literal);

        // Then (d): the schema and its definition become components, and the parameter references the
        // former.
        assertEquals(new JsonObject("""
                        {"name": "code", "in": "query",
                         "schema": {"$ref": "#/components/schemas/findItem.query.code"}}"""), findItemParameter(relocatedDoc));
        assertEquals(new JsonObject("""
                        {"findItem.query.code": {"$ref": "#/components/schemas/findItem.query.code.Code"},
                         "findItem.query.code.Code": {"type": "string", "pattern": "^[a-z]+$"}}"""), componentSchemas(relocatedDoc));

        // Then (f): the schema is published inline, unchanged, and no component is made of it.
        assertEquals(
                new JsonObject().put("name", "code").put("in", "query").put("schema", dataBefore),
                findItemParameter(literalDoc));
        assertFalse(
                componentKeys(literalDoc).contains("findItem.query.code"),
                () -> "(f) became a component: " + literalDoc.encode());

        relocatedBefore.assertUnchanged();
        literalBefore.assertUnchanged();
    }

    @Test
    @DisplayName("Relocation rewrites the root, definition, definition-path, and root-path reference forms")
    void relocationRewritesEveryFragmentReferenceForm() {
        // Given: captured schemas of findItem's query parameter 'code'. (a) references of every
        // fragment form: '#', '#/$defs/<def>', '#/$defs/<def>/<rest>', and a pointer into the root;
        // (b) definitions 'a/b' and 'c d', referenced with an escaped and a percent-encoded fragment;
        // (c) definitions 'a/b' and 'a_b', whose component keys coincide.
        JsonObject everyForm = new JsonObject("""
                {"$defs": {"Code": {"type": "string", "pattern": "^[a-z]+$"},
                           "Wrap": {"anyOf": [{"$ref": "#/$defs/Code"}, {"$ref": "#"}]}},
                 "allOf": [{"$ref": "#/$defs/Wrap"}, {"$ref": "#/$defs/Wrap/anyOf/0"}],
                 "not": {"$ref": "#/allOf/0"}}""");
        JsonObject encodedNames = new JsonObject("""
                {"$defs": {"a/b": {"type": "string"}, "c d": {"type": "integer"}},
                 "anyOf": [{"$ref": "#/$defs/a~1b"}, {"$ref": "#/$defs/c%20d"}]}""");
        JsonObject collidingNames = new JsonObject("""
                {"$defs": {"a/b": {"type": "string"}, "a_b": {"type": "integer"}},
                 "anyOf": [{"$ref": "#/$defs/a~1b"}, {"$ref": "#/$defs/a_b"}]}""");
        Publications.Built everyFormBuilt = findItemWithCode(everyForm);
        Publications.Built encodedNamesBuilt = findItemWithCode(encodedNames);
        Publications.Built collidingNamesBuilt = findItemWithCode(collidingNames);
        Snapshots.Snapshot everyFormBefore = Snapshots.of(everyFormBuilt.publication());
        Snapshots.Snapshot encodedNamesBefore = Snapshots.of(encodedNamesBuilt.publication());
        Snapshots.Snapshot collidingNamesBefore = Snapshots.of(collidingNamesBuilt.publication());

        // When: each document is assembled.
        JsonObject everyFormDoc = assemble(PUBLIC_DOCUMENT, everyFormBuilt);
        JsonObject encodedNamesDoc = assemble(PUBLIC_DOCUMENT, encodedNamesBuilt);
        RestConfigurationException collision = assembleExpectingFailure(PUBLIC_DOCUMENT, collidingNamesBuilt);

        // Then (a): the parameter references the component; the component is the root without $defs,
        // each definition is its own component, and every reference names its relocated target.
        JsonObject codeParameter = new JsonObject("""
                {"name": "code", "in": "query",
                 "schema": {"$ref": "#/components/schemas/findItem.query.code"}}""");
        assertEquals(codeParameter, findItemParameter(everyFormDoc));
        assertEquals(new JsonObject("""
                        {"findItem.query.code": {
                            "allOf": [{"$ref": "#/components/schemas/findItem.query.code.Wrap"},
                                      {"$ref": "#/components/schemas/findItem.query.code.Wrap/anyOf/0"}],
                            "not": {"$ref": "#/components/schemas/findItem.query.code/allOf/0"}},
                         "findItem.query.code.Code": {"type": "string", "pattern": "^[a-z]+$"},
                         "findItem.query.code.Wrap": {
                            "anyOf": [{"$ref": "#/components/schemas/findItem.query.code.Code"},
                                      {"$ref": "#/components/schemas/findItem.query.code"}]}}"""), componentSchemas(everyFormDoc));
        OpenApi31Toolchain.assertValid(everyFormDoc);

        // Then (b): the decoded definition names pass through the key replacement, and each reference
        // names its definition's key.
        assertEquals(codeParameter, findItemParameter(encodedNamesDoc));
        assertEquals(new JsonObject("""
                        {"findItem.query.code": {
                            "anyOf": [{"$ref": "#/components/schemas/findItem.query.code.a_b"},
                                      {"$ref": "#/components/schemas/findItem.query.code.c_d"}]},
                         "findItem.query.code.a_b": {"type": "string"},
                         "findItem.query.code.c_d": {"type": "integer"}}"""), componentSchemas(encodedNamesDoc));
        OpenApi31Toolchain.assertValid(encodedNamesDoc);

        // Then (c): publication fails naming the mount, the operation, and the parameter whose
        // definitions collide; relocated definitions are named as one component, never by key.
        String message = collision.getMessage();
        assertTrue(message.contains(MOUNT_FRAGMENT), () -> "the mount is not named: " + message);
        assertTrue(message.contains(FIND_ITEM_FRAGMENT), () -> "the operation is not named: " + message);
        assertTrue(message.contains(CODE_FRAGMENT), () -> "the parameter is not named: " + message);
        assertTrue(message.contains("one component"), () -> "the collision is not described: " + message);
        assertFalse(message.contains("findItem.query.code.a_b"), () -> "the key was echoed: " + message);

        // Then: every captured schema is unchanged.
        everyFormBefore.assertUnchanged();
        encodedNamesBefore.assertUnchanged();
        collidingNamesBefore.assertUnchanged();
    }

    @Test
    @DisplayName("Rewritten references keep the escaped and percent-encoded form of the tokens they carry over")
    void rewrittenReferencesKeepTheirEscaping() {
        // Given: findItem's parameter 'code' captured with properties 'a/b' and 'c d', a definition 'W'
        // with a property 'x y', and references into each: one needing '~1', one '%20' into the root,
        // and one '%20' below a definition.
        JsonObject escaped = new JsonObject("""
                {"properties": {"a/b": {"type": "string"}, "c d": {"type": "string"}},
                 "$defs": {"W": {"properties": {"x y": {"type": "integer"}}}},
                 "anyOf": [{"$ref": "#/properties/a~1b"},
                           {"$ref": "#/properties/c%20d"},
                           {"$ref": "#/$defs/W/properties/x%20y"}]}""");
        Publications.Built built = findItemWithCode(escaped);
        Snapshots.Snapshot before = Snapshots.of(built.publication());

        // When: the document is assembled.
        JsonObject doc = assemble(PUBLIC_DOCUMENT, built);

        // Then: the parameter references its component; the component is the root without $defs, the
        // definition is its own component, and each rewritten reference re-escapes '/' as '~1' and
        // percent-encodes the space.
        assertEquals(new JsonObject("""
                        {"name": "code", "in": "query",
                         "schema": {"$ref": "#/components/schemas/findItem.query.code"}}"""), findItemParameter(doc));
        assertEquals(new JsonObject("""
                        {"findItem.query.code": {
                            "properties": {"a/b": {"type": "string"}, "c d": {"type": "string"}},
                            "anyOf": [{"$ref": "#/components/schemas/findItem.query.code/properties/a~1b"},
                                      {"$ref": "#/components/schemas/findItem.query.code/properties/c%20d"},
                                      {"$ref": "#/components/schemas/findItem.query.code.W/properties/x%20y"}]},
                         "findItem.query.code.W": {"properties": {"x y": {"type": "integer"}}}}"""), componentSchemas(doc));
        OpenApi31Toolchain.assertValid(doc);

        // Then: the captured schema is unchanged.
        before.assertUnchanged();
    }

    @Test
    @DisplayName("A reference to the definitions object itself fails publication as unresolved")
    void referenceToTheDefinitionsObjectFailsAsUnresolved() {
        // Given: findItem's parameter 'code' captured with a reference to its root $defs object, which is
        // a map of names, not a schema.
        Publications.Built built = findItemWithCode(new JsonObject("""
                {"$defs": {"A": {}}, "$ref": "#/$defs"}"""));
        Snapshots.Snapshot before = Snapshots.of(built.publication());

        // When: the document is assembled.
        RestConfigurationException failure = assembleExpectingFailure(PUBLIC_DOCUMENT, built);

        // Then: the failure names the mount, the operation, the parameter, and the reference's pointer,
        // never the reference value; the captured schema is unchanged.
        assertRefusalOfCode("reference to $defs", failure, "/$ref");
        assertFalse(
                failure.getMessage().contains("#/$defs"), () -> "the reference was echoed: " + failure.getMessage());
        before.assertUnchanged();
    }
}
