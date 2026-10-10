// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.mcp.lifecycle.McpTransportOutcome;
import dev.vertique.mcp.server.McpOutputPipelineITFixture.CountingOversizedResultToolInvoker;
import dev.vertique.mcp.server.McpOutputPipelineITFixture.InvalidOutputToolInvoker;
import dev.vertique.mcp.server.McpOutputPipelineITFixture.MetricsShapedObserver;
import dev.vertique.mcp.server.McpOutputPipelineITFixture.NearCapResultToolInvoker;
import dev.vertique.mcp.server.McpOutputPipelineITFixture.OversizedResultToolInvoker;
import dev.vertique.mcp.server.McpOutputPipelineITFixture.Started;
import dev.vertique.mcp.server.McpOutputPipelineITFixture.StructuredResultToolInvoker;
import dev.vertique.mcp.server.McpRecordingCompletedListener.Completion;
import dev.vertique.mcp.server.McpRequestDispatcher.CappedOutputStream;
import dev.vertique.mcp.server.McpRequestDispatcher.OutputCapExceededException;
import dev.vertique.mcp.tool.McpToolInvoker;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The bounded output pipeline contract matrix: a complete result is normalized exactly once, bounded
 * at {@code mcp.output.maxBytes} as bytes are produced, validated against the tool's advertised output
 * schema before exposure, handed to the single terminal writer, and reported through the request
 * view's tool output only when that write carried the result itself.
 *
 * <p>Also carries {@link #shouldBoundNormalizationAndReportNoOutputForARejectedResult()}: normalization
 * itself is bounded as bytes are produced, and the view reports no tool output for a result the
 * terminal envelope's cap rejected, even when the value on its own fits under the cap.
 *
 * <p>Modeled on {@code McpInputLifecycleObservationIT}'s and {@code McpToolResultTest}'s harnesses:
 * bind and connect explicitly to {@code 127.0.0.1} (never the default host or {@code "localhost"}),
 * dispatch every named matrix row from one parameterized test method, and close every owned resource
 * on every teardown path. Each row starts its own isolated server and fixture tools — {@code
 * mcp.output.maxBytes=1024} throughout — so one row's call can never leak state into another's
 * decisive counters. Framework wiring lives behind {@link McpOutputPipelineITFixture}; only the Given
 * values, the one action per row, and the decisive assertions live here.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpOutputPipelineIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SSE_PREFIX = "event: message\ndata: ";
    private static final int OUTPUT_MAX_BYTES = 1_024;

    private static final String NORMALIZE_ONCE_ROW = "shouldNormalizeAndValidateAStructuredResultOnce";
    private static final String SCHEMA_INVALID_ROW = "shouldFailSchemaInvalidOutputBeforeExposure";
    private static final String OVER_CAP_ROW = "shouldAbortAnOverCapResultAsBytesAreProduced";
    private static final String OBSERVATION_ROW = "shouldReportTheOutputThroughTheRequestView";

    private final Vertx vertx = Vertx.vertx();

    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;
    private int port;

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
        rawClient = null;
        client = null;
        port = 0;
    }

    private static Stream<String> contractRows() {
        return Stream.of(NORMALIZE_ONCE_ROW, SCHEMA_INVALID_ROW, OVER_CAP_ROW, OBSERVATION_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contractRows")
    @DisplayName("enforces the output pipeline contract matrix")
    void shouldEnforceTheContractMatrix(String row) throws Exception {
        switch (row) {
            case NORMALIZE_ONCE_ROW -> shouldNormalizeAndValidateAStructuredResultOnce();
            case SCHEMA_INVALID_ROW -> shouldFailSchemaInvalidOutputBeforeExposure();
            case OVER_CAP_ROW -> shouldAbortAnOverCapResultAsBytesAreProduced();
            case OBSERVATION_ROW -> shouldReportTheOutputThroughTheRequestView();
            default -> fail("unknown contract matrix row: " + row);
        }
    }

    // --- Row 1: a structured result is normalized exactly once and validated before exposure ---

    private void shouldNormalizeAndValidateAStructuredResultOnce() throws Exception {
        StructuredResultToolInvoker tool = new StructuredResultToolInvoker();
        startServer(tool, new InvalidOutputToolInvoker(), new OversizedResultToolInvoker());

        HttpResponse<Buffer> response = await(callTool(StructuredResultToolInvoker.TOOL_NAME, 1));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonObject result = sseResult(response.bodyAsString());
        assertThat(result.getBoolean("isError")).isFalse();
        assertThat(result.getJsonObject("structuredContent").getString("name"))
                .as("the normalized structured content must reach the wire")
                .isEqualTo("Ada");

        // DECISIVE: the raw application value was converted to its normalized shape exactly once. A
        // count of 0 would mean it never reached the wire (this proof would be vacuous); a count of 2
        // or more would mean the server re-serialized the original application object a second time
        // (e.g. once for validation and again for the wire embed) instead of reusing one normalized
        // value throughout.
        assertThat(tool.normalizeInvocationCount())
                .as("DECISIVE: the raw structured value must be normalized exactly once, not zero or "
                        + "more than once")
                .isEqualTo(1);
    }

    // --- Row 2: a schema-invalid structured result never reaches the wire ---

    private void shouldFailSchemaInvalidOutputBeforeExposure() throws Exception {
        InvalidOutputToolInvoker tool = new InvalidOutputToolInvoker();
        startServer(new StructuredResultToolInvoker(), tool, new OversizedResultToolInvoker());

        HttpResponse<Buffer> response = await(callTool(InvalidOutputToolInvoker.TOOL_NAME, 2));

        assertThat(response.statusCode())
                .as("a schema-invalid structured result must settle as a bounded server-side failure")
                .isEqualTo(500);
        String rawBody = response.bodyAsString();
        // DECISIVE: observed on the raw wire bytes, not on a parsed structuredContent==null — the
        // invalid value's own marker must never appear anywhere in the response, proving it was
        // rejected before exposure rather than merely omitted from one field after being written.
        assertThat(rawBody)
                .as("DECISIVE: the schema-invalid value must never reach the wire in any form")
                .doesNotContain(InvalidOutputToolInvoker.LEAKED_SENTINEL)
                .doesNotContain("\"other\"");
        JsonObject body = sseError(rawBody);
        assertThat(body.getInteger("code")).isEqualTo(-32603);
        assertThat(body.containsKey("data")).isFalse();
    }

    // --- Row 3: an over-cap structured result aborts as bytes are produced ---

    private void shouldAbortAnOverCapResultAsBytesAreProduced() throws Exception {
        startServer(
                new StructuredResultToolInvoker(), new InvalidOutputToolInvoker(), new OversizedResultToolInvoker());

        HttpResponse<Buffer> response = await(callTool(OversizedResultToolInvoker.TOOL_NAME, 3));

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body().length())
                .as("the degraded response must respect the hard output cap")
                .isLessThanOrEqualTo(OUTPUT_MAX_BYTES);
        String rawBody = response.bodyAsString();
        assertThat(rawBody)
                .as("no oversized element may reach the wire, even partially")
                .doesNotContain(OversizedResultToolInvoker.SENTINEL_PREFIX);

        // DECISIVE ordering proof. The live HTTP outcome above (bounded, no leaked element) cannot by
        // itself distinguish a streaming abort from an implementation that first materializes the full
        // byte array and then rejects it on length — both produce the same bounded external response.
        // This directly exercises the exact production McpRequestDispatcher.CappedOutputStream (used
        // unmodified by this task's new structured-content path — see encodeCapped) against a document
        // byte-identical in shape to what the dispatcher builds for this exact oversized tool result,
        // proving its internal buffer never reaches the full document size before it throws.
        assertCappedStreamAbortsBeforeMaterializing(OUTPUT_MAX_BYTES);
    }

    /**
     * Feeds a {@code toolCallResponse}-shaped document — the same {@code content}/{@code
     * structuredContent}/{@code isError} shape the dispatcher builds, carrying the same oversized
     * element set {@link OversizedResultToolInvoker} returns — directly to the real, unmodified {@link
     * CappedOutputStream}, and proves its buffer never grows to the document's true, uncapped size
     * before it throws {@link OutputCapExceededException}.
     */
    private static void assertCappedStreamAbortsBeforeMaterializing(int cap) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode elements = mapper.createArrayNode();
        for (int i = 0; i < OversizedResultToolInvoker.ELEMENT_COUNT; i++) {
            elements.add(OversizedResultToolInvoker.elementAt(i));
        }
        ObjectNode toolResult = mapper.createObjectNode();
        toolResult.set("content", mapper.createArrayNode());
        toolResult.put("isError", false);
        toolResult.set("structuredContent", elements);
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("result", toolResult);
        response.put("id", 3);

        byte[] fullBytes = mapper.writeValueAsBytes(response);
        assertThat(fullBytes.length)
                .as("the fixture document must genuinely exceed the cap for this proof to be non-vacuous")
                .isGreaterThan(cap);

        CappedOutputStream capped = new CappedOutputStream(cap);
        assertThatExceptionOfType(OutputCapExceededException.class)
                .isThrownBy(() -> mapper.writeValue(capped, response));
        assertThat(capped.toByteArray().length)
                .as("DECISIVE: the capped stream's internal buffer must never reach the document's true "
                        + fullBytes.length + "-byte size before throwing — proving the abort happens while "
                        + "bytes are being produced, not after the full byte array was already materialized "
                        + "and then measured")
                .isLessThanOrEqualTo(cap)
                .isLessThan(fullBytes.length);
    }

    // --- Row 4: the request view reports the normalized output only for the write that carried it ---

    private void shouldReportTheOutputThroughTheRequestView() throws Exception {
        StructuredResultToolInvoker tool = new StructuredResultToolInvoker();
        MetricsShapedObserver metrics = new MetricsShapedObserver();
        McpRecordingCompletedListener listener = new McpRecordingCompletedListener();
        Started started = McpOutputPipelineITFixture.start(
                vertx,
                OUTPUT_MAX_BYTES,
                metrics,
                listener,
                tool,
                new InvalidOutputToolInvoker(),
                new OversizedResultToolInvoker());
        server = started.server();
        port = started.port();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        HttpResponse<Buffer> response = await(callTool(StructuredResultToolInvoker.TOOL_NAME, 4));
        assertThat(response.statusCode()).isEqualTo(200);

        // DECISIVE: the listener receives exactly one view, carrying the bounded normalized value the
        // client received. It completes after transport completion, so it is awaited.
        List<Completion> completions = listener.await(1);
        assertThat(completions)
                .as("the completion listener must provably receive exactly one view")
                .hasSize(1);
        Completion completion = completions.get(0);
        assertThat(completion.toolOutput())
                .as("the output must be reported when the result's own write won settlement")
                .isPresent();
        @SuppressWarnings("unchecked")
        Map<String, Object> normalizedOutput =
                (Map<String, Object>) completion.structuredOutput().orElseThrow();
        assertThat(normalizedOutput.get("name")).isEqualTo("Ada");
        assertThat(completion.responseBody())
                .as("the view's response is the bytes the client received")
                .isEqualTo(response.bodyAsString())
                .contains("\"name\":\"Ada\"");
        assertThatThrownBy(() -> normalizedOutput.put("x", "y"))
                .as("the reported output must be read-only")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(completion.event().transportOutcome()).isEqualTo(McpTransportOutcome.WRITTEN);
        assertThat(metrics.session().terminalCount()).isEqualTo(1);
        assertThat(metrics.session().completedCount()).isEqualTo(1);
    }

    // --- Normalization is bounded as bytes are produced, and the view never reports a value the
    // wire cap rejects ---

    /**
     * An application result whose own canonical JSON size exceeds {@code mcp.output.maxBytes} must
     * abort <em>during normalization</em>, before a full tree is ever retained, and must never reach
     * the view's tool output. Asserting only "the call returns 500" cannot distinguish this from an
     * implementation that normalized unbounded to completion and only the final terminal-message
     * encode rejected it. {@link CountingOversizedResultToolInvoker#accessCount()} is the decisive,
     * non-behavioral proxy: it counts exactly how many of the result's {@code ELEMENT_COUNT} elements
     * serialization actually visited before the capped sink aborted.
     *
     * <p>A second, deliberately distinct scenario ({@link NearCapResultToolInvoker}) isolates the
     * visibility rule from normalization: its structured value is under cap <em>on its own</em>, so
     * normalization and schema validation both succeed — only the fully-enveloped terminal message
     * exceeds the cap. This is the only shape that can decisively prove that the output is withheld
     * when a bounded error replaced it on the wire: for the first scenario, bounded normalization alone
     * already prevents the result from ever being reported, so it cannot distinguish "visibility
     * regressed" from "normalization stayed bounded".
     */
    @Test
    @DisplayName("bounds normalization as bytes are produced and reports no output for a rejected result")
    void shouldBoundNormalizationAndReportNoOutputForARejectedResult() throws Exception {
        CountingOversizedResultToolInvoker oversizedTool = new CountingOversizedResultToolInvoker();
        McpRecordingCompletedListener listener = new McpRecordingCompletedListener();
        Started started = McpOutputPipelineITFixture.start(
                vertx,
                OUTPUT_MAX_BYTES,
                new MetricsShapedObserver(),
                listener,
                Set.<McpToolInvoker>of(
                        new StructuredResultToolInvoker(),
                        new InvalidOutputToolInvoker(),
                        new OversizedResultToolInvoker(),
                        oversizedTool,
                        new NearCapResultToolInvoker()));
        server = started.server();
        port = started.port();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        HttpResponse<Buffer> response = await(callTool(CountingOversizedResultToolInvoker.TOOL_NAME, 5));

        assertThat(response.statusCode())
                .as("an over-cap structured result must settle as a bounded server-side failure")
                .isEqualTo(500);
        assertThat(response.body().length())
                .as("the degraded response must respect the hard output cap")
                .isLessThanOrEqualTo(OUTPUT_MAX_BYTES);

        // DECISIVE: normalization must abort while still producing bytes, not after visiting the full
        // oversized value. A count of 0 would mean the fixture never actually ran (vacuous); a count
        // anywhere near CountingOversizedResultToolInvoker.ELEMENT_COUNT (5,000) would mean nearly every
        // element was consumed before any cap check ran — an unbounded convertValue tree build.
        // Bounding well under a tenth of ELEMENT_COUNT (not merely "less than ELEMENT_COUNT") is what
        // distinguishes a genuine as-bytes-are-produced abort from one that happens to stop one element
        // short of the end.
        assertThat(oversizedTool.accessCount())
                .as("DECISIVE: normalization must abort near the cap, not after visiting most of the "
                        + "oversized value")
                .isPositive()
                .isLessThan(CountingOversizedResultToolInvoker.ELEMENT_COUNT / 10);

        // DECISIVE: an over-cap result must never be reported as the tool output, and the view's
        // response is the bounded error the client received. (This assertion alone cannot isolate the
        // visibility rule — see the near-cap scenario below.)
        List<Completion> afterOverCap = listener.await(1);
        assertThat(afterOverCap.get(0).toolOutput())
                .as("DECISIVE: an over-cap result must never be reported as the tool output")
                .isEmpty();
        assertThat(afterOverCap.get(0).responseBody())
                .as("the view's response is the bounded error the client received")
                .isEqualTo(response.bodyAsString());

        // --- Second scenario: under cap alone, over cap once enveloped — isolates the visibility
        // rule from bounded normalization ---
        HttpResponse<Buffer> nearCapResponse = await(callTool(NearCapResultToolInvoker.TOOL_NAME, 6));

        assertThat(nearCapResponse.statusCode())
                .as("a result that only exceeds the cap once enveloped must still settle as a bounded "
                        + "server-side failure")
                .isEqualTo(500);
        assertThat(nearCapResponse.body().length())
                .as("the degraded response must respect the hard output cap")
                .isLessThanOrEqualTo(OUTPUT_MAX_BYTES);

        // DECISIVE: normalization succeeded for this value (it is under cap on its own — only the fully
        // enveloped message is over cap), so this assertion isolates the visibility rule: a bounded
        // error replaced the result on the wire, so the view must not report the value as the tool
        // output, without touching bounded normalization at all.
        List<Completion> afterNearCap = listener.await(2);
        assertThat(afterNearCap.get(1).toolOutput())
                .as("DECISIVE: a value that only fails the enveloped-message cap must never be reported "
                        + "as the tool output, isolating the visibility rule from normalization "
                        + "boundedness")
                .isEmpty();
        assertThat(afterNearCap.get(1).responseBody())
                .as("the view's response is the bounded error the client received")
                .isEqualTo(nearCapResponse.bodyAsString());
        assertThat(afterNearCap.get(1).toolInput())
                .as("the call itself was prepared, so its arguments are still reported")
                .isPresent();
    }

    // --- Shared framework construction ---

    private void startServer(
            StructuredResultToolInvoker structuredTool,
            InvalidOutputToolInvoker invalidTool,
            OversizedResultToolInvoker oversizedTool)
            throws Exception {
        Started started = McpOutputPipelineITFixture.start(
                vertx,
                OUTPUT_MAX_BYTES,
                new MetricsShapedObserver(),
                new McpRecordingCompletedListener(),
                structuredTool,
                invalidTool,
                oversizedTool);
        server = started.server();
        port = started.port();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
    }

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

    private static JsonObject sseError(String rawBody) {
        assertThat(rawBody).startsWith(SSE_PREFIX);
        JsonObject decoded =
                new JsonObject(rawBody.substring(SSE_PREFIX.length()).stripTrailing());
        JsonObject error = decoded.getJsonObject("error");
        assertThat(error).isNotNull();
        assertThat(decoded.containsKey("result")).isFalse();
        return error;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
