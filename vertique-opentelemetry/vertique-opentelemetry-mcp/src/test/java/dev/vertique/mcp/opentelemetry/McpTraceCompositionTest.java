// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.opentelemetry.McpTraceCompositionTestFixture.Started;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T022 TP-003 — three Dagger graphs serving one identical tool call: omitting {@link
 * McpOpenTelemetryModule} entirely, installing it with a no-op {@link OpenTelemetry} instance and no
 * exporter configured, and installing it with a recording SDK-backed tracer. Framework wiring lives
 * behind {@link McpTraceCompositionTestFixture}; only the Given values, the one action per graph, and
 * the decisive assertions live here.
 *
 * <p>All three graphs run sequentially within one test method against the <em>same</em> shared {@link
 * InMemorySpanExporter}: the omitting and no-op graphs' Vertx instances are wired to tracers that
 * never touch it (no tracer at all, and a no-op {@link OpenTelemetry} instance respectively), so the
 * exporter can only ever receive spans from the third, recording graph — making "0 after the first two
 * graphs, 1 after the third" a single running count rather than three independent measurements.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpTraceCompositionTest {

    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SSE_PREFIX = "event: message\ndata: ";

    private final List<Vertx> ownedVertxInstances = new ArrayList<>();
    private final List<Started> ownedServers = new ArrayList<>();
    private final List<HttpClient> ownedClients = new ArrayList<>();
    private OpenTelemetrySdk sdk;

    @AfterEach
    void tearDown() throws Exception {
        List<Future<Void>> closes = new ArrayList<>();
        for (Started started : ownedServers) {
            closes.add(started.server().close());
        }
        for (HttpClient client : ownedClients) {
            closes.add(client.close());
        }
        Future.all(closes)
                .onComplete(ignored -> {
                    if (sdk != null) {
                        sdk.close();
                    }
                    GlobalOpenTelemetry.resetForTest();
                    for (Vertx vertx : ownedVertxInstances) {
                        vertx.close();
                    }
                })
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        ownedServers.clear();
        ownedClients.clear();
        ownedVertxInstances.clear();
        sdk = null;
    }

    @Test
    @DisplayName("omitting, no-op, and recording graphs compose correctly and degrade safely")
    void shouldComposeAndDegradeSafelyWithoutAnExporter() throws Exception {
        // Given: a shared in-memory exporter that only the recording graph's Vertx tracer is ever
        // wired to.
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();

        // Graph 1 — omitting: McpOpenTelemetryModule is absent from the component's module list.
        Vertx omittingVertx = Vertx.vertx();
        ownedVertxInstances.add(omittingVertx);
        Started omitting = McpTraceCompositionTestFixture.startOmitting(omittingVertx);
        ownedServers.add(omitting);
        HttpResponse<Buffer> omittingResponse = await(callTool(omittingVertx, omitting));

        // Then (structural + DECISIVE): the omitting graph contributes no observer at all. Structurally,
        // McpTraceCompositionTestFixture.OmittingComponent's own module list (see its javadoc) never
        // references McpOpenTelemetryModule or McpServerSpanObserver, so no MCP OTel type can reach it.
        assertThat(omitting.lifecycleObservers())
                .as("DECISIVE: the omitting graph contributes zero lifecycle observers")
                .isEmpty();

        // Graph 2 — no-op: McpOpenTelemetryModule installed, but the Vertx tracer is wired to the
        // OpenTelemetry API's own no-op instance, with no exporter configured for it at all.
        Vertx noOpVertx = McpTraceCompositionTestFixture.buildVertx(OpenTelemetry.noop());
        ownedVertxInstances.add(noOpVertx);
        Started noOp = McpTraceCompositionTestFixture.startWithObserver(noOpVertx);
        ownedServers.add(noOp);
        HttpResponse<Buffer> noOpResponse = await(callTool(noOpVertx, noOp));

        assertThat(noOp.lifecycleObservers())
                .as("DECISIVE: the no-op graph builds and contributes exactly one observer")
                .hasSize(1);
        assertThat(noOp.lifecycleObservers().iterator().next())
                .as("the contributed observer is the real McpServerSpanObserver, not a stand-in")
                .isInstanceOf(McpServerSpanObserver.class);
        assertThat(noOpResponse.statusCode()).isEqualTo(200);
        assertThat(sseResult(noOpResponse.bodyAsString()).getBoolean("isError")).isFalse();
        assertThat(exporter.getFinishedSpanItems())
                .as("DECISIVE (sensitivity target): the no-op graph must record nothing — 0 spans so far")
                .isEmpty();

        // Graph 3 — recording: McpOpenTelemetryModule installed, Vertx tracer wired to the real,
        // exporter-backed SDK.
        Vertx recordingVertx = McpTraceCompositionTestFixture.buildVertx(sdk);
        ownedVertxInstances.add(recordingVertx);
        Started recording = McpTraceCompositionTestFixture.startWithObserver(recordingVertx);
        ownedServers.add(recording);
        HttpResponse<Buffer> recordingResponse = await(callTool(recordingVertx, recording));
        await(pollUntilSpanPresent(recordingVertx, exporter, 60, 50));

        assertThat(recordingResponse.statusCode()).isEqualTo(200);
        assertThat(sseResult(recordingResponse.bodyAsString()).getBoolean("isError"))
                .isFalse();
        assertThat(exporter.getFinishedSpanItems())
                .as("DECISIVE (sensitivity target): the recording graph enriches exactly one span")
                .hasSize(1);

        // Then (DECISIVE): the protocol response is byte-identical across all three graphs.
        assertThat(omittingResponse.statusCode()).isEqualTo(noOpResponse.statusCode());
        assertThat(noOpResponse.statusCode()).isEqualTo(recordingResponse.statusCode());
        assertThat(omittingResponse.body().getBytes())
                .as("DECISIVE: the protocol response body is byte-identical across all three graphs")
                .isEqualTo(noOpResponse.body().getBytes())
                .isEqualTo(recordingResponse.body().getBytes());

        // Then (structural): no OpenTelemetry type appears on the MCP lifecycle SPI's own signatures —
        // vertique-mcp-core has no OpenTelemetry dependency at all, so this is an architectural
        // invariant this reflection check makes decisively observable rather than merely assumed.
        for (java.lang.reflect.Method method : McpRequestLifecycleObserver.class.getMethods()) {
            assertThat(method.getReturnType().getPackageName()).doesNotStartWith("io.opentelemetry");
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertThat(parameterType.getPackageName()).doesNotStartWith("io.opentelemetry");
            }
        }
    }

    // --- Wire helpers ---

    private Future<HttpResponse<Buffer>> callTool(Vertx vertx, Started started) {
        HttpClient rawClient = vertx.createHttpClient();
        ownedClients.add(rawClient);
        WebClient client = WebClient.wrap(rawClient);

        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params = new JsonObject()
                .put("_meta", meta)
                .put("name", McpTraceCompositionTestFixture.TOOL_NAME)
                .put("arguments", new JsonObject());
        JsonObject body = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put("params", params);
        return client.post(
                        started.port(),
                        McpTraceCompositionTestFixture.LOOPBACK,
                        McpTraceCompositionTestFixture.REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", McpTraceCompositionTestFixture.TOOL_NAME)
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
