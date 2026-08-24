// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextScopes;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.server.support.McpJsonTokenCorpus;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationDecision;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** T033's fixed characterization of configured, single-pass result encoding. */
class McpResultEncodingCharacterizationTest {

    private static final String SEED_RESOURCE = "/mcp/characterization/result-seeds.jsonl";
    private static final String RESULT_CORPUS_CONSUMER = "result-characterization";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String TOOL_NAME = "characterization.result";
    private static final String SSE_PREFIX = "event: message\ndata: ";
    private static final String SSE_SUFFIX = "\n\n";
    private static final long ASYNC_TIMEOUT_SECONDS = 5;
    private static final JsonMapper VALUE_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
    private static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void closeVertx() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("seeds")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("T033: every result seed retains its configured bounded encoding")
    void shouldEncodeEverySeedWithinTheOutputCap(Seed seed) throws Exception {
        // Given: one literal result seed and the shared structural value it references, when any.
        var fixture = McpResultEncodingCharacterizationTestFixture.start(seed, vertx);
        // When: the real dispatcher encodes the tool result exactly once.
        Encoded actual = fixture.encode();

        // Then: status, full framed bytes, complete JSON-RPC shape, and configured JSON payload
        // bound all retain their pinned characterization.
        EncodingPin actualPin =
                new EncodingPin(actual.status(), actual.payload().length, actual.body().length, sha256(actual.body()));
        assertThat(actualPin).isEqualTo(seed.expectedPin());
        assertThat(actual.payload().length).isLessThanOrEqualTo(seed.outputMaxBytes());
        assertThat(actual.rawSerializationAttempts()).isEqualTo(seed.expectedRawSerializationAttempts());
        assertThat(actual.responseWrites()).isOne();

        JsonObject envelope = new JsonObject(Buffer.buffer(actual.payload()));
        if (seed.expectedShape() == ExpectedShape.RESULT) {
            assertThat(envelope.containsKey("error")).isFalse();
            assertThat(envelope.getJsonObject("result").getString("resultType")).isEqualTo("complete");
            if (seed.id().equals("structured-numeric-fidelity")) {
                assertThat(new String(actual.payload(), UTF_8))
                        .contains(new String(corpusRow(seed.value()).renderedUtf8(), UTF_8));
            }
        } else {
            assertThat(envelope.containsKey("result")).isFalse();
            assertThat(envelope.getJsonObject("error").getInteger("code")).isEqualTo(-32603);
        }
    }

    private static Stream<Seed> seeds() {
        return readSeeds().stream();
    }

    private static List<Seed> readSeeds() {
        try (var input = McpResultEncodingCharacterizationTest.class.getResourceAsStream(SEED_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing characterization resource " + SEED_RESOURCE);
            }
            try (var reader = new BufferedReader(new InputStreamReader(input, UTF_8))) {
                return reader.lines()
                        .filter(line -> !line.isBlank())
                        .map(Seed::fromJson)
                        .toList();
            }
        } catch (IOException unreadable) {
            throw new IllegalStateException("Cannot read characterization resource " + SEED_RESOURCE, unreadable);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError("SHA-256 is required by the JDK", unavailable);
        }
    }

    private static McpJsonTokenCorpus.Row corpusRow(String id) {
        return McpJsonTokenCorpus.rowsForConsumer(RESULT_CORPUS_CONSUMER).stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing T033 shared corpus row " + id));
    }

    private enum ResultKind {
        TEXT,
        ERROR,
        CORPUS,
        STRUCTURED_LITERAL
    }

    private enum ExpectedShape {
        RESULT,
        ERROR
    }

