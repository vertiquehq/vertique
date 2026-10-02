// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextScopes;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
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
import dev.vertique.rest.core.events.HttpRequestCompletedEvent;
import dev.vertique.rest.core.events.HttpRequestCompletedListener;
import dev.vertique.rest.core.events.RequestCompletionRecorder;
import dev.vertique.rest.core.events.RestRequestCompletedEvent;
import dev.vertique.rest.core.events.RestRequestCompletedListener;
import dev.vertique.rest.core.events.RestRequestCompletionEmitter;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.HolderBackedSecurityRuntime;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.RestAuthenticationEvidence;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.AuthMethodKind;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContextSnapshot;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.security.verification.CustomVerificationSource;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.impl.UserContextInternal;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves which completion events a request that reaches the MCP mount produces when the rest-core
 * completion emitter runs as ROOT middleware.
 *
 * <p>A request MCP settles, by writing its response, by rejecting it after its lifecycle observation
 * opened, or by settling a disconnect or a reset, produces only MCP's own lifecycle events: the
 * observer's terminal and completion, and the {@link McpRequestCompletedListener} event. The emitter
 * produces neither a {@link RestRequestCompletedEvent} nor an {@link HttpRequestCompletedEvent} for it,
 * so REST listeners never see it. A request MCP does not settle completes as an ordinary rest-core
 * request with exactly one event: an admission rejection, a failure that an earlier mount's failure
 * handler takes, and a request rerouted out of the mount that completes on its new target.
 *
 * <p><strong>Harness.</strong> No MCP integration test deploys {@code HttpVerticle}, so each fixture
 * builds the main router by hand in {@code HttpVerticle}'s ROOT order: {@link RequestContextLifecycle},
 * the {@link CompletionBarrier}, the {@link RestRequestCompletionEmitter} with one recording REST
 * listener and one recording HTTP listener, then every sub-router, each mounted with {@code
 * route(path).subRouter(router)} in the order the test gives. The barrier registers its end handler on
 * a request's first routing pass, before the emitter's and before MCP's settlement hook. Vert.x Web runs
 * end handlers once, in reverse registration order, on a normal end and on a lost connection alike, so
 * once the barrier has recorded a request, every rest-core event and every claim for it has already
 * happened. Every "no event" assertion therefore waits for the barrier, never for a fixed sleep.
 *
 * <p><strong>Waits and assertions.</strong> Every wait is bounded and never throws: it reports whether
 * its condition held, and the assertions report the rest. One wait lasts at most {@link #WAIT_BOUND},
 * and all waits of one test share {@link #WAIT_BUDGET}, so a stalled request fails an assertion inside
 * the class timeout instead of timing the test out. Each test ends in one {@code assertAll} over named
 * blocks (witness, MCP, REST, HTTP); each block checks counts before identities, so a missing value
 * fails its block instead of throwing.
 *
 * <p><strong>Clients.</strong> Requests go through a {@link WebClient} that wraps a raw {@link
 * HttpClient}. The raw client never issues a request: it exists for its awaitable {@code close()},
 * because this test owns its {@link Vertx}. The disconnect and reset rows use a raw {@link Socket},
 * the one client that can drop the connection before MCP writes and choose between an orderly close and
 * a reset.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpCompletionClaimIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String MCP_PATH = "/mcp/";
    private static final String UNMATCHED_PATH = "/unmatched";
    private static final String ROOT_PATH = "/*";
    private static final String CATCH_ALL_MOUNT = "/*";
    private static final String REROUTE_MOUNT = "/rerouted/*";
    private static final String REROUTED_OPERATION_PATH = "/rerouted/op";
    private static final String REROUTED_UNMATCHED_PATH = "/rerouted/none";
    private static final String REROUTED_HANGING_PATH = "/rerouted/hang";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String OK_TOOL = "ok";
    private static final String HANG_TOOL = "hang";
    private static final String CLOSED_OBJECT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false}";
    private static final String BEARER_SCHEME = "bearer";
    private static final String REROUTE_SCHEME = "reroute";
    private static final String BEARER_ALICE = "Bearer alice";
    private static final String BEARER_MALLORY = "Bearer mallory";
    private static final String UNTRUSTED_ORIGIN = "https://untrusted.example";
    private static final String SSE_PREFIX = "event: message\ndata: ";
    private static final String REROUTED_BODY = "rerouted";
    private static final String CATCH_ALL_FAILURE_BODY = "catch-all failure";
    private static final String POST = "POST";
    private static final String REENTER_SCHEME = "reenter";
    private static final String ALICE = "alice";
    private static final String COPY_ROLE_HEADER = "X-Copy-Role";
    private static final String COPY_SOURCE = "source";
    private static final String COPY_TARGET = "target";

    /** The body limit the MCP mount enforces; the oversized-body row exceeds it. */
    private static final int MAX_BODY_BYTES = 64 * 1_024;

    /** The body-limit rejection status; the client only checks a 4xx for it, the event the exact code. */
    private static final int BODY_LIMIT_STATUS = 413;

    /** The longest any single wait may last. */
    private static final Duration WAIT_BOUND = Duration.ofSeconds(5);

    /** The total all waits of one test may last, from fixture start, kept well inside the timeout. */
    private static final Duration WAIT_BUDGET = Duration.ofSeconds(14);

    /** Bounded wait for the teardown's closes. */
    private static final long TEARDOWN_SECONDS = 10;

    /** The reroute target's operation descriptor; its REST event must carry this instance. */
    private static final RestOperationDescriptor TARGET_OPERATION = new TargetOperationDescriptor();

    private final Vertx vertx = Vertx.vertx();
    private final long waitDeadlineNanos = System.nanoTime() + WAIT_BUDGET.toNanos();
    private final CompletionBarrier barrier = new CompletionBarrier();
    private final RecordingRestListener restListener = new RecordingRestListener();
    private final RecordingHttpListener httpListener = new RecordingHttpListener();
    private final RecordingObserver observer = new RecordingObserver();
    private final RecordingCompletionListener completionListener = new RecordingCompletionListener();
    private final HangingTool hangingTool = new HangingTool();

    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;
    private int port;

    /**
     * Releases the hanging tool first, so no invocation is left pending, then joins the server and
     * raw-client closes before closing the owned {@link Vertx}. A continuation that the release
     * resumes after its request already settled is silenced by the coordinator's settlement guard.
     *
     * @throws Exception if the closes do not complete within their bound
     */
    @AfterEach
    void tearDown() throws Exception {
        hangingTool.release();
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
        closed.get(TEARDOWN_SECONDS, TimeUnit.SECONDS);
        server = null;
        rawClient = null;
        client = null;
    }

    @Test
    @DisplayName("an admitted tools/call produces only MCP lifecycle events and no rest-core completion event")
    void admittedToolsCallProducesOnlyMcpLifecycleEvents() throws Exception {
        // Given: the MCP mount is the only mount, with no authentication scheme.
        startFixture(mcpMount());

        // When: one admitted tools/call, then a control request that no route matches.
        HttpResponse<Buffer> response = awaitResponse("tools/call", callTool(OK_TOOL));
        HttpResponse<Buffer> control = sendControl();
        completionListener.awaitCount(1, nextWaitNanos());
        boolean recorded = awaitBarrier(2);

        // Then: MCP settled the request once; the only rest-core event is the control's, which shows
        // the emitter and the HTTP listener are live. No REST event means the REST request metrics
        // have nothing to record for the MCP request.
        assertAll(
                "admitted tools/call",
                block("witness", () -> {
                    assertAnsweredSuccessfully(response);
                    assertControlAnswered(control);
                    assertThat(recorded)
                            .as("barrier recorded the tools/call and the control")
                            .isTrue();
                }),
                block("MCP", this::assertMcpSettledOnce),
                block("REST", this::assertNoRestEvent),
                block("HTTP", () -> {
                    assertOnlyControlHttpEvent();
                    assertThat(eventsForPath(MCP_PATH))
                            .as("rest-core events for %s", MCP_PATH)
                            .isEmpty();
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("admissionRejections")
    @DisplayName("each admission rejection produces exactly one HTTP event and no MCP lifecycle event")
    void admissionRejectionProducesExactlyOneHttpEvent(String label, String method, RequestSender request, int status)
            throws Exception {
        // Given: the MCP mount is the only mount, with its body limit.
        startFixture(mcpMount());

        // When: the row's request is sent.
        HttpResponse<Buffer> response = awaitResponse(label, request.send(client, port));
        boolean recorded = awaitBarrier(1);

        // Then: the rejection preceded begin, so the writer found no coordinator and nothing claimed it.
        assertExactlyOneHttpEventAndNoMcpObservation(
                label,
                () -> {
                    assertThat(response).as("response to the rejected request").isNotNull();
                    if (status == BODY_LIMIT_STATUS) {
                        // As the streamable-HTTP contract suite's oversized-body row does, the client only
                        // checks for a 4xx; the server-side event carries the exact status.
                        assertThat(response.statusCode())
                                .as("client status of the body-limit rejection")
                                .isBetween(400, 499);
                    } else {
                        assertThat(response.statusCode())
                                .as("client status of the rejection")
                                .isEqualTo(status);
                    }
                    assertThat(recorded)
                            .as("barrier recorded the rejected request")
                            .isTrue();
                },
                method,
                MCP_PATH,
                status);
    }

    private static Stream<Arguments> admissionRejections() {
        return Stream.of(
                rejection(
                        "method GET (405)",
                        "GET",
                        (client, port) -> client.get(port, LOOPBACK, MCP_PATH).send(),
                        405),
                rejection(
                        "untrusted Origin (403)",
                        POST,
                        (client, port) -> discoverRequest(client, port)
                                .putHeader("Origin", UNTRUSTED_ORIGIN)
                                .sendBuffer(discoverBody()),
                        403),
                rejection(
                        "missing Content-Type (415)",
                        POST,
                        (client, port) ->
                                discoverRequestWithoutContentType(client, port).sendBuffer(discoverBody()),
                        415),
                rejection(
                        "Accept text/html (406)",
                        POST,
                        (client, port) -> discoverRequest(client, port)
                                .putHeader("Accept", "text/html")
                                .sendBuffer(discoverBody()),
                        406),
                rejection(
                        "oversized body (413)",
                        POST,
                        (client, port) -> discoverRequest(client, port)
                                .sendBuffer(Buffer.buffer(new byte[MAX_BODY_BYTES + 1_024])),
                        BODY_LIMIT_STATUS));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rerouteTargets")
    @DisplayName("a request rerouted after begin gets one rest-core event for its target and no MCP completion")
    void admittedRequestReroutedAfterBeginIsDecidedByItsRerouteTarget(
            String label, String target, int expectedStatus, @Nullable String expectedBody, EventKind expectedEvent)
            throws Exception {
        // Given: the MCP mount, whose authentication handler reroutes after begin, then the target mount.
        RerouteTargetRouter targetRouter = new RerouteTargetRouter();
        startFixture(mcpMount(REROUTE_SCHEME, new ReroutingRouteAuthHandler(target)), targetRouter.mount());

        // When: one admitted tools/call is sent to the MCP mount.
        HttpResponse<Buffer> response = awaitResponse("tools/call", callTool(OK_TOOL));
        boolean recorded = awaitBarrier(1);

        // Then: the target decided the request's one rest-core event, and MCP, which opened an
        // observation at begin, settled nothing. MCP's settlement hook runs before the barrier's end
        // handler, so the "no MCP completion" assertion rests on the barrier.
        assertAll(
                label,
                block("witness", () -> {
                    assertThat(response).as("response to the rerouted request").isNotNull();
                    assertThat(response.statusCode())
                            .as("status of the reroute target's response")
                            .isEqualTo(expectedStatus);
                    if (expectedBody != null) {
                        assertThat(response.bodyAsString())
                                .as("body of the reroute target's response")
                                .isEqualTo(expectedBody);
                    }
                    assertThat(recorded)
                            .as("barrier recorded the rerouted request")
                            .isTrue();
                }),
                block("MCP", this::assertMcpOpenedButNeverSettled),
                block("REST", () -> {
                    List<RestRequestCompletedEvent> events = restListener.events();
                    if (expectedEvent != EventKind.REST) {
                        assertThat(events).as("REST listener events").isEmpty();
                        return;
                    }
                    assertThat(events).as("REST listener events").hasSize(1);
                    RestRequestCompletedEvent event = events.get(0);
                    assertThat(event.operation())
                            .as("operation of the REST event")
                            .isSameAs(TARGET_OPERATION);
                    assertThat(event.path()).as("path of the REST event").isEqualTo(target);
                    assertThat(event.statusCode())
                            .as("status of the REST event")
                            .isEqualTo(expectedStatus);
                }),
                block("HTTP", () -> {
                    List<String> events = httpListener.summaries();
                    if (expectedEvent != EventKind.HTTP) {
                        assertThat(events).as("HTTP listener events").isEmpty();
                    } else {
                        assertThat(events).as("HTTP listener events").hasSize(1);
                        assertThat(events)
                                .as("HTTP listener events")
                                .containsExactly(summary(POST, target, expectedStatus));
                    }
                    assertThat(eventsForPath(MCP_PATH))
                            .as("rest-core events for %s", MCP_PATH)
                            .isEmpty();
                }));
    }

    private static Stream<Arguments> rerouteTargets() {
        return Stream.of(
                Arguments.of("operation route", REROUTED_OPERATION_PATH, 200, REROUTED_BODY, EventKind.REST),
                Arguments.of("unmatched path", REROUTED_UNMATCHED_PATH, 404, null, EventKind.HTTP));
    }

    @Test
    @DisplayName("a request that falls through a catch-all sub-router with no failure handler is claimed by MCP")
    void requestFallingThroughCatchAllSubRouterIsClaimedByMcp() throws Exception {
        // Given: a catch-all sub-router with no failure handler, mounted ahead of the MCP mount.
        CatchAllFallThroughRouter catchAll = new CatchAllFallThroughRouter();
        startFixture(catchAll.mount(), mcpMount());

        // When: one admitted tools/call is sent to the MCP mount.
        HttpResponse<Buffer> response = awaitResponse("tools/call", callTool(OK_TOOL));
        completionListener.awaitCount(1, nextWaitNanos());
        boolean recorded = awaitBarrier(1);

        // Then: the request entered the catch-all first, fell through to MCP, and MCP's claim leaves
        // rest-core with no event for it.
        assertAll(
                "fall-through request",
                block("witness", () -> {
                    assertThat(catchAll.entries())
                            .as("entries into the catch-all sub-router")
                            .isOne();
                    assertThat(response).as("tools/call response").isNotNull();
                    assertThat(response.statusCode()).as("tools/call status").isEqualTo(200);
                    assertThat(recorded).as("barrier recorded the tools/call").isTrue();
                }),
                block("MCP", this::assertMcpSettledOnce),
                block("REST", this::assertNoRestEvent),
                block("HTTP", () -> assertThat(httpListener.summaries())
                        .as("HTTP listener events")
                        .isEmpty()));
    }

    @Test
    @DisplayName("a failure after begin that an earlier mount's failure handler takes produces one HTTP event")
    void failureTakenByEarlierMountFailureHandlerProducesOneHttpEvent() throws Exception {
        // Given: a catch-all sub-router with a failure handler, mounted ahead of the MCP mount, whose
        // bearer handler fails an unknown credential after begin.
        CatchAllFailureHandlingRouter catchAll = new CatchAllFailureHandlingRouter();
        startFixture(catchAll.mount(), mcpMount(BEARER_SCHEME, new FailingBearerRouteAuthHandler()));

        // When: one tools/call carries a credential the bearer handler rejects.
        HttpResponse<Buffer> response = awaitResponse("tools/call", callTool(OK_TOOL, BEARER_MALLORY));
        boolean recorded = awaitBarrier(1);

        // Then: the catch-all's failure handler, not MCP's, took the failure, so MCP never wrote or
        // settled, and the unclaimed request has exactly one HTTP event. MCP's settlement hook returned on
        // the succeeded outcome before the barrier recorded the request.
        assertAll(
                "failure taken by an earlier mount",
                block("witness", () -> {
                    assertThat(response).as("tools/call response").isNotNull();
                    assertThat(response.statusCode()).as("tools/call status").isEqualTo(401);
                    assertThat(response.bodyAsString()).as("tools/call body").isEqualTo(CATCH_ALL_FAILURE_BODY);
                    assertThat(catchAll.failures())
                            .as(
                                    "invocations of the catch-all's failure handler (catch-all entries: %d)",
                                    catchAll.entries())
                            .isOne();
                    assertThat(recorded).as("barrier recorded the tools/call").isTrue();
                }),
                block("MCP", this::assertMcpOpenedButNeverSettled),
                block("REST", this::assertNoRestEvent),
                block("HTTP", () -> {
                    List<String> events = httpListener.summaries();
                    assertThat(events).as("HTTP listener events").hasSize(1);
                    assertThat(events).as("HTTP listener events").containsExactly(summary(POST, MCP_PATH, 401));
                }));
    }

    @Test
    @DisplayName("in the default mount order, an authentication failure after begin produces only an MCP completion")
    void authenticationFailureAfterBeginInDefaultOrderProducesOnlyMcpCompletion() throws Exception {
        // Given: the MCP mount is the only mount, so its own failure handler is the first that matches.
        startFixture(mcpMount(BEARER_SCHEME, new FailingBearerRouteAuthHandler()));

        // When: one tools/call carries a credential the bearer handler rejects, then the control request.
        HttpResponse<Buffer> response = awaitResponse("tools/call", callTool(OK_TOOL, BEARER_MALLORY));
        HttpResponse<Buffer> control = sendControl();
        completionListener.awaitCount(1, nextWaitNanos());
        boolean recorded = awaitBarrier(2);

        // Then: MCP rejected the request through its writer and settled it once as an authentication
        // failure; rest-core has only the control's event.
        assertAll(
                "authentication failure in the default mount order",
                block("witness", () -> {
                    assertThat(response).as("tools/call response").isNotNull();
                    assertThat(response.statusCode()).as("tools/call status").isEqualTo(401);
                    assertControlAnswered(control);
                    assertThat(recorded)
                            .as("barrier recorded the tools/call and the control")
                            .isTrue();
                }),
                block("MCP", () -> {
                    assertMcpSettledOnce();
                    McpRequestTerminalEvent terminal =
                            observer.observation(0).terminals().get(0).event();
                    assertThat(terminal.httpStatus()).as("terminal HTTP status").isEqualTo(401);
                    assertThat(terminal.errorType()).as("terminal error type").isEqualTo(McpErrorType.AUTHENTICATION);
                }),
                block("REST", this::assertNoRestEvent),
                block("HTTP", () -> {
                    assertOnlyControlHttpEvent();
                    assertThat(eventsForPath(MCP_PATH))
                            .as("rest-core events for %s", MCP_PATH)
                            .isEmpty();
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("disconnectRows")
    @DisplayName("a disconnect or reset that MCP settles produces no rest-core completion event")
    void disconnectOrResetSettledByMcpProducesNoRestCoreEvent(
            String label,
            DisconnectFixture fixture,
            RawToolCall.CloseMode closeMode,
            Set<McpTransportOutcome> expectedOutcomes)
            throws Exception {
        // Given: the row's fixture, in which a tools/call for the hanging tool parks before MCP writes.
        RerouteTargetRouter target = new RerouteTargetRouter();
        CompletableFuture<Void> parked;
        if (fixture == DisconnectFixture.REROUTED_TO_HANGING_TARGET) {
            startFixture(
                    mcpMount(REROUTE_SCHEME, new ReroutingRouteAuthHandler(REROUTED_HANGING_PATH)), target.mount());
            parked = target.hangingEntered();
        } else {
            startFixture(mcpMount());
            parked = hangingTool.invoked();
        }

        // When: the frame is sent on a raw socket, which closes in the row's mode once the request has
        // parked; then the control request.
        boolean parkedBeforeClose = sendToolCallThenClose(parked, closeMode);
        HttpResponse<Buffer> control = sendControl();
        completionListener.awaitCount(1, nextWaitNanos());
        boolean recorded = awaitBarrier(2);

        // Then: MCP settled the lost connection once and claimed it in its settlement hook, so rest-core
        // has only the control's event.
        assertAll(
                label,
                block("witness", () -> {
                    if (fixture == DisconnectFixture.REROUTED_TO_HANGING_TARGET) {
                        assertThat(target.hangingEntries())
                                .as("entries into the hanging reroute target")
                                .isOne();
                    } else {
                        assertThat(parkedBeforeClose)
                                .as("hang tool invoked before the close")
                                .isTrue();
                    }
                    assertControlAnswered(control);
                    assertThat(recorded)
                            .as("barrier recorded the tools/call and the control")
                            .isTrue();
                }),
                block("MCP", () -> {
                    assertMcpSettledOnce();
                    assertThat(completionListener.events().get(0).transportOutcome())
                            .as("transport outcome of the MCP completion")
                            .isIn(expectedOutcomes);
                }),
                block("REST", this::assertNoRestEvent),
                block("HTTP", () -> {
                    assertOnlyControlHttpEvent();
                    assertThat(eventsForPath(MCP_PATH))
                            .as("rest-core events for %s", MCP_PATH)
                            .isEmpty();
                    assertThat(eventsForPath(REROUTED_HANGING_PATH))
                            .as("rest-core events for %s", REROUTED_HANGING_PATH)
                            .isEmpty();
                }));
    }

    private static Stream<Arguments> disconnectRows() {
        // A close before the write settles as DISCONNECTED or RESET; a reset before the write as RESET.
        return Stream.of(
                Arguments.of(
                        "disconnect",
                        DisconnectFixture.MCP_ONLY,
                        RawToolCall.CloseMode.DISCONNECT,
                        EnumSet.of(McpTransportOutcome.DISCONNECTED, McpTransportOutcome.RESET)),
                Arguments.of(
                        "reset",
                        DisconnectFixture.MCP_ONLY,
                        RawToolCall.CloseMode.RESET,
                        EnumSet.of(McpTransportOutcome.RESET)),
                Arguments.of(
                        "rerouted, then disconnect",
                        DisconnectFixture.REROUTED_TO_HANGING_TARGET,
                        RawToolCall.CloseMode.DISCONNECT,
                        EnumSet.of(McpTransportOutcome.DISCONNECTED, McpTransportOutcome.RESET)));
    }

    /**
     * A reroute back to the MCP mount path re-enters the MCP router at its first route, so {@code begin}
     * runs again for the same request, with the same {@code data()} and the same end handlers. The
     * re-entered {@code begin} reuses the coordinator the first pass built: the request keeps its one
     * lifecycle observation and its first pass's settlement hook, and gets exactly one MCP completion,
     * whether it then completes normally, disconnects or is reset.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("reentryRows")
    @DisplayName("a request rerouted back into MCP opens one observation and gets exactly one MCP completion")
    void requestReroutedBackIntoMcpReusesItsCoordinator(
            String label,
            String tool,
            @Nullable RawToolCall.CloseMode closeMode,
            Set<McpTransportOutcome> expectedOutcomes)
            throws Exception {
        // Given: the MCP mount is the only mount; its authentication handler reroutes the request's first
        // pass back to the mount path and lets the second pass continue anonymously.
        ReenteringRouteAuthHandler reentering = new ReenteringRouteAuthHandler();
        startFixture(mcpMount(REENTER_SCHEME, reentering));

        // When: the row's tools/call is answered through the client, or parks in the hanging tool on its
        // second pass and then loses its connection in the row's mode; then the control request.
        HttpResponse<Buffer> response = closeMode == null ? awaitResponse("tools/call", callTool(tool)) : null;
        boolean parkedBeforeClose =
                closeMode != null && sendToolCallThenClose(tool, hangingTool.invoked(), closeMode, Map.of());
        HttpResponse<Buffer> control = sendControl();
        completionListener.awaitCount(1, nextWaitNanos());
        boolean recorded = awaitBarrier(2);

        // Then: the handler ran on both passes, so the reroute re-entered the mount and begin ran twice.
        // MCP opened one observation and settled it once, and its claim leaves rest-core with only the
        // control's event. A coordinator opens its observers in its constructor, inside begin, so the open
        // count is final before the response starts, and an observation completes at most once.
        assertAll(
                label,
                block("re-entry witness", () -> {
                    assertThat(reentering.invocations())
                            .as("invocations of the re-entering authentication handler")
                            .isEqualTo(2);
                    if (closeMode == null) {
                        assertAnsweredSuccessfully(response);
                    } else {
                        assertThat(parkedBeforeClose)
                                .as("hang tool invoked before the close")
                                .isTrue();
                    }
                    assertControlAnswered(control);
                    assertThat(recorded)
                            .as("barrier recorded the tools/call and the control")
                            .isTrue();
                }),
                block("MCP", () -> assertMcpSettledOnceWithOutcome(expectedOutcomes)),
                block("REST", this::assertNoRestEvent),
                block("HTTP", () -> {
                    assertOnlyControlHttpEvent();
                    assertThat(eventsForPath(MCP_PATH))
                            .as("rest-core events for %s", MCP_PATH)
                            .isEmpty();
                }));
    }

    private static Stream<Arguments> reentryRows() {
        // A normal end is WRITTEN; a close before the write settles as DISCONNECTED or RESET, a reset as RESET.
        return Stream.of(
                Arguments.of("normal", OK_TOOL, null, EnumSet.of(McpTransportOutcome.WRITTEN)),
                Arguments.of(
                        "disconnect",
                        HANG_TOOL,
                        RawToolCall.CloseMode.DISCONNECT,
                        EnumSet.of(McpTransportOutcome.DISCONNECTED, McpTransportOutcome.RESET)),
                Arguments.of("reset", HANG_TOOL, RawToolCall.CloseMode.RESET, EnumSet.of(McpTransportOutcome.RESET)));
    }

    /**
     * Other code can copy one request's coordinator into another request's {@code data()}: the
     * coordinator key is one predictable string, and application ROOT middleware runs before the MCP
     * mount. MCP uses a coordinator only when it belongs to the request at hand, by {@code
     * HttpServerRequest} identity (review findings CX-F-006 and SEC-L16). So the target request's {@code
     * begin} builds its own coordinator, and neither request's lifecycle events reach the other, whether
     * the source request is still in flight or already settled.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("copiedCoordinatorRows")
    @DisplayName("a coordinator copied from another request is not reused")
    void coordinatorCopiedFromAnotherRequestIsNotReused(
            String label, String sourceTool, boolean sourceSettledBeforeCopy) throws Exception {
        // Given: the MCP mount is the only mount, with no authentication scheme, behind application ROOT
        // middleware that records the source request's routing context and puts the published value into
        // the target request's coordinator slot before the target reaches begin.
        AtomicReference<Object> published = new AtomicReference<>();
        CoordinatorCopyingHandler copying = new CoordinatorCopyingHandler(published);
        startFixture(List.of(copying), mcpMount());

        // When: the source request A is past begin, in flight in the hanging tool or settled; that signal
        // orders begin before the test reads A's coordinator from A's routing context and publishes it.
        // Then the target request B is sent and completes; then an in-flight A is released and completes;
        // then the control request.
        Future<HttpResponse<Buffer>> sourceCall = callToolWithCopyRole(sourceTool, COPY_SOURCE);
        boolean sourcePastBegin;
        if (sourceSettledBeforeCopy) {
            awaitResponse("source tools/call", sourceCall);
            sourcePastBegin = completionListener.awaitCount(1, nextWaitNanos());
        } else {
            sourcePastBegin = awaitSignal("the source's hang tool to be invoked", hangingTool.invoked());
        }
        Object sourceCoordinator = copying.sourceSlot();
        ObservationSnapshot sourceBeforeTarget = observationSnapshot(0);
        published.set(sourceCoordinator);
        HttpResponse<Buffer> targetResponse =
                awaitResponse("target tools/call", callToolWithCopyRole(OK_TOOL, COPY_TARGET));
        completionListener.awaitCount(sourceSettledBeforeCopy ? 2 : 1, nextWaitNanos());
        Object targetSlot = copying.targetSlot();
        ObservationSnapshot sourceWhenTargetCompleted = observationSnapshot(0);
        if (!sourceSettledBeforeCopy) {
            hangingTool.release();
        }
        HttpResponse<Buffer> sourceResponse = awaitResponse("source tools/call", sourceCall);
        completionListener.awaitCount(2, nextWaitNanos());
        HttpResponse<Buffer> control = sendControl();
        boolean recorded = awaitBarrier(3);

        // Then: B reached begin with A's coordinator in its slot, yet built its own, so each request has
        // its own observation, settled once by that request. MCP claimed both, so rest-core has only the
        // control's event.
        assertAll(
                label,
                block("copy witness", () -> {
                    assertThat(sourcePastBegin)
                            .as("source request past begin before the copy")
                            .isTrue();
                    assertThat(sourceCoordinator)
                            .as("coordinator in the source request's slot")
                            .isInstanceOf(McpCompletionCoordinator.class);
                    assertThat(copying.copiedValue())
                            .as("value copied into the target request's slot")
                            .isSameAs(sourceCoordinator);
                    assertToolCallAnswered("target", targetResponse);
                    assertThat(sourceResponse).as("source tools/call response").isNotNull();
                    assertThat(sourceResponse.statusCode())
                            .as("source tools/call status")
                            .isEqualTo(200);
                    assertControlAnswered(control);
                    assertThat(recorded)
                            .as("barrier recorded the source, the target and the control")
                            .isTrue();
                }),
                block("MCP", () -> {
                    assertThat(observer.openCount())
                            .as("MCP observations opened (%s)", mcpEventCounts())
                            .isEqualTo(2);
                    assertThat(targetSlot)
                            .as("coordinator in the target request's slot after it completed")
                            .isInstanceOf(McpCompletionCoordinator.class)
                            .isNotSameAs(sourceCoordinator);
                    assertObservationSettledOnce("target", observer.observation(1), OK_TOOL);
                    if (sourceSettledBeforeCopy) {
                        assertThat(sourceBeforeTarget)
                                .as("source observation before the target was sent")
                                .isNotNull();
                        assertThat(sourceBeforeTarget.terminals())
                                .as("terminals of the source observation before the target was sent")
                                .hasSize(1);
                        assertThat(sourceBeforeTarget.completions())
                                .as("completions of the source observation before the target was sent")
                                .hasSize(1);
                        RecordedObservation source = observer.observation(0);
                        assertThat(source.terminals())
                                .as("terminals of the source observation after the target")
                                .containsExactlyElementsOf(sourceBeforeTarget.terminals());
                        assertThat(source.completions())
                                .as("completions of the source observation after the target")
                                .containsExactlyElementsOf(sourceBeforeTarget.completions());
                    } else {
                        assertThat(sourceWhenTargetCompleted)
                                .as("source observation when the target completed")
                                .isNotNull();
                        assertThat(sourceWhenTargetCompleted.terminals())
                                .as("terminals of the source observation when the target completed")
                                .isEmpty();
                        assertThat(sourceWhenTargetCompleted.completions())
                                .as("completions of the source observation when the target completed")
                                .isEmpty();
                    }
                    assertObservationSettledOnce("source", observer.observation(0), sourceTool);
                    assertThat(completionListener.events())
                            .as("MCP completion listener events")
                            .hasSize(2);
                }),
                block("REST", this::assertNoRestEvent),
                block("HTTP", () -> {
                    assertOnlyControlHttpEvent();
                    assertThat(eventsForPath(MCP_PATH))
                            .as("rest-core events for %s", MCP_PATH)
                            .isEmpty();
                }));
    }

    private static Stream<Arguments> copiedCoordinatorRows() {
        return Stream.of(Arguments.of("in flight", HANG_TOOL, false), Arguments.of("settled", OK_TOOL, true));
    }

    /**
     * A request that authenticates only on its re-entered pass settles a disconnect or a reset with that
     * pass's principal (FR-003; review finding CX-F-007). The reused settlement hook builds its terminal
     * when it runs and reads the security runtime then. Identity resolution on the second pass binds the
     * security context and registers its scope with the request's lifecycle handle; a reroute reuses that
     * one handle, whose cleanup, registered first, runs after the hook. The holder-backed runtime's scope
     * restores the prior binding on close, as in production, so a cleanup that ran before the hook would
     * leave the terminal with no security snapshot.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("secondPassAuthenticationRows")
    @DisplayName("a re-entered request that authenticates on its second pass settles with that principal")
    void reenteredRequestAuthenticatedOnSecondPassSettlesWithItsPrincipal(
            String label, RawToolCall.CloseMode closeMode, Set<McpTransportOutcome> expectedOutcomes) throws Exception {
        // Given: the re-entry fixture with the holder-backed security wiring: one security runtime shared by
        // the dispatcher, the policy enforcer and identity resolution, and a resolver that resolves the
        // evidence's subject as a user.
        ReenteringRouteAuthHandler reentering = new ReenteringRouteAuthHandler();
        startFixture(mcpMount(
                REENTER_SCHEME,
                new HolderBackedSecurityRuntime((securityContext, secure) -> null),
                new SubjectRoleIdentityResolver(),
                reentering));

        // When: the frame carries alice's credential. The first pass reroutes without reading it; the
        // second pass authenticates alice and parks in the hanging tool; the socket then closes in the row's
        // mode; then the control request.
        boolean parkedBeforeClose = sendToolCallThenClose(
                HANG_TOOL, hangingTool.invoked(), closeMode, Map.of("Authorization", BEARER_ALICE));
        HttpResponse<Buffer> control = sendControl();
        completionListener.awaitCount(1, nextWaitNanos());
        boolean recorded = awaitBarrier(2);
        System.out.println("completion actors: " + completionActors() + " (" + label + ")");

        // Then: as for any re-entered request, one observation settled once and no rest-core event; and the
        // completion carries the principal the second pass authenticated.
        assertAll(
                label,
                block("re-entry witness", () -> {
                    assertThat(reentering.invocations())
                            .as("invocations of the re-entering authentication handler")
                            .isEqualTo(2);
                    assertThat(parkedBeforeClose)
                            .as("hang tool invoked before the close")
                            .isTrue();
                    assertControlAnswered(control);
                    assertThat(recorded)
                            .as("barrier recorded the tools/call and the control")
                            .isTrue();
                }),
                block("MCP", () -> assertMcpSettledOnceWithOutcome(expectedOutcomes)),
                block("security", this::assertCompletionCarriesSecondPassPrincipal),
                block("REST", this::assertNoRestEvent),
                block("HTTP", () -> {
                    assertOnlyControlHttpEvent();
                    assertThat(eventsForPath(MCP_PATH))
                            .as("rest-core events for %s", MCP_PATH)
                            .isEmpty();
                }));
    }

    private static Stream<Arguments> secondPassAuthenticationRows() {
        // A close before the write settles as DISCONNECTED or RESET, a reset as RESET.
        return Stream.of(
                Arguments.of(
                        "disconnect",
                        RawToolCall.CloseMode.DISCONNECT,
                        EnumSet.of(McpTransportOutcome.DISCONNECTED, McpTransportOutcome.RESET)),
                Arguments.of("reset", RawToolCall.CloseMode.RESET, EnumSet.of(McpTransportOutcome.RESET)));
    }

    /**
     * A request rejected at admission never reaches {@code begin}, so the terminal writer is the only code
     * that reads its coordinator slot. The writer uses a coordinator only when it belongs to the request
     * (review findings CX-F-006 and SEC-L16), so a coordinator copied from an in-flight request neither
     * claims the rejection nor settles the other request's observation with the rejection's facts.
     */
    @Test
    @DisplayName("a coordinator copied into a request rejected at admission is not used")
    void admissionRejectionIgnoresCoordinatorCopiedFromAnotherRequest() throws Exception {
        // Given: the MCP mount is the only mount, with no authentication scheme, behind the application ROOT
        // middleware that puts the published value into the target request's coordinator slot.
        AtomicReference<Object> published = new AtomicReference<>();
        CoordinatorCopyingHandler copying = new CoordinatorCopyingHandler(published);
        startFixture(List.of(copying), mcpMount());

        // When: once the source request A is in flight in the hanging tool, the test reads and publishes
        // its coordinator; the target request B, the Accept text/html discover request that cheap admission
        // rejects with 406, is sent while A is in flight; then A is released and completes; then the
        // control request.
        Future<HttpResponse<Buffer>> sourceCall = callToolWithCopyRole(HANG_TOOL, COPY_SOURCE);
        boolean sourceInvoked = awaitSignal("the source's hang tool to be invoked", hangingTool.invoked());
        Object sourceCoordinator = copying.sourceSlot();
        published.set(sourceCoordinator);
        HttpResponse<Buffer> targetResponse = awaitResponse(
                "target discover request",
                discoverRequest(client, port)
                        .putHeader("Accept", "text/html")
                        .putHeader(COPY_ROLE_HEADER, COPY_TARGET)
                        .sendBuffer(discoverBody()));
        ObservationSnapshot sourceAtTargetResponse = observationSnapshot(0);
        hangingTool.release();
        HttpResponse<Buffer> sourceResponse = awaitResponse("source tools/call", sourceCall);
        completionListener.awaitCount(1, nextWaitNanos());
        HttpResponse<Buffer> control = sendControl();
        boolean recorded = awaitBarrier(3);

        // Then: B's rejection stayed unclaimed, with its one HTTP event, and only A's own write settled A's
        // observation, so A has no rest-core event.
        assertAll(
                "coordinator copied into an admission rejection",
                block("copy witness", () -> {
                    assertThat(sourceInvoked)
                            .as("source's hang tool invoked before the copy")
                            .isTrue();
                    assertThat(sourceCoordinator)
                            .as("coordinator in the source request's slot")
                            .isInstanceOf(McpCompletionCoordinator.class);
                    assertThat(copying.copiedValue())
                            .as("value copied into the target request's slot")
                            .isSameAs(sourceCoordinator);
                    assertThat(targetResponse).as("target response").isNotNull();
                    assertThat(targetResponse.statusCode()).as("target status").isEqualTo(406);
                    assertThat(sourceResponse).as("source tools/call response").isNotNull();
                    assertThat(sourceResponse.statusCode())
                            .as("source tools/call status")
                            .isEqualTo(200);
                    assertControlAnswered(control);
                    assertThat(recorded)
                            .as("barrier recorded the source, the target and the control")
                            .isTrue();
                }),
                block("MCP", () -> {
                    assertThat(observer.openCount())
                            .as("MCP observations opened (%s)", mcpEventCounts())
                            .isOne();
                    assertThat(sourceAtTargetResponse)
                            .as("source observation when the target's response arrived")
                            .isNotNull();
                    assertThat(sourceAtTargetResponse.terminals())
                            .as("terminals of the source observation when the target's response arrived")
                            .isEmpty();
                    assertThat(sourceAtTargetResponse.completions())
                            .as("completions of the source observation when the target's response arrived")
                            .isEmpty();
                    RecordedObservation source = observer.observation(0);
                    assertObservationSettledOnce("source", source, HANG_TOOL);
                    assertThat(source.terminals().get(0).event().httpStatus())
                            .as("HTTP status of the source observation's terminal")
                            .isEqualTo(200);
                    List<McpRequestCompletedEvent> completions = completionListener.events();
                    assertThat(completions).as("MCP completion listener events").hasSize(1);
                    assertThat(completions.get(0).terminal().toolName())
                            .as("tool name of the MCP completion's terminal")
                            .isEqualTo(HANG_TOOL);
                }),
                block("REST", this::assertNoRestEvent),
                block("HTTP", () -> {
                    List<String> events = httpListener.summaries();
                    assertThat(events).as("HTTP listener events").hasSize(2);
                    assertThat(events)
                            .as("HTTP listener events")
                            .containsExactlyInAnyOrder(
                                    summary(POST, MCP_PATH, 406), summary(POST, UNMATCHED_PATH, 404));
                }));
    }

    // --- Fixture ---

    /**
     * Builds the main router in the shared ROOT order, with no application ROOT middleware, mounts
     * {@code mounts} in the order given, and starts the server and the client.
     *
     * @param mounts the sub-router mounts, in mount order
     * @throws Exception if a router or the server does not start within the wait budget
     */
    private void startFixture(Mount... mounts) throws Exception {
        startFixture(List.of(), mounts);
    }

    /**
     * Builds the main router in {@code HttpVerticle}'s ROOT order: {@link RequestContextLifecycle}, the
     * completion barrier, the completion emitter, then {@code applicationRootMiddleware} where an
     * application's ROOT middleware runs, ahead of every mount; then mounts {@code mounts} in the order
     * given, each as {@code route(path).subRouter(router)}; then starts the server on the loopback
     * interface and the client.
     *
     * @param applicationRootMiddleware ROOT handlers that run where application ROOT middleware runs
     * @param mounts the sub-router mounts, in mount order
     * @throws Exception if a router or the server does not start within the wait budget
     */
    private void startFixture(List<Handler<RoutingContext>> applicationRootMiddleware, Mount... mounts)
            throws Exception {
        Router mainRouter = Router.router(vertx);
        RequestContextLifecycle lifecycle = new RequestContextLifecycle();
        mainRouter.route(lifecycle.path()).handler(lifecycle);
        mainRouter.route(ROOT_PATH).handler(barrier);
        RestRequestCompletionEmitter emitter = new RestRequestCompletionEmitter(
                Optional.empty(), NO_OP_CONTEXT_HOLDER, Set.of(restListener), Set.of(httpListener), Set.of());
        mainRouter.route(emitter.path()).handler(emitter);
        applicationRootMiddleware.forEach(
                middleware -> mainRouter.route(ROOT_PATH).handler(middleware));
        for (Mount mount : mounts) {
            mainRouter.route(mount.path()).subRouter(awaitSetup(mount.router().apply(vertx)));
        }
        server = awaitSetup(vertx.createHttpServer().requestHandler(mainRouter).listen(0, LOOPBACK));
        port = server.actualPort();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
    }

    /** The MCP mount with no authentication scheme. */
    private Mount mcpMount() {
        return mcpMount(null);
    }

    /**
     * The MCP mount with the shared security wiring: a recording security runtime and an identity
     * resolver that resolves every request as anonymous.
     *
     * @param scheme the authentication scheme, or {@code null} for none
     * @param routeAuthHandlers the route authentication handlers the scheme selects from
     * @return the MCP mount
     */
    private Mount mcpMount(@Nullable String scheme, RouteAuthHandler... routeAuthHandlers) {
        return mcpMount(scheme, new RecordingSecurityRuntime(), new AnonymousOnlyIdentityResolver(), routeAuthHandlers);
    }

    /**
     * The MCP mount, built as the admission suite builds it, plus the recording lifecycle observer, the
     * recording completion listener, and the {@code ok} and {@code hang} tools. One security runtime is
     * shared by the dispatcher, the policy enforcer and identity resolution.
     *
     * @param scheme the authentication scheme, or {@code null} for none
     * @param securityRuntime the security runtime every MCP collaborator shares
     * @param identityResolver the identity resolver identity resolution uses
     * @param routeAuthHandlers the route authentication handlers the scheme selects from
     * @return the MCP mount
     */
    private Mount mcpMount(
            @Nullable String scheme,
            SecurityRuntime securityRuntime,
            SecurityIdentityResolver identityResolver,
            RouteAuthHandler... routeAuthHandlers) {
        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .serverName(SERVER_NAME)
                .serverVersion(SERVER_VERSION)
                .authenticationScheme(scheme)
                .build();
        McpToolRegistry registry = McpToolRegistry.build(Set.of(new OkTool(), hangingTool));
        McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                new SecurityEventEmitter(Set.of()),
                NO_OP_CONTEXT_HOLDER,
                securityRuntime,
                Optional.empty()));
        HttpConfig httpConfig = HttpConfig.builder()
                .idleTimeoutSeconds(60)
                .maxBodySize(MAX_BODY_BYTES)
                .build();
        McpRouterMount mount = new McpRouterMount(
                config,
                new McpServerConfigValidator(),
                new McpRequestDispatcher(
                        config,
                        securityRuntime,
                        Set.of(observer),
                        Set.of(completionListener),
                        Set.of(),
                        Set.of(),
                        httpConfig,
                        registry,
                        policyEnforcer,
                        NO_OP_CONTEXT_HOLDER,
                        new CorrelationContextFactory(Optional.empty())),
                Set.of(routeAuthHandlers),
                new IdentityResolutionMiddleware(
                        Set.of(identityResolver),
                        Optional.of(new DefaultSecurityClaimMapper()),
                        new SecurityEventEmitter(Set.of()),
                        securityRuntime,
                        NO_OP_CONTEXT_HOLDER),
                httpConfig,
                registry);
        return new Mount(config.mountPath(), mount::createRouter);
    }

    // --- Requests ---

    /** A {@code tools/call} for {@code tool} to the MCP mount, with no {@code Authorization}. */
    private Future<HttpResponse<Buffer>> callTool(String tool) {
        return callRequest(MCP_PATH, tool).sendBuffer(callBody(tool));
    }

    /** A {@code tools/call} for {@code tool} to the MCP mount, carrying {@code authorization}. */
    private Future<HttpResponse<Buffer>> callTool(String tool, String authorization) {
        return callRequest(MCP_PATH, tool)
                .putHeader("Authorization", authorization)
                .sendBuffer(callBody(tool));
    }

    /** A {@code tools/call} for {@code tool} to the MCP mount, marked with {@code copyRole} for the copying handler. */
    private Future<HttpResponse<Buffer>> callToolWithCopyRole(String tool, String copyRole) {
        return callRequest(MCP_PATH, tool).putHeader(COPY_ROLE_HEADER, copyRole).sendBuffer(callBody(tool));
    }

    /** Sends the control request, the {@code ok} call's headers and body to a path no route matches. */
    @Nullable
    private HttpResponse<Buffer> sendControl() {
        return awaitResponse(
                "control request", callRequest(UNMATCHED_PATH, OK_TOOL).sendBuffer(callBody(OK_TOOL)));
    }

    private HttpRequest<Buffer> callRequest(String path, String tool) {
        return client.post(port, LOOPBACK, path)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", tool);
    }

    private static Buffer callBody(String tool) {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put("params", new JsonObject().put("_meta", requestMeta()).put("name", tool))
                .toBuffer();
    }

    private static HttpRequest<Buffer> discoverRequest(WebClient client, int port) {
        return discoverRequestWithoutContentType(client, port).putHeader("content-type", "application/json");
    }

    private static HttpRequest<Buffer> discoverRequestWithoutContentType(WebClient client, int port) {
        return client.post(port, LOOPBACK, MCP_PATH)
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "server/discover")
                .putHeader("Mcp-Name", "server/discover");
    }

    private static Buffer discoverBody() {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "server/discover")
                .put("params", new JsonObject().put("_meta", requestMeta()))
                .toBuffer();
    }

    private static JsonObject requestMeta() {
        return new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
    }

    /**
     * Sends one {@code tools/call} frame for the hanging tool on a raw socket, waits until the request
     * has parked, then closes the socket in {@code closeMode}.
     *
     * @param parked completes once the request has parked where the row expects it
     * @param closeMode how the socket closes
     * @return whether the request parked within the bound before the close
     * @throws IOException if the socket cannot connect or write the frame
     */
    private boolean sendToolCallThenClose(CompletableFuture<Void> parked, RawToolCall.CloseMode closeMode)
            throws IOException {
        return sendToolCallThenClose(HANG_TOOL, parked, closeMode, Map.of());
    }

    /**
     * Sends one {@code tools/call} frame for {@code tool} on a raw socket, with {@code extraHeaders} in its
     * head, waits until the request has parked, then closes the socket in {@code closeMode}.
     *
     * @param tool the tool the frame calls
     * @param parked completes once the request has parked where the row expects it
     * @param closeMode how the socket closes
     * @param extraHeaders headers added to the frame's head
     * @return whether the request parked within the bound before the close
     * @throws IOException if the socket cannot connect or write the frame
     */
    private boolean sendToolCallThenClose(
            String tool,
            CompletableFuture<Void> parked,
            RawToolCall.CloseMode closeMode,
            Map<String, String> extraHeaders)
            throws IOException {
        try (RawToolCall call = RawToolCall.send(port, tool, extraHeaders, nextWaitNanos())) {
            boolean parkedBeforeClose = awaitSignal("the request to park", parked);
            call.close(closeMode);
            return parkedBeforeClose;
        }
    }

    // --- Bounded, non-throwing waits ---

    /** The next wait's bound: at most {@link #WAIT_BOUND}, and never past the test's wait budget. */
    private long nextWaitNanos() {
        long remaining = waitDeadlineNanos - System.nanoTime();
        return Math.max(0, Math.min(WAIT_BOUND.toNanos(), remaining));
    }

    /**
     * Waits for {@code future} within the next wait's bound.
     *
     * @return its result, or {@code null} when it failed or did not complete in time
     */
    @Nullable
    private <T> T awaitResponse(String what, Future<T> future) {
        CompletableFuture<T> completion = future.toCompletionStage().toCompletableFuture();
        return awaitSignal(what, completion) ? completion.getNow(null) : null;
    }

    /**
     * Waits for {@code signal} within the next wait's bound.
     *
     * @return whether it completed normally in time
     */
    private boolean awaitSignal(String what, CompletableFuture<?> signal) {
        try {
            signal.get(nextWaitNanos(), TimeUnit.NANOSECONDS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            reportUnfinishedWait(what, interrupted);
        } catch (ExecutionException | TimeoutException unfinished) {
            reportUnfinishedWait(what, unfinished);
        }
        return false;
    }

    /**
     * Waits until the barrier has recorded {@code requests} requests, then drains the Vert.x context of
     * every request it saw once, so a settlement redispatched to the request's context has run.
     *
     * @return whether the barrier recorded them and every drain ran within its bound
     */
    private boolean awaitBarrier(int requests) {
        boolean recorded = barrier.awaitRecorded(requests, nextWaitNanos());
        boolean drained = barrier.drain(this::nextWaitNanos);
        if (!recorded || !drained) {
            System.err.println("barrier wait for " + requests + " request(s) fell short: recorded " + barrier.recorded()
                    + ", drained " + drained);
        }
        return recorded && drained;
    }

    /** Waits for a setup step; unlike the proof's waits it throws, because there is no fixture without it. */
    private <T> T awaitSetup(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(nextWaitNanos(), TimeUnit.NANOSECONDS);
    }

    private static void reportUnfinishedWait(String what, Throwable cause) {
        System.err.println("bounded wait for " + what + " ended without a result: " + cause);
    }

    // --- Assertion blocks ---

    /**
     * Names one block of a test's single {@code assertAll}: a failure inside it names the block, and the
     * other blocks still run.
     */
    private static Executable block(String name, Executable assertions) {
        return () -> {
            try {
                assertions.execute();
            } catch (AssertionError failure) {
                throw new AssertionError(name + " block: " + failure.getMessage(), failure);
            }
        };
    }

    /**
     * Asserts that an unclaimed request that never reached {@code begin} produced exactly one HTTP event
     * with {@code method}, {@code path} and {@code status}, no REST event, and no MCP observation.
     */
    private void assertExactlyOneHttpEventAndNoMcpObservation(
            String heading, Executable witness, String method, String path, int status) {
        assertAll(
                heading,
                block("witness", witness),
                block("MCP", () -> {
                    assertThat(observer.openCount())
                            .as("MCP observations opened")
                            .isZero();
                    assertThat(observer.terminalCount())
                            .as("MCP terminals delivered")
                            .isZero();
                    assertThat(completionListener.events())
                            .as("MCP completion listener events")
                            .isEmpty();
                }),
                block("REST", this::assertNoRestEvent),
                block("HTTP", () -> {
                    List<String> events = httpListener.summaries();
                    assertThat(events).as("HTTP listener events").hasSize(1);
                    assertThat(events).as("HTTP listener events").containsExactly(summary(method, path, status));
                }));
    }

    /** One observation opened, which received one terminal and one completion; one listener event. */
    private void assertMcpSettledOnce() {
        assertThat(observer.openCount()).as("MCP observations opened").isOne();
        RecordedObservation observation = observer.observation(0);
        assertThat(observation.terminals())
                .as("terminals of the MCP observation")
                .hasSize(1);
        assertThat(observation.completions())
                .as("completions of the MCP observation")
                .hasSize(1);
        assertThat(completionListener.events())
                .as("MCP completion listener events")
                .hasSize(1);
    }

    /** One observation opened at {@code begin}, which received no terminal and no completion. */
    private void assertMcpOpenedButNeverSettled() {
        assertThat(observer.openCount()).as("MCP observations opened").isOne();
        RecordedObservation observation = observer.observation(0);
        assertThat(observation.terminals())
                .as("terminals of the MCP observation")
                .isEmpty();
        assertThat(observation.completions())
                .as("completions of the MCP observation")
                .isEmpty();
        assertThat(completionListener.events())
                .as("MCP completion listener events")
                .isEmpty();
    }

    private void assertNoRestEvent() {
        assertThat(restListener.summaries()).as("REST listener events").isEmpty();
    }

    /** The HTTP listener holds exactly one event, the control request's. */
    private void assertOnlyControlHttpEvent() {
        List<String> events = httpListener.summaries();
        assertThat(events).as("HTTP listener events").hasSize(1);
        assertThat(events).as("HTTP listener events").containsExactly(summary(POST, UNMATCHED_PATH, 404));
    }

    private static void assertAnsweredSuccessfully(@Nullable HttpResponse<Buffer> response) {
        assertThat(response).as("tools/call response").isNotNull();
        assertThat(response.statusCode()).as("tools/call status").isEqualTo(200);
        JsonObject result = sseResult(response.bodyAsString());
        assertThat(result).as("tools/call SSE result").isNotNull();
        assertThat(result.getBoolean("isError"))
                .as("tools/call SSE result isError")
                .isFalse();
    }

    private static void assertControlAnswered(@Nullable HttpResponse<Buffer> control) {
        assertThat(control).as("control response").isNotNull();
        assertThat(control.statusCode()).as("control status").isEqualTo(404);
    }

    /**
     * {@link #assertMcpSettledOnce}, plus the completion's transport outcome. An open-count failure names
     * every observation's events, so a second observation shows what it received.
     */
    private void assertMcpSettledOnceWithOutcome(Set<McpTransportOutcome> expectedOutcomes) {
        assertThat(observer.openCount())
                .as("MCP observations opened (%s)", mcpEventCounts())
                .isOne();
        assertMcpSettledOnce();
        assertThat(completionListener.events().get(0).transportOutcome())
                .as("transport outcome of the MCP completion")
                .isIn(expectedOutcomes);
    }

    /**
     * The MCP completion's terminal carries alice as a user authenticated with JWT evidence, and the
     * observer's terminal carries the same snapshot.
     */
    private void assertCompletionCarriesSecondPassPrincipal() {
        List<McpRequestCompletedEvent> completions = completionListener.events();
        assertThat(completions).as("MCP completion listener events").isNotEmpty();
        SecurityContextSnapshot security = completions.get(0).terminal().security();
        assertThat(security)
                .as("security snapshot of the MCP completion's terminal")
                .isNotNull();
        PrincipalRef actor = security.identity().actor();
        assertThat(actor.type()).as("actor type of the MCP completion").isEqualTo(PrincipalType.USER);
        assertThat(actor.id()).as("actor id of the MCP completion").isEqualTo(ALICE);
        assertThat(security.authentication().primaryMethod().normalizedKind())
                .as("primary authentication method of the MCP completion")
                .isEqualTo(AuthMethodKind.JWT);
        assertThat(observer.openCount()).as("MCP observations opened").isPositive();
        List<McpRequestTerminalObservation> terminals = observer.observation(0).terminals();
        assertThat(terminals).as("terminals of the MCP observation").isNotEmpty();
        assertThat(terminals.get(0).event().security())
                .as("security snapshot of the MCP observation's terminal")
                .isEqualTo(security);
    }

    /** {@code observation} received one terminal, for {@code toolName}, and one completion. */
    private static void assertObservationSettledOnce(String which, RecordedObservation observation, String toolName) {
        assertThat(observation.terminals())
                .as("terminals of the %s observation", which)
                .hasSize(1);
        assertThat(observation.terminals().get(0).event().toolName())
                .as("tool name of the %s observation's terminal", which)
                .isEqualTo(toolName);
        assertThat(observation.completions())
                .as("completions of the %s observation", which)
                .hasSize(1);
    }

    /** The {@code which} request's {@code tools/call} response is 200, and its SSE result is not an error. */
    private static void assertToolCallAnswered(String which, @Nullable HttpResponse<Buffer> response) {
        assertThat(response).as("%s tools/call response", which).isNotNull();
        assertThat(response.statusCode()).as("%s tools/call status", which).isEqualTo(200);
        JsonObject result = sseResult(response.bodyAsString());
        assertThat(result).as("%s tools/call SSE result", which).isNotNull();
        assertThat(result.getBoolean("isError"))
                .as("%s tools/call SSE result isError", which)
                .isFalse();
    }

    /** Each opened observation's terminal and completion counts, in open order, and the listener's count. */
    private String mcpEventCounts() {
        String perObservation = IntStream.range(0, observer.openCount())
                .mapToObj(observer::observation)
                .map(observation -> observation.terminals().size() + " terminal(s), "
                        + observation.completions().size() + " completion(s)")
                .collect(Collectors.joining("; ", "[", "]"));
        return "per observation " + perObservation + "; completion listener events "
                + completionListener.events().size();
    }

    /** Each MCP completion's actor as {@code type:id}, or {@code null} when its terminal has no security snapshot. */
    private List<String> completionActors() {
        return completionListener.events().stream()
                .map(event -> event.terminal().security())
                .map(security -> security == null
                        ? "null"
                        : security.identity().actor().type() + ":"
                                + security.identity().actor().id())
                .toList();
    }

    /**
     * The terminals and completions the {@code index}-th opened observation holds now.
     *
     * @return them, or {@code null} when fewer observations were opened
     */
    @Nullable
    private ObservationSnapshot observationSnapshot(int index) {
        if (observer.openCount() <= index) {
            return null;
        }
        RecordedObservation observation = observer.observation(index);
        return new ObservationSnapshot(observation.terminals(), observation.completions());
    }

    /**
     * Every rest-core completion event, of either type, recorded for {@code path}, tagged with its type.
     */
    private List<String> eventsForPath(String path) {
        Stream<String> rest = restListener.events().stream()
                .filter(event -> path.equals(event.path()))
                .map(event -> "REST " + summary(event));
        Stream<String> http = httpListener.events().stream()
                .filter(event -> path.equals(event.path()))
                .map(event -> "HTTP " + summary(event));
        return Stream.concat(rest, http).toList();
    }

    private static String summary(String method, String path, int status) {
        return method + " " + path + " " + status;
    }

    private static String summary(HttpRequestCompletedEvent event) {
        return summary(event.method(), event.path(), event.statusCode());
    }

    private static String summary(RestRequestCompletedEvent event) {
        return summary(event.method(), event.path(), event.statusCode()) + " "
                + event.operation().operationId();
    }

    /**
     * The {@code result} of a single-frame SSE response.
     *
     * @return the result, or {@code null} when the body is not one SSE frame carrying a result
     */
    @Nullable
    private static JsonObject sseResult(@Nullable String rawBody) {
        if (rawBody == null || !rawBody.startsWith(SSE_PREFIX)) {
            return null;
        }
        try {
            return new JsonObject(rawBody.substring(SSE_PREFIX.length()).stripTrailing()).getJsonObject("result");
        } catch (RuntimeException unparseable) {
            return null;
        }
    }

    // --- Rows ---

    /** Sends one admission row's request. */
    @FunctionalInterface
    private interface RequestSender {
        Future<HttpResponse<Buffer>> send(WebClient client, int port);
    }

    private static Arguments rejection(String label, String method, RequestSender request, int status) {
        return Arguments.of(label, method, request, status);
    }

    /** Which rest-core event a rerouted request's target decides. */
    private enum EventKind {
        REST,
        HTTP
    }

    /** Where a disconnect row's request parks when the socket closes. */
    private enum DisconnectFixture {
        /** In the hanging tool, with the MCP mount as the only mount. */
        MCP_ONLY,
        /** In the hanging reroute target, after the MCP mount rerouted it following {@code begin}. */
        REROUTED_TO_HANGING_TARGET
    }

    /** One sub-router the fixture mounts at {@code path}, in the order given. */
    private record Mount(String path, Function<Vertx, Future<Router>> router) {}

    /** The terminals and completions one observation held at one moment. */
    private record ObservationSnapshot(
            List<McpRequestTerminalObservation> terminals, List<McpRequestCompletedEvent> completions) {}

    // --- Fixtures ---

    /**
     * Test-only ROOT handler between {@link RequestContextLifecycle} and the completion emitter. On a
     * request's first routing pass only, marked by a test-local data key, it records the request's
     * Vert.x context and registers one end handler that records the request as completed. A reroute
     * re-runs ROOT handlers on the same request and finds the key, so it registers nothing more.
     *
     * <p>Its end handler is registered before the emitter's and before MCP's settlement hook, so it runs
     * after both. Once it has recorded a request, the emitter has dispatched every event it will
     * produce for it. A settlement MCP redispatches to the request's context can still be queued there,
     * so {@link #drain} runs one marker task on each recorded context before the test counts.
     */
    private static final class CompletionBarrier implements Handler<RoutingContext> {
        private static final String REGISTERED_KEY = "test.barrier.registered";

        private final EventLog<String> completed = new EventLog<>();
        private final List<Context> requestContexts = new CopyOnWriteArrayList<>();

        @Override
        public void handle(RoutingContext context) {
            if (context.get(REGISTERED_KEY) == null) {
                context.put(REGISTERED_KEY, Boolean.TRUE);
                Context requestContext = Vertx.currentContext();
                if (requestContext != null) {
                    requestContexts.add(requestContext);
                }
                String request = context.request().method().name() + " "
                        + context.request().path();
                context.addEndHandler(ignored -> completed.add(request));
            }
            context.next();
        }

        boolean awaitRecorded(int requests, long timeoutNanos) {
            return completed.awaitCount(requests, timeoutNanos);
        }

        /** The requests recorded so far, as their first pass's method and path. */
        List<String> recorded() {
            return completed.snapshot();
        }

        /**
         * Runs one marker task on each recorded request context, and waits for each within {@code
         * timeoutNanos}.
         *
         * @return whether every marker ran in time
         */
        boolean drain(LongSupplier timeoutNanos) {
            for (Context requestContext : requestContexts) {
                CompletableFuture<Void> marker = new CompletableFuture<>();
                requestContext.runOnContext(ignored -> marker.complete(null));
                try {
                    marker.get(timeoutNanos.getAsLong(), TimeUnit.NANOSECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (ExecutionException | TimeoutException unfinished) {
                    return false;
                }
            }
            return true;
        }
    }

    /** A thread-safe, append-only list whose size a test can await without throwing. */
    private static final class EventLog<T> {
        private final List<T> events = new CopyOnWriteArrayList<>();

        void add(T event) {
            events.add(event);
            synchronized (this) {
                notifyAll();
            }
        }

        List<T> snapshot() {
            return List.copyOf(events);
        }

        /**
         * Waits until at least {@code count} events were added.
         *
         * @return whether they were, within {@code timeoutNanos}
         */
        boolean awaitCount(int count, long timeoutNanos) {
            long deadline = System.nanoTime() + timeoutNanos;
            synchronized (this) {
                while (events.size() < count) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        return false;
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(this, remaining);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return true;
            }
        }
    }

    /** Records every {@link RestRequestCompletedEvent} the emitter dispatches. */
    private static final class RecordingRestListener implements RestRequestCompletedListener {
        private final List<RestRequestCompletedEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onCompleted(RestRequestCompletedEvent event) {
            events.add(event);
        }

        List<RestRequestCompletedEvent> events() {
            return List.copyOf(events);
        }

        List<String> summaries() {
            return events().stream().map(McpCompletionClaimIT::summary).toList();
        }
    }

    /** Records every {@link HttpRequestCompletedEvent} the emitter dispatches. */
    private static final class RecordingHttpListener implements HttpRequestCompletedListener {
        private final List<HttpRequestCompletedEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onCompleted(HttpRequestCompletedEvent event) {
            events.add(event);
        }

        List<HttpRequestCompletedEvent> events() {
            return List.copyOf(events);
        }

        List<String> summaries() {
            return events().stream().map(McpCompletionClaimIT::summary).toList();
        }
    }

    /** Counts {@code open} calls and keeps each observation's events, in the order observations opened. */
    private static final class RecordingObserver implements McpRequestLifecycleObserver {
        private final List<RecordedObservation> opened = new CopyOnWriteArrayList<>();

        @Override
        public McpRequestObservation open(Instant startedAt) {
            RecordedObservation observation = new RecordedObservation();
            opened.add(observation);
            return observation;
        }

        int openCount() {
            return opened.size();
        }

        RecordedObservation observation(int index) {
            return opened.get(index);
        }

        int terminalCount() {
            return opened.stream()
                    .mapToInt(observation -> observation.terminals().size())
                    .sum();
        }
    }

    /** One lifecycle observation's terminals and completions. */
    private static final class RecordedObservation implements McpRequestObservation {
        private final List<McpRequestTerminalObservation> terminals = new CopyOnWriteArrayList<>();
        private final List<McpRequestCompletedEvent> completions = new CopyOnWriteArrayList<>();

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            terminals.add(observation);
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            completions.add(event);
        }

        List<McpRequestTerminalObservation> terminals() {
            return List.copyOf(terminals);
        }

        List<McpRequestCompletedEvent> completions() {
            return List.copyOf(completions);
        }
    }

    /** Records every MCP completion; the test awaits it without throwing. */
    private static final class RecordingCompletionListener implements McpRequestCompletedListener {
        private final EventLog<McpRequestCompletedEvent> events = new EventLog<>();

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            events.add(event);
        }

        boolean awaitCount(int count, long timeoutNanos) {
            return events.awaitCount(count, timeoutNanos);
        }

        List<McpRequestCompletedEvent> events() {
            return events.snapshot();
        }
    }

    /**
     * A sub-router at {@code /*}, mounted ahead of the MCP mount, that matches no route for an MCP
     * request and registers no failure handler. It stands in for a JAX-RS mount ordered ahead of MCP:
     * only the identity handler on an operation route claims a request for REST, so a mount none of
     * whose operation routes matches claims nothing, and the request falls through to the next mount.
     * Its mount-level pass-through handler counts entries; that count is its only state.
     */
    private static final class CatchAllFallThroughRouter {
        private final AtomicInteger entries = new AtomicInteger();

        Mount mount() {
            return new Mount(CATCH_ALL_MOUNT, vertx -> {
                Router router = Router.router(vertx);
                router.route().handler(context -> {
                    entries.incrementAndGet();
                    context.next();
                });
                router.get("/other/only")
                        .handler(
                                context -> context.response().setStatusCode(200).end());
                return Future.succeededFuture(router);
            });
        }

        int entries() {
            return entries.get();
        }
    }

    /**
     * A sub-router at {@code /*}, mounted ahead of the MCP mount, with no route that matches the
     * request and a router-level failure handler. A failure raised inside the MCP sub-router restarts
     * on the main router and reaches the first path-matching mount's failure handler, this one, not
     * MCP's. So MCP does not always settle a request after {@code begin}: this handler ends the
     * response, and MCP neither writes nor settles. Its entry and failure counters are its only state.
     */
    private static final class CatchAllFailureHandlingRouter {
        private final AtomicInteger entries = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();

        Mount mount() {
            return new Mount(CATCH_ALL_MOUNT, vertx -> {
                Router router = Router.router(vertx);
                router.route().handler(context -> {
                    entries.incrementAndGet();
                    context.next();
                });
                router.route().failureHandler(context -> {
                    failures.incrementAndGet();
                    context.response().setStatusCode(context.statusCode()).end(CATCH_ALL_FAILURE_BODY);
                });
                return Future.succeededFuture(router);
            });
        }

        int entries() {
            return entries.get();
        }

        int failures() {
            return failures.get();
        }
    }

    /**
     * The reroute target, mounted at {@code /rerouted/*}. Its {@code /op} route stands in for a JAX-RS
     * operation route: its first handler is the identity handler the JAX-RS registrar installs first on
     * every operation route, recording {@link #TARGET_OPERATION}. Its {@code /hang} route is not an
     * operation route, so it has no identity handler; it counts its entries and never ends the
     * response. Any other path under the mount matches no route and falls through to the main router's
     * 404.
     */
    private static final class RerouteTargetRouter {
        private final AtomicInteger hangingEntries = new AtomicInteger();
        private final CompletableFuture<Void> hangingEntered = new CompletableFuture<>();

        Mount mount() {
            return new Mount(REROUTE_MOUNT, vertx -> {
                Router router = Router.router(vertx);
                router.post("/op")
                        .handler(RequestCompletionRecorder.operationRouteHandler(TARGET_OPERATION))
                        .handler(
                                context -> context.response().setStatusCode(200).end(REROUTED_BODY));
                router.route("/hang").handler(context -> {
                    hangingEntries.incrementAndGet();
                    hangingEntered.complete(null);
                });
                return Future.succeededFuture(router);
            });
        }

        int hangingEntries() {
            return hangingEntries.get();
        }

        CompletableFuture<Void> hangingEntered() {
            return hangingEntered;
        }
    }

    /**
     * The one {@link RouteAuthHandler} of the {@code reroute} scheme. Its optional handler reroutes the
     * request to {@code target} and does not call {@code next()}.
     *
     * <p>It is how a test reroutes a request after {@code begin} without changing MCP. A route added to
     * the MCP router, by a mount customizer or otherwise, runs before {@code begin} or is never reached,
     * because {@code dispatch} ends the response without calling {@code next()}; and a request
     * interceptor receives no routing context. The selected optional authentication handler is the one
     * application-supplied handler that runs after {@code begin} and receives the routing context.
     */
    private static final class ReroutingRouteAuthHandler implements RouteAuthHandler {
        private final String target;

        ReroutingRouteAuthHandler(String target) {
            this.target = target;
        }

        @Override
        public String schemeName() {
            return REROUTE_SCHEME;
        }

        @Override
        public Handler<RoutingContext> createHandler() {
            return context -> context.fail(401);
        }

        @Override
        public Optional<Handler<RoutingContext>> createOptionalHandler() {
            return Optional.of(context -> {
                context.reroute(target);
            });
        }
    }

    /**
     * The one {@link RouteAuthHandler} of the {@code bearer} scheme, in the optional bearer shape the
     * correlation-lifecycle suite uses: no credential continues anonymously, {@code Bearer alice}
     * authenticates {@code alice} with JWT evidence, and any other credential fails the request with
     * 401. The optional handler runs as MCP's authentication step, after {@code begin}.
     */
    private static final class FailingBearerRouteAuthHandler implements RouteAuthHandler {

        @Override
        public String schemeName() {
            return BEARER_SCHEME;
        }

        @Override
        public Handler<RoutingContext> createHandler() {
            return context -> context.fail(401);
        }

        @Override
        public Optional<Handler<RoutingContext>> createOptionalHandler() {
            return Optional.of(context -> {
                String credential = context.request().getHeader("Authorization");
                if (credential == null) {
                    context.next();
                    return;
                }
                if (!BEARER_ALICE.equals(credential)) {
                    context.fail(401);
                    return;
                }
                RestAuthenticationEvidence.append(
                        context,
                        new AuthenticationEvidence(
                                DefaultAuthMethod.jwt(),
                                Optional.of("alice"),
                                Instant.now(),
                                Optional.empty(),
                                new CustomVerificationSource("test", Map.of()),
                                Map.of("sub", "alice")));
                ((UserContextInternal) context.userContext())
                        .setUser(User.create(
                                new JsonObject().put("sub", "alice").put("roles", new JsonArray().add("ops"))));
                context.next();
            });
        }
    }

    /**
     * The one {@link RouteAuthHandler} of the {@code reenter} scheme. Its optional handler runs as MCP's
     * authentication step, after {@code begin} in the same route. On a request's first pass, marked by a
     * test-local data key, it sets the key and reroutes the request back to the MCP mount path, without
     * reading the credential and without calling {@code next()}. On the second pass it runs the optional
     * bearer shape of {@link FailingBearerRouteAuthHandler}: no credential continues anonymously, and
     * {@code Bearer alice} authenticates alice with JWT evidence. Its invocation counter and first-pass
     * key are its only state.
     *
     * <p>A reroute back to the mount path re-enters the MCP router at its first route: the upload cleanup,
     * cheap admission and the body handler run again, then {@code begin}, then this handler. A reroute
     * keeps the request's {@code data()} and its end handlers, and every routing context of one request,
     * before and after a reroute, returns the same {@code HttpServerRequest}. So the re-entered {@code
     * begin} finds the coordinator the first pass built, and that coordinator belongs to this request.
     * The first pass leaves no user and no evidence, so the second pass's admission, which fails closed
     * on either when a scheme is configured, sees a clean state.
     */
    private static final class ReenteringRouteAuthHandler implements RouteAuthHandler {
        private static final String FIRST_PASS_KEY = "test.reenter.firstPass";

        private final AtomicInteger invocations = new AtomicInteger();
        private final Handler<RoutingContext> bearer =
                new FailingBearerRouteAuthHandler().createOptionalHandler().orElseThrow();

        @Override
        public String schemeName() {
            return REENTER_SCHEME;
        }

        @Override
        public Handler<RoutingContext> createHandler() {
            return context -> context.fail(401);
        }

        @Override
        public Optional<Handler<RoutingContext>> createOptionalHandler() {
            return Optional.of(context -> {
                invocations.incrementAndGet();
                if (context.get(FIRST_PASS_KEY) == null) {
                    context.put(FIRST_PASS_KEY, Boolean.TRUE);
                    context.reroute(MCP_PATH);
                    return;
                }
                bearer.handle(context);
            });
        }

        int invocations() {
            return invocations.get();
        }
    }

    /**
     * Application ROOT middleware that copies one request's MCP completion coordinator into another
     * request's {@code data()}, ahead of the MCP mount and so before {@code begin}. The coordinator key is
     * one predictable string for every dispatcher, so any application code can do this (review findings
     * CX-F-006 and SEC-L16).
     *
     * <p>For a request marked {@code X-Copy-Role: source}, it records the request's main-router routing
     * context. A sub-router context reads and writes the same {@code data()}, so the test reads the
     * source's coordinator from it, only after a signal that orders {@code begin} before the read. For a
     * request marked {@code X-Copy-Role: target}, it puts the value the test published under the
     * coordinator key, and records that value and the request's routing context. Every request then
     * continues. Its recorded contexts and its copied value are its only state; the test owns the
     * published reference.
     */
    private static final class CoordinatorCopyingHandler implements Handler<RoutingContext> {
        private final AtomicReference<Object> published;
        private final AtomicReference<RoutingContext> source = new AtomicReference<>();
        private final AtomicReference<RoutingContext> target = new AtomicReference<>();
        private final AtomicReference<Object> copied = new AtomicReference<>();

        CoordinatorCopyingHandler(AtomicReference<Object> published) {
            this.published = published;
        }

        @Override
        public void handle(RoutingContext context) {
            String role = context.request().getHeader(COPY_ROLE_HEADER);
            if (COPY_SOURCE.equals(role)) {
                source.set(context);
            } else if (COPY_TARGET.equals(role)) {
                Object value = published.get();
                context.put(McpRequestDispatcher.COMPLETION_COORDINATOR_KEY, value);
                copied.set(value);
                target.set(context);
            }
            context.next();
        }

        /** The source request's coordinator slot; read it only after a signal that follows its {@code begin}. */
        @Nullable
        Object sourceSlot() {
            return slotOf(source.get());
        }

        /** The target request's coordinator slot; read it only after a signal that follows its completion. */
        @Nullable
        Object targetSlot() {
            return slotOf(target.get());
        }

        /** The value put into the target request's slot, or {@code null} before the target arrived. */
        @Nullable
        Object copiedValue() {
            return copied.get();
        }

        @Nullable
        private static Object slotOf(@Nullable RoutingContext context) {
            return context == null ? null : context.get(McpRequestDispatcher.COMPLETION_COORDINATOR_KEY);
        }
    }

    /** The zero-argument {@code ok} tool, which returns {@code ok}. */
    private static final class OkTool implements McpToolInvoker {
        private final McpToolDescriptor descriptor = toolDescriptor(OK_TOOL);

        @Override
        public McpToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return Map.of();
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    return Future.succeededFuture(McpToolResult.text("ok"));
                }
            };
        }
    }

    /**
     * The zero-argument {@code hang} tool: its invocation completes {@link #invoked()} and returns a
     * future that only {@link #release()} completes, so its request stays in flight until the
     * connection is lost or the test releases it. One per fixture.
     */
    private static final class HangingTool implements McpToolInvoker {
        private final McpToolDescriptor descriptor = toolDescriptor(HANG_TOOL);
        private final Promise<McpToolResult<?>> result = Promise.promise();
        private final CompletableFuture<Void> invoked = new CompletableFuture<>();

        @Override
        public McpToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return Map.of();
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    invoked.complete(null);
                    return result.future();
                }
            };
        }

        CompletableFuture<Void> invoked() {
            return invoked;
        }

        /** Completes the pending invocation, if it is still pending. */
        void release() {
            result.tryComplete(McpToolResult.text("released"));
        }
    }

    private static McpToolDescriptor toolDescriptor(String name) {
        return new McpToolDescriptor(
                name,
                null,
                "Completion-claim fixture tool.",
                new McpToolAnnotations(true, false, true, false),
                CLOSED_OBJECT_SCHEMA,
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
    }

    /**
     * One {@code tools/call} frame written on a raw socket that the test then closes before MCP writes
     * a response, either in an orderly way or with a reset ({@code SO_LINGER} 0). It owns the socket,
     * the frame and the two close modes.
     */
    private static final class RawToolCall implements AutoCloseable {

        /** How the socket closes. */
        enum CloseMode {
            /** An orderly close. */
            DISCONNECT,
            /** A reset: {@code SO_LINGER} 0, then close, so the server sees a RST. */
            RESET
        }

        private final Socket socket;

        private RawToolCall(Socket socket) {
            this.socket = socket;
        }

        /**
         * Connects to the loopback server within {@code connectTimeoutNanos} and writes one {@code
         * tools/call} frame for {@code toolName} to the MCP mount, with {@code extraHeaders} added to the
         * frame's head.
         *
         * @throws IOException if the socket cannot connect or write the frame
         */
        static RawToolCall send(int port, String toolName, Map<String, String> extraHeaders, long connectTimeoutNanos)
                throws IOException {
            Socket socket = new Socket();
            try {
                // A zero connect timeout means no bound at all, so a spent wait budget still bounds it.
                int connectTimeoutMillis = (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(connectTimeoutNanos));
                socket.connect(new InetSocketAddress(LOOPBACK, port), connectTimeoutMillis);
                byte[] body = callBody(toolName).getBytes();
                String extraHeaderLines = extraHeaders.entrySet().stream()
                        .map(header -> header.getKey() + ": " + header.getValue() + "\r\n")
                        .collect(Collectors.joining());
                String head = "POST " + MCP_PATH + " HTTP/1.1\r\n"
                        + "Host: " + LOOPBACK + "\r\n"
                        + "Content-Type: application/json\r\n"
                        + "MCP-Protocol-Version: " + PROTOCOL_VERSION + "\r\n"
                        + "Mcp-Method: tools/call\r\n"
                        + "Mcp-Name: " + toolName + "\r\n"
                        + extraHeaderLines
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(body);
                socket.getOutputStream().flush();
            } catch (IOException failed) {
                socket.close();
                throw failed;
            }
            return new RawToolCall(socket);
        }

        /** Closes the socket in {@code mode}. */
        void close(CloseMode mode) throws IOException {
            if (mode == CloseMode.RESET) {
                socket.setSoLinger(true, 0);
            }
            socket.close();
        }

        /** Closes the socket in an orderly way; a no-op once it is closed. */
        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /**
     * The test stand-in for the reroute target's JAX-RS operation descriptor ({@code POST
     * /rerouted/op}, {@code reroutedOp}): identity only, empty members, no security policy. Equal only
     * to itself.
     */
    private static final class TargetOperationDescriptor implements RestOperationDescriptor {

        @Override
        public String operationId() {
            return "reroutedOp";
        }

        @Override
        public String httpMethod() {
            return POST;
        }

        @Override
        public String routeTemplate() {
            return REROUTED_OPERATION_PATH;
        }

        @Override
        public List<String> consumes() {
            return List.of();
        }

        @Override
        public List<String> produces() {
            return List.of();
        }

        @Override
        public SecurityPolicy securityPolicy() {
            return new SecurityPolicy.None();
        }

        @Override
        public List<SecurityRequirementSet> securityRequirementSets() {
            return List.of();
        }

        @Override
        public List<Annotation> methodAnnotations() {
            return List.of();
        }

        @Override
        public List<Annotation> classAnnotations() {
            return List.of();
        }

        @Override
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
            return Optional.empty();
        }

        @Override
        public String toString() {
            return "POST " + REROUTED_OPERATION_PATH + " (reroutedOp)";
        }
    }

    /** Resolves every request as the canonical anonymous identity. */
    private record AnonymousOnlyIdentityResolver() implements SecurityIdentityResolver {
        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
        }
    }

    /** Resolves the canonical anonymous identity from empty evidence, and the {@code sub}-named user otherwise. */
    private record SubjectRoleIdentityResolver() implements SecurityIdentityResolver {
        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            if (context.evidence().isEmpty()) {
                return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
            }
            Object subject = context.evidence().get(0).safeAttributes().get("sub");
            return Future.succeededFuture(Optional.of(
                    SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, String.valueOf(subject), Map.of()))));
        }
    }

    private static final ContextHolder NO_OP_CONTEXT_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return ContextScopes.noop();
        }
    };

    /** A {@link SecurityRuntime} that keeps the last bound {@link SecurityContext}. */
    private static final class RecordingSecurityRuntime implements SecurityRuntime {
        private volatile SecurityContext bound;

        @Override
        public SecurityContext current() {
            return bound;
        }

        @Override
        public ContextHolder.Scope bindCurrent(SecurityContext context) {
            bound = context;
            return ContextScopes.noop();
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }
}
