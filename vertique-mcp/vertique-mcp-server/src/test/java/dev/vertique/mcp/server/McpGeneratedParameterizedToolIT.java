// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * MCP-001 P04 remediation TP-001 — proves the mandatory input-processing pipeline (contract §4.7)
 * genuinely runs for a real, generated <strong>parameterized</strong> {@code @McpTool} invoker,
 * closing the exact gap the phase-exit security review named: T015's real-processor proof
 * ({@code McpInputPipelineIT}) exercises a hand-written {@code PipelineToolInvoker} stand-in for
 * {@code prepare()}, and T012's real-generated-invoker proof ({@code McpGeneratedHelloToolIT}) uses a
 * zero-argument tool whose {@code Input} carrier has no component to process, materialize, or
 * validate. Neither proof could have caught the emitter shipping a vacuous {@code prepare()} that
 * never called {@code InputObjectProcessor}, {@code McpToolRuntime}, or {@code McpBeanValidation} at
 * all — this test compiles a real parameterized tool through the real {@link
 * dev.vertique.codegen.mcp.McpToolProcessor} and drives it exactly as production {@link
 * McpRequestDispatcher} would, so a regression back to that vacuous shape fails here.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpGeneratedParameterizedToolIT {

    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SSE_PREFIX = "event: message\ndata: ";
    private static final String RAW_NAME = "ada";
    private static final String SANITIZED_NAME = "ADA";
    private static final String EXPECTED_GREETING = "Hello, " + SANITIZED_NAME + "!";
    private static final String BEAN_VALIDATION_MESSAGE = "Invalid tool arguments: constraint validation failed";

    /**
     * The placeholder input schema the pre-remediation emitter always published, regardless of the
     * tool's real declared parameters — the exact literal (a) below must distinguish from.
     */
    private static final String INPUT_SCHEMA_PLACEHOLDER = "{\"type\":\"object\"}";

    private final Vertx vertx = Vertx.vertx();

    private McpGeneratedParameterizedToolITFixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    /**
     * Joins the server and raw-client closes, then closes the owned {@link Vertx} from the join
     * callback, mirroring {@link McpGeneratedHelloToolIT}'s teardown.
     *
     * @throws Exception if teardown does not complete within its bound
     */
    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose)
                .onComplete(ignored -> vertx.close().onComplete(result -> closed.complete(null)));
        closed.get(10, TimeUnit.SECONDS);
        fixture = null;
        server = null;
        rawClient = null;
        client = null;
    }

    @Test
    @DisplayName("shouldRunTheRealInputPipelineForAGeneratedParameterizedTool")
    void shouldRunTheRealInputPipelineForAGeneratedParameterizedTool() throws Exception {
        // --- Given: a composed server whose single tool is produced by the real annotation processor
        // from a real, parameterized @McpTool application source declaring a real @Sanitize chain and
        // a real @NotBlank constraint directly on the tool parameter. ---
        fixture = McpGeneratedParameterizedToolITFixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        // --- Then (a): the real runtime binding's descriptor carries the hardened input schema —
        // DECISIVE: the pre-remediation emitter always published the placeholder object schema below
        // regardless of the tool's real parameters, which would neither equal a different string nor
        // ever carry "additionalProperties":false (nothing hardens a literal that is never generated
        // from the carrier type at all). ---
        String inputSchema = fixture.invoker().descriptor().inputSchema();
        assertThat(inputSchema)
                .as("DECISIVE: the published schema must be the real hardened schema, never the frozen"
                        + " placeholder every tool used to publish regardless of its declared parameters")
                .isNotEqualTo(INPUT_SCHEMA_PLACEHOLDER)
                .contains("\"additionalProperties\":false");

        // --- When: a valid call, whose "name" argument the declared @Sanitize chain uppercases. ---
        HttpResponse<Buffer> validCall = await(post().sendBuffer(callBody(RAW_NAME)));

        // --- Then (b): the tool's own response text was built from the post-sanitization value —
        // DECISIVE: this text can only appear if the real InputObjectProcessor genuinely invoked the
        // declared UpperCaseSanitizer between the raw wire value and the materialized carrier; an
        // inert stage 2 would have called GreetingTools.compose("ada", ...) and produced "Hello, ada!"
        // instead. ---
        assertThat(validCall.statusCode())
                .as("a valid, authorized call must succeed")
                .isEqualTo(200);
        JsonObject validResult = sseResult(validCall.bodyAsString());
        assertThat(validResult.getBoolean("isError")).isFalse();
        assertThat(validResult.getJsonArray("content").getJsonObject(0).getString("text"))
                .as("DECISIVE: the declared @Sanitize chain must have genuinely transformed the argument"
                        + " before the handler ever ran")
                .isEqualTo(EXPECTED_GREETING);

        // --- Then (d): the delivered onToolInput observation carries the post-processing tree, never
        // the raw wire tree — DECISIVE: normalizedArguments() must reflect the sanitized value; the raw
        // wire value "ada" reaching this callback would mean the server substituted (or a stand-in
        // fabricated) the pre-processing map instead of McpPreparedToolCall's own real value. ---
        assertThat(fixture.session().toolInputCount())
                .as("exactly the one valid call must have reached the value-observation stage")
                .isEqualTo(1);
        assertThat(fixture.session().observed().normalizedArguments())
                .as("DECISIVE: the observed tree must be the post-processing (sanitized) value, not the"
                        + " pre-processing wire value")
                .isEqualTo(Map.of("name", SANITIZED_NAME));

        // --- When: a constraint-violating call — a blank/whitespace-only "name" the declared
        // @NotBlank rejects only after stage 2/3 succeed (the sanitizer's uppercase transform leaves
        // whitespace unchanged, and materialization itself cannot fail on a plain String). ---
        HttpResponse<Buffer> rejectedCall = await(post().sendBuffer(callBody("   ")));

        // --- Then (c): the rejection settles as the bounded text-only isError=true tool-error outcome
        // — DECISIVE: not HTTP 500/INTERNAL. Before this remediation, McpBeanValidation was never
        // called, so this exact argument would have reached GreetingTools.compose(...) instead of
        // being rejected, or a wrong-typed/mis-cast argument elsewhere in the pipeline would have
        // settled as an uncaught-exception internal error; the bounded text asserted below is also the
        // fixed literal, never a Bean Validation ConstraintViolation#getMessage() (which Hibernate
        // Validator interpolates through EL and which must never reach the wire). ---
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(rejectedCall.statusCode())
                .as("DECISIVE: a stage-4 pipeline rejection must still be HTTP 200, never HTTP 500")
                .isEqualTo(200);
        JsonObject rejectedResult = sseResult(rejectedCall.bodyAsString());
        softly.assertThat(rejectedResult.getBoolean("isError"))
                .as("a pipeline rejection is a tool-error result")
                .isTrue();
        softly.assertThat(rejectedResult.getJsonArray("content").size())
                .as("bounded: exactly one content item")
                .isEqualTo(1);
        softly.assertThat(
                        rejectedResult.getJsonArray("content").getJsonObject(0).getString("text"))
                .as("DECISIVE: the exact fixed literal, never an interpolated Bean Validation message")
                .isEqualTo(BEAN_VALIDATION_MESSAGE);
        softly.assertThat(rejectedResult.containsKey("structuredContent"))
                .as("text-only: no structured content")
                .isFalse();
        softly.assertThat(fixture.session().toolInputCount())
                .as("the rejected call must never have reached the value-observation stage")
                .isEqualTo(1);
        softly.assertAll();

        // --- Then (decisive — "without reflection"): the generated invoker's own source contains no
        // java.lang.reflect symbol, exactly like every other generated invoker. ---
        fixture.result()
                .assertGeneratedSourceDoesNotContain(
                        McpGeneratedParameterizedToolITFixture.INVOKER_FQN, "java.lang.reflect");
    }

    // --- Wire helpers ---

    private HttpRequest<Buffer> post() {
        return client.post(fixture.port(), "127.0.0.1", REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", McpGeneratedParameterizedToolITFixture.TOOL_NAME);
    }

    private static Buffer callBody(String name) {
        JsonObject meta = new JsonObject()
                .put(
                        "_meta",
                        new JsonObject()
                                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject()));
        JsonObject params = meta.copy()
                .put("name", McpGeneratedParameterizedToolITFixture.TOOL_NAME)
                .put("arguments", new JsonObject().put("name", name));
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put("params", params)
                .toBuffer();
    }

    private static JsonObject sseResult(String rawBody) {
        assertThat(rawBody).as("every row here settles through SSE framing").startsWith(SSE_PREFIX);
        JsonObject payload =
                new JsonObject(rawBody.substring(SSE_PREFIX.length()).stripTrailing());
        JsonObject result = payload.getJsonObject("result");
        assertThat(result)
                .as("every row here settles as a CallToolResult, never a JSON-RPC error")
                .isNotNull();
        return result;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
