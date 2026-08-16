// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.ValidationProblemDetail;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves the {@code openapi-contract} strategy sanitizes its validation error output (W6): when the
 * standalone {@code RequestValidator} rejects a request, the resulting {@link RestValidationException}
 * (and the {@link ValidationProblemDetail} the REST pipeline renders from it) must NOT echo the
 * submitted request value or any undeclared property name the client sent, and must NOT carry raw
 * vertx-json-schema / vertx-openapi internal text. Each per-field {@link ValidationErrorDetail} must
 * still carry the sanitized pointer/keyword context (mirroring the {@code web-validation} strategy's
 * {@code safeDetail} sanitization).
 *
 * <p>The gate runs after {@code BodyHandler} exactly as production places it; the failure handler
 * captures the raised {@link RestValidationException} so the test can build the full problem-detail
 * body and assert no submitted value leaks across the whole serialized response.
 *
 * <p>The request is issued through a {@link WebClient} rather than a raw {@code HttpClient}
 * deliberately: a raw {@code HttpClientResponse} discards body buffers that arrive before a body
 * handler is attached, so under load a body read can succeed with zero bytes while the status code is
 * correct (issue #167). This case asserts on the status code and on the server-side captured
 * exception rather than on the wire body, so the raw idiom was latent rather than actively broken
 * here — but a {@link WebClient} aggregates the response before completing the send, which removes
 * the trap for whoever next asserts on the rendered response body.
 *
 * <p>The request body and its {@code content-type} are the subject under test, so the request goes out
 * via {@code sendBuffer}, which — unlike {@code sendJson} — sets no {@code Content-Type} of its own and
 * writes exactly the bytes given: the contract gate sees precisely the leak-bait body the test
 * authored.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class OpenApiContractSanitizationIT {

    private static final String CONTRACT_PATH = "openapi-contract-sanitization-test.json";

    /** A submitted value that violates {@code maxLength:4} and must never appear in the response. */
    private static final String SECRET_PIN = "1234-SUPER-SECRET-LEAK-9999";

    /** An undeclared (additionalProperties:false) property name the client sent; must not leak either. */
    private static final String SECRET_PROPERTY = "internalSecretToken";

    private static Vertx vertx;
    private static HttpServer server;
    private static WebClient client;
    private static int port;

    private static final AtomicReference<RestValidationException> CAPTURED = new AtomicReference<>();

    @BeforeAll
    static void setUp(Vertx v, VertxTestContext ctx) {
        vertx = v;
        // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));

        OpenApiContractValidationStrategy strategy = new OpenApiContractValidationStrategy(
                vertx, JaxRsConfig.builder().openapiPath(CONTRACT_PATH).build());

        JaxRsOperationDescriptor createAccount = op("POST", "/accounts", "createAccount");
        Handler<RoutingContext> gate =
                strategy.gateFor(createAccount, OperationSchemas.empty()).orElseThrow();

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.post("/accounts")
                .handler(gate)
                .handler(rc -> rc.response().setStatusCode(201).end("created"));
        // Capture the RestValidationException so the test can render the full problem detail and assert
        // no submitted value leaks across the whole serialized response (mirrors the REST error pipeline,
        // which maps RestValidationException -> 400 problem+json with the structured field errors).
        router.route().failureHandler(rc -> {
            Throwable failure = rc.failure();
            if (failure instanceof RestValidationException rve) {
                CAPTURED.set(rve);
            }
            int status = failure instanceof RestValidationException ? 400 : 500;
            rc.response().setStatusCode(status).end();
        });

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, "127.0.0.1")
                .onSuccess(s -> {
                    server = s;
                    port = s.actualPort();
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    /**
     * Closes the server first and the {@link WebClient} afterwards, preserving the order the raw-client
     * teardown used.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns once
     * the underlying client has been asked to close, so there is no future to chain here and the server
     * close alone carries the completion.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        Future<Void> closeServer = server != null ? server.close() : Future.succeededFuture();
        closeServer.onComplete(ar -> {
            if (client != null) {
                client.close();
            }
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("Validation error output does not leak the submitted value or undeclared property, and carries"
            + " sanitized pointer/keyword fields")
    void sanitizedValidationErrorDoesNotLeakSubmittedValue(VertxTestContext ctx) throws Exception {
        CAPTURED.set(null);
        // A body violating maxLength:4 AND carrying an undeclared property under additionalProperties:false.
        String body = new io.vertx.core.json.JsonObject()
                .put("pin", SECRET_PIN)
                .put(SECRET_PROPERTY, "another-secret")
                .encode();

        post("/accounts", body)
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertTrue(status >= 400 && status < 500, "schema violation must be a 4xx; was " + status);

                    RestValidationException ex = CAPTURED.get();
                    assertNotNull(ex, "the gate must have raised a RestValidationException");

                    // Render the problem detail exactly as the REST error pipeline would.
                    ValidationProblemDetail pd = ValidationProblemDetail.of(ex.getMessage(), ex.errors());
                    String json;
                    try {
                        json = new ObjectMapper().writeValueAsString(pd);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }

                    // --- The submitted value and undeclared property name must NOT leak anywhere ---
                    assertFalse(
                            json.contains(SECRET_PIN), "submitted value must not appear in the response; got: " + json);
                    assertFalse(
                            json.contains(SECRET_PROPERTY),
                            "undeclared property name must not appear in the response; got: " + json);
                    // No raw validator-internal class names / package text.
                    assertFalse(
                            json.contains("io.vertx"),
                            "raw vertx-internal text must not appear in the response; got: " + json);
                    assertFalse(json.contains("Exception"), "raw exception text must not appear; got: " + json);

                    // --- Sanitized per-field context must still be present ---
                    assertFalse(ex.errors().isEmpty(), "there must be at least one structured field error");
                    ValidationErrorDetail first = ex.errors().get(0);
                    assertNotNull(first.path(), "each error must carry a (pointer) path");
                    assertNotNull(first.type(), "each error must carry the failed keyword as 'type'");
                    assertNotNull(first.location(), "each error must carry a location token");
                    // The detail message must not echo the submitted value.
                    boolean leaks = ex.errors().stream()
                            .anyMatch(e -> (e.detail() != null && e.detail().contains(SECRET_PIN))
                                    || (e.detail() != null && e.detail().contains(SECRET_PROPERTY)));
                    assertFalse(leaks, "no field detail may echo the submitted value or undeclared property name");

                    ctx.completeNow();
                })));
    }

    // --- helpers ---

    private Future<Integer> post(String path, String jsonBody) {
        return client.post(port, "127.0.0.1", path)
                .putHeader("content-type", "application/json")
                .sendBuffer(Buffer.buffer(jsonBody))
                .map(resp -> resp.statusCode());
    }

    private static JaxRsOperationDescriptor op(String method, String route, String operationId) {
        return StubDescriptors.builder()
                .operationId(operationId)
                .httpMethod(method)
                .routeTemplate(route)
                .build();
    }
}
