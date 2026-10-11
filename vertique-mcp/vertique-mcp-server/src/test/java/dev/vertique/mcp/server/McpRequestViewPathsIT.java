// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static dev.vertique.mcp.server.McpRequestViewITSupport.DENIED_TOOL;
import static dev.vertique.mcp.server.McpRequestViewITSupport.RESPONSE_MARKER;
import static dev.vertique.mcp.server.McpRequestViewITSupport.RETAINING_TOOL;
import static dev.vertique.mcp.server.McpRequestViewITSupport.await;
import static dev.vertique.mcp.server.McpRequestViewITSupport.callBody;
import static dev.vertique.mcp.server.McpRequestViewITSupport.post;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.mcp.server.McpRecordingCompletedListener.Completion;
import dev.vertique.mcp.server.McpRequestViewITSupport.DeniedTool;
import dev.vertique.mcp.server.McpRequestViewITSupport.RetainingTool;
import dev.vertique.mcp.server.McpRequestViewITSupport.Started;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Every path a request can take reaches exactly one completion listener view, and the view reports the
 * facts the server had bound by the time the request ended.
 *
 * <p>Real port-0 server bound and connected on {@code 127.0.0.1}; each test starts its own server so a
 * path is observed in isolation. A genuine credential rejection (a route-level {@code 401}) happens
 * ahead of the MCP mount and so reaches no coordinator; this fixture has no such gate, and the nearest
 * rejection it can drive is an identity-resolution failure, which is covered below.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpRequestViewPathsIT {

    private static final String AUTHORIZATION = "Bearer secret-token-xyz";
    private static final Map<String, String> CALLER_HEADERS = Map.of("Authorization", AUTHORIZATION);
    private static final String ARGUMENTS = "{\"customer\":{\"name\":\"Ada\"}}";
    private static final String NOT_JSON = "{not json";
    private static final int ID = 1;

    private final Vertx vertx = Vertx.vertx();

    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;
    private McpRecordingCompletedListener listener;
    private int port;
    private AtomicBoolean failIdentity;

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
    @DisplayName("a successful call reports its id, headers, bytes and tool values")
    void shouldReportEveryFactOfASuccessfulCall() throws Exception {
        // Given: a server with a public tool and one listener.
        start();
        String body = callBody(RETAINING_TOOL, ARGUMENTS, ID);

        // When: one successful call carrying a credential header is made.
        HttpResponse<Buffer> response = await(post(client, port, RETAINING_TOOL, body, CALLER_HEADERS));
        Completion view = onlyCompletion();

        // Then: the view reports what was sent, what was received and the prepared call.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(view.jsonRpcRequestId()).contains("1");
        assertThat(view.requestHeaders().get("authorization")).containsExactly(AUTHORIZATION);
        assertThat(view.requestBody()).isEqualTo(body);
        assertThat(view.responseBody()).isEqualTo(response.bodyAsString()).contains(RESPONSE_MARKER);
        assertThat(view.toolContext()).isPresent();
        assertThat(view.toolContext().orElseThrow().tool().name()).isEqualTo(RETAINING_TOOL);
        assertThat(view.toolInput()).isPresent();
        assertThat(view.toolInput().orElseThrow())
                .containsEntry("customer", Map.of("name", "Ada"))
                .hasSize(1);
        assertThat(view.toolOutput()).isPresent();
        assertThat(view.structuredOutput()).isPresent();
        assertThat(view.structuredOutput().orElseThrow()).isEqualTo(Map.of("marker", RESPONSE_MARKER, "seen", "null"));
    }

    @Test
    @DisplayName("an unknown method is reported with its headers and bytes, and no tool values")
    void shouldReportTheRequestAndResponseOfAnUnknownMethod() throws Exception {
        // Given: a server and a request whose JSON-RPC method does not exist.
        start();
        String body = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", ID)
                .put("method", "tools/unknown")
                .put("params", new JsonObject())
                .encode();

        // When: it is sent.
        HttpResponse<Buffer> response = await(post(client, port, RETAINING_TOOL, body, CALLER_HEADERS));
        Completion view = onlyCompletion();

        // Then: the server rejected it, and the view still carries the request and the rejection.
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(view.requestHeaders().get("authorization")).containsExactly(AUTHORIZATION);
        assertThat(view.hasRequestBody()).isTrue();
        assertThat(view.requestBody()).isEqualTo(body);
        assertThat(view.responseBody()).isEqualTo(response.bodyAsString());
        assertThat(view.toolInput()).isEmpty();
        assertThat(view.toolContext()).isEmpty();
        assertThat(view.toolOutput()).isEmpty();
    }

    @Test
    @DisplayName("a params-schema rejection is reported with the request id, bytes and no tool values")
    void shouldReportTheRequestIdAndBytesOfAProtocolRejectionPastTheEnvelope() throws Exception {
        // Given: a well-formed envelope whose params violate the schema (a numeric tool name).
        start();
        String body =
                callBody(RETAINING_TOOL, ARGUMENTS, ID).replace("\"name\":\"" + RETAINING_TOOL + "\"", "\"name\":5");
        assertThat(body).as("the rejection is built from the ordinary call").contains("\"name\":5");

        // When: it is sent.
        HttpResponse<Buffer> response = await(post(client, port, RETAINING_TOOL, body, CALLER_HEADERS));
        Completion view = onlyCompletion();

        // Then: the envelope decoded, so the id is bound; the rejection never reached a tool.
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(view.jsonRpcRequestId()).contains("1");
        assertThat(view.requestHeaders().get("authorization")).containsExactly(AUTHORIZATION);
        assertThat(view.requestBody()).isEqualTo(body);
        assertThat(view.responseBody()).isEqualTo(response.bodyAsString());
        assertThat(view.toolInput()).isEmpty();
        assertThat(view.toolContext()).isEmpty();
    }

    @Test
    @DisplayName("an undecodable body is reported with its headers and bytes, and no request id")
    void shouldReportTheRequestBytesOfAnUndecodableBody() throws Exception {
        // Given: a server and a body that is not JSON.
        start();

        // When: it is sent.
        HttpResponse<Buffer> response = await(post(client, port, RETAINING_TOOL, NOT_JSON, CALLER_HEADERS));
        Completion view = onlyCompletion();

        // Then: a coordinator exists for this path, the headers and bytes were bound at admission, and
        // no id could be decoded.
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(view.requestHeaders().get("authorization")).containsExactly(AUTHORIZATION);
        assertThat(view.hasRequestBody()).isTrue();
        assertThat(view.requestBody()).isEqualTo(NOT_JSON);
        assertThat(view.jsonRpcRequestId()).isEmpty();
        assertThat(view.responseBody()).isEqualTo(response.bodyAsString());
        assertThat(view.toolInput()).isEmpty();
        assertThat(view.toolContext()).isEmpty();
    }

    @Test
    @DisplayName("a call to a tool the caller may not invoke is reported with its bytes and no tool values")
    void shouldReportTheRequestAndRejectionOfADeniedToolCall() throws Exception {
        // Given: a server with a tool no caller may invoke.
        start();
        String body = callBody(DENIED_TOOL, ARGUMENTS, ID);

        // When: it is called.
        HttpResponse<Buffer> response = await(post(client, port, DENIED_TOOL, body, CALLER_HEADERS));
        Completion view = onlyCompletion();

        // Then: authorization rejected the call before any tool value was bound.
        assertThat(response.statusCode()).isBetween(400, 403);
        assertThat(view.jsonRpcRequestId()).contains("1");
        assertThat(view.requestHeaders().get("authorization")).containsExactly(AUTHORIZATION);
        assertThat(view.requestBody()).isEqualTo(body);
        assertThat(view.responseBody()).isEqualTo(response.bodyAsString());
        assertThat(view.toolInput()).isEmpty();
        assertThat(view.toolContext()).isEmpty();
        assertThat(view.toolOutput()).isEmpty();
    }

    @Test
    @DisplayName("an identity-resolution failure is reported with its headers and bytes, and no request id")
    void shouldReportTheRequestAndFailureOfAnIdentityResolutionFailure() throws Exception {
        // Given: a server whose identity resolution fails.
        start();
        failIdentity.set(true);
        String body = callBody(RETAINING_TOOL, ARGUMENTS, ID);

        // When: a call is made.
        HttpResponse<Buffer> response = await(post(client, port, RETAINING_TOOL, body, CALLER_HEADERS));
        Completion view = onlyCompletion();

        // Then: the failure is on the wire and in the view next to the request, ahead of any decoding.
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(view.requestHeaders().get("authorization")).containsExactly(AUTHORIZATION);
        assertThat(view.requestBody()).isEqualTo(body);
        assertThat(view.jsonRpcRequestId()).isEmpty();
        assertThat(view.responseBody())
                .as("the failure response carries no body, and the view reports exactly that")
                .isEqualTo(Objects.toString(response.bodyAsString(), ""));
        assertThat(view.toolInput()).isEmpty();
        assertThat(view.toolOutput()).isEmpty();
    }

    // --- harness ---

    private void start() throws Exception {
        listener = new McpRecordingCompletedListener();
        failIdentity = new AtomicBoolean();
        Started started = McpRequestViewITSupport.start(
                vertx, Set.of(listener), Set.of(new RetainingTool(RETAINING_TOOL), new DeniedTool()), failIdentity);
        server = started.server();
        port = started.port();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
    }

    /** Awaits the completion, then proves no second view arrives for the one request. */
    private Completion onlyCompletion() throws Exception {
        List<Completion> completions = listener.await(1);
        assertThat(completions)
                .as("the listener must receive exactly one view for one request")
                .hasSize(1);
        assertThat(listener.arrivesWithin(2, Duration.ofMillis(200)))
                .as("no second view may follow")
                .isFalse();
        Optional<Completion> only = completions.stream().findFirst();
        return only.orElseThrow();
    }
}
