// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.sanitization.Canonicalize;
import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.json.JacksonFieldNameResolver;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpOutcome;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.lifecycle.McpResultType;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpBeanValidation;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpInputRejectionException;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T015 TP-001 — real port-0 contract matrix for the fixed request-time input pipeline (contract
 * §4.7): the precompiled schema validator (T009), INP-001 canonicalization/sanitization at {@code
 * InputLocation.PAYLOAD}, materialization through the effective mapper, and Bean Validation, in that
 * fixed order, failing closed at the first authoritative stage without ever invoking the handler.
 *
 * <p>Exposes one record-argument tool ({@code Args}, carrying a {@code @NotBlank} component and a
 * polymorphic {@code Contact} member) whose hand-written {@link PipelineToolInvoker} stands in for a
 * real generated invoker's {@code prepare()} exactly as {@code McpPreparedToolCallTestFixture}'s
 * {@code GeneratedInvoker} did for T014 — {@code prepare()} is the generated fixed input boundary and
 * runs stages 2-4 for real through the same production {@link InputObjectProcessor}, {@link
 * ObjectMapper}, and {@link McpBeanValidation#validate} a real generated invoker would use; only
 * {@link McpRequestDispatcher} (stage 1, schema validation before {@code prepare()} is ever called) is
 * this task's own production change.
 *
 * <p>A real server, bound and connected on the literal {@code 127.0.0.1} (never {@code listen(0)},
 * never {@code "localhost"} — see {@code docs/standards/testing.md} § Dynamic Port Allocation).
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpInputPipelineIT {

    private static final String SHOULD_INVOKE_VALID = "shouldInvokeOnAValidCall";
    private static final String SHOULD_REJECT_UNKNOWN_PROPERTIES = "shouldRejectUnknownPropertiesBeforeInvocation";
    private static final String SHOULD_REJECT_UNKNOWN_DISCRIMINATOR =
            "shouldRejectUnknownPolymorphicDiscriminatorBeforeInvocation";
    private static final String SHOULD_REJECT_SANITIZATION_FAILURE = "shouldRejectSanitizationFailureBeforeInvocation";
    private static final String SHOULD_REJECT_BEAN_VALIDATION_FAILURE =
            "shouldRejectBeanValidationFailureBeforeInvocation";

    private static final String LOOPBACK = "127.0.0.1";
    private static final String REQUEST_PATH = "/mcp/";
    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String SSE_PREFIX = "event: message\ndata: ";
    private static final String TOOL_NAME = "pipeline.tool";

    private static final String SCHEMA_MESSAGE = "Invalid tool arguments: schema validation failed";
    private static final String SANITIZATION_MESSAGE = "Invalid tool arguments: input sanitization failed";
    private static final String BEAN_VALIDATION_MESSAGE = "Invalid tool arguments: constraint validation failed";
    private static final String BLOCKED_NAME = "<script>";

    private final Vertx vertx = Vertx.vertx();

    private Fixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    private static Stream<String> t015ContractRows() {
        return Stream.of(
                SHOULD_INVOKE_VALID,
                SHOULD_REJECT_UNKNOWN_PROPERTIES,
                SHOULD_REJECT_UNKNOWN_DISCRIMINATOR,
                SHOULD_REJECT_SANITIZATION_FAILURE,
                SHOULD_REJECT_BEAN_VALIDATION_FAILURE);
    }

    @AfterEach
    void tearDown() throws Exception {
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose)
                .compose(ignored -> vertx.close())
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        fixture = null;
        server = null;
        rawClient = null;
        client = null;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t015ContractRows")
    @DisplayName("T015 input pipeline matrix: fixed stage order, fail-closed, zero invocation on rejection")
    void shouldEnforceT015ContractMatrix(String row) throws Exception {
        startServer();
        PipelineToolInvoker tool = fixture.tool();

        switch (row) {
            case SHOULD_INVOKE_VALID -> {
                // Given/When: a schema-valid, sanitization-clean, non-blank argument tree.
                JsonObject arguments = new JsonObject()
                        .put("name", "Ada Lovelace")
                        .put("contact", new JsonObject().put("type", "email").put("address", "ada@example.com"));
                HttpResponse<Buffer> response = await(callTool(arguments, 1));

                // Then: the call invokes exactly once, and every stage genuinely ran.
                assertThat(response.statusCode()).isEqualTo(200);
                JsonObject result = sseResult(response.bodyAsString());
                assertThat(result.getBoolean("isError")).isFalse();
                assertThat(tool.invocationCount())
                        .as("DECISIVE: the counter every rejection row's zero-invocation claim rests on must "
                                + "itself be provably live")
                        .isEqualTo(1);
                assertThat(tool.prepareCallCount()).isEqualTo(1);
                assertThat(tool.sanitizationAttempted()).isEqualTo(1);
                assertThat(tool.materializationAttempted()).isEqualTo(1);
                assertThat(tool.beanValidationAttempted()).isEqualTo(1);
            }
            case SHOULD_REJECT_UNKNOWN_PROPERTIES -> {
                // Given/When: schema-valid contents plus one undeclared root property.
                JsonObject arguments = new JsonObject()
                        .put("name", "Ada Lovelace")
                        .put("contact", new JsonObject().put("type", "email").put("address", "ada@example.com"))
                        .put("extra", "not declared");
                HttpResponse<Buffer> response = await(callTool(arguments, 2));

                // Then: rejected at stage 1 — prepare() itself is never called, so every later-stage
                // counter stays at zero without the pipeline having had any chance to run them.
                assertThat(response.statusCode()).isEqualTo(200);
                assertBoundedTextOnlyError(sseResult(response.bodyAsString()), SCHEMA_MESSAGE);
                assertThat(tool.prepareCallCount())
                        .as("DECISIVE: stage 1 must reject before the generated fixed input boundary "
                                + "(prepare()) is ever entered")
                        .isZero();
                assertThat(tool.invocationCount()).isZero();
                assertStage1TerminalClassification(fixture);
            }
            case SHOULD_REJECT_UNKNOWN_DISCRIMINATOR -> {
                // Given/When: a polymorphic member whose discriminator names no declared subtype.
                JsonObject arguments = new JsonObject()
                        .put("name", "Ada Lovelace")
                        .put("contact", new JsonObject().put("type", "fax").put("number", "000"));
                HttpResponse<Buffer> response = await(callTool(arguments, 3));

                // Then: the withdrawn startup polymorphism check's legitimate request-time replacement
                // (contract "Carried obligations") — rejected at stage 1, prepare() never called.
                assertThat(response.statusCode()).isEqualTo(200);
                assertBoundedTextOnlyError(sseResult(response.bodyAsString()), SCHEMA_MESSAGE);
                assertThat(tool.prepareCallCount())
                        .as("DECISIVE: an unknown polymorphic discriminator must fail schema validation "
                                + "before prepare() is ever entered")
                        .isZero();
                assertThat(tool.invocationCount()).isZero();
                assertStage1TerminalClassification(fixture);
            }
            case SHOULD_REJECT_SANITIZATION_FAILURE -> {
                // Given/When: schema-valid, but the name carries a value the tool's declared @Sanitize
                // chain rejects.
                JsonObject arguments = new JsonObject()
                        .put("name", BLOCKED_NAME)
                        .put("contact", new JsonObject().put("type", "email").put("address", "ada@example.com"));
                HttpResponse<Buffer> response = await(callTool(arguments, 4));

                // Then: stage 1 passed (prepare() was entered) and stage 2 (INP-001 sanitization) itself
                // rejected — DECISIVE: materialization and Bean Validation were never attempted after it,
                // and the handler never ran.
                assertThat(response.statusCode()).isEqualTo(200);
                assertBoundedTextOnlyError(sseResult(response.bodyAsString()), SANITIZATION_MESSAGE);
                assertThat(tool.prepareCallCount()).isEqualTo(1);
                assertThat(tool.sanitizationAttempted()).isEqualTo(1);
                assertThat(tool.materializationAttempted())
                        .as("DECISIVE: materialization must never be attempted after sanitization rejected")
                        .isZero();
                assertThat(tool.beanValidationAttempted()).isZero();
                assertThat(tool.invocationCount()).isZero();
                assertStages2To4TerminalClassification(fixture);
            }
            case SHOULD_REJECT_BEAN_VALIDATION_FAILURE -> {
                // Given/When: schema-valid and sanitization-clean, but the materialized name is blank
                // once INP-001 canonicalization trims it — @NotBlank rejects it only after materialization.
                JsonObject arguments = new JsonObject()
                        .put("name", "   ")
                        .put("contact", new JsonObject().put("type", "email").put("address", "ada@example.com"));
                HttpResponse<Buffer> response = await(callTool(arguments, 5));

                // Then: stages 1-3 all ran (schema, sanitization, materialization) — DECISIVE: Bean
                // Validation was reached and itself rejected, and the handler still never ran despite
                // materialization having succeeded.
                assertThat(response.statusCode()).isEqualTo(200);
                assertBoundedTextOnlyError(sseResult(response.bodyAsString()), BEAN_VALIDATION_MESSAGE);
                assertThat(tool.prepareCallCount()).isEqualTo(1);
                assertThat(tool.sanitizationAttempted()).isEqualTo(1);
                assertThat(tool.materializationAttempted()).isEqualTo(1);
                assertThat(tool.beanValidationAttempted())
                        .as("DECISIVE: Bean Validation must have been reached and itself have rejected")
                        .isEqualTo(1);
                assertThat(tool.invocationCount())
                        .as("DECISIVE: the handler must never run despite materialization having succeeded")
                        .isZero();
                assertStages2To4TerminalClassification(fixture);
            }
            default -> fail("unknown T015 input pipeline matrix row: " + row);
        }
    }

    /**
     * DECISIVE (P04 remediation, issue W2): a stage 1 (schema) rejection must record {@link
     * McpOutcome#TOOL_ERROR}/{@link McpErrorType#INPUT_VALIDATION}/{@link McpResultType#COMPLETE} on
     * the terminal event — never {@link McpErrorType#HANDLER}, which would misclassify a rejection no
     * handler ever ran as a genuine handler fault. The wire body is identical across every rejection
     * stage in this matrix; only the terminal event's own facts distinguish them.
     */
    private static void assertStage1TerminalClassification(Fixture fixture) {
        McpRequestTerminalEvent terminal = fixture.terminals().getLast();
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.TOOL_ERROR);
        assertThat(terminal.errorType())
                .as("DECISIVE: a stage 1 schema rejection must classify as INPUT_VALIDATION, never HANDLER")
                .isEqualTo(McpErrorType.INPUT_VALIDATION);
        assertThat(terminal.resultType()).isEqualTo(McpResultType.COMPLETE);
    }

    /**
     * DECISIVE (P04 remediation, issue W2): a stages 2-4 ({@link McpInputRejectionException}) rejection
     * must record {@link McpOutcome#TOOL_ERROR}/{@link McpErrorType#INPUT_PROCESSING}/{@link
     * McpResultType#COMPLETE} on the terminal event — never {@link McpErrorType#HANDLER}.
     */
    private static void assertStages2To4TerminalClassification(Fixture fixture) {
        McpRequestTerminalEvent terminal = fixture.terminals().getLast();
        assertThat(terminal.outcome()).isEqualTo(McpOutcome.TOOL_ERROR);
        assertThat(terminal.errorType())
                .as("DECISIVE: a stages 2-4 input-processing rejection must classify as INPUT_PROCESSING, "
                        + "never HANDLER")
                .isEqualTo(McpErrorType.INPUT_PROCESSING);
        assertThat(terminal.resultType()).isEqualTo(McpResultType.COMPLETE);
    }

    // --- Wire helpers ---

    private void startServer() throws Exception {
        fixture = Fixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
    }

    private Future<HttpResponse<Buffer>> callTool(JsonObject arguments, Object id) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        JsonObject params =
                new JsonObject().put("_meta", meta).put("name", TOOL_NAME).put("arguments", arguments);
        JsonObject body = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", "tools/call")
                .put("params", params);
        return client.post(fixture.port(), LOOPBACK, REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "tools/call")
                .putHeader("Mcp-Name", TOOL_NAME)
                .sendBuffer(body.toBuffer());
    }

    /** Extracts and parses the JSON payload framed by the frozen {@code event: message}/{@code data:} block. */
    private static JsonObject sseData(String rawBody) {
        assertThat(rawBody)
                .as("a schema- or pipeline-rejected call still committed to SSE framing before prepare() ran")
                .startsWith(SSE_PREFIX);
        return new JsonObject(rawBody.substring(SSE_PREFIX.length()).stripTrailing());
    }

    private static JsonObject sseResult(String rawBody) {
        JsonObject result = sseData(rawBody).getJsonObject("result");
        assertThat(result)
                .as("every row here settles as a CallToolResult, never a JSON-RPC error")
                .isNotNull();
        return result;
    }

    /**
     * Asserts the exact bounded text-only {@code isError=true} shape §4.7 requires for a pipeline
     * rejection: one text content item carrying {@code expectedMessage}, no structured content. The
     * same assertion runs for every rejection row, so the shape itself — not merely "some error came
     * back" — is compared across rows.
     */
    private static void assertBoundedTextOnlyError(JsonObject result, String expectedMessage) {
        assertThat(result.getBoolean("isError"))
                .as("a pipeline rejection is a tool-error result")
                .isTrue();
        JsonArray content = result.getJsonArray("content");
        assertThat(content.size()).as("bounded: exactly one content item").isEqualTo(1);
        JsonObject item = content.getJsonObject(0);
        assertThat(item.getString("type")).isEqualTo("text");
        assertThat(item.getString("text")).isEqualTo(expectedMessage);
        assertThat(result.containsKey("structuredContent"))
                .as("text-only: no structured content")
                .isFalse();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // --- Fixture ---

    /**
     * Builds and starts one real MCP mount with one anonymous-only, {@code PERMIT_ALL},
     * record-argument tool: {@link PipelineToolInvoker} stands in for a real generated invoker's
     * {@code prepare()}, running stages 2-4 for real through production {@link InputObjectProcessor},
     * {@link ObjectMapper}, and {@link McpBeanValidation#validate} instances (T015 owns the request-time
     * pipeline; no reflection, no interceptor stage — matching T014's precedent).
     */
    private static final class Fixture {

        private final HttpServer server;
        private final int port;
        private final PipelineToolInvoker tool;
        private final List<McpRequestTerminalEvent> terminals = new java.util.concurrent.CopyOnWriteArrayList<>();

        private Fixture(Vertx vertx) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .build();

            ObjectMapper mapper = new ObjectMapper();
            InputObjectProcessor processor = InputObjectProcessor.createDefault(
                    canonicalizerType -> {
                        if (canonicalizerType == TrimCanonicalizer.class) {
                            return new TrimCanonicalizer();
                        }
                        throw new IllegalArgumentException("unresolvable canonicalizer " + canonicalizerType);
                    },
                    sanitizerType -> {
                        if (sanitizerType == RejectingSanitizer.class) {
                            return new RejectingSanitizer();
                        }
                        throw new IllegalArgumentException("unresolvable sanitizer " + sanitizerType);
                    });

            McpToolDescriptor descriptor = new McpToolDescriptor(
                    TOOL_NAME,
                    null,
                    "T015 fixture tool " + TOOL_NAME + ".",
                    new McpToolAnnotations(true, false, true, false),
                    INPUT_SCHEMA,
                    null,
                    new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
            this.tool = new PipelineToolInvoker(descriptor, processor, mapper);
            McpToolRegistry registry = McpToolRegistry.build(Set.of(tool));

            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
            McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                    Optional.empty(),
                    Optional.empty(),
                    Set.of(),
                    new SecurityEventEmitter(Set.of()),
                    NO_OP_CONTEXT_HOLDER,
                    securityRuntime,
                    Optional.empty()));
            HttpConfig httpConfig = HttpConfig.builder().idleTimeoutSeconds(60).build();

            McpRouterMount mount = new McpRouterMount(
                    config,
                    new McpServerConfigValidator(),
                    new McpRequestDispatcher(
                            config,
                            securityRuntime,
                            Set.of(recordingObserver()),
                            Set.of(),
                            Set.of(),
                            Set.of(),
                            httpConfig,
                            registry,
                            policyEnforcer,
                            NO_OP_CONTEXT_HOLDER,
                            new CorrelationContextFactory(Optional.empty())),
                    Set.of(),
                    identityResolution(securityRuntime),
                    httpConfig,
                    registry);
            Router router = Router.router(vertx);
            router.route().handler(new RequestContextLifecycle());
            router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
            this.server = await(vertx.createHttpServer().requestHandler(router).listen(0, LOOPBACK));
            this.port = server.actualPort();
        }

        static Fixture start(Vertx vertx) throws Exception {
            return new Fixture(vertx);
        }

        HttpServer server() {
            return server;
        }

        int port() {
            return port;
        }

        PipelineToolInvoker tool() {
            return tool;
        }

        /** Every terminal event this fixture's dispatcher has published so far, in publish order. */
        List<McpRequestTerminalEvent> terminals() {
            return terminals;
        }

        private McpRequestLifecycleObserver recordingObserver() {
            return startedAt -> new McpRequestObservation() {
                @Override
                public void onTerminal(McpRequestTerminalObservation observation) {
                    terminals.add(observation.event());
                }
            };
        }

        private static IdentityResolutionMiddleware identityResolution(SecurityRuntime securityRuntime) {
            return new IdentityResolutionMiddleware(
                    Set.of(new AnonymousIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    new SecurityEventEmitter(Set.of()),
                    securityRuntime,
                    NO_OP_CONTEXT_HOLDER);
        }

        private static <T> T await(Future<T> future) throws Exception {
            return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Stands in for one {@code @McpTool}-generated invoker's {@code prepare()}: the generated fixed
     * input boundary that owns stages 2-4 (contract §4.2/§4.7) — INP-001 canonicalization/sanitization
     * at {@code InputLocation.PAYLOAD}, materialization through the effective mapper, and Bean
     * Validation — exactly as {@code McpPreparedToolCallTestFixture}'s {@code GeneratedInvoker} stood
     * in for T014's boundary. Every stage counter here is incremented at the point that stage is
     * genuinely attempted, so a test reading it has proof the stage ran (or did not), never an assertion
     * that merely could not fail.
     */
    private static final class PipelineToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor;
        private final InputObjectProcessor processor;
        private final ObjectMapper mapper;
        private final InputFieldNameResolver resolver;
        private final AtomicInteger prepareCallCount = new AtomicInteger();
        private final AtomicInteger sanitizationAttempted = new AtomicInteger();
        private final AtomicInteger materializationAttempted = new AtomicInteger();
        private final AtomicInteger beanValidationAttempted = new AtomicInteger();
        private final AtomicInteger invocationCount = new AtomicInteger();

        PipelineToolInvoker(McpToolDescriptor descriptor, InputObjectProcessor processor, ObjectMapper mapper) {
            this.descriptor = descriptor;
            this.processor = processor;
            this.mapper = mapper;
            this.resolver = JacksonFieldNameResolver.forMapper(mapper);
            // Composition-time postcondition (InputObjectProcessor#precomputeFieldNameResolution): every
            // statically knowable owner is prepared before any request reaches processInput.
            processor.precomputeFieldNameResolution(Args.class, resolver);
        }

        @Override
        public McpToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            prepareCallCount.incrementAndGet();

            // Stage 2 — INP-001 canonicalization/sanitization at InputLocation.PAYLOAD (contract §4.7
            // point 2): the generated invocation-level literal is always EffectiveInputPolicies.NONE
            // (§4.1) — declared per-field policies are discovered reflectively from the carrier's own
            // annotations, not passed as invocation-level policies.
            sanitizationAttempted.incrementAndGet();
            Object processed;
            try {
                processed = processor.processInput(
                        arguments, Args.class, EffectiveInputPolicies.NONE, InputLocation.PAYLOAD, resolver);
            } catch (RuntimeException sanitizationFailure) {
                throw new McpInputRejectionException(
                        "Invalid tool arguments: input sanitization failed", sanitizationFailure);
            }

            // Stage 3 — materialization through the effective mapper (contract §4.7 point 3).
            materializationAttempted.incrementAndGet();
            Args materialized;
            try {
                materialized = mapper.convertValue(processed, Args.class);
            } catch (RuntimeException materializationFailure) {
                throw new McpInputRejectionException(
                        "Invalid tool arguments: materialization failed", materializationFailure);
            }

            // Stage 4 — Bean Validation on the materialized carrier (contract §4.7 point 4).
            beanValidationAttempted.incrementAndGet();
            if (!McpBeanValidation.validate(materialized, Optional.empty()).isEmpty()) {
                throw new McpInputRejectionException("Invalid tool arguments: constraint validation failed");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> normalized = Map.copyOf((Map<String, Object>) processed);
            Args validated = materialized;
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return normalized;
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    invocationCount.incrementAndGet();
                    return Future.succeededFuture(McpToolResult.text("ok: " + validated.name()));
                }
            };
        }

        int prepareCallCount() {
            return prepareCallCount.get();
        }

        int sanitizationAttempted() {
            return sanitizationAttempted.get();
        }

        int materializationAttempted() {
            return materializationAttempted.get();
        }

        int beanValidationAttempted() {
            return beanValidationAttempted.get();
        }

        int invocationCount() {
            return invocationCount.get();
        }
    }

    // --- Tool argument shapes ---

    /**
     * The record-argument tool's carrier: a {@code @NotBlank}, {@code @Canonicalize}d, {@code
     * @Sanitize}d component and a polymorphic member.
     *
     * <p>The declared canonicalization/sanitization annotations live on this top-level {@code name}
     * component rather than inside the polymorphic {@link Contact} member: the reflective
     * input-processing walker's precompute postcondition is explicit that a {@code @JsonTypeInfo}
     * subtype is not statically knowable ({@link InputObjectProcessor#precomputeFieldNameResolution}),
     * so a policy declared only inside one closed subtype could never be reliably reached before
     * materialization. The polymorphic member instead proves the schema-level closed-discriminator
     * boundary (contract "Carried obligations").
     */
    private record Args(
            @JsonProperty("name") @NotBlank @Canonicalize(TrimCanonicalizer.class) @Sanitize(RejectingSanitizer.class)
            String name,

            @JsonProperty("contact") Contact contact) {}

    /** The polymorphic member: a closed discriminator over exactly two declared subtypes. */
    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "type",
            visible = true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = EmailContact.class, name = "email"),
        @JsonSubTypes.Type(value = PhoneContact.class, name = "phone")
    })
    private interface Contact {}

    private record EmailContact(
            @JsonProperty("type") String type,
            @JsonProperty("address") String address) implements Contact {}

    private record PhoneContact(
            @JsonProperty("type") String type,
            @JsonProperty("number") String number) implements Contact {}

    /** Strips leading/trailing whitespace — proves the tool's canonicalization annotation genuinely runs. */
    private static final class TrimCanonicalizer implements Canonicalizer {
        @Override
        public String canonicalize(String value, InputValueContext context) {
            return value == null ? null : value.strip();
        }
    }

    /** Rejects exactly one literal blocked value, standing in for a real content-safety sanitizer. */
    private static final class RejectingSanitizer implements Sanitizer {
        @Override
        public String sanitize(String value, InputValueContext context) {
            if (BLOCKED_NAME.equals(value)) {
                throw new IllegalArgumentException("blocked value rejected by sanitizer");
            }
            return value;
        }
    }

    // --- Input schema (hand-authored: matches what T009's generator + hardener would produce for the
    //     Args/Contact shape above; the compiled Validator itself is always the real McpSchemaRegistry) ---

    private static final String CONTACT_SCHEMA = "{\"oneOf\":["
            + "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"type\",\"address\"],"
            + "\"properties\":{\"type\":{\"const\":\"email\"},\"address\":{\"type\":\"string\"}}},"
            + "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"type\",\"number\"],"
            + "\"properties\":{\"type\":{\"const\":\"phone\"},\"number\":{\"type\":\"string\"}}}"
            + "]}";

    private static final String INPUT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false,"
            + "\"required\":[\"name\",\"contact\"],\"properties\":{\"name\":{\"type\":\"string\"},"
            + "\"contact\":" + CONTACT_SCHEMA + "}}";

    // --- Security wiring (no configured scheme — matches McpCancellationIT's anonymous-only fixture) ---

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

    /** A {@link SecurityRuntime} that records the bound {@link SecurityContext} without asserting on it. */
    private static final class RecordingSecurityRuntime implements SecurityRuntime {
        private volatile SecurityContext bound;

        @Override
        public SecurityContext current() {
            return bound;
        }

        @Override
        public ContextHolder.Scope bindCurrent(SecurityContext context) {
            bound = context;
            return () -> {};
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }

    /** Resolves the canonical anonymous identity from empty evidence; no other credential is ever sent. */
    private record AnonymousIdentityResolver() implements SecurityIdentityResolver {

        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            if (context.evidence().isEmpty()) {
                return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
            }
            Object subject = context.evidence().get(0).safeAttributes().get("sub");
            return Future.succeededFuture(Optional.of(
                    SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, String.valueOf(subject), Map.of()))));
        }
    }
}
