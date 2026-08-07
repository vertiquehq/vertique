// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Exercises the {@code openapi-contract} validation gate end-to-end against a real Vert.x HTTP server,
 * with the gate installed after {@code BodyHandler} exactly as the production router places it
 * (body-read-once). It asserts the contract-driven behavior (FR-020):
 *
 * <ul>
 *   <li>a request missing a required body field fails the routing context with a
 *       {@link RestValidationException} (mapped by the REST error pipeline to a 400);</li>
 *   <li>a conforming request reaches dispatch via {@code ctx.next()};</li>
 *   <li>a body that violates the contract schema (here, an undeclared/additional property under a
 *       {@code additionalProperties: false} schema) is rejected — the strategy inherits the standalone
 *       {@code RequestValidator}'s strictness (pre-change-behavior reproduction);</li>
 *   <li>a request whose operationId is absent from the loaded contract fails with a <em>server</em>
 *       error (5xx) — the JAX-RS/contract mismatch is a deployment misconfiguration, NOT a client
 *       error, and must not be misreported as 400.</li>
 * </ul>
 *
 * <p>The failure surfaces as a 400 in this test because the test server's failure handler renders any
 * {@link RestValidationException} as a 400; the production REST error pipeline does the same. Non-
 * {@link RestValidationException} failures are rendered as 500 by the same handler, mirroring the
 * production pipeline's behaviour for server/config errors.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class OpenApiContractStrategyIT {

    private static final String CONTRACT_PATH = "openapi-contract-strategy-test.json";

    private static Vertx vertx;
    private static HttpServer server;
    private static HttpClient client;
    private static int port;

    @BeforeAll
    static void setUp(Vertx v, VertxTestContext ctx) {
        vertx = v;
        client = vertx.createHttpClient();

        OpenApiContractValidationStrategy strategy = new OpenApiContractValidationStrategy(
                vertx, JaxRsConfig.builder().openapiPath(CONTRACT_PATH).build());

        JaxRsOperationDescriptor createWidget = op("POST", "/widgets", "createWidget");
        Handler<RoutingContext> gate =
                strategy.gateFor(createWidget, OperationSchemas.empty()).orElseThrow();

        // Gate for an operationId that does NOT exist in the loaded contract. Used by the
        // missing-operation → server-error test.
        JaxRsOperationDescriptor missingOp = op("POST", "/missing", "operationNotInContract");
        Handler<RoutingContext> missingGate =
                strategy.gateFor(missingOp, OperationSchemas.empty()).orElseThrow();

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.post("/widgets")
                .handler(gate)
                .handler(rc -> rc.response().setStatusCode(201).end("created"));
        // Route whose operationId is absent from the loaded contract — expected to produce a 5xx.
        router.post("/missing")
                .handler(missingGate)
                .handler(rc -> rc.response().setStatusCode(200).end("should-not-reach"));
        // A minimal failure handler that renders any RestValidationException as a 400, mirroring the
        // production REST error pipeline (which maps RestValidationException -> 400 problem+json).
        // Any other throwable is rendered as a 500, mirroring the pipeline's treatment of server/config
        // errors (which do NOT wrap in RestValidationException).
        router.route().failureHandler(rc -> {
            Throwable failure = rc.failure();
            int status = failure instanceof RestValidationException ? 400 : 500;
            rc.response().setStatusCode(status).end(failure == null ? "" : String.valueOf(failure.getMessage()));
        });

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(0)
                .onSuccess(s -> {
                    server = s;
                    port = s.actualPort();
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        Future<Void> closeServer = server != null ? server.close() : Future.succeededFuture();
        closeServer
                .eventually(() -> client != null ? client.close() : Future.succeededFuture())
                .onComplete(ar -> ctx.completeNow());
    }

    @Test
    @DisplayName("A request missing the required body field is rejected with a 400 (validates via RequestValidator)")
    void openApiContractStrategyValidatesViaRequestValidator(VertxTestContext ctx) {
        post("/widgets", new io.vertx.core.json.JsonObject().put("notName", "x").encode())
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertEquals(400, status, "missing required 'name' must be rejected as 400");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("A conforming request reaches dispatch and returns 201")
    void openApiContractStrategyPassesConformingRequest(VertxTestContext ctx) {
        post(
                        "/widgets",
                        new io.vertx.core.json.JsonObject().put("name", "gizmo").encode())
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertEquals(201, status, "a conforming body must reach dispatch");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("A schema-violating body (undeclared property under additionalProperties:false) is rejected (400)")
    void openApiContractStrategyReproducesPreChangeBehavior(VertxTestContext ctx) {
        // additionalProperties:false in the contract makes an undeclared property a strict violation —
        // the standalone RequestValidator rejects it, reproducing the pre-change router strictness.
        post(
                        "/widgets",
                        new io.vertx.core.json.JsonObject()
                                .put("name", "gizmo")
                                .put("undeclared", true)
                                .encode())
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertTrue(status >= 400 && status < 500, "strict schema violation must be a 4xx; was " + status);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName(
            "A request whose operationId is absent from the loaded contract produces a 5xx server error, not a 400")
    void openApiContractStrategyMapsMissingOperationToServerError(VertxTestContext ctx) {
        // The gate for "operationNotInContract" calls contract.operation(operationId) which returns null
        // because that id is not in the test openapi.json. The gate should fail the routing context with
        // a server error (the JAX-RS code and the contract disagree — a deployment misconfiguration),
        // NOT wrap the resulting IllegalStateException in RestValidationException and produce a 400.
        post(
                        "/missing",
                        new io.vertx.core.json.JsonObject().put("name", "gizmo").encode())
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertTrue(
                            status >= 500,
                            "a missing operationId in the contract is a server/config error; expected 5xx but got "
                                    + status);
                    ctx.completeNow();
                })));
    }

    // --- helpers ---

    private Future<Integer> post(String path, String jsonBody) {
        return client.request(HttpMethod.POST, port, "localhost", path)
                .compose(req -> {
                    req.putHeader("content-type", "application/json");
                    return req.send(Buffer.buffer(jsonBody));
                })
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
