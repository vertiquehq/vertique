// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.interceptor.McpRequestInterceptor;
import dev.vertique.mcp.interceptor.McpToolInterceptor;
import dev.vertique.mcp.lifecycle.McpCompletionScope;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpRawEvidenceObservation;
import dev.vertique.mcp.lifecycle.McpRequestAdmissionEvidence;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.lifecycle.McpToolInputObservation;
import dev.vertique.mcp.lifecycle.McpToolOutputObservation;
import dev.vertique.mcp.lifecycle.McpToolValueObservation;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpInputRejectionException;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import dev.vertique.ratelimit.GreedyRateLimitRefill;
import dev.vertique.ratelimit.LocalRateLimitBackendFactory;
import dev.vertique.ratelimit.RateLimitFailureMode;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitPolicy;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.TokenBucketRateLimit;
import dev.vertique.ratelimit.spi.AnonymousRateLimitPolicy;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendRequest;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import dev.vertique.ratelimit.spi.RateLimitSubject;
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
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the order of the whole MCP request pipeline against one real port-0 mount.
 *
 * <p>The order is otherwise an emergent property of route registration order and statement order
 * across the router mount, the request dispatcher and the completion coordinator, and each
 * earlier test covers one adjacency. This class covers the sequence as a whole in two ways:
 *
 * <ul>
 *   <li><strong>Precedence rows.</strong> Each row sends a request that violates two adjacent stages
 *       at once and asserts that the earlier stage's error is the one on the wire, that the later
 *       stage's collaborators were never entered, and that the terminal observation carries the
 *       earlier stage's classification. Each row distinguishes the order of the two stages it names:
 *       reversing that pair in production code makes the row fail. Pairs the rows do not name are
 *       covered only by the full-sequence test below.
 *   <li><strong>Full sequence.</strong> One successful call records every stage a probe can observe
 *       and asserts the exact order, including the completion side: the terminal observation before
 *       the tool-output observation, and every completion scope opened before and closed after the
 *       completion callbacks.
 * </ul>
 *
 * <p>Authorization has no probe of its own: its position is pinned through the responses that
 * differ depending on whether it ran before or after its neighbours. A denied tool and an unknown
 * tool share one wire response, so the rows distinguish them through the terminal observation's
 * tool name.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpPipelineOrderIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SSE_PREFIX = "event: message\ndata: ";
    private static final long ASYNC_TIMEOUT_SECONDS = 10;
    private static final long BODY_LIMIT_BYTES = 16_384;
    private static final int OVERSIZED_BODY_BYTES = 20_000;
    private static final String DISALLOWED_ORIGIN = "https://untrusted.example";
    private static final String POLICY_NAME = "order-quota";

    private static final String OPEN_TOOL = "order.open";
    private static final String DENIED_TOOL = "order.denied";
    private static final String LIMITED_TOOL = "order.limited";
    private static final String OVER_CAP_TOOL = "order.overcap";
    private static final String BAD_SHAPE_TOOL = "order.badshape";
    private static final String UNKNOWN_TOOL = "order.missing";

    private static final String CAPABILITY = "elicitation";
    private static final String PREPARE_REJECTION = "prepare-rejected";
    private static final String SCHEMA_REJECTION = "Invalid tool arguments: schema validation failed";
    private static final String TOOL_INTERCEPTOR_REJECTION = "Tool call rejected";

    // Probe event names, in the order a successful call produces them.
    private static final String OPEN = "lifecycle.open";
    private static final String IDENTITY = "identity.resolve";
    private static final String ADMITTED = "evidence.admitted";
    private static final String REQUEST_INTERCEPTOR = "interceptor.request";
    private static final String ADMISSION = "admission.consume";
    private static final String PREPARE = "tool.prepare";
    private static final String TOOL_INPUT = "observation.toolInput";
    private static final String TOOL_INTERCEPTOR = "interceptor.tool";
    private static final String INVOKE = "tool.invoke";
    private static final String TERMINAL = "observation.terminal";
    private static final String TOOL_OUTPUT = "observation.toolOutput";
    private static final String SCOPE_OPEN = "completion.scopeOpen";
    private static final String COMPLETED = "completion.observation";
    private static final String LISTENER_COMPLETED = "completion.listener";
    private static final String SCOPE_CLOSE = "completion.scopeClose";

    private static final Set<String> COMPLETION_EVENTS = Set.of(SCOPE_OPEN, COMPLETED, LISTENER_COMPLETED, SCOPE_CLOSE);
    private static final List<String> REJECTED_AFTER_INTERCEPTOR =
            List.of(OPEN, IDENTITY, ADMITTED, REQUEST_INTERCEPTOR, TERMINAL);
    private static final List<String> THROUGH_INVOKE = List.of(
            OPEN, IDENTITY, ADMITTED, REQUEST_INTERCEPTOR, PREPARE, TOOL_INPUT, TOOL_INTERCEPTOR, INVOKE, TERMINAL);

    private final Vertx vertx = Vertx.vertx();

    private Fixture fixture;
    private HttpClient rawClient;
    private WebClient client;

    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future<Void> serverClose = fixture != null ? fixture.server().close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose).onComplete(joined -> vertx.close().onComplete(vertxResult -> {
            Throwable failure = joined.failed() ? joined.cause() : vertxResult.cause();
            if (failure == null) {
                closed.complete(null);
            } else {
                closed.completeExceptionally(failure);
            }
        }));
        closed.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        fixture = null;
        rawClient = null;
        client = null;
    }

    private static Stream<Row> precedenceRows() {
        return Stream.of(
                // --- ingress: cheap admission, then body limit, before any lifecycle opens ---
                new Row(
                        "method is judged before origin",
                        call(OPEN_TOOL, "{}", new JsonObject())
                                .method(HttpMethod.GET)
                                .header("Origin", DISALLOWED_ORIGIN),
                        Setup.NONE,
                        Expect.status(405, List.of())),
                new Row(
                        "origin is judged before content type",
                        call(OPEN_TOOL, "{}", new JsonObject())
                                .header("Origin", DISALLOWED_ORIGIN)
                                .withoutHeader("content-type"),
                        Setup.NONE,
                        Expect.status(403, List.of())),
                new Row(
                        "content type is judged before accept",
                        call(OPEN_TOOL, "{}", new JsonObject())
                                .header("content-type", "text/plain")
                                .header("Accept", "text/html"),
                        Setup.NONE,
                        Expect.status(415, List.of())),
                new Row(
                        "accept is judged before the body limit",
                        call(OPEN_TOOL, "{}", new JsonObject())
                                .header("Accept", "text/html")
                                .body("x".repeat(OVERSIZED_BODY_BYTES)),
                        Setup.NONE,
                        Expect.status(406, List.of())),
                new Row(
                        "body limit is judged before envelope parsing",
                        call(OPEN_TOOL, "{}", new JsonObject()).body("{not json ".repeat(OVERSIZED_BODY_BYTES / 10)),
                        Setup.NONE,
                        Expect.status(413, List.of())),
                // --- envelope and protocol negotiation ---
                new Row(
                        "envelope parsing is judged before negotiation",
                        call(OPEN_TOOL, "{}", new JsonObject())
                                .body("{not json")
                                .header("Mcp-Method", "tools/list")
                                .header("MCP-Protocol-Version", "1999-01-01"),
                        Setup.NONE,
                        Expect.rpc(400, -32700, List.of(OPEN, IDENTITY, TERMINAL))),
                new Row(
                        "identity establishment is judged before envelope parsing",
                        call(OPEN_TOOL, "{}", new JsonObject()).body("{not json"),
                        Setup.FAIL_IDENTITY,
                        Expect.status(500, List.of(OPEN, IDENTITY, TERMINAL))),
                new Row(
                        "an unknown method is judged before the negotiation failure its missing _meta would cause",
                        call(OPEN_TOOL, "{}", new JsonObject())
                                .body(new JsonObject()
                                        .put("jsonrpc", "2.0")
                                        .put("id", 1)
                                        .put("method", "tools/unknown")
                                        .put("params", new JsonObject())
                                        .encode()),
                        Setup.NONE,
                        Expect.rpc(404, -32601, List.of(OPEN, IDENTITY, TERMINAL))),
                new Row(
                        "params schema is judged before negotiation",
                        call(OPEN_TOOL, "{}", new JsonObject())
                                .body(callBody(OPEN_TOOL, "{}", new JsonObject())
                                        .put(
                                                "params",
                                                callParams(OPEN_TOOL, "{}", new JsonObject())
                                                        .put("name", 5))
                                        .encode())
                                .header("Mcp-Method", "tools/list"),
                        Setup.NONE,
                        Expect.rpc(400, -32602, List.of(OPEN, IDENTITY, ADMITTED, TERMINAL))),
                new Row(
                        "negotiation is judged before the request interceptors",
                        call(OPEN_TOOL, "{}", new JsonObject()).header("Mcp-Method", "tools/list"),
                        Setup.REJECT_REQUESTS,
                        Expect.rpc(400, -32020, List.of(OPEN, IDENTITY, ADMITTED, TERMINAL))),
                // --- request interceptors, then tool resolution and authorization ---
                new Row(
                        "request interceptors are judged before tool resolution",
                        call(UNKNOWN_TOOL, "{}", new JsonObject()),
                        Setup.REJECT_REQUESTS,
                        Expect.rpc(403, -32001, REJECTED_AFTER_INTERCEPTOR).tool("UNKNOWN")),
                new Row(
                        "authorization is judged before the required client capability",
                        call(DENIED_TOOL, "{}", new JsonObject()),
                        Setup.NONE,
                        Expect.rpc(400, -32602, REJECTED_AFTER_INTERCEPTOR).tool(DENIED_TOOL)),
                // --- capability, admission, then the input pipeline ---
                new Row(
                        "required client capability is judged before admission",
                        call(LIMITED_TOOL, "{}", new JsonObject()),
                        Setup.DRAIN_QUOTA,
                        Expect.rpc(400, -32021, REJECTED_AFTER_INTERCEPTOR).tool(LIMITED_TOOL)),
                new Row(
                        "admission is judged before input schema validation and response transport selection",
                        call(LIMITED_TOOL, "{\"n\":\"x\"}", capability()),
                        Setup.DRAIN_QUOTA,
                        Expect.rpc(
                                        429,
                                        McpRequestDispatcher.RATE_LIMITED,
                                        List.of(OPEN, IDENTITY, ADMITTED, REQUEST_INTERCEPTOR, ADMISSION, TERMINAL))
                                .tool(LIMITED_TOOL)
                                .json()),
                new Row(
                        "input schema validation is judged before input processing",
                        call(OPEN_TOOL, "{\"n\":\"x\",\"rejectInput\":true}", new JsonObject()),
                        Setup.NONE,
                        Expect.toolError(SCHEMA_REJECTION, REJECTED_AFTER_INTERCEPTOR, McpErrorType.INPUT_VALIDATION)),
                new Row(
                        "input processing is judged before the tool interceptors",
                        call(OPEN_TOOL, "{\"rejectInput\":true}", new JsonObject()),
                        Setup.REJECT_TOOLS,
                        Expect.toolError(
                                PREPARE_REJECTION,
                                List.of(OPEN, IDENTITY, ADMITTED, REQUEST_INTERCEPTOR, PREPARE, TERMINAL),
                                McpErrorType.INPUT_PROCESSING)),
                new Row(
                        "tool interceptors are judged before invocation",
                        call(OPEN_TOOL, "{\"n\":1}", new JsonObject()),
                        Setup.REJECT_TOOLS,
                        Expect.toolError(
                                TOOL_INTERCEPTOR_REJECTION,
                                List.of(
                                        OPEN,
                                        IDENTITY,
                                        ADMITTED,
                                        REQUEST_INTERCEPTOR,
                                        PREPARE,
                                        TOOL_INPUT,
                                        TOOL_INTERCEPTOR,
                                        TERMINAL),
                                McpErrorType.INTERCEPTOR)),
                // --- output: bounded normalization, then output schema, then the write ---
                new Row(
                        "output normalization is judged before output schema validation",
                        call(OVER_CAP_TOOL, "{}", new JsonObject()),
                        Setup.NONE,
                        Expect.internalError(THROUGH_INVOKE, McpErrorType.SERIALIZATION)),
                // Negative pin: the output-schema failure settles through its own writer, which never
                // reaches the tool-output publication, so the absence of that observation is the expected
                // result of the stage order rather than of a missing probe.
                new Row(
                        "an output-schema-invalid result settles without reaching the output observation",
                        call(BAD_SHAPE_TOOL, "{}", new JsonObject()),
                        Setup.NONE,
                        Expect.internalError(THROUGH_INVOKE, McpErrorType.OUTPUT_VALIDATION)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("precedenceRows")
    @DisplayName("an earlier pipeline stage wins when a request also violates the next stage")
    void shouldLetTheEarlierStageWin(Row row) throws Exception {
        start();
        row.setup().apply(this);

        HttpResponse<Buffer> response = send(row.wire());
        fixture.probe().awaitCompletionWhenOpened();

        Expect expect = row.expect();
        assertThat(response.statusCode()).as("HTTP status").isEqualTo(expect.status());
        if (expect.rpcCode() != null) {
            assertThat(responseJson(response).getJsonObject("error").getInteger("code"))
                    .as("JSON-RPC error code")
                    .isEqualTo(expect.rpcCode());
        }
        if (expect.bodyContains() != null) {
            assertThat(response.bodyAsString()).contains(expect.bodyContains());
        }
        assertThat(fixture.probe().stages())
                .as("stages entered, in order (completion callbacks excluded)")
                .isEqualTo(expect.stages());
        if (expect.transport() == Transport.JSON) {
            assertThat(response.getHeader("Content-Type")).startsWith("application/json");
            assertThat(response.getHeader("X-Accel-Buffering"))
                    .as("response transport selection must not have run")
                    .isNull();
        }
        if (expect.errorType() != null) {
            assertThat(fixture.probe().lastTerminal().errorType()).isEqualTo(expect.errorType());
        }
        if (expect.terminalTool() != null) {
            assertThat(fixture.probe().lastTerminal().toolName()).isEqualTo(expect.terminalTool());
        }
    }

    @Test
    @DisplayName("an unknown tool and a denied tool share one wire response and differ only in the terminal tool name")
    void shouldAnswerUnknownAndDeniedToolsWithTheSameWireResponse() throws Exception {
        start();

        HttpResponse<Buffer> unknown = send(call(UNKNOWN_TOOL, "{}", capability()));
        fixture.probe().awaitCompletionWhenOpened();
        String unknownTerminalTool = fixture.probe().lastTerminal().toolName();
        List<String> unknownStages = fixture.probe().stages();
        fixture.probe().clear();

        HttpResponse<Buffer> denied = send(call(DENIED_TOOL, "{}", capability()));
        fixture.probe().awaitCompletionWhenOpened();
        String deniedTerminalTool = fixture.probe().lastTerminal().toolName();
        List<String> deniedStages = fixture.probe().stages();

        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(denied.statusCode()).isEqualTo(unknown.statusCode());
        assertThat(denied.bodyAsString()).isEqualTo(unknown.bodyAsString());
        assertThat(responseJson(unknown).getJsonObject("error").getInteger("code"))
                .isEqualTo(-32602);
        assertThat(unknownStages).isEqualTo(REJECTED_AFTER_INTERCEPTOR);
        assertThat(deniedStages).isEqualTo(REJECTED_AFTER_INTERCEPTOR);
        assertThat(unknownTerminalTool).isEqualTo("UNKNOWN");
        assertThat(deniedTerminalTool).isEqualTo(DENIED_TOOL);
    }

    @Test
    @DisplayName("a successful call enters every observable stage once, in pipeline order")
    void shouldEnterEveryObservableStageInPipelineOrderOnASuccessfulCall() throws Exception {
        start();

        HttpResponse<Buffer> response = send(call(LIMITED_TOOL, "{\"n\":1}", capability()));
        fixture.probe().awaitCompletionWhenOpened();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.bodyAsString()).startsWith(SSE_PREFIX).contains("done");
        assertThat(response.getHeader("Content-Type")).startsWith("text/event-stream");
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
        fixture.probe().awaitEvent(TOOL_OUTPUT);
        List<String> events = fixture.probe().events();
        assertThat(fixture.probe().stages())
                .as("DECISIVE: every request-side stage once, in order, with the terminal observation "
                        + "before the tool-output observation")
                .containsExactly(
                        OPEN,
                        IDENTITY,
                        ADMITTED,
                        REQUEST_INTERCEPTOR,
                        ADMISSION,
                        PREPARE,
                        TOOL_INPUT,
                        TOOL_INTERCEPTOR,
                        INVOKE,
                        TERMINAL,
                        TOOL_OUTPUT);
        // Whether the transport completion lands before or after the tool-output observation depends
        // on whether the response write resolves synchronously, so only the completion bracket itself
        // is pinned: scopes open, then both completion callbacks, then scopes close, contiguously and
        // after the terminal observation.
        int scopeOpen = events.indexOf(SCOPE_OPEN);
        assertThat(scopeOpen).isGreaterThan(events.indexOf(TERMINAL));
        assertThat(events.subList(scopeOpen, scopeOpen + 4))
                .as("completion scopes open before and close after both completion callbacks")
                .containsExactly(SCOPE_OPEN, COMPLETED, LISTENER_COMPLETED, SCOPE_CLOSE);
    }

    // --- wire helpers ---

    private void start() throws Exception {
        fixture = Fixture.start(vertx);
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
    }

    private HttpResponse<Buffer> send(Wire wire) throws Exception {
        HttpRequest<Buffer> request = client.request(wire.method(), fixture.port(), LOOPBACK, REQUEST_PATH);
        wire.headers().forEach(request::putHeader);
        return await(request.sendBuffer(Buffer.buffer(wire.body())));
    }

    private static JsonObject responseJson(HttpResponse<Buffer> response) {
        String body = response.bodyAsString();
        return new JsonObject(
                body.startsWith(SSE_PREFIX)
                        ? body.substring(SSE_PREFIX.length()).stripTrailing()
                        : body);
    }

    private static JsonObject capability() {
        return new JsonObject().put(CAPABILITY, new JsonObject());
    }

    private static JsonObject callParams(String tool, String argumentsJson, JsonObject capabilities) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", capabilities);
        return new JsonObject().put("_meta", meta).put("name", tool).put("arguments", new JsonObject(argumentsJson));
    }

    private static JsonObject callBody(String tool, String argumentsJson, JsonObject capabilities) {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put("params", callParams(tool, argumentsJson, capabilities));
    }

    /** A well-formed {@code tools/call} request; rows then break exactly the parts they test. */
    private static Wire call(String tool, String argumentsJson, JsonObject capabilities) {
        return new Wire()
                .header("content-type", "application/json")
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .header("Mcp-Method", "tools/call")
                .header("Mcp-Name", tool)
                .body(callBody(tool, argumentsJson, capabilities).encode());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    // --- row model ---

    /** One request as it goes on the wire. */
    private static final class Wire {
        private HttpMethod method = HttpMethod.POST;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String body = "";

        Wire method(HttpMethod value) {
            this.method = value;
            return this;
        }

        Wire header(String name, String value) {
            headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
            headers.put(name, value);
            return this;
        }

        Wire withoutHeader(String name) {
            headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
            return this;
        }

        Wire body(String value) {
            this.body = value;
            return this;
        }

        HttpMethod method() {
            return method;
        }

        Map<String, String> headers() {
            return headers;
        }

        String body() {
            return body;
        }
    }

    /** How the server is armed before the request under test is sent. */
    private enum Setup {
        NONE {
            @Override
            void apply(McpPipelineOrderIT test) {}
        },
        REJECT_REQUESTS {
            @Override
            void apply(McpPipelineOrderIT test) {
                test.fixture.rejectRequests().set(true);
            }
        },
        FAIL_IDENTITY {
            @Override
            void apply(McpPipelineOrderIT test) {
                test.fixture.failIdentity().set(true);
            }
        },
        REJECT_TOOLS {
            @Override
            void apply(McpPipelineOrderIT test) {
                test.fixture.rejectTools().set(true);
            }
        },
        /** Spends the only token of the rate-limited tool with one ordinary call. */
        DRAIN_QUOTA {
            @Override
            void apply(McpPipelineOrderIT test) throws Exception {
                HttpResponse<Buffer> drain = test.send(call(LIMITED_TOOL, "{\"n\":1}", capability()));
                assertThat(drain.statusCode()).isEqualTo(200);
                test.fixture.probe().awaitCompletionWhenOpened();
                test.fixture.probe().awaitEvent(TOOL_OUTPUT);
                test.fixture.probe().clear();
            }
        };

        abstract void apply(McpPipelineOrderIT test) throws Exception;
    }

    private record Expect(
            int status,
            Integer rpcCode,
            String bodyContains,
            List<String> stages,
            McpErrorType errorType,
            String terminalTool,
            Transport transport) {

        static Expect status(int status, List<String> stages) {
            return new Expect(status, null, null, stages, null, null, Transport.UNCHECKED);
        }

        static Expect rpc(int status, int rpcCode, List<String> stages) {
            return new Expect(status, rpcCode, null, stages, null, null, Transport.UNCHECKED);
        }

        static Expect toolError(String bodyContains, List<String> stages, McpErrorType errorType) {
            return new Expect(200, null, bodyContains, stages, errorType, null, Transport.UNCHECKED);
        }

        static Expect internalError(List<String> stages, McpErrorType errorType) {
            return new Expect(500, -32603, null, stages, errorType, null, Transport.UNCHECKED);
        }

        Expect tool(String toolName) {
            return new Expect(status, rpcCode, bodyContains, stages, errorType, toolName, transport);
        }

        /** The response is plain JSON: response transport selection was never reached. */
        Expect json() {
            return new Expect(status, rpcCode, bodyContains, stages, errorType, terminalTool, Transport.JSON);
        }
    }

    private enum Transport {
        UNCHECKED,
        JSON
    }

    private record Row(String name, Wire wire, Setup setup, Expect expect) {
        @Override
        public String toString() {
            return name;
        }
    }

    // --- probe ---

    /** Records every stage entry a test can observe, in the order the server enters them. */
    private static final class Probe {
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final List<McpRequestTerminalEvent> terminals = new CopyOnWriteArrayList<>();
        private volatile CompletableFuture<Void> scopeClosed = new CompletableFuture<>();

        void record(String event) {
            events.add(event);
            if (SCOPE_CLOSE.equals(event)) {
                scopeClosed.complete(null);
            }
        }

        /** A request that never opened a lifecycle observation has no completion to wait for. */
        void awaitCompletionWhenOpened() throws Exception {
            if (events.contains(OPEN)) {
                scopeClosed.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }

        void clear() {
            events.clear();
            terminals.clear();
            scopeClosed = new CompletableFuture<>();
        }

        List<String> events() {
            return List.copyOf(events);
        }

        /** Every recorded stage entry except the completion callbacks and their scopes. */
        List<String> stages() {
            return events.stream()
                    .filter(event -> !COMPLETION_EVENTS.contains(event))
                    .toList();
        }

        /** Waits for a stage that is recorded after the response may already have been completed. */
        void awaitEvent(String event) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ASYNC_TIMEOUT_SECONDS);
            while (!events.contains(event)) {
                assertThat(System.nanoTime())
                        .as("timed out waiting for " + event)
                        .isLessThan(deadline);
                Thread.sleep(5);
            }
        }

        McpRequestTerminalEvent lastTerminal() {
            assertThat(terminals).as("terminal observations").isNotEmpty();
            return terminals.get(terminals.size() - 1);
        }
    }

    private static final class ProbeObserver implements McpRequestLifecycleObserver {
        private final Probe probe;

        ProbeObserver(Probe probe) {
            this.probe = probe;
        }

        @Override
        public McpRequestObservation open(Instant startedAt) {
            probe.record(OPEN);
            return new Session(probe);
        }
    }

    private static final class Session
            implements McpRawEvidenceObservation, McpToolValueObservation, McpCompletionScope {
        private final Probe probe;

        Session(Probe probe) {
            this.probe = probe;
        }

        @Override
        public void onRequestAdmitted(McpRequestAdmissionEvidence evidence) {
            probe.record(ADMITTED);
        }

        @Override
        public void onToolInput(McpToolInputObservation observation) {
            probe.record(TOOL_INPUT);
        }

        @Override
        public void onToolOutput(McpToolOutputObservation observation) {
            probe.record(TOOL_OUTPUT);
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            probe.terminals.add(observation.event());
            probe.record(TERMINAL);
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            probe.record(COMPLETED);
        }

        @Override
        public AutoCloseable openCompletionScope() {
            probe.record(SCOPE_OPEN);
            return () -> probe.record(SCOPE_CLOSE);
        }
    }

    /** Records the point at which the admission step reaches the rate-limit backend. */
    private static final class ProbeBackend implements RateLimitBackend {
        private final RateLimitBackend delegate = LocalRateLimitBackendFactory.local(ignored -> 100L, 60_000L);
        private final Probe probe;

        ProbeBackend(Probe probe) {
            this.probe = probe;
        }

        @Override
        public Future<RateLimitBackendResult> consume(RateLimitBackendRequest request) {
            probe.record(ADMISSION);
            return delegate.consume(request);
        }
    }

    // --- tools ---

    private static final class ProbeTool implements McpToolInvoker {
        private final McpToolDescriptor descriptor;
        private final Set<String> requiredCapabilities;
        private final Probe probe;
        private final Supplier<McpToolResult<?>> result;

        ProbeTool(
                String name,
                McpToolAccess access,
                String outputSchema,
                Set<String> requiredCapabilities,
                Probe probe,
                Supplier<McpToolResult<?>> result) {
            this.descriptor = new McpToolDescriptor(
                    name,
                    null,
                    "Pipeline order fixture tool.",
                    new McpToolAnnotations(true, false, true, false),
                    "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\"},"
                            + "\"rejectInput\":{\"type\":\"boolean\"}},\"additionalProperties\":false}",
                    outputSchema,
                    access);
            this.requiredCapabilities = requiredCapabilities;
            this.probe = probe;
            this.result = result;
        }

        @Override
        public McpToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public Set<String> requiredClientCapabilities() {
            return requiredCapabilities;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            probe.record(PREPARE);
            if (Boolean.TRUE.equals(arguments.get("rejectInput"))) {
                throw new McpInputRejectionException(PREPARE_REJECTION);
            }
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return Map.of();
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    probe.record(INVOKE);
                    return Future.succeededFuture(result.get());
                }
            };
        }
    }

    // --- framework wiring ---

    /** Resolves the anonymous identity, records the stage, and fails on demand. */
    private record ProbeIdentityResolver(Probe probe, AtomicBoolean fail) implements SecurityIdentityResolver {
        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            probe.record(IDENTITY);
            if (fail.get()) {
                return Future.failedFuture(new RuntimeException("identity resolution failed by fixture"));
            }
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

    /**
     * One real port-0 mount with the default (empty) origin allowlist, a small body limit and output
     * cap, one rate-limited tool, and a probe on every stage a test can observe.
     */
    private static final class Fixture {
        private final HttpServer server;
        private final int port;
        private final Probe probe = new Probe();
        private final AtomicBoolean rejectRequests = new AtomicBoolean();
        private final AtomicBoolean rejectTools = new AtomicBoolean();
        private final AtomicBoolean failIdentity = new AtomicBoolean();

        private Fixture(Vertx vertx) throws Exception {
            McpToolRateLimitConfig limited = new McpToolRateLimitConfig(LIMITED_TOOL, POLICY_NAME, null, null, 1L);
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName("vertique-test")
                    .serverVersion("1.0")
                    .outputMaxBytes(2_048)
                    .rateLimit(new McpRateLimitConfig(
                            null, RateLimitSubject.NONE, AnonymousRateLimitPolicy.SHARED_BUCKET, List.of(limited)))
                    .build();
            String outputSchema =
                    "{\"type\":\"object\",\"properties\":{\"v\":{\"type\":\"integer\"}}," + "\"required\":[\"v\"]}";
            McpToolAccess permitAll = new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null);
            McpToolRegistry registry = McpToolRegistry.build(Set.of(
                    new ProbeTool(OPEN_TOOL, permitAll, null, Set.of(), probe, () -> McpToolResult.text("done")),
                    new ProbeTool(
                            DENIED_TOOL,
                            new McpToolAccess(McpAccessMode.DENY_ALL, List.of(), null),
                            null,
                            Set.of(CAPABILITY),
                            probe,
                            () -> McpToolResult.text("never")),
                    new ProbeTool(
                            LIMITED_TOOL, permitAll, null, Set.of(CAPABILITY), probe, () -> McpToolResult.text("done")),
                    new ProbeTool(
                            OVER_CAP_TOOL,
                            permitAll,
                            outputSchema,
                            Set.of(),
                            probe,
                            () -> McpToolResult.structured(Map.of("v", "x".repeat(4_096)))),
                    new ProbeTool(
                            BAD_SHAPE_TOOL,
                            permitAll,
                            outputSchema,
                            Set.of(),
                            probe,
                            () -> McpToolResult.structured(Map.of("v", "not-an-integer")))));
            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
            McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                    Optional.empty(),
                    Optional.empty(),
                    Set.of(),
                    new SecurityEventEmitter(Set.of()),
                    NO_OP_CONTEXT_HOLDER,
                    securityRuntime,
                    Optional.empty(),
                    TestResilience.shared()));
            HttpConfig httpConfig = HttpConfig.builder()
                    .idleTimeoutSeconds(60)
                    .maxBodySize(BODY_LIMIT_BYTES)
                    .build();
            McpRequestInterceptor requestInterceptor = context -> {
                probe.record(REQUEST_INTERCEPTOR);
                return rejectRequests.get()
                        ? Future.failedFuture(new RuntimeException("rejected by fixture"))
                        : Future.succeededFuture();
            };
            McpToolInterceptor toolInterceptor = context -> {
                probe.record(TOOL_INTERCEPTOR);
                return rejectTools.get()
                        ? Future.failedFuture(new RuntimeException("rejected by fixture"))
                        : Future.succeededFuture();
            };
            McpRequestCompletedListener listener = event -> probe.record(LISTENER_COMPLETED);
            RateLimitPolicy policy = new RateLimitPolicy(
                    POLICY_NAME,
                    true,
                    RateLimitMode.LOCAL,
                    RateLimitFailureMode.OPEN,
                    "r1",
                    1L,
                    new TokenBucketRateLimit(1L, new GreedyRateLimitRefill(1L, Duration.ofMinutes(1))));
            RateLimiters rateLimiters = new RateLimiters(
                    Set.of(policy),
                    Map.of(RateLimitMode.LOCAL, new ProbeBackend(probe)),
                    null,
                    vertx,
                    Set.of(),
                    Optional::empty,
                    true);
            McpRequestDispatcher dispatcher = new McpRequestDispatcher(
                    config,
                    securityRuntime,
                    Set.of(new ProbeObserver(probe)),
                    Set.of(listener),
                    Set.of(requestInterceptor),
                    Set.of(toolInterceptor),
                    httpConfig,
                    registry,
                    policyEnforcer,
                    McpToolAdmission.create(config, registry, Optional.of(rateLimiters)),
                    NO_OP_CONTEXT_HOLDER,
                    new CorrelationContextFactory(Optional.empty()));
            McpRouterMount mount = new McpRouterMount(
                    config,
                    new McpServerConfigValidator(),
                    dispatcher,
                    Set.of(),
                    new IdentityResolutionMiddleware(
                            Set.of(new ProbeIdentityResolver(probe, failIdentity)),
                            Optional.of(new DefaultSecurityClaimMapper()),
                            new SecurityEventEmitter(Set.of()),
                            securityRuntime,
                            NO_OP_CONTEXT_HOLDER),
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

        Probe probe() {
            return probe;
        }

        AtomicBoolean rejectRequests() {
            return rejectRequests;
        }

        AtomicBoolean rejectTools() {
            return rejectTools;
        }

        AtomicBoolean failIdentity() {
            return failIdentity;
        }
    }
}
