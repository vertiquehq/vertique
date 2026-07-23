// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.logging.MDCContexts;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link ContextualLoggingMiddleware} after the correlation refactor (PR1).
 *
 * <p>This middleware now only binds {@code method} and {@code path} into MDC — request-id
 * resolution, the {@code X-Request-Id} response header, and the {@code requestId} MDC entry are
 * owned by {@code CorrelationIngressMiddleware}. The tests below verify the surviving contract.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ContextualLoggingMiddlewareTest {

    private final ContextualLoggingMiddleware middleware = new ContextualLoggingMiddleware();

    @Test
    @DisplayName("priority is 0 (after RequestContextLifecycle = Integer.MIN_VALUE)")
    void hasExpectedOrder() {
        assertEquals(0, middleware.priority());
    }

    @Test
    @DisplayName("scope is ROOT")
    void hasRootScope() {
        assertSame(MiddlewareScope.ROOT, middleware.scope());
    }

    @Test
    @DisplayName("method and path MDC keys are bound inside the request handler")
    void mdcMethodAndPathBoundInsideHandler(Vertx vertx, VertxTestContext ctx) {
        AtomicReference<Map<String, String>> capturedMdc = new AtomicReference<>();

        Router router = Router.router(vertx);
        router.route().order(RequestContextLifecycle.ORDER).handler(new RequestContextLifecycle());
        router.route().order(middleware.priority()).handler(middleware);
        router.route("/test").handler(rc -> {
            capturedMdc.set(MDCContexts.copy());
            rc.end();
        });

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(0)
                .compose(server -> {
                    int port = server.actualPort();
                    return vertx.createHttpClient()
                            .request(HttpMethod.POST, port, "localhost", "/test")
                            .compose(req -> req.send()
                                    .compose(response -> response.body().mapEmpty()));
                })
                .onComplete(ctx.succeeding(v -> {
                    Map<String, String> mdc = capturedMdc.get();
                    assertNotNull(mdc, "MDC must be captured inside the handler");
                    assertEquals("POST", mdc.get(MdcKeys.METHOD));
                    assertEquals("/test", mdc.get(MdcKeys.PATH));
                    ctx.completeNow();
                }));
    }
}
