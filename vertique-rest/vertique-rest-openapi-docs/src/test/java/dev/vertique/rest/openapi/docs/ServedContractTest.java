// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.publication.InputKey;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit proof that the checks of a served contract refuse each violation with a configuration error
 * that names the application and the offending operation ids or JSON Pointers, and accept every
 * valid variation.
 *
 * <p>Every row starts from one valid tree that describes the routed operations {@code listOrders},
 * {@code createOrder}, and {@code uploadNote} under mount-relative path keys, and applies exactly one
 * named edit. The routed operations of the mount are the one constant {@link #ROUTED}. A row expected
 * to fail asserts the exception type, that its message names the application, and that it contains
 * every hand-written fragment of the row: operation ids, JSON Pointers, and the short wording of the
 * violated rule, gathered as constants at the top of the class. Every tree carries the marker {@code
 * zq7} in a description or value; no message in the exception's cause chain may contain it, and rows
 * whose violation is a reference value also assert that the value is never echoed.
 *
 * <p>The test method carries a {@link Timeout} of ten seconds, which JUnit applies to each
 * parameterized invocation separately. It runs the invocation in a separate thread, so a check that
 * follows a reference cycle forever fails that one row instead of hanging the build: a same-thread
 * timeout only interrupts the thread, which a loop that never waits does not notice.
 */
@DisplayName("Served contract checks")
class ServedContractTest {

    // ---------------------------------------------------------------------------------------------
    // Message fragments the checks must produce
    // ---------------------------------------------------------------------------------------------

    /** How every refusal names the application. */
    private static final String APPLICATION_NAMED = "application 'partner'";

    /** A {@code $ref} value that does not start with {@code #/}. */
    private static final String NOT_LOCAL_REFERENCE = "is not a local reference";

    /** An {@code operationRef} or {@code $id} member. */
    private static final String MEMBER_NOT_ALLOWED = "is not allowed in a served contract";

    /** The {@code openapi} member is missing or is not {@code 3.0.<n>} or {@code 3.1.<n>}, or the root is not an object. */
    private static final String VERSION_RULE = "must be OpenAPI 3.0 or 3.1";

    /** How a refusal names the root pointer, which is the empty string. */
    private static final String ROOT_POINTER = "the document root";

    /** An Operation Object without a non-blank {@code operationId}. */
    private static final String MISSING_OPERATION_ID = "has no operationId";

    /** An {@code operationId} the mount does not route. */
    private static final String NOT_ROUTED = "is not routed by the mount";

    /** A routed operation that is not hidden and has no route operation. */
    private static final String NOT_DESCRIBED = "is not described";

    /** Two Operation Objects share one {@code operationId}. */
    private static final String REPEATED = "is repeated";

    /** A route operation whose method or rendered path differs from its routed twin. */
    private static final String NOT_BOUND = "is not bound to its routed operation";

    /** A webhook or callback operation whose id is a routed id. */
    private static final String REUSES_ROUTED_ID = "reuses a routed operation id";

    /** A reference chain that comes back to a reference it already followed. */
    private static final String REFERENCE_CYCLE = "reference cycle";

    /** A Parameter Object or Link parameter key naming a hidden input of the operation. */
    private static final String HIDDEN_INPUT = "describes a hidden input";

    /** A form schema property naming a hidden form input of the operation. */
    private static final String HIDDEN_FORM_INPUT = "describes a hidden form input";

    /** A form request body schema with an applicator or property-expansion keyword, or no {@code properties}. */
    private static final String NOT_PLAIN_OBJECT = "is not a plain object schema";

    /** A Link {@code requestBody} for an operation that has a hidden form input. */
    private static final String LINK_REQUEST_BODY = "link request body";

    /** The marker no message may contain. */
    private static final String MARKER = "zq7";

    // ---------------------------------------------------------------------------------------------
    // The mount
    // ---------------------------------------------------------------------------------------------

    /** The application every row checks. */
    private static final String APPLICATION = "partner";

    /** The application's normalized mount path. */
    private static final String MOUNT_PATH = "/api/partner";

    /**
     * The operations the mount routes: {@code listOrders} with hidden query {@code debug} and header
     * {@code X-Debug}; {@code createOrder}; {@code uploadNote}, which consumes a URL-encoded form with
     * the hidden form input {@code internalRef}; and the hidden {@code getOrderInternal} with hidden
     * query {@code trace}.
     */
    private static final List<RoutedOperation> ROUTED = List.of(
            new RoutedOperation(
                    "listOrders",
                    "GET",
                    "/orders",
                    false,
                    Set.of(new InputKey(ParamLocation.QUERY, "debug"), new InputKey(ParamLocation.HEADER, "X-Debug"))),
            new RoutedOperation("createOrder", "POST", "/orders", false, Set.of()),
            new RoutedOperation(
                    "uploadNote",
                    "POST",
                    "/orders/notes",
                    false,
                    Set.of(new InputKey(ParamLocation.FORM, "internalRef"))),
            new RoutedOperation(
                    "getOrderInternal",
                    "GET",
                    "/orders/{id}",
                    true,
                    Set.of(new InputKey(ParamLocation.QUERY, "trace"))));

    /** The valid tree every row edits. */
    private static final String VALID_TREE = """
            {
              "openapi": "3.1.0",
              "info": {"title": "Partner", "version": "1.0", "description": "Partner orders zq7"},
              "servers": [{"url": "/api/partner"}],
              "paths": {
                "/orders": {
                  "get": {
                    "operationId": "listOrders",
                    "responses": {"200": {"description": "The orders"}}
                  },
                  "post": {
                    "operationId": "createOrder",
                    "responses": {"201": {"description": "Created"}}
                  }
                },
                "/orders/notes": {
                  "post": {
                    "operationId": "uploadNote",
                    "requestBody": {
                      "content": {
                        "application/x-www-form-urlencoded": {
                          "schema": {"$ref": "#/components/schemas/NoteForm"}
                        }
                      }
                    },
                    "responses": {"204": {"description": "Stored"}}
                  }
                }
              },
              "components": {
                "schemas": {
                  "NoteForm": {"type": "object", "properties": {"text": {"type": "string"}}},
                  "Order": {"type": "object", "properties": {"sku": {"type": "string"}}}
                }
              }
            }
            """;

    // ---------------------------------------------------------------------------------------------
    // JSON Pointers of the valid tree
    // ---------------------------------------------------------------------------------------------

    private static final String ORDERS = "/paths/~1orders";
    private static final String LIST_ORDERS = ORDERS + "/get";
    private static final String CREATE_ORDER = ORDERS + "/post";
    private static final String UPLOAD_NOTE = "/paths/~1orders~1notes/post";
    private static final String NOTE_FORM_SCHEMA =
            UPLOAD_NOTE + "/requestBody/content/application~1x-www-form-urlencoded/schema";
    private static final String CREATE_ORDER_JSON_SCHEMA =
            CREATE_ORDER + "/requestBody/content/application~1json/schema";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("The contract checks refuse each violation, naming ids or pointers, and accept valid variations")
    void contractChecksRefuseEachViolationNamingIdsOrPointers(
            String label, UnaryOperator<JsonNode> edit, Expectation expectation) {
        // Given
        JsonNode tree = edit.apply(json(VALID_TREE));

        // When
        Executable check = () -> ServedContractChecks.check(APPLICATION, tree, MOUNT_PATH, ROUTED);

        // Then
        if (expectation.fragments().isEmpty()) {
            assertDoesNotThrow(check, label);
            return;
        }
        RestConfigurationException failure = assertThrows(RestConfigurationException.class, check, label);
        String message = failure.getMessage();
        assertNotNull(message, label);
        List<Executable> assertions = new ArrayList<>();
        assertions.add(() -> assertTrue(
                message.contains(APPLICATION_NAMED), () -> label + ": names " + APPLICATION_NAMED + " in: " + message));
        for (String fragment : expectation.fragments()) {
            assertions.add(() -> assertTrue(
                    message.contains(fragment), () -> label + ": contains '" + fragment + "' in: " + message));
        }
        for (String value : expectation.neverEchoed()) {
            assertions.add(() -> assertFalse(
                    message.contains(value), () -> label + ": never echoes '" + value + "' in: " + message));
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String causeMessage = String.valueOf(cause.getMessage());
            assertions.add(() -> assertFalse(
                    causeMessage.contains(MARKER), () -> label + ": never echoes the marker in: " + causeMessage));
        }
        assertAll(label, assertions);
    }

    static Stream<Arguments> rows() {
        Map<String, Row> rows = new LinkedHashMap<>();

        // ----- Local references, operationRef, and $id -----
        rows.put(
                "(1) a $ref to another file",
                row(
                        root -> createOrderBody(root, "other.yaml#/components/schemas/Order"),
                        refused(NOT_LOCAL_REFERENCE, CREATE_ORDER_JSON_SCHEMA + "/$ref")
                                .neverEchoing("other.yaml")));
        rows.put(
                "(2) a $ref to a URL",
                row(
                        root -> createOrderBody(root, "https://example.com/order.json"),
                        refused(NOT_LOCAL_REFERENCE, CREATE_ORDER_JSON_SCHEMA + "/$ref")
                                .neverEchoing("example.com", "order.json")));
        rows.put(
                "(3) a $ref that is a relative path",
                row(
                        root -> createOrderBody(root, "components/schemas/Order"),
                        refused(NOT_LOCAL_REFERENCE, CREATE_ORDER_JSON_SCHEMA + "/$ref")
                                .neverEchoing("components/schemas/Order")));
        rows.put(
                "(21) a Link Object with an operationRef",
                row(
                        root -> obj(root, LIST_ORDERS + "/responses/200").set("links", json("""
                                {"next": {"operationRef": "https://example.com/zq7#/paths/~1orders/get"}}
                                """)),
                        refused(MEMBER_NOT_ALLOWED, LIST_ORDERS + "/responses/200/links/next/operationRef")
                                .neverEchoing("example.com")));
        rows.put(
                "(22) a schema carrying $id",
                row(
                        root -> obj(root, "/components/schemas/Order")
                                .put("$id", "https://example.com/schemas/order-zq7"),
                        refused(MEMBER_NOT_ALLOWED, "/components/schemas/Order/$id")
                                .neverEchoing("example.com")));
        rows.put(
                "(23) a Path Item reference cycle a, b, a reached from paths",
                row(
                        root -> {
                            obj(root, "/paths").set("/loop", json("{\"$ref\": \"#/components/pathItems/a\"}"));
                            ObjectNode pathItems = obj(root, "/components").putObject("pathItems");
                            pathItems.set("a", json("{\"$ref\": \"#/components/pathItems/b\"}"));
                            pathItems.set("b", json("{\"$ref\": \"#/components/pathItems/a\"}"));
                        },
                        refused(REFERENCE_CYCLE, "/components/pathItems/b")));

        // ----- OpenAPI version -----
        rows.put(
                "(7) openapi 2.0", row(root -> obj(root, "").put("openapi", "2.0"), refused(VERSION_RULE, "/openapi")));
        rows.put(
                "(8) swagger 2.0 and no openapi",
                row(
                        root -> {
                            obj(root, "").remove("openapi");
                            obj(root, "").put("swagger", "2.0");
                        },
                        refused(VERSION_RULE, "/openapi")));
        rows.put(
                "(9) a root array",
                replacingRoot(root -> MAPPER.createArrayNode().add(root), refused(VERSION_RULE, ROOT_POINTER)));
        rows.put("(18) openapi 3", row(root -> obj(root, "").put("openapi", "3"), refused(VERSION_RULE, "/openapi")));
        rows.put(
                "(34) openapi 3.2.0",
                row(root -> obj(root, "").put("openapi", "3.2.0"), refused(VERSION_RULE, "/openapi")));
        rows.put(
                "(35) openapi 3.garbage",
                row(root -> obj(root, "").put("openapi", "3.garbage"), refused(VERSION_RULE, "/openapi")));

        // ----- Operation ids: leak, completeness, duplicates, missing ids -----
        rows.put(
                "(4) an extra operation deleteOrder under paths",
                row(
                        root -> obj(root, ORDERS).set("delete", operation("deleteOrder")),
                        refused("deleteOrder", NOT_ROUTED)));
        rows.put(
                "(5) createOrder removed",
                row(root -> obj(root, ORDERS).remove("post"), refused("createOrder", NOT_DESCRIBED)));
        rows.put(
                "(6) an operation under paths without operationId",
                row(
                        root -> obj(root, ORDERS)
                                .set("delete", json("{\"responses\": {\"204\": {\"description\": \"Gone\"}}}")),
                        refused(MISSING_OPERATION_ID, ORDERS + "/delete")));
        rows.put(
                "(10) listOrders described as listorders",
                row(
                        root -> obj(root, LIST_ORDERS).put("operationId", "listorders"),
                        refused("listorders", NOT_ROUTED, "listOrders", NOT_DESCRIBED)));
        rows.put(
                "(11) a referenced Path Item holding deleteOrder",
                row(
                        root -> {
                            obj(root, "/paths")
                                    .set("/orders/archive", json("{\"$ref\": \"#/components/pathItems/orders\"}"));
                            obj(root, "/components")
                                    .putObject("pathItems")
                                    .putObject("orders")
                                    .set("delete", operation("deleteOrder"));
                        },
                        refused("deleteOrder", NOT_ROUTED)));
        rows.put(
                "(12) a second operation under paths with the id listOrders",
                row(
                        root -> obj(root, "/paths")
                                .putObject("/api/partner/orders")
                                .set("get", operation("listOrders")),
                        refused("listOrders", REPEATED, LIST_ORDERS, "/paths/~1api~1partner~1orders/get")));
        rows.put(
                "(15) a webhooks operation without operationId",
                row(
                        root -> obj(root, "")
                                .putObject("webhooks")
                                .putObject("shipped")
                                .set("post", json("{\"responses\": {\"200\": {\"description\": \"Received\"}}}")),
                        refused(MISSING_OPERATION_ID, "/webhooks/shipped/post")));
        rows.put(
                "(16) a Link Object naming the unrouted deleteOrder",
                row(
                        root -> obj(root, LIST_ORDERS + "/responses/200")
                                .set("links", json("{\"remove\": {\"operationId\": \"deleteOrder\"}}")),
                        refused("deleteOrder", NOT_ROUTED)));
        rows.put(
                "(17) an example value naming the unrouted exportOrders",
                row(
                        root -> obj(root, "/components/schemas/Order")
                                .set("example", json("{\"operationId\": \"exportOrders\"}")),
                        refused("exportOrders", NOT_ROUTED)));
        rows.put(
                "(28) createOrder described only as a webhook operation",
                row(
                        root -> {
                            obj(root, ORDERS).remove("post");
                            obj(root, "")
                                    .putObject("webhooks")
                                    .putObject("created")
                                    .set("post", operation("createOrder"));
                        },
                        refused("createOrder", NOT_DESCRIBED, "/webhooks/created/post", REUSES_ROUTED_ID)));
        rows.put(
                "(29) two webhooks operations with the id orderShipped",
                row(
                        root -> {
                            ObjectNode webhooks = obj(root, "").putObject("webhooks");
                            webhooks.putObject("shipped").set("post", operation("orderShipped"));
                            webhooks.putObject("reshipped").set("post", operation("orderShipped"));
                        },
                        refused("orderShipped", REPEATED, "/webhooks/shipped/post", "/webhooks/reshipped/post")));
        rows.put(
                "a webhooks operation with the routed id getOrderInternal",
                row(
                        root -> obj(root, "")
                                .putObject("webhooks")
                                .putObject("internal")
                                .set("get", operation("getOrderInternal")),
                        refused("/webhooks/internal/get", REUSES_ROUTED_ID)));

        // ----- Operation binding -----
        rows.put(
                "(19) listOrders described at /orders/all",
                row(
                        root -> {
                            JsonNode list = obj(root, ORDERS).remove("get");
                            obj(root, "/paths").putObject("/orders/all").set("get", list);
                        },
                        refused(NOT_BOUND, "/paths/~1orders~1all/get")));
        rows.put(
                "(20) listOrders described under put at /orders",
                row(
                        root -> obj(root, ORDERS).set("put", obj(root, ORDERS).remove("get")),
                        refused(NOT_BOUND, ORDERS + "/put")));

        // ----- Hidden parameters -----
        rows.put(
                "(24) listOrders describing the hidden query parameter debug",
                row(
                        root -> obj(root, LIST_ORDERS).set("parameters", json("""
                        [{"name": "debug", "in": "query", "description": "zq7", "schema": {"type": "boolean"}}]
                        """)),
                        refused("listOrders", HIDDEN_INPUT, LIST_ORDERS + "/parameters/0")));
        rows.put(
                "(25) the hidden debug given by a reference in the Path Item's parameters",
                row(
                        root -> {
                            obj(root, ORDERS)
                                    .set("parameters", json("[{\"$ref\": \"#/components/parameters/debug\"}]"));
                            obj(root, "/components").set("parameters", json("""
                            {"debug": {"name": "debug", "in": "query", "description": "zq7",
                                       "schema": {"type": "boolean"}}}
                            """));
                        },
                        refused("listOrders", HIDDEN_INPUT, "/components/parameters/debug")));
        rows.put(
                "(26) listOrders describing the hidden header X-Debug in another case",
                row(
                        root -> obj(root, LIST_ORDERS).set("parameters", json("""
                        [{"name": "x-debug", "in": "header", "description": "zq7", "schema": {"type": "string"}}]
                        """)),
                        refused("listOrders", HIDDEN_INPUT, LIST_ORDERS + "/parameters/0")));
        rows.put(
                "(32) an unreferenced components Path Item describing the hidden trace of getOrderInternal",
                row(
                        root -> obj(root, "/components").putObject("pathItems").set("internal", json("""
                        {"get": {"operationId": "getOrderInternal",
                                 "parameters": [{"name": "trace", "in": "query", "description": "zq7",
                                                 "schema": {"type": "string"}}],
                                 "responses": {"200": {"description": "The order"}}}}
                        """)),
                        refused("getOrderInternal", HIDDEN_INPUT, "/components/pathItems/internal/get/parameters/0")));

        // ----- Hidden form inputs and form schemas -----
        rows.put(
                "(27) NoteForm gaining the hidden property internalRef",
                row(
                        root -> obj(root, "/components/schemas/NoteForm/properties")
                                .set("internalRef", json("{\"type\": \"string\", \"description\": \"zq7\"}")),
                        refused(
                                "uploadNote",
                                HIDDEN_FORM_INPUT,
                                "/components/schemas/NoteForm/properties/internalRef")));
        rows.put(
                "(30) a form schema whose allOf reaches internalRef",
                row(
                        root -> {
                            noteFormSchema(root, """
                            {"type": "object", "properties": {"text": {"type": "string"}},
                             "allOf": [{"$ref": "#/components/schemas/NoteForm"}]}
                            """);
                            obj(root, "/components/schemas/NoteForm/properties")
                                    .set("internalRef", json("{\"type\": \"string\", \"description\": \"zq7\"}"));
                        },
                        notPlainObject()));
        rows.put(
                "(31) a form schema without properties",
                row(
                        root -> noteFormSchema(root, "{\"type\": \"object\", \"description\": \"zq7\"}"),
                        notPlainObject()));
        rows.put(
                "(36) a form schema with patternProperties",
                row(
                        root -> formSchemaWith(
                                root, "patternProperties", "{\"^internalRef$\": {\"type\": \"string\"}}"),
                        notPlainObject()));
        rows.put(
                "(37) a form schema with propertyNames",
                row(
                        root -> formSchemaWith(root, "propertyNames", "{\"pattern\": \"^internalRef$\"}"),
                        notPlainObject()));
        rows.put(
                "(38) a form schema with additionalProperties",
                row(root -> formSchemaWith(root, "additionalProperties", "true"), notPlainObject()));
        rows.put(
                "(39) a form schema with unevaluatedProperties",
                row(root -> formSchemaWith(root, "unevaluatedProperties", "true"), notPlainObject()));
        rows.put(
                "(42) a form schema with anyOf",
                row(root -> formSchemaWith(root, "anyOf", "[" + INTERNAL_REF_SCHEMA + "]"), notPlainObject()));
        rows.put(
                "(43) a form schema with oneOf",
                row(root -> formSchemaWith(root, "oneOf", "[" + INTERNAL_REF_SCHEMA + "]"), notPlainObject()));
        rows.put(
                "(44) a form schema with not",
                row(root -> formSchemaWith(root, "not", INTERNAL_REF_SCHEMA), notPlainObject()));
        rows.put(
                "(45) a form schema with if",
                row(root -> formSchemaWith(root, "if", INTERNAL_REF_SCHEMA), notPlainObject()));
        rows.put(
                "(46) a form schema with then",
                row(root -> formSchemaWith(root, "then", INTERNAL_REF_SCHEMA), notPlainObject()));
        rows.put(
                "(47) a form schema with else",
                row(root -> formSchemaWith(root, "else", INTERNAL_REF_SCHEMA), notPlainObject()));
        rows.put(
                "(48) a form schema with dependentSchemas",
                row(
                        root -> formSchemaWith(root, "dependentSchemas", "{\"trigger\": " + INTERNAL_REF_SCHEMA + "}"),
                        notPlainObject()));

        // ----- Links naming routed operations -----
        rows.put(
                "(33) a Link naming listOrders with the parameter key query.debug",
                row(
                        root -> createOrderLink(
                                root, "{\"operationId\": \"listOrders\", \"parameters\": {\"query.debug\": \"zq7\"}}"),
                        refused(
                                "listOrders",
                                HIDDEN_INPUT,
                                CREATE_ORDER + "/responses/201/links/related/parameters/query.debug")));
        rows.put(
                "(40) a Link naming uploadNote with a literal requestBody",
                row(
                        root -> createOrderLink(
                                root,
                                "{\"operationId\": \"uploadNote\", \"requestBody\": {\"internalRef\": \"x\"}, \"description\": \"zq7\"}"),
                        refused(
                                "uploadNote",
                                LINK_REQUEST_BODY,
                                CREATE_ORDER + "/responses/201/links/related/requestBody")));
        rows.put(
                "(41) a Link naming uploadNote with a runtime-expression requestBody",
                row(
                        root -> createOrderLink(
                                root,
                                "{\"operationId\": \"uploadNote\", \"requestBody\": \"$request.body#/internalRef\", \"description\": \"zq7\"}"),
                        refused(
                                "uploadNote",
                                LINK_REQUEST_BODY,
                                CREATE_ORDER + "/responses/201/links/related/requestBody")));

        // ----- Rows expected to pass -----
        rows.put(
                "(13) a webhooks operation with the unrouted id orderShipped",
                row(
                        root -> obj(root, "")
                                .putObject("webhooks")
                                .putObject("shipped")
                                .set("post", operation("orderShipped")),
                        passes()));
        rows.put(
                "(14) a callback operation with the unrouted id notifyPartner",
                row(root -> obj(root, CREATE_ORDER).set("callbacks", json("""
                        {"onShipped": {"{$request.body#/callbackUrl}": {"post": {
                            "operationId": "notifyPartner",
                            "responses": {"200": {"description": "Received"}}}}}}
                        """)), passes()));
        rows.put("the valid tree", row(root -> {}, passes()));
        rows.put(
                "the hidden getOrderInternal described with another variable name",
                row(root -> obj(root, "/paths").set("/orders/{orderId}", json("""
                        {"get": {"operationId": "getOrderInternal",
                                 "parameters": [{"name": "orderId", "in": "path", "required": true,
                                                 "schema": {"type": "string"}}],
                                 "responses": {"200": {"description": "The order"}}}}
                        """)), passes()));
        rows.put(
                "the orders Path Item given by a reference",
                row(
                        root -> {
                            JsonNode orders = obj(root, "/paths")
                                    .replace("/orders", json("{\"$ref\": \"#/components/pathItems/orders\"}"));
                            obj(root, "/components").putObject("pathItems").set("orders", orders);
                        },
                        passes()));
        rows.put(
                "a property named $ref with an object value",
                row(
                        root -> obj(root, "/components/schemas/Order/properties")
                                .set("$ref", json("{\"type\": \"string\"}")),
                        passes()));
        rows.put("a local $ref", row(root -> createOrderBody(root, "#/components/schemas/Order"), passes()));
        rows.put(
                "a Link naming listOrders",
                row(root -> createOrderLink(root, "{\"operationId\": \"listOrders\"}"), passes()));
        rows.put(
                "mount-prefixed path keys",
                row(
                        root -> {
                            ObjectNode paths = obj(root, "/paths");
                            ObjectNode prefixed = MAPPER.createObjectNode();
                            for (Map.Entry<String, JsonNode> entry : paths.properties()) {
                                prefixed.set(MOUNT_PATH + entry.getKey(), entry.getValue());
                            }
                            obj(root, "").set("paths", prefixed);
                        },
                        passes()));
        rows.put(
                "listOrders describing the visible query parameter page",
                row(
                        root -> obj(root, LIST_ORDERS)
                                .set(
                                        "parameters",
                                        json(
                                                "[{\"name\": \"page\", \"in\": \"query\", \"schema\": {\"type\": \"integer\"}}]")),
                        passes()));
        rows.put(
                "an example with an externalValue",
                row(root -> obj(root, LIST_ORDERS + "/responses/200").set("content", json("""
                        {"application/json": {"examples": {"all": {"externalValue": "https://example.com/orders.json"}}}}
                        """)), passes()));
        rows.put(
                "a Link naming listOrders with the visible parameter key page",
                row(
                        root -> createOrderLink(
                                root, "{\"operationId\": \"listOrders\", \"parameters\": {\"page\": \"1\"}}"),
                        passes()));
        rows.put("openapi 3.0.3", row(root -> obj(root, "").put("openapi", "3.0.3"), passes()));
        rows.put(
                "a Link naming listOrders with a literal requestBody",
                row(
                        root -> createOrderLink(
                                root, "{\"operationId\": \"listOrders\", \"requestBody\": {\"note\": \"x\"}}"),
                        passes()));

        // ----- Referenced Links naming hidden inputs at their own location -----
        rows.put(
                "(49) a components Link naming listOrders with query.debug, given by a reference",
                row(
                        root -> referencedLink(root, COMPONENTS_LINK, "query.debug"),
                        refused("listOrders", HIDDEN_INPUT, COMPONENTS_LINK + "/parameters/query.debug")));
        rows.put(
                "(50) a components Link naming listOrders with header.X-Debug, given by a reference",
                row(
                        root -> referencedLink(root, COMPONENTS_LINK, "header.X-Debug"),
                        refused("listOrders", HIDDEN_INPUT, COMPONENTS_LINK + "/parameters/header.X-Debug")));
        rows.put(
                "(51) an extension Link naming listOrders with query.debug, given by a reference",
                row(
                        root -> referencedLink(root, EXTENSION_LINK, "query.debug"),
                        refused("listOrders", HIDDEN_INPUT, EXTENSION_LINK + "/parameters/query.debug")));
        rows.put(
                "(52) an extension Link naming listOrders with header.X-Debug, given by a reference",
                row(
                        root -> referencedLink(root, EXTENSION_LINK, "header.X-Debug"),
                        refused("listOrders", HIDDEN_INPUT, EXTENSION_LINK + "/parameters/header.X-Debug")));
        rows.put(
                "a components Link naming listOrders with the visible key page, given by a reference",
                row(root -> referencedLink(root, COMPONENTS_LINK, "page"), passes()));

        // ----- A tree that is not a contract reports only the version rule -----
        rows.put(
                "(53) a tree without openapi carrying an external $ref, an operationId, and a path key",
                replacingRoot(root -> json(NOT_A_CONTRACT), notAContract(VERSION_RULE, "/openapi")));
        rows.put(
                "(54) a root array holding that tree",
                replacingRoot(
                        root -> MAPPER.createArrayNode().add(json(NOT_A_CONTRACT)),
                        notAContract(VERSION_RULE, ROOT_POINTER)));

        return rows.entrySet().stream()
                .map(entry -> Arguments.of(
                        entry.getKey(),
                        Named.of("edit", entry.getValue().edit()),
                        Named.of("expectation", entry.getValue().expectation())));
    }

    // ---------------------------------------------------------------------------------------------
    // Edits
    // ---------------------------------------------------------------------------------------------

    /** A schema describing only the hidden form input. */
    private static final String INTERNAL_REF_SCHEMA = "{\"properties\": {\"internalRef\": {\"type\": \"string\"}}}";

    /** Where a referenced Link is stored under {@code components}. */
    private static final String COMPONENTS_LINK = "/components/links/DebugLink";

    /** Where a referenced Link is stored under a root extension. */
    private static final String EXTENSION_LINK = "/x-stash/L";

    /**
     * A tree with no {@code openapi} member that also carries an external {@code $ref} under a key, an
     * {@code operationId} no mount routes, and a path key, each holding a marked token.
     */
    private static final String NOT_A_CONTRACT = """
            {
              "aws": {"sk_live_zq7KEY": {"$ref": "https://zq7user:pw@internal.example/x"}},
              "paths": {"/admin?zq7path=s3cr3t": {"get": {}}},
              "svc": {"operationId": "zq7value"}
            }
            """;

    /**
     * Refuses {@link #NOT_A_CONTRACT} with the given fragments only: the message carries none of the
     * tree's tokens and none of the wording of another rule.
     */
    private static Expectation notAContract(String... fragments) {
        Expectation refusal = refused(fragments);
        return refusal.neverEchoing(
                "zq7KEY",
                "zq7value",
                "zq7path",
                "internal.example",
                "s3cr3t",
                NOT_LOCAL_REFERENCE,
                NOT_ROUTED,
                MISSING_OPERATION_ID,
                NOT_DESCRIBED);
    }

    /**
     * Stores a Link naming {@code listOrders} with the one parameter key at the given pointer of the
     * root, and gives the {@code 200} response of {@code listOrders} the one link {@code debug}, a
     * reference to it.
     */
    private static void referencedLink(JsonNode root, String pointer, String parameterKey) {
        int split = pointer.lastIndexOf('/');
        String parent = pointer.substring(0, split);
        String name = pointer.substring(split + 1);
        ObjectNode link = MAPPER.createObjectNode().put("operationId", "listOrders");
        link.putObject("parameters").put(parameterKey, "$response.body#/zq7");
        ensureObject(root, parent).set(name, link);
        obj(root, LIST_ORDERS + "/responses/200")
                .putObject("links")
                .putObject("debug")
                .put("$ref", "#" + pointer);
    }

    /** The object at a JSON Pointer of plain member names, creating every missing object on the way. */
    private static ObjectNode ensureObject(JsonNode root, String pointer) {
        ObjectNode current = obj(root, "");
        for (String member : pointer.substring(1).split("/")) {
            JsonNode next = current.get(member);
            current = next instanceof ObjectNode object ? object : current.putObject(member);
        }
        return current;
    }

    /** Gives {@code createOrder} a JSON request body whose schema is the given reference. */
    private static void createOrderBody(JsonNode root, String ref) {
        ObjectNode body = obj(root, CREATE_ORDER).putObject("requestBody");
        body.putObject("content")
                .putObject("application/json")
                .putObject("schema")
                .put("$ref", ref);
    }

    /** Gives the {@code 201} response of {@code createOrder} the one link {@code related}. */
    private static void createOrderLink(JsonNode root, String link) {
        obj(root, CREATE_ORDER + "/responses/201").putObject("links").set("related", json(link));
    }

    /** Replaces the referenced form schema of {@code uploadNote} with an inline one. */
    private static void noteFormSchema(JsonNode root, String schema) {
        obj(root, UPLOAD_NOTE + "/requestBody/content/application~1x-www-form-urlencoded")
                .set("schema", json(schema));
    }

    /** Replaces the form schema of {@code uploadNote} with an inline one carrying {@code text} and one keyword. */
    private static void formSchemaWith(JsonNode root, String keyword, String value) {
        ObjectNode schema = (ObjectNode) json(
                "{\"type\": \"object\", \"description\": \"zq7\", \"properties\": {\"text\": {\"type\": \"string\"}}}");
        schema.set(keyword, json(value));
        noteFormSchema(root, schema.toString());
    }

    /** An Operation Object with the given id and one response. */
    private static JsonNode operation(String operationId) {
        ObjectNode operation = MAPPER.createObjectNode().put("operationId", operationId);
        operation.putObject("responses").putObject("200").put("description", "OK");
        return operation;
    }

    /** The object at a JSON Pointer of the tree; the empty pointer is the root. */
    private static ObjectNode obj(JsonNode root, String pointer) {
        JsonNode node = root.at(pointer);
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalStateException("No object at " + pointer);
        }
        return object;
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Rows and expectations
    // ---------------------------------------------------------------------------------------------

    /** One row: an edit of the valid tree and what the checks do with the result. */
    private record Row(UnaryOperator<JsonNode> edit, Expectation expectation) {}

    /**
     * What the checks do with a tree: pass when there are no fragments, otherwise refuse with a
     * message containing every fragment and none of the values never echoed.
     */
    private record Expectation(List<String> fragments, List<String> neverEchoed) {

        Expectation neverEchoing(String... values) {
            return new Expectation(fragments, List.of(values));
        }

        @Override
        public String toString() {
            return fragments.isEmpty() ? "passes" : "refused naming " + fragments;
        }
    }

    private static Row row(Consumer<JsonNode> edit, Expectation expectation) {
        return new Row(
                root -> {
                    edit.accept(root);
                    return root;
                },
                expectation);
    }

    private static Row replacingRoot(UnaryOperator<JsonNode> edit, Expectation expectation) {
        return new Row(edit, expectation);
    }

    private static Expectation refused(String... fragments) {
        return new Expectation(List.of(fragments), List.of());
    }

    private static Expectation notPlainObject() {
        return refused("uploadNote", NOT_PLAIN_OBJECT, NOTE_FORM_SCHEMA);
    }

    private static Expectation passes() {
        return new Expectation(List.of(), List.of());
    }
}
