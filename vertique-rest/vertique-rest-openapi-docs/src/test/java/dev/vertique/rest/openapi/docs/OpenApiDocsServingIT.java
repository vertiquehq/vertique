// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.MarkerRouterMount;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves the {@code public} document of the shared fixture over a real, loopback-bound
 * {@code HttpVerticle} deployment and checks what a client observes: the JSON and YAML forms with
 * their strong entity tags, conditional requests and {@code HEAD}; that the documentation mount runs
 * before every application mount and answers only its exact document URLs; and that the documents'
 * {@code Cache-Control} follows the effective default-headers value without ever becoming public.
 *
 * <p>Every expectation is written by hand: the served JSON bytes are a literal, entity tags are
 * computed here from the received bytes, and the YAML form is parsed by an independent Jackson YAML
 * mapper. One Vert.x instance and one client serve the class; each deployment is undeployed, and the
 * {@code vertique} local map cleared, before the next.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class OpenApiDocsServingIT {

    private static final Logger LOG = LoggerFactory.getLogger(OpenApiDocsServingIT.class);

    private static final String HOST = "127.0.0.1";

    /**
     * The exact bytes the {@code public} document's JSON form must have under the shared configuration.
     *
     * <p>Each member, and the rule it follows (compact JSON, members in the writer's fixed order):
     *
     * <ul>
     *   <li>Root members in the order {@code openapi}, {@code info}, {@code jsonSchemaDialect},
     *       {@code servers}, {@code paths}, {@code components}, {@code tags},
     *       {@code x-vertique-validation}.
     *   <li>{@code info}: the configured title and version; no description is configured.
     *   <li>{@code jsonSchemaDialect}: every document declares JSON Schema draft 2020-12.
     *   <li>{@code servers}: no server URL is configured, so the one entry is the mount path
     *       {@code /api/public/*} without its {@code /*}.
     *   <li>{@code paths}: one key per route template relative to the mount, in natural string order
     *       ({@code /items} before {@code /items/{id}}); within a path, methods in the order get,
     *       put, post, delete, options, head, patch, trace; each operation lists {@code tags} (the
     *       class tag), {@code summary} (from the operation annotation; no description or external
     *       documentation is set, so neither is written), {@code operationId}
     *       (the runtime id, the method name), then {@code parameters} (left out when empty), then
     *       {@code requestBody} (left out when the operation has no body). Responses are not part
     *       of this document yet.
     *   <li>{@code tags}: the root list holds the one tag the resource class declares,
     *       {@code {"name":"catalog"}}; it has no description or external documentation, and unset
     *       members are never written.
     *   <li>Parameters in declaration order, each as {@code name}, {@code in} (lowercase location),
     *       {@code required} (only {@code true}, only for an input that is certainly required),
     *       {@code schema}. No parameter carries a description annotation.
     *   <li>{@code limit}: a boxed query parameter with no constraint is not required, so no
     *       {@code required} key; its schema is the one the schema source captured for it, published
     *       unchanged: {@code {"type":"integer"}}.
     *   <li>{@code id}: a path parameter is always required; captured schema
     *       {@code {"type":"string"}}.
     *   <li>{@code dryRun}: a primitive query parameter without a default is of unknown requiredness,
     *       so no {@code required} key; it is the first declared parameter of its operation, so the
     *       schema source captured {@code {"type":"boolean"}} for it.
     *   <li>{@code createItem}'s request body: one media type per consumed type
     *       ({@code application/json}), each referencing the component
     *       {@code createItem.request}; no {@code required} key, because the {@code none} strategy
     *       installs no validation gate.
     *   <li>{@code components.schemas}: keys in natural order; {@code createItem.request} is the
     *       captured body schema published unchanged (it has no local definitions to relocate and no
     *       references to rewrite): the input-direction description of {@code CreateItemRequest}
     *       under the default profile, with its root {@code $schema} and its keys in canonical order.
     *   <li>{@code x-vertique-validation}: every document records the pattern dialect
     *       {@code java.util.regex}; a public document records nothing else there.
     * </ul>
     */
    private static final String EXPECTED_JSON = "{\"openapi\":\"3.1.1\","
            + "\"info\":{\"title\":\"Catalog\",\"version\":\"1.0\"},"
            + "\"jsonSchemaDialect\":\"https://json-schema.org/draft/2020-12/schema\","
            + "\"servers\":[{\"url\":\"/api/public\"}],"
            + "\"paths\":{"
            + "\"/items\":{"
            + "\"get\":{\"tags\":[\"catalog\"],\"summary\":\"List items\",\"operationId\":\"listItems\","
            + "\"parameters\":[{\"name\":\"limit\",\"in\":\"query\",\"schema\":{\"type\":\"integer\"}}]},"
            + "\"post\":{\"tags\":[\"catalog\"],\"summary\":\"Create an item\",\"operationId\":\"createItem\","
            + "\"parameters\":[{\"name\":\"dryRun\",\"in\":\"query\",\"schema\":{\"type\":\"boolean\"}}],"
            + "\"requestBody\":{\"content\":{\"application/json\":"
            + "{\"schema\":{\"$ref\":\"#/components/schemas/createItem.request\"}}}}}},"
            + "\"/items/{id}\":{"
            + "\"get\":{\"tags\":[\"catalog\"],\"summary\":\"Get an item\",\"operationId\":\"getItem\","
            + "\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\"}}]}}},"
            + "\"components\":{\"schemas\":{"
            + "\"createItem.request\":{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
            + "\"properties\":{\"name\":{\"type\":\"string\"},\"quantity\":{\"type\":\"integer\"}},"
            + "\"type\":\"object\"}}},"
            + "\"tags\":[{\"name\":\"catalog\"}],"
            + "\"x-vertique-validation\":{\"patternDialect\":\"java.util.regex\"}}";

    private static final String JSON_URL = "/apidocs/public/openapi.json";
    private static final String YAML_URL = "/apidocs/public/openapi.yaml";

    /** A well-formed strong entity tag equal to neither form's tag. */
    private static final String OTHER_TAG = "\"" + "0".repeat(64) + "\"";

    /** A second well-formed strong entity tag equal to neither form's tag nor {@link #OTHER_TAG}. */
    private static final String SECOND_OTHER_TAG = "\"" + "1".repeat(64) + "\"";

    /** A resource route of the {@code public} application, answered by the JAX-RS mount. */
    private static final String RESOURCE_URL = "/api/public/items";

    /** The meta the documentation mount must be customized with, exactly once. */
    private static final MountMeta DOCS_META = new MountMeta("apidocs", "/apidocs/*", null, Set.of());

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

    @Test
    @DisplayName(
            "The public document is served as exact JSON bytes and an equal YAML tree, each with a strong SHA-256 entity tag, 304 on matching, weak, '*', and listed conditionals, and a bodiless HEAD")
    void servesJsonAndYamlWithEntityTags() throws Exception {
        DocsTestComponents.SharedComponent component =
                DaggerDocsTestComponents_SharedComponent.factory().create(DocsConfigs.shared());

        Deployment deployment = deploy(component::httpVerticle);
        try {
            int port = deployment.port();
            byte[] expectedJson = EXPECTED_JSON.getBytes(StandardCharsets.UTF_8);

            // JSON form: GET twice.
            HttpResponse<Buffer> jsonFirst = send(HttpMethod.GET, port, JSON_URL, null);
            HttpResponse<Buffer> jsonSecond = send(HttpMethod.GET, port, JSON_URL, null);
            String jsonTag = jsonFirst.getHeader("ETag");
            assertNotNull(jsonTag, "the JSON form must carry an ETag");
            assertEquals(strongTag(expectedJson), jsonTag, "the JSON tag is the quoted SHA-256 of the exact bytes");
            assertArrayEquals(expectedJson, bodyBytes(jsonFirst), "the JSON form must be exactly the literal bytes");
            assertOk(jsonFirst, "application/json", expectedJson.length);
            assertArrayEquals(
                    bodyBytes(jsonFirst), bodyBytes(jsonSecond), "repeated JSON requests return identical bytes");
            assertEquals(jsonTag, jsonSecond.getHeader("ETag"), "repeated JSON requests return the same tag");
            assertOk(jsonSecond, "application/json", expectedJson.length);

            // YAML form: GET twice.
            HttpResponse<Buffer> yamlFirst = send(HttpMethod.GET, port, YAML_URL, null);
            HttpResponse<Buffer> yamlSecond = send(HttpMethod.GET, port, YAML_URL, null);
            byte[] yamlBytes = bodyBytes(yamlFirst);
            String yamlTag = yamlFirst.getHeader("ETag");
            assertNotNull(yamlTag, "the YAML form must carry an ETag");
            assertEquals(strongTag(yamlBytes), yamlTag, "the YAML tag is the quoted SHA-256 of the YAML bytes");
            assertNotEquals(jsonTag, yamlTag, "each form has its own tag");
            JsonNode yamlTree = new ObjectMapper(new YAMLFactory()).readTree(yamlBytes);
            JsonNode jsonTree = new ObjectMapper().readTree(EXPECTED_JSON);
            assertEquals(jsonTree, yamlTree, "the YAML form parses to the JSON form's tree");
            assertOk(yamlFirst, "application/yaml", yamlBytes.length);
            assertArrayEquals(yamlBytes, bodyBytes(yamlSecond), "repeated YAML requests return identical bytes");
            assertEquals(yamlTag, yamlSecond.getHeader("ETag"), "repeated YAML requests return the same tag");
            assertOk(yamlSecond, "application/yaml", yamlBytes.length);
            LOG.info(
                    "served JSON tag {}, YAML tag {}, YAML bytes:\n{}",
                    jsonTag,
                    yamlTag,
                    new String(yamlBytes, StandardCharsets.UTF_8));

            // HEAD for both forms, twice each.
            for (int round = 0; round < 2; round++) {
                assertHead(send(HttpMethod.HEAD, port, JSON_URL, null), jsonFirst);
                assertHead(send(HttpMethod.HEAD, port, YAML_URL, null), yamlFirst);
            }

            // Conditionals, GET and HEAD, both forms.
            assertConditionals(port, JSON_URL, jsonFirst, expectedJson);
            assertConditionals(port, YAML_URL, yamlFirst, yamlBytes);

            OpenApi31Toolchain.assertValid(new JsonObject(Buffer.buffer(bodyBytes(jsonFirst))));
        } finally {
            undeploy(deployment);
        }
    }

    @Test
    @DisplayName(
            "The documentation mount is customized once with its frozen meta, runs before an earliest application mount, and answers only the exact document URLs")
    void docsMountAnswersOnlyExactDocumentUrls() throws Exception {
        DocsTestComponents.EarlyMarkerComponent component =
                DaggerDocsTestComponents_EarlyMarkerComponent.factory().create(DocsConfigs.shared());

        Deployment deployment = deploy(component::httpVerticle);
        try {
            int port = deployment.port();

            List<MountMeta> applied = component.mountCustomizer().applied();
            LOG.info("customized mounts: {}", applied);
            assertEquals(
                    1,
                    applied.stream().filter(DOCS_META::equals).count(),
                    () -> "the customizer must be applied exactly once with " + DOCS_META + "; applied: " + applied);

            List<Request> answeredByDocs = List.of(
                    new Request(HttpMethod.GET, JSON_URL),
                    new Request(HttpMethod.HEAD, JSON_URL),
                    new Request(HttpMethod.GET, JSON_URL + "?x=1"));
            for (Request request : answeredByDocs) {
                HttpResponse<Buffer> response = send(request.method(), port, request.uri(), null);
                assertNull(
                        response.getHeader(MarkerRouterMount.HEADER),
                        () -> request + " must be answered by the documentation mount, not the marker");
                assertEquals(200, response.statusCode(), () -> request + " must answer 200");
                assertNotNull(response.getHeader("ETag"), () -> request + " must carry the document's ETag");
            }

            List<Request> fallsThrough = List.of(
                    new Request(HttpMethod.GET, "/apidocs/public/openapi.json/"),
                    new Request(HttpMethod.GET, "/apidocs/Public/openapi.json"),
                    new Request(HttpMethod.GET, "/apidocs/public/openapi.JSON"),
                    new Request(HttpMethod.GET, "/apidocs/public/openapi.yml"),
                    new Request(HttpMethod.GET, "/apidocs/public/openapi.json/x"),
                    new Request(HttpMethod.GET, "/apidocs/public/"),
                    new Request(HttpMethod.GET, "/apidocs/public"),
                    new Request(HttpMethod.GET, "/apidocs/"),
                    new Request(HttpMethod.GET, "/apidocs"),
                    new Request(HttpMethod.GET, "/apidocs/unknown/openapi.json"),
                    new Request(HttpMethod.GET, "/apidocs/mgmt/openapi.json"),
                    new Request(HttpMethod.GET, "/apidocs/ui/"),
                    new Request(HttpMethod.POST, JSON_URL),
                    new Request(HttpMethod.PUT, JSON_URL),
                    new Request(HttpMethod.DELETE, JSON_URL),
                    new Request(HttpMethod.PATCH, JSON_URL),
                    new Request(HttpMethod.OPTIONS, JSON_URL));
            for (Request request : fallsThrough) {
                HttpResponse<Buffer> response = send(request.method(), port, request.uri(), null);
                assertEquals(
                        MarkerRouterMount.EARLY,
                        response.getHeader(MarkerRouterMount.HEADER),
                        () -> request + " must fall through to the early marker mount");
            }
        } finally {
            undeploy(deployment);
        }
    }

    /**
     * The seven default-headers variants: the variant's name, how it fills
     * {@code jaxrs.defaultHeaders} ({@code null} when the section is absent), the {@code Cache-Control} every document response must carry, and
     * the {@code Cache-Control} a resource route carries under the same configuration ({@code null}
     * when the middleware sets none).
     *
     * @return the variants
     */
    static Stream<Arguments> cacheVariants() {
        return Stream.of(
                Arguments.of("cacheControl absent", null, "no-store", "no-store"),
                Arguments.of(
                        "cacheControl \"public, max-age=600\"",
                        (Consumer<JsonObject>) headers -> headers.put("cacheControl", "public, max-age=600"),
                        "no-cache",
                        "public, max-age=600"),
                Arguments.of(
                        "cacheControl \"no-cache\"",
                        (Consumer<JsonObject>) headers -> headers.put("cacheControl", "no-cache"),
                        "no-cache",
                        "no-cache"),
                Arguments.of(
                        "cacheControl \"private, NO-STORE\"",
                        (Consumer<JsonObject>) headers -> headers.put("cacheControl", "private, NO-STORE"),
                        "private, no-store",
                        "private, NO-STORE"),
                Arguments.of(
                        "cacheControl \"Private, max-age=60\"",
                        (Consumer<JsonObject>) headers -> headers.put("cacheControl", "Private, max-age=60"),
                        "private, no-cache",
                        "Private, max-age=60"),
                Arguments.of(
                        "cacheControl \"\" (suppressed)",
                        (Consumer<JsonObject>) headers -> headers.put("cacheControl", ""),
                        "no-cache",
                        null),
                Arguments.of(
                        "additionalHeaders cache-control \"s-maxage=60\"",
                        (Consumer<JsonObject>) headers ->
                                headers.put("additionalHeaders", new JsonObject().put("cache-control", "s-maxage=60")),
                        "no-cache",
                        "s-maxage=60"));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("cacheVariants")
    @DisplayName(
            "Document responses carry no-store or no-cache, private-prefixed when the effective default is private, never public")
    void publicCacheControlFollowsEffectiveDefault(
            String variant, Consumer<JsonObject> defaultHeaders, String expectedDocs, String expectedResource)
            throws Exception {
        JsonObject config = DocsConfigs.shared();
        if (defaultHeaders != null) {
            JsonObject headers = new JsonObject();
            defaultHeaders.accept(headers);
            config.getJsonObject("jaxrs").put("defaultHeaders", headers);
        }
        DocsTestComponents.SharedComponent component =
                DaggerDocsTestComponents_SharedComponent.factory().create(config);

        Deployment deployment = deploy(component::httpVerticle);
        try {
            int port = deployment.port();

            // Control: the configuration reached the default-headers middleware as intended.
            HttpResponse<Buffer> resource = send(HttpMethod.GET, port, RESOURCE_URL, null);
            assertNull(resource.getHeader(MarkerRouterMount.HEADER), "the resource route must be answered by JAX-RS");
            assertEquals(
                    expectedResource == null ? List.of() : List.of(expectedResource),
                    resource.headers().getAll("Cache-Control"),
                    () -> variant + ": the resource route carries the middleware's configured Cache-Control");

            HttpResponse<Buffer> get = send(HttpMethod.GET, port, JSON_URL, null);
            assertNull(get.getHeader(MarkerRouterMount.HEADER), "GET must be answered by the documentation mount");
            String tag = get.getHeader("ETag");
            assertNotNull(tag, "GET must carry the document's ETag");
            assertEquals(200, get.statusCode(), "GET must answer 200");

            HttpResponse<Buffer> head = send(HttpMethod.HEAD, port, JSON_URL, null);
            assertNull(head.getHeader(MarkerRouterMount.HEADER), "HEAD must be answered by the documentation mount");
            assertEquals(200, head.statusCode(), "HEAD must answer 200");

            HttpResponse<Buffer> conditional = send(HttpMethod.GET, port, JSON_URL, tag);
            assertNull(
                    conditional.getHeader(MarkerRouterMount.HEADER),
                    "the conditional GET must be answered by the documentation mount");
            assertEquals(304, conditional.statusCode(), "the matching conditional GET must answer 304");

            for (HttpResponse<Buffer> response : List.of(get, head, conditional)) {
                List<String> cacheControl = response.headers().getAll("Cache-Control");
                assertEquals(
                        List.of(expectedDocs),
                        cacheControl,
                        () -> variant + ": status " + response.statusCode() + " must carry exactly one Cache-Control");
                String lower = cacheControl.get(0).toLowerCase(Locale.ROOT);
                assertFalse(lower.contains("public"), () -> variant + ": a document is never public");
                assertFalse(lower.contains("s-maxage"), () -> variant + ": a document never carries s-maxage");
            }
        } finally {
            undeploy(deployment);
        }
    }

    // --- Assertion helpers ---

    private static void assertOk(HttpResponse<Buffer> response, String contentType, int length) {
        assertEquals(200, response.statusCode(), "a document form answers 200");
        assertEquals(contentType, response.getHeader("Content-Type"), "Content-Type is exactly " + contentType);
        assertEquals(String.valueOf(length), response.getHeader("Content-Length"), "Content-Length is the body length");
        assertNotNull(response.getHeader("Cache-Control"), "a document form carries Cache-Control");
    }

    private static void assertHead(HttpResponse<Buffer> head, HttpResponse<Buffer> get) {
        assertEquals(get.statusCode(), head.statusCode(), "HEAD answers GET's status");
        assertEquals(get.getHeader("ETag"), head.getHeader("ETag"), "HEAD carries GET's ETag");
        assertEquals(get.getHeader("Content-Type"), head.getHeader("Content-Type"), "HEAD carries GET's Content-Type");
        assertEquals(
                get.getHeader("Content-Length"), head.getHeader("Content-Length"), "HEAD carries GET's Content-Length");
        assertEquals(0, bodyBytes(head).length, "HEAD sends no body");
    }

    /**
     * Sends {@code GET} and {@code HEAD} with each {@code If-None-Match} row for one form: single tags,
     * and comma-separated lists whose members are trimmed and compared weakly.
     *
     * @param bytes the form's exact bytes, from which its tag is computed here
     */
    private static void assertConditionals(int port, String uri, HttpResponse<Buffer> get, byte[] bytes)
            throws Exception {
        String tag = strongTag(bytes);
        String cacheControl = get.getHeader("Cache-Control");
        List<String> notModifiedRows = List.of(
                tag,
                "W/" + tag,
                "*",
                OTHER_TAG + ", W/" + tag,
                OTHER_TAG + " , " + tag,
                OTHER_TAG + "," + tag,
                OTHER_TAG + ", *");
        List<String> modifiedRows = List.of(OTHER_TAG, OTHER_TAG + ", W/" + SECOND_OTHER_TAG);
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
            for (String ifNoneMatch : notModifiedRows) {
                HttpResponse<Buffer> notModified = send(method, port, uri, ifNoneMatch);
                String row = method + " " + uri + " If-None-Match: " + ifNoneMatch;
                assertEquals(304, notModified.statusCode(), () -> row + " must answer 304");
                assertEquals(tag, notModified.getHeader("ETag"), () -> row + " must carry the ETag");
                assertEquals(
                        cacheControl, notModified.getHeader("Cache-Control"), () -> row + " must carry Cache-Control");
                assertEquals(0, bodyBytes(notModified).length, () -> row + " must send no body");
            }
            for (String ifNoneMatch : modifiedRows) {
                HttpResponse<Buffer> modified = send(method, port, uri, ifNoneMatch);
                String row = method + " " + uri + " If-None-Match: " + ifNoneMatch;
                assertEquals(200, modified.statusCode(), () -> row + " must answer 200");
                assertEquals(tag, modified.getHeader("ETag"), () -> row + " must carry the ETag");
                if (method == HttpMethod.GET) {
                    assertArrayEquals(bytes, bodyBytes(modified), () -> row + " must send the document");
                } else {
                    assertEquals(0, bodyBytes(modified).length, () -> row + " must send no body");
                }
            }
        }
    }

    // --- Request and deployment helpers ---

    private record Request(HttpMethod method, String uri) {
        @Override
        public String toString() {
            return method + " " + uri;
        }
    }

    private record Deployment(String id, int port) {}

    private static HttpResponse<Buffer> send(HttpMethod method, int port, String uri, String ifNoneMatch)
            throws Exception {
        HttpRequest<Buffer> request = client.request(method, port, HOST, uri);
        if (ifNoneMatch != null) {
            request.putHeader("If-None-Match", ifNoneMatch);
        }
        return await(request.send());
    }

    private static byte[] bodyBytes(HttpResponse<Buffer> response) {
        Buffer body = response.body();
        return body == null ? new byte[0] : body.getBytes();
    }

    private static String strongTag(byte[] bytes) throws Exception {
        return "\""
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + "\"";
    }

    private static Deployment deploy(Supplier<Verticle> verticles) throws Exception {
        vertx.sharedData().getLocalMap("vertique").clear();
        String id = await(vertx.deployVerticle(verticles, new DeploymentOptions()));
        Integer port = (Integer) vertx.sharedData().getLocalMap("vertique").get("http.port");
        assertNotNull(port, "the deployment must publish its port");
        return new Deployment(id, port);
    }

    private static void undeploy(Deployment deployment) throws Exception {
        try {
            await(vertx.undeploy(deployment.id()));
        } finally {
            vertx.sharedData().getLocalMap("vertique").clear();
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }
}
