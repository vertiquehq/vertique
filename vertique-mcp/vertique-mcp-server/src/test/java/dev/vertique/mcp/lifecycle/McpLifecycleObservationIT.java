// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.mcp.server.McpLifecycleObservationITFixture;
import dev.vertique.mcp.server.McpLifecycleObservationITFixture.CapableObserver;
import dev.vertique.mcp.server.McpLifecycleObservationITFixture.ErrorToolInvoker;
import dev.vertique.mcp.server.McpLifecycleObservationITFixture.PlainObserver;
import dev.vertique.mcp.server.McpLifecycleObservationITFixture.RecordingCapableSession;
import dev.vertique.mcp.server.McpLifecycleObservationITFixture.RecordingPlainSession;
import dev.vertique.mcp.server.McpLifecycleObservationITFixture.Started;
import dev.vertique.mcp.server.McpLifecycleObservationITFixture.SuccessToolInvoker;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T020 TP-002 — the fixed lifecycle callback order holds across a successful call, a tool-error call,
 * and a rejected call: {@code open -> onToolInput -> onToolOutput -> onTerminal -> onCompleted}, each
 * applicable callback exactly once, with a rejected call skipping the two value callbacks while still
 * delivering exactly one terminal and one completion.
 *
 * <p>Modeled on {@code McpInputLifecycleObservationIT}'s harness: bind and connect explicitly to
 * {@code 127.0.0.1} (never the default host or {@code "localhost"}), and close every owned resource on
 * every teardown path. Framework wiring lives behind {@link McpLifecycleObservationITFixture}; only the
 * Given values, the one action per outcome, and the decisive assertions live here. Following T017's
 * established technique, each session records its callbacks into <em>one shared ordered list</em>
 * rather than independent booleans, so a reordering — not merely a missing or extra call — would be
 * caught.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpLifecycleObservationIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SSE_PREFIX = "event: message\ndata: ";
    private static final String UNKNOWN_TOOL_NAME = "lifecycle.observation.unknown";

    private final Vertx vertx = Vertx.vertx();

    private HttpServer server;
    private int port;
    private HttpClient rawClient;
    private WebClient client;

    @AfterEach
    void tearDown() throws Exception {
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose)
                .compose(ignored -> vertx.close())
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        server = null;
        port = 0;
        rawClient = null;
        client = null;
    }

    @Test
    @DisplayName("delivers open -> onToolInput -> onToolOutput -> onTerminal -> onCompleted exactly once per request")
    void shouldOrderEveryCallbackExactlyOncePerRequest() throws Exception {
        // Given: a port-0 server with one capability-implementing observer and one plain observer, a
        // successful-call tool, and a tool-error-call tool.
        CapableObserver capable = new CapableObserver();
        PlainObserver plain = new PlainObserver();
        SuccessToolInvoker successTool = new SuccessToolInvoker();
        ErrorToolInvoker errorTool = new ErrorToolInvoker();
        Started started = McpLifecycleObservationITFixture.start(vertx, capable, plain, successTool, errorTool);
        server = started.server();
        port = started.port();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        // When: execute one request per outcome — a successful call, a tool-error call, and a rejected
        // call (an unknown tool name, settled before any tool is ever resolved).
        HttpResponse<Buffer> successResponse = await(callTool(SuccessToolInvoker.TOOL_NAME, 1));
        HttpResponse<Buffer> toolErrorResponse = await(callTool(ErrorToolInvoker.TOOL_NAME, 2));
        HttpResponse<Buffer> rejectedResponse = await(callTool(UNKNOWN_TOOL_NAME, 3));

        assertThat(successResponse.statusCode()).isEqualTo(200);
        JsonObject successResult = sseResult(successResponse.bodyAsString());
        assertThat(successResult.getString("resultType")).isEqualTo("complete");
        assertThat(successResult.getBoolean("isError")).isFalse();
        assertThat(toolErrorResponse.statusCode()).isEqualTo(200);
        JsonObject toolErrorResult = sseResult(toolErrorResponse.bodyAsString());
        assertThat(toolErrorResult.getString("resultType")).isEqualTo("complete");
        assertThat(toolErrorResult.getBoolean("isError")).isTrue();
        assertThat(rejectedResponse.statusCode())
                .as("an unknown tool name is rejected before any invocation is attempted")
                .isEqualTo(400);

        // Then (DECISIVE — one exact ordered sequence per session, not five independent booleans):
        // open() is called once per request, so the three requests above produced three independent
        // sessions per observer, in call order.
        List<RecordingCapableSession> capableSessions = capable.sessions();
        assertThat(capableSessions)
                .as("open() must be called exactly once per request for the capable observer")
                .hasSize(3);
        assertThat(capableSessions.get(0).events())
                .as("DECISIVE: the successful call's exact ordered callback sequence")
                .containsExactly("open", "onToolInput", "onToolOutput", "onTerminal", "onCompleted");
        assertThat(capableSessions.get(1).events())
                .as("DECISIVE: the tool-error call's exact ordered callback sequence — a completed "
                        + "tool-error result still runs the full value-observation pipeline")
                .containsExactly("open", "onToolInput", "onToolOutput", "onTerminal", "onCompleted");
        assertThat(capableSessions.get(2).events())
                .as("DECISIVE: the rejected call skips both value callbacks but still delivers exactly "
                        + "one terminal and one completion")
                .containsExactly("open", "onTerminal", "onCompleted");

        List<RecordingPlainSession> plainSessions = plain.sessions();
        assertThat(plainSessions)
                .as("open() must be called exactly once per request for the plain observer too")
                .hasSize(3);
        for (RecordingPlainSession session : plainSessions) {
            assertThat(session.events())
                    .as("a plain McpRequestObservation session can only ever record open/terminal/completed, "
                            + "for every outcome")
                    .containsExactly("open", "onTerminal", "onCompleted");
        }
    }

    // --- Wire helpers ---

    private Future<HttpResponse<Buffer>> callTool(String toolName, Object id) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params =
                new JsonObject().put("_meta", meta).put("name", toolName).put("arguments", new JsonObject());
        JsonObject body = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", "tools/call")
                .put("params", params);
        return client.post(port, LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", toolName)
                .sendBuffer(body.toBuffer());
    }

    private static JsonObject sseResult(String rawBody) {
        assertThat(rawBody).startsWith(SSE_PREFIX);
        JsonObject decoded =
                new JsonObject(rawBody.substring(SSE_PREFIX.length()).stripTrailing());
        JsonObject result = decoded.getJsonObject("result");
        assertThat(result).isNotNull();
        return result;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
