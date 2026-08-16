// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.logging.MDCContexts;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
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
 *
 * <p>The request goes through a {@link WebClient} bound to the per-test {@link Vertx} instance
 * rather than a fresh inline {@code HttpClient}. Two reasons: an inline client is unclosable by
 * construction, so {@code Vertx} teardown reclaims its netty pools while the request may still be
 * in flight; and a raw {@code HttpClientResponse} discards body buffers that arrive before a body
 * handler is attached, which the drain step below existed to work around (issues #167, #330). A
 * {@link WebClient} has already aggregated the body by the time its send future resolves, so no
 * drain step is needed. Nothing here answers 3xx, so the follow-redirects default never engages.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ContextualLoggingMiddlewareTest {

    private final ContextualLoggingMiddleware middleware = new ContextualLoggingMiddleware();

    /**
     * The request client, bound by the one test that issues HTTP so it can be closed; stays
     * {@code null} for the two contract tests, which never create a {@link Vertx} instance at all.
     */
    private WebClient client;

    /**
     * Closes the {@link WebClient} the test that just ran created, before the extension closes the
     * {@link Vertx} instance it was created on.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns
     * once the underlying client has been asked to close, so there is no future to await here.
     */
    @AfterEach
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

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
        client = WebClient.create(vertx);

        Router router = Router.router(vertx);
        router.route().order(RequestContextLifecycle.ORDER).handler(new RequestContextLifecycle());
        router.route().order(middleware.priority()).handler(middleware);
        router.route("/test").handler(rc -> {
            capturedMdc.set(MDCContexts.copy());
            rc.end();
        });

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, "127.0.0.1")
                .compose(server -> {
                    int port = server.actualPort();
                    return client.post(port, "127.0.0.1", "/test").send().mapEmpty();
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
