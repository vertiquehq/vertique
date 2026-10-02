// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsBeanParamRegistry;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsDescriptorRegistry;
import dev.vertique.rest.openapi.docs.CompleteDocumentTestComponents.GenComponent;
import dev.vertique.rest.openapi.docs.CompleteDocumentTestComponents.RefComponent;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.conformance.complete.CompleteEntries;
import dev.vertique.rest.openapi.docs.fixture.conformance.complete.GenEntriesApi;
import dev.vertique.rest.openapi.docs.fixture.conformance.complete.GeneratedEntryResource;
import dev.vertique.rest.openapi.docs.fixture.conformance.complete.GeneratedFilter;
import dev.vertique.rest.openapi.docs.fixture.conformance.complete.GeneratedPaging;
import dev.vertique.rest.openapi.docs.fixture.conformance.complete.RefEntriesApi;
import dev.vertique.rest.openapi.docs.fixture.conformance.complete.ReflectedEntryResource;
import dev.vertique.rest.openapi.docs.fixture.conformance.complete.ReflectedFilter;
import dev.vertique.rest.openapi.docs.fixture.conformance.complete.ReflectedPaging;
import dev.vertique.rest.openapi.docs.fixture.support.Deployments;
import dev.vertique.rest.openapi.docs.fixture.support.DocumentRequests;
import dev.vertique.rest.openapi.docs.fixture.support.DocumentRequests.Answer;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Serves the public documents of two declared applications listing twin complete-feature resources
 * over real, loopback-bound {@code HttpVerticle} deployments, one described by the reflective scanner
 * and one by a hand-written companion in the generated descriptor shape, and checks that the two
 * descriptor paths publish the same complete document.
 *
 * <p>The twins carry identical annotations and operation ids: path, query, header, and cookie
 * parameters, a {@code @BeanParam} bean and a {@code @RequestParams} record, a redacted JSON request
 * body, {@code @Operation} and {@code @Tag} documentation, inferred and declared responses with a
 * header and a named example, two response producers, scoped and scopeless requirements of two
 * described schemes, and one hidden operation. Each application runs in a composition of its own,
 * since one composition refuses two resources sharing an operation id across its mounts. The
 * deployments run one after the other, each with one instance; every expected value is a fixed
 * literal.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class CompleteDocumentParityIT {

    /** The longest one deployment, request, or undeployment is awaited. */
    private static final Duration WAIT = Duration.ofSeconds(5);

    /** The path keys both documents publish; the hidden operation's path is not among them. */
    private static final Set<String> PUBLISHED_PATHS =
            Set.of(CompleteEntries.RESOURCE_PATH, CompleteEntries.ENTRY_ROUTE, CompleteEntries.EXPORTS_ROUTE);

    private Vertx vertx;

    private WebClient client;

    private final List<String> deploymentIds = new ArrayList<>();

    @BeforeEach
    void startClient(Vertx sharedVertx) {
        vertx = sharedVertx;
        client = WebClient.create(vertx);
    }

    @AfterEach
    void closeClientThenUndeploy() throws Exception {
        try {
            client.close();
        } finally {
            Deployments.undeployAll(vertx, deploymentIds, WAIT);
            deploymentIds.clear();
        }
    }

    @Test
    @DisplayName(
            "The generated and the reflective descriptor paths publish the same complete document, apart from servers, and the hidden operation still answers on both mounts")
    void generatedAndReflectionDocumentsAgree() throws Exception {
        // Given: the twins, the generated-shape one with its descriptor and composite companions and the
        // reflective one with none, each listed by its own declaration in a composition of its own,
        // under web-validation with the canonical schema source and no configured info
        assertAll(
                "each twin is described by the path it stands for",
                () -> assertTrue(
                        GeneratedJaxRsDescriptorRegistry.shared()
                                .lookup(GeneratedEntryResource.class)
                                .isPresent(),
                        "the generated-shape twin must have a descriptor companion"),
                () -> assertTrue(
                        GeneratedJaxRsDescriptorRegistry.shared()
                                .lookup(ReflectedEntryResource.class)
                                .isEmpty(),
                        "the reflective twin must have no descriptor companion"),
                () -> assertTrue(
                        GeneratedJaxRsBeanParamRegistry.shared()
                                .lookup(GeneratedPaging.class)
                                .isPresent(),
                        "the generated-shape bean must have a model companion"),
                () -> assertTrue(
                        GeneratedJaxRsBeanParamRegistry.shared()
                                .lookup(GeneratedFilter.class)
                                .isPresent(),
                        "the generated-shape record must have a model companion"),
                () -> assertTrue(
                        GeneratedJaxRsBeanParamRegistry.shared()
                                .lookup(ReflectedPaging.class)
                                .isEmpty(),
                        "the reflective bean must have no model companion"),
                () -> assertTrue(
                        GeneratedJaxRsBeanParamRegistry.shared()
                                .lookup(ReflectedFilter.class)
                                .isEmpty(),
                        "the reflective record must have no model companion"));
        RefComponent ref =
                DaggerCompleteDocumentTestComponents_RefComponent.factory().create(vertx, webValidationConfig());
        GenComponent gen =
                DaggerCompleteDocumentTestComponents_GenComponent.factory().create(vertx, webValidationConfig());

        // When: each composition is deployed in turn, its document fetched, and its hidden route requested
        Served refServed = serve(ref::httpVerticle, RefEntriesApi.NAME, RefEntriesApi.PATH);
        Served genServed = serve(gen::httpVerticle, GenEntriesApi.NAME, GenEntriesApi.PATH);

        // Then: the documents are equal once servers are removed, hold no $id, validate, and are not
        // empty of what they must describe, and the hidden route answered on both mounts
        JsonObject refDocument = refServed.document();
        JsonObject genDocument = genServed.document();
        JsonObject expected = normalize(refDocument);
        JsonObject actual = normalize(genDocument);
        assertEquals(
                expected,
                actual,
                () -> "the generated-shape document must equal the reflective one once servers are removed;"
                        + " differing lines (reflective | generated):\n"
                        + lineDifferences(expected.encodePrettily(), actual.encodePrettily()));
        assertAll(
                "both documents and both hidden routes",
                () -> assertEquals(List.of(), idMembers(refDocument), "the reflective document holds no $id"),
                () -> assertEquals(List.of(), idMembers(genDocument), "the generated-shape document holds no $id"),
                () -> OpenApi31Toolchain.assertValid(refDocument),
                () -> OpenApi31Toolchain.assertValid(genDocument),
                () -> assertEquals(PUBLISHED_PATHS, pathKeys(refDocument), "the reflective document's path keys"),
                () -> assertEquals(PUBLISHED_PATHS, pathKeys(genDocument), "the generated-shape document's path keys"),
                () -> assertEquals(200, refServed.hidden().status(), "the hidden route answers 200 under /ref"),
                () -> assertEquals(
                        CompleteEntries.LIVE_BODY,
                        new String(refServed.hidden().body(), StandardCharsets.UTF_8),
                        "the hidden route's body under /ref"),
                () -> assertEquals(200, genServed.hidden().status(), "the hidden route answers 200 under /gen"),
                () -> assertEquals(
                        CompleteEntries.LIVE_BODY,
                        new String(genServed.hidden().body(), StandardCharsets.UTF_8),
                        "the hidden route's body under /gen"));
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** One deployment's fetched document and the answer of its hidden route. */
    private record Served(JsonObject document, Answer hidden) {}

    /**
     * Returns a copy of a document without its root {@code servers} member, the only member allowed to
     * differ between the two documents (it names each application's mount). Nothing else is removed or
     * changed.
     *
     * @param document a fetched document
     * @return the copy without {@code servers}
     */
    private static JsonObject normalize(JsonObject document) {
        JsonObject copy = document.copy();
        copy.remove("servers");
        return copy;
    }

    /**
     * Deploys one instance of a component's verticle, fetches its application's JSON document and the
     * hidden operation's route under its mount, then undeploys it.
     */
    private Served serve(Supplier<Verticle> verticles, String documentName, String mountPath) throws Exception {
        int port = Deployments.deployAndReadPort(vertx, verticles, new DeploymentOptions(), deploymentIds, WAIT);
        String documentPath = DocsConfigs.DEFAULT_APIDOCS_PATH + "/" + documentName + "/openapi.json";
        Answer document = DocumentRequests.get(client, port, documentPath, null, WAIT);
        assertEquals(200, document.status(), () -> "GET " + documentPath + " must answer 200");
        Answer hidden = DocumentRequests.get(client, port, mountPath + CompleteEntries.LIVE_ROUTE, null, WAIT);
        String deploymentId = deploymentIds.remove(deploymentIds.size() - 1);
        Futures.await(vertx.undeploy(deploymentId), WAIT);
        return new Served(new JsonObject(Buffer.buffer(document.body())), hidden);
    }

    /** The loopback configuration with the {@code web-validation} strategy and no document entries. */
    private static JsonObject webValidationConfig() {
        JsonObject config = DocsConfigs.loopback();
        config.getJsonObject("jaxrs").put("validationStrategy", "web-validation");
        return config;
    }

    /** The document's path keys. */
    private static Set<String> pathKeys(JsonObject document) {
        JsonObject paths = document.getJsonObject("paths");
        assertNotNull(paths, "the document publishes paths");
        assertFalse(paths.isEmpty(), "the document publishes at least one path");
        return new TreeSet<>(paths.fieldNames());
    }

    /** Returns the JSON Pointer of every {@code $id} member anywhere in a tree, in walk order. */
    private static List<String> idMembers(JsonObject document) {
        List<String> found = new ArrayList<>();
        collectIdMembers(document, "", found);
        return found;
    }

    private static void collectIdMembers(Object node, String pointer, List<String> found) {
        if (node instanceof JsonObject object) {
            for (String name : object.fieldNames()) {
                String child = pointer + "/" + name.replace("~", "~0").replace("/", "~1");
                if ("$id".equals(name)) {
                    found.add(child);
                }
                collectIdMembers(object.getValue(name), child, found);
            }
        } else if (node instanceof JsonArray array) {
            for (int index = 0; index < array.size(); index++) {
                collectIdMembers(array.getValue(index), pointer + "/" + index, found);
            }
        }
    }

    /**
     * Lists the lines at which two pretty-printed documents differ, each as {@code <line>: <left> |
     * <right>}, at most forty of them.
     */
    private static String lineDifferences(String left, String right) {
        String[] leftLines = left.split("\n", -1);
        String[] rightLines = right.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (int line = 0; line < Math.max(leftLines.length, rightLines.length) && shown < 40; line++) {
            String l = line < leftLines.length ? leftLines[line] : "<none>";
            String r = line < rightLines.length ? rightLines[line] : "<none>";
            if (!l.equals(r)) {
                out.append(line + 1)
                        .append(": ")
                        .append(l.strip())
                        .append(" | ")
                        .append(r.strip())
                        .append('\n');
                shown++;
            }
        }
        return out.toString();
    }
}
