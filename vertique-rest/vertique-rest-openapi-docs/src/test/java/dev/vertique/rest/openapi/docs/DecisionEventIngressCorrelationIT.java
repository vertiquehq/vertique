// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.root.DecisionRecorder;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.root.RootApplicationModule;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments.Outcome;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves that an {@link AuthorizationDecisionEvent} emitted for a REST request carries the
 * correlation the root ingress middleware bound for that request, never the
 * {@link CorrelationContext#unbound()} sentinel.
 *
 * <p>The component is the real Dagger-wired REST stack with JWT authentication: the request
 * crosses the root correlation ingress middleware, the identity pipeline and the authorization
 * enforcer exactly as it does in an application. The request id the server echoes in
 * {@code X-Request-Id} is the ingress identifier, so equality with the event's request id shows
 * both came from one bound context.
 *
 * <p>Each test deploys its own component. A test reads the recorder with a bounded poll after its
 * response arrived.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class DecisionEventIngressCorrelationIT {

    private static final String HOST = "127.0.0.1";

    /** A protected document any authenticated caller may read, so its read emits one permit. */
    private static final String PROTECTED_URL = "/apidocs/internal/openapi.json";

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /** The reserved value of the unbound sentinel's identifiers. */
    private static final String UNBOUND_VALUE =
            CorrelationContext.unbound().requestId().value();

    private static final long REQUEST_SECONDS = 5;
    private static final long DECISION_MILLIS = 5_000;

    private Vertx vertx;
    private WebClient client;
    private Outcome deployment;
    private DecisionRecorder recorder;
    private int port;
    private String token;

    @BeforeEach
    void setUp(Vertx testVertx) throws Exception {
        vertx = testVertx;
        client = WebClient.create(vertx, new WebClientOptions().setDefaultHost(HOST));
        ProtectedRootTestComponents.AuthenticatedOnlyComponent component =
                DaggerProtectedRootTestComponents_AuthenticatedOnlyComponent.factory()
                        .create(vertx, DocsConfigs.loopback());
        recorder = component.decisionRecorder();
        deployment = StartupDeployments.deploy(vertx, component::httpVerticle, new DeploymentOptions());
        assertNull(deployment.failure(), () -> "the deployment failed: " + deployment.failure());
        assertNotNull(deployment.port(), "the deployment published no port");
        port = deployment.port();
        token = JwtAuthFactory.fromSymmetricKey(
                        vertx, RootApplicationModule.ALGORITHM, RootApplicationModule.SIGNING_KEY)
                .generateToken(new JsonObject().put("sub", "bob").put("roles", new JsonArray().add("user")));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
            client = null;
        }
        if (deployment != null) {
            StartupDeployments.undeploy(vertx, deployment);
            deployment = null;
        }
    }

    @Test
    @DisplayName("A request without correlation headers yields a decision event carrying the generated ingress ids")
    void requestWithoutHeadersCarriesGeneratedIngressIds() throws Exception {
        // When: an authenticated caller sends no correlation headers
        HttpResponse<Buffer> response = send(null, null);
        AuthorizationDecisionEvent event = awaitSingleDecision();

        // Then: the event's ids are the ones ingress generated and echoed, not the sentinel
        String echoed = response.getHeader(REQUEST_ID_HEADER);
        CorrelationContext correlation = event.correlation();
        assertAll(
                () -> assertEquals(200, response.statusCode(), "status"),
                () -> assertNotNull(echoed, "the response echoes no request id"),
                () -> assertDoesNotThrow(() -> UUID.fromString(echoed), "the default generator yields a UUID"),
                () -> assertEquals(echoed, correlation.requestId().value(), "event request id"),
                () -> assertEquals("generated", correlation.requestId().source(), "event request id source"),
                () -> assertEquals(echoed, correlation.correlationId().value(), "event correlation id"),
                () -> assertEquals(
                        "generated-from-request-id",
                        correlation.correlationId().source(),
                        "event correlation id source"),
                () -> assertNotEquals(UNBOUND_VALUE, correlation.requestId().value(), "sentinel request id"));
    }

    @Test
    @DisplayName("Supplied correlation headers reach the decision event unchanged")
    void suppliedHeadersReachTheDecisionEvent() throws Exception {
        // When: an authenticated caller supplies both identifiers
        HttpResponse<Buffer> response = send("req-supplied-1", "corr-supplied-1");
        AuthorizationDecisionEvent event = awaitSingleDecision();

        // Then: the event carries the supplied values with their header provenance
        CorrelationContext correlation = event.correlation();
        assertAll(
                () -> assertEquals(200, response.statusCode(), "status"),
                () -> assertEquals("req-supplied-1", response.getHeader(REQUEST_ID_HEADER), "echoed request id"),
                () -> assertEquals("req-supplied-1", correlation.requestId().value(), "event request id"),
                () -> assertEquals("http-header", correlation.requestId().source(), "event request id source"),
                () -> assertEquals(
                        "corr-supplied-1", correlation.correlationId().value(), "event correlation id"),
                () -> assertEquals("http-header", correlation.correlationId().source(), "event correlation id source"));
    }

    @Test
    @DisplayName("Consecutive requests without correlation headers get distinct ingress ids")
    void consecutiveRequestsGetDistinctIngressIds() throws Exception {
        // When: two header-less requests are served one after the other
        HttpResponse<Buffer> first = send(null, null);
        AuthorizationDecisionEvent firstEvent = awaitSingleDecision();
        recorder.clear();
        HttpResponse<Buffer> second = send(null, null);
        AuthorizationDecisionEvent secondEvent = awaitSingleDecision();

        // Then: each event carries its own request's id; nothing leaks from the first request
        String firstId = firstEvent.correlation().requestId().value();
        String secondId = secondEvent.correlation().requestId().value();
        assertAll(
                () -> assertEquals(first.getHeader(REQUEST_ID_HEADER), firstId, "first event request id"),
                () -> assertEquals(second.getHeader(REQUEST_ID_HEADER), secondId, "second event request id"),
                () -> assertNotEquals(firstId, secondId, "the second request reused the first request's id"));
    }

    /** Sends one authenticated read of the protected document, with the given correlation headers. */
    private HttpResponse<Buffer> send(String requestId, String correlationId) throws Exception {
        HttpRequest<Buffer> request =
                client.get(port, HOST, PROTECTED_URL).putHeader("Authorization", "Bearer " + token);
        if (requestId != null) {
            request.putHeader(REQUEST_ID_HEADER, requestId);
        }
        if (correlationId != null) {
            request.putHeader(CORRELATION_ID_HEADER, correlationId);
        }
        return Futures.await(request.send(), Duration.ofSeconds(REQUEST_SECONDS));
    }

    /** Waits for the recorder to hold a decision, then requires that it holds exactly one. */
    private AuthorizationDecisionEvent awaitSingleDecision() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DECISION_MILLIS);
        List<AuthorizationDecisionEvent> found = recorder.decisions();
        while (found.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
            found = recorder.decisions();
        }
        assertEquals(1, found.size(), "authorization decisions recorded: " + found);
        return found.get(0);
    }
}
