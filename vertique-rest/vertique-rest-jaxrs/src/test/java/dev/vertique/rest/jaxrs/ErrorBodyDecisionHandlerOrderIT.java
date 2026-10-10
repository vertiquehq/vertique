// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.rest.core.events.RequestCompletionRecorder;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Pins that {@link JaxRsRouteRegistrar#errorBodyDecisionHandler} makes the error-body decision of the
 * route that actually matched, whatever the route order.
 *
 * <p>After {@code ctx.fail(...)} Vert.x runs the failure handler of every route matching the path and
 * method, in route order. Two overlapping routes are built here with the less specific one first. It
 * matches the request, records itself, and passes control on; the later, more specific route then
 * records itself and fails. The earlier route's failure handler runs first, yet the later route's
 * profile must decide the error body. The fixture stands in for an earlier-ordered overlapping route
 * that the registrar's own most-specific-first sort cannot produce, such as one a customizer adds.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ErrorBodyDecisionHandlerOrderIT {

    private static final ObjectMapper MAPPER_EARLIER = new ObjectMapper();
    private static final ObjectMapper MAPPER_LATER = new ObjectMapper();

    private final RestOperationDescriptor earlier = mock(RestOperationDescriptor.class);
    private final RestOperationDescriptor later = mock(RestOperationDescriptor.class);

    private HttpServer server;
    private WebClient client;

    /** What the router-level failure handler observed when the failure reached it. */
    private record Observed(Object mapper, Object decided) {}

    /**
     * Closes the {@link WebClient} and then the server started by the test that just ran.
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

    /**
     * Builds the two-route router. The earlier route (path parameter) is added first and passes control
     * on after recording itself; the later route (static path) records itself and fails when
     * {@code laterFails}, otherwise the earlier route is the one that fails.
     */
    private Router router(
            Vertx vertx,
            boolean mountCompletionState,
            boolean laterFails,
            ObjectMapper laterMapper,
            AtomicReference<Observed> observed) {
        Router router = Router.router(vertx);
        if (mountCompletionState) {
            router.route().handler(rc -> {
                RequestCompletionRecorder.installHolder(rc);
                rc.next();
            });
        }
        Route first = router.route(HttpMethod.GET, "/overlap/:id");
        first.handler(RequestCompletionRecorder.operationRouteHandler(earlier));
        first.handler(rc -> {
            if (laterFails) {
                rc.next();
            } else {
                rc.fail(new RuntimeException("earlier failed"));
            }
        });
        first.failureHandler(JaxRsRouteRegistrar.errorBodyDecisionHandler(earlier, MAPPER_EARLIER));

        Route second = router.route(HttpMethod.GET, "/overlap/fixed");
        second.handler(RequestCompletionRecorder.operationRouteHandler(later));
        second.handler(rc -> rc.fail(new RuntimeException("later failed")));
        second.failureHandler(JaxRsRouteRegistrar.errorBodyDecisionHandler(later, laterMapper));

        router.route().failureHandler(rc -> {
            observed.set(new Observed(
                    rc.get(BoundRequest.KEY_RESOLVED_BODY_MAPPER), rc.get(BoundRequest.KEY_ERROR_BODY_MAPPER_DECIDED)));
            rc.response().setStatusCode(500).end("done");
        });
        return router;
    }

    private Future<Integer> get(Vertx vertx, Router router, String path) {
        return vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, "127.0.0.1")
                .compose(s -> {
                    server = s;
                    // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
                    client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
                    return client.get(s.actualPort(), "127.0.0.1", path).send().map(resp -> resp.statusCode());
                });
    }

    @Test
    @DisplayName("the failing later route's profile decides although an earlier overlapping route's handler runs first")
    void laterRouteFailing_itsOwnProfileDecides(Vertx vertx, VertxTestContext ctx) {
        AtomicReference<Observed> observed = new AtomicReference<>();
        Router router = router(vertx, true, true, MAPPER_LATER, observed);

        get(vertx, router, "/overlap/fixed").onComplete(ctx.succeeding(status -> {
            ctx.verify(() -> {
                assertEquals(500, status);
                assertSame(
                        MAPPER_LATER,
                        observed.get().mapper(),
                        "the route that failed must decide the error-body mapper, not the earlier overlapping route");
                assertEquals(Boolean.TRUE, observed.get().decided());
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("a failing later process-codec route stashes no mapper although an earlier route is profiled")
    void laterProcessCodecRouteFailing_stashesNothing(Vertx vertx, VertxTestContext ctx) {
        AtomicReference<Observed> observed = new AtomicReference<>();
        Router router = router(vertx, true, true, null, observed);

        get(vertx, router, "/overlap/fixed").onComplete(ctx.succeeding(status -> {
            ctx.verify(() -> {
                assertNull(
                        observed.get().mapper(),
                        "a process-codec decision stashes nothing; the earlier route's profile must not leak");
                assertEquals(Boolean.TRUE, observed.get().decided(), "the matched route still marks its decision");
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("the earlier route's profile decides when the earlier route is the one that failed")
    void earlierRouteFailing_itsOwnProfileDecides(Vertx vertx, VertxTestContext ctx) {
        AtomicReference<Observed> observed = new AtomicReference<>();
        Router router = router(vertx, true, false, MAPPER_LATER, observed);

        get(vertx, router, "/overlap/other").onComplete(ctx.succeeding(status -> {
            ctx.verify(() -> {
                assertSame(MAPPER_EARLIER, observed.get().mapper());
                assertEquals(Boolean.TRUE, observed.get().decided());
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("without a recorded operation the first handler to run keeps deciding")
    void noRecordedOperation_firstDecisionWins(Vertx vertx, VertxTestContext ctx) {
        AtomicReference<Observed> observed = new AtomicReference<>();
        Router router = router(vertx, false, true, MAPPER_LATER, observed);

        get(vertx, router, "/overlap/fixed").onComplete(ctx.succeeding(status -> {
            ctx.verify(() -> {
                assertSame(
                        MAPPER_EARLIER,
                        observed.get().mapper(),
                        "with nothing recorded the handler cannot tell the routes apart and the first decides");
                assertEquals(Boolean.TRUE, observed.get().decided());
            });
            ctx.completeNow();
        }));
    }
}
