// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Review-finding proof (round-14 remediation, defect #5): {@code McpToolInvokerEmitter} emitted
 * {@code ctx.types().erasure(...)} for a generic structured-output type, so {@code
 * Future<List<Note>>} advertised the same schema {@code List.class} would (no element description),
 * and output-schema validation could never reject an element that violated it.
 *
 * <p>Proved through two real, generated, parameterized (never hand-authored) tools compiled by the real {@link
 * dev.vertique.codegen.mcp.McpToolProcessor}, both returning {@code Future<List<Note>>} where {@code
 * Note} carries a real Jakarta Bean Validation {@code @Min(1)} constraint on a record component:
 *
 * <ul>
 *   <li>the loaded invoker's own descriptor must advertise an output schema describing {@code Note}'s
 *       fields under {@code items}, not an opaque/untyped array — DECISIVE against the erased {@code
 *       List.class} shape, which JSON-005 cannot describe an element for.
 *   <li>a call whose returned element violates that schema ({@code priority = -1} against {@code
 *       @Min(1)}) must be rejected as a bounded internal error, never delivered to the wire; a sibling
 *       call whose element satisfies the schema must succeed — the same output-validation gate,
 *       proved both ways so a schema that trivially accepts everything cannot pass silently.
 * </ul>
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpGeneratedGenericStructuredOutputIT {

    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SSE_PREFIX = "event: message\ndata: ";

    private final Vertx vertx = Vertx.vertx();

    private McpGeneratedGenericStructuredOutputITFixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

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
    @DisplayName("a generic structured-output type advertises its element schema and enforces it")
    void shouldAdvertiseElementSchemaAndRejectAViolatingElement() throws Exception {
        fixture = McpGeneratedGenericStructuredOutputITFixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        // --- Then (a): the real generated invoker's own descriptor advertises the element schema ---
        String outputSchemaJson = fixture.validInvoker().descriptor().outputSchema();
        assertThat(outputSchemaJson)
                .as("a structured (non-text) tool must publish an output schema")
                .isNotNull();
        JsonNode outputSchema = new ObjectMapper().readTree(outputSchemaJson);
        assertThat(outputSchema.at("/type").asText())
                .as("the declared List<Note> return type must describe a JSON array")
                .isEqualTo("array");
        assertThat(outputSchema.at("/items/properties/display_name/type").asText())
                .as("DECISIVE: the array's element type must be described — the pre-fix erasure to "
                        + "List.class produces no /items definition at all")
                .isEqualTo("string");
        assertThat(outputSchema.at("/items/properties/priority/minimum").asInt())
                .as("DECISIVE: the element's own @Min(1) constraint must reach the advertised schema, "
                        + "proving the full generic Note element type — not just List — was captured")
                .isEqualTo(1);
        JsonNode inputSchema =
                new ObjectMapper().readTree(fixture.validInvoker().descriptor().inputSchema());
        assertThat(inputSchema
                        .at("/properties/input/properties/display_name/type")
                        .asText())
                .as("the same selected profile must publish the typed input member name")
                .isEqualTo("string");
        assertThat(inputSchema.at("/properties/input/properties/displayName").isMissingNode())
                .as("the neutral mapper's input spelling must not escape the generated profile")
                .isTrue();

        // --- Then (b): a call whose returned element violates that schema is rejected ---
        HttpResponse<Buffer> invalid = await(post(McpGeneratedGenericStructuredOutputITFixture.INVALID_TOOL_NAME)
                .sendBuffer(callBody(McpGeneratedGenericStructuredOutputITFixture.INVALID_TOOL_NAME)));
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(invalid.statusCode())
                .as("DECISIVE: an output element violating its own advertised schema must never reach "
                        + "the wire as a successful result")
                .isEqualTo(500);
        softly.assertThat(invalid.getHeader("content-type"))
                .as("SSE was already selected before output validation runs")
                .isEqualTo("text/event-stream");
        JsonObject invalidError = sseError(invalid.bodyAsString());
        softly.assertThat(invalidError.getInteger("code"))
                .as("output-schema rejection settles as the bounded internal error")
                .isEqualTo(-32603);

        // --- Then (c): the sibling call whose element satisfies the same schema succeeds ---
        fixture.resetNoteAccessCount();
        HttpResponse<Buffer> valid = await(post(McpGeneratedGenericStructuredOutputITFixture.VALID_TOOL_NAME)
                .sendBuffer(callBody(McpGeneratedGenericStructuredOutputITFixture.VALID_TOOL_NAME)));
        softly.assertThat(valid.statusCode())
                .as("an element satisfying the advertised schema must succeed")
                .isEqualTo(200);
        JsonObject validResult = sseResult(valid.bodyAsString());
        softly.assertThat(validResult.getBoolean("isError")).isFalse();
        softly.assertThat(validResult
                        .getJsonArray("structuredContent")
                        .getJsonObject(0)
                        .getString("display_name"))
                .as("the selected profile's output name must reach the wire through the generated writer")
                .isEqualTo("hello");
        softly.assertThat(fixture.observedOutput().toString())
                .as("the output observer receives the same profile-normalized name")
                .contains("display_name")
                .doesNotContain("displayName");
        softly.assertThat(fixture.noteAccessCount())
                .as("the raw generated result is serialized exactly once before normalized reuse")
                .isEqualTo(1);
        softly.assertAll();
    }

    @Test
    @DisplayName("generated profile writers reject every non-finite nested output before observation")
    void shouldRejectEveryNonFiniteNestedValueThroughTheGeneratedProfileWriter() throws Exception {
        fixture = McpGeneratedGenericStructuredOutputITFixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        for (String toolName : McpGeneratedGenericStructuredOutputITFixture.NON_FINITE_TOOL_NAMES) {
            HttpResponse<Buffer> response = await(post(toolName).sendBuffer(callBody(toolName)));

            assertThat(response.statusCode()).as(toolName).isEqualTo(500);
            assertThat(response.bodyAsString())
                    .as("a non-finite spelling from %s must never reach the wire", toolName)
                    .doesNotContain("NaN")
                    .doesNotContain("Infinity");
        }
        assertThat(fixture.outputObservationCount())
                .as("no non-finite value may reach the opt-in output observer")
                .isZero();
    }

    // --- Wire helpers ---

    private HttpRequest<Buffer> post(String toolName) {
        return client.post(fixture.port(), "127.0.0.1", REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", toolName);
    }

    private static Buffer callBody(String toolName) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params = new JsonObject().put("_meta", meta).put("name", toolName);
        if (toolName.equals(McpGeneratedGenericStructuredOutputITFixture.VALID_TOOL_NAME)
                || toolName.equals(McpGeneratedGenericStructuredOutputITFixture.INVALID_TOOL_NAME)) {
            params.put("arguments", new JsonObject().put("input", new JsonObject().put("display_name", "hello")));
        }
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put("params", params)
                .toBuffer();
    }

    private static JsonObject sseData(String rawBody) {
        assertThat(rawBody).as("every row here settles through SSE framing").startsWith(SSE_PREFIX);
        return new JsonObject(rawBody.substring(SSE_PREFIX.length()).stripTrailing());
    }

    private static JsonObject sseResult(String rawBody) {
        JsonObject result = sseData(rawBody).getJsonObject("result");
        assertThat(result).as("a successful response must carry a result").isNotNull();
        return result;
    }

    private static JsonObject sseError(String rawBody) {
        JsonObject error = sseData(rawBody).getJsonObject("error");
        assertThat(error).as("a rejected response must carry a JSON-RPC error").isNotNull();
        return error;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
