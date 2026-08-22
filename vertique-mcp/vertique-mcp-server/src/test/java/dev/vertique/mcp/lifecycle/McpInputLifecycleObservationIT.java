// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.mcp.server.McpInputLifecycleObservationITFixture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T018 TP-001 — a real port-0 server delivers the bounded, normalized value tree only to a session
 * implementing the opt-in {@link McpToolValueObservation} capability, and never to an ordinary
 * metrics- or tracing-shaped session.
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
    @DisplayName("delivers the bounded, normalized value tree only to the capability-implementing session")
    void shouldDeliverBoundedValuesOnlyToCapableSessions() throws Exception {
        // Given: a port-0 server with one metrics-shaped observer, one tracing-shaped observer, and one
        // capability-implementing observer, exposing a record-argument tool.
        McpInputLifecycleObservationITFixture.MetricsShapedObserver metrics =
                new McpInputLifecycleObservationITFixture.MetricsShapedObserver();
        McpInputLifecycleObservationITFixture.TracingShapedObserver tracing =
                new McpInputLifecycleObservationITFixture.TracingShapedObserver();
        McpInputLifecycleObservationITFixture.CapableObserver capable =
                new McpInputLifecycleObservationITFixture.CapableObserver();
        McpInputLifecycleObservationITFixture.FixtureToolInvoker tool =
                new McpInputLifecycleObservationITFixture.FixtureToolInvoker();
        McpInputLifecycleObservationITFixture.Started started =
                McpInputLifecycleObservationITFixture.start(vertx, metrics, tracing, capable, tool);
        server = started.server();
        port = started.port();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        // Given: raw argument values whose normalized form genuinely differs (whitespace-padded, as a
        // real generated invoker's INP-001 canonicalization stage trims — see McpInputPipelineIT).
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

        // Then (DECISIVE — counter provably increments, and only for the capable session): exactly the
        // capability-implementing session received onToolInput.
        assertThat(capable.session().toolInputCount())
                .as("the value-callback counter must provably increment for the capable session")
                .isEqualTo(1);
        assertThat(metrics.session().terminalCount()).isEqualTo(1);
        assertThat(metrics.session().completedCount()).isEqualTo(1);
        assertThat(tracing.session().terminalCount()).isEqualTo(1);
        assertThat(tracing.session().completedCount()).isEqualTo(1);
        assertThat(capable.session().terminalCount())
                .as("the capable session must still receive the ordinary terminal callback")
                .isEqualTo(1);
        assertThat(capable.session().completedCount())
                .as("the capable session must still receive the ordinary completion callback")
                .isEqualTo(1);

        // Then (DECISIVE — structural, not behavioral): a plain McpRequestObservation has no method on
        // its own type through which a value reference could ever reach it — established by reflecting
        // over the interface's declared methods, not from the metrics/tracing sessions merely declining
        // to implement the capability.
        for (Method method : McpRequestObservation.class.getDeclaredMethods()) {
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertThat(parameterType)
                        .as("DECISIVE: no McpRequestObservation callback may accept a value-observation type")
                        .isNotEqualTo(McpToolInputObservation.class)
                        .isNotEqualTo(McpToolOutputObservation.class);
            }
            assertThat(method.getReturnType())
                    .as("DECISIVE: no McpRequestObservation callback may return a value-observation type")
                    .isNotEqualTo(McpToolInputObservation.class)
                    .isNotEqualTo(McpToolOutputObservation.class);
        }

        // Then (DECISIVE — normalized differs genuinely from raw, and is bounded to exactly the
        // schema/materialization-produced tree): the delivered value is the trimmed form, not the raw
        // whitespace-padded wire value this test sent.
        McpToolInputObservation observed = capable.session().observed();
        assertThat(observed).isNotNull();
        Map<String, Object> normalizedArguments = observed.normalizedArguments();
        @SuppressWarnings("unchecked")
        Map<String, Object> customer = (Map<String, Object>) normalizedArguments.get("customer");
        assertThat(customer.get("name"))
                .as("DECISIVE: the delivered value must be the normalized (trimmed) form, not the raw wire value")
                .isEqualTo(TRIMMED_NAME)
                .isNotEqualTo(RAW_PADDED_NAME);

        // Then (DECISIVE — unmodifiable at every level of the nested tree, not only the outermost map):
        // mutation is rejected at the top map, a nested map, and a nested list.
        assertThatThrownBy(() -> normalizedArguments.put("x", "y"))
                .as("the outermost map must reject mutation")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> customer.put("x", "y"))
                .as("a nested map must reject mutation")
                .isInstanceOf(UnsupportedOperationException.class);
        @SuppressWarnings("unchecked")
        List<Object> tags = (List<Object>) normalizedArguments.get("tags");
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
