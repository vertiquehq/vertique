// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.failure.ExamplesApi;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.failure.ExamplesResource;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.failure.SearchApi;
import dev.vertique.rest.openapi.docs.fixture.metadata.it.failure.SearchResource;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupDeployments;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupDeployments.Outcome;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

/**
 * Deploys {@code HttpVerticle}s over documented applications whose operation metadata the document
 * cannot publish faithfully, and checks that an enabled document refuses the deployment before a
 * port is published, naming the mount, the operation, and the attribute and echoing none of the
 * attribute's values, while a composition without an enabled document deploys and binds the
 * operation unchanged.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class MetadataFailureIT {

    private static final String HOST = "127.0.0.1";

    /** The sentinel description of the {@code search} parameter. */
    private static final String DESCRIPTION_SENTINEL = SearchResource.DESCRIPTION;

    /** The sentinel example of the {@code search} parameter. */
    private static final String EXAMPLE_SENTINEL = SearchResource.EXAMPLE;

    /** The sentinel component name inside the {@code examples} reference. */
    private static final String REFERENCE_SENTINEL = "REFZX";

    /** The attribute the {@code search} parameter declares a location with. */
    private static final String LOCATION_ATTRIBUTE = "@Parameter.in";

    /** The attribute the {@code examples} example refers with. */
    private static final String REFERENCE_ATTRIBUTE = "@ExampleObject.ref";

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
            "A parameter location or an example reference fails the deployment of an enabled document naming mount, operation, and attribute; without an enabled document the operation deploys and answers")
    void invalidMetadataFailsStartupOnlyWhenPublished() throws Exception {
        // Given: (a) the application search whose query parameter declares a header location and
        // (b) the application examples whose parameter example is a reference, each with an enabled
        // document; and (c) the application search with its document switched off
        JsonObject searchEnabled = InputAssemblyIT.webValidationConfig(SearchApi.NAME);
        JsonObject examplesEnabled = InputAssemblyIT.webValidationConfig(ExamplesApi.NAME);
        JsonObject searchDisabled = DocsConfigs.withDocumentEnabled(
                InputAssemblyIT.webValidationConfig(SearchApi.NAME), SearchApi.NAME, false);
        String documentUrl = InputAssemblyIT.documentPath(SearchApi.NAME, InputAssemblyIT.JSON_FORM);
        assertEquals("/apidocs/search/openapi.json", documentUrl, "the document URL of the search application");

        // When: each composition is deployed
        Outcome location = deploy(() -> DaggerMetadataFailureTestComponents_SearchComponent.factory()
                .create(searchEnabled)
                .httpVerticle());
        undeploy(location);
        Outcome reference = deploy(() -> DaggerMetadataFailureTestComponents_ExamplesComponent.factory()
                .create(examplesEnabled)
                .httpVerticle());
        undeploy(reference);
        print("location", location);
        print("reference", reference);

        Outcome unpublished = deploy(() -> DaggerMetadataFailureTestComponents_SearchComponent.factory()
                .create(searchDisabled)
                .httpVerticle());
        Integer answered = null;
        Integer documentStatus = null;
        String echoed = null;
        try {
            if (unpublished.failure() == null && unpublished.port() != null) {
                int port = unpublished.port();
                HttpResponse<Buffer> search = await(client.get(
                                port, HOST, SearchApi.PATH + SearchResource.ROUTE + "?" + SearchResource.QUERY + "=x")
                        .send());
                answered = search.statusCode();
                echoed = search.bodyAsString();
                documentStatus =
                        await(client.get(port, HOST, documentUrl).send()).statusCode();
            }
        } finally {
            undeploy(unpublished);
        }

        Integer answeredStatus = answered;
        String echoedBody = echoed;
        Integer documentRouteStatus = documentStatus;
        // Then: (a) and (b) fail before a port is published, naming mount, operation, and attribute,
        // echoing none of the values
        List<Executable> checks = List.of(
                () -> assertRefused(
                        "(a) @Parameter.in", location, SearchApi.PATH, SearchResource.OPERATION_ID, LOCATION_ATTRIBUTE),
                () -> assertRefused(
                        "(b) @ExampleObject.ref",
                        reference,
                        ExamplesApi.PATH,
                        ExamplesResource.OPERATION_ID,
                        REFERENCE_ATTRIBUTE),
                // Then: (c) deploys, answers its success status, and no docs route answers the document URL
                () -> assertNull(unpublished.failure(), "(c) the composition deploys: " + unpublished.failure()),
                () -> assertNotNull(unpublished.port(), "(c) a port is published"),
                () -> assertEquals(200, answeredStatus, "(c) GET /search?q=x answers its success status"),
                () -> assertEquals("x", echoedBody, "(c) the operation binds the query unchanged"),
                () -> assertEquals(404, documentRouteStatus, "(c) no docs route answers " + documentUrl));
        assertAll("metadata failure compositions", checks);
    }

    private static void assertRefused(
            String label, Outcome outcome, String mount, String operationId, String attribute) {
        Throwable failure = outcome.failure();
        String message = failure == null ? "" : String.valueOf(failure.getMessage());
        assertAll(
                label + "; message: " + message,
                () -> assertNotNull(failure, "the deployment fails"),
                () -> assertNull(outcome.port(), "no port is published"),
                () -> assertTrue(message.contains(mount), "the message names the mount " + mount),
                () -> assertTrue(message.contains(operationId), "the message names the operation " + operationId),
                () -> assertTrue(message.contains(attribute), "the message names the attribute " + attribute),
                () -> assertFalse(message.contains(DESCRIPTION_SENTINEL), "the message echoes no description"),
                () -> assertFalse(message.contains(EXAMPLE_SENTINEL), "the message echoes no example"),
                () -> assertFalse(message.contains(REFERENCE_SENTINEL), "the message echoes no reference"),
                () -> assertFalse(message.contains("{"), "the message holds no '{'"));
    }

    private static void print(String label, Outcome outcome) {
        Throwable failure = outcome.failure();
        System.out.println("FAILURE " + label + ": " + (failure == null ? null : failure.getMessage()));
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

    /** Clears the {@code vertique} local map and deploys a verticle supplier. */
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
