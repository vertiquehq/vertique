// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * STEP-1 probe: confirms a per-route {@code failureHandler} that does NOT end the response and calls
 * {@code ctx.next()} CHAINS to the router-level catch-all failure handler in Vert.x 5.1.2 — the
 * pattern the production fix relies on (per-route handler stashes the route's decision, then defers
 * to the existing catch-all {@code handleFailure} to serialize the error body).
 *
 * <p>The client is a {@link WebClient} rather than a raw {@code HttpClient} deliberately: a raw
 * {@code HttpClientResponse} discards body buffers that arrive before a body handler is attached, so
 * under load a body read can succeed with zero bytes while the status code is correct (issue #167).
 * This probe asserts on the server-side {@code order} list rather than on the body, so the raw idiom is
 * latent rather than actively broken here — but a {@link WebClient} aggregates the response before
 * completing the send, which removes the trap for whoever next adds a body assertion. The response is
 * consequently projected to its status code alone: the previous body read existed only to complete the
 * exchange, and the aggregation makes it redundant.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class FailureHandlerChainProbeIT {

    private HttpServer server;
    private WebClient client;
    private final List<String> order = new CopyOnWriteArrayList<>();

    /**
     * Closes the {@link WebClient} and then the server started by the test that just ran.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns once
     * the underlying client has been asked to close, so there is no future to join here and the server
     * close alone carries the completion.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ar -> ctx.completeNow());
    }

    @Test
    @DisplayName("per-route failureHandler + ctx.next() chains to the router-level catch-all failureHandler")
    void perRouteThenCatchAll(Vertx vertx, VertxTestContext ctx) {
        Router router = Router.router(vertx);
        Route op = router.route(HttpMethod.GET, "/op");
        op.putMetadata("routeTag", "op-route");
        op.handler(rc -> rc.fail(new RuntimeException("boom")));
        op.failureHandler(rc -> {
            String tag = rc.currentRoute() != null ? (String) rc.currentRoute().getMetadata("routeTag") : null;
            order.add("perRoute:" + tag);
            rc.next(); // do not end — defer to the catch-all
        });
        router.route().failureHandler(rc -> {
            order.add("catchAll");
            rc.response().setStatusCode(500).end("done");
        });

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, "127.0.0.1")
                .compose(s -> {
                    server = s;
                    client = WebClient.create(vertx);
                    return client.get(s.actualPort(), "127.0.0.1", "/op").send().map(resp -> resp.statusCode());
                })
                .onComplete(ctx.succeeding(status -> {
                    ctx.verify(() -> {
                        System.out.println("[CHAIN] order=" + order);
                        assertEquals(
                                List.of("perRoute:op-route", "catchAll"),
                                order,
                                "per-route handler must run first then chain to the catch-all via ctx.next()");
                    });
                    ctx.completeNow();
                }));
    }
}
