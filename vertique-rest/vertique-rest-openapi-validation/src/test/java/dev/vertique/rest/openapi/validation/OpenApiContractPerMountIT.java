// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration proofs for {@link OpenApiContractValidationStrategy}'s per-mount contract cache (T005):
 * two mounts with divergent {@code openapiPath}s validate independently over HTTP (TP-003), a mount
 * whose contract fails to load fails only that mount's own operations (TP-004), and the per-mount gate
 * continues a request on the request's own {@link Context} even when the contract was loaded on a
 * different one (TP-005, AR-003).
 *
 * <p>TP-003 and TP-004 share one router built in {@link #setUp}, binding a single {@code
 * OpenApiContractValidationStrategy} singleton to three mounts: {@code /a/*} (fixture A, the shared
 * {@code openapi-contract-strategy-test.json}), {@code /b/*} (fixture B, {@code
 * openapi-contract-mount-b.json}), and {@code /c/*} (a nonexistent contract path). On the untouched
 * strategy, binding the second (divergent) mount throws synchronously inside {@link #setUp} — the
 * bind is captured into {@link #mountStartupFailure} instead of being allowed to fail the {@code
 * @BeforeAll} callback itself (which would abort every test in the class, including TP-005, whose
 * own topology does not depend on this shared setup). Each of TP-003/TP-004 asserts on {@link
 * #mountStartupFailure} first, so its own red reason is visible in isolation.
 *
 * <p>TP-005 builds its own two independent {@link Vertx} instances (one to load the contract on, one
 * to serve HTTP on) entirely inside its own test method, so it is unaffected by the shared fixture's
 * setup outcome either way.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class OpenApiContractPerMountIT {

    private static final String CONTRACT_A_PATH = "openapi-contract-strategy-test.json";
    private static final String CONTRACT_B_PATH = "openapi-contract-mount-b.json";
    private static final String CONTRACT_MISSING_PATH = "openapi-contract-missing.json";
    private static final long ASYNC_TIMEOUT_SECONDS = 20;

    private static Vertx vertx;
    private static HttpServer server;
    private static WebClient client;
    private static int port;

    /**
     * Captures a {@code bindToMount} failure from {@link #setUp} instead of letting it propagate out of
     * the {@code @BeforeAll} callback. A thrown {@code @BeforeAll} exception would abort every test in
     * this class — including TP-005, which does not depend on this shared fixture — so each of
     * TP-003/TP-004 asserts on this field explicitly via {@link #assertMountsStartedCleanly()} to
     * surface its own red reason rather than a shared, misattributed setup failure.
     */
    private static RuntimeException mountStartupFailure;

    @BeforeAll
    static void setUp(Vertx v, VertxTestContext ctx) {
        vertx = v;
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));

        OpenApiContractValidationStrategy strategy = new OpenApiContractValidationStrategy(
                vertx, JaxRsConfig.builder().openapiPath(CONTRACT_A_PATH).build());

        MountMeta mountA = mountMeta("jaxrs:/a/*", "/a/*", CONTRACT_A_PATH);
        MountMeta mountB = mountMeta("jaxrs:/b/*", "/b/*", CONTRACT_B_PATH);
        MountMeta mountC = mountMeta("jaxrs:/c/*", "/c/*", CONTRACT_MISSING_PATH);

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        RuntimeException failure = null;
        try {
            strategy.bindToMount(mountA);
            strategy.bindToMount(mountB);
            strategy.bindToMount(mountC);

            JaxRsOperationDescriptor createWidgetOnA = op("POST", "/widgets", "createWidget");
            JaxRsOperationDescriptor createGadgetOnB = op("POST", "/gadgets", "createGadget");
            JaxRsOperationDescriptor createWidgetOnC = op("POST", "/widgets", "createWidget");

            Handler<RoutingContext> gateA = strategy.gateFor(createWidgetOnA, OperationSchemas.empty(), mountA)
                    .orElseThrow();
            Handler<RoutingContext> gateB = strategy.gateFor(createGadgetOnB, OperationSchemas.empty(), mountB)
                    .orElseThrow();
            Handler<RoutingContext> gateC = strategy.gateFor(createWidgetOnC, OperationSchemas.empty(), mountC)
                    .orElseThrow();

            router.post("/a/widgets")
                    .handler(gateA)
                    .handler(rc -> rc.response().setStatusCode(201).end("created"));
            router.post("/b/gadgets")
                    .handler(gateB)
                    .handler(rc -> rc.response().setStatusCode(201).end("created"));
            router.post("/c/widgets")
                    .handler(gateC)
                    .handler(rc -> rc.response().setStatusCode(201).end("created"));
        } catch (RuntimeException ex) {
            failure = ex;
        }
        mountStartupFailure = failure;

        // Minimal failure handler mirroring the framework REST error pipeline: RestValidationException
        // -> 400, anything else (including a mount's contract-load failure) -> 500.
        router.route().failureHandler(rc -> {
            Throwable f = rc.failure();
            int status = f instanceof RestValidationException ? 400 : 500;
            rc.response().setStatusCode(status).end(f == null ? "" : String.valueOf(f.getMessage()));
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

    /**
     * Fails the calling test with {@link #mountStartupFailure}'s message when the shared {@code
     * @BeforeAll} setup could not bind all three mounts. Called first by both TP-003 and TP-004 so a
     * setup-time failure surfaces as that test's own assertion failure, quoting the original exception.
     */
    private static void assertMountsStartedCleanly() {
        if (mountStartupFailure != null) {
            fail(
                    "mount startup failed (bindToMount threw before routes were installed): "
                            + mountStartupFailure.getMessage(),
                    mountStartupFailure);
        }
    }

    @Test
    @DisplayName("Two mounts with divergent OpenAPI contracts validate independently over HTTP")
    void eachMountValidatesAgainstItsOwnContract(VertxTestContext ctx) {
        assertMountsStartedCleanly();

        // Valid under A (requires "name") but invalid under B (requires "sku", additionalProperties:false).
        String bodyValidOnlyUnderA = new JsonObject().put("name", "gizmo").encode();

        post("/b/gadgets", bodyValidOnlyUnderA)
                .compose(bStatus -> {
                    assertEquals(400, bStatus, "a body valid only under A must be rejected by B's own contract");
                    return post("/a/widgets", bodyValidOnlyUnderA);
                })
                .onComplete(ctx.succeeding(aStatus -> ctx.verify(() -> {
                    assertTrue(
                            aStatus >= 200 && aStatus < 300,
                            "a body valid under A must pass A's own contract; was " + aStatus);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("A mount whose contract fails to load fails only that mount's own operations with a 500")
    void unloadableMountContractFailsOnlyThatMount(VertxTestContext ctx) {
        assertMountsStartedCleanly();

        String body = new JsonObject().put("name", "gizmo").encode();

        post("/c/widgets", body)
                .compose(cStatus -> {
                    assertTrue(
                            cStatus >= 500,
                            "an unloadable mount contract must fail its own operations with a 5xx; was " + cStatus);
                    return post("/a/widgets", body);
                })
                .onComplete(ctx.succeeding(aStatus -> ctx.verify(() -> {
                    assertTrue(
                            aStatus >= 200 && aStatus < 300,
                            "a mount whose own contract loaded fine must still succeed; was " + aStatus);
                    ctx.completeNow();
                })));
    }

    /**
     * TP-005 (AR-003): proves the 3-arg gate continues a request on the request's own {@link Context}
     * even though the contract was loaded on a completely different {@link Vertx} instance's context.
     *
     * <p>Mirrors the established foreign-context proof idiom in this codebase (see {@code
     * McpCompletedForeignContextAnchorIT}): the strategy is constructed and bound while genuinely
     * running on {@code loadingContext} (via {@link Context#runOnContext}), so the {@code Future} the
     * gate composes on is bound to that context. A separate {@code serverVertx} then serves the actual
     * HTTP request, so the request's own context is necessarily different from {@code loadingContext}.
     *
     * <p>Per Vert.x 5.1.6's {@code FutureBase.emitResult}, a future bound to a non-null context
     * dispatches every attached listener via that context's {@code execute(...)} whenever the attaching
     * thread is not already running on it — regardless of whether the future was already complete at
     * attachment time. The untouched strategy's gate directly {@code .compose(...)}s onto the cached
     * future, so today it inherits that foreign-context dispatch instead of continuing on the request's
     * own context.
     *
     * <p>A warm-up request is sent first so the cached future is guaranteed complete (a successful
     * response is only possible once the composed future has resolved) before the assertion request is
     * sent, isolating the already-complete-future case from the still-pending case.
     */
    @Test
    @DisplayName("The gate continues on the request context even when the contract was loaded elsewhere")
    void gateContinuesOnRequestContextWhenContractLoadedElsewhere() throws Exception {
        Vertx loadingVertx = Vertx.vertx();
        Vertx serverVertx = Vertx.vertx();
        HttpServer localServer = null;
        WebClient localClient = null;
        try {
            Context loadingContext = loadingVertx.getOrCreateContext();
            OpenApiContractValidationStrategy strategy = constructOnContext(loadingContext, loadingVertx);
            MountMeta mountD = mountMeta("jaxrs:/d/*", "/d/*", CONTRACT_A_PATH);
            bindOnContext(loadingContext, strategy, mountD);

            JaxRsOperationDescriptor createWidget = op("POST", "/widgets", "createWidget");
            Handler<RoutingContext> gate = strategy.gateFor(createWidget, OperationSchemas.empty(), mountD)
                    .orElseThrow();

            AtomicReference<Context> preGateContext = new AtomicReference<>();
            AtomicReference<Context> postGateContext = new AtomicReference<>();

            Router router = Router.router(serverVertx);
            router.route().handler(BodyHandler.create());
            router.post("/d/widgets")
                    .handler(rc -> {
                        preGateContext.set(Vertx.currentContext());
                        rc.next();
                    })
                    .handler(gate)
                    .handler(rc -> {
                        postGateContext.set(Vertx.currentContext());
                        rc.response().setStatusCode(201).end("created");
                    });
            router.route().failureHandler(rc -> {
                Throwable failure = rc.failure();
                int status = failure instanceof RestValidationException ? 400 : 500;
                rc.response().setStatusCode(status).end(failure == null ? "" : String.valueOf(failure.getMessage()));
            });

            localServer =
                    await(serverVertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
            int localPort = localServer.actualPort();
            localClient = WebClient.create(serverVertx);

            String body = new JsonObject().put("name", "gizmo").encode();

            // Warm-up: guarantees the cached future backing the gate has already completed before the
            // assertion request below — a 201 is only reachable once the gate's composed future resolved.
            HttpResponse<Buffer> warmup = await(localClient
                    .post(localPort, "127.0.0.1", "/d/widgets")
                    .putHeader("content-type", "application/json")
                    .sendBuffer(Buffer.buffer(body)));
            assertEquals(201, warmup.statusCode(), "warm-up request must succeed so the cached future is complete");

            preGateContext.set(null);
            postGateContext.set(null);
            HttpResponse<Buffer> second = await(localClient
                    .post(localPort, "127.0.0.1", "/d/widgets")
                    .putHeader("content-type", "application/json")
                    .sendBuffer(Buffer.buffer(body)));
            assertEquals(201, second.statusCode(), "the assertion request must also be a conforming request");

            assertNotNull(preGateContext.get(), "the pre-gate handler must have run");
            assertNotNull(postGateContext.get(), "the post-gate handler must have run");
            assertSame(
                    preGateContext.get(),
                    postGateContext.get(),
                    "the gate must continue on the request's own context, not re-dispatch elsewhere");
            assertNotSame(
                    loadingContext,
                    postGateContext.get(),
                    "the gate must not continue on the context that loaded the contract");
        } finally {
            closeOwned(localClient, localServer, serverVertx, loadingVertx);
        }
    }

    // --- TP-005 helpers ---

    /**
     * Constructs the strategy while genuinely running on {@code context} (via {@link
     * Context#runOnContext}), so the {@code Future} chain the constructor starts is bound to it.
     */
    private static OpenApiContractValidationStrategy constructOnContext(Context context, Vertx onVertx)
            throws Exception {
        CompletableFuture<OpenApiContractValidationStrategy> constructed = new CompletableFuture<>();
        context.runOnContext(ignored -> constructed.complete(new OpenApiContractValidationStrategy(
                onVertx, JaxRsConfig.builder().openapiPath(CONTRACT_A_PATH).build())));
        return constructed.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Runs {@code strategy.bindToMount(mount)} while genuinely running on {@code context}. */
    private static void bindOnContext(Context context, OpenApiContractValidationStrategy strategy, MountMeta mount)
            throws Exception {
        CompletableFuture<Void> bound = new CompletableFuture<>();
        context.runOnContext(ignored -> {
            strategy.bindToMount(mount);
            bound.complete(null);
        });
        bound.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Closes the server and client first (joined), then both owned {@link Vertx} instances, mirroring
     * this codebase's owned-{@code Vertx} teardown join pattern (never close {@code Vertx} while a
     * request could still be in flight). Null-guards every field so a test that failed before assigning
     * one still tears down cleanly.
     */
    private static void closeOwned(WebClient client, HttpServer server, Vertx serverVertx, Vertx loadingVertx)
            throws Exception {
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        CompletableFuture<Void> done = new CompletableFuture<>();
        serverClose.onComplete(ar -> {
            if (client != null) {
                client.close();
            }
            Future<Void> serverVertxClose = serverVertx.close();
            Future<Void> loadingVertxClose = loadingVertx.close();
            Future.join(serverVertxClose, loadingVertxClose).onComplete(joined -> done.complete(null));
        });
        done.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    // --- shared helpers ---

    private static Future<Integer> post(String path, String jsonBody) {
        return client.post(port, "127.0.0.1", path)
                .putHeader("content-type", "application/json")
                .sendBuffer(Buffer.buffer(jsonBody))
                .map(resp -> resp.statusCode());
    }

    private static MountMeta mountMeta(String mountId, String mountPath, String openapiPath) {
        return new MountMeta(mountId, mountPath, openapiPath, Set.of());
    }

    private static JaxRsOperationDescriptor op(String method, String route, String operationId) {
        return StubDescriptors.builder()
                .operationId(operationId)
                .httpMethod(method)
                .routeTemplate(route)
                .build();
    }
}
