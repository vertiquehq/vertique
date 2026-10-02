// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.caching.CachingModules;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments.Outcome;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Reads every API document of five deployments with {@code GET} and with a matching {@code
 * If-None-Match}, as a caller allowed to read it, and checks the exact {@code Cache-Control} and
 * {@code Vary} of each {@code 200} and {@code 304} response against a hand-written row table.
 *
 * <p>The graphs, in the order they are deployed:
 *
 * <ul>
 *   <li>(a) a public document {@code public} beside a protected document {@code management}, which
 *       the JWT bearer scheme guards with the role {@code admin}; {@code
 *       jaxrs.defaultHeaders.cacheControl} is {@code public, max-age=3600};
 *   <li>(b) the same applications with the framework's default {@code Cache-Control}
 *       ({@code no-store}), the {@code jaxrs.defaultHeaders} section absent;
 *   <li>(c) one application per scheme kind, each with a protected document and no roles: a scheme
 *       whose handler describes nothing, an API key in the {@code X-Api-Key} header, in the {@code
 *       session} cookie, and in the {@code api_key} query parameter, OAuth 2, OpenID Connect, and
 *       mutual TLS; {@code jaxrs.defaultHeaders.cacheControl} is the permissive {@code public,
 *       max-age=3600}, so the protected value cannot come from the default;
 *   <li>(d) the applications of (a) with {@code private, max-age=600};
 *   <li>(e) the applications of (a) with {@code private, no-store}.
 * </ul>
 *
 * <p>A protected document always answers {@code private, no-store}, and varies on the request header
 * its scheme's credential travels in: {@code Authorization} for an HTTP, OAuth 2, OpenID Connect, or
 * undescribed scheme, the key's header for a header API key, {@code Cookie} for a cookie API key,
 * and no {@code Vary} at all for a query API key or mutual TLS. A public document answers {@code
 * no-store} when the effective default has {@code no-store} and {@code no-cache} otherwise, preceded
 * by {@code private, } when the default is private, and carries no {@code Vary}. No document response
 * is ever {@code public} or carries {@code s-maxage}.
 *
 * <p>A sixth graph (f), deployed by its own test, holds the applications of (a) with the framework's
 * default {@code Cache-Control} and CORS enabled for two origins. A cross-origin read of the
 * protected document, and its revalidation, must vary on both {@code Origin}, which the CORS handler
 * adds, and {@code Authorization}, which the document adds: the document's {@code Vary} joins the one
 * already present rather than replacing it.
 *
 * <p>Each of the graphs (a) to (e) is one invocation of a parameterized test, named after the behavior
 * it isolates, so each passes or fails on its own. Both forms of every document are read. Each graph
 * gets its own client, which is closed before the graph is undeployed. The class allows 60 seconds
 * rather than 20 per test because a graph holds a JWT provider and up to seven applications, and each
 * deployment and undeployment is separately bounded at ten seconds.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class DocumentCachingIT {

    private static final String HOST = "127.0.0.1";

    /** The bound of one request. */
    private static final long REQUEST_SECONDS = 10;

    /** The {@code info.version} configured for every document. */
    private static final String VERSION = "1.0";

    /** The {@code info.title} configured for each document, by document name. */
    private static final Map<String, String> TITLES = Map.of(
            "public", "Caching public",
            "management", "Caching management",
            "undescribed", "Caching undescribed",
            "header-key", "Caching header key",
            "cookie-key", "Caching cookie key",
            "query-key", "Caching query key",
            "oauth", "Caching OAuth",
            "openid", "Caching OpenID",
            "mutual-tls", "Caching mutual TLS");

    /**
     * One expected document response: the graph, the document's name, the exact {@code
     * Cache-Control}, and the exact {@code Vary}, or {@code null} when the response must carry no
     * {@code Vary} header at all. Both the {@code 200} and the {@code 304} of both forms must match.
     */
    private record Row(String graph, String document, String cacheControl, String vary) {}

    /** The expected headers, every value written by hand. */
    private static final List<Row> ROWS = List.of(
            new Row("a", "public", "no-cache", null),
            new Row("a", "management", "private, no-store", "Authorization"),
            new Row("b", "public", "no-store", null),
            new Row("b", "management", "private, no-store", "Authorization"),
            new Row("c", "undescribed", "private, no-store", "Authorization"),
            new Row("c", "header-key", "private, no-store", "X-Api-Key"),
            new Row("c", "cookie-key", "private, no-store", "Cookie"),
            new Row("c", "query-key", "private, no-store", null),
            new Row("c", "oauth", "private, no-store", "Authorization"),
            new Row("c", "openid", "private, no-store", "Authorization"),
            new Row("c", "mutual-tls", "private, no-store", null),
            new Row("d", "public", "private, no-cache", null),
            new Row("d", "management", "private, no-store", "Authorization"),
            new Row("e", "public", "private, no-store", null),
            new Row("e", "management", "private, no-store", "Authorization"));

    /**
     * One graph: its label, its {@code jaxrs.defaultHeaders.cacheControl} ({@code null} when the
     * section is absent), and whether it holds the scheme-kind applications instead of {@code public}
     * and {@code management}.
     */
    private record Graph(String label, String defaultCacheControl, boolean schemeKinds) {}

    /**
     * The graphs of the row table, each named after the behavior it isolates, in deployment order.
     * Each is deployed and reported as its own test invocation.
     *
     * @return one named argument per graph
     */
    static Stream<Arguments> graphs() {
        return Stream.of(
                graph("permissive public default", new Graph("a", "public, max-age=3600", false)),
                graph("framework default no-store", new Graph("b", null, false)),
                graph("scheme kinds under permissive default", new Graph("c", "public, max-age=3600", true)),
                graph("private max-age default", new Graph("d", "private, max-age=600", false)),
                graph("private no-store default", new Graph("e", "private, no-store", false)));
    }

    private static Arguments graph(String name, Graph graph) {
        return Arguments.of(Named.of("(" + graph.label() + ") " + name, graph));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("graphs")
    @DisplayName(
            "Document Cache-Control and Vary are exact for every default and scheme kind, never public, on 200 and 304")
    void cachingHeadersNeverWeakerThanDefault(Graph graph, Vertx vertx) throws Exception {
        // Given: the caller credentials each document accepts; public documents need none
        JWTAuth tokens = JwtAuthFactory.fromSymmetricKey(vertx, "HS256", CachingModules.SIGNING_KEY);
        String adminToken =
                tokens.generateToken(new JsonObject().put("sub", "alice").put("roles", new JsonArray().add("admin")));
        Map<String, Consumer<HttpRequest<Buffer>>> credentials = Map.of(
                "public", request -> {},
                "management", request -> request.putHeader("Authorization", "Bearer " + adminToken),
                "undescribed", request -> request.putHeader("Authorization", "Bearer undescribed-credential"),
                "header-key", request -> request.putHeader("X-Api-Key", "header-key-credential"),
                "cookie-key", request -> request.putHeader("Cookie", "session=cookie-key-credential"),
                "query-key", request -> request.addQueryParam("api_key", "query-key-credential"),
                "oauth", request -> request.putHeader("Authorization", "Bearer oauth-credential"),
                "openid", request -> request.putHeader("Authorization", "Bearer openid-credential"),
                "mutual-tls",
                        request -> request.putHeader("X-Fixture-Client-Certificate", "fixture-client-certificate"));

        // Given: the graph deployed
        JsonObject config = config(graph);
        vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).clear();
        Outcome outcome = StartupDeployments.deploy(
                vertx,
                () -> graph.schemeKinds()
                        ? DaggerDocumentCachingTestComponents_SchemeKindsComponent.factory()
                                .create(vertx, config)
                                .httpVerticle()
                        : DaggerDocumentCachingTestComponents_PublicAndManagementComponent.factory()
                                .create(vertx, config)
                                .httpVerticle());
        WebClient client = WebClient.create(vertx);
        try {
            assertNull(outcome.failure(), () -> "(" + graph.label() + ") the deployment failed: " + outcome.failure());
            assertNotNull(outcome.port(), () -> "(" + graph.label() + ") the deployment published no port");
            int port = outcome.port();

            List<Row> rows = ROWS.stream()
                    .filter(row -> row.graph().equals(graph.label()))
                    .toList();
            assertFalse(rows.isEmpty(), () -> "(" + graph.label() + ") the row table has no row for this graph");
            for (Row row : rows) {
                Consumer<HttpRequest<Buffer>> credential = credentials.get(row.document());
                // When / Then: each form read with GET, then with its entity tag
                readBothResponses(client, port, row, "openapi.json", credential, DocumentCachingIT::jsonTitle);
                readBothResponses(client, port, row, "openapi.yaml", credential, DocumentCachingIT::yamlTitle);
            }
        } finally {
            client.close();
            StartupDeployments.undeploy(vertx, outcome);
            vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).clear();
        }
    }

    /** The origin the cross-origin requests come from, one of the two configured origins. */
    private static final String CORS_ORIGIN = "https://app.example";

    /**
     * The second configured origin. With a single configured origin the CORS handler writes no
     * {@code Vary} at all, so two are configured.
     */
    private static final String OTHER_CORS_ORIGIN = "https://admin.example";

    @Test
    @DisplayName("A protected document adds its Vary on Authorization beside the CORS Vary on Origin")
    void protectedDocumentVaryKeepsCorsOrigin(Vertx vertx) throws Exception {
        // Given: the public and management applications with CORS enabled for two origins, and alice's token
        JWTAuth tokens = JwtAuthFactory.fromSymmetricKey(vertx, "HS256", CachingModules.SIGNING_KEY);
        String adminToken =
                tokens.generateToken(new JsonObject().put("sub", "alice").put("roles", new JsonArray().add("admin")));
        JsonObject config = config(new Graph("f", null, false));
        config.put(
                "cors",
                new JsonObject()
                        .put("enabled", true)
                        .put("origins", new JsonArray().add(CORS_ORIGIN).add(OTHER_CORS_ORIGIN)));
        vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).clear();
        Outcome outcome = StartupDeployments.deploy(
                vertx, () -> DaggerDocumentCachingTestComponents_PublicAndManagementComponent.factory()
                        .create(vertx, config)
                        .httpVerticle());
        WebClient client = WebClient.create(vertx);
        try {
            assertNull(outcome.failure(), () -> "(f) the deployment failed: " + outcome.failure());
            assertNotNull(outcome.port(), "(f) the deployment published no port");
            int port = outcome.port();

            for (String form : List.of("openapi.json", "openapi.yaml")) {
                String uri = "/apidocs/management/" + form;
                String label = "(f) management " + form;

                // When: alice reads the form from the origin, then revalidates it with its entity tag
                HttpResponse<Buffer> ok = Futures.await(
                        client.get(port, HOST, uri)
                                .putHeader("Authorization", "Bearer " + adminToken)
                                .putHeader("Origin", CORS_ORIGIN)
                                .send(),
                        Duration.ofSeconds(REQUEST_SECONDS));
                assertEquals(200, ok.statusCode(), () -> label + ": GET must answer 200, body: " + ok.bodyAsString());
                String entityTag = ok.getHeader("ETag");
                assertNotNull(entityTag, () -> label + ": the 200 must carry an entity tag");
                HttpResponse<Buffer> notModified = Futures.await(
                        client.get(port, HOST, uri)
                                .putHeader("Authorization", "Bearer " + adminToken)
                                .putHeader("Origin", CORS_ORIGIN)
                                .putHeader("If-None-Match", entityTag)
                                .send(),
                        Duration.ofSeconds(REQUEST_SECONDS));
                assertEquals(
                        304,
                        notModified.statusCode(),
                        () -> label + ": a matching If-None-Match must answer 304, body: "
                                + notModified.bodyAsString());

                // Then: both vary on exactly Origin and Authorization, stay private, no-store, and passed CORS
                for (HttpResponse<Buffer> response : List.of(ok, notModified)) {
                    String which = label + " " + response.statusCode();
                    assertEquals(
                            List.of("private, no-store"),
                            response.headers().getAll("Cache-Control"),
                            () -> which + ": exactly one Cache-Control");
                    assertEquals(
                            CORS_ORIGIN,
                            response.getHeader("Access-Control-Allow-Origin"),
                            () -> which + ": the CORS handler answered the origin");
                    assertEquals(
                            List.of("authorization", "origin"),
                            varyTokens(response),
                            () -> which + ": Vary names Origin and Authorization, each once, was "
                                    + response.headers().getAll("Vary"));
                }
            }
        } finally {
            client.close();
            StartupDeployments.undeploy(vertx, outcome);
            vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).clear();
        }
    }

    /**
     * The field names of every {@code Vary} header of a response, split on commas, trimmed, lower-cased
     * (field names are case-insensitive, and the CORS handler writes {@code origin}), and sorted.
     */
    private static List<String> varyTokens(HttpResponse<Buffer> response) {
        return response.headers().getAll("Vary").stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(token -> token.toLowerCase(Locale.ROOT))
                .sorted()
                .toList();
    }

    /**
     * Reads one form of a row's document with {@code GET}, expecting {@code 200} with the document,
     * then with {@code If-None-Match} set to the received entity tag, expecting {@code 304} without a
     * body; both responses must carry the row's headers.
     */
    private static void readBothResponses(
            WebClient client,
            int port,
            Row row,
            String form,
            Consumer<HttpRequest<Buffer>> credential,
            Function<byte[], String> titleOf)
            throws Exception {
        String uri = "/apidocs/" + row.document() + "/" + form;
        String label = "(" + row.graph() + ") " + row.document() + " " + form;

        HttpRequest<Buffer> get = client.get(port, HOST, uri);
        credential.accept(get);
        HttpResponse<Buffer> ok = Futures.await(get.send(), Duration.ofSeconds(REQUEST_SECONDS));
        assertEquals(200, ok.statusCode(), () -> label + ": GET must answer 200, body: " + ok.bodyAsString());
        byte[] body = ok.body() == null ? new byte[0] : ok.body().getBytes();
        assertTrue(body.length > 0, () -> label + ": the 200 must carry the document");
        assertEquals(
                TITLES.get(row.document()),
                titleOf.apply(body),
                () -> label + ": the 200 must carry this document's info.title");
        String entityTag = ok.getHeader("ETag");
        assertNotNull(entityTag, () -> label + ": the 200 must carry an entity tag");
        assertCachingHeaders(label + " 200", row, ok);

        HttpRequest<Buffer> conditional = client.get(port, HOST, uri).putHeader("If-None-Match", entityTag);
        credential.accept(conditional);
        HttpResponse<Buffer> notModified = Futures.await(conditional.send(), Duration.ofSeconds(REQUEST_SECONDS));
        assertEquals(
                304,
                notModified.statusCode(),
                () -> label + ": a matching If-None-Match must answer 304, body: " + notModified.bodyAsString());
        assertTrue(
                notModified.body() == null || notModified.body().length() == 0,
                () -> label + ": the 304 must carry no body");
        assertCachingHeaders(label + " 304", row, notModified);
    }

    /**
     * Asserts the exact {@code Cache-Control} and {@code Vary} of a row, and that no {@code
     * Cache-Control} value is public or carries {@code s-maxage}.
     */
    private static void assertCachingHeaders(String label, Row row, HttpResponse<Buffer> response) {
        List<String> cacheControl = response.headers().getAll("Cache-Control");
        assertEquals(List.of(row.cacheControl()), cacheControl, () -> label + ": exactly one Cache-Control");
        for (String value : cacheControl) {
            String lower = value.toLowerCase(Locale.ROOT);
            assertFalse(lower.contains("public"), () -> label + ": Cache-Control must never be public: " + value);
            assertFalse(
                    lower.contains("s-maxage"), () -> label + ": Cache-Control must never carry s-maxage: " + value);
        }
        List<String> vary = response.headers().getAll("Vary");
        if (row.vary() == null) {
            assertEquals(List.of(), vary, () -> label + ": no Vary header at all");
        } else {
            assertEquals(List.of(row.vary()), vary, () -> label + ": exactly one Vary");
        }
    }

    /**
     * The configuration of a graph: loopback, the {@code none} strategy, the JWT scheme {@code
     * bearerAuth}, every document's {@code info}, and {@code jaxrs.defaultHeaders.cacheControl} when
     * the graph sets one. The {@code management} document is also explicitly enabled with a server
     * URL, the non-disabling configuration a document accepts.
     */
    private static JsonObject config(Graph graph) {
        JsonObject config = DocsConfigs.loopback();
        config.put("jwt", new JsonObject().put("schemeName", "bearerAuth"));
        List<String> documents = graph.schemeKinds()
                ? List.of("undescribed", "header-key", "cookie-key", "query-key", "oauth", "openid", "mutual-tls")
                : List.of("public", "management");
        for (String document : documents) {
            DocsConfigs.withDocumentInfo(config, document, TITLES.get(document), VERSION);
        }
        if (!graph.schemeKinds()) {
            DocsConfigs.document(config, "management")
                    .put("enabled", true)
                    .put("serverUrl", "https://docs.example/api/mgmt");
        }
        if (graph.defaultCacheControl() != null) {
            config.getJsonObject("jaxrs")
                    .put("defaultHeaders", new JsonObject().put("cacheControl", graph.defaultCacheControl()));
        }
        return config;
    }

    private static String jsonTitle(byte[] body) {
        return title(new ObjectMapper(), body);
    }

    private static String yamlTitle(byte[] body) {
        return title(new ObjectMapper(new YAMLFactory()), body);
    }

    private static String title(ObjectMapper mapper, byte[] body) {
        try {
            JsonNode tree = mapper.readTree(body);
            return tree.path("info").path("title").asText(null);
        } catch (IOException unreadable) {
            throw new AssertionError("the document could not be parsed", unreadable);
        }
    }
}
