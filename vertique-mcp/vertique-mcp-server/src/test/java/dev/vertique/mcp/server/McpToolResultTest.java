// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import dev.vertique.codegen.mcp.McpToolProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpResultType;
import dev.vertique.mcp.server.runtime.McpToolRuntimeFactory;
import dev.vertique.mcp.server.runtime.McpToolRuntimeFactoryTestSupport;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T019 TP-001 — the frozen complete-only result algebra contract matrix.
 *
 * <p>Rows 1-3 compile one real {@code ResultAlgebraTools} application source with the real {@link
 * McpToolProcessor}, load each generated {@code *_McpToolInvoker}, and call {@code
 * prepare(...).invoke()} directly — the same plain interface calls production {@link
 * McpRequestDispatcher} makes — to observe exactly what the generated invocation adapts a scalar, a
 * record, and a {@code Future<T>} onto: a bare {@link McpToolResult}, never a partial or
 * intermediate shape. Row 4 constructs {@link McpRequestTerminalEvent} directly through its
 * rejected/failed/cancelled factories, the only place {@link McpResultType} is decided, and proves
 * each of the three non-complete outcomes independently retains {@code NONE}.
 *
 * <p>No Vert.x context, timer, or observer is created anywhere in this class — compilation and
 * every generated invocation here settle synchronously on the calling thread (no {@code Vertx}
 * instance exists to schedule on) — so there is nothing to close in teardown.
 */
@DisplayName("MCP complete-only tool result algebra — T019 contract matrix")
class McpToolResultTest {

    private static final String TOOL_PACKAGE = "com.example.resultalgebra";
    private static final String TOOLS_FQN = TOOL_PACKAGE + ".ResultAlgebraTools";

    private static final String SCALAR_ROW = "shouldAdaptScalarAndRecordReturns";
    private static final String FUTURE_ROW = "shouldAdaptFutureReturns";
    private static final String IS_ERROR_ROW = "shouldPreserveIsErrorResults";
    private static final String TERMINAL_OUTCOME_ROW =
            "shouldRetainNoneResultTypeForRejectedFailedAndCancelledOutcomes";

    private static final Instant STARTED_AT = Instant.parse("2026-08-22T00:00:00Z");
    private static final Instant TERMINAL_AT = STARTED_AT.plusMillis(5);
    private static final int JSON_RPC_INTERNAL_ERROR = -32_603;

