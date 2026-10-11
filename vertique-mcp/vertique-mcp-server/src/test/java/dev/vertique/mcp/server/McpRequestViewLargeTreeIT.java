// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static dev.vertique.mcp.server.McpRequestViewITSupport.PASS_THROUGH_TOOL;
import static dev.vertique.mcp.server.McpRequestViewITSupport.SSE_PREFIX;
import static dev.vertique.mcp.server.McpRequestViewITSupport.await;
import static dev.vertique.mcp.server.McpRequestViewITSupport.callBody;
import static dev.vertique.mcp.server.McpRequestViewITSupport.post;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestView;
import dev.vertique.mcp.server.McpRequestViewITSupport.PassThroughTool;
import dev.vertique.mcp.server.McpRequestViewITSupport.Started;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A tool argument tree nested as deep as the envelope codec permits reaches a completion listener
 * through the request view and can be walked to its leaf, and the request itself completes normally.
 *
 * <p>Real port-0 server bound and connected on {@code 127.0.0.1}; every owned resource closed on every
 * teardown path.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class McpRequestViewLargeTreeIT {

    /**
     * The codec bounds the whole document at 1,000 levels: the envelope object, its {@code params}
     * and {@code arguments} take three, so {@code arguments} may itself nest 998 objects.
     */
    private static final int ARGUMENT_LEVELS = 998;

    private static final String LEAF = "leaf-value";

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
    @DisplayName("a maximally nested argument tree is walked to its leaf through the view and the call completes 200")
    void shouldWalkAMaximallyNestedArgumentTreeToItsLeafThroughTheView() throws Exception {
        // Given: a listener that walks the reported tree with an explicit stack and records the result.
        CompletableFuture<Walk> walked = new CompletableFuture<>();
        McpRequestCompletedListener listener = new McpRequestCompletedListener() {
            @Override
            public void onCompleted(McpRequestCompletedEvent event) {}

            @Override
            public void onCompleted(McpRequestCompletedEvent event, McpRequestView view) {
                try {
                    walked.complete(walk(view.toolInput().orElseThrow()));
                } catch (Throwable failure) {
                    walked.completeExceptionally(failure);
                }
            }
        };
        Started started = McpRequestViewITSupport.start(
                vertx, Set.of(listener), Set.of(new PassThroughTool()), new AtomicBoolean());
        server = started.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
        String arguments = "{\"k\":".repeat(ARGUMENT_LEVELS) + "\"" + LEAF + "\"" + "}".repeat(ARGUMENT_LEVELS);

        // When: the call carrying the deepest tree the codec accepts is made.
        HttpResponse<Buffer> response = await(
                post(client, started.port(), PASS_THROUGH_TOOL, callBody(PASS_THROUGH_TOOL, arguments, 1), Map.of()));

        // Then: the request completes normally, and the listener reached the leaf without error.
        assertThat(response.statusCode()).isEqualTo(200);
        String text = response.bodyAsString();
        assertThat(text).startsWith(SSE_PREFIX);
        JsonObject result = new JsonObject(text.substring(SSE_PREFIX.length()).stripTrailing()).getJsonObject("result");
        assertThat(result.getBoolean("isError")).isFalse();
        Walk walk = walked.get(10, TimeUnit.SECONDS);
        assertThat(walk.leaf()).as("the scalar at the bottom of the tree").isEqualTo(LEAF);
        assertThat(walk.containerLevels()).as("nested object levels walked").isEqualTo(ARGUMENT_LEVELS);
    }

    /** Walks {@code root} with an explicit stack, counting container levels and finding the leaf. */
    private static Walk walk(Map<String, Object> root) {
        Deque<Object> stack = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        stack.push(root);
        depths.push(1);
        int deepest = 0;
        Object leaf = null;
        while (!stack.isEmpty()) {
            Object node = stack.pop();
            int depth = depths.pop();
            if (node instanceof Map<?, ?> map) {
                deepest = Math.max(deepest, depth);
                for (Object child : map.values()) {
                    stack.push(child);
                    depths.push(depth + 1);
                }
            } else if (node instanceof List<?> list) {
                deepest = Math.max(deepest, depth);
                for (Object child : list) {
                    stack.push(child);
                    depths.push(depth + 1);
                }
            } else {
                leaf = node;
            }
        }
        return new Walk(leaf, deepest);
    }

    private record Walk(Object leaf, int containerLevels) {}
}