    private record Seed(
            String id,
            ResultKind kind,
            String value,
            int outputMaxBytes,
            int outputMaxTokens,
            int expectedStatus,
            int expectedPayloadBytes,
            int expectedBodyBytes,
            String expectedBodySha256,
            int expectedRawSerializationAttempts,
            ExpectedShape expectedShape) {

        private static Seed fromJson(String line) {
            JsonObject json = new JsonObject(line);
            return new Seed(
                    json.getString("id"),
                    ResultKind.valueOf(json.getString("kind")),
                    json.getString("value"),
                    json.getInteger("outputMaxBytes"),
                    json.getInteger("outputMaxTokens"),
                    json.getInteger("expectedStatus"),
                    json.getInteger("expectedPayloadBytes"),
                    json.getInteger("expectedBodyBytes"),
                    json.getString("expectedBodySha256"),
                    json.getInteger("expectedRawSerializationAttempts"),
                    ExpectedShape.valueOf(json.getString("expectedShape")));
        }

        @Override
        public String toString() {
            return id;
        }

        private EncodingPin expectedPin() {
            return new EncodingPin(expectedStatus, expectedPayloadBytes, expectedBodyBytes, expectedBodySha256);
        }
    }

    private record EncodingPin(int status, int payloadBytes, int bodyBytes, String bodySha256) {}

    private record Encoded(int status, byte[] body, byte[] payload, int rawSerializationAttempts, int responseWrites) {}

    /** Owns one mocked transport around the real dispatcher and one task-owned Vert.x context. */
    private static final class McpResultEncodingCharacterizationTestFixture {
        private final Vertx vertx;
        private final AtomicInteger rawSerializationAttempts = new AtomicInteger();
        private final AtomicInteger responseWrites = new AtomicInteger();
        private final AtomicReference<Buffer> responseBody = new AtomicReference<>();
        private final AtomicReference<Integer> responseStatus = new AtomicReference<>();
        private final CountDownLatch responseEnded = new CountDownLatch(1);
        private final McpRequestDispatcher dispatcher;
        private final RoutingContext context;

        private McpResultEncodingCharacterizationTestFixture(Seed seed, Vertx vertx) throws Exception {
            this.vertx = vertx;
            McpToolResult<?> result = result(seed);
            McpServerConfig config = McpServerConfig.builder()
                    .serverName("vertique-characterization")
                    .serverVersion("1.0")
                    .outputMaxBytes(seed.outputMaxBytes())
                    .outputMaxTokens(seed.outputMaxTokens())
                    .build();
            McpPolicyEnforcer policyEnforcer = mock(McpPolicyEnforcer.class);
            when(policyEnforcer.decide(any(), any()))
                    .thenReturn(Future.succeededFuture(AuthorizationDecision.permit("PERMITTED")));
            SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
            SecurityContext anonymous = SecurityContexts.unauthenticated(SecurityIdentity.anonymous());
            when(securityRuntime.current()).thenReturn(anonymous);
            dispatcher = new McpRequestDispatcher(
                    config,
                    securityRuntime,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    HttpConfig.builder().build(),
                    McpToolRegistry.build(Set.of(new FixedResultTool(result))),
                    policyEnforcer,
                    NO_OP_CONTEXT_HOLDER,
                    new CorrelationContextFactory(Optional.empty()));
            context = routingContext();
            new RequestContextLifecycle().handle(context);
            dispatcher.begin(context);
        }

        private static McpResultEncodingCharacterizationTestFixture start(Seed seed, Vertx vertx) throws Exception {
            return new McpResultEncodingCharacterizationTestFixture(seed, vertx);
        }

