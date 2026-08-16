// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.core.correlation.CorrelationPropagationMode;
import dev.vertique.core.correlation.CorrelationResponseMode;
import dev.vertique.core.correlation.ProtocolCorrelationRef;
import dev.vertique.core.correlation.TraceReference;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.correlation.CorrelationContextMutator;
import dev.vertique.correlation.CorrelationMdcKeys;
import dev.vertique.correlation.TraceReferenceResolver;
import dev.vertique.logging.MDCContexts;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Behavioural test for {@link CorrelationIngressMiddleware} via a real Vert.x router.
 *
 * <p>Verifies the core ingress contract: a generated request id when the header is absent;
 * verbatim preservation when the header is present and valid; correlation id falling back to the
 * request id with provenance recorded; live {@link CorrelationContext} bound on the holder
 * during the request; {@code X-Request-Id} response echo enabled by default; mirrored MDC keys
 * present during handler execution; the REJECT policy short-circuit for invalid headers; and the
 * {@link TraceReferenceResolver} integration (trace ids in MDC when present, warn-on-error, no
 * change when absent or resolver throws).
 *
 * <p>Per the repo's testing rules a single {@link WebClient} is shared across all test methods
 * via {@code @BeforeAll} to avoid netty channel-pool churn under full-reactor load. Each test
 * still creates its own {@link HttpServer}, closed in {@code @AfterEach}, because the router
 * wiring differs per test. Server bind and client connect both use the loopback literal.
 *
 * <p>The client is a {@link WebClient} rather than a raw {@code HttpClient} deliberately: a raw
 * {@code HttpClientResponse} discards body buffers that arrive before a body handler is attached, so
 * under load a body read can succeed with zero bytes while the status code is correct (issue #167).
 * Every assertion here reads the response status, a response header, or state captured inside the
 * handler — never the body — so these tests cannot flake on that today; the raw idiom was latent, and
 * would become a live race the moment anyone asserted on the body, with no diff to hint why. A
 * {@link WebClient} aggregates the body into its {@code HttpResponse} before completing the send, and
 * the aggregated {@code HttpResponse} exposes the response headers through the same
 * {@code io.vertx.core.http.HttpResponseHead#getHeader(String)} contract the raw response did, so
 * the header assertions read exactly the same values. No route in this class ever answers with a
 * 3xx, so the {@link WebClient}'s redirect-following default never engages and every assertion still
 * sees the first (and only) response.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class CorrelationIngressMiddlewareTest {

    // --- Class-scoped resources (shared across all @Test methods) ---

    private static Vertx vertx;
    private static WebClient client;

    // --- Per-test resources ---

    private HttpServer server;

    /**
     * Creates the class-scoped {@link Vertx} instance and shared {@link WebClient} once for the
     * entire test class. Allocating a fresh client per test accumulates netty channel pools that
     * surface under full-reactor load as connection timeouts.
     *
     * @param v   the class-scoped Vert.x instance injected by vertx-junit5
     * @param ctx the test context used to signal setup completion
     */
    @BeforeAll
    static void setUpClass(Vertx v, VertxTestContext ctx) {
        vertx = v;
        // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
        client = WebClient.create(v, new WebClientOptions().setFollowRedirects(false));
        ctx.completeNow();
    }

    /**
     * Closes the per-test {@link HttpServer}. The shared {@link WebClient} is left open and closed
     * only in {@link #tearDownClass(VertxTestContext)}.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ar -> ctx.completeNow());
    }

    /**
     * Closes the shared {@link WebClient} after all tests in the class have run.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns once
     * the underlying client has been asked to close, so there is no future to chain the context
     * completion off.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        ctx.completeNow();
    }

    // --- Helpers ---

    private static CorrelationIngressMiddleware newMiddleware() {
        return newMiddleware(CorrelationIngressConfig.defaults());
    }

    private static CorrelationIngressMiddleware newMiddleware(CorrelationIngressConfig config) {
        return newMiddleware(config, Set.of(), Set.of());
    }

    private static CorrelationIngressMiddleware newMiddleware(
            CorrelationIngressConfig config,
            Set<ProtocolCorrelationSpec> specs,
            Set<ProtocolCorrelationContributor> contributors) {
        return newMiddleware(config, specs, contributors, Optional.empty());
    }

    private static CorrelationIngressMiddleware newMiddleware(
            CorrelationIngressConfig config,
            Set<ProtocolCorrelationSpec> specs,
            Set<ProtocolCorrelationContributor> contributors,
            Optional<TraceReferenceResolver> traceResolver) {
        ContextHolder holder = new DefaultContextHolder();
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        CorrelationContextMutator mutator = new CorrelationContextMutator(holder);
        return new CorrelationIngressMiddleware(holder, factory, mutator, config, specs, contributors, traceResolver);
    }

    private static Router router(Vertx vertx, CorrelationIngressMiddleware middleware, RouteHandler handler) {
        Router router = Router.router(vertx);
        router.route().order(RequestContextLifecycle.ORDER).handler(new RequestContextLifecycle());
        router.route().order(middleware.priority()).handler(middleware);
        router.route("/test").handler(rc -> {
            handler.handle(rc);
            if (!rc.response().ended()) {
                rc.end();
            }
        });
        return router;
    }

    /**
     * Boots the server on the loopback interface, stores it on the test instance for
     * {@code @AfterEach} cleanup, and returns the listening port. The shared {@link WebClient}
     * is used for all requests.
     */
    private Future<Integer> startServer(Vertx vertx, Router router) {
        return vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, "127.0.0.1")
                .map(s -> {
                    this.server = s;
                    return s.actualPort();
                });
    }

    @FunctionalInterface
    private interface RouteHandler {
        void handle(io.vertx.ext.web.RoutingContext rc);
    }

    // --- Tests ---

    @Test
    @DisplayName("X-Request-Id is generated when absent and echoed in the response (defaults)")
    void generatesAndEchosRequestId(VertxTestContext ctx) {
        CorrelationIngressMiddleware middleware = newMiddleware();
        ContextHolder holder = new DefaultContextHolder();
        AtomicReference<CorrelationIdentifier> captured = new AtomicReference<>();

        Router router = router(
                vertx,
                middleware,
                rc -> captured.set(holder.current(CorrelationContext.class)
                        .map(CorrelationContext::requestId)
                        .orElse(null)));

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                .onComplete(ctx.succeeding((HttpResponse<Buffer> resp) -> {
                    String echoed = resp.getHeader("X-Request-Id");
                    assertNotNull(echoed);
                    CorrelationIdentifier bound = captured.get();
                    assertNotNull(bound);
                    assertEquals(echoed, bound.value());
                    assertEquals("generated", bound.source());
                    assertEquals(4, UUID.fromString(echoed).version());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("inbound X-Request-Id is preserved verbatim and echoed")
    void preservesInboundRequestId(VertxTestContext ctx) {
        CorrelationIngressMiddleware middleware = newMiddleware();
        ContextHolder holder = new DefaultContextHolder();
        AtomicReference<CorrelationContext> captured = new AtomicReference<>();

        Router router = router(
                vertx,
                middleware,
                rc -> captured.set(holder.current(CorrelationContext.class).orElse(null)));

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test")
                        .putHeader("X-Request-Id", "explicit-id-1")
                        .send())
                .onComplete(ctx.succeeding((HttpResponse<Buffer> resp) -> {
                    assertEquals("explicit-id-1", resp.getHeader("X-Request-Id"));
                    CorrelationContext live = captured.get();
                    assertNotNull(live);
                    assertEquals("explicit-id-1", live.requestId().value());
                    assertEquals("http-header", live.requestId().source());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("correlation id falls back to request id with source=generated-from-request-id")
    void correlationFallsBackToRequest(VertxTestContext ctx) {
        CorrelationIngressMiddleware middleware = newMiddleware();
        ContextHolder holder = new DefaultContextHolder();
        AtomicReference<CorrelationContext> captured = new AtomicReference<>();

        Router router = router(
                vertx,
                middleware,
                rc -> captured.set(holder.current(CorrelationContext.class).orElse(null)));

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                .onComplete(ctx.succeeding(resp -> {
                    CorrelationContext live = captured.get();
                    assertNotNull(live);
                    assertEquals(live.requestId().value(), live.correlationId().value());
                    assertEquals(
                            "generated-from-request-id", live.correlationId().source());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("mirrored MDC keys requestId / correlationId are visible during handler execution")
    void mirroredMdcKeysPresentDuringHandler(VertxTestContext ctx) {
        CorrelationIngressMiddleware middleware = newMiddleware();
        AtomicReference<String> capturedRequestId = new AtomicReference<>();
        AtomicReference<String> capturedCorrelationId = new AtomicReference<>();

        Router router = router(vertx, middleware, rc -> {
            capturedRequestId.set(MDCContexts.get(CorrelationMdcKeys.REQUEST_ID));
            capturedCorrelationId.set(MDCContexts.get(CorrelationMdcKeys.CORRELATION_ID));
        });

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                .onComplete(ctx.succeeding(resp -> {
                    assertNotNull(capturedRequestId.get(), "requestId MDC must be bound in handler");
                    assertNotNull(capturedCorrelationId.get(), "correlationId MDC must be bound in handler");
                    assertEquals(capturedRequestId.get(), capturedCorrelationId.get());
                    assertTrue(capturedRequestId.get().length() > 0);
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("each request gets an independent generated id")
    void independentIdsAcrossRequests(VertxTestContext ctx) {
        CorrelationIngressMiddleware middleware = newMiddleware();
        Router router = router(vertx, middleware, rc -> {});

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test")
                        // First request: capture the id the middleware generated for it.
                        .send()
                        .compose(resp -> {
                            String first = resp.getHeader("X-Request-Id");
                            return client.get(port, "127.0.0.1", "/test")
                                    // Second request on the same port must get its own id.
                                    .send()
                                    .map(resp2 -> {
                                        assertNotEquals(first, resp2.getHeader("X-Request-Id"));
                                        return null;
                                    });
                        }))
                .onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    @Test
    @DisplayName("REJECT policy: invalid inbound X-Request-Id produces a 400 response")
    void rejectPolicyRejectsInvalidInbound(VertxTestContext ctx) {
        CorrelationIngressConfig config = new CorrelationIngressConfig(
                "X-Request-Id",
                "X-Correlation-Id",
                "X-Causation-Id",
                true,
                false,
                false,
                CorrelationIngressConfig.InvalidValuePolicy.REJECT);
        CorrelationIngressMiddleware middleware = newMiddleware(config);
        AtomicBoolean handlerReached = new AtomicBoolean();
        Router router = router(vertx, middleware, rc -> handlerReached.set(true));

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test")
                        // "@" is not in the validator's allow-list — REJECT fires.
                        .putHeader("X-Request-Id", "bad@value")
                        .send())
                .onComplete(ctx.succeeding((HttpResponse<Buffer> resp) -> {
                    assertEquals(400, resp.statusCode());
                    assertFalse(handlerReached.get(), "handler must not run when REJECT short-circuits");
                    String generated = resp.getHeader("X-Request-Id");
                    assertNotNull(generated);
                    assertNotEquals("bad@value", generated);
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("REJECT policy: blank inbound X-Request-Id also rejects (present-but-empty fails the validator)")
    void rejectPolicyRejectsBlankInbound(VertxTestContext ctx) {
        CorrelationIngressConfig config = new CorrelationIngressConfig(
                "X-Request-Id",
                "X-Correlation-Id",
                "X-Causation-Id",
                true,
                false,
                false,
                CorrelationIngressConfig.InvalidValuePolicy.REJECT);
        CorrelationIngressMiddleware middleware = newMiddleware(config);
        AtomicBoolean handlerReached = new AtomicBoolean();
        Router router = router(vertx, middleware, rc -> handlerReached.set(true));

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test")
                        .putHeader("X-Request-Id", "")
                        .send())
                .onComplete(ctx.succeeding((HttpResponse<Buffer> resp) -> {
                    assertEquals(400, resp.statusCode());
                    assertFalse(handlerReached.get());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("REJECT policy: blank inbound X-Correlation-Id also rejects")
    void rejectPolicyRejectsBlankCorrelationId(VertxTestContext ctx) {
        CorrelationIngressConfig config = new CorrelationIngressConfig(
                "X-Request-Id",
                "X-Correlation-Id",
                "X-Causation-Id",
                true,
                false,
                false,
                CorrelationIngressConfig.InvalidValuePolicy.REJECT);
        CorrelationIngressMiddleware middleware = newMiddleware(config);
        AtomicBoolean handlerReached = new AtomicBoolean();
        Router router = router(vertx, middleware, rc -> handlerReached.set(true));

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test")
                        .putHeader("X-Correlation-Id", "")
                        .send())
                .onComplete(ctx.succeeding((HttpResponse<Buffer> resp) -> {
                    assertEquals(400, resp.statusCode());
                    assertFalse(handlerReached.get());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("REJECT policy: blank inbound X-Causation-Id also rejects when causation parsing is on")
    void rejectPolicyRejectsBlankCausationId(VertxTestContext ctx) {
        CorrelationIngressConfig config = new CorrelationIngressConfig(
                "X-Request-Id",
                "X-Correlation-Id",
                "X-Causation-Id",
                true,
                false,
                true,
                CorrelationIngressConfig.InvalidValuePolicy.REJECT);
        CorrelationIngressMiddleware middleware = newMiddleware(config);
        AtomicBoolean handlerReached = new AtomicBoolean();
        Router router = router(vertx, middleware, rc -> handlerReached.set(true));

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test")
                        .putHeader("X-Causation-Id", "")
                        .send())
                .onComplete(ctx.succeeding((HttpResponse<Buffer> resp) -> {
                    assertEquals(400, resp.statusCode());
                    assertFalse(handlerReached.get());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName(
            "REPLACE_WITH_GENERATED policy (default): invalid inbound is replaced with a generated id, request proceeds")
    void replacePolicyFallsThrough(VertxTestContext ctx) {
        CorrelationIngressMiddleware middleware = newMiddleware();
        AtomicBoolean handlerReached = new AtomicBoolean();
        Router router = router(vertx, middleware, rc -> handlerReached.set(true));

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test")
                        .putHeader("X-Request-Id", "bad@value")
                        .send())
                .onComplete(ctx.succeeding((HttpResponse<Buffer> resp) -> {
                    assertEquals(200, resp.statusCode());
                    assertTrue(handlerReached.get(), "handler must run under REPLACE policy");
                    String echoed = resp.getHeader("X-Request-Id");
                    assertNotNull(echoed);
                    assertNotEquals("bad@value", echoed);
                    ctx.completeNow();
                }));
    }

    // --- Inbound correlation/causation id resolution edge cases ---

    @Test
    @DisplayName("inbound X-Correlation-Id is preserved verbatim with source=http-header")
    void preserveValidCorrelationId(VertxTestContext ctx) {
        CorrelationIngressMiddleware middleware = newMiddleware();
        AtomicReference<CorrelationContext> seen = new AtomicReference<>();
        Router router = router(vertx, middleware, rc -> new DefaultContextHolder()
                .current(CorrelationContext.class)
                .ifPresent(seen::set));

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test")
                        .putHeader("X-Correlation-Id", "cor-upstream-1")
                        .send())
                .onComplete(ctx.succeeding((HttpResponse<Buffer> resp) -> {
                    assertEquals(200, resp.statusCode());
                    assertNotNull(seen.get());
                    assertEquals("cor-upstream-1", seen.get().correlationId().value());
                    assertEquals("http-header", seen.get().correlationId().source());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName(
            "REPLACE policy: invalid X-Correlation-Id is dropped and correlation id falls back to request id with provenance")
    void replacePolicyInvalidCorrelationIdFallsBack(VertxTestContext ctx) {
        CorrelationIngressMiddleware middleware = newMiddleware();
        AtomicReference<CorrelationContext> seen = new AtomicReference<>();
        Router router = router(vertx, middleware, rc -> new DefaultContextHolder()
                .current(CorrelationContext.class)
                .ifPresent(seen::set));

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test")
                        .putHeader("X-Request-Id", "req-good")
                        .putHeader("X-Correlation-Id", "bad@value")
                        .send())
                .onComplete(ctx.succeeding((HttpResponse<Buffer> resp) -> {
                    assertEquals(200, resp.statusCode());
                    assertNotNull(seen.get());
                    // Fall back: correlation id derived from the (preserved) request id.
                    assertEquals("req-good", seen.get().correlationId().value());
                    assertEquals(
                            "generated-from-request-id",
                            seen.get().correlationId().source());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("valid X-Causation-Id is parsed when parseCausationId is enabled and projected to MDC")
    void parseCausationIdHappyPath(VertxTestContext ctx) {
        CorrelationIngressConfig config = new CorrelationIngressConfig(
                "X-Request-Id",
                "X-Correlation-Id",
                "X-Causation-Id",
                true,
                false,
                true,
                CorrelationIngressConfig.InvalidValuePolicy.REPLACE_WITH_GENERATED);
        CorrelationIngressMiddleware middleware = newMiddleware(config);
        AtomicReference<CorrelationContext> seen = new AtomicReference<>();
        AtomicReference<String> capturedMdcCausation = new AtomicReference<>();
        Router router = router(vertx, middleware, rc -> {
            new DefaultContextHolder().current(CorrelationContext.class).ifPresent(seen::set);
            capturedMdcCausation.set(MDCContexts.get(CorrelationMdcKeys.CAUSATION_ID));
        });

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test")
                        .putHeader("X-Causation-Id", "cause-1")
                        .send())
                .onComplete(ctx.succeeding((HttpResponse<Buffer> resp) -> {
                    assertEquals(200, resp.statusCode());
                    assertNotNull(seen.get());
                    assertNotNull(seen.get().causationId());
                    assertEquals("cause-1", seen.get().causationId().value());
                    assertEquals("http-header", seen.get().causationId().source());
                    assertEquals("cause-1", capturedMdcCausation.get());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName(
            "REPLACE policy + parseCausationId=true: invalid X-Causation-Id is dropped (no causation id bound, request proceeds)")
    void replacePolicyInvalidCausationDropped(VertxTestContext ctx) {
        CorrelationIngressConfig config = new CorrelationIngressConfig(
                "X-Request-Id",
                "X-Correlation-Id",
                "X-Causation-Id",
                true,
                false,
                true,
                CorrelationIngressConfig.InvalidValuePolicy.REPLACE_WITH_GENERATED);
        CorrelationIngressMiddleware middleware = newMiddleware(config);
        AtomicReference<CorrelationContext> seen = new AtomicReference<>();
        Router router = router(vertx, middleware, rc -> new DefaultContextHolder()
                .current(CorrelationContext.class)
                .ifPresent(seen::set));

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test")
                        .putHeader("X-Causation-Id", "bad@value")
                        .send())
                .onComplete(ctx.succeeding((HttpResponse<Buffer> resp) -> {
                    assertEquals(200, resp.statusCode());
                    assertNotNull(seen.get());
                    // Invalid causation header is dropped under REPLACE policy — no causation
                    // id ends up bound.
                    org.junit.jupiter.api.Assertions.assertNull(seen.get().causationId());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("config echoCorrelationId=true emits X-Correlation-Id on the response")
    void echoCorrelationIdEmitsResponseHeader(VertxTestContext ctx) {
        CorrelationIngressConfig config = new CorrelationIngressConfig(
                "X-Request-Id",
                "X-Correlation-Id",
                "X-Causation-Id",
                true,
                true,
                false,
                CorrelationIngressConfig.InvalidValuePolicy.REPLACE_WITH_GENERATED);
        CorrelationIngressMiddleware middleware = newMiddleware(config);
        Router router = router(vertx, middleware, rc -> {});

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test")
                        .putHeader("X-Correlation-Id", "cor-upstream")
                        .send())
                .onComplete(ctx.succeeding((HttpResponse<Buffer> resp) -> {
                    assertEquals(200, resp.statusCode());
                    assertEquals("cor-upstream", resp.getHeader("X-Correlation-Id"));
                    ctx.completeNow();
                }));
    }

    // --- ProtocolCorrelationSpec + Contributor extension paths ---

    @Test
    @DisplayName("ProtocolCorrelationSpec: present-and-valid inbound is captured as a ref and echoed on the response")
    void protocolSpecCapturesAndEchoesInbound(VertxTestContext ctx) {
        ProtocolCorrelationSpec spec = new ProtocolCorrelationSpec() {
            @Override
            public String headerName() {
                return "X-Test-Trace-Id";
            }

            @Override
            public CorrelationResponseMode responseMode() {
                return CorrelationResponseMode.ECHO_SAME_HEADER;
            }

            @Override
            public CorrelationPropagationMode propagationMode() {
                return CorrelationPropagationMode.PROPAGATE_SAME_HEADER;
            }

            @Override
            public boolean durableSafe() {
                return true;
            }

            @Override
            public Optional<String> acceptInbound(String inboundValue) {
                return inboundValue == null ? Optional.empty() : Optional.of(inboundValue);
            }
        };
        CorrelationIngressMiddleware middleware =
                newMiddleware(CorrelationIngressConfig.defaults(), Set.of(spec), Set.of());
        AtomicReference<List<ProtocolCorrelationRef>> seen = new AtomicReference<>();
        Router router = router(vertx, middleware, rc -> new DefaultContextHolder()
                .current(CorrelationContext.class)
                .ifPresent(c -> seen.set(c.protocolCorrelations())));

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test")
                        .putHeader("X-Test-Trace-Id", "trace-abc-123")
                        .send())
                .onComplete(ctx.succeeding((HttpResponse<Buffer> resp) -> {
                    assertEquals(200, resp.statusCode());
                    assertEquals("trace-abc-123", resp.getHeader("X-Test-Trace-Id"));
                    assertNotNull(seen.get());
                    assertEquals(1, seen.get().size());
                    ProtocolCorrelationRef ref = seen.get().get(0);
                    assertEquals("X-Test-Trace-Id", ref.headerName());
                    assertEquals("trace-abc-123", ref.value());
                    assertEquals("http-header", ref.source());
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName(
            "ProtocolCorrelationSpec: absent inbound + ECHO_OR_GENERATE_RFC4122 generates a fresh ref and echoes it")
    void protocolSpecGeneratesWhenAbsent(VertxTestContext ctx) {
        ProtocolCorrelationSpec spec = new ProtocolCorrelationSpec() {
            @Override
            public String headerName() {
                return "X-Test-Interaction-Id";
            }

            @Override
            public CorrelationResponseMode responseMode() {
                return CorrelationResponseMode.ECHO_OR_GENERATE_RFC4122;
            }

            @Override
            public CorrelationPropagationMode propagationMode() {
                return CorrelationPropagationMode.NONE;
            }

            @Override
            public boolean durableSafe() {
                return true;
            }

            @Override
            public Optional<String> acceptInbound(String inboundValue) {
                return Optional.empty();
            }

            @Override
            public String generate() {
                return "00000000-0000-4000-8000-000000000001";
            }

            @Override
            public Map<String, String> attributes() {
                return Map.of("standard", "test");
            }
        };
        CorrelationIngressMiddleware middleware =
                newMiddleware(CorrelationIngressConfig.defaults(), Set.of(spec), Set.of());
        Router router = router(vertx, middleware, rc -> {});

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                .onComplete(ctx.succeeding((HttpResponse<Buffer> resp) -> {
                    assertEquals(200, resp.statusCode());
                    assertEquals("00000000-0000-4000-8000-000000000001", resp.getHeader("X-Test-Interaction-Id"));
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName(
            "ProtocolCorrelationSpec: absent inbound + non-generating response mode produces no ref (response header absent)")
    void protocolSpecSkipsWhenNoObligation(VertxTestContext ctx) {
        ProtocolCorrelationSpec spec = new ProtocolCorrelationSpec() {
            @Override
            public String headerName() {
                return "X-Optional-Tag";
            }

            @Override
            public CorrelationResponseMode responseMode() {
                return CorrelationResponseMode.NONE;
            }

            @Override
            public CorrelationPropagationMode propagationMode() {
                return CorrelationPropagationMode.NONE;
            }

            @Override
            public boolean durableSafe() {
                return false;
            }

            @Override
            public Optional<String> acceptInbound(String inboundValue) {
                return Optional.empty();
            }
        };
        CorrelationIngressMiddleware middleware =
                newMiddleware(CorrelationIngressConfig.defaults(), Set.of(spec), Set.of());
        AtomicReference<List<ProtocolCorrelationRef>> seen = new AtomicReference<>();
        Router router = router(vertx, middleware, rc -> new DefaultContextHolder()
                .current(CorrelationContext.class)
                .ifPresent(c -> seen.set(c.protocolCorrelations())));

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                .onComplete(ctx.succeeding((HttpResponse<Buffer> resp) -> {
                    assertEquals(200, resp.statusCode());
                    org.junit.jupiter.api.Assertions.assertNull(resp.getHeader("X-Optional-Tag"));
                    assertNotNull(seen.get());
                    assertTrue(seen.get().isEmpty(), "no protocol ref when spec returns null");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("ProtocolCorrelationContributor: contributed ref is appended to the bound context and echoed")
    void protocolContributorAppendsRef(VertxTestContext ctx) {
        ProtocolCorrelationContributor contributor = request -> Optional.of(new ProtocolCorrelationRef(
                "X-Custom-Resolve",
                "from-contributor",
                "contributor-source",
                CorrelationResponseMode.ECHO_SAME_HEADER,
                CorrelationPropagationMode.NONE,
                false,
                Map.of()));
        CorrelationIngressMiddleware middleware =
                newMiddleware(CorrelationIngressConfig.defaults(), Set.of(), Set.of(contributor));
        AtomicReference<List<ProtocolCorrelationRef>> seen = new AtomicReference<>();
        Router router = router(vertx, middleware, rc -> new DefaultContextHolder()
                .current(CorrelationContext.class)
                .ifPresent(c -> seen.set(c.protocolCorrelations())));

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                .onComplete(ctx.succeeding((HttpResponse<Buffer> resp) -> {
                    assertEquals(200, resp.statusCode());
                    assertEquals("from-contributor", resp.getHeader("X-Custom-Resolve"));
                    assertNotNull(seen.get());
                    assertEquals(1, seen.get().size());
                    assertEquals("contributor-source", seen.get().get(0).source());
                    ctx.completeNow();
                }));
    }

    // --- TraceReferenceResolver integration tests ---

    @Test
    @DisplayName("resolver returning a TraceReference: traceId and spanId are in MDC during the request and gone after")
    void resolverPresentSetsTraceIdsInMdcAndClearsAfter(VertxTestContext ctx) {
        TraceReferenceResolver resolver = () -> Optional.of(new TraceReference("trace-1", "span-1", "test"));
        CorrelationIngressMiddleware middleware =
                newMiddleware(CorrelationIngressConfig.defaults(), Set.of(), Set.of(), Optional.of(resolver));

        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        AtomicReference<String> capturedSpanId = new AtomicReference<>();

        Router router = router(vertx, middleware, rc -> {
            capturedTraceId.set(MDCContexts.get(CorrelationMdcKeys.TRACE_ID));
            capturedSpanId.set(MDCContexts.get(CorrelationMdcKeys.SPAN_ID));
        });

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                .onComplete(ctx.succeeding(resp -> {
                    // Inside the request the trace ids must be visible in MDC.
                    assertEquals("trace-1", capturedTraceId.get(), "traceId must be in MDC during request");
                    assertEquals("span-1", capturedSpanId.get(), "spanId must be in MDC during request");
                    // After the request scope is closed the snapshot restores absence.
                    assertNull(
                            MDCContexts.get(CorrelationMdcKeys.TRACE_ID),
                            "traceId MDC key must be removed after request end");
                    assertNull(
                            MDCContexts.get(CorrelationMdcKeys.SPAN_ID),
                            "spanId MDC key must be removed after request end");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("resolver returning empty: no traceId or spanId MDC keys during the request")
    void resolverEmptyNoTraceIdsInMdc(VertxTestContext ctx) {
        TraceReferenceResolver resolver = () -> Optional.empty();
        CorrelationIngressMiddleware middleware =
                newMiddleware(CorrelationIngressConfig.defaults(), Set.of(), Set.of(), Optional.of(resolver));

        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        AtomicReference<String> capturedSpanId = new AtomicReference<>();

        Router router = router(vertx, middleware, rc -> {
            capturedTraceId.set(MDCContexts.get(CorrelationMdcKeys.TRACE_ID));
            capturedSpanId.set(MDCContexts.get(CorrelationMdcKeys.SPAN_ID));
        });

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                .onComplete(ctx.succeeding(resp -> {
                    assertNull(capturedTraceId.get(), "traceId must not be in MDC when resolver returns empty");
                    assertNull(capturedSpanId.get(), "spanId must not be in MDC when resolver returns empty");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("resolver throwing RuntimeException: request completes normally (200) and no traceId in MDC")
    void resolverThrowingDoesNotFailRequest(VertxTestContext ctx) {
        TraceReferenceResolver resolver = () -> {
            throw new RuntimeException("tracer exploded");
        };
        CorrelationIngressMiddleware middleware =
                newMiddleware(CorrelationIngressConfig.defaults(), Set.of(), Set.of(), Optional.of(resolver));

        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        AtomicBoolean handlerReached = new AtomicBoolean();

        Router router = router(vertx, middleware, rc -> {
            handlerReached.set(true);
            capturedTraceId.set(MDCContexts.get(CorrelationMdcKeys.TRACE_ID));
        });

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                .onComplete(ctx.succeeding((HttpResponse<Buffer> resp) -> {
                    assertEquals(200, resp.statusCode(), "request must complete normally when resolver throws");
                    assertTrue(handlerReached.get(), "handler must still run when resolver throws");
                    assertNull(capturedTraceId.get(), "no traceId in MDC when resolver threw");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("no resolver (Optional.empty): behavior identical to pre-change baseline (existing tests unchanged)")
    void noResolverBehaviorUnchanged(VertxTestContext ctx) {
        // This test uses the default newMiddleware() which passes Optional.empty() — it mirrors
        // the baseline test "X-Request-Id is generated when absent and echoed in the response".
        CorrelationIngressMiddleware middleware = newMiddleware();
        AtomicReference<String> capturedTraceId = new AtomicReference<>();

        Router router = router(vertx, middleware, rc -> {
            capturedTraceId.set(MDCContexts.get(CorrelationMdcKeys.TRACE_ID));
        });

        startServer(vertx, router)
                .compose(port -> client.get(port, "127.0.0.1", "/test").send())
                .onComplete(ctx.succeeding((HttpResponse<Buffer> resp) -> {
                    assertEquals(200, resp.statusCode());
                    // No resolver means no trace enrichment — traceId must not appear in MDC.
                    assertNull(capturedTraceId.get(), "traceId must not be in MDC when no resolver is wired");
                    ctx.completeNow();
                }));
    }
}
