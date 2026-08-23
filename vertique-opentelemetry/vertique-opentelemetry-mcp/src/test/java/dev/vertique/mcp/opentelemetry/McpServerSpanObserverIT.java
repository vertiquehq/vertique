// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.mcp.opentelemetry.McpServerSpanObserverITFixture.Started;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
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
 * T022 TP-002 — proves the observer against a real Vert.x HTTP server with the Vert.x OpenTelemetry
 * tracing provider installed: exactly one server span is exported for one authenticated {@code
 * tools/call} carrying a W3C {@code traceparent}, it carries the bounded MCP attributes, and no
 * MCP-created child span exists.
 *
 * <p>Modeled on {@code RestServerSpanEnrichmentIT}'s harness (bind and connect explicitly to {@code
 * 127.0.0.1}, poll the in-memory exporter, close every owned resource on every teardown path).
 * Framework wiring lives behind {@link McpServerSpanObserverITFixture}; only the Given values, the
 * one action, and the decisive assertions live here.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpServerSpanObserverIT {

    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SSE_PREFIX = "event: message\ndata: ";

    private Started started;
    private HttpClient rawClient;
    private WebClient client;

    @AfterEach
    void tearDown() throws Exception {
        Future<Void> serverClose =
                started != null && started.server() != null ? started.server().close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose)
                .onComplete(ignored -> {
                    if (started != null && started.sdk() != null) {
                        started.sdk().close();
                    }
                    GlobalOpenTelemetry.resetForTest();
                    Vertx vertx = started != null ? started.vertx() : null;
                    if (vertx != null) {
                        vertx.close();
                    }
                })
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        started = null;
        rawClient = null;
        client = null;
    }

    @Test
    @DisplayName("one authenticated tool call carrying traceparent exports exactly one enriched server span")
    void shouldEnrichTheRealVertxServerSpan() throws Exception {
        // Given: a port-0 MCP server with the Vert.x OTel tracing provider installed and an in-memory
        // exporter, and one authenticated tool call carrying a valid, sampled W3C traceparent.
        started = McpServerSpanObserverITFixture.start();
        rawClient = started.vertx().createHttpClient();
        client = WebClient.wrap(rawClient);

        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-"
                + TraceFlags.getSampled().asHex();

        // When: execute one tools/call request and poll the exporter once the response resolves.
        HttpResponse<Buffer> response = await(callTool(traceparent));
        InMemorySpanExporter exporter = started.exporter();
        await(pollUntilSpanPresent(started.vertx(), exporter, 60, 50));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonObject result = sseResult(response.bodyAsString());
        assertThat(result.getBoolean("isError")).isFalse();

        // Then (DECISIVE): exactly one span is exported — proving no MCP-created child span exists,
        // not merely that no non-SERVER-kind span exists.
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans)
                .as("DECISIVE: exactly one span exported — no MCP-created child span")
                .hasSize(1);

        SpanData serverSpan = spans.get(0);
        assertThat(serverSpan.getKind()).isEqualTo(SpanKind.SERVER);

        // Then (DECISIVE): the exported server span carries every bounded MCP attribute — the count
        // below is the sensitivity target: removing the observer must drop it to 0 while the exported
        // server-span count above stays 1.
        assertThat(mcpAttributeCount(serverSpan))
                .as("DECISIVE: the MCP attribute count on the exported server span")
                .isEqualTo(5);
        assertThat(serverSpan.getAttributes().get(McpServerSpanObserver.RPC_SYSTEM_NAME))
                .isEqualTo(McpServerSpanObserver.RPC_SYSTEM_JSONRPC);
        assertThat(serverSpan.getAttributes().get(McpServerSpanObserver.MCP_METHOD_NAME))
                .isEqualTo("TOOLS_CALL");
        assertThat(serverSpan.getAttributes().get(McpServerSpanObserver.VERTIQUE_MCP_OUTCOME))
                .isEqualTo("SUCCESS");
        assertThat(serverSpan.getAttributes().get(McpServerSpanObserver.VERTIQUE_MCP_RESULT_TYPE))
                .isEqualTo("COMPLETE");
        assertThat(serverSpan.getAttributes().get(McpServerSpanObserver.GEN_AI_TOOL_NAME))
                .isEqualTo(McpServerSpanObserverITFixture.TOOL_NAME);
    }

    /** Counts how many of the five bounded MCP attribute keys are present on {@code span}. */
    private static long mcpAttributeCount(SpanData span) {
        return java.util.stream.Stream.of(
                        McpServerSpanObserver.RPC_SYSTEM_NAME,
                        McpServerSpanObserver.MCP_METHOD_NAME,
                        McpServerSpanObserver.VERTIQUE_MCP_OUTCOME,
                        McpServerSpanObserver.VERTIQUE_MCP_RESULT_TYPE,
                        McpServerSpanObserver.GEN_AI_TOOL_NAME)
                .filter(key -> span.getAttributes().get(key) != null)
                .count();
    }

    // --- Wire helpers ---

    private Future<HttpResponse<Buffer>> callTool(String traceparent) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params = new JsonObject()
                .put("_meta", meta)
                .put("name", McpServerSpanObserverITFixture.TOOL_NAME)
                .put("arguments", new JsonObject());
        JsonObject body = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put("params", params);
        return client.post(
                        started.port(),
                        McpServerSpanObserverITFixture.LOOPBACK,
                        McpServerSpanObserverITFixture.REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("traceparent", traceparent)
                .putHeader("Authorization", McpServerSpanObserverITFixture.VALID_BEARER)
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

    private static Future<Void> pollUntilSpanPresent(
            Vertx vertx, InMemorySpanExporter exporter, int maxAttempts, long delayMs) {
        if (maxAttempts <= 0) {
            return Future.failedFuture(new AssertionError("Timed out waiting for a span in the exporter"));
        }
        if (!exporter.getFinishedSpanItems().isEmpty()) {
            return Future.succeededFuture();
        }
        return Future.<Void>future(p -> vertx.setTimer(delayMs, id -> p.complete()))
                .compose(v -> pollUntilSpanPresent(vertx, exporter, maxAttempts - 1, delayMs));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
