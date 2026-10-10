// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.interceptor.McpRequestContext;
import dev.vertique.mcp.interceptor.McpRequestInterceptor;
import dev.vertique.mcp.interceptor.McpToolInterceptor;
import dev.vertique.mcp.interceptor.McpToolInvocationContext;
import dev.vertique.mcp.lifecycle.McpCompletionScope;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpOutcome;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
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
import dev.vertique.resilience.Resilience;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.events.RequestCompletionRecorder;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.AuthorizationDecisionPoint;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
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
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.TimeoutHandler;
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
import org.junit.jupiter.api.Timeout;

/**
 * Real-transport characterization of per-request deadlines on multiplexed HTTP/2 (h2c) streams.
 *
 * <p>The connection-level idle and read-idle timers cannot reclaim a hung request while sibling
 * streams keep an HTTP/2 connection busy (see {@link McpHttp2LivenessCharacterizationIT}). These
 * rows ask whether a per-request wall-clock deadline that already exists in the stack, Vert.x Web's
 * {@link TimeoutHandler}, reclaims such a stream and whether it composes safely with MCP's settlement
 * and response handling. Every row runs against a real port-0 server bound to {@code 127.0.0.1}
 * with the connection-level idle timer armed far above the observation window, so any reclaim
 * observed inside a row is attributable to the per-request deadline and never to a connection timer.
 *
 * <p>The rows pin whichever behavior the real stack produces; none of them is a statement that the
 * observed behavior is desirable. The deadline under test is installed in four ways: absent (control),
 * on the main router ahead of the mount, appended to the mount's own router, and first on the mount's
 * own router. Mirroring the two application hooks, the main-router form is what a router customizer
 * running before the mounts produces and the mount-first form is what a mount customizer produces.
 * A last family installs a small prototype deadline that settles through the request's completion
 * coordinator instead of through {@link RoutingContext#fail(int)}.
 *
 * <p>Rows that depend on the transport run on both h2c and HTTP/1.1, because the completion outcome
 * the end handlers report differs between them: a response written from a timer task on h2c reaches
 * the routing context's end handlers as a closed connection before the write resolves, so it
 * completes as a reset and fires the request's cancellation signal after the bytes were delivered.
 * That is independent of any deadline (see the baseline rows) and it decides which late stages are
 * fenced after a deadline settles the request.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class McpHttp2RequestDeadlineSpikeIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String SCHEME_NAME = "spike-scheme";
    private static final String HUNG_TOOL = "deadline.spike.hung";
    private static final String PROGRESS_TOOL = "deadline.spike.progress";
    private static final String PLAIN_TOOL = "deadline.spike.plain";
    private static final String RESTRICTED_TOOL = "deadline.spike.restricted";
    private static final String DISCOVER_METHOD = "server/discover";

    /** The per-request deadline under test: short, but far above scheduling jitter. */
    private static final long DEADLINE_MS = 600;

    /** Sibling discover cadence, fast enough that the connection is never idle for a full second. */
    private static final long SIBLING_INTERVAL_MS = 100;

    /** How long a negative observation is held open after the request context was drained. */
    private static final Duration CONFIRMATION_WINDOW = Duration.ofMillis(1_000);

    private static final long ASYNC_TIMEOUT_SECONDS = 15;

    private final Vertx vertx = Vertx.vertx();
    private final List<Throwable> uncaught = new CopyOnWriteArrayList<>();

    private Fixture fixture;
    private HttpClient client;
    private Resilience resilience;

    @BeforeEach
    void captureUncaughtFailures() {
        vertx.exceptionHandler(uncaught::add);
    }

    @AfterEach
    void tearDown() throws Exception {
        Future<Void> serverClose = fixture != null ? fixture.server().close() : Future.succeededFuture();
        Future<Void> clientClose = client != null ? client.close() : Future.succeededFuture();
        Future<Void> resilienceClose = resilience != null ? resilience.close() : Future.succeededFuture();
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future.join(serverClose, clientClose, resilienceClose)
                .onComplete(joined -> vertx.close().onComplete(vertxResult -> {
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
        resilience = null;
    }

    // ---------------------------------------------------------------------------------------------
    // Baselines: how an asynchronously completed response is reported, with no deadline involved
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("control: with no per-request deadline a hung tools/call stays open on a busy h2c "
            + "connection although the connection-level timer is armed")
    void withoutADeadlineTheHungStreamIsNeverReclaimed() throws Exception {
        start(Deadline.none());
        CompletableFuture<Reply> hung = callTool(HUNG_TOOL, false);
        fixture.hung().awaitInvoked();

        Siblings siblings = new Siblings();
        siblings.start();
        boolean reclaimed = awaitDone(hung, Duration.ofMillis(DEADLINE_MS * 3));
        siblings.stop();

        assertThat(reclaimed)
                .as("without a per-request deadline the hung stream is not reclaimed inside %d ms", DEADLINE_MS * 3)
                .isFalse();
        assertThat(siblings.failures()).as("sibling streams stay healthy").isEmpty();
        assertThat(siblings.attempted()).isGreaterThanOrEqualTo(10);
        assertThat(fixture.connections())
                .as("hung request and siblings share one connection")
                .hasSize(1);
        assertThat(fixture.recorder().unsettled())
                .as("exactly the hung request is unsettled")
                .hasSize(1);
    }

    @Test
    @DisplayName("baseline: a tools/call answered immediately on h2c completes as WRITTEN")
    void immediateResponseOnH2cIsReportedAsWritten() throws Exception {
        start(Deadline.none());

        Reply reply = callTool(PLAIN_TOOL, false).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(reply.status()).isEqualTo(200);
        assertThat(session.completions().get(0).transportOutcome()).isEqualTo(McpTransportOutcome.WRITTEN);
        assertThat(fixture.endOutcomes()).containsExactly("succeeded");
    }

    @Test
    @DisplayName("baseline: a tools/call completed later from a timer on h2c is fully delivered but its "
            + "completion is RESET and fires cancellation after the write (no deadline involved)")
    void laterResponseOnH2cIsReportedAsResetAfterFullDelivery() throws Exception {
        start(Deadline.none());
        CompletableFuture<Reply> call = callTool(HUNG_TOOL, false);
        fixture.hung().awaitInvoked();
        vertx.setTimer(200, ignored -> fixture.hung().release());

        Reply reply = call.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(reply.failure()).isNull();
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("\"late\"");
        assertThat(session.terminals().get(0).outcome()).isEqualTo(McpOutcome.SUCCESS);
        assertThat(session.completions().get(0).transportOutcome())
                .as("the response was delivered in full, yet the end handlers saw a closed connection "
                        + "before the write resolved")
                .isEqualTo(McpTransportOutcome.RESET);
        assertThat(fixture.endOutcomes()).hasSize(1);
        assertThat(fixture.endOutcomes().get(0)).contains("Connection closed");
        assertThat(fixture.hung().signal().isCancelled()).isTrue();
    }

    @Test
    @DisplayName("baseline: the same later completion on HTTP/1.1 completes as WRITTEN and never cancels")
    void laterResponseOnHttp1IsReportedAsWritten() throws Exception {
        start(Deadline.none(), Set.of(), Set.of(), null, HttpVersion.HTTP_1_1);
        CompletableFuture<Reply> call = callTool(HUNG_TOOL, false);
        fixture.hung().awaitInvoked();
        vertx.setTimer(200, ignored -> fixture.hung().release());

        Reply reply = call.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(reply.status()).isEqualTo(200);
        assertThat(session.completions().get(0).transportOutcome()).isEqualTo(McpTransportOutcome.WRITTEN);
        assertThat(fixture.endOutcomes()).containsExactly("succeeded");
        assertThat(fixture.hung().signal().isCancelled()).isFalse();
    }

    @Test
    @DisplayName("positive control: held stages released before the deadline run the tool, so the "
            + "not-invoked rows below are not vacuous")
    void stagesReleasedInTimeInvokeTheTool() throws Exception {
        HoldingInterceptor requestInterceptor = new HoldingInterceptor();
        HeldDecisionPoint decisionPoint = new HeldDecisionPoint();
        start(
                Deadline.timeoutHandler(Placement.ROOT, 10 * DEADLINE_MS, 503),
                Set.of(requestInterceptor),
                Set.of(),
                decisionPoint);
        vertx.setTimer(100, ignored -> requestInterceptor.release());
        vertx.setTimer(200, ignored -> decisionPoint.releaseWithPermit());

        Reply reply = callTool(RESTRICTED_TOOL, false).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(fixture.restricted().invokes()).isOne();
        assertThat(fixture.deadlineRuns()).isOne();
    }

    // ---------------------------------------------------------------------------------------------
    // (a) hung tool, TimeoutHandler on the main router ahead of the mount
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a: a main-router TimeoutHandler reclaims the hung h2c stream with a 503 while sibling "
            + "streams and the connection stay healthy")
    void rootTimeoutHandlerReclaimsAHungToolStreamWhileSiblingsStayHealthy() throws Exception {
        start(Deadline.timeoutHandler(Placement.ROOT, DEADLINE_MS, 503));
        CompletableFuture<Reply> hung = callTool(HUNG_TOOL, false);
        fixture.hung().awaitInvoked();
        Siblings siblings = new Siblings();
        siblings.start();

        Reply reply = hung.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();
        siblings.stop();

        assertThat(reply.failure())
                .as("the client sees a response, not a stream reset")
                .isNull();
        assertThat(reply.status()).isEqualTo(503);
        assertThat(reply.contentType())
                .as("SSE was selected before the tool hung, so the empty 503 carries the stream media type")
                .startsWith("text/event-stream");
        assertThat(reply.body()).isEmpty();
        assertThat(reply.elapsedMs())
                .as("reclaimed at the deadline, not by a connection timer")
                .isBetween(DEADLINE_MS - 50, DEADLINE_MS + 3_000);
        assertThat(fixture.deadlineRuns()).isOne();
        assertThat(siblings.failures())
                .as("sibling streams were never disturbed")
                .isEmpty();
        assertThat(siblings.attempted()).isGreaterThanOrEqualTo(3);
        assertThat(fixture.connections())
                .as("the connection survived and was shared")
                .hasSize(1);
        assertDeadlineTerminal(session, H2C);
    }

    @Test
    @DisplayName("a: on HTTP/1.1 the same deadline settles identically but completes as WRITTEN and "
            + "leaves the cancellation signal unfired")
    void rootTimeoutHandlerReclaimsAHungToolOnHttp1() throws Exception {
        start(
                Deadline.timeoutHandler(Placement.ROOT, DEADLINE_MS, 503),
                Set.of(),
                Set.of(),
                null,
                HttpVersion.HTTP_1_1);
        CompletableFuture<Reply> hung = callTool(HUNG_TOOL, false);
        fixture.hung().awaitInvoked();

        Reply reply = hung.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(reply.status()).isEqualTo(503);
        assertThat(reply.elapsedMs()).isBetween(DEADLINE_MS - 50, DEADLINE_MS + 3_000);
        assertDeadlineTerminal(session, H1);
    }

    /**
     * The settlement facts every hung-tool deadline produces: one rejected/internal terminal that has
     * lost the method and tool identity, one completion, one bracketed completion scope.
     */
    private void assertDeadlineTerminal(Session session, Expect expect) {
        assertThat(session.terminals()).hasSize(1);
        McpRequestTerminalEvent terminal = session.terminals().get(0);
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.REJECTED);
        assertThat(terminal.errorType()).isEqualTo(McpErrorType.INTERNAL);
        assertThat(terminal.httpStatus()).isEqualTo(503);
        assertThat(terminal.method())
                .as("the deadline path loses the method identity the dispatcher had already resolved")
                .isEqualTo(McpMethod.OTHER);
        assertThat(terminal.toolName()).isEqualTo(McpRequestTerminalEvent.UNKNOWN_TOOL_NAME);
        assertThat(session.completions()).hasSize(1);
        assertThat(session.completions().get(0).transportOutcome()).isEqualTo(expect.transport());
        assertThat(session.completions().get(0).responseCommitted()).isTrue();
        assertThat(session.order())
                .as("terminal before completion, completion callbacks inside one opened scope, exactly once")
                .containsExactly("toolInput", "terminal", "scope-open", "completed", "scope-close");
        assertThat(fixture.listenerEvents())
                .as("the completion listener runs exactly once")
                .hasSize(1);
        assertThat(fixture.hung().signal().isCancelled())
                .as("cancellation fires only through a non-WRITTEN completion; the deadline itself never " + "fires it")
                .isEqualTo(expect.cancellationFired());
        assertThat(uncaught).isEmpty();
    }

    @Test
    @DisplayName("a: a result that arrives after the deadline is fenced on h2c: no second terminal, no "
            + "output observation, no uncaught failure, the connection keeps serving")
    void lateToolResultAfterTheDeadlineIsFencedOnH2c() throws Exception {
        assertLateToolResultIsFenced(HttpVersion.HTTP_2);
    }

    @Test
    @DisplayName("a: a result that arrives after the deadline is fenced on HTTP/1.1 by the write race alone")
    void lateToolResultAfterTheDeadlineIsFencedOnHttp1() throws Exception {
        assertLateToolResultIsFenced(HttpVersion.HTTP_1_1);
    }

    private void assertLateToolResultIsFenced(HttpVersion version) throws Exception {
        start(Deadline.timeoutHandler(Placement.ROOT, DEADLINE_MS, 503), Set.of(), Set.of(), null, version);
        CompletableFuture<Reply> hung = callTool(HUNG_TOOL, false);
        fixture.hung().awaitInvoked();
        hung.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();
        List<String> before = List.copyOf(session.order());

        fixture.hung().release();
        drain(session.context());
        quietFor(CONFIRMATION_WINDOW);
        drain(session.context());

        assertThat(session.order())
                .as("nothing was published for the late result")
                .isEqualTo(before);
        assertThat(session.terminals()).hasSize(1);
        assertThat(session.completions()).hasSize(1);
        assertThat(session.outputs())
                .as("the output observation is gated on winning the write")
                .isZero();
        assertThat(uncaught)
                .as("no failure escaped to the context exception handler")
                .isEmpty();
        assertThat(fixture.hung().invokes()).isOne();

        Reply followUp = post(discoverBody(), DISCOVER_METHOD, DISCOVER_METHOD, "application/json")
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(followUp.status()).as("the connection still serves requests").isEqualTo(200);
    }

    // ---------------------------------------------------------------------------------------------
    // (b) hung stages other than the handler
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("b: a hung request interceptor is reclaimed on h2c and its late release is fenced")
    void hungRequestInterceptorOnH2c() throws Exception {
        assertHungRequestInterceptorIsReclaimedAndFenced(H2C);
    }

    @Test
    @DisplayName("b: a hung request interceptor is reclaimed on HTTP/1.1 and its late release is fenced")
    void hungRequestInterceptorOnHttp1() throws Exception {
        assertHungRequestInterceptorIsReclaimedAndFenced(H1);
    }

    private void assertHungRequestInterceptorIsReclaimedAndFenced(Expect expect) throws Exception {
        HoldingInterceptor interceptor = new HoldingInterceptor();
        start(
                Deadline.timeoutHandler(Placement.ROOT, DEADLINE_MS, 503),
                Set.of(interceptor),
                Set.of(),
                null,
                expect.version());

        Reply reply = callTool(PLAIN_TOOL, false).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(reply.status()).isEqualTo(503);
        assertThat(reply.elapsedMs()).isBetween(DEADLINE_MS - 50, DEADLINE_MS + 3_000);
        assertThat(interceptor.calls()).isOne();
        assertThat(session.completions().get(0).transportOutcome()).isEqualTo(expect.transport());

        interceptor.release();
        drain(session.context());

        // On HTTP/1.1 the cancellation signal is unfired, so this is not the settlement guard: the
        // dispatcher's next stage selects SSE on the already ended response, which throws
        // IllegalStateException (confirmed with a temporary probe), and the dispatch failure guard
        // then loses the write race. The tool is therefore never prepared or invoked.
        assertThat(fixture.plain().awaitInvoked(CONFIRMATION_WINDOW))
                .as("the tool must not run for a request the deadline already answered")
                .isFalse();
        assertThat(fixture.plain().prepares()).isZero();
        assertThat(session.terminals()).as("still exactly one terminal").hasSize(1);
        assertThat(session.completions()).hasSize(1);
        assertThat(uncaught).isEmpty();
    }

    @Test
    @DisplayName("b: a hung tool interceptor is reclaimed on h2c and its late release is fenced because "
            + "the (RESET) completion cancelled the request")
    void hungToolInterceptorOnH2c() throws Exception {
        assertHungToolInterceptor(H2C, false);
    }

    @Test
    @DisplayName("b: a hung tool interceptor is reclaimed on HTTP/1.1 but its late release still runs "
            + "the tool body for a request that was already answered with a 503")
    void hungToolInterceptorOnHttp1() throws Exception {
        assertHungToolInterceptor(H1, true);
    }

    private void assertHungToolInterceptor(Expect expect, boolean toolRunsLate) throws Exception {
        HoldingToolInterceptor interceptor = new HoldingToolInterceptor();
        start(
                Deadline.timeoutHandler(Placement.ROOT, DEADLINE_MS, 503),
                Set.of(),
                Set.of(interceptor),
                null,
                expect.version());

        Reply reply = callTool(PLAIN_TOOL, false).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(reply.status()).isEqualTo(503);
        assertThat(interceptor.calls()).isOne();
        assertThat(fixture.plain().prepares())
                .as("the input pipeline already ran before the gate")
                .isOne();
        assertThat(fixture.plain().invokes()).isZero();
        assertThat(session.completions().get(0).transportOutcome()).isEqualTo(expect.transport());

        interceptor.release();
        drain(session.context());

        assertThat(fixture.plain().awaitInvoked(CONFIRMATION_WINDOW))
                .as("whether the tool body runs after the client was answered follows the cancellation "
                        + "signal, which only a non-WRITTEN completion fires")
                .isEqualTo(toolRunsLate);
        assertThat(session.terminals()).hasSize(1);
        assertThat(session.completions()).hasSize(1);
        assertThat(uncaught).isEmpty();
    }

    @Test
    @DisplayName("b: a hung authorization decision is reclaimed on h2c and its late permit is fenced")
    void hungAuthorizationOnH2c() throws Exception {
        assertHungAuthorizationIsReclaimedAndFenced(H2C);
    }

    @Test
    @DisplayName("b: a hung authorization decision is reclaimed on HTTP/1.1 and its late permit is fenced")
    void hungAuthorizationOnHttp1() throws Exception {
        assertHungAuthorizationIsReclaimedAndFenced(H1);
    }

    private void assertHungAuthorizationIsReclaimedAndFenced(Expect expect) throws Exception {
        HeldDecisionPoint decisionPoint = new HeldDecisionPoint();
        start(
                Deadline.timeoutHandler(Placement.ROOT, DEADLINE_MS, 503),
                Set.of(),
                Set.of(),
                decisionPoint,
                expect.version());

        Reply reply = callTool(RESTRICTED_TOOL, false).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(reply.status()).isEqualTo(503);
        assertThat(reply.elapsedMs()).isBetween(DEADLINE_MS - 50, DEADLINE_MS + 3_000);
        assertThat(decisionPoint.calls()).isOne();
        assertThat(session.completions().get(0).transportOutcome()).isEqualTo(expect.transport());

        decisionPoint.releaseWithPermit();
        drain(session.context());

        // Same incidental fence as for the request interceptor on HTTP/1.1: SSE selection throws on
        // the ended response, here inside a future callback no dispatch guard covers. The failure is
        // swallowed (it never reaches the Vert.x exception handler).
        assertThat(fixture.restricted().awaitInvoked(CONFIRMATION_WINDOW))
                .as("a late permit must not run the tool for an already-answered request")
                .isFalse();
        assertThat(fixture.restricted().prepares()).isZero();
        assertThat(session.terminals()).hasSize(1);
        assertThat(session.completions()).hasSize(1);
        assertThat(uncaught).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // (c)(iv) body not yet fully read
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("c-iv: a main-router TimeoutHandler answers a stalled h2c upload with a bare 503, MCP "
            + "never opens a lifecycle observation, and the connection survives")
    void rootDeadlineReclaimsAStalledUploadBeforeMcpEverSeesTheRequest() throws Exception {
        start(Deadline.timeoutHandler(Placement.ROOT, DEADLINE_MS, 503));
        Siblings siblings = new Siblings();
        siblings.start();

        StalledUpload upload = stalledUpload();

        assertThat(upload.reply().failure()).isNull();
        assertThat(upload.reply().status()).isEqualTo(503);
        assertThat(upload.reply().body()).isEmpty();
        assertThat(upload.reply().elapsedMs()).isBetween(DEADLINE_MS - 50, DEADLINE_MS + 3_000);
        assertThat(upload.endResult())
                .as("finishing the upload after the answer neither fails nor wedges the stream")
                .isEqualTo("ended");
        siblings.stop();
        assertThat(siblings.failures()).isEmpty();
        assertThat(fixture.connections()).hasSize(1);
        assertThat(fixture.recorder().nonDiscover())
                .as("no terminal: the request never left body aggregation")
                .isEmpty();
        assertThat(fixture.recorder().unsettled())
                .as("no observation was ever opened for the stalled upload")
                .isEmpty();
        assertThat(uncaught).isEmpty();
    }

    @Test
    @DisplayName("c-iv: the same holds when the deadline is the first handler of the mount's own router")
    void mountFirstDeadlineReclaimsAStalledUploadBeforeBodyAggregation() throws Exception {
        start(Deadline.timeoutHandler(Placement.MOUNT_FIRST, DEADLINE_MS, 503));

        StalledUpload upload = stalledUpload();

        assertThat(upload.reply().failure()).isNull();
        assertThat(upload.reply().status()).isEqualTo(503);
        assertThat(fixture.deadlineRuns()).isOne();
        assertThat(fixture.recorder().unsettled()).isEmpty();
        assertThat(uncaught).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // (c)(v) streaming (SSE) tool
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("c-v control: without a deadline a long progress-streaming tools/call completes with "
            + "every frame and its result")
    void withoutADeadlineTheProgressStreamCompletes() throws Exception {
        start(Deadline.none());

        Reply reply = callTool(PROGRESS_TOOL, true).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.contentType()).startsWith("text/event-stream");
        assertThat(count(reply.body(), "notifications/progress")).isEqualTo(Tool.PROGRESS_TICKS);
        assertThat(reply.body()).contains("\"result\"");
        assertThat(reply.elapsedMs()).isGreaterThanOrEqualTo(Tool.PROGRESS_TICKS * Tool.PROGRESS_TICK_MS - 100);
    }

    @Test
    @DisplayName("c-v: on h2c a wall-clock TimeoutHandler cuts a healthy streaming tool mid-stream: the "
            + "client sees a clean 200 with partial progress and no result while the terminal says 503")
    void wallClockDeadlineCutsALegitimateProgressStreamOnH2c() throws Exception {
        start(Deadline.timeoutHandler(Placement.ROOT, DEADLINE_MS, 503));

        Reply reply = callTool(PROGRESS_TOOL, true).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertSseWasCutCleanly(reply, session);
        assertThat(session.completions().get(0).transportOutcome()).isEqualTo(McpTransportOutcome.RESET);
        assertThat(fixture.progress().awaitStoppedEarly(Duration.ofSeconds(ASYNC_TIMEOUT_SECONDS)))
                .as("the RESET completion fires cancellation, so a cooperative tool stops")
                .isTrue();
        assertThat(fixture.progress().ticks()).isLessThan(Tool.PROGRESS_TICKS);
        assertThat(uncaught).isEmpty();
    }

    @Test
    @DisplayName("c-v: on HTTP/1.1 the same cut leaves the tool running to completion for a request "
            + "that was already answered")
    void wallClockDeadlineCutsALegitimateProgressStreamOnHttp1() throws Exception {
        start(
                Deadline.timeoutHandler(Placement.ROOT, DEADLINE_MS, 503),
                Set.of(),
                Set.of(),
                null,
                HttpVersion.HTTP_1_1);

        Reply reply = callTool(PROGRESS_TOOL, true).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertSseWasCutCleanly(reply, session);
        assertThat(session.completions().get(0).transportOutcome()).isEqualTo(McpTransportOutcome.WRITTEN);
        assertThat(fixture.progress().awaitFinished(Duration.ofSeconds(ASYNC_TIMEOUT_SECONDS)))
                .as("the tool keeps running to completion; nothing told it to stop")
                .isTrue();
        assertThat(fixture.progress().ticks()).isEqualTo(Tool.PROGRESS_TICKS);
        assertThat(fixture.progress().signal().isCancelled()).isFalse();
        assertThat(session.terminals()).as("the late result published nothing").hasSize(1);
        assertThat(session.outputs()).isZero();
        assertThat(uncaught).isEmpty();
    }

    private void assertSseWasCutCleanly(Reply reply, Session session) {
        assertThat(reply.failure())
                .as("the stream ends cleanly, it is not reset")
                .isNull();
        assertThat(reply.status())
                .as("the committed status is what the client sees")
                .isEqualTo(200);
        assertThat(reply.contentType()).startsWith("text/event-stream");
        assertThat(count(reply.body(), "notifications/progress"))
                .as("progress frames written before the cut")
                .isBetween(1, Tool.PROGRESS_TICKS - 1);
        assertThat(reply.body())
                .as("no terminal JSON-RPC message: the client cannot tell a cut from a finished stream")
                .doesNotContain("\"result\"")
                .doesNotContain("\"error\"");
        assertThat(reply.elapsedMs()).isGreaterThanOrEqualTo(DEADLINE_MS - 50);
        McpRequestTerminalEvent terminal = session.terminals().get(0);
        assertThat(terminal.httpStatus())
                .as("the recorded status disagrees with the wire")
                .isEqualTo(503);
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.REJECTED);
        assertThat(session.completions()).hasSize(1);
        assertThat(session.completions().get(0).responseCommitted()).isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // (d) placement and composition with admission
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("d: a handler appended to the mount's own router after the MCP chain never runs")
    void aDeadlineAppendedToTheMountRouterNeverRuns() throws Exception {
        start(Deadline.timeoutHandler(Placement.MOUNT_APPENDED, DEADLINE_MS, 503));
        CompletableFuture<Reply> hung = callTool(HUNG_TOOL, false);
        fixture.hung().awaitInvoked();

        boolean reclaimed = awaitDone(hung, Duration.ofMillis(DEADLINE_MS * 3));

        assertThat(fixture.deadlineRuns())
                .as("the MCP chain never calls next()")
                .isZero();
        assertThat(reclaimed).isFalse();
    }

    @Test
    @DisplayName("d: a handler registered first on the mount's own router reclaims the hung stream with "
            + "the same outcome as one on the main router")
    void aDeadlineFirstOnTheMountRouterReclaimsTheStream() throws Exception {
        start(Deadline.timeoutHandler(Placement.MOUNT_FIRST, DEADLINE_MS, 503));
        CompletableFuture<Reply> hung = callTool(HUNG_TOOL, false);
        fixture.hung().awaitInvoked();

        Reply reply = hung.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(reply.status()).isEqualTo(503);
        assertThat(fixture.deadlineRuns()).isOne();
        assertDeadlineTerminal(session, H2C);
    }

    @Test
    @DisplayName("d: the deadline status code is only the HTTP status; the lifecycle classification "
            + "stays an internal rejection")
    void aConfiguredDeadlineStatusDoesNotChangeTheLifecycleClassification() throws Exception {
        start(Deadline.timeoutHandler(Placement.ROOT, DEADLINE_MS, 504));
        CompletableFuture<Reply> hung = callTool(HUNG_TOOL, false);

        Reply reply = hung.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(reply.status()).isEqualTo(504);
        McpRequestTerminalEvent terminal = session.terminals().get(0);
        assertThat(terminal.httpStatus()).isEqualTo(504);
        assertThat(terminal.errorType())
                .as("not the TIMEOUT classification the handler-level resilience timeout produces")
                .isEqualTo(McpErrorType.INTERNAL);
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.REJECTED);
    }

    @Test
    @DisplayName("d: requests that finish or are rejected at cheap admission before the deadline leave "
            + "no stray failure behind when the deadline later elapses")
    void aDeadlineArmedOnFastAndAdmissionRejectedRequestsIsInert() throws Exception {
        start(Deadline.timeoutHandler(Placement.ROOT, 300, 503));

        Reply discover = post(discoverBody(), DISCOVER_METHOD, DISCOVER_METHOD, "application/json")
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Reply wrongType = post(discoverBody(), DISCOVER_METHOD, DISCOVER_METHOD, "text/plain")
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(discover.status()).isEqualTo(200);
        assertThat(wrongType.status()).isEqualTo(415);

        quietFor(Duration.ofMillis(900));

        assertThat(fixture.recorder().sessions()).hasSize(1);
        Session session = fixture.recorder().sessions().get(0);
        assertThat(session.terminals()).hasSize(1);
        assertThat(session.terminals().get(0).outcome()).isEqualTo(McpOutcome.SUCCESS);
        assertThat(session.completions()).hasSize(1);
        assertThat(uncaught).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // (e) alternatives
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("e: wrapping a hung request interceptor in the resilience timeout fails the stage "
            + "closed as an interceptor rejection with no deadline handler at all")
    void resilienceTimeoutAroundAHungInterceptorFailsClosed() throws Exception {
        resilience = Resilience.create(vertx);
        dev.vertique.resilience.Timeout timeout = dev.vertique.resilience.Timeout.builder(
                        resilience, "spike.interceptor")
                .duration(Duration.ofMillis(DEADLINE_MS))
                .build();
        HoldingInterceptor inner = new HoldingInterceptor();
        McpRequestInterceptor wrapped = context -> timeout.<Void>execute(() -> inner.beforeRequest(context));
        start(Deadline.none(), Set.of(wrapped), Set.of(), null);

        Reply reply = callTool(PLAIN_TOOL, false).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(reply.elapsedMs()).isBetween(DEADLINE_MS - 50, DEADLINE_MS + 3_000);
        assertThat(reply.status()).isEqualTo(403);
        McpRequestTerminalEvent terminal = session.terminals().get(0);
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.REJECTED);
        assertThat(terminal.errorType())
                .as("the stage-local timeout is indistinguishable from an interceptor rejection")
                .isEqualTo(McpErrorType.INTERCEPTOR);
        assertThat(terminal.httpStatus()).isEqualTo(403);

        inner.release();
        drain(session.context());
        assertThat(fixture.plain().prepares())
                .as("the late release finds a settled request")
                .isZero();
        assertThat(session.terminals()).hasSize(1);
    }

    @Test
    @DisplayName("e: wrapping a hung authorization decision in the resilience timeout fails closed as "
            + "an authorization rejection")
    void resilienceTimeoutAroundAHungDecisionPointFailsClosed() throws Exception {
        resilience = Resilience.create(vertx);
        dev.vertique.resilience.Timeout timeout = dev.vertique.resilience.Timeout.builder(resilience, "spike.decision")
                .duration(Duration.ofMillis(DEADLINE_MS))
                .build();
        HeldDecisionPoint inner = new HeldDecisionPoint();
        AuthorizationDecisionPoint wrapped =
                request -> timeout.<AuthorizationDecision>execute(() -> inner.decide(request));
        start(Deadline.none(), Set.of(), Set.of(), wrapped);

        Reply reply = callTool(RESTRICTED_TOOL, false).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(reply.elapsedMs()).isBetween(DEADLINE_MS - 50, DEADLINE_MS + 3_000);
        assertThat(reply.status()).isEqualTo(400);
        McpRequestTerminalEvent terminal = session.terminals().get(0);
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.REJECTED);
        assertThat(terminal.errorType()).isEqualTo(McpErrorType.AUTHORIZATION);
        assertThat(fixture.restricted().invokes())
                .as("a timed-out authorization never permits")
                .isZero();

        inner.releaseWithPermit();
        drain(session.context());
        assertThat(fixture.restricted().prepares()).isZero();
        assertThat(session.terminals()).hasSize(1);
    }

    @Test
    @DisplayName("e: a handler-level resilience timeout shorter than the deadline keeps its own "
            + "504/timeout classification and leaves the deadline inert")
    void handlerTimeoutShorterThanTheDeadlineWins() throws Exception {
        resilience = Resilience.create(vertx);
        start(Deadline.timeoutHandler(Placement.ROOT, 3 * DEADLINE_MS, 503));
        fixture.hung()
                .useHandlerTimeout(dev.vertique.resilience.Timeout.builder(resilience, "spike.handler")
                        .duration(Duration.ofMillis(DEADLINE_MS / 2))
                        .build());

        Reply reply = callTool(HUNG_TOOL, false).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();
        quietFor(Duration.ofMillis(3 * DEADLINE_MS));

        assertThat(reply.status()).isEqualTo(504);
        assertThat(reply.elapsedMs()).isBetween(DEADLINE_MS / 2 - 50, DEADLINE_MS + 3_000);
        McpRequestTerminalEvent terminal = session.terminals().get(0);
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.FAILED);
        assertThat(terminal.errorType()).isEqualTo(McpErrorType.TIMEOUT);
        assertThat(terminal.method())
                .as("the handler path keeps the resolved identity")
                .isEqualTo(McpMethod.TOOLS_CALL);
        assertThat(terminal.toolName()).isEqualTo(HUNG_TOOL);
        assertThat(session.terminals()).hasSize(1);
        assertThat(session.completions()).hasSize(1);
        assertThat(uncaught).isEmpty();
    }

    @Test
    @DisplayName("e: a deadline shorter than the handler-level resilience timeout wins, and the later "
            + "handler timeout publishes nothing")
    void deadlineShorterThanTheHandlerTimeoutWins() throws Exception {
        resilience = Resilience.create(vertx);
        start(Deadline.timeoutHandler(Placement.ROOT, DEADLINE_MS / 2, 503));
        fixture.hung()
                .useHandlerTimeout(dev.vertique.resilience.Timeout.builder(resilience, "spike.handler")
                        .duration(Duration.ofMillis(2 * DEADLINE_MS))
                        .build());

        Reply reply = callTool(HUNG_TOOL, false).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();
        quietFor(Duration.ofMillis(3 * DEADLINE_MS));

        assertThat(reply.status()).isEqualTo(503);
        McpRequestTerminalEvent terminal = session.terminals().get(0);
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.REJECTED);
        assertThat(terminal.errorType()).isEqualTo(McpErrorType.INTERNAL);
        assertThat(session.terminals())
                .as("the late handler timeout was fenced")
                .hasSize(1);
        assertThat(session.completions()).hasSize(1);
        assertThat(fixture.listenerEvents()).hasSize(1);
        assertThat(uncaught).isEmpty();
    }

    @Test
    @DisplayName("e prototype: a deadline that settles through the completion coordinator reclaims the "
            + "hung h2c stream with one cancelled/timeout terminal and fires the tool's cancellation")
    void coordinatorDeadlineSettlesOnceAndCancelsTheHungTool() throws Exception {
        start(Deadline.prototype(Placement.ROOT, DEADLINE_MS));
        CompletableFuture<Reply> hung = callTool(HUNG_TOOL, false);
        fixture.hung().awaitInvoked();
        Siblings siblings = new Siblings();
        siblings.start();

        Reply reply = hung.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();
        siblings.stop();

        assertThat(reply.status()).isEqualTo(504);
        assertThat(reply.elapsedMs()).isBetween(DEADLINE_MS - 50, DEADLINE_MS + 3_000);
        assertThat(siblings.failures()).isEmpty();
        assertThat(fixture.connections()).hasSize(1);
        assertThat(session.terminals()).hasSize(1);
        McpRequestTerminalEvent terminal = session.terminals().get(0);
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.CANCELLED);
        assertThat(terminal.errorType()).isEqualTo(McpErrorType.TIMEOUT);
        assertThat(terminal.method()).isEqualTo(McpMethod.TOOLS_CALL);
        assertThat(terminal.toolName()).isEqualTo(HUNG_TOOL);
        assertThat(session.completions()).hasSize(1);
        assertThat(session.completions().get(0).transportOutcome())
                .as("the completion vocabulary has no timeout outcome; the prototype borrows RESET")
                .isEqualTo(McpTransportOutcome.RESET);
        assertThat(fixture.hung().signal().isCancelled())
                .as("the tool observes cancellation")
                .isTrue();
        assertThat(session.order()).containsExactly("toolInput", "terminal", "scope-open", "completed", "scope-close");

        fixture.hung().release();
        drain(session.context());
        assertThat(session.terminals()).hasSize(1);
        assertThat(session.completions()).hasSize(1);
        assertThat(session.outputs()).isZero();
        assertThat(uncaught).isEmpty();
    }

    @Test
    @DisplayName("e prototype: the settlement guard fences a late interceptor release, so the tool is "
            + "never invoked for a request the deadline already settled")
    void coordinatorDeadlineFencesTheLateInterceptorRelease() throws Exception {
        HoldingInterceptor interceptor = new HoldingInterceptor();
        start(Deadline.prototype(Placement.ROOT, DEADLINE_MS), Set.of(interceptor), Set.of(), null);

        Reply reply = callTool(PLAIN_TOOL, false).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();
        assertThat(reply.status()).isEqualTo(504);

        interceptor.release();
        drain(session.context());

        assertThat(fixture.plain().awaitInvoked(CONFIRMATION_WINDOW))
                .as("the tool must never run for a settled request")
                .isFalse();
        assertThat(fixture.plain().prepares()).isZero();
        assertThat(session.terminals()).hasSize(1);
        assertThat(uncaught).isEmpty();
    }

    @Test
    @DisplayName("e prototype: on a committed SSE response the deadline resets the stream with CANCEL, "
            + "so the client sees an error instead of a silently truncated 200, and a cooperative "
            + "tool stops")
    void coordinatorDeadlineResetsACommittedStreamAndStopsACooperativeTool() throws Exception {
        start(Deadline.prototype(Placement.ROOT, DEADLINE_MS));

        Reply reply = callTool(PROGRESS_TOOL, true).get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Session session = fixture.recorder().awaitNonDiscoverCompletion();

        assertThat(reply.status()).as("the head was committed before the cut").isEqualTo(200);
        assertThat(reply.failure())
                .as("the client sees a stream reset, not a clean end")
                .isNotNull();
        assertThat(reply.failure()).isInstanceOf(io.vertx.core.http.StreamResetException.class);
        assertThat(((io.vertx.core.http.StreamResetException) reply.failure()).getCode())
                .isEqualTo(PROTOTYPE_RESET_CODE);
        McpRequestTerminalEvent terminal = session.terminals().get(0);
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.CANCELLED);
        assertThat(terminal.errorType()).isEqualTo(McpErrorType.TIMEOUT);
        assertThat(session.completions().get(0).responseCommitted()).isTrue();
        assertThat(fixture.progress().awaitStoppedEarly(Duration.ofSeconds(ASYNC_TIMEOUT_SECONDS)))
                .as("a tool that polls the cancellation signal stops")
                .isTrue();
        assertThat(fixture.progress().ticks()).isLessThan(Tool.PROGRESS_TICKS);
    }

    @Test
    @DisplayName("e prototype: before MCP has begun the request (stalled upload) the same deadline "
            + "falls back to a plain 504")
    void coordinatorDeadlineFallsBackToAPlainResponseBeforeTheRequestIsBegun() throws Exception {
        start(Deadline.prototype(Placement.ROOT, DEADLINE_MS));

        StalledUpload upload = stalledUpload();

        assertThat(upload.reply().failure()).isNull();
        assertThat(upload.reply().status()).isEqualTo(504);
        assertThat(fixture.recorder().unsettled()).isEmpty();
        assertThat(uncaught).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------------

    private void start(Deadline deadline) throws Exception {
        start(deadline, Set.of(), Set.of(), null, HttpVersion.HTTP_2);
    }

    private void start(
            Deadline deadline,
            Set<McpRequestInterceptor> requestInterceptors,
            Set<McpToolInterceptor> toolInterceptors,
            AuthorizationDecisionPoint decisionPoint)
            throws Exception {
        start(deadline, requestInterceptors, toolInterceptors, decisionPoint, HttpVersion.HTTP_2);
    }

    private void start(
            Deadline deadline,
            Set<McpRequestInterceptor> requestInterceptors,
            Set<McpToolInterceptor> toolInterceptors,
            AuthorizationDecisionPoint decisionPoint,
            HttpVersion version)
            throws Exception {
        fixture = new Fixture(vertx, deadline, requestInterceptors, toolInterceptors, decisionPoint);
        client = vertx.createHttpClient(
                new HttpClientOptions().setProtocolVersion(version).setHttp2ClearTextUpgrade(false));
    }

    private CompletableFuture<Reply> callTool(String toolName, boolean withProgressToken) {
        return post(toolCallBody(toolName, withProgressToken), "tools/call", toolName, "application/json");
    }

    private RequestOptions options(String mcpMethod, String mcpName, String contentType) {
        return new RequestOptions()
                .setMethod(HttpMethod.POST)
                .setHost(LOOPBACK)
                .setPort(fixture.port())
                .setURI(REQUEST_PATH)
                .putHeader("content-type", contentType)
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", mcpMethod)
                .putHeader("Mcp-Name", mcpName);
    }

    private CompletableFuture<Reply> post(Buffer body, String mcpMethod, String mcpName, String contentType) {
        CompletableFuture<Reply> done = new CompletableFuture<>();
        long start = System.nanoTime();
        client.request(options(mcpMethod, mcpName, contentType))
                .compose(request -> request.send(body))
                .onComplete(sent -> {
                    if (sent.failed()) {
                        done.complete(new Reply(-1, null, null, sent.cause(), elapsedMs(start)));
                        return;
                    }
                    HttpClientResponse response = sent.result();
                    int status = response.statusCode();
                    String contentTypeHeader = response.getHeader("content-type");
                    response.body()
                            .onComplete(read -> done.complete(
                                    read.succeeded()
                                            ? new Reply(
                                                    status,
                                                    contentTypeHeader,
                                                    read.result().toString(),
                                                    null,
                                                    elapsedMs(start))
                                            : new Reply(
                                                    status, contentTypeHeader, null, read.cause(), elapsedMs(start))));
                });
        return done;
    }

    /**
     * Sends the first half of a tools/call body and then stalls: the request is neither ended nor
     * reset, so body aggregation never completes.
     */
    private StalledUpload stalledUpload() throws Exception {
        CompletableFuture<Reply> done = new CompletableFuture<>();
        CompletableFuture<HttpClientRequest> request = new CompletableFuture<>();
        long start = System.nanoTime();
        Buffer full = toolCallBody(HUNG_TOOL, false);
        Buffer half = full.getBuffer(0, full.length() / 2);
        client.request(options("tools/call", HUNG_TOOL, "application/json")).onComplete(created -> {
            if (created.failed()) {
                done.complete(new Reply(-1, null, null, created.cause(), elapsedMs(start)));
                return;
            }
            HttpClientRequest stalled = created.result();
            stalled.setChunked(true);
            request.complete(stalled);
            stalled.response().onComplete(responded -> {
                if (responded.failed()) {
                    done.complete(new Reply(-1, null, null, responded.cause(), elapsedMs(start)));
                    return;
                }
                HttpClientResponse response = responded.result();
                int status = response.statusCode();
                response.body()
                        .onComplete(read -> done.complete(new Reply(
                                status,
                                response.getHeader("content-type"),
                                read.succeeded() ? read.result().toString() : null,
                                read.failed() ? read.cause() : null,
                                elapsedMs(start))));
            });
            stalled.write(half);
        });
        Reply reply = done.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // Completing the request after the response must not wedge the connection or the harness.
        CompletableFuture<String> ended = new CompletableFuture<>();
        request.get()
                .end()
                .onComplete(result -> ended.complete(
                        result.succeeded() ? "ended" : result.cause().toString()));
        return new StalledUpload(reply, ended.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
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

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static Buffer toolCallBody(String toolName, boolean withProgressToken) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        if (withProgressToken) {
            meta.put("progressToken", "spike-progress");
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

    private static Buffer discoverBody() {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", DISCOVER_METHOD)
                .put("params", new JsonObject().put("_meta", meta))
                .toBuffer();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** The per-transport expectations for a request settled by a rejection written from a timer. */
    private record Expect(HttpVersion version, McpTransportOutcome transport, boolean cancellationFired) {}

    private static final Expect H2C = new Expect(HttpVersion.HTTP_2, McpTransportOutcome.RESET, true);
    private static final Expect H1 = new Expect(HttpVersion.HTTP_1_1, McpTransportOutcome.WRITTEN, false);

    /** HTTP/2 {@code CANCEL}: the stream is no longer needed. */
    private static final long PROTOTYPE_RESET_CODE = 0x8;

    /** What the client observed for one request. */
    private record Reply(int status, String contentType, String body, Throwable failure, long elapsedMs) {}

    private record StalledUpload(Reply reply, String endResult) {}

    /** Periodic sibling {@code server/discover} traffic multiplexed on the same connection. */
    private final class Siblings {
        private final AtomicInteger attempted = new AtomicInteger();
        private final List<String> failures = new CopyOnWriteArrayList<>();
        private final List<CompletableFuture<Reply>> outstanding = new CopyOnWriteArrayList<>();
        private volatile long timerId = -1;

        void start() {
            timerId = vertx.setPeriodic(SIBLING_INTERVAL_MS, ignored -> {
                attempted.incrementAndGet();
                CompletableFuture<Reply> reply =
                        post(discoverBody(), DISCOVER_METHOD, DISCOVER_METHOD, "application/json");
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

    // ---------------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------------

    private enum Placement {
        NONE,
        /** On the main router, after the request-context lifecycle and ahead of the mount. */
        ROOT,
        /** On the mount's own router, appended after the MCP chain. */
        MOUNT_APPENDED,
        /** On the mount's own router, ordered ahead of cheap admission. */
        MOUNT_FIRST
    }

    private enum Kind {
        TIMEOUT_HANDLER,
        COORDINATOR_PROTOTYPE
    }

    private record Deadline(Placement placement, Kind kind, long millis, int status) {
        static Deadline none() {
            return new Deadline(Placement.NONE, Kind.TIMEOUT_HANDLER, 0, 503);
        }

        static Deadline timeoutHandler(Placement placement, long millis, int status) {
            return new Deadline(placement, Kind.TIMEOUT_HANDLER, millis, status);
        }

        static Deadline prototype(Placement placement, long millis) {
            return new Deadline(placement, Kind.COORDINATOR_PROTOTYPE, millis, 504);
        }
    }

    /** One real port-0 h2c-capable mount with four tools and the deadline under test installed. */
    private static final class Fixture {
        private final HttpServer server;
        private final int port;
        private final Tool hung;
        private final Tool progress;
        private final Tool plain;
        private final Tool restricted;
        private final Recorder recorder = new Recorder();
        private final Set<HttpConnection> connections = ConcurrentHashMap.newKeySet();
        private final AtomicInteger deadlineRuns = new AtomicInteger();
        private final List<String> endOutcomes = new CopyOnWriteArrayList<>();
        private final List<McpRequestCompletedEvent> listenerEvents = new CopyOnWriteArrayList<>();

        private Fixture(
                Vertx vertx,
                Deadline deadline,
                Set<McpRequestInterceptor> requestInterceptors,
                Set<McpToolInterceptor> toolInterceptors,
                AuthorizationDecisionPoint decisionPoint)
                throws Exception {
            McpAccessMode permitAll = McpAccessMode.PERMIT_ALL;
            this.hung = new Tool(vertx, HUNG_TOOL, new McpToolAccess(permitAll, List.of(), null), Tool.Behavior.HANG);
            this.progress = new Tool(
                    vertx, PROGRESS_TOOL, new McpToolAccess(permitAll, List.of(), null), Tool.Behavior.PROGRESS);
            this.plain =
                    new Tool(vertx, PLAIN_TOOL, new McpToolAccess(permitAll, List.of(), null), Tool.Behavior.IMMEDIATE);
            this.restricted = new Tool(
                    vertx,
                    RESTRICTED_TOOL,
                    new McpToolAccess(McpAccessMode.RESTRICTED, List.of("ops"), null),
                    Tool.Behavior.IMMEDIATE);
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .authenticationScheme(SCHEME_NAME)
                    .build();
            McpToolRegistry registry = McpToolRegistry.build(Set.of(hung, progress, plain, restricted));
            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
            McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                    Optional.ofNullable(decisionPoint),
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
                            Set.of((McpRequestCompletedListener) listenerEvents::add),
                            requestInterceptors,
                            toolInterceptors,
                            httpConfig,
                            registry,
                            policyEnforcer,
                            NO_OP_CONTEXT_HOLDER,
                            new CorrelationContextFactory(Optional.empty())),
                    Set.of(new OptionalRouteAuthHandler()),
                    identityResolution(securityRuntime),
                    httpConfig,
                    registry);
            Router root = Router.router(vertx);
            root.route().handler(ctx -> {
                connections.add(ctx.request().connection());
                if ("tools/call".equals(ctx.request().getHeader("Mcp-Method"))) {
                    ctx.addEndHandler(outcome -> endOutcomes.add(
                            outcome.succeeded() ? "succeeded" : outcome.cause().toString()));
                }
                ctx.next();
            });
            root.route().handler(new RequestContextLifecycle());
            Handler<RoutingContext> deadlineHandler = deadlineHandler(deadline);
            if (deadline.placement() == Placement.ROOT) {
                root.route(config.mountPath()).handler(deadlineHandler);
            }
            Router mountRouter = await(mount.createRouter(vertx));
            if (deadline.placement() == Placement.MOUNT_APPENDED) {
                mountRouter.route().handler(deadlineHandler);
            } else if (deadline.placement() == Placement.MOUNT_FIRST) {
                mountRouter.route().order(Integer.MIN_VALUE).handler(deadlineHandler);
            }
            root.route(config.mountPath()).subRouter(mountRouter);
            HttpServerOptions serverOptions =
                    httpConfig.toHttpServerOptions().setPort(0).setHost(LOOPBACK);
            this.server = await(
                    vertx.createHttpServer(serverOptions).requestHandler(root).listen());
            this.port = server.actualPort();
        }

        private Handler<RoutingContext> deadlineHandler(Deadline deadline) {
            if (deadline.placement() == Placement.NONE) {
                return RoutingContext::next;
            }
            if (deadline.kind() == Kind.TIMEOUT_HANDLER) {
                TimeoutHandler timeoutHandler = TimeoutHandler.create(deadline.millis(), deadline.status());
                return ctx -> {
                    if ("tools/call".equals(ctx.request().getHeader("Mcp-Method"))) {
                        deadlineRuns.incrementAndGet();
                    }
                    timeoutHandler.handle(ctx);
                };
            }
            return ctx -> {
                if ("tools/call".equals(ctx.request().getHeader("Mcp-Method"))) {
                    deadlineRuns.incrementAndGet();
                }
                armCoordinatorDeadline(ctx, deadline.millis());
                ctx.next();
            };
        }

        /**
         * Prototype of a dispatcher-level deadline: on expiry it settles through the request's own
         * completion coordinator (one terminal, one completion, cancellation fired, later stages
         * fenced) and then ends or resets the response. Before MCP has begun the request it falls
         * back to failing the routing context.
         */
        private static void armCoordinatorDeadline(RoutingContext ctx, long millis) {
            Instant startedAt = Instant.now();
            long timer = ctx.vertx().setTimer(millis, id -> {
                if (ctx.response().ended() || ctx.response().closed()) {
                    return;
                }
                Object slot = ctx.get(McpRequestDispatcher.COMPLETION_COORDINATOR_KEY);
                if (!(slot instanceof McpCompletionCoordinator coordinator)) {
                    ctx.fail(504);
                    return;
                }
                String header = ctx.request().getHeader("Mcp-Name");
                String toolName = header != null && McpToolDescriptor.isValidName(header)
                        ? header
                        : McpRequestTerminalEvent.UNKNOWN_TOOL_NAME;
                McpMethod method = McpRequestTerminalEvent.UNKNOWN_TOOL_NAME.equals(toolName)
                        ? McpMethod.OTHER
                        : McpMethod.TOOLS_CALL;
                boolean committed = ctx.response().headWritten();
                McpRequestTerminalEvent terminal = McpRequestTerminalEvent.cancelled(
                        startedAt,
                        Instant.now(),
                        method,
                        toolName,
                        McpErrorType.TIMEOUT,
                        504,
                        null,
                        null,
                        null,
                        null,
                        null,
                        dev.vertique.security.origin.RequestOrigin.unknown());
                RequestCompletionRecorder.claimForOtherTransport(ctx);
                coordinator.settleReset(terminal, committed);
                if (committed) {
                    ctx.response().reset(PROTOTYPE_RESET_CODE);
                } else {
                    ctx.response().setStatusCode(504).end();
                }
            });
            ctx.addEndHandler(done -> ctx.vertx().cancelTimer(timer));
        }

        HttpServer server() {
            return server;
        }

        int port() {
            return port;
        }

        Tool hung() {
            return hung;
        }

        Tool progress() {
            return progress;
        }

        Tool plain() {
            return plain;
        }

        Tool restricted() {
            return restricted;
        }

        Recorder recorder() {
            return recorder;
        }

        Set<HttpConnection> connections() {
            return connections;
        }

        int deadlineRuns() {
            return deadlineRuns.get();
        }

        List<String> endOutcomes() {
            return endOutcomes;
        }

        /** Completion events delivered to the completed listener, excluding sibling discovers. */
        List<McpRequestCompletedEvent> listenerEvents() {
            return listenerEvents.stream()
                    .filter(event -> event.terminal().method() != McpMethod.SERVER_DISCOVER)
                    .toList();
        }

        private static IdentityResolutionMiddleware identityResolution(SecurityRuntime securityRuntime) {
            return new IdentityResolutionMiddleware(
                    Set.of(new AnonymousIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    new SecurityEventEmitter(Set.of()),
                    securityRuntime,
                    NO_OP_CONTEXT_HOLDER);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Tools, interceptors, decision point
    // ---------------------------------------------------------------------------------------------

    /** One fixture tool whose behavior is selected at construction. */
    private static final class Tool implements McpToolInvoker {
        enum Behavior {
            HANG,
            PROGRESS,
            IMMEDIATE
        }

        static final int PROGRESS_TICKS = 12;
        static final long PROGRESS_TICK_MS = 100;

        private final Vertx vertx;
        private final McpToolDescriptor descriptor;
        private final Behavior behavior;
        private final AtomicInteger prepares = new AtomicInteger();
        private final AtomicInteger invokes = new AtomicInteger();
        private final AtomicInteger ticks = new AtomicInteger();
        private final CompletableFuture<Void> firstInvoke = new CompletableFuture<>();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private final CompletableFuture<Void> stoppedEarly = new CompletableFuture<>();
        private final Promise<McpToolResult<?>> gate = Promise.promise();
        private volatile McpCancellationSignal signal;
        private volatile dev.vertique.resilience.Timeout handlerTimeout;

        Tool(Vertx vertx, String name, McpToolAccess access, Behavior behavior) {
            this.vertx = vertx;
            this.behavior = behavior;
            this.descriptor = new McpToolDescriptor(
                    name,
                    null,
                    "Per-request deadline spike fixture tool.",
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
                    invokes.incrementAndGet();
                    firstInvoke.complete(null);
                    return switch (behavior) {
                        case HANG ->
                            handlerTimeout != null
                                    ? handlerTimeout.<McpToolResult<?>>execute(gate::future)
                                    : gate.future();
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
                    finished.complete(null);
                    result.tryComplete(McpToolResult.text("streamed"));
                }
            });
            return result.future();
        }

        void release() {
            gate.tryComplete(McpToolResult.text("late"));
        }

        /** Wraps the hung invocation in the resilience timeout, as a handler-level timeout does. */
        void useHandlerTimeout(dev.vertique.resilience.Timeout timeout) {
            this.handlerTimeout = timeout;
        }

        void fail() {
            gate.tryFail(new IllegalStateException("tool failed"));
        }

        void awaitInvoked() throws Exception {
            firstInvoke.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        boolean awaitInvoked(Duration window) throws Exception {
            return awaitDone(firstInvoke, window);
        }

        boolean awaitFinished(Duration window) throws Exception {
            return awaitDone(finished, window);
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

        int invokes() {
            return invokes.get();
        }

        int ticks() {
            return ticks.get();
        }
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

    /** A tool interceptor whose future is held until the test releases it. */
    private static final class HoldingToolInterceptor implements McpToolInterceptor {
        private final AtomicInteger calls = new AtomicInteger();
        private final Promise<Void> gate = Promise.promise();

        @Override
        public Future<Void> beforeInvocation(McpToolInvocationContext context) {
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

    /** An authorization decision held until the test releases it with a permit. */
    private static final class HeldDecisionPoint implements AuthorizationDecisionPoint {
        private final AtomicInteger calls = new AtomicInteger();
        private final Promise<AuthorizationDecision> gate = Promise.promise();

        @Override
        public Future<AuthorizationDecision> decide(AuthorizationRequest request) {
            calls.incrementAndGet();
            return gate.future();
        }

        void releaseWithPermit() {
            gate.tryComplete(AuthorizationDecision.permit("PERMITTED"));
        }

        int calls() {
            return calls.get();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle recording
    // ---------------------------------------------------------------------------------------------

    /** Records one {@link Session} per request that MCP admitted past its own begin stage. */
    private static final class Recorder implements McpRequestLifecycleObserver {
        private final List<Session> sessions = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Session> firstNonDiscoverCompletion = new CompletableFuture<>();

        @Override
        public McpRequestObservation open(Instant startedAt) {
            Session session = new Session(Vertx.currentContext(), this);
            sessions.add(session);
            return session;
        }

        List<Session> sessions() {
            return sessions;
        }

        /** Sessions whose terminal is not a sibling {@code server/discover}. */
        List<Session> nonDiscover() {
            return sessions.stream()
                    .filter(session -> session.terminals().stream()
                            .anyMatch(terminal -> terminal.method() != McpMethod.SERVER_DISCOVER))
                    .toList();
        }

        /** Sessions that opened but never published a terminal. */
        List<Session> unsettled() {
            return sessions.stream()
                    .filter(session -> session.terminals().isEmpty())
                    .toList();
        }

        Session awaitNonDiscoverCompletion() throws Exception {
            return firstNonDiscoverCompletion.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static final class Session implements McpCompletionScope, McpToolValueObservation {
        private final Context context;
        private final Recorder recorder;
        private final List<String> order = new CopyOnWriteArrayList<>();
        private final List<McpRequestTerminalEvent> terminals = new CopyOnWriteArrayList<>();
        private final List<McpRequestCompletedEvent> completions = new CopyOnWriteArrayList<>();
        private final AtomicInteger outputs = new AtomicInteger();
        private final CompletableFuture<Void> completed = new CompletableFuture<>();

        Session(Context context, Recorder recorder) {
            this.context = context;
            this.recorder = recorder;
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            terminals.add(observation.event());
            order.add("terminal");
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            completions.add(event);
            order.add("completed");
            completed.complete(null);
            if (event.terminal().method() != McpMethod.SERVER_DISCOVER) {
                recorder.firstNonDiscoverCompletion.complete(this);
            }
        }

        @Override
        public void onToolInput(McpToolInputObservation observation) {
            order.add("toolInput");
        }

        @Override
        public void onToolOutput(McpToolOutputObservation observation) {
            outputs.incrementAndGet();
            order.add("toolOutput");
        }

        @Override
        public AutoCloseable openCompletionScope() {
            order.add("scope-open");
            return () -> order.add("scope-close");
        }

        Context context() {
            return context;
        }

        void awaitCompleted() throws Exception {
            completed.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        List<String> order() {
            return order;
        }

        List<McpRequestTerminalEvent> terminals() {
            return terminals;
        }

        List<McpRequestCompletedEvent> completions() {
            return completions;
        }

        int outputs() {
            return outputs.get();
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
