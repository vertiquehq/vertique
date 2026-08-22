// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpToolOutputObservation;
import dev.vertique.mcp.server.McpOutputPipelineITFixture.CapableObserver;
import dev.vertique.mcp.server.McpOutputPipelineITFixture.InvalidOutputToolInvoker;
import dev.vertique.mcp.server.McpOutputPipelineITFixture.MetricsShapedObserver;
import dev.vertique.mcp.server.McpOutputPipelineITFixture.OversizedResultToolInvoker;
import dev.vertique.mcp.server.McpOutputPipelineITFixture.Started;
import dev.vertique.mcp.server.McpOutputPipelineITFixture.StructuredResultToolInvoker;
import dev.vertique.mcp.server.McpRequestDispatcher.CappedOutputStream;
import dev.vertique.mcp.server.McpRequestDispatcher.OutputCapExceededException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T020 TP-001 — the bounded output pipeline contract matrix: a complete result is normalized exactly
 * once, bounded at {@code mcp.output.maxBytes} as bytes are produced, validated against the tool's
 * advertised output schema before exposure, offered to the opt-in output observation only once
 * validation passes, and only then handed to the single terminal writer.
 *
 * <p>Modeled on {@code McpInputLifecycleObservationIT}'s and {@code McpToolResultTest}'s harnesses:
 * bind and connect explicitly to {@code 127.0.0.1} (never the default host or {@code "localhost"}),
 * dispatch every named matrix row from one parameterized test method (T019's established shape), and
 * close every owned resource on every teardown path. Each row starts its own isolated server and
 * fixture tools — {@code mcp.output.maxBytes=1024} throughout — so one row's call can never leak state
 * into another's decisive counters. Framework wiring lives behind {@link McpOutputPipelineITFixture};
 * only the Given values, the one action per row, and the decisive assertions live here.
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
    private static final String OBSERVATION_ROW = "shouldEmitTheOutputObservationOnlyToCapableSessions";

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

    private static Stream<String> t020ContractRows() {
        return Stream.of(NORMALIZE_ONCE_ROW, SCHEMA_INVALID_ROW, OVER_CAP_ROW, OBSERVATION_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t020ContractRows")
    @DisplayName("enforces the T020 contract matrix")
    void shouldEnforceT020ContractMatrix(String row) throws Exception {
        switch (row) {
            case NORMALIZE_ONCE_ROW -> shouldNormalizeAndValidateAStructuredResultOnce();
            case SCHEMA_INVALID_ROW -> shouldFailSchemaInvalidOutputBeforeExposure();
            case OVER_CAP_ROW -> shouldAbortAnOverCapResultAsBytesAreProduced();
            case OBSERVATION_ROW -> shouldEmitTheOutputObservationOnlyToCapableSessions();
            default -> fail("unknown T020 contract matrix row: " + row);
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

    // --- Row 4: the output observation reaches only the capability-implementing session ---

    private void shouldEmitTheOutputObservationOnlyToCapableSessions() throws Exception {
        StructuredResultToolInvoker tool = new StructuredResultToolInvoker();
        MetricsShapedObserver metrics = new MetricsShapedObserver();
        CapableObserver capable = new CapableObserver();
        Started started = McpOutputPipelineITFixture.start(
                vertx,
                OUTPUT_MAX_BYTES,
                metrics,
                capable,
                tool,
                new InvalidOutputToolInvoker(),
                new OversizedResultToolInvoker());
        server = started.server();
        port = started.port();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        HttpResponse<Buffer> response = await(callTool(StructuredResultToolInvoker.TOOL_NAME, 4));
        assertThat(response.statusCode()).isEqualTo(200);

        // DECISIVE: exactly the capability-implementing session received onToolOutput, carrying the
        // bounded normalized value — never the plain metrics-shaped session.
        assertThat(capable.session().toolOutputCount())
                .as("the output-value callback counter must provably increment for the capable session")
                .isEqualTo(1);
        McpToolOutputObservation observed = capable.session().observedOutput();
        assertThat(observed).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> normalizedOutput = (Map<String, Object>) observed.normalizedOutput();
        assertThat(normalizedOutput.get("name")).isEqualTo("Ada");
        assertThat(metrics.session().terminalCount()).isEqualTo(1);
        assertThat(metrics.session().completedCount()).isEqualTo(1);
        assertThat(capable.session().terminalCount())
                .as("the capable session must still receive the ordinary terminal callback")
                .isEqualTo(1);
        assertThat(capable.session().completedCount())
                .as("the capable session must still receive the ordinary completion callback")
                .isEqualTo(1);

        // DECISIVE — structural, not behavioral: a plain McpRequestObservation has no method on its
        // own type through which an output-value reference could ever reach it.
        for (Method method : McpRequestObservation.class.getDeclaredMethods()) {
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertThat(parameterType)
                        .as("DECISIVE: no McpRequestObservation callback may accept an output-observation type")
                        .isNotEqualTo(McpToolOutputObservation.class);
            }
        }
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
                new CapableObserver(),
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