    private static final McpCancellationSignal NEVER_CANCELLED = new McpCancellationSignal() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public Future<Void> cancelled() {
            return Promise.<Void>promise().future();
        }
    };

    private static ProcessorTestHarness.Result compilation;
    private static Class<?> toolsClass;
    private static Object toolsInstance;

    @BeforeAll
    static void compileFixtureTools() throws Exception {
        JavaFileObject source = SourceFiles.inline(TOOLS_FQN, """
                package com.example.resultalgebra;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.tool.McpToolResult;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                public class ResultAlgebraTools {

                    @Inject
                    public ResultAlgebraTools() {}

                    @McpTool(name = "result.scalar", description = "Returns a scalar text value.")
                    public String scalar() {
                        return "scalar-text";
                    }

                    @McpTool(name = "result.record", description = "Returns a structured record value.")
                    public Measurement recordValue() {
                        return new Measurement("celsius", 21);
                    }

                    @McpTool(name = "result.future-resolved", description = "Returns a resolved future.")
                    public Future<Measurement> futureResolved() {
                        return Future.succeededFuture(new Measurement("celsius", 21));
                    }

                    @McpTool(name = "result.future-failed", description = "Returns a failed future.")
                    public Future<Measurement> futureFailed() {
                        return Future.failedFuture(new IllegalStateException("handler failure"));
                    }

                    @McpTool(name = "result.explicit-error", description = "Returns an explicit tool-error result.")
                    public McpToolResult<Void> explicitError() {
                        return McpToolResult.error("bounded error message");
                    }

                    public record Measurement(String unit, int value) {}
                }
                """);
        compilation = ProcessorTestHarness.run(new McpToolProcessor(), source);
        compilation.assertSuccess();
        toolsClass = compilation.loadGeneratedClass(TOOLS_FQN);
        toolsInstance = toolsClass.getDeclaredConstructor().newInstance();
    }

    private static Stream<String> t019ContractRows() {
        return Stream.of(SCALAR_ROW, FUTURE_ROW, IS_ERROR_ROW, TERMINAL_OUTCOME_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t019ContractRows")
    @DisplayName("enforces the T019 contract matrix")
    void shouldEnforceT019ContractMatrix(String row) throws Exception {
        switch (row) {
            case SCALAR_ROW -> shouldAdaptScalarAndRecordReturns();
            case FUTURE_ROW -> shouldAdaptFutureReturns();
            case IS_ERROR_ROW -> shouldPreserveIsErrorResults();
            case TERMINAL_OUTCOME_ROW -> shouldRetainNoneResultTypeForRejectedFailedAndCancelledOutcomes();
            default -> fail("unknown T019 contract matrix row: " + row);
        }
    }

    // --- Row 1: a plain String adapts to text-only content; any other plain T adapts to structured content ---

    private void shouldAdaptScalarAndRecordReturns() throws Exception {
        McpToolResult<?> scalarResult = invoke("scalar");
        assertThat(scalarResult.textContent())
                .as("DECISIVE: a plain String return adapts to exactly one text content item")
                .containsExactly("scalar-text");
        assertThat(scalarResult.structuredContent())
                .as("a text adaptation carries no structured content")
                .isNull();
        assertThat(scalarResult.isError()).isFalse();
        assertThat(McpRequestTerminalEvent.success(
                                STARTED_AT,
                                TERMINAL_AT,
                                McpMethod.TOOLS_CALL,
                                "result.scalar",
                                200,
                                null,
                                null,
                                null,
                                null)
                        .resultType())
                .as("a successful adaptation settles as the one completed result type")
                .isEqualTo(McpResultType.COMPLETE);

        McpToolResult<?> recordResult = invoke("recordValue");
        assertThat(recordResult.textContent())
                .as("a structured adaptation carries no text content")
                .isEmpty();
        Object structured = recordResult.structuredContent();
        assertThat(structured)
                .as("DECISIVE: a plain non-String return adapts to structured content, not text")
                .isNotNull();
        assertThat(recordComponent(structured, "unit")).isEqualTo("celsius");
        assertThat(recordComponent(structured, "value")).isEqualTo(21);
        assertThat(recordResult.isError()).isFalse();
    }

    // --- Row 2: Future<T> composes on both a resolved and a failed future ---

    private void shouldAdaptFutureReturns() throws Exception {
        McpToolResult<?> resolved = invoke("futureResolved");
        assertThat(resolved.textContent()).isEmpty();
        Object structured = resolved.structuredContent();
        assertThat(structured)
                .as("DECISIVE: a resolved Future<T> adapts its value to structured content")
                .isNotNull();
        assertThat(recordComponent(structured, "unit")).isEqualTo("celsius");
        assertThat(recordComponent(structured, "value")).isEqualTo(21);
        assertThat(resolved.isError()).isFalse();

        Throwable cause = invokeFailureCause("futureFailed");
        assertThat(cause)
                .as("DECISIVE: a failed Future<T> propagates the failure — no McpToolResult is ever produced")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("handler failure");
        assertThat(McpRequestTerminalEvent.failed(
                                STARTED_AT,
                                TERMINAL_AT,
                                McpMethod.TOOLS_CALL,
                                "result.future-failed",
                                McpErrorType.INTERNAL,
                                500,
                                JSON_RPC_INTERNAL_ERROR,
                                null,
                                null,
                                null,
                                null)
                        .resultType())
                .as("a failed invocation never reaches a completed result")
                .isEqualTo(McpResultType.NONE);
    }

    // --- Row 3: a handler-authored isError=true result is preserved exactly, not coerced ---

    private void shouldPreserveIsErrorResults() throws Exception {
        McpToolResult<?> errorResult = invoke("explicitError");
        assertThat(errorResult.isError())
                .as("DECISIVE: a handler-authored McpToolResult.error(...) is passed through untouched")
                .isTrue();
        assertThat(errorResult.textContent()).containsExactly("bounded error message");
        assertThat(errorResult.structuredContent()).isNull();
        assertThat(McpRequestTerminalEvent.toolError(
                                STARTED_AT,
                                TERMINAL_AT,
                                McpMethod.TOOLS_CALL,
                                "result.explicit-error",
                                McpErrorType.HANDLER,
                                200,
                                null,
                                null,
                                null,
                                null)
                        .resultType())
                .as("a tool execution error is still a completed result, distinct from a protocol-level failure")
                .isEqualTo(McpResultType.COMPLETE);
    }

    // --- Row 4: NONE is the only outcome for rejected, failed, and cancelled requests; PARTIAL cannot exist ---

    private void shouldRetainNoneResultTypeForRejectedFailedAndCancelledOutcomes() {
        assertThat(McpResultType.values())
                .as("DECISIVE: the enum itself declares only NONE and COMPLETE — no PARTIAL state exists to"
                        + " construct")
                .containsExactlyInAnyOrder(McpResultType.NONE, McpResultType.COMPLETE);

        McpRequestTerminalEvent rejected = McpRequestTerminalEvent.rejected(
                STARTED_AT,
                TERMINAL_AT,
                McpMethod.TOOLS_CALL,
                "result.schema-rejected",
                McpErrorType.INPUT_VALIDATION,
                200,
                null,
                null,
                null,
                null,
                null);
        assertThat(List.of(rejected))
                .as("terminal event count for the rejected outcome")
                .hasSize(1);
        assertThat(rejected.resultType()).as("a rejected outcome retains NONE").isEqualTo(McpResultType.NONE);

        McpRequestTerminalEvent failed = McpRequestTerminalEvent.failed(
                STARTED_AT,
                TERMINAL_AT,
                McpMethod.TOOLS_CALL,
                "result.future-failed",
                McpErrorType.INTERNAL,
                500,
                JSON_RPC_INTERNAL_ERROR,
                null,
                null,
                null,
                null);
        assertThat(List.of(failed))
                .as("terminal event count for the failed outcome")
                .hasSize(1);
        assertThat(failed.resultType()).as("a failed outcome retains NONE").isEqualTo(McpResultType.NONE);

        McpRequestTerminalEvent cancelled = McpRequestTerminalEvent.cancelled(
                STARTED_AT,
                TERMINAL_AT,
                McpMethod.TOOLS_CALL,
                "result.cancelled-fixture",
                McpErrorType.TRANSPORT,
                0,
                null,
                null,
                null,
                null,
                null);
        assertThat(List.of(cancelled))
                .as("terminal event count for the cancelled outcome")
                .hasSize(1);
        assertThat(cancelled.resultType())
                .as("a cancelled outcome retains NONE")
                .isEqualTo(McpResultType.NONE);
    }

    // --- Shared framework construction ---

    private static McpToolResult<?> invoke(String methodName) throws Exception {
        return await(prepareCall(methodName).invoke());
    }

    private static Throwable invokeFailureCause(String methodName) throws Exception {
        try {
            await(prepareCall(methodName).invoke());
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("expected '" + methodName + "' to fail");
    }

    private static McpPreparedToolCall prepareCall(String methodName) throws Exception {
        return loadInvoker(methodName).prepare(Map.of(), NEVER_CANCELLED);
    }

    /**
     * Loads the generated invoker for {@code methodName} and constructs it through its generated
     * {@code @Inject} constructor: the tool bean, a real {@link McpToolRuntimeFactory}, and a real
     * (policy-free) {@link InputObjectProcessor} — every {@code ResultAlgebraTools} method here is
     * zero-argument, so neither resolver function is ever actually invoked.
     */
    private static McpToolInvoker loadInvoker(String methodName) throws Exception {
        String invokerFqn = TOOLS_FQN + "_" + methodName + "_McpToolInvoker";
        Class<?> invokerClass = compilation.loadGeneratedClass(invokerFqn);
        Constructor<?> constructor = invokerClass.getDeclaredConstructor(
                toolsClass, McpToolRuntimeFactory.class, InputObjectProcessor.class, Optional.class);
        constructor.setAccessible(true);
        return (McpToolInvoker) constructor.newInstance(
                toolsInstance,
                McpToolRuntimeFactoryTestSupport.factory(),
                InputObjectProcessor.createDefault(
                        canonicalizerType -> {
                            throw new IllegalArgumentException("unresolvable canonicalizer " + canonicalizerType);
                        },
                        sanitizerType -> {
                            throw new IllegalArgumentException("unresolvable sanitizer " + sanitizerType);
                        }),
                Optional.empty());
    }

    private static McpToolResult<?> await(Future<McpToolResult<?>> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /** Reads one record component's value from a dynamically compiled record instance by name. */
    private static Object recordComponent(Object record, String name) throws Exception {
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            if (component.getName().equals(name)) {
                return component.getAccessor().invoke(record);
            }
        }
        throw new NoSuchFieldException(name);
    }
}
