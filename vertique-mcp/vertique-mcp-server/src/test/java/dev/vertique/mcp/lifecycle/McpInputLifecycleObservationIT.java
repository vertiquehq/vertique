// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.mcp.server.McpInputLifecycleObservationITFixture;
import dev.vertique.mcp.server.McpRecordingCompletedListener;
import dev.vertique.mcp.server.McpRecordingCompletedListener.Completion;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A real port-0 server reports the bounded, normalized argument tree of a tool call to a completion
 * listener through the framework-owned {@link McpRequestView}, next to the raw request the caller
 * sent, and an ordinary metrics- or tracing-shaped observer is unaffected.
 *
 * <p>Modeled on {@code McpToolCallIT}'s and {@code McpInputPipelineIT}'s harness: bind and connect
 * explicitly to {@code 127.0.0.1} (never the default host or {@code "localhost"}), and close every
 * owned resource on every teardown path. Framework wiring lives behind {@link
 * McpInputLifecycleObservationITFixture}; only the Given values, the one action, and the decisive
 * assertions live here.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpInputLifecycleObservationIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SSE_PREFIX = "event: message\ndata: ";
    private static final String RAW_PADDED_NAME = "  Ada Lovelace  ";
    private static final String TRIMMED_NAME = "Ada Lovelace";

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
    @DisplayName("reports the bounded, normalized argument tree through the request view")
    void shouldReportTheNormalizedArgumentTreeThroughTheRequestView() throws Exception {
        // Given: a port-0 server with one metrics-shaped observer, one tracing-shaped observer, and one
        // completion listener that reads the request view, exposing a record-argument tool.
        McpInputLifecycleObservationITFixture.MetricsShapedObserver metrics =
                new McpInputLifecycleObservationITFixture.MetricsShapedObserver();
        McpInputLifecycleObservationITFixture.TracingShapedObserver tracing =
                new McpInputLifecycleObservationITFixture.TracingShapedObserver();
        McpRecordingCompletedListener listener = new McpRecordingCompletedListener();
        McpInputLifecycleObservationITFixture.FixtureToolInvoker tool =
                new McpInputLifecycleObservationITFixture.FixtureToolInvoker();
        McpInputLifecycleObservationITFixture.Started started =
                McpInputLifecycleObservationITFixture.start(vertx, metrics, tracing, listener, tool);
        server = started.server();
        port = started.port();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        // Given: raw argument values whose normalized form genuinely differs (whitespace-padded, as a
        // real generated invoker's canonicalization stage trims — see McpInputPipelineIT).
        JsonObject rawArguments = new JsonObject()
                .put("customer", new JsonObject().put("name", RAW_PADDED_NAME))
                .put("tags", new JsonArray(List.of("  vip  ", "returning")));

        // When: execute exactly one tool call.
        HttpResponse<Buffer> response = await(callTool(rawArguments, 1));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonObject result = sseResult(response.bodyAsString());
        assertThat(result.getBoolean("isError")).isFalse();
        assertThat(tool.invocationCount())
                .as("the tool call must genuinely have been invoked for this proof to be non-vacuous")
                .isEqualTo(1);

        // Then (DECISIVE — the completion arrived exactly once, and the plain observers are unaffected):
        // the listener completes after transport completion, so it is awaited rather than read at once.
        List<Completion> completions = listener.await(1);
        assertThat(completions)
                .as("the completion listener must provably receive exactly one view for one request")
                .hasSize(1);
        Completion completion = completions.get(0);
        assertThat(metrics.session().terminalCount()).isEqualTo(1);
        assertThat(metrics.session().completedCount()).isEqualTo(1);
        assertThat(tracing.session().terminalCount()).isEqualTo(1);
        assertThat(tracing.session().completedCount()).isEqualTo(1);
        assertThat(completion.event().transportOutcome()).isEqualTo(McpTransportOutcome.WRITTEN);

        // Then (DECISIVE — the view reports the prepared call): the invocation context carries the
        // resolved tool, and the raw request the caller sent is reported next to the normalized tree.
        assertThat(completion.toolContext())
                .as("a call that reached a prepared invocation must report its invocation context")
                .isPresent();
        assertThat(completion.toolContext().orElseThrow().tool().name())
                .isEqualTo(McpInputLifecycleObservationITFixture.TOOL_NAME);
        assertThat(completion.requestBody())
                .as("the view reports the raw request bytes, whitespace padding included")
                .contains(RAW_PADDED_NAME);

        // Then (DECISIVE — normalized differs genuinely from raw, and is exactly the tree prepare()
        // produced): the reported value is the trimmed form, not the raw whitespace-padded wire value
        // this test sent.
        assertThat(completion.toolInput())
                .as("DECISIVE: the view must report the normalized argument tree of a prepared call")
                .isPresent();
        Map<String, Object> normalizedArguments = completion.toolInput().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> customer = (Map<String, Object>) normalizedArguments.get("customer");
        assertThat(customer.get("name"))
                .as("DECISIVE: the reported value must be the normalized (trimmed) form, not the raw wire value")
                .isEqualTo(TRIMMED_NAME)
                .isNotEqualTo(RAW_PADDED_NAME);
        @SuppressWarnings("unchecked")
        List<Object> tags = (List<Object>) normalizedArguments.get("tags");
        assertThat(tags).containsExactly("vip", "returning");

        // Then (DECISIVE — unmodifiable at every level of the nested tree, not only the outermost map):
        // mutation is rejected at the top map, a nested map, and a nested list.
        assertThatThrownBy(() -> normalizedArguments.put("x", "y"))
                .as("the outermost map must reject mutation")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> customer.put("x", "y"))
                .as("a nested map must reject mutation")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tags.add("x"))
                .as("a nested list must reject mutation")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // --- Wire helpers ---

    private Future<HttpResponse<Buffer>> callTool(JsonObject arguments, Object id) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params = new JsonObject()
                .put("_meta", meta)
                .put("name", McpInputLifecycleObservationITFixture.TOOL_NAME)
                .put("arguments", arguments);
        JsonObject body = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", "tools/call")
                .put("params", params);
        return client.post(port, LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", McpInputLifecycleObservationITFixture.TOOL_NAME)
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
