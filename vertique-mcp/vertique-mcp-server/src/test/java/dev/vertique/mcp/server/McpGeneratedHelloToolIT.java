// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T012 TP-002 — proves the {@code tools/call} vertical against genuine annotation-processor output,
 * not a hand-authored double. {@code McpToolCallIT}'s tools are all directly constructed {@link
 * dev.vertique.mcp.tool.McpToolInvoker} doubles; this test's single tool is produced by the real
 * {@link dev.vertique.codegen.mcp.McpToolProcessor} from a real {@code @McpTool} application source,
 * compiled and loaded through {@link McpGeneratedHelloToolITFixture} — the gap T008's carrier IT
 * could not close, because {@code vertique-mcp-server} must never depend on the codegen module at
 * compile/runtime (it is a test-scope-only dependency here).
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpGeneratedHelloToolIT {

    private static final String REQUEST_PATH = "/mcp/";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SSE_PREFIX = "event: message\ndata: ";

    private final Vertx vertx = Vertx.vertx();

    private McpGeneratedHelloToolITFixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    /**
     * Joins the server and raw-client closes, then closes the owned {@link Vertx} from the join
     * callback, mirroring {@link McpToolCallIT}'s teardown.
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
    @DisplayName("shouldCallAGeneratedToolEndToEnd")
    void shouldCallAGeneratedToolEndToEnd() throws Exception {
        // --- Given: a composed server whose single tool is produced by the real annotation processor
        // from a real @McpTool application source, reached over one stateless Streamable HTTP mount. ---
        fixture = McpGeneratedHelloToolITFixture.start(vertx, true);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        // --- When: discover, list, and call the generated tool once, in that order, over the same
        // mount, with no initialization request and no session id sent or ever established. ---
        HttpResponse<Buffer> discover =
                await(post("server/discover", "server/discover").sendBuffer(discoverBody()));
        HttpResponse<Buffer> list = await(post("tools/list", "tools/list").sendBuffer(listBody()));
        HttpResponse<Buffer> call = await(
                post("tools/call", McpGeneratedHelloToolITFixture.TOOL_NAME).sendBuffer(callBody()));

        // --- Then: all three interactions succeed over the same mount, no session id is ever
        // established, and the call reaches the real generated invoker: the greeting text can only
        // have come from HelloTools.greet() actually executing, since no other code path produces it. ---
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(discover.statusCode())
                .as("server/discover must succeed")
                .isEqualTo(200);
        softly.assertThat(list.statusCode()).as("tools/list must succeed").isEqualTo(200);
        JsonObject listResult = new JsonObject(list.bodyAsString()).getJsonObject("result");
        softly.assertThat(toolNames(listResult))
                .as("exactly the one generated tool must be listed")
                .containsExactly(McpGeneratedHelloToolITFixture.TOOL_NAME);
        softly.assertThat(call.statusCode()).as("tools/call must succeed").isEqualTo(200);
        softly.assertThat(call.getHeader("content-type"))
                .as("a known, authorized call must select SSE")
                .isEqualTo("text/event-stream");
        softly.assertThat(call.bodyAsString())
                .as("a known, authorized tools/call response must use the frozen SSE framing")
                .startsWith(SSE_PREFIX);
        softly.assertThat(bestEffortCallText(call.bodyAsString()))
                .as("DECISIVE: this text is produced only by the real generated invoker actually calling"
                        + " HelloTools.greet() directly — no hand-authored double reaches this path")
                .isEqualTo(McpGeneratedHelloToolITFixture.GREETING);
        for (HttpResponse<Buffer> response : List.of(discover, list, call)) {
            softly.assertThat(response.getHeader("Mcp-Session-Id"))
                    .as("the stateless mount establishes no session id")
                    .isNull();
        }
        softly.assertAll();

        // --- Then (decisive — "without reflection"): the generated invoker's own source contains no
        // java.lang.reflect symbol, and every call this test drove above reached it only through the
        // McpToolInvoker interface — exactly like production McpRequestDispatcher, which imports no
        // java.lang.reflect type at all. ---
        fixture.result()
                .assertGeneratedSourceDoesNotContain(McpGeneratedHelloToolITFixture.INVOKER_FQN, "java.lang.reflect");
    }

    // --- Wire helpers ---

    private HttpRequest<Buffer> post(String negotiatedMethod, String negotiatedName) {
        return client.post(fixture.port(), "127.0.0.1", REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", negotiatedMethod)
                .putHeader("Mcp-Name", negotiatedName);
    }

    private static Buffer discoverBody() {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "server/discover")
                .put("params", meta())
                .toBuffer();
    }

    private static Buffer listBody() {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 2)
                .put("method", "tools/list")
                .put("params", meta())
                .toBuffer();
    }

    private static Buffer callBody() {
        JsonObject params = meta().put("name", McpGeneratedHelloToolITFixture.TOOL_NAME);
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 3)
                .put("method", "tools/call")
                .put("params", params)
                .toBuffer();
    }

    private static JsonObject meta() {
        return new JsonObject()
                .put(
                        "_meta",
                        new JsonObject()
                                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject()));
    }

    private static List<String> toolNames(JsonObject result) {
        List<String> names = new ArrayList<>();
        for (Object element : result.getJsonArray("tools")) {
            names.add(((JsonObject) element).getString("name"));
        }
        return names;
    }

    /**
     * Extracts the call's reported text content, defensively: an SSE-framed success yields the real
     * text; anything else (a JSON-RPC error, a differently-shaped body) yields a blank string rather
     * than throwing, so this participates in the soft-assertion batch above instead of short-
     * circuiting it — the surrounding {@code SSE_PREFIX}/{@code content-type} soft assertions already
     * name the actual shape mismatch precisely.
     */
    private static String bestEffortCallText(String rawBody) {
        if (!rawBody.startsWith(SSE_PREFIX)) {
            return "";
        }
        JsonObject payload =
                new JsonObject(rawBody.substring(SSE_PREFIX.length()).stripTrailing());
        JsonObject result = payload.getJsonObject("result");
        if (result == null) {
            return "";
        }
        return result.getJsonArray("content").getJsonObject(0).getString("text");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
