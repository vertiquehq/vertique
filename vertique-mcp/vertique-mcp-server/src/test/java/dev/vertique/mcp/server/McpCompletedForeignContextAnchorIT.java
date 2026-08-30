// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.interceptor.McpRequestContext;
import dev.vertique.mcp.interceptor.McpRequestInterceptor;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * RED proof for repair task R47 (phase-exit Critical C1): {@link McpRequestDispatcher}'s private
 * {@code anchoredOnContext(Future, Context)} helper (around line 610) treats an
 * already-{@code isComplete()} future exactly like a {@code null} owning-context — an identity
 * no-op that returns the future unchanged, without ever re-anchoring it onto the request-owning
 * context.
 *
 * <p>That identity-no-op reasoning silently assumes a completed future carries no context binding
 * of its own. Per Vert.x 5.1.6's {@code FutureBase.emitResult} (see {@code
 * io.vertx.core.impl.future.FutureBase#emitResult}), a future that <em>is</em> bound to a
 * non-{@code null} context — for instance one minted via {@code
 * ((ContextInternal) context).promise()} — dispatches every listener attached to it via {@code
 * context.execute(...)} whenever the attaching thread is not already running on that context,
 * regardless of whether the future was already complete at attachment time. A completed future
 * that happens to be bound to some <em>other</em>, foreign Vert.x context therefore escapes the
 * anchor entirely: {@code .compose(...)}'d directly, its continuation — and everything chained
 * after it, including {@link McpCompletionCoordinator}'s context-confined settlement latches —
 * runs on that foreign event loop instead of the request-owning one.
 *
 * <p>This proof mints exactly such a future: a second, independent {@link Vertx} instance's own
 * context is used to create a {@link Promise} via {@code ((ContextInternal) foreignContext).promise()}
 * and complete it — both while genuinely running on {@code foreignContext} — before the HTTP
 * request is ever issued, so the future handed to the first request-interceptor is both {@code
 * isComplete() == true} and bound to a context the dispatcher never owns. The second, ordered
 * interceptor then probes {@link Vertx#currentContext()} the instant its own {@code beforeRequest}
 * is invoked — which, per T017/T016's ordering guarantee, only ever happens inside the
 * continuation chained directly onto the first interceptor's returned future.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpCompletedForeignContextAnchorIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final long ASYNC_TIMEOUT_SECONDS = 10;

    private final Vertx vertx = Vertx.vertx();
    private final Vertx foreignVertx = Vertx.vertx();

    private Fixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    @AfterEach
    void tearDown() throws Exception {
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future.join(serverClose, clientClose).onComplete(joined -> {
            Future<Void> vertxClose = vertx.close();
            Future<Void> foreignVertxClose = foreignVertx.close();
            Future.join(vertxClose, foreignVertxClose).onComplete(vertxJoined -> {
                Throwable failure;
                if (joined.failed()) {
                    failure = joined.cause();
                } else if (vertxJoined.failed()) {
                    failure = vertxJoined.cause();
                } else {
                    failure = null;
                }
                if (failure != null) {
                    closed.completeExceptionally(failure);
                } else {
                    closed.complete(null);
                }
            });
        });
        closed.get(10, TimeUnit.SECONDS);
        fixture = null;
        server = null;
        rawClient = null;
        client = null;
    }

    @Test
    @DisplayName("R47 C1: a completed-but-foreign-context-bound interceptor future must still be "
            + "anchored onto the request's own Vert.x context, never dispatched on the foreign one")
    void shouldAnchorAnAlreadyCompleteForeignContextBoundFutureOntoTheRequestOwningContext() throws Exception {
        Context foreignContext = foreignVertx.getOrCreateContext();
        Future<Void> foreignCompletedFuture = completedFutureBoundTo(foreignContext);
        assertThat(foreignCompletedFuture.isComplete())
                .as("SETUP: the foreign-bound future must already be complete before the dispatcher "
                        + "ever composes onto it — otherwise this proof would exercise the pending-future "
                        + "redispatch path instead of the isComplete() fast path under repair")
                .isTrue();

        fixture = Fixture.start(vertx, foreignCompletedFuture);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        Future<HttpResponse<Buffer>> responseFuture = client.post(fixture.port(), LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/list")
                .putHeader("Mcp-Name", "tools/list")
                .sendBuffer(toolsListBody());

        HttpResponse<Buffer> response = await(responseFuture);
        assertThat(fixture.observer().awaitSettlement()).isTrue();

        assertThat(fixture.observer().expectedContext())
                .as("NON-VACUITY: the request must genuinely have opened its lifecycle observation on a "
                        + "real Vert.x context, or the equality assertion below would be trivially "
                        + "satisfiable by two null references")
                .isNotNull();
        assertThat(foreignContext)
                .as("NON-VACUITY: the minted future's context must genuinely be foreign to the "
                        + "request-owning context, or a passing probe below would prove nothing")
                .isNotEqualTo(fixture.observer().expectedContext());

        assertThat(fixture.probeInterceptor().observedContext())
                .as("DECISIVE: the second interceptor's beforeRequest must run on the same "
                        + "request-owning Vert.x context the request's lifecycle observation opened on, "
                        + "not on the foreign context the first interceptor's already-complete future "
                        + "was bound to")
                .isEqualTo(fixture.observer().expectedContext());

        assertThat(response.statusCode()).isEqualTo(200);
        fixture.observer().assertExactlyOneTerminalThenOneCompletion();
    }

    /**
     * Mints a {@link Promise} genuinely bound to {@code foreignContext} (via {@code
     * ((ContextInternal) foreignContext).promise()}) and completes it — both while actually running
     * on {@code foreignContext}, via {@link Context#runOnContext} — then hands the resulting,
     * already-complete future back to the caller once completion is confirmed.
     */
    private static Future<Void> completedFutureBoundTo(Context foreignContext) throws Exception {
        CompletableFuture<Future<Void>> minted = new CompletableFuture<>();
        foreignContext.runOnContext(ignored -> {
            Promise<Void> promise = ((ContextInternal) foreignContext).promise();
            promise.complete(null);
            minted.complete(promise.future());
        });
        return minted.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static Buffer toolsListBody() {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/list")
                .put("params", new JsonObject().put("_meta", meta))
                .toBuffer();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Returns exactly the pre-minted, already-complete, foreign-context-bound future handed to it. */
    private static final class ForeignCompletedFutureInterceptor implements McpRequestInterceptor {
        private final Future<Void> foreignCompletedFuture;

        private ForeignCompletedFutureInterceptor(Future<Void> foreignCompletedFuture) {
            this.foreignCompletedFuture = foreignCompletedFuture;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public Future<Void> beforeRequest(McpRequestContext context) {
            return foreignCompletedFuture;
        }
    }

    /** Records the Vert.x context (or its absence) its own {@code beforeRequest} is invoked on. */
    private static final class ProbeRequestInterceptor implements McpRequestInterceptor {
        private volatile Context observedContext;

        @Override
        public int priority() {
            return 1;
        }

        @Override
        public Future<Void> beforeRequest(McpRequestContext context) {
            observedContext = Vertx.currentContext();
            return Future.succeededFuture();
        }

        private Context observedContext() {
            return observedContext;
        }
    }

    /** Records the request-owning context its own {@code open} runs on, and this request's settlement. */
    private static final class RecordingObserver implements McpRequestLifecycleObserver, McpRequestObservation {
        private volatile Context expectedContext;
        private final CountDownLatch settlement = new CountDownLatch(2);
        private final List<String> order = new CopyOnWriteArrayList<>();
        private final AtomicInteger terminalCount = new AtomicInteger();
        private final AtomicInteger completionCount = new AtomicInteger();

        @Override
        public McpRequestObservation open(Instant startedAt) {
            expectedContext = Vertx.currentContext();
            return this;
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            terminalCount.incrementAndGet();
            order.add("terminal");
            settlement.countDown();
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            completionCount.incrementAndGet();
            order.add("completed");
            settlement.countDown();
        }

        private Context expectedContext() {
            return expectedContext;
        }

        private boolean awaitSettlement() throws InterruptedException {
            return settlement.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private void assertExactlyOneTerminalThenOneCompletion() {
            assertThat(terminalCount.get()).isOne();
            assertThat(completionCount.get()).isOne();
            assertThat(order).containsExactly("terminal", "completed");
        }
    }

    private record AnonymousIdentityResolver() implements SecurityIdentityResolver {
        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
        }
    }

    private static final ContextHolder NO_OP_CONTEXT_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return () -> {};
        }
    };

    private static final class RecordingSecurityRuntime implements SecurityRuntime {
        private volatile SecurityContext bound;

        @Override
        public SecurityContext current() {
            return bound;
        }

        @Override
        public ContextHolder.Scope bindCurrent(SecurityContext context) {
            bound = context;
            return () -> {};
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }

    /** One real port-0 mount, no tools, and the two ordered request-interceptors under proof. */
    private static final class Fixture {
        private final HttpServer server;
        private final int port;
        private final ForeignCompletedFutureInterceptor foreignInterceptor;
        private final ProbeRequestInterceptor probeInterceptor = new ProbeRequestInterceptor();
        private final RecordingObserver observer = new RecordingObserver();

        private Fixture(Vertx vertx, Future<Void> foreignCompletedFuture) throws Exception {
            this.foreignInterceptor = new ForeignCompletedFutureInterceptor(foreignCompletedFuture);
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .build();
            McpToolRegistry registry = McpToolRegistry.build(Set.of());
            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
            McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                    Optional.empty(),
                    Optional.empty(),
                    Set.of(),
                    new SecurityEventEmitter(Set.of()),
                    NO_OP_CONTEXT_HOLDER,
                    securityRuntime,
                    Optional.empty()));
            HttpConfig httpConfig = HttpConfig.builder().idleTimeoutSeconds(60).build();
            McpRouterMount mount = new McpRouterMount(
                    config,
                    new McpServerConfigValidator(),
                    new McpRequestDispatcher(
                            config,
                            securityRuntime,
                            Set.of(observer),
                            Set.of(),
                            Set.of(foreignInterceptor, probeInterceptor),
                            Set.of(),
                            httpConfig,
                            registry,
                            policyEnforcer,
                            NO_OP_CONTEXT_HOLDER,
                            new CorrelationContextFactory(Optional.empty())),
                    Set.of(),
                    identityResolution(securityRuntime),
                    httpConfig,
                    registry);
            Router router = Router.router(vertx);
            router.route().handler(new RequestContextLifecycle());
            router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
            this.server = await(vertx.createHttpServer().requestHandler(router).listen(0, LOOPBACK));
            this.port = server.actualPort();
        }

        static Fixture start(Vertx vertx, Future<Void> foreignCompletedFuture) throws Exception {
            return new Fixture(vertx, foreignCompletedFuture);
        }

        HttpServer server() {
            return server;
        }

        int port() {
            return port;
        }

        ProbeRequestInterceptor probeInterceptor() {
            return probeInterceptor;
        }

        RecordingObserver observer() {
            return observer;
        }

        private static IdentityResolutionMiddleware identityResolution(SecurityRuntime securityRuntime) {
            return new IdentityResolutionMiddleware(
                    Set.of(new AnonymousIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    new SecurityEventEmitter(Set.of()),
                    securityRuntime,
                    NO_OP_CONTEXT_HOLDER);
        }

        private static <T> T await(Future<T> future) throws Exception {
            return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
}
