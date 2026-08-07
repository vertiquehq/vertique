// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
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
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class FailureHandlerChainProbeIT {

    private HttpServer server;
    private HttpClient client;
    private final List<String> order = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<?> clientClose = client != null ? client.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose).onComplete(ar -> ctx.completeNow());
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
                    client = vertx.createHttpClient();
                    return client.request(HttpMethod.GET, s.actualPort(), "127.0.0.1", "/op")
                            .compose(req -> req.send())
                            .compose(resp -> resp.body().map(b -> b.toString()));
                })
                .onComplete(ctx.succeeding(body -> {
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
