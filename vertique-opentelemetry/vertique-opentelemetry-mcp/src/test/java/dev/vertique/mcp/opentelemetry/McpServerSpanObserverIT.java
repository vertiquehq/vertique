// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.mcp.opentelemetry.McpServerSpanObserverITFixture.Started;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import jakarta.annotation.Nullable;
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

    // --- R39 fixture values: HTTP-header trace identity vs. body _meta trace identity ---

    /** The RFC-example trace/span id pair carried on the HTTP {@code traceparent} header. */
    private static final String HTTP_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static final String HTTP_SPAN_ID = "00f067aa0ba902b7";

    /** A distinct, valid W3C trace/span id pair carried in the request body's {@code _meta}. */
    private static final String BODY_TRACE_ID = "1234567890abcdef1234567890abcdef";

    private static final String BODY_SPAN_ID = "abcdef1234567890";

    private static final String HTTP_TRACEPARENT = "00-" + HTTP_TRACE_ID + "-" + HTTP_SPAN_ID + "-01";
    private static final String DISTINCT_BODY_TRACEPARENT = "00-" + BODY_TRACE_ID + "-" + BODY_SPAN_ID + "-01";

    /** {@link dev.vertique.mcp.interceptor.McpTraceContext}'s bound is 512 chars; this is well over it. */
    private static final String OVERSIZED_TRACESTATE = "vendor=" + "x".repeat(600);

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

    // --- R39: the body trace-context producer, resolved extraction key ---
    //
    // The frozen contract (protocol-and-server-configuration.md §4.9 tracing bullet) fixes only the
    // policy — "HTTP traceparent is the sole parent; a valid distinct body trace reference becomes an
    // OTel link" — never the exact body key. R39's own task file defers the key resolution to the
    // implementer, reading T022's malformed-data rows and this observer's expectations. Resolved here
    // (recorded in evidence/module.md by the producer slice that follows): the JSON-RPC request's
    // params._meta` carries plain, unprefixed `traceparent`/`tracestate` string keys, mirroring the W3C
    // HTTP header names verbatim — deliberately *not* namespaced under the reserved
    // `io.modelcontextprotocol/` prefix this same `_meta` object already uses for
    // `protocolVersion`/`clientCapabilities`, since W3C trace propagation is a Vertique-owned extension,
    // not an official MCP protocol field. `traceparent`'s value is the ordinary W3C wire format
    // (`00-<32 lowercase hex trace id>-<16 lowercase hex span id>-<2 hex flags>`); `tracestate` is the
    // ordinary W3C tracestate string, bounded by `McpTraceContext`'s own bounds constants.

    @Test
    @DisplayName("R39: a body _meta traceparent distinct from the HTTP header becomes exactly one span link "
            + "(RED today — producer missing)")
    void shouldAddOneLinkForABodyTraceReferenceDistinctFromTheHttpHeader() throws Exception {
        // Given: a port-0 MCP server, and one authenticated tool call whose HTTP traceparent header and
        // body `_meta.traceparent` carry two distinct, individually valid W3C trace references.
        started = McpServerSpanObserverITFixture.start();
        rawClient = started.vertx().createHttpClient();
        client = WebClient.wrap(rawClient);

        // When: execute the call and poll the exporter once the response resolves.
        HttpResponse<Buffer> response = await(callToolWithBodyTrace(HTTP_TRACEPARENT, DISTINCT_BODY_TRACEPARENT, null));
        InMemorySpanExporter exporter = started.exporter();
        await(pollUntilSpanPresent(started.vertx(), exporter, 60, 50));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(sseResult(response.bodyAsString()).getBoolean("isError")).isFalse();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans)
                .as("exactly one span exported — no MCP-created child span")
                .hasSize(1);
        SpanData serverSpan = spans.get(0);

        // Then (DECISIVE): the HTTP traceparent remains the sole parent — the server span's own trace
        // id and parent span id both come from the HTTP header, never from the body.
        assertThat(serverSpan.getTraceId())
                .as("the server span's own trace id is the HTTP header's trace id")
                .isEqualTo(HTTP_TRACE_ID);
        assertThat(serverSpan.getParentSpanContext().getSpanId())
                .as("DECISIVE: HTTP traceparent is the sole parent")
                .isEqualTo(HTTP_SPAN_ID);

        // Then (DECISIVE): the valid, distinct body trace reference becomes exactly one link whose
        // SpanContext equals the body reference.
        List<LinkData> links = serverSpan.getLinks();
        assertThat(links)
                .as("DECISIVE: a valid distinct body trace reference produces exactly one link")
                .hasSize(1);
        SpanContext linkContext = links.get(0).getSpanContext();
        assertThat(linkContext.getTraceId()).isEqualTo(BODY_TRACE_ID);
        assertThat(linkContext.getSpanId()).isEqualTo(BODY_SPAN_ID);
    }

    @Test
    @DisplayName("R39 control: a body _meta traceparent identical to the HTTP header adds no link (green "
            + "both before and after the producer fix)")
    void shouldAddNoLinkForABodyTraceReferenceIdenticalToTheHttpHeader() throws Exception {
        // Given: the body `_meta.traceparent` carries the exact same trace/span id pair as the HTTP
        // traceparent header — a self-reference, not a genuine cross-boundary correlation.
        started = McpServerSpanObserverITFixture.start();
        rawClient = started.vertx().createHttpClient();
        client = WebClient.wrap(rawClient);

        HttpResponse<Buffer> response = await(callToolWithBodyTrace(HTTP_TRACEPARENT, HTTP_TRACEPARENT, null));
        InMemorySpanExporter exporter = started.exporter();
        await(pollUntilSpanPresent(started.vertx(), exporter, 60, 50));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(sseResult(response.bodyAsString()).getBoolean("isError")).isFalse();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getLinks())
                .as("DECISIVE: a body trace reference identical to the HTTP context is a self-reference — "
                        + "no link, today and after the fix")
                .isEmpty();
    }

    @Test
    @DisplayName("R39 bounds: malformed body traceparent syntax never links and never fails the request "
            + "(trivially green today — the real bounds proof starts once the producer extracts it)")
    void shouldSucceedWithNoLinkForMalformedBodyTraceparentSyntax() throws Exception {
        // Given: a body `_meta.traceparent` that is not well-formed W3C traceparent syntax at all.
        started = McpServerSpanObserverITFixture.start();
        rawClient = started.vertx().createHttpClient();
        client = WebClient.wrap(rawClient);

        HttpResponse<Buffer> response = await(callToolWithBodyTrace(HTTP_TRACEPARENT, "not-a-valid-traceparent", null));
        InMemorySpanExporter exporter = started.exporter();
        await(pollUntilSpanPresent(started.vertx(), exporter, 60, 50));

        // Then: the request still succeeds and no link is added. This fixture wires no log-capturing
        // test appender, so the bounded WARN diagnostic T022 specifies is not independently observable
        // here — request success plus zero links is the decisive, fixture-observable proof.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(sseResult(response.bodyAsString()).getBoolean("isError")).isFalse();
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getLinks())
                .as("malformed body traceparent syntax must never add a link")
                .isEmpty();
    }

    @Test
    @DisplayName("R39 bounds: an oversized body tracestate beyond McpTraceContext's cap never links and never "
            + "fails the request (trivially green today — the real bounds proof starts once the "
            + "producer extracts it)")
    void shouldSucceedWithNoLinkForOversizedBodyTracestate() throws Exception {
        // Given: a structurally valid, distinct body traceparent, but a tracestate longer than
        // McpTraceContext's 512-character bound.
        started = McpServerSpanObserverITFixture.start();
        rawClient = started.vertx().createHttpClient();
        client = WebClient.wrap(rawClient);

        HttpResponse<Buffer> response =
                await(callToolWithBodyTrace(HTTP_TRACEPARENT, DISTINCT_BODY_TRACEPARENT, OVERSIZED_TRACESTATE));
        InMemorySpanExporter exporter = started.exporter();
        await(pollUntilSpanPresent(started.vertx(), exporter, 60, 50));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(sseResult(response.bodyAsString()).getBoolean("isError")).isFalse();
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getLinks())
                .as("an oversized tracestate must be rejected at McpTraceContext's cap without a link "
                        + "or a request failure")
                .isEmpty();
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
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", McpServerSpanObserverITFixture.TOOL_NAME)
                .putHeader("traceparent", traceparent)
                .putHeader("Authorization", McpServerSpanObserverITFixture.VALID_BEARER)
                .sendBuffer(body.toBuffer());
    }

    /**
     * Sends one {@code tools/call} whose HTTP {@code traceparent} header is {@code httpTraceparent},
     * and whose body {@code params._meta} optionally carries {@code traceparent}/{@code tracestate}
     * values distinct from the resolved {@code io.modelcontextprotocol/} reserved keys (R39's resolved
     * extraction shape — see the block comment above the R39 test methods).
     *
     * @param httpTraceparent the HTTP {@code traceparent} header value; never {@code null}
     * @param bodyTraceparent the body {@code _meta.traceparent} value, or {@code null} to omit it
     * @param bodyTracestate the body {@code _meta.tracestate} value, or {@code null} to omit it
     */
    private Future<HttpResponse<Buffer>> callToolWithBodyTrace(
            String httpTraceparent, @Nullable String bodyTraceparent, @Nullable String bodyTracestate) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        if (bodyTraceparent != null) {
            meta.put("traceparent", bodyTraceparent);
        }
        if (bodyTracestate != null) {
            meta.put("tracestate", bodyTracestate);
        }
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
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", McpServerSpanObserverITFixture.TOOL_NAME)
                .putHeader("traceparent", httpTraceparent)
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
