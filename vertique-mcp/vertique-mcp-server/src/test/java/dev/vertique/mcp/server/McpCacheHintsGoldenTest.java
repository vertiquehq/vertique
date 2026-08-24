// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins the mandatory {@code ttlMs}/{@code cacheScope=private} cache hints — and their canonical byte
 * order — for both {@code server/discover} and {@code tools/list} (§4.7), driving {@link
 * McpRequestDispatcher#dispatch} directly against a mocked {@link RoutingContext} rather than a real
 * HTTP connection, and comparing raw response bytes rather than a parsed round trip.
 */
class McpCacheHintsGoldenTest {

    private static final long TOOLS_TTL_MS = 300_000;

    private static final byte[] EXPECTED_DISCOVER_RESPONSE =
            ("{\"jsonrpc\":\"2.0\",\"result\":{\"resultType\":\"complete\","
                            + "\"supportedVersions\":[\"2026-07-28\"],\"capabilities\":{\"tools\":{}},"
                            + "\"ttlMs\":300000,\"cacheScope\":\"private\","
                            + "\"_meta\":{\"io.modelcontextprotocol/serverInfo\":"
                            + "{\"name\":\"vertique-golden\",\"version\":\"1.0\"}}},\"id\":1}")
                    .getBytes(StandardCharsets.UTF_8);

    private static final byte[] EXPECTED_TOOLS_LIST_RESPONSE =
            ("{\"jsonrpc\":\"2.0\",\"result\":{\"resultType\":\"complete\",\"tools\":[{"
                            + "\"name\":\"golden.tool\",\"description\":\"Golden fixture tool.\","
                            + "\"inputSchema\":{\"type\":\"object\",\"additionalProperties\":false},"
                            + "\"annotations\":{\"readOnlyHint\":true,\"destructiveHint\":false,"
                            + "\"idempotentHint\":true,\"openWorldHint\":false}}],"
                            + "\"ttlMs\":300000,\"cacheScope\":\"private\","
                            + "\"_meta\":{\"io.modelcontextprotocol/serverInfo\":"
                            + "{\"name\":\"vertique-golden\",\"version\":\"1.0\"}}},\"id\":1}")
                    .getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("server/discover and tools/list both emit the pinned ttlMs/cacheScope=private cache hints")
    void shouldMatchPinnedDiscoverAndListCacheHints() {
        McpCacheHintsGoldenTestFixture fixture = McpCacheHintsGoldenTestFixture.build(TOOLS_TTL_MS);

        byte[] discoverBytes = fixture.dispatch(McpCacheHintsGoldenTestFixture.discoverRequestBody());
        assertThat(discoverBytes)
                .as("server/discover must match the pinned canonical byte order")
                .isEqualTo(EXPECTED_DISCOVER_RESPONSE);

        byte[] toolsListBytes = fixture.dispatch(McpCacheHintsGoldenTestFixture.toolsListRequestBody());
        assertThat(toolsListBytes)
                .as("tools/list must match the pinned canonical byte order")
                .isEqualTo(EXPECTED_TOOLS_LIST_RESPONSE);
    }

    /** Framework wiring for the golden proof: mocked routing plumbing and byte capture, nothing decisive. */
    private static final class McpCacheHintsGoldenTestFixture {

        private static final String SERVER_NAME = "vertique-golden";
        private static final String SERVER_VERSION = "1.0";
        private static final String PROTOCOL_VERSION = "2026-07-28";
        private static final String TOOL_NAME = "golden.tool";

        private final McpRequestDispatcher dispatcher;

        private McpCacheHintsGoldenTestFixture(McpRequestDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        static McpCacheHintsGoldenTestFixture build(long toolsTtlMs) {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .toolsTtlMs(toolsTtlMs)
                    .build();
            McpToolRegistry registry = McpToolRegistry.build(Set.of(new FixtureToolInvoker(descriptor())));
            SecurityPolicyEnforcer securityPolicyEnforcer = new SecurityPolicyEnforcer(
                    Optional.empty(),
                    Optional.empty(),
                    Set.of(),
                    new SecurityEventEmitter(Set.of()),
                    NO_OP_CONTEXT_HOLDER,
                    mock(SecurityRuntime.class),
                    Optional.empty());
            McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(securityPolicyEnforcer);
            SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
            SecurityContext anonymous = SecurityContexts.unauthenticated(SecurityIdentity.anonymous());
            when(securityRuntime.current()).thenReturn(anonymous);
            McpRequestDispatcher dispatcher = new McpRequestDispatcher(
                    config,
                    securityRuntime,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    HttpConfig.builder().build(),
                    registry,
                    policyEnforcer,
                    NO_OP_CONTEXT_HOLDER,
                    new CorrelationContextFactory(Optional.empty()));
            return new McpCacheHintsGoldenTestFixture(dispatcher);
        }

        /**
         * Drives {@link McpRequestDispatcher#dispatch} once against a mocked {@link RoutingContext}
         * carrying {@code requestBody}, and returns the exact bytes written to the response.
         */
        byte[] dispatch(byte[] requestBody) {
            RoutingContext context = mock(RoutingContext.class);
            HttpServerRequest request = mock(HttpServerRequest.class);
            HttpServerResponse response = mock(HttpServerResponse.class);
            RequestBody body = mock(RequestBody.class);

            when(context.request()).thenReturn(request);
            when(context.response()).thenReturn(response);
            when(context.body()).thenReturn(body);
            when(body.buffer()).thenReturn(Buffer.buffer(requestBody));
            when(request.headers()).thenReturn(negotiationHeadersFor(requestBody));
            when(response.putHeader(anyString(), anyString())).thenReturn(response);
            when(response.setStatusCode(anyInt())).thenReturn(response);
            when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());

            dispatcher.dispatch(context);

            ArgumentCaptor<Buffer> written = ArgumentCaptor.forClass(Buffer.class);
            verify(response).end(written.capture());
            return written.getValue().getBytes();
        }

        /**
         * Builds the required negotiation headers (R05, issue #429) self-consistent with
         * {@code requestBody}'s own method and {@code _meta.protocolVersion} — this fixture drives
         * cache-hint bytes, not negotiation, so its headers always agree with the body.
         */
        private static io.vertx.core.MultiMap negotiationHeadersFor(byte[] requestBody) {
            JsonObject decoded = new JsonObject(Buffer.buffer(requestBody));
            String method = decoded.getString("method");
            io.vertx.core.MultiMap headers = io.vertx.core.MultiMap.caseInsensitiveMultiMap();
            headers.set("MCP-Protocol-Version", PROTOCOL_VERSION);
            headers.set("Mcp-Method", method);
            headers.set("Mcp-Name", method);
            return headers;
        }

        static byte[] discoverRequestBody() {
            return new JsonObject()
                    .put("jsonrpc", "2.0")
                    .put("id", 1)
                    .put("method", "server/discover")
                    .put("params", requestParams())
                    .toBuffer()
                    .getBytes();
        }

        static byte[] toolsListRequestBody() {
            return new JsonObject()
                    .put("jsonrpc", "2.0")
                    .put("id", 1)
                    .put("method", "tools/list")
                    .put("params", requestParams())
                    .toBuffer()
                    .getBytes();
        }

        private static JsonObject requestParams() {
            return new JsonObject()
                    .put(
                            "_meta",
                            new JsonObject()
                                    .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                                    .put("io.modelcontextprotocol/clientCapabilities", new JsonObject()));
        }

        private static McpToolDescriptor descriptor() {
            McpToolAccess access = new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null);
            McpToolAnnotations annotations = new McpToolAnnotations(true, false, true, false);
            return new McpToolDescriptor(
                    TOOL_NAME,
                    null,
                    "Golden fixture tool.",
                    annotations,
                    "{\"type\":\"object\",\"additionalProperties\":false}",
                    null,
                    access);
        }

        /** A tool-invoker double that only publishes a fixed descriptor; this golden test never invokes it. */
        private static final class FixtureToolInvoker implements McpToolInvoker {
            private final McpToolDescriptor descriptor;

            private FixtureToolInvoker(McpToolDescriptor descriptor) {
                this.descriptor = descriptor;
            }

            @Override
            public McpToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
                throw new AssertionError("the cache-hints golden test never invokes a tool");
            }
        }
    }

    /** A {@link ContextHolder} that resolves nothing and discards every binding. */
    private static final ContextHolder NO_OP_CONTEXT_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return () -> {};
        }
    };
}
