// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextScopes;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.interceptor.McpRequestContext;
import dev.vertique.mcp.interceptor.McpRequestInterceptor;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/** T030's fixed characterization of the frozen header and JSON-RPC envelope classifier. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpHeaderEnvelopeCharacterizationTest {

    private static final List<String> SEED_RESOURCES =
            List.of("/mcp/characterization/header-seeds.jsonl", "/mcp/characterization/envelope-seeds.jsonl");

    @ParameterizedTest(name = "{0}")
    @MethodSource("seeds")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("T030: every fixed header and envelope seed retains its pinned classification")
    void shouldClassifyEverySeedAsPinned(Seed seed) {
        var fixture = new McpHeaderEnvelopeCharacterizationTestFixture();

        Classification actual = fixture.classify(seed);

        assertThat(actual.status()).isEqualTo(seed.expectedStatus());
        assertThat(actual.errorCode()).isEqualTo(seed.expectedCode());
        assertThat(fixture.interceptorInvocations()).isEqualTo(seed.expectedInterceptorInvocations());
        assertThat(fixture.toolInvocations())
                .as("seed classification must never invoke an application tool")
                .isZero();
    }

    private static Stream<Seed> seeds() {
        return SEED_RESOURCES.stream().flatMap(resource -> readSeeds(resource).stream());
    }

    private static List<Seed> readSeeds(String resourceName) {
        try (var input = McpHeaderEnvelopeCharacterizationTest.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Missing characterization resource " + resourceName);
            }
            try (var reader = new BufferedReader(new InputStreamReader(input, UTF_8))) {
                return reader.lines()
                        .filter(line -> !line.isBlank())
                        .map(Seed::fromJson)
                        .toList();
            }
        } catch (IOException unreadable) {
            throw new IllegalStateException("Cannot read characterization resource " + resourceName, unreadable);
        }
    }

    private record Classification(int status, Integer errorCode) {}

    private static McpToolInvoker seedTool(AtomicInteger invocationCount) {
        McpToolDescriptor descriptor = new McpToolDescriptor(
                "seed.tool",
                null,
                "T030 invocation canary",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\",\"additionalProperties\":false}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
        return new McpToolInvoker() {
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
                        invocationCount.incrementAndGet();
                        return Future.succeededFuture(McpToolResult.text("unexpected invocation"));
                    }
                };
            }
        };
    }

    private record Seed(
            String id,
            String body,
            MultiMap headers,
            int expectedStatus,
            Integer expectedCode,
            int expectedInterceptorInvocations) {

        private static Seed fromJson(String line) {
            JsonObject json = new JsonObject(line);
            MultiMap headers = MultiMap.caseInsensitiveMultiMap();
            JsonObject encodedHeaders = json.getJsonObject("headers");
            // Repair task R33 defect 1: a header value encoded as a JSON array (rather than the usual
            // plain string) represents a single header name sent with more than one value on the wire —
            // MultiMap#add appends each one as its own entry instead of MultiMap#set overwriting the
            // header, so headerMatches's own MultiMap#getAll(String) sees the same duplication a real
            // client's repeated header line would produce.
            encodedHeaders.fieldNames().forEach(name -> {
                Object value = encodedHeaders.getValue(name);
                if (value instanceof JsonArray values) {
                    values.forEach(entry -> headers.add(name, (String) entry));
                } else {
                    headers.set(name, (String) value);
                }
            });
            return new Seed(
                    json.getString("id"),
                    json.getString("body"),
                    headers,
                    json.getInteger("expectedStatus"),
                    json.getInteger("expectedCode"),
                    json.getInteger("expectedInterceptorInvocations"));
        }

        @Override
        public String toString() {
            return id;
        }
    }

    private static final class RecordingInterceptor implements McpRequestInterceptor {
        private int invocations;

        @Override
        public ExtensionPhase phase() {
            return ExtensionPhase.APPLICATION;
        }

        @Override
        public Future<Void> beforeRequest(McpRequestContext context) {
            invocations++;
            return Future.succeededFuture();
        }

        int invocations() {
            return invocations;
        }
    }

    /** Owns the invariant dispatcher and mocked Vert.x transport wiring for one seed classification. */
    private static final class McpHeaderEnvelopeCharacterizationTestFixture {
        private final AtomicInteger toolInvocations = new AtomicInteger();
        private final RecordingInterceptor interceptor = new RecordingInterceptor();
        private final McpRequestDispatcher dispatcher;

        private McpHeaderEnvelopeCharacterizationTestFixture() {
            SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
            when(securityRuntime.current()).thenReturn(SecurityContexts.unauthenticated(SecurityIdentity.anonymous()));
            dispatcher = new McpRequestDispatcher(
                    McpServerConfig.defaults(),
                    securityRuntime,
                    Set.of(),
                    Set.of(),
                    Set.of(interceptor),
                    Set.of(),
                    HttpConfig.builder().build(),
                    McpToolRegistry.build(Set.of(seedTool(toolInvocations))),
                    mock(McpPolicyEnforcer.class),
                    NO_OP_CONTEXT_HOLDER,
                    new CorrelationContextFactory(Optional.empty()));
        }

        private Classification classify(Seed seed) {
            RoutingContext context = mock(RoutingContext.class);
            HttpServerRequest request = mock(HttpServerRequest.class);
            HttpServerResponse response = mock(HttpServerResponse.class);
            RequestBody requestBody = mock(RequestBody.class);
            when(context.request()).thenReturn(request);
            when(context.response()).thenReturn(response);
            when(context.body()).thenReturn(requestBody);
            when(requestBody.buffer()).thenReturn(Buffer.buffer(seed.body(), UTF_8.name()));
            when(request.headers()).thenReturn(seed.headers());
            when(response.putHeader(anyString(), anyString())).thenReturn(response);
            when(response.setStatusCode(anyInt())).thenReturn(response);
            when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());

            dispatcher.dispatch(context);

            ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Buffer> body = ArgumentCaptor.forClass(Buffer.class);
            verify(response).setStatusCode(status.capture());
            verify(response).end(body.capture());
            JsonObject payload = new JsonObject(body.getValue());
            JsonObject error = payload.getJsonObject("error");
            return new Classification(status.getValue(), error == null ? null : error.getInteger("code"));
        }

        private int interceptorInvocations() {
            return interceptor.invocations();
        }

        private int toolInvocations() {
            return toolInvocations.get();
        }
    }

    private static final ContextHolder NO_OP_CONTEXT_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return ContextScopes.noop();
        }
    };
}
