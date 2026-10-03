// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.startup.fallthrough.FallThroughCounters;
import dev.vertique.rest.openapi.docs.fixture.startup.fallthrough.FallThroughCounters.Counts;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/**
 * Checks, over a real loopback deployment, that the documentation mount answers only its exact
 * document URLs and lets every other path under its prefix fall through to later mounts, and that
 * document requests bypass the described mount's request interceptors and {@code API}-scoped
 * middleware while main-router ({@code ROOT}) middleware still runs.
 *
 * <p>The composition is the documented root application {@code api} at {@code /} holding
 * {@code CatalogResource}, with {@code apidocs.documents.api.info} {@code {title: "Catalog", version:
 * "1.0"}}; an application-owned UI mount at {@code /apidocs/ui/*} with the default phase and priority;
 * a counting request interceptor; and counting {@code API}- and {@code ROOT}-scoped middlewares. Each
 * named request is sent between two counter snapshots, and one expectation table per test states the
 * source that must answer each request and the counter changes it must cause. Every expected value is
 * a hand-written literal; entity tags are computed here from the received bytes.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DocsFallThroughIT {

    private static final String HOST = "127.0.0.1";

    /** The JSON document URL of the root application {@code api}. */
    private static final String DOCUMENT_URL = "/apidocs/api/openapi.json";

    /** A document URL of a name no application declares. */
    private static final String UNKNOWN_DOCUMENT_URL = "/apidocs/unknown/openapi.json";

    /** The UI mount's page. */
    private static final String UI_URL = "/apidocs/ui/";

    /** The URL of the {@code GET} route of {@code listItems}, the resource control. */
    private static final String RESOURCE_URL = "/items";

    /** The document name, which is the root application's name. */
    private static final String DOCUMENT_NAME = "api";

    private static final String DOCUMENT_TITLE = "Catalog";
    private static final String DOCUMENT_VERSION = "1.0";

    /** The content type of a JSON document response. */
    private static final String DOCUMENT_CONTENT_TYPE = "application/json";

    /** The UI page's content type. */
    private static final String UI_CONTENT_TYPE = "text/html; charset=utf-8";

    /** The UI page's content security policy. */
    private static final String UI_CSP =
            "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'";

    /** The UI page's marker body. */
    private static final String UI_PAGE = "<!doctype html><title>docs-ui-marker</title>";

    /** The fixed body of {@code listItems}. */
    private static final String RESOURCE_BODY = "items";

    /** The header the match-all customizer sets on every router it customizes. */
    private static final String CUSTOMIZED_HEADER = "X-Customized";

    /** The documentation mount's id, which the customizer's header must carry on a document response. */
    private static final String DOCS_MOUNT_ID = "apidocs";

    /** The logger of the per-document warning about uncovered mount-scoped controls. */
    private static final String WARNINGS_LOGGER = "dev.vertique.rest.openapi.docs.DocumentWarnings";

    /** The match-all customizer's simple name, which no warning may contain. */
    private static final String CUSTOMIZER_NAME = "EveryMountHeaderCustomizer";

    /** No interceptor or {@code API} middleware call; one {@code ROOT} middleware call. */
    private static final Counts MAIN_ROUTER_ONLY = new Counts(0, 0, 0, 1);

    /** One call of each counted interceptor method and middleware. */
    private static final Counts THROUGH_JAXRS_MOUNT = new Counts(1, 1, 1, 1);

    /** Which mount must answer a request, and how that is observed. */
    private enum Source {
        /** The UI mount: its page, content type, and policy. */
        UI_MOUNT,
        /** The documentation mount: {@code 200}, the JSON content type, and the document's entity tag. */
        DOCS_MOUNT,
        /** The root JAX-RS mount, which has no route for the URL: {@code 404} without an entity tag. */
        ROOT_JAXRS_MOUNT_NOT_FOUND,
        /** The root JAX-RS mount's {@code listItems} route: {@code 200} and its fixed body. */
        LISTITEMS_ROUTE
    }

    /**
     * One named request and its expectation.
     *
     * @param name             the row's name
     * @param method           the request method
     * @param uri              the request URI
     * @param source           the mount that must answer
     * @param delta            the counter changes the request must cause
     * @param customizedHeader the {@code X-Customized} value the response must carry, or
     *                         {@code null} when the row does not check it
     */
    private record Row(
            String name, HttpMethod method, String uri, Source source, Counts delta, String customizedHeader) {
        @Override
        public String toString() {
            return name + " (" + method + " " + uri + ")";
        }
    }

    private WebClient client;
    private FallThroughCounters counters;

    private Logger warningsLogger;
    private Level previousWarningsLevel;
    private ListAppender<ILoggingEvent> warningsAppender;

    @BeforeEach
    void createClientAndCounters(Vertx vertx) {
        client = WebClient.create(vertx);
        counters = new FallThroughCounters();
        counters.reset();
    }

    @BeforeEach
    void captureWarnings() {
        warningsLogger = (Logger) LoggerFactory.getLogger(WARNINGS_LOGGER);
        previousWarningsLevel = warningsLogger.getLevel();
        warningsLogger.setLevel(Level.INFO);
        warningsAppender = new ListAppender<>();
        warningsAppender.start();
        warningsLogger.addAppender(warningsAppender);
    }

    @AfterEach
    void closeClient(Vertx vertx) {
        client.close();
        vertx.sharedData().getLocalMap(StartupDeployments.LOCAL_MAP).clear();
    }

    @AfterEach
    void releaseWarnings() {
        warningsLogger.detachAppender(warningsAppender);
        warningsAppender.stop();
        warningsLogger.setLevel(previousWarningsLevel);
    }

    @Test
    @DisplayName(
            "Document requests are answered by the documentation mount without reaching the interceptor or API middleware, the UI and an unknown document name fall through to later mounts, and ROOT middleware sees every request")
    void documentRequestsBypassMountControlsAndOtherPathsFallThrough(Vertx vertx) throws Exception {
        // Given: the root application with CatalogResource, the api document, the UI mount, and the
        // counting interceptor and middlewares
        FallThroughTestComponents.FallThroughComponent component =
                DaggerFallThroughTestComponents_FallThroughComponent.factory().create(configuration(), counters);
        List<Row> table = List.of(
                new Row("UI page", HttpMethod.GET, UI_URL, Source.UI_MOUNT, MAIN_ROUTER_ONLY, null),
                new Row("document GET", HttpMethod.GET, DOCUMENT_URL, Source.DOCS_MOUNT, MAIN_ROUTER_ONLY, null),
                new Row("document HEAD", HttpMethod.HEAD, DOCUMENT_URL, Source.DOCS_MOUNT, MAIN_ROUTER_ONLY, null),
                new Row(
                        "unknown document name",
                        HttpMethod.GET,
                        UNKNOWN_DOCUMENT_URL,
                        Source.ROOT_JAXRS_MOUNT_NOT_FOUND,
                        THROUGH_JAXRS_MOUNT,
                        null),
                new Row(
                        "resource control",
                        HttpMethod.GET,
                        RESOURCE_URL,
                        Source.LISTITEMS_ROUTE,
                        THROUGH_JAXRS_MOUNT,
                        null));

        StartupDeployments.Outcome outcome = deploy(vertx, component::httpVerticle);
        try {
            // When / Then: each request, between two counter snapshots
            sendAll(outcome.port(), table);
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    @Test
    @DisplayName(
            "A match-all mount customizer applies to the documentation router, the document and the UI are still answered by their own mounts, an unknown document name still falls through, and no warning names the customizer")
    void matchAllMountCustomizerAppliesAndKeepsFallThrough(Vertx vertx) throws Exception {
        // Given: the fall-through composition plus EveryMountHeaderCustomizer
        FallThroughTestComponents.CustomizedFallThroughComponent component =
                DaggerFallThroughTestComponents_CustomizedFallThroughComponent.factory()
                        .create(configuration(), counters);
        List<Row> table = List.of(
                new Row(
                        "document GET",
                        HttpMethod.GET,
                        DOCUMENT_URL,
                        Source.DOCS_MOUNT,
                        MAIN_ROUTER_ONLY,
                        DOCS_MOUNT_ID),
                new Row(
                        "unknown document name",
                        HttpMethod.GET,
                        UNKNOWN_DOCUMENT_URL,
                        Source.ROOT_JAXRS_MOUNT_NOT_FOUND,
                        THROUGH_JAXRS_MOUNT,
                        null),
                new Row("UI page", HttpMethod.GET, UI_URL, Source.UI_MOUNT, MAIN_ROUTER_ONLY, null));

        StartupDeployments.Outcome outcome = deploy(vertx, component::httpVerticle);
        try {
            // When / Then: each request, between two counter snapshots
            sendAll(outcome.port(), table);

            // Then: no warning names the customizer
            List<String> namingCustomizer = warningsContaining(CUSTOMIZER_NAME);
            assertEquals(List.of(), namingCustomizer, "no warning may name the match-all customizer");
        } finally {
            StartupDeployments.undeploy(vertx, outcome);
        }
    }

    // --- Table execution ---

    private void sendAll(int port, List<Row> table) throws Exception {
        String documentTag = null;
        for (Row row : table) {
            Counts before = counters.snapshot();
            HttpResponse<Buffer> response = Futures.await(
                    client.request(row.method(), port, HOST, row.uri()).send(), Duration.ofSeconds(15));
            Counts after = counters.snapshot();

            assertEquals(row.delta(), after.minus(before), () -> row + ": counter changes");
            if (row.customizedHeader() != null) {
                assertEquals(
                        row.customizedHeader(),
                        response.getHeader(CUSTOMIZED_HEADER),
                        () -> row + ": " + CUSTOMIZED_HEADER + " header");
            }
            switch (row.source()) {
                case UI_MOUNT -> assertUiPage(row, response);
                case DOCS_MOUNT -> documentTag = assertDocument(row, response, documentTag);
                case ROOT_JAXRS_MOUNT_NOT_FOUND -> assertRootMountNotFound(row, response);
                case LISTITEMS_ROUTE -> assertListItems(row, response);
            }
        }
    }

    private static void assertUiPage(Row row, HttpResponse<Buffer> response) {
        assertEquals(200, response.statusCode(), () -> row + ": status");
        assertEquals(UI_CONTENT_TYPE, response.getHeader("Content-Type"), () -> row + ": Content-Type");
        assertEquals(UI_CSP, response.getHeader("Content-Security-Policy"), () -> row + ": Content-Security-Policy");
        assertEquals(UI_PAGE, bodyText(response), () -> row + ": the UI mount's marker body");
        assertNull(response.getHeader("ETag"), () -> row + ": no document ETag");
    }

    /**
     * Checks a document response. A {@code GET} must carry the quoted SHA-256 of its own bytes as its
     * {@code ETag} and be the {@code api} document; a {@code HEAD} must carry the tag of the preceding
     * {@code GET} and no body.
     *
     * @return the document's entity tag
     */
    private static String assertDocument(Row row, HttpResponse<Buffer> response, String getTag) throws Exception {
        assertEquals(200, response.statusCode(), () -> row + ": status");
        assertEquals(DOCUMENT_CONTENT_TYPE, response.getHeader("Content-Type"), () -> row + ": Content-Type");
        String tag = response.getHeader("ETag");
        assertNotNull(tag, () -> row + ": the document's ETag");
        byte[] body = bodyBytes(response);
        if (row.method() == HttpMethod.HEAD) {
            assertNotNull(getTag, () -> row + ": a document GET row must precede the HEAD row");
            assertEquals(getTag, tag, () -> row + ": HEAD carries the GET's ETag");
            assertEquals(0, body.length, () -> row + ": HEAD sends no body");
            return getTag;
        }
        assertEquals(strongTag(body), tag, () -> row + ": ETag is the quoted SHA-256 of the served bytes");
        JsonObject info = new JsonObject(Buffer.buffer(body)).getJsonObject("info");
        assertNotNull(info, () -> row + ": the document has an info object");
        assertEquals(
                DOCUMENT_TITLE, info.getString("title"), () -> row + ": the " + DOCUMENT_NAME + " document's title");
        assertEquals(
                DOCUMENT_VERSION,
                info.getString("version"),
                () -> row + ": the " + DOCUMENT_NAME + " document's version");
        return tag;
    }

    private static void assertRootMountNotFound(Row row, HttpResponse<Buffer> response) {
        assertEquals(404, response.statusCode(), () -> row + ": status");
        assertNull(response.getHeader("ETag"), () -> row + ": no document ETag");
    }

    private static void assertListItems(Row row, HttpResponse<Buffer> response) {
        assertEquals(200, response.statusCode(), () -> row + ": status");
        assertEquals(RESOURCE_BODY, bodyText(response), () -> row + ": listItems' body");
        assertNull(response.getHeader("ETag"), () -> row + ": no document ETag");
    }

    // --- Helpers ---

    /**
     * Returns the loopback configuration with {@code apidocs.documents.api.info}
     * {@code {title: "Catalog", version: "1.0"}}.
     */
    private static JsonObject configuration() {
        return DocsConfigs.withDocumentInfo(DocsConfigs.loopback(), DOCUMENT_NAME, DOCUMENT_TITLE, DOCUMENT_VERSION);
    }

    private static StartupDeployments.Outcome deploy(Vertx vertx, Supplier<Verticle> verticles) throws Exception {
        StartupDeployments.Outcome outcome = StartupDeployments.deploy(vertx, verticles);
        assertNull(outcome.failure(), () -> "the composition must deploy: " + outcome.failure());
        assertNotNull(outcome.port(), "the deployment must publish its port");
        assertTrue(outcome.deployed(), "the composition must deploy");
        return outcome;
    }

    private List<String> warningsContaining(String fragment) {
        synchronized (warningsAppender) {
            return warningsAppender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains(fragment))
                    .toList();
        }
    }

    private static byte[] bodyBytes(HttpResponse<Buffer> response) {
        Buffer body = response.body();
        return body == null ? new byte[0] : body.getBytes();
    }

    private static String bodyText(HttpResponse<Buffer> response) {
        return new String(bodyBytes(response), StandardCharsets.UTF_8);
    }

    private static String strongTag(byte[] bytes) throws Exception {
        return "\""
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + "\"";
    }
}
