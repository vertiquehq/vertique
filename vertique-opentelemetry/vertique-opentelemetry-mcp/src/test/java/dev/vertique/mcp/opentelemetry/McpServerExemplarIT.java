// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.mcp.opentelemetry.McpServerExemplarITFixture.Started;
import io.opentelemetry.api.GlobalOpenTelemetry;
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
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

/**
 * R06 (issue #435) TP-002 — production-path exemplar proof: proves that when a sampled OpenTelemetry
 * server span is current at MCP terminal settlement, and the OpenTelemetry MCP observer's completion
 * scope ({@link McpServerSpanObserver.Session#openCompletionScope()} via the new {@code
 * McpCompletionScope} capability) re-establishes it around the framework's real completion dispatch
 * (real {@code McpCompletionCoordinator}, real {@code McpServerMetricsObserver}), the resulting
 * Prometheus OpenMetrics scrape carries an exemplar with a {@code trace_id} matching the server span.
 *
 * <h3>Why this proves the fix, not just the plumbing</h3>
 *
 * <p>Vert.x 5's OTel tracer ends the HTTP server span before any completion callback runs — the same
 * situation {@code RestServerExemplarIT} documents for REST. Before R06, {@code
 * McpServerMetricsObserver}'s session recorded its timer with no span current, so the OpenMetrics
 * scrape carried no exemplar; the R06 completion evidence records this class failing exactly that way
 * when {@code McpCompletionCoordinator}'s scope bracketing is reverted. The exemplar bridge itself
 * ({@link io.prometheus.metrics.tracer.otel.OpenTelemetrySpanContext}) is registry-level, shared,
 * un-MCP-specific plumbing already proven for REST — this test's only job is proving the MCP-specific
 * half: that a sampled span is actually current when the timer records.
 *
 * <h3>Test setup</h3>
 *
 * <p>Framework wiring lives behind {@link McpServerExemplarITFixture}: a real port-0 MCP server
 * composed from {@code McpServerModule} + {@link McpOpenTelemetryModule} + {@code
 * dev.vertique.mcp.micrometer.McpMicrometerModule} over the real REST-security identity stack, plus a
 * {@link io.micrometer.prometheusmetrics.PrometheusMeterRegistry} wired with the exemplar bridge.
 * Repeated 5 times to verify determinism, mirroring {@code RestServerExemplarIT}.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpServerExemplarIT {

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
                .compose(ignored -> {
                    if (started != null && started.sdk() != null) {
                        started.sdk().close();
                    }
                    GlobalOpenTelemetry.resetForTest();
                    Vertx vertx = started != null ? started.vertx() : null;
                    return vertx != null ? vertx.close() : Future.<Void>succeededFuture();
                })
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        started = null;
        rawClient = null;
        client = null;
    }

    @RepeatedTest(5)
    @DisplayName("R06 TP-002: OpenMetrics scrape carries an exemplar with trace_id matching the "
            + "server span, recorded via the real completion-scope-bracketed pipeline")
    void productionPathExemplarAttachesViaCompletionScope() throws Exception {
        started = McpServerExemplarITFixture.start();
        rawClient = started.vertx().createHttpClient();
        client = WebClient.wrap(rawClient);

        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-"
                + TraceFlags.getSampled().asHex();

        HttpResponse<Buffer> response = await(callTool(traceparent));
        InMemorySpanExporter exporter = started.exporter();
        await(pollUntilSpanPresent(started.vertx(), exporter, 60, 50));
        // Extra wait for the completion dispatch (and this test's timer recording inside it) to run
        // after the response resolves — mirrors RestServerExemplarIT's own post-response wait.
        await(delay(started.vertx(), 100));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonObject result = sseResult(response.bodyAsString());
        assertThat(result.getBoolean("isError")).isFalse();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertFalse(spans.isEmpty(), "at least one span must be exported");
        String expectedTraceId = spans.get(0).getTraceId();

        String openMetricsBody =
                started.registry().scrape("application/openmetrics-text; version=1.0.0; charset=utf-8");

        assertTrue(
                openMetricsBody.contains("trace_id"),
                "DECISIVE: OpenMetrics body must contain 'trace_id' in an exemplar block; body:\n" + openMetricsBody);
        assertTrue(
                openMetricsBody.contains(expectedTraceId),
                "DECISIVE: OpenMetrics body must contain the server span's traceId '" + expectedTraceId + "'; body:\n"
                        + openMetricsBody);
        assertTrue(
                openMetricsBody.contains("# {"),
                "OpenMetrics body must contain an exemplar block '# {'; body:\n" + openMetricsBody);
    }

    // --- Wire helpers ---

    private Future<HttpResponse<Buffer>> callTool(String traceparent) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params = new JsonObject()
                .put("_meta", meta)
                .put("name", McpServerExemplarITFixture.TOOL_NAME)
                .put("arguments", new JsonObject());
        JsonObject body = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put("params", params);
        return client.post(started.port(), McpServerExemplarITFixture.LOOPBACK, McpServerExemplarITFixture.REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", McpServerExemplarITFixture.TOOL_NAME)
                .putHeader("traceparent", traceparent)
                .putHeader("Authorization", McpServerExemplarITFixture.VALID_BEARER)
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

    private static Future<Void> delay(Vertx vertx, long delayMs) {
        return Future.future(p -> vertx.setTimer(delayMs, id -> p.complete()));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
