// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.lifecycle.McpToolInputObservation;
import dev.vertique.mcp.lifecycle.McpToolOutputObservation;
import dev.vertique.mcp.lifecycle.McpToolValueObservation;
import dev.vertique.mcp.server.support.McpJsonTokenCorpus;
import dev.vertique.mcp.server.support.McpJsonTokenCorpus.Expected;
import dev.vertique.mcp.server.support.McpJsonTokenCorpus.Row;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** R19 proof that the output-normalization reparse consumes the configured token budget once. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class McpOutputTokenBudgetIT {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String TOOL_NAME = "output.token-budget.values";
    private static final int MIN_TOKENS = 1_024;
    private static final int DEFAULT_TOKENS = 65_536;
    private static final int MAX_TOKENS = 262_144;
    private static final int DEFAULT_OUTPUT_MAX_BYTES = 2_097_152;

    private final Vertx vertx = Vertx.vertx();

    private HttpServer server;
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
        rawClient = null;
        client = null;
    }

    @Test
    @DisplayName("R19: enforces configured output token boundaries without reserializing")
    void shouldEnforceConfiguredOutputTokenBoundariesWithoutReserializing() throws Exception {
        for (Scenario scenario : List.of(
                Scenario.boundary("minimum exact", "minimum-flat-array-exact"),
                Scenario.boundary("minimum plus one", "minimum-flat-array-plus-one"),
                Scenario.boundary("default exact", "default-flat-array-exact"),
                Scenario.boundary("default plus one", "default-flat-array-plus-one"),
                Scenario.boundary("maximum exact", "maximum-flat-array-exact"),
                Scenario.boundary("maximum plus one", "maximum-flat-array-plus-one"),
                Scenario.byteFailure("byte cap precedes token reparse", "minimum-flat-array-exact", 1_024),
                Scenario.ingressIndependentFailure(
                        "ingress budget does not alter output", "minimum-flat-array-plus-one", MAX_TOKENS))) {
            assertScenario(scenario);
        }
    }

    @Test
    @DisplayName("R19: preserves the canonical corpus numeric representation on the raw SSE wire")
    void shouldPreserveCanonicalCorpusNumericRepresentationOnWire() throws Exception {
        Row row = Scenario.outputRow("output-numeric-fidelity");
        String canonicalJson = new String(row.renderedUtf8(), StandardCharsets.UTF_8);
        Matcher expected = Pattern.compile("\\{\\\"precise\\\":([^,]+),\\\"large\\\":([^}]+)}")
                .matcher(canonicalJson);
        assertThat(expected.matches())
                .as("the canonical numeric corpus row has the pinned object shape")
                .isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build()
                .readValue(row.renderedUtf8(), Map.class);
        RecordingObserver observer = new RecordingObserver();
        Scenario scenario = new Scenario("numeric wire fidelity", row, DEFAULT_OUTPUT_MAX_BYTES, DEFAULT_TOKENS, true);
        Started started = startServer(scenario, new FixedResultTool(result), observer);
        server = started.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        HttpResponse<Buffer> response = await(client.post(started.port(), LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", TOOL_NAME)
                .sendJsonObject(callBody()));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.bodyAsString())
                .contains("\"precise\":" + expected.group(1))
                .contains("\"large\":" + expected.group(2))
                .doesNotContain("Infinity");
        assertThat(observer.outputCount()).isEqualTo(1);
        assertThat(observer.terminal().errorType()).isEqualTo(McpErrorType.NONE);
        closeStartedServer();
    }

    private void assertScenario(Scenario scenario) throws Exception {
        CountingResultTool tool = new CountingResultTool(scenario.scalarCount());
        RecordingObserver observer = new RecordingObserver();
        Started started = startServer(scenario, tool, observer);
        server = started.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        HttpResponse<Buffer> response = await(client.post(started.port(), LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", TOOL_NAME)
                .sendJsonObject(callBody()));

        assertThat(tool.serializationAttempts())
                .as("%s: the raw result must be serialized exactly once", scenario.name())
                .isEqualTo(1);
        assertThat(observer.terminalCount())
                .as("%s: the request must emit exactly one terminal event", scenario.name())
                .isEqualTo(1);
        assertThat(observer.completedCount())
                .as("%s: the request must emit exactly one completion event", scenario.name())
                .isEqualTo(1);

        if (scenario.succeeds()) {
            assertThat(response.statusCode()).as(scenario.name()).isEqualTo(200);
            assertThat(response.bodyAsString())
                    .as("%s: an accepted result must remain a complete success", scenario.name())
                    .contains("\"isError\":false");
            assertThat(observer.outputCount())
                    .as("%s: only a complete normalized value is observed", scenario.name())
                    .isEqualTo(1);
            assertThat(observer.terminal().errorType()).isEqualTo(McpErrorType.NONE);
        } else {
            assertThat(response.statusCode()).as(scenario.name()).isEqualTo(500);
            assertThat(response.bodyAsString())
                    .as(
                            "%s: failure must be the bounded internal wire response, never a partial success",
                            scenario.name())
                    .contains("\"code\":-32603")
                    .doesNotContain("\"structuredContent\"");
            assertThat(observer.outputCount())
                    .as("%s: a rejected normalized value must not be observed", scenario.name())
                    .isZero();
            assertThat(observer.terminal().errorType())
                    .as("%s: serialization-bound failure has one lifecycle classification", scenario.name())
                    .isEqualTo(McpErrorType.SERIALIZATION);
        }

        closeStartedServer();
    }

    private void closeStartedServer() throws Exception {
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose)
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        server = null;
        rawClient = null;
        client = null;
    }

    private Started startServer(Scenario scenario, McpToolInvoker tool, RecordingObserver observer) throws Exception {
        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .serverName("vertique-test")
                .serverVersion("1.0")
                .ingressMaxTokens(scenario.ingressMaxTokens())
                .outputMaxTokens(scenario.outputMaxTokens())
                .outputMaxBytes(scenario.outputMaxBytes())
                .build();
        McpOutputPipelineITFixture.Started started =
                McpOutputPipelineITFixture.start(vertx, config, Set.of(observer), Set.of(tool));
        return new Started(started.server(), started.port());
    }

    private static JsonObject callBody() {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put(
                        "params",
                        new JsonObject()
                                .put(
                                        "_meta",
                                        new JsonObject()
                                                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                                                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject()))
                                .put("name", TOOL_NAME)
                                .put("arguments", new JsonObject()));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private record Scenario(String name, Row row, int outputMaxBytes, int ingressMaxTokens, boolean succeeds) {
        static Scenario boundary(String name, String rowId) {
            Row row = outputRow(rowId);
            return new Scenario(name, row, DEFAULT_OUTPUT_MAX_BYTES, DEFAULT_TOKENS, row.expected() == Expected.ACCEPT);
        }

        static Scenario byteFailure(String name, String rowId, int outputMaxBytes) {
            return new Scenario(name, outputRow(rowId), outputMaxBytes, DEFAULT_TOKENS, false);
        }

        static Scenario ingressIndependentFailure(String name, String rowId, int ingressMaxTokens) {
            return new Scenario(name, outputRow(rowId), DEFAULT_OUTPUT_MAX_BYTES, ingressMaxTokens, false);
        }

        int outputMaxTokens() {
            return row.configuredBudget();
        }

        int scalarCount() {
            Object value = row.generator().parameters().get("elementCount");
            if (!row.generator().description().equals("flat-scalar-array") || !(value instanceof Number number)) {
                throw new IllegalStateException("R19 HTTP boundary row must use flat-scalar-array: " + row.id());
            }
            return number.intValue();
        }

        private static Row outputRow(String id) {
            return McpJsonTokenCorpus.outputNormalizationRows().stream()
                    .filter(row -> row.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Missing R19 output corpus row: " + id));
        }
    }

    private record Started(HttpServer server, int port) {}

    private static final class CountingResultTool implements McpToolInvoker {
        private final AtomicInteger serializationAttempts = new AtomicInteger();
        private final int scalarCount;
        private final McpToolDescriptor descriptor = new McpToolDescriptor(
                TOOL_NAME,
                null,
                "R19 configured output-token fixture.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\"}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));

        CountingResultTool(int scalarCount) {
            this.scalarCount = scalarCount;
        }

        int serializationAttempts() {
            return serializationAttempts.get();
        }

        @Override
        public McpToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return Map.of();
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    return Future.succeededFuture(
                            McpToolResult.structured(new CountingZeros(scalarCount, serializationAttempts)));
                }
            };
        }
    }

    private static final class FixedResultTool implements McpToolInvoker {
        private final Object result;
        private final McpToolDescriptor descriptor = new McpToolDescriptor(
                TOOL_NAME,
                null,
                "R19 numeric wire-fidelity fixture.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\"}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));

        FixedResultTool(Object result) {
            this.result = result;
        }

        @Override
        public McpToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return Map.of();
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    return Future.succeededFuture(McpToolResult.structured(result));
                }
            };
        }
    }

    private static final class CountingZeros extends AbstractList<Integer> {
        private final int size;
        private final AtomicInteger serializationAttempts;

        CountingZeros(int size, AtomicInteger serializationAttempts) {
            this.size = size;
            this.serializationAttempts = serializationAttempts;
        }

        @Override
        public Integer get(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException(index);
            }
            if (index == 0) {
                serializationAttempts.incrementAndGet();
            }
            return 0;
        }

        @Override
        public int size() {
            return size;
        }
    }

    private static final class RecordingObserver implements McpRequestLifecycleObserver {
        private final RecordingSession session = new RecordingSession();

        @Override
        public McpRequestObservation open(Instant startedAt) {
            return session;
        }

        int outputCount() {
            return session.outputCount.get();
        }

        int terminalCount() {
            return session.terminalCount.get();
        }

        int completedCount() {
            return session.completedCount.get();
        }

        dev.vertique.mcp.lifecycle.McpRequestTerminalEvent terminal() {
            return session.terminal.get();
        }
    }

    private static final class RecordingSession implements McpToolValueObservation {
        private final AtomicInteger outputCount = new AtomicInteger();
        private final AtomicInteger terminalCount = new AtomicInteger();
        private final AtomicInteger completedCount = new AtomicInteger();
        private final AtomicReference<dev.vertique.mcp.lifecycle.McpRequestTerminalEvent> terminal =
                new AtomicReference<>();

        @Override
        public void onToolInput(McpToolInputObservation observation) {}

        @Override
        public void onToolOutput(McpToolOutputObservation observation) {
            outputCount.incrementAndGet();
        }

        @Override
        public void onTerminal(McpRequestTerminalObservation observation) {
            terminalCount.incrementAndGet();
            terminal.set(observation.event());
        }

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {
            completedCount.incrementAndGet();
        }
    }
}
