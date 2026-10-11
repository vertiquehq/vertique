// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.mcp.server.McpLifecycleObservationITFixture;
import dev.vertique.mcp.server.McpLifecycleObservationITFixture.ErrorToolInvoker;
import dev.vertique.mcp.server.McpLifecycleObservationITFixture.OrderedListener;
import dev.vertique.mcp.server.McpLifecycleObservationITFixture.OrderedObserver;
import dev.vertique.mcp.server.McpLifecycleObservationITFixture.PlainObserver;
import dev.vertique.mcp.server.McpLifecycleObservationITFixture.RecordingPlainSession;
import dev.vertique.mcp.server.McpLifecycleObservationITFixture.Started;
import dev.vertique.mcp.server.McpLifecycleObservationITFixture.SuccessToolInvoker;
import dev.vertique.mcp.server.McpRecordingCompletedListener.Completion;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The fixed lifecycle callback order holds across a successful call, a tool-error call, and a
 * rejected call: {@code open -> onTerminal -> onCompleted}, then the completion listener with its
 * request view, each applicable callback exactly once per request. The view reports the prepared
 * call's arguments for the two calls that reached a tool and nothing for the rejected call, while
 * every request still delivers exactly one terminal and one completion.
 *
 * <p>Modeled on {@code McpInputLifecycleObservationIT}'s harness: bind and connect explicitly to
 * {@code 127.0.0.1} (never the default host or {@code "localhost"}), and close every owned resource on
 * every teardown path. Framework wiring lives behind {@link McpLifecycleObservationITFixture}; only
 * the Given values, the one action per outcome, and the decisive assertions live here. Every callback
 * is recorded into <em>one shared ordered journal</em> rather than independent booleans, so a
 * reordering — not merely a missing or extra call — would be caught.
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
    @DisplayName("delivers open -> onTerminal -> onCompleted -> listener view exactly once per request")
    void shouldOrderEveryCallbackExactlyOncePerRequest() throws Exception {
        // Given: a port-0 server with one ordered-event observer, one plain observer, one ordered-event
        // completion listener, a successful-call tool, and a tool-error-call tool.
        List<String> journal = Collections.synchronizedList(new ArrayList<>());
        OrderedObserver ordered = new OrderedObserver(journal);
        OrderedListener listener = new OrderedListener(journal);
        PlainObserver plain = new PlainObserver();
        SuccessToolInvoker successTool = new SuccessToolInvoker();
        ErrorToolInvoker errorTool = new ErrorToolInvoker();
        Started started =
                McpLifecycleObservationITFixture.start(vertx, ordered, plain, listener, successTool, errorTool);
        server = started.server();
        port = started.port();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        // When: execute one request per outcome — a successful call, a tool-error call, and a rejected
        // call (an unknown tool name, settled before any tool is ever resolved). The listener runs after
        // transport completion, so each request's completion is awaited before the next is sent.
        HttpResponse<Buffer> successResponse = await(callTool(SuccessToolInvoker.TOOL_NAME, 1));
        listener.recording().await(1);
        HttpResponse<Buffer> toolErrorResponse = await(callTool(ErrorToolInvoker.TOOL_NAME, 2));
        listener.recording().await(2);
        HttpResponse<Buffer> rejectedResponse = await(callTool(UNKNOWN_TOOL_NAME, 3));
        List<Completion> completions = listener.recording().await(3);

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

        // Then (DECISIVE — one exact ordered sequence, not independent booleans): open() is called once
        // per request, so the three requests above produced three open/terminal/completed triples, each
        // followed by exactly one listener callback, in call order.
        assertThat(journal)
                .as("DECISIVE: the exact ordered callback sequence of the three requests — each completion "
                        + "listener callback arrives after its own request's observer completion, and "
                        + "the rejected call still delivers exactly one terminal and one completion")
                .containsExactly(
                        "open",
                        "onTerminal",
                        "onCompleted",
                        "listener",
                        "open",
                        "onTerminal",
                        "onCompleted",
                        "listener",
                        "open",
                        "onTerminal",
                        "onCompleted",
                        "listener");

        // Then (DECISIVE — the view differs per outcome): a completed tool call, successful or a
        // tool error, reports the prepared call's arguments and its written output; a rejected call
        // reports neither, yet still reports the raw request it carried and the response it received.
        assertThat(completions).hasSize(3);
        assertThat(completions.get(0).toolInput())
                .as("DECISIVE: the successful call's view reports its prepared arguments")
                .isPresent();
        assertThat(completions.get(0).structuredOutput())
                .as("DECISIVE: the successful call's view reports its written structured result")
                .contains(Map.of("status", "ok"));
        assertThat(completions.get(1).toolInput())
                .as("DECISIVE: a completed tool-error result still reports its prepared arguments")
                .isPresent();
        assertThat(completions.get(1).toolOutput())
                .as("DECISIVE: a completed tool-error result is still a written tool output")
                .isPresent();
        assertThat(completions.get(1).structuredOutput())
                .as("a tool-error result carries no structured content")
                .isEmpty();
        assertThat(completions.get(2).toolInput())
                .as("DECISIVE: the rejected call never reached a prepared invocation")
                .isEmpty();
        assertThat(completions.get(2).toolOutput())
                .as("DECISIVE: the rejected call wrote no tool output")
                .isEmpty();
        assertThat(completions.get(2).requestBody()).contains(UNKNOWN_TOOL_NAME);
        assertThat(completions.get(2).responseBody()).isEqualTo(rejectedResponse.bodyAsString());

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
