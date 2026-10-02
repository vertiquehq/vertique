// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.events.HttpRequestCompletedEvent;
import dev.vertique.rest.core.events.RestRequestCompletedEvent;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.jaxrs.completion.CompletionComponent;
import dev.vertique.rest.jaxrs.completion.CompletionRecords;
import dev.vertique.rest.jaxrs.completion.DaggerCompletionComponent;
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
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration proof that a synthetic operation route records its request's completion as a REST
 * operation completion carrying its own descriptor, on every outcome, including the 401 the
 * scheme's authentication handler answers before any contributor runs.
 *
 * <p>The fixture is one {@code HttpVerticle} built from {@link CompletionComponent} (package
 * {@code dev.vertique.rest.jaxrs.completion}, outside this package), so the production ROOT
 * middlewares, the completion emitter among them, run for every request. Its {@code SYSTEM_FIRST}
 * mount at {@code /apidocs/*} installs one synthetic operation, {@code apidocs:management:json} of
 * application {@code management}, requiring role {@code admin} under the stub scheme {@code stub},
 * at {@code /management/openapi.json}, answering {@code GET} and {@code HEAD}; the request URL is
 * therefore {@code /apidocs/management/openapi.json}. A recording contributor keeps the descriptor it receives for the
 * operation; recording listeners keep every {@link RestRequestCompletedEvent} and
 * {@link HttpRequestCompletedEvent}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class SyntheticOperationCompletionIT {

    private static final String DOCUMENT_URL = "/apidocs/management/openapi.json";
    private static final String OPERATION_ID = "apidocs:management:json";
    private static final String APPLICATION_NAME = "management";
    private static final String DOCUMENT_BODY = "management-document-bytes";
    private static final String UNAUTHORIZED_PROBLEM =
            "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401}";
    private static final String FORBIDDEN_PROBLEM = "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403}";

    private static final String USER_HEADER = "X-Test-User";
    private static final String ROLES_HEADER = "X-Test-Roles";

    private static final long COMPLETION_WAIT_SECONDS = 10;
    private static final long TEARDOWN_TIMEOUT_SECONDS = 15;

    private Vertx vertx;
    private WebClient client;
    private String deploymentId;
    private int port;
    private CompletionRecords records;

    /**
     * Builds the component and deploys its verticle on {@code 127.0.0.1}, port 0, then reads the
     * published port and creates the client.
     *
     * @param injectedVertx the Vert.x instance injected by vertx-junit5
     */
    @BeforeEach
    void setUp(Vertx injectedVertx) {
        vertx = injectedVertx;
        JsonObject config = new JsonObject()
                .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                .put("jaxrs", new JsonObject().put("validationStrategy", "none"));
        CompletionComponent component = DaggerCompletionComponent.factory().create(config);
        records = component.completionRecords();

        Supplier<Verticle> verticleSupplier = component::httpVerticle;
        deploymentId = await(vertx.deployVerticle(verticleSupplier, new DeploymentOptions()));
        port = (Integer) vertx.sharedData().getLocalMap("vertique").get("http.port");
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
    }

    /**
     * Closes the client, then undeploys the verticle, which closes the server, attempting each even if
     * the other failed, and fails the teardown when either failed or the bounded undeploy did not
     * complete in time. The injected {@link Vertx} is owned and closed by the extension.
     *
     * @throws Exception the first cleanup failure, carrying later ones as suppressed
     */
    @AfterEach
    void tearDown() throws Exception {
        CleanupFailures cleanup = new CleanupFailures();
        cleanup.attempt(() -> {
            if (client != null) {
                client.close();
            }
        });
        if (deploymentId != null) {
            cleanup.await(() -> vertx.undeploy(deploymentId), TEARDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        cleanup.rethrowIfAny();
    }

    @Test
    @DisplayName("A synthetic route records each request's completion with its own descriptor, 401 included")
    void recorderRunsFirstWithTheSyntheticDescriptor() throws InterruptedException {
        RestOperationDescriptor registered = records.registered(OPERATION_ID);
        assertNotNull(registered, "the recording contributor must have received the synthetic operation's descriptor");

        Observation anonymous = observe("no credential", null, null);
        Observation nonAdmin = observe("non-admin credential", "user", "");
        Observation admin = observe("admin credential", "admin", "admin");

        assertAll(
                "one REST completion per request, carrying the synthetic descriptor",
                () -> assertRow(anonymous, 401, UNAUTHORIZED_PROBLEM, registered),
                () -> assertRow(nonAdmin, 403, FORBIDDEN_PROBLEM, registered),
                () -> assertRow(admin, 200, DOCUMENT_BODY, registered));
    }

    private static void assertBody(int status, String expectedBody, String actualBody, String label) {
        if (status == 200) {
            assertEquals(expectedBody, actualBody, label + "response body");
            return;
        }
        JsonObject actual;
        try {
            actual = new JsonObject(actualBody);
        } catch (RuntimeException e) {
            throw new AssertionError(label + "expected a JSON problem body but got: " + actualBody, e);
        }
        assertEquals(new JsonObject(expectedBody), actual, label + "problem body");
    }

    private void assertRow(
            Observation row, int expectedStatus, String expectedBody, RestOperationDescriptor registered) {
        String label = row.label() + ": ";
        RestRequestCompletedEvent event =
                row.restEvents().isEmpty() ? null : row.restEvents().get(0);
        assertAll(
                row.label(),
                () -> assertEquals(expectedStatus, row.responseStatus(), label + "response status"),
                () -> assertBody(expectedStatus, expectedBody, row.responseBody(), label),
                () -> assertTrue(row.completed(), label + "a completion event must arrive"),
                () -> assertEquals(1, row.restEvents().size(), label + "REST completion events " + row.restEvents()),
                () -> assertEquals(0, row.httpEvents().size(), label + "HTTP completion events " + row.httpEvents()),
                () -> assertSame(
                        registered,
                        event == null ? null : event.operation(),
                        label + "operation identity: the event must carry the instance the contributor received"),
                () -> assertEquals(
                        OPERATION_ID, event == null ? null : event.operation().operationId(), label + "operationId"),
                () -> assertEquals(
                        APPLICATION_NAME,
                        event == null ? null : event.operation().applicationName(),
                        label + "applicationName"),
                () -> assertEquals(expectedStatus, event == null ? -1 : event.statusCode(), label + "event status"));
    }

    private Observation observe(String label, String user, String roles) throws InterruptedException {
        records.resetEvents();
        HttpRequest<Buffer> request = client.request(HttpMethod.GET, port, "127.0.0.1", DOCUMENT_URL);
        if (user != null) {
            request.putHeader(USER_HEADER, user);
        }
        if (roles != null) {
            request.putHeader(ROLES_HEADER, roles);
        }
        HttpResponse<Buffer> response = await(request.send());
        boolean completed = records.awaitCompletion(COMPLETION_WAIT_SECONDS, TimeUnit.SECONDS);
        return new Observation(
                label,
                response.statusCode(),
                response.bodyAsString(),
                completed,
                records.restEvents(),
                records.httpEvents());
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new AssertionError("asynchronous step failed: " + e.getMessage(), e);
        }
    }

    /** What one request produced: its response status and the completion events it emitted. */
    private record Observation(
            String label,
            int responseStatus,
            String responseBody,
            boolean completed,
            List<RestRequestCompletedEvent> restEvents,
            List<HttpRequestCompletedEvent> httpEvents) {}
}
