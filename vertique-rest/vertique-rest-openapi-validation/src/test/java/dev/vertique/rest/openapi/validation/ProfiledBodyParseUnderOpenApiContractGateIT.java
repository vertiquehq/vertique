// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.core.exception.ValidationException;
import dev.vertique.json.VertxJsonSupport;
import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.request.BoundRequest;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves the {@code openapi-contract} validation gate honors the JSON-profile request-body FIRST PARSE
 * contract (review finding W-A, FR-JSON-024/024A): when a non-{@code vertx} JSON profile mapper is
 * stashed on the {@link RoutingContext} under {@link BoundRequest#KEY_RESOLVED_BODY_MAPPER} (as the
 * production {@code JaxRsRouteRegistrar} does for ALL strategies ahead of the gate), the
 * {@code openapi-contract} gate must trigger the profile mapper's strict first parse <em>before</em>
 * running OpenAPI schema validation, so a body the strict profile rejects fails with a {@code 400}
 * rather than being accepted by Vert.x's lenient parse and validated only against the contract schema.
 *
 * <p><strong>The defect this guards against.</strong> The {@code web-validation} strategy already
 * honors this contract: {@code WebValidationStrategy.validateBody} constructs a
 * {@code DefaultBoundRequest(ctx, op)} whose constructor first-parses the raw body through the resolved
 * profile mapper (strict features apply), throwing a {@link ValidationException} (HTTP 400) on a strict
 * rejection. But {@code OpenApiContractValidationStrategy} extracted the raw body via
 * {@code RequestUtils.extract(...)} and ran Vert.x's parse for OpenAPI schema validation — it NEVER
 * triggered the profile first parse. Under that strategy the profile mapper was therefore not the first
 * parse, and the parse boundary was not uniform across strategies.
 *
 * <p><strong>given / when / then.</strong> <em>given</em> an {@code openapi-contract} gate whose
 * routing context carries a strict profile mapper (with {@code STRICT_DUPLICATE_DETECTION}) under
 * {@link BoundRequest#KEY_RESOLVED_BODY_MAPPER}, and a body {@code {"name":"a","name":"b"}} that the
 * strict profile mapper REJECTS (duplicate key) but Vert.x — and the OpenAPI schema, which sees the
 * last-wins {@code name:"b"} — would ACCEPT; <em>when</em> the body is POSTed under the
 * {@code openapi-contract} strategy; <em>then</em> the response is {@code 400} (the profile
 * {@link ValidationException}, proving the profile parse ran at/before the gate), and the response body
 * carries the static, value-free profile rejection message — never the duplicate-key request value.
 *
 * <p>The gate is installed after {@code BodyHandler} exactly as the production router places it
 * (body-read-once). The {@link BoundRequest#KEY_RESOLVED_BODY_MAPPER} stash handler is installed ahead
 * of the gate, mirroring {@code JaxRsRouteRegistrar}, which installs that stash unconditionally for
 * every validation strategy.
 *
 * <p>Requests are issued through a {@link WebClient} rather than a raw {@code HttpClient}
 * deliberately: a raw {@code HttpClientResponse} discards body buffers that arrive before a body
 * handler is attached, so under load {@code body()} can succeed with zero bytes while the status code
 * is correct (issue #167). The rejection test asserts on the rendered rejection message and on the
 * absence of the request values from it, so a silently emptied body would let the value-free
 * assertion pass for the wrong reason while the message assertion failed. A {@link WebClient}
 * aggregates the body into its {@code HttpResponse} before completing the send, so the race is closed
 * by construction rather than by every author remembering an idiom.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ProfiledBodyParseUnderOpenApiContractGateIT {

    private static final String CONTRACT_PATH = "openapi-contract-strategy-test.json";

    /** The static, value-free message {@code ProfileBodyMaterialization.rejection} carries to clients. */
    private static final String PROFILE_REJECTION_MESSAGE = "Request body rejected by JSON profile";

    private static Vertx vertx;
    private static HttpServer server;
    private static WebClient client;
    private static int port;

    /**
     * Builds the strict request-body profile mapper: a Jackson mapper with {@code STRICT_DUPLICATE_DETECTION}
     * (rejects a duplicate JSON key) and {@code FAIL_ON_TRAILING_TOKENS} (rejects trailing garbage), plus
     * Vert.x JSON support so {@code JsonObject}/{@code JsonArray} round-trip through the profile mapper's
     * first parse (the binder reads {@code JsonObject.class}/{@code JsonArray.class} via this mapper).
     *
     * @return the strict profile {@link ObjectMapper}
     */
    private static ObjectMapper strictProfileMapper() {
        return JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .addModule(VertxJsonSupport.module())
                .build();
    }

    @BeforeAll
    static void setUp(Vertx v, VertxTestContext ctx) {
        vertx = v;
        // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));

        OpenApiContractValidationStrategy strategy = new OpenApiContractValidationStrategy(
                vertx, JaxRsConfig.builder().openapiPath(CONTRACT_PATH).build());

        JaxRsOperationDescriptor createWidget = op("POST", "/widgets", "createWidget");
        Handler<RoutingContext> gate =
                strategy.gateFor(createWidget, OperationSchemas.empty()).orElseThrow();

        ObjectMapper profileMapper = strictProfileMapper();

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.post("/widgets")
                // Mirror JaxRsRouteRegistrar: stash the resolved non-vertx profile mapper on the context
                // BEFORE the validation gate, unconditionally for every strategy. The openapi-contract gate
                // must honor it by first-parsing the body through the profile mapper.
                .handler(rc -> {
                    rc.put(BoundRequest.KEY_RESOLVED_BODY_MAPPER, profileMapper);
                    rc.next();
                })
                .handler(gate)
                .handler(rc -> rc.response().setStatusCode(201).end("created"));
        // Mirror the production REST error pipeline: a RestValidationException (OpenAPI schema failure)
        // and the core ValidationException (profile-mapper first-parse rejection) both render as 400; any
        // other throwable renders as 500. The body echoes only getMessage() (value-free for the profile
        // rejection), mirroring the production mapper, which never serializes the rejection cause.
        router.route().failureHandler(rc -> {
            Throwable failure = rc.failure();
            int status =
                    (failure instanceof RestValidationException || failure instanceof ValidationException) ? 400 : 500;
            rc.response().setStatusCode(status).end(failure == null ? "" : String.valueOf(failure.getMessage()));
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
     * Closes the shared {@link WebClient} and then the shared server, before the extension-owned
     * {@link Vertx} instance is closed.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns once
     * the underlying client has been asked to close, so there is nothing to chain the server close off
     * and the server close alone carries the completion.
     *
     * @param ctx the test context used for async teardown assertion
     */
    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        Future<Void> closeServer = server != null ? server.close() : Future.succeededFuture();
        closeServer.onComplete(ar -> ctx.completeNow());
    }

    @Test
    @DisplayName("A duplicate-key body rejected by the strict profile is rejected 400 before OpenAPI validation")
    void rejectsStrictProfileViolationBeforeOpenApiValidation(VertxTestContext ctx) {
        // {"name":"a","name":"b"} is VALID per the OpenAPI schema (last-wins name:"b" satisfies required +
        // minLength + additionalProperties:false), so without the profile first parse the openapi-contract
        // RequestValidator accepts it and dispatch returns 201. With the fix, the strict profile mapper's
        // FIRST PARSE rejects the duplicate key -> ValidationException -> 400, before OpenAPI validation.
        client.post(port, "127.0.0.1", "/widgets")
                .putHeader("content-type", "application/json")
                .sendBuffer(Buffer.buffer("{\"name\":\"a\",\"name\":\"b\"}"))
                .map(response -> response.statusCode() + "|" + response.bodyAsString())
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    String[] parts = result.split("\\|", 2);
                    int status = Integer.parseInt(parts[0]);
                    String body = parts.length > 1 ? parts[1] : "";
                    assertEquals(
                            400,
                            status,
                            "a duplicate-key body the strict profile rejects must be 400 from the profile first parse, "
                                    + "not accepted by Vert.x and validated only against the contract schema");
                    assertEquals(
                            PROFILE_REJECTION_MESSAGE,
                            body,
                            "the response must carry the static, value-free profile rejection message");
                    assertFalse(
                            body.contains("\"a\"") || body.contains("\"b\""),
                            "the response must not echo the duplicate-key request values");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("A conforming body under the profiled openapi-contract gate still reaches dispatch (201)")
    void conformingBodyUnderProfiledGateReachesDispatch(VertxTestContext ctx) {
        // A single-key conforming body passes the profile first parse AND the OpenAPI schema, proving the
        // profiled openapi-contract path does not reject valid bodies — the strict parse only adds the
        // duplicate-key/trailing-token rejection, it does not break the happy path.
        client.post(port, "127.0.0.1", "/widgets")
                .putHeader("content-type", "application/json")
                .sendBuffer(Buffer.buffer("{\"name\":\"gizmo\"}"))
                .map(response -> response.statusCode())
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertTrue(
                            status == 201,
                            "a conforming body must reach dispatch (201) under the profiled openapi-contract gate; was "
                                    + status);
                    ctx.completeNow();
                })));
    }

    // --- helpers ---

    private static JaxRsOperationDescriptor op(String method, String route, String operationId) {
        return StubDescriptors.builder()
                .operationId(operationId)
                .httpMethod(method)
                .routeTemplate(route)
                .build();
    }
}