        private Encoded encode() throws Exception {
            dispatcher.dispatch(context);
            assertThat(responseEnded.await(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("the dispatcher must settle the mocked response")
                    .isTrue();
            byte[] body = responseBody.get().getBytes();
            String framed = new String(body, UTF_8);
            assertThat(framed).startsWith(SSE_PREFIX).endsWith(SSE_SUFFIX);
            byte[] payload = framed.substring(SSE_PREFIX.length(), framed.length() - SSE_SUFFIX.length())
                    .getBytes(UTF_8);
            return new Encoded(
                    responseStatus.get(), body, payload, rawSerializationAttempts.get(), responseWrites.get());
        }

        private McpToolResult<?> result(Seed seed) throws Exception {
            return switch (seed.kind()) {
                case TEXT -> McpToolResult.text(seed.value());
                case ERROR -> McpToolResult.error(seed.value());
                case STRUCTURED_LITERAL ->
                    McpToolResult.structured(new CountingStructuredValue(seed.value(), rawSerializationAttempts));
                case CORPUS ->
                    McpToolResult.structured(
                            new CountingStructuredValue(corpusValue(seed.value()), rawSerializationAttempts));
            };
        }

        private static Object corpusValue(String id) throws Exception {
            return VALUE_MAPPER.readValue(corpusRow(id).renderedUtf8(), Object.class);
        }

        private RoutingContext routingContext() {
            RoutingContext routingContext = mock(RoutingContext.class);
            io.vertx.core.Vertx contextVertx = mock(io.vertx.core.Vertx.class);
            HttpServerRequest request = mock(HttpServerRequest.class);
            HttpServerResponse response = mock(HttpServerResponse.class);
            RequestBody requestBody = mock(RequestBody.class);
            Map<Object, Object> attributes = new HashMap<>();
            JsonObject body = callBody();

            when(routingContext.vertx()).thenReturn(contextVertx);
            when(contextVertx.getOrCreateContext()).thenReturn(vertx.getOrCreateContext());
            when(routingContext.request()).thenReturn(request);
            when(routingContext.response()).thenReturn(response);
            when(routingContext.body()).thenReturn(requestBody);
            when(requestBody.buffer()).thenReturn(body.toBuffer());
            when(request.headers()).thenReturn(headers());
            when(response.putHeader(anyString(), anyString())).thenReturn(response);
            when(response.setStatusCode(anyInt())).thenAnswer(invocation -> {
                responseStatus.set(invocation.getArgument(0));
                return response;
            });
            when(response.end(any(Buffer.class))).thenAnswer(invocation -> {
                responseWrites.incrementAndGet();
                responseBody.set(invocation.getArgument(0));
                responseEnded.countDown();
                return Future.succeededFuture();
            });
            when(response.end()).thenReturn(Future.succeededFuture());
            when(response.closeHandler(any())).thenReturn(response);
            when(response.exceptionHandler(any())).thenReturn(response);
            when(routingContext.put(anyString(), any())).thenAnswer(invocation -> {
                attributes.put(invocation.getArgument(0), invocation.getArgument(1));
                return routingContext;
            });
            when(routingContext.get(anyString())).thenAnswer(invocation -> attributes.get(invocation.getArgument(0)));
            return routingContext;
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
                                                    .put(
                                                            "io.modelcontextprotocol/clientCapabilities",
                                                            new JsonObject()))
                                    .put("name", TOOL_NAME)
                                    .put("arguments", new JsonObject()));
        }

        private static MultiMap headers() {
            return MultiMap.caseInsensitiveMultiMap()
                    .set("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .set("Mcp-Method", "tools/call")
                    .set("Mcp-Name", TOOL_NAME);
        }
    }

    /** Exposes one Jackson value while counting visits to the original application object. */
    private record CountingStructuredValue(Object value, AtomicInteger serializationAttempts) {
        @JsonValue
        Object jsonValue() {
            serializationAttempts.incrementAndGet();
            return value;
        }
    }

    private record FixedResultTool(McpToolResult<?> result) implements McpToolInvoker {
        private static final McpToolDescriptor DESCRIPTOR = new McpToolDescriptor(
                TOOL_NAME,
                null,
                "Result-encoding characterization tool.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\",\"additionalProperties\":false}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));

        @Override
        public McpToolDescriptor descriptor() {
            return DESCRIPTOR;
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
                    return Future.succeededFuture(result);
                }
            };
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
