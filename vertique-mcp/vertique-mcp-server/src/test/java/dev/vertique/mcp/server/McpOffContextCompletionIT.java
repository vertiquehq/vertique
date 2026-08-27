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
 * RED proof for repair task R32 defect 1: the dispatcher composes an application-supplied {@link
 * McpRequestInterceptor#beforeRequest} future without ever re-anchoring the chain's continuation
 * back onto the request-owning Vert.x context.
 *
 * <p>{@link McpRequestInterceptor#beforeRequest} and {@link
 * dev.vertique.mcp.interceptor.McpToolInterceptor#beforeInvocation} are both documented as running
 * "on the request's owning Vert.x context" for every interceptor in the ordered chain — not merely
 * the first one. Today the dispatcher's {@code runRequestInterceptors} composes each interceptor's
 * returned future directly ({@code outcome.compose(ignored -> runRequestInterceptors(index + 1,
 * requestContext))}), so once an application supplies a future backed by a plain, context-unaware
 * {@link Promise} completed from outside Vert.x, every later interceptor's continuation runs on
 * whichever raw thread happened to complete it, not on the context the request actually arrived on.
 *
 * <p>This proof registers two ordered request-interceptors: the first ({@link
 * GatedRequestInterceptor}) hands back a future released only from a plain {@code new Thread}, once
 * the dispatcher has already attached its continuation (T017/T016's own ordering guarantee: {@code
 * beforeRequest} always returns, and the caller always calls {@code .compose(...)} on the very next
 * statement, before any other thread can run); the second ({@link ProbeRequestInterceptor}) records
 * {@link Vertx#currentContext()} the instant its own {@code beforeRequest} is invoked — which only
 * ever happens inside that same continuation. Comparing the probed context against the context the
 * request's own lifecycle observation opened on is therefore a direct proof of defect 1.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpOffContextCompletionIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final long ASYNC_TIMEOUT_SECONDS = 10;

    private final Vertx vertx = Vertx.vertx();

    private Fixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    @AfterEach
    void tearDown() throws Exception {
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future.join(serverClose, clientClose).onComplete(joined -> vertx.close().onComplete(vertxResult -> {
            Throwable failure = joined.failed() ? joined.cause() : vertxResult.cause();
            if (failure != null) {
                closed.completeExceptionally(failure);
            } else {
                closed.complete(null);
            }
        }));
        closed.get(10, TimeUnit.SECONDS);
        fixture = null;
        server = null;
        rawClient = null;
        client = null;
    }

    @Test
    @DisplayName("R32 defect 1: a later request-interceptor's continuation must run on the request's "
            + "own Vert.x context, never on the raw thread that completed an earlier one's future")
    void shouldRunTheRequestInterceptorChainContinuationOnTheRequestOwningContext() throws Exception {
        fixture = Fixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        Future<HttpResponse<Buffer>> responseFuture = client.post(fixture.port(), LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/list")
                .putHeader("Mcp-Name", "tools/list")
                .sendBuffer(toolsListBody());

        fixture.gatedInterceptor().awaitInvoked();
        fixture.gatedInterceptor().releaseFromNonVertxThread();

        HttpResponse<Buffer> response = await(responseFuture);
        assertThat(fixture.observer().awaitSettlement()).isTrue();

        assertThat(fixture.observer().expectedContext())
                .as("NON-VACUITY: the request must genuinely have opened its lifecycle observation on a "
                        + "real Vert.x context, or the equality assertion below would be trivially "
                        + "satisfiable by two null references")
                .isNotNull();
        assertThat(fixture.probeInterceptor().observedContext())
                .as("DECISIVE: the second interceptor's beforeRequest must run on the same "
                        + "request-owning Vert.x context the request's lifecycle observation opened on, "
                        + "not on the raw, non-Vert.x thread that completed the first interceptor's "
                        + "future")
                .isEqualTo(fixture.observer().expectedContext());

        assertThat(response.statusCode()).isEqualTo(200);
        fixture.observer().assertExactlyOneTerminalThenOneCompletion();
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

    /** Permits after a gate released only from a plain, non-Vert.x thread. */
    private static final class GatedRequestInterceptor implements McpRequestInterceptor {
        private final CompletableFuture<Void> invoked = new CompletableFuture<>();
        private final Promise<Void> gate = Promise.promise();

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public Future<Void> beforeRequest(McpRequestContext context) {
            invoked.complete(null);
            return gate.future();
        }

        private void awaitInvoked() throws Exception {
            invoked.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        /**
         * Releases the gate from a plain {@code new Thread} — never a Vert.x-managed thread — only
         * once the caller has confirmed this interceptor was already invoked, which the dispatcher's
         * own single-threaded, non-yielding sequence (invoke, then attach the continuation) already
         * guarantees happened before this method is even called.
         */
        private void releaseFromNonVertxThread() {
            Thread releaser = new Thread(() -> gate.complete(null), "off-context-interceptor-release");
            releaser.start();
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
        private final GatedRequestInterceptor gatedInterceptor = new GatedRequestInterceptor();
        private final ProbeRequestInterceptor probeInterceptor = new ProbeRequestInterceptor();
        private final RecordingObserver observer = new RecordingObserver();

        private Fixture(Vertx vertx) throws Exception {
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
                            Set.of(gatedInterceptor, probeInterceptor),
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

        static Fixture start(Vertx vertx) throws Exception {
            return new Fixture(vertx);
        }

        HttpServer server() {
            return server;
        }

        int port() {
            return port;
        }

        GatedRequestInterceptor gatedInterceptor() {
            return gatedInterceptor;
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
