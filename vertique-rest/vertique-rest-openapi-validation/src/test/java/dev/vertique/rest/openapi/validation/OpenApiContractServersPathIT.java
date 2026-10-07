// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves that the contract's {@code servers} URL never changes how the {@code openapi-contract} gate
 * validates a request: the gate matches by operation id and the router owns the path, so a path
 * parameter is read from the request path whether the contract declares no {@code servers}, a
 * relative one, or an absolute one with a path.
 *
 * <p>Each contract differs only in its {@code servers} entry. Every one is served under the same
 * route and must accept a conforming integer path parameter and reject a non-integer one; a
 * {@code servers} path that shifted the parameter's position would turn the conforming request into
 * a rejection.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class OpenApiContractServersPathIT {

    private static final String CONTRACT_TEMPLATE = "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"Orders\","
            + "\"version\":\"1\"}%s,\"paths\":{\"/orders/{id}\":{\"get\":{\"operationId\":\"getOrder\","
            + "\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"required\":true,"
            + "\"schema\":{\"type\":\"integer\"}}],\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}";

    @TempDir
    static Path directory;

    private static HttpServer server;
    private static WebClient client;
    private static int port;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) throws IOException {
        client = WebClient.create(vertx);
        Router router = Router.router(vertx);
        mount(vertx, router, "/none", "");
        mount(vertx, router, "/relative", ",\"servers\":[{\"url\":\"/api\"}]");
        mount(vertx, router, "/absolute", ",\"servers\":[{\"url\":\"http://example.com/api\"}]");
        router.route().failureHandler(rc -> {
            int status = rc.failure() instanceof RestValidationException ? 400 : 500;
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

    /** Serves {@code /orders/:id} under {@code prefix}, gated by a contract with the given {@code servers}. */
    private static void mount(Vertx vertx, Router router, String prefix, String servers) throws IOException {
        Path contract = Files.writeString(
                directory.resolve(prefix.substring(1) + ".json"), String.format(CONTRACT_TEMPLATE, servers));
        OpenApiContractValidationStrategy strategy = new OpenApiContractValidationStrategy(
                vertx, JaxRsConfig.builder().openapiPath(contract.toString()).build());
        JaxRsOperationDescriptor descriptor = StubDescriptors.builder()
                .operationId("getOrder")
                .httpMethod("GET")
                .routeTemplate("/orders/{id}")
                .build();
        Handler<RoutingContext> gate =
                strategy.gateFor(descriptor, OperationSchemas.empty()).orElseThrow();
        router.get(prefix + "/orders/:id")
                .handler(gate)
                .handler(rc -> rc.response().setStatusCode(200).end());
    }

    private static Future<Integer> get(String path) {
        return client.get(port, "127.0.0.1", path).send().map(response -> response.statusCode());
    }

    @Test
    @DisplayName("Without servers a conforming path parameter is accepted and a non-integer one rejected")
    void withoutServers(VertxTestContext ctx) {
        assertStatuses(ctx, "/none");
    }

    @Test
    @DisplayName("With a relative servers url a conforming path parameter is accepted and a non-integer one rejected")
    void withRelativeServers(VertxTestContext ctx) {
        assertStatuses(ctx, "/relative");
    }

    @Test
    @DisplayName("With an absolute servers url a conforming path parameter is accepted and a non-integer one rejected")
    void withAbsoluteServers(VertxTestContext ctx) {
        assertStatuses(ctx, "/absolute");
    }

    private static void assertStatuses(VertxTestContext ctx, String prefix) {
        get(prefix + "/orders/5")
                .compose(conforming -> get(prefix + "/orders/abc").map(rejected -> new int[] {conforming, rejected}))
                .onComplete(ctx.succeeding(statuses -> ctx.verify(() -> {
                    assertEquals(200, statuses[0], "a conforming path parameter must reach dispatch");
                    assertEquals(400, statuses[1], "a non-integer path parameter must be rejected as 400");
                    ctx.completeNow();
                })));
    }
}
