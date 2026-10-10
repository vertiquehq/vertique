// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.interceptor.McpRequestContext;
import dev.vertique.mcp.interceptor.McpRequestInterceptor;
import dev.vertique.mcp.lifecycle.McpCompletionScope;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpOutcome;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.lifecycle.McpToolInputObservation;
import dev.vertique.mcp.lifecycle.McpToolOutputObservation;
import dev.vertique.mcp.lifecycle.McpToolValueObservation;
import dev.vertique.mcp.lifecycle.McpTransportOutcome;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.RouteAuthHandler;
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
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.StreamResetException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Real-transport proof of the per-mount request deadline {@code mcp.requestDeadlineMs}, on h2c and
 * HTTP/1.1, with the connection-level idle timer armed far above every observation window.
 *
 * <p>The rows pin that a hung request is reclaimed at the deadline with one cancelled
 * {@code TIMEOUT} terminal that keeps the request's identity while sibling streams and the
 * connection stay healthy, that every later stage and a late tool result are fenced, that a
 * committed streaming response is reset instead of silently truncated, that a stalled upload is
 * answered before MCP begins the request, and that requests that finish in time are unaffected.
 */
class McpRequestDeadlineIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String SCHEME_NAME = "request-deadline-scheme";
    private static final String HELD_TOOL = "request.deadline.held";
    private static final String PLAIN_TOOL = "request.deadline.plain";
    private static final String STREAMED_TOOL = "request.deadline.streamed";
    private static final String DISCOVER_METHOD = "server/discover";

    /** The request deadline under test: short, but far above scheduling jitter. */
    private static final long DEADLINE_MS = 1_000;

    /** Sibling discover cadence, fast enough that the connection is never idle for a full second. */
    private static final long SIBLING_INTERVAL_MS = 100;

    /** How long a negative observation is held open after the request was settled. */
    private static final Duration CONFIRMATION_WINDOW = Duration.ofMillis(800);

    private static final long ASYNC_TIMEOUT_SECONDS = 15;

    private final Vertx vertx = Vertx.vertx();
    private final List<Throwable> uncaught = new CopyOnWriteArrayList<>();

    private Fixture fixture;
    private HttpClient client;

    @BeforeEach
    void captureUncaughtFailures() {
        vertx.exceptionHandler(uncaught::add);
    }

    @AfterEach
    void tearDown() throws Exception {
        Future<Void> serverClose = fixture != null ? fixture.server().close() : Future.succeededFuture();
        Future<Void> clientClose = client != null ? client.close() : Future.succeededFuture();
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future.join(serverClose, clientClose).onComplete(joined -> vertx.close().onComplete(vertxResult -> {
            Throwable failure = joined.failed() ? joined.cause() : vertxResult.cause();
            if (failure != null) {
                closed.completeExceptionally(failure);
            } else {
                closed.complete(null);
            }
        }));
        closed.get(15, TimeUnit.SECONDS);
        fixture = null;
        client = null;
        assertThat(uncaught)
                .as("no failure escaped to the context exception handler")
                .isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // A hung request is reclaimed at the deadline
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a hung tool is answered 504 at the deadline on a busy h2c connection: one cancelled "
            + "timeout terminal with the tool identity, the tool cancelled, siblings healthy")
    void hungToolOnH2cIsReclaimedWhileSiblingsStayHealthy() throws Exception {
        assertHungToolIsReclaimed(HttpVersion.HTTP_2);
    }

    @Test
    @DisplayName("a hung tool is answered 504 at the deadline on HTTP/1.1 with one cancelled timeout "
            + "terminal and the tool cancelled")
    void hungToolOnHttp1IsReclaimed() throws Exception {
        assertHungToolIsReclaimed(HttpVersion.HTTP_1_1);
    }

    private void assertHungToolIsReclaimed(HttpVersion version) throws Exception {
        start(version, DEADLINE_MS, Set.of());
        long startedNanos = System.nanoTime();
        CompletableFuture<Reply> hung = callTool(HELD_TOOL);
        fixture.held().awaitInvoked();
        Siblings siblings = new Siblings();
        siblings.start();

        Reply reply = hung.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();
        siblings.stop();

        assertThat(reply.failure()).isNull();
        assertThat(reply.status()).isEqualTo(504);
        assertThat(reply.body()).contains("\"error\"").contains("-32603");
        assertThat(elapsedMs).isBetween(DEADLINE_MS - 50, DEADLINE_MS + 3_000);
        assertThat(siblings.failures()).as("sibling streams stay healthy").isEmpty();
        assertThat(siblings.attempted()).isGreaterThanOrEqualTo(1);
        if (version == HttpVersion.HTTP_2) {
            assertThat(fixture.connections())
                    .as("the hung request and siblings share one connection")
                    .hasSize(1);
        }
        assertThat(session.terminals()).hasSize(1);
        McpRequestTerminalEvent terminal = session.terminals().get(0);
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.CANCELLED);
        assertThat(terminal.errorType()).isEqualTo(McpErrorType.TIMEOUT);
        assertThat(terminal.method()).isEqualTo(McpMethod.TOOLS_CALL);
        assertThat(terminal.toolName()).isEqualTo(HELD_TOOL);
        assertThat(terminal.security())
                .as("the identity established before the deadline is kept")
                .isNotNull();
        assertThat(session.completions()).hasSize(1);
        assertThat(session.completions().get(0).transportOutcome()).isEqualTo(McpTransportOutcome.RESET);
        assertThat(session.completions().get(0).responseCommitted()).isTrue();
        assertThat(fixture.held().signal().isCancelled())
                .as("the tool observes cancellation")
                .isTrue();

        fixture.held().release();
        drain(session.context());
        assertThat(session.terminals())
                .as("a late tool result publishes nothing")
                .hasSize(1);
        assertThat(session.completions()).hasSize(1);
        assertThat(session.outputs()).isZero();
        assertThat(uncaught).isEmpty();
    }

    @Test
    @DisplayName("a late release of a hung request interceptor is fenced on h2c: the tool is never run "
            + "for a request the deadline already answered")
    void lateInterceptorReleaseIsFencedOnH2c() throws Exception {
        assertLateInterceptorReleaseIsFenced(HttpVersion.HTTP_2);
    }

    @Test
    @DisplayName("a late release of a hung request interceptor is fenced on HTTP/1.1: the tool is never "
            + "run for a request the deadline already answered")
    void lateInterceptorReleaseIsFencedOnHttp1() throws Exception {
        assertLateInterceptorReleaseIsFenced(HttpVersion.HTTP_1_1);
    }

    private void assertLateInterceptorReleaseIsFenced(HttpVersion version) throws Exception {
        HoldingInterceptor interceptor = new HoldingInterceptor();
        start(version, DEADLINE_MS, Set.of(interceptor));

        Reply reply = callTool(PLAIN_TOOL).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();
        assertThat(reply.status()).isEqualTo(504);
        assertThat(interceptor.calls()).isOne();
        assertThat(session.terminals().get(0).errorType()).isEqualTo(McpErrorType.TIMEOUT);

        interceptor.release();
        drain(session.context());

        assertThat(fixture.plain().awaitInvoked(CONFIRMATION_WINDOW))
                .as("the tool must never run for a settled request")
                .isFalse();
        assertThat(fixture.plain().prepares()).isZero();
        assertThat(session.terminals()).hasSize(1);
        assertThat(session.completions()).hasSize(1);
        assertThat(uncaught).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // A committed streaming response is cut loudly
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("on a committed SSE response on h2c the deadline resets the stream with CANCEL, so the "
            + "client sees an error rather than a truncated 200, and a cooperative tool stops")
    void committedStreamIsResetOnH2c() throws Exception {
        start(HttpVersion.HTTP_2, DEADLINE_MS, Set.of());

        Reply reply = callTool(STREAMED_TOOL, true).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(reply.status()).as("the head was committed before the cut").isEqualTo(200);
        assertThat(reply.failure()).isInstanceOf(StreamResetException.class);
        assertThat(((StreamResetException) reply.failure()).getCode()).isEqualTo(0x8);
        assertCommittedStreamWasSettledAsTimeout(session);
    }

    @Test
    @DisplayName("on a committed SSE response on HTTP/1.1 the deadline closes the connection, so the "
            + "client sees an error rather than a truncated 200, and a cooperative tool stops")
    void committedStreamIsClosedOnHttp1() throws Exception {
        start(HttpVersion.HTTP_1_1, DEADLINE_MS, Set.of());

        Reply reply = callTool(STREAMED_TOOL, true).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(reply.failure())
                .as("the client sees a broken stream, not a clean end")
                .isNotNull();
        assertCommittedStreamWasSettledAsTimeout(session);
    }

    private void assertCommittedStreamWasSettledAsTimeout(Session session) throws Exception {
        assertThat(session.terminals()).hasSize(1);
        McpRequestTerminalEvent terminal = session.terminals().get(0);
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.CANCELLED);
        assertThat(terminal.errorType()).isEqualTo(McpErrorType.TIMEOUT);
        assertThat(session.completions()).hasSize(1);
        assertThat(session.completions().get(0).responseCommitted()).isTrue();
        assertThat(fixture.streamed().awaitStoppedEarly(Duration.ofSeconds(ASYNC_TIMEOUT_SECONDS)))
                .as("a tool that polls the cancellation signal stops")
                .isTrue();
        assertThat(fixture.streamed().ticks()).isLessThan(Tool.PROGRESS_TICKS);
    }

    // ---------------------------------------------------------------------------------------------
    // Before MCP has begun the request, and requests that finish in time
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a stalled h2c upload is answered 504 with the bounded JSON-RPC error and its stream is "
            + "reset, before MCP begins the request and opens any lifecycle observation")
    void stalledUploadOnH2cIsAnsweredAndTheStreamIsReclaimed() throws Exception {
        start(HttpVersion.HTTP_2, DEADLINE_MS, Set.of());

        StalledUpload upload = stalledUpload();

        assertThat(upload.reply().failure()).isNull();
        assertThat(upload.reply().status()).isEqualTo(504);
        assertThat(upload.reply().body()).contains("-32603");
        assertThat(upload.lateWriteFailure())
                .as("the server aborted the inbound side, so the trickling client cannot hold the stream")
                .isNotNull();
        assertThat(fixture.recorder().sessionCount()).isZero();
        assertThat(uncaught).isEmpty();
    }

    @Test
    @DisplayName("a stalled HTTP/1.1 upload is answered 504 with the bounded JSON-RPC error and the "
            + "connection is closed, before MCP begins the request")
    void stalledUploadOnHttp1IsAnsweredAndTheConnectionIsClosed() throws Exception {
        start(HttpVersion.HTTP_1_1, DEADLINE_MS, Set.of());

        StalledUpload upload = stalledUpload();

        assertThat(upload.reply().failure()).isNull();
        assertThat(upload.reply().status()).isEqualTo(504);
        assertThat(upload.reply().body()).contains("-32603");
        assertThat(upload.lateWriteFailure())
                .as("the connection was closed after the answer")
                .isNotNull();
        assertThat(fixture.recorder().sessionCount()).isZero();
        assertThat(uncaught).isEmpty();
    }

    @Test
    @DisplayName("a hung identity resolver is answered 504 with no security snapshot, and its late "
            + "release runs neither the request interceptors nor the tool")
    void lateIdentityResolutionIsFenced() throws Exception {
        HoldingInterceptor interceptor = new HoldingInterceptor();
        HoldingIdentityResolver resolver = new HoldingIdentityResolver();
        start(HttpVersion.HTTP_2, DEADLINE_MS, Set.of(interceptor), resolver);

        Reply reply = callTool(PLAIN_TOOL).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(reply.status()).isEqualTo(504);
        assertThat(session.terminals()).hasSize(1);
        assertThat(session.terminals().get(0).errorType()).isEqualTo(McpErrorType.TIMEOUT);
        assertThat(session.terminals().get(0).security())
                .as("the deadline expired before identity establishment")
                .isNull();

        resolver.release();
        drain(session.context());
        quietFor(CONFIRMATION_WINDOW);

        assertThat(interceptor.calls())
                .as("no request interceptor ran after the answer")
                .isZero();
        assertThat(fixture.plain().prepares()).isZero();
        assertThat(session.terminals()).hasSize(1);
        assertThat(session.completions()).hasSize(1);
        assertThat(uncaught).isEmpty();
    }

    @Test
    @DisplayName("a request that finishes before the deadline is unaffected and the deadline never "
            + "settles it a second time")
    void requestFinishedInTimeIsUnaffected() throws Exception {
        start(HttpVersion.HTTP_2, DEADLINE_MS, Set.of());

        Reply reply = callTool(PLAIN_TOOL).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();
        quietFor(Duration.ofMillis(DEADLINE_MS * 2));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(session.terminals()).hasSize(1);
        assertThat(session.terminals().get(0).outcome()).isEqualTo(McpOutcome.SUCCESS);
        assertThat(session.completions()).hasSize(1);
        assertThat(session.completions().get(0).transportOutcome()).isEqualTo(McpTransportOutcome.WRITTEN);
        assertThat(fixture.plain().signal().isCancelled()).isFalse();
    }

    @Test
    @DisplayName("without a configured deadline a hung tool stays open on the same busy connection")
    void withoutADeadlineTheHungToolIsNotReclaimed() throws Exception {
        start(HttpVersion.HTTP_2, 0, Set.of());
        CompletableFuture<Reply> hung = callTool(HELD_TOOL);
        fixture.held().awaitInvoked();

        boolean reclaimed = awaitDone(hung, Duration.ofMillis(DEADLINE_MS * 3));

        assertThat(reclaimed).as("no deadline is armed by default").isFalse();
        assertThat(fixture.recorder().sessionCount()).isOne();
        assertThat(fixture.held().signal().isCancelled()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------------

    private void start(HttpVersion version, long deadlineMs, Set<McpRequestInterceptor> requestInterceptors)
            throws Exception {
        start(version, deadlineMs, requestInterceptors, new AnonymousIdentityResolver());
    }

    private void start(
            HttpVersion version,
            long deadlineMs,
            Set<McpRequestInterceptor> requestInterceptors,
            SecurityIdentityResolver identityResolver)
            throws Exception {
        fixture = new Fixture(vertx, deadlineMs, requestInterceptors, identityResolver);
        client = vertx.createHttpClient(
                new HttpClientOptions().setProtocolVersion(version).setHttp2ClearTextUpgrade(false));
    }

    private CompletableFuture<Reply> callTool(String toolName) {
        return callTool(toolName, false);
    }

    private CompletableFuture<Reply> callTool(String toolName, boolean withProgressToken) {
        return exchange(options(toolName), toolCallBody(toolName, withProgressToken));
    }

    /**
     * Sends {@code body} and reads the whole response. The response and its body are observed before
     * the request is ended, so no body buffer can arrive unobserved; a failed body read keeps the
     * status the head carried.
     */
    private CompletableFuture<Reply> exchange(RequestOptions options, Buffer body) {
        CompletableFuture<Reply> done = new CompletableFuture<>();
        client.request(options).onComplete(created -> {
            if (created.failed()) {
                done.complete(new Reply(-1, null, created.cause()));
                return;
            }
            HttpClientRequest request = created.result();
            request.response().onComplete(responded -> {
                if (responded.failed()) {
                    done.complete(new Reply(-1, null, responded.cause()));
                    return;
                }
                HttpClientResponse response = responded.result();
                int status = response.statusCode();
                response.body()
                        .onComplete(read -> done.complete(
                                read.succeeded()
                                        ? new Reply(status, read.result().toString(), null)
                                        : new Reply(status, null, read.cause())));
            });
            request.end(body);
        });
        return done;
    }

    /** Sends a tools/call and leaves it unanswered, returning the request so the test can abort it. */
    private HttpClientRequest openToolCall(String toolName) throws Exception {
        HttpClientRequest request = await(client.request(options(toolName)));
        // The response is intentionally never read: the test aborts the request or connection.
        request.response().onComplete(ignored -> {});
        request.end(toolCallBody(toolName, false));
        return request;
    }

    private RequestOptions options(String toolName) {
        return new RequestOptions()
                .setMethod(HttpMethod.POST)
                .setHost(LOOPBACK)
                .setPort(fixture.port())
                .setURI(REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", toolName);
    }

    private static Buffer toolCallBody(String toolName, boolean withProgressToken) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        if (withProgressToken) {
            meta.put("progressToken", "delayed-write-progress");
        }
        JsonObject params =
                new JsonObject().put("_meta", meta).put("name", toolName).put("arguments", new JsonObject());
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put("params", params)
                .toBuffer();
    }

    private static boolean awaitDone(CompletableFuture<?> future, Duration window) throws Exception {
        try {
            future.get(window.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException notDone) {
            return false;
        }
    }

    /** Holds a negative observation open for {@code window}, driven by a Vert.x timer. */
    private void quietFor(Duration window) throws Exception {
        CompletableFuture<Void> elapsed = new CompletableFuture<>();
        vertx.setTimer(window.toMillis(), ignored -> elapsed.complete(null));
        elapsed.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void drain(Context requestContext) throws Exception {
        CompletableFuture<Void> marker = new CompletableFuture<>();
        requestContext.runOnContext(ignored -> marker.complete(null));
        marker.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * A tools/call whose body is half sent and never finished, until the response arrives; then
     * keeps writing until the server has aborted its inbound side, and returns that failure.
     */
    private StalledUpload stalledUpload() throws Exception {
        CompletableFuture<Reply> done = new CompletableFuture<>();
        CompletableFuture<HttpClientRequest> request = new CompletableFuture<>();
        Buffer full = toolCallBody(HELD_TOOL, false);
        Buffer half = full.getBuffer(0, full.length() / 2);
        client.request(options(HELD_TOOL)).onComplete(created -> {
            if (created.failed()) {
                done.complete(new Reply(-1, null, created.cause()));
                return;
            }
            HttpClientRequest stalled = created.result();
            stalled.setChunked(true);
            request.complete(stalled);
            stalled.response().onComplete(responded -> {
                if (responded.failed()) {
                    done.complete(new Reply(-1, null, responded.cause()));
                    return;
                }
                HttpClientResponse response = responded.result();
                int status = response.statusCode();
                response.body()
                        .onComplete(read -> done.complete(
                                read.succeeded()
                                        ? new Reply(status, read.result().toString(), null)
                                        : new Reply(status, null, read.cause())));
            });
            stalled.write(half);
        });
        Reply reply = done.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // A client that keeps trickling the body after the answer must find the inbound side aborted.
        Throwable lateWriteFailure = null;
        for (int attempt = 0; attempt < 50 && lateWriteFailure == null; attempt++) {
            CompletableFuture<Throwable> written = new CompletableFuture<>();
            request.get()
                    .write(Buffer.buffer("x"))
                    .onComplete(result -> written.complete(result.failed() ? result.cause() : null));
            lateWriteFailure = written.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (lateWriteFailure == null) {
                quietFor(Duration.ofMillis(100));
            }
        }
        return new StalledUpload(reply, lateWriteFailure);
    }

    /** The answer to a stalled upload and the failure a later write on the same request observed. */
    private record StalledUpload(Reply reply, Throwable lateWriteFailure) {}

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** What the client observed for one request. */
    private record Reply(int status, String body, Throwable failure) {}

    /** Periodic sibling {@code server/discover} traffic multiplexed on the same connection. */
    private final class Siblings {
        private final AtomicInteger attempted = new AtomicInteger();
        private final List<String> failures = new CopyOnWriteArrayList<>();
        private final List<CompletableFuture<Reply>> outstanding = new CopyOnWriteArrayList<>();
        private volatile long timerId = -1;

        void start() {
            timerId = vertx.setPeriodic(SIBLING_INTERVAL_MS, ignored -> {
                attempted.incrementAndGet();
                CompletableFuture<Reply> reply = discover();
                outstanding.add(reply);
                reply.thenAccept(result -> {
                    if (result.status() != 200) {
                        failures.add("status " + result.status() + " failure " + result.failure());
                    }
                });
            });
        }

        void stop() throws Exception {
            if (timerId >= 0) {
                vertx.cancelTimer(timerId);
                timerId = -1;
            }
            CompletableFuture.allOf(outstanding.toArray(CompletableFuture[]::new))
                    .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        int attempted() {
            return attempted.get();
        }

        List<String> failures() {
            return failures;
        }
    }

    private CompletableFuture<Reply> discover() {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        Buffer body = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", DISCOVER_METHOD)
                .put("params", new JsonObject().put("_meta", meta))
                .toBuffer();
        return exchange(options(DISCOVER_METHOD).putHeader("Mcp-Method", DISCOVER_METHOD), body);
    }

    /** A request interceptor whose future is held until the test releases it. */
    private static final class HoldingInterceptor implements McpRequestInterceptor {
        private final AtomicInteger calls = new AtomicInteger();
        private final Promise<Void> gate = Promise.promise();

        @Override
        public Future<Void> beforeRequest(McpRequestContext context) {
            calls.incrementAndGet();
            return gate.future();
        }

        void release() {
            gate.tryComplete();
        }

        int calls() {
            return calls.get();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------------

    /** One real port-0 h2c-capable mount with a tool that is held until released and one that is not. */
    private static final class Fixture {
        private final HttpServer server;
        private final int port;
        private final Tool held;
        private final Tool plain;
        private final Tool streamed;
        private final Recorder recorder = new Recorder();
        private final Set<HttpConnection> connections = ConcurrentHashMap.newKeySet();

        private Fixture(
                Vertx vertx,
                long deadlineMs,
                Set<McpRequestInterceptor> requestInterceptors,
                SecurityIdentityResolver identityResolver)
                throws Exception {
            McpAccessMode permitAll = McpAccessMode.PERMIT_ALL;
            this.held = new Tool(vertx, HELD_TOOL, new McpToolAccess(permitAll, List.of(), null), Tool.Behavior.HOLD);
            this.plain =
                    new Tool(vertx, PLAIN_TOOL, new McpToolAccess(permitAll, List.of(), null), Tool.Behavior.IMMEDIATE);
            this.streamed = new Tool(
                    vertx, STREAMED_TOOL, new McpToolAccess(permitAll, List.of(), null), Tool.Behavior.PROGRESS);
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .authenticationScheme(SCHEME_NAME)
                    .requestDeadlineMs(deadlineMs)
                    .build();
            McpToolRegistry registry = McpToolRegistry.build(Set.of(held, plain, streamed));
            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
            McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                    Optional.empty(),
                    Optional.empty(),
                    Set.of(),
                    new SecurityEventEmitter(Set.of()),
                    NO_OP_CONTEXT_HOLDER,
                    securityRuntime,
                    Optional.empty()));
            // The connection-level idle timer is armed, but far above every observation window.
            HttpConfig httpConfig = HttpConfig.builder().idleTimeoutSeconds(60).build();
            McpRouterMount mount = new McpRouterMount(
                    config,
                    new McpServerConfigValidator(),
                    new McpRequestDispatcher(
                            config,
                            securityRuntime,
                            Set.of(recorder),
                            Set.of(),
                            requestInterceptors,
                            Set.of(),
                            httpConfig,
                            registry,
                            policyEnforcer,
                            NO_OP_CONTEXT_HOLDER,
                            new CorrelationContextFactory(Optional.empty())),
                    Set.of(new OptionalRouteAuthHandler()),
                    identityResolution(identityResolver, securityRuntime),
                    httpConfig,
                    registry);
            Router root = Router.router(vertx);
            root.route().handler(ctx -> {
                connections.add(ctx.request().connection());
                ctx.next();
            });
            root.route().handler(new RequestContextLifecycle());
            Router mountRouter = await(mount.createRouter(vertx));
            root.route(config.mountPath()).subRouter(mountRouter);
            HttpServerOptions serverOptions =
                    httpConfig.toHttpServerOptions().setPort(0).setHost(LOOPBACK);
            this.server = await(
                    vertx.createHttpServer(serverOptions).requestHandler(root).listen());
            this.port = server.actualPort();
        }

        HttpServer server() {
            return server;
        }

        int port() {
            return port;
        }

        Tool held() {
            return held;
        }

        Tool plain() {
            return plain;
        }

        Tool streamed() {
            return streamed;
        }

        Recorder recorder() {
            return recorder;
        }

        Set<HttpConnection> connections() {
            return connections;
        }

        private static IdentityResolutionMiddleware identityResolution(
                SecurityIdentityResolver identityResolver, SecurityRuntime securityRuntime) {
            return new IdentityResolutionMiddleware(
                    Set.of(identityResolver),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    new SecurityEventEmitter(Set.of()),
                    securityRuntime,
                    NO_OP_CONTEXT_HOLDER);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Tool, observers, security plumbing
    // ---------------------------------------------------------------------------------------------

    /** One fixture tool: answered immediately, or held until the test releases it. */
    private static final class Tool implements McpToolInvoker {
        enum Behavior {
            HOLD,
            IMMEDIATE,
            PROGRESS
        }

        static final int PROGRESS_TICKS = 100;
        static final long PROGRESS_TICK_MS = 50;

        private final Vertx vertx;
        private final McpToolDescriptor descriptor;
        private final Behavior behavior;
        private final AtomicInteger prepares = new AtomicInteger();
        private final AtomicInteger ticks = new AtomicInteger();
        private final CompletableFuture<Void> firstInvoke = new CompletableFuture<>();
        private final CompletableFuture<Void> stoppedEarly = new CompletableFuture<>();
        private final Promise<McpToolResult<?>> gate = Promise.promise();
        private volatile McpCancellationSignal signal;

        Tool(Vertx vertx, String name, McpToolAccess access, Behavior behavior) {
            this.vertx = vertx;
            this.behavior = behavior;
            this.descriptor = new McpToolDescriptor(
                    name,
                    null,
                    "Request deadline fixture tool.",
                    new McpToolAnnotations(true, false, true, false),
                    "{\"type\":\"object\",\"additionalProperties\":false}",
                    null,
                    access);
        }

        @Override
        public McpToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            prepares.incrementAndGet();
            signal = cancellation;
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return Map.of();
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    firstInvoke.complete(null);
                    return switch (behavior) {
                        case HOLD -> gate.future();
                        case IMMEDIATE -> Future.succeededFuture(McpToolResult.text("done"));
                        case PROGRESS -> streamProgress(cancellation);
                    };
                }
            };
        }

        private Future<McpToolResult<?>> streamProgress(McpCancellationSignal cancellation) {
            Promise<McpToolResult<?>> result = Promise.promise();
            vertx.setPeriodic(PROGRESS_TICK_MS, timerId -> {
                if (cancellation.isCancelled()) {
                    vertx.cancelTimer(timerId);
                    stoppedEarly.complete(null);
                    result.tryComplete(McpToolResult.text("stopped"));
                    return;
                }
                int tick = ticks.incrementAndGet();
                cancellation.progressReporter().report(tick, (double) PROGRESS_TICKS, null);
                if (tick >= PROGRESS_TICKS) {
                    vertx.cancelTimer(timerId);
                    result.tryComplete(McpToolResult.text("streamed"));
                }
            });
            return result.future();
        }

        void release() {
            gate.tryComplete(McpToolResult.text("late"));
        }

        void awaitInvoked() throws Exception {
            firstInvoke.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        boolean awaitInvoked(Duration window) throws Exception {
            return awaitDone(firstInvoke, window);
        }

        boolean awaitStoppedEarly(Duration window) throws Exception {
            return awaitDone(stoppedEarly, window);
        }

        McpCancellationSignal signal() {
            return signal;
        }

        int prepares() {
            return prepares.get();
        }

        int ticks() {
            return ticks.get();
        }
    }

    private static final class Recorder implements McpRequestLifecycleObserver {
        private final CompletableFuture<Session> firstNonDiscoverCompletion = new CompletableFuture<>();
        private final AtomicInteger opened = new AtomicInteger();

        @Override
        public McpRequestObservation open(Instant startedAt) {
            opened.incrementAndGet();
            return new Session(Vertx.currentContext(), this);
        }

        int sessionCount() {
            return opened.get();
        }

        Session awaitNonDiscoverCompletion() throws Exception {
            return firstNonDiscoverCompletion.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static final class Session implements McpCompletionScope, McpToolValueObservation {
        private final Context context;
        private final Recorder recorder;
        private final List<McpRequestTerminalEvent> terminals = new CopyOnWriteArrayList<>();
        private final List<McpRequestCompletedEvent> completions = new CopyOnWriteArrayList<>();
        private final AtomicInteger outputs = new AtomicInteger();

        Session(Context context, Recorder recorder) {
            this.context = context;
            this.recorder = recorder;
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            terminals.add(observation.event());
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            completions.add(event);
            if (event.terminal().method() != McpMethod.SERVER_DISCOVER) {
                recorder.firstNonDiscoverCompletion.complete(this);
            }
        }

        @Override
        public void onToolInput(McpToolInputObservation observation) {}

        @Override
        public void onToolOutput(McpToolOutputObservation observation) {
            outputs.incrementAndGet();
        }

        @Override
        public AutoCloseable openCompletionScope() {
            return () -> {};
        }

        Context context() {
            return context;
        }

        int outputs() {
            return outputs.get();
        }

        List<McpRequestTerminalEvent> terminals() {
            return terminals;
        }

        List<McpRequestCompletedEvent> completions() {
            return completions;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Security plumbing
    // ---------------------------------------------------------------------------------------------

    private record OptionalRouteAuthHandler() implements RouteAuthHandler {
        @Override
        public String schemeName() {
            return SCHEME_NAME;
        }

        @Override
        public Handler<RoutingContext> createHandler() {
            return RoutingContext::next;
        }

        @Override
        public Optional<Handler<RoutingContext>> createOptionalHandler() {
            return Optional.of(RoutingContext::next);
        }
    }

    /** An identity resolver whose future is held until the test releases it. */
    private static final class HoldingIdentityResolver implements SecurityIdentityResolver {
        private final Promise<Optional<SecurityIdentity>> gate = Promise.promise();

        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            return gate.future();
        }

        void release() {
            gate.tryComplete(Optional.of(SecurityIdentity.anonymous()));
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
        public void clearCurrent() {
            bound = null;
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }
}
