// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.jaxrs.application.RestApplications;
import dev.vertique.rest.jaxrs.publication.CapturedSchemas;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationDetail;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.jaxrs.publication.ResponseShape;
import dev.vertique.rest.openapi.docs.fixture.contract.PartnerApi;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit proof that the publication sink refuses to load an application's own contract when one of the
 * mount's routed operations reaches it without its operation detail: without the detail the sink
 * cannot know the operation's hidden inputs, so the contract checks could not refuse a contract that
 * describes them.
 *
 * <p>The sink is the real {@link DocsPublicationSink}, built with a declared-application view in which
 * the documented application {@value PartnerApi#NAME} serves its own contract from a temporary file.
 * That file is a valid contract describing exactly the two routed operations {@code listOrders} and
 * {@code createOrder}, and carries the marker {@code zq7} in a description. The sink is handed a
 * hand-built publication on an event-loop context, as a mount's build hands it, and the test waits on
 * the future it returns. A control publishes the same mount with detail on every operation and must
 * store the document, so a refusal can only come from the missing detail.
 */
@DisplayName("Publication of a served contract")
class ServedContractPublicationTest {

    /** How the refusal names the application. */
    private static final String APPLICATION_NAMED = "application 'partner'";

    /** The statement of the refusal. */
    private static final String WITHOUT_DETAIL = "was published without its detail";

    /** The marker no message may contain. */
    private static final String MARKER = "zq7";

    /** The operation published with its detail in both tests. */
    private static final String LIST_ORDERS = "listOrders";

    /** The operation published without its detail in the refused publication. */
    private static final String CREATE_ORDER = "createOrder";

    /** The profile id of every operation detail. */
    private static final String PROFILE_ID = "default";

    /** The longest the test waits for the sink's call or its future. */
    private static final long TIMEOUT_SECONDS = 10;

    /** The application's own contract, valid for the two routed operations. */
    private static final String CONTRACT = """
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
                }
              }
            }
            """;

    @TempDir
    Path directory;

    private Vertx vertx;

    @BeforeEach
    void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void closeVertx() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("A routed operation published without its detail fails the load naming the application and the id")
    void operationWithoutDetailFailsTheLoad() throws Exception {
        // Given: partner serving its own valid contract, and a mount whose createOrder has no detail
        DocumentStore store = new DocumentStore();
        DocsPublicationSink sink = sink(store);
        MountPublication publication =
                publication(operation(LIST_ORDERS, "GET", detail()), operation(CREATE_ORDER, "POST", null));

        // When: the sink is handed the mount on an event-loop context
        Future<Void> result = mountBuiltOnContext(sink, publication);

        // Then: the publication fails naming the application, the operation, and the missing detail
        RestConfigurationException failure = assertInstanceOf(RestConfigurationException.class, failureOf(result));
        String message = String.valueOf(failure.getMessage());
        List<Executable> assertions = new ArrayList<>();
        assertions.add(() -> assertTrue(message.contains(APPLICATION_NAMED), "names " + APPLICATION_NAMED));
        assertions.add(() -> assertTrue(message.contains(CREATE_ORDER), "names " + CREATE_ORDER));
        assertions.add(() -> assertTrue(message.contains(WITHOUT_DETAIL), "says " + WITHOUT_DETAIL));
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String causeMessage = String.valueOf(cause.getMessage());
            assertions.add(() -> assertFalse(causeMessage.contains(MARKER), "echoes the marker: " + causeMessage));
        }

        // Then: no flight is left and nothing was stored
        assertions.add(() -> assertFalse(store.hasFlight(PartnerApi.NAME), "a flight is left in the store"));
        assertions.add(() -> assertTrue(store.lookup(PartnerApi.NAME).isEmpty(), "a document was stored"));
        assertAll("the refusal: " + message, assertions);
    }

    @Test
    @DisplayName("Control: the same mount with detail on every operation stores the served contract")
    void operationsWithDetailStoreTheDocument() throws Exception {
        // Given: partner serving its own valid contract, and a mount whose operations all have detail
        DocumentStore store = new DocumentStore();
        DocsPublicationSink sink = sink(store);
        MountPublication publication =
                publication(operation(LIST_ORDERS, "GET", detail()), operation(CREATE_ORDER, "POST", detail()));

        // When: the sink is handed the mount on an event-loop context
        Future<Void> result = mountBuiltOnContext(sink, publication);

        // Then: the publication succeeds and partner's document is stored
        awaitSuccess(result);
        assertTrue(store.lookup(PartnerApi.NAME).isPresent(), "no document was stored");
    }

    /** Builds the sink for partner, whose declared contract location is a temporary copy of {@link #CONTRACT}. */
    private DocsPublicationSink sink(DocumentStore store) throws Exception {
        Path contract = directory.resolve("partner-contract.json");
        Files.writeString(contract, CONTRACT, StandardCharsets.UTF_8);
        String location = contract.toAbsolutePath().toString();
        EnabledDocuments.EnabledDocument document = new EnabledDocuments.EnabledDocument(
                PartnerApi.NAME,
                PartnerApi.class,
                ApiDocs.Access.PUBLIC,
                PartnerApi.MOUNT_PATH,
                RestApplications.ContractOrigin.CONFIGURATION,
                null);
        RestApplications applications = new RestApplications(List.of(new RestApplications.Entry(
                PartnerApi.NAME,
                PartnerApi.class,
                true,
                PartnerApi.MOUNT_PATH,
                location,
                RestApplications.ContractOrigin.CONFIGURATION)));
        return new DocsPublicationSink(
                new EnabledDocuments(List.of(document)),
                store,
                ApidocsConfig.DEFAULT_PATH,
                Set.of(),
                applications,
                TestContexts.noSource());
    }

    /** The publication of partner's mount with the given operations. */
    private static MountPublication publication(OperationPublication... operations) {
        return new MountPublication(
                PartnerApi.MOUNT_PATH, "partner-mount", PartnerApi.NAME, PartnerApi.class, "none", List.of(operations));
    }

    /** An unrestricted operation on {@code /orders} with the given method and detail. */
    private static OperationPublication operation(String operationId, String httpMethod, OperationDetail detail) {
        return new OperationPublication(
                operationId,
                httpMethod,
                "/orders",
                "/orders",
                false,
                new SecurityPolicy.None(),
                List.of(),
                false,
                detail);
    }

    /** A detail with no inputs, no captured schema, and no descriptor, as a detached publication carries. */
    private static OperationDetail detail() {
        ResponseShape response =
                new ResponseShape(void.class, PartnerApi.class, false, true, List.of("application/json"), PROFILE_ID);
        return new OperationDetail(
                null, PROFILE_ID, new CapturedSchemas(null, null, Map.of()), true, List.of(), response);
    }

    /**
     * Calls {@code mountBuilt} on an event-loop context, as a mount's build does, and returns the future
     * it returned; a call that throws instead of returning a future fails the test.
     */
    private Future<Void> mountBuiltOnContext(DocsPublicationSink sink, MountPublication publication) throws Exception {
        Context caller = vertx.getOrCreateContext();
        CompletableFuture<Future<Void>> returned = new CompletableFuture<>();
        caller.runOnContext(ignored -> {
            try {
                returned.complete(sink.mountBuilt(publication));
            } catch (Throwable t) {
                returned.completeExceptionally(t);
            }
        });
        try {
            return returned.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException thrown) {
            throw new AssertionError("the sink threw instead of returning a future", thrown.getCause());
        }
    }

    /** Waits for {@code future} to fail and returns its failure, unwrapped. */
    private static Throwable failureOf(Future<Void> future) throws InterruptedException {
        try {
            future.toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            return failed.getCause();
        } catch (TimeoutException incomplete) {
            throw new AssertionError("the future did not complete", incomplete);
        }
        throw new AssertionError("the publication succeeded instead of failing");
    }

    /** Waits for {@code future} to succeed. */
    private static void awaitSuccess(Future<Void> future) throws InterruptedException {
        try {
            future.toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            throw new AssertionError("the publication failed instead of succeeding", failed.getCause());
        } catch (TimeoutException incomplete) {
            throw new AssertionError("the future did not complete", incomplete);
        }
    }
}
