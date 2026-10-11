// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static dev.vertique.mcp.server.McpRequestViewITSupport.await;
import static dev.vertique.mcp.server.McpRequestViewITSupport.callBody;
import static dev.vertique.mcp.server.McpRequestViewITSupport.post;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpOutcome;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpTransportOutcome;
import dev.vertique.mcp.server.McpRecordingCompletedListener.Completion;
import dev.vertique.mcp.server.McpRequestViewITSupport.Started;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A request that fails through the mount's failure handler is reported with the method the server had
 * classified by then, and reaches exactly one completion listener view.
 *
 * <p>Real port-0 server on {@code 127.0.0.1}. Application middleware on the root router records the
 * request's routing context. The tool under test fails that context from inside its own invocation,
 * which is how an application component that fails a request in flight does it: the failure then
 * travels the router's failure chain into the mount's failure handler, after the dispatcher classified
 * {@code tools/call}. The second test fails the request before the dispatcher classified anything.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpRequestViewFailurePathIT {

    private static final String FAILING_TOOL = "view.fixture.failing";
    private static final int ID = 7;

    private final Vertx vertx = Vertx.vertx();

    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;
    private McpRecordingCompletedListener listener;
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
    }

    @Test
    @DisplayName("a tool call failed through the failure handler after dispatch is reported as tools/call, "
            + "to one listener view")
    void shouldReportTheClassifiedMethodOfACallFailedAfterDispatch() throws Exception {
        // Given: a server whose tool fails the request's routing context while it is being invoked.
        AtomicReference<RoutingContext> routingContext = new AtomicReference<>();
        start(new AtomicBoolean(), routingContext);

        // When: a tools/call reaches the tool.
        HttpResponse<Buffer> response =
                await(post(client, port, FAILING_TOOL, callBody(FAILING_TOOL, "{}", ID), Map.of()));
        Completion view = onlyCompletion();

        // Then: the failure handler answered, and its terminal carries the classified method.
        assertThat(response.statusCode()).as("the failure handler answered").isEqualTo(500);
        McpRequestTerminalEvent terminal = view.event().terminal();
        assertThat(terminal.method())
                .as("DECISIVE: dispatch had classified tools/call before the failure")
                .isEqualTo(McpMethod.TOOLS_CALL);
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.REJECTED);
        assertThat(terminal.errorType()).isEqualTo(McpErrorType.INTERNAL);
        assertThat(view.event().transportOutcome()).isEqualTo(McpTransportOutcome.WRITTEN);
        assertThat(view.jsonRpcRequestId())
                .as("the id the dispatcher bound before the failure")
                .contains(String.valueOf(ID));
        assertThat(view.toolOutput())
                .as("the failure answered the request, so no tool output is reported")
                .isEmpty();
    }

    @Test
    @DisplayName("a request failed through the failure handler before dispatch classified it is reported "
            + "as OTHER, to one listener view")
    void shouldReportOtherForAFailureBeforeDispatchClassifiedTheMethod() throws Exception {
        // Given: a server whose identity resolution fails, ahead of dispatch.
        start(new AtomicBoolean(true), new AtomicReference<>());

        // When: a tools/call is made.
        HttpResponse<Buffer> response =
                await(post(client, port, FAILING_TOOL, callBody(FAILING_TOOL, "{}", ID), Map.of()));
        Completion view = onlyCompletion();

        // Then: the failure handler answered, and nothing had classified the method yet.
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(view.event().terminal().method())
                .as("DECISIVE: no method was classified before the failure")
                .isEqualTo(McpMethod.OTHER);
        assertThat(view.event().terminal().errorType()).isEqualTo(McpErrorType.INTERNAL);
        assertThat(view.jsonRpcRequestId()).isEmpty();
    }

    // --- harness ---

    private void start(AtomicBoolean failIdentity, AtomicReference<RoutingContext> routingContext) throws Exception {
        listener = new McpRecordingCompletedListener();
        Started started = McpRequestViewITSupport.start(
                vertx, Set.of(listener), Set.of(new FailingTool(routingContext)), failIdentity, context -> {
                    routingContext.set(context);
                    context.next();
                });
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
        assertThat(listener.arrivesWithin(2, Duration.ofMillis(300)))
                .as("no second view may follow")
                .isFalse();
        return completions.get(0);
    }

    /**
     * A tool that fails the request's routing context from inside its invocation and then never
     * completes, so the failure handler is the only thing that can answer the request.
     */
    private static final class FailingTool implements McpToolInvoker {
        private final McpToolDescriptor descriptor = new McpToolDescriptor(
                FAILING_TOOL,
                null,
                "Failure path fixture tool.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\"}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
        private final AtomicReference<RoutingContext> routingContext;

        FailingTool(AtomicReference<RoutingContext> routingContext) {
            this.routingContext = routingContext;
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
                    routingContext.get().fail(500);
                    return Promise.<McpToolResult<?>>promise().future();
                }
            };
        }
    }
}
