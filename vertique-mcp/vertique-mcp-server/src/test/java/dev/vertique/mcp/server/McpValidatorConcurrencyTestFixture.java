// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Framework wiring for {@link McpValidatorConcurrencyTest}: descriptor and context plumbing only. */
final class McpValidatorConcurrencyTestFixture {

    private McpValidatorConcurrencyTestFixture() {}

    /**
     * Builds the fixed two-tool descriptor registry both contexts compile: {@code weather.lookup}
     * requires a {@code city} string argument on a closed argument object, and {@code clock.now}
     * takes no arguments on a closed argument object.
     *
     * @return the descriptor registry, keyed by tool name
     */
    static Map<String, McpToolDescriptor> twoToolDescriptors() {
        McpToolAnnotations annotations = new McpToolAnnotations(true, false, true, false);
        McpToolAccess access = new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null);
        McpToolDescriptor weatherLookup = new McpToolDescriptor(
                "weather.lookup",
                null,
                "Look up weather",
                annotations,
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},"
                        + "\"required\":[\"city\"],\"additionalProperties\":false}",
                null,
                access);
        McpToolDescriptor clockNow = new McpToolDescriptor(
                "clock.now",
                null,
                "Read the clock",
                annotations,
                "{\"type\":\"object\",\"additionalProperties\":false}",
                null,
                access);
        return Map.of("weather.lookup", weatherLookup, "clock.now", clockNow);
    }

    /**
     * Composes a fresh {@link McpSchemaRegistry} for one tool descriptor registry, executed on the
     * owning Vert.x {@link Context} and awaited from the calling test thread.
     *
     * @param context the owning Vert.x context
     * @param descriptors the tool descriptor registry to compile
     * @return the composed registry
     * @throws Exception if construction fails or the context never completes it
     */
    static McpSchemaRegistry compileOnContext(Context context, Map<String, McpToolDescriptor> descriptors)
            throws Exception {
        CompletableFuture<McpSchemaRegistry> result = new CompletableFuture<>();
        context.runOnContext(ignored -> {
            try {
                result.complete(new McpSchemaRegistry(descriptors));
            } catch (RuntimeException failed) {
                result.completeExceptionally(failed);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    /**
     * Closes a {@link Vertx} instance and waits for teardown to settle, so no owned resource leaks
     * past the test.
     *
     * @param vertx the instance to close
     * @throws Exception if close fails or never completes
     */
    static void closeAndAwait(Vertx vertx) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close().onComplete(outcome -> {
            if (outcome.failed()) {
                failure.set(outcome.cause());
            }
            closed.complete(null);
        });
        closed.get(10, TimeUnit.SECONDS);
        if (failure.get() != null) {
            throw new IllegalStateException("failed to close Vertx", failure.get());
        }
    }
}
