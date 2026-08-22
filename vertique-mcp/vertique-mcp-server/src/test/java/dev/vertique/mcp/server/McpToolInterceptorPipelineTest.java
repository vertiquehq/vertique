// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.mcp.interceptor.McpToolInterceptor;
import dev.vertique.mcp.interceptor.McpToolInvocationContext;
import dev.vertique.mcp.lifecycle.McpMethod;
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
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationDecision;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/**
 * T017 TP-001 — the frozen tool-interceptor SPI and post-validation, pre-invocation stage contract
 * matrix.
 *
 * <p>Every row drives {@link McpRequestDispatcher#dispatch} directly against a mocked {@link
 * RoutingContext} carrying a valid zero-argument {@code tools/call} request for one fixture tool —
 * mirroring {@link McpRequestInterceptorPipelineTest}'s T016 driving style — and observes the exact
 * SSE-framed {@code CallToolResult}, the generated invocation counter, and a single shared ordered
 * list every fixture participant appends to. That one list is the decisive ordering proof: it
 * records {@code "validation"} from the fixture invoker's {@code prepare()} (standing in for the
 * Bean Validation stage that already ran), each permitted guard's own marker, and {@code "invoke"}
 * from the fixture invoker's {@code invoke()} (the generated invocation) — so a single assertion on
 * its exact contents proves the guard's position relative to <em>both</em> neighbours at once; two
 * independent assertions that "validation happened" and "the guard ran" could not detect the guard
 * running out of place.
 */
@DisplayName("MCP tool interceptor SPI and post-validation stage — T017 contract matrix")
class McpToolInterceptorPipelineTest {

    private static final String ZERO_INTERCEPTORS_ROW = "shouldInvokeWithZeroToolInterceptors";
    private static final String AFTER_VALIDATION_ROW = "shouldRunToolInterceptorsAfterValidation";
    private static final String REJECT_STOPS_INVOCATION_ROW = "shouldRejectWithoutInvokingTheGeneratedInvoker";
    private static final String FAIL_CLOSED_ROW = "shouldFailClosedOnThrowNullFutureAndFailedFuture";
    private static final String NO_RAW_ARGUMENTS_ROW = "shouldExposeNoRawArgumentsToTheGuard";

    private static Stream<String> t017ContractRows() {
        return Stream.of(
                ZERO_INTERCEPTORS_ROW,
                AFTER_VALIDATION_ROW,
                REJECT_STOPS_INVOCATION_ROW,
                FAIL_CLOSED_ROW,
                NO_RAW_ARGUMENTS_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t017ContractRows")
    @DisplayName("enforces the T017 contract matrix")
    void shouldEnforceT017ContractMatrix(String row) {
        switch (row) {
            case ZERO_INTERCEPTORS_ROW -> shouldInvokeWithZeroToolInterceptors();
            case AFTER_VALIDATION_ROW -> shouldRunToolInterceptorsAfterValidation();
            case REJECT_STOPS_INVOCATION_ROW -> shouldRejectWithoutInvokingTheGeneratedInvoker();
            case FAIL_CLOSED_ROW -> shouldFailClosedOnThrowNullFutureAndFailedFuture();
            case NO_RAW_ARGUMENTS_ROW -> shouldExposeNoRawArgumentsToTheGuard();
            default -> fail("unknown T017 contract matrix row: " + row);
        }
    }

    // --- Row 1: zero interceptors still invoke, proving the invocation counter can increment at all ---

    private void shouldInvokeWithZeroToolInterceptors() {
        List<String> recordedOrder = new ArrayList<>();
        AtomicInteger invokeCount = new AtomicInteger();
        McpToolInterceptorPipelineTestFixture.GuardedFixtureInvoker invoker =
                new McpToolInterceptorPipelineTestFixture.GuardedFixtureInvoker(recordedOrder, invokeCount);

        McpRequestDispatcher dispatcher = McpToolInterceptorPipelineTestFixture.buildDispatcher(Set.of(), invoker);
        McpToolInterceptorPipelineTestFixture.DispatchOutcome outcome =
                McpToolInterceptorPipelineTestFixture.dispatchToolCall(
                        dispatcher, McpToolInterceptorPipelineTestFixture.GuardedFixtureInvoker.TOOL_NAME);

        assertThat(outcome.status())
                .as("a zero-interceptor composition must still reach the generated invocation")
                .isEqualTo(200);
        assertThat(outcome.isError()).as("an unguarded call succeeds").isFalse();
        assertThat(invokeCount.get())
                .as("the invocation counter must be able to increment — this is the baseline that proves it")
                .isEqualTo(1);
        assertThat(recordedOrder)
                .as("validation (prepare()) precedes the generated invocation even with zero guards between")
                .containsExactly("validation", "invoke");
    }

    // --- Row 2: three interceptors run after validation, in frozen order, before invocation ---

    private void shouldRunToolInterceptorsAfterValidation() {
        List<String> recordedOrder = new ArrayList<>();
        AtomicInteger invokeCount = new AtomicInteger();
        AtomicReference<McpToolInvocationContext> observedContext = new AtomicReference<>();
        McpToolInterceptorPipelineTestFixture.GuardedFixtureInvoker invoker =
                new McpToolInterceptorPipelineTestFixture.GuardedFixtureInvoker(recordedOrder, invokeCount);

        McpToolInterceptorPipelineTestFixture.PerToolEntitlementGuard entitlement =
                McpToolInterceptorPipelineTestFixture.PerToolEntitlementGuard.permitting(
                        10, "entitlement-guard", recordedOrder, observedContext);
        McpToolInterceptorPipelineTestFixture.DataLossPreventionGuard dlp =
                McpToolInterceptorPipelineTestFixture.DataLossPreventionGuard.permitting(15, recordedOrder);
        McpToolInterceptorPipelineTestFixture.ContentPolicyGuard contentPolicy =
                McpToolInterceptorPipelineTestFixture.ContentPolicyGuard.permitting(20, recordedOrder);

        // Declared in a deliberately non-ascending-priority order (content-policy, entitlement, dlp): a
        // raw Set/Dagger iteration order would reproduce exactly this declaration order, which is why it
        // is asserted below to differ from the frozen expectation.
        Set<McpToolInterceptor> declared = new LinkedHashSet<>();
        declared.add(contentPolicy);
        declared.add(entitlement);
        declared.add(dlp);
        List<String> declarationOrder = List.of("validation", "content-policy", "entitlement", "dlp", "invoke");

        McpRequestDispatcher dispatcher = McpToolInterceptorPipelineTestFixture.buildDispatcher(declared, invoker);
        McpToolInterceptorPipelineTestFixture.DispatchOutcome outcome =
                McpToolInterceptorPipelineTestFixture.dispatchToolCall(
                        dispatcher, McpToolInterceptorPipelineTestFixture.GuardedFixtureInvoker.TOOL_NAME);

        assertThat(outcome.status()).isEqualTo(200);
        assertThat(outcome.isError()).isFalse();
        assertThat(invokeCount.get()).isEqualTo(1);
        assertThat(recordedOrder)
                .as("DECISIVE: validation precedes every guard, ascending priority governs guard order "
                        + "(entitlement(10), dlp(15), content-policy(20)), and the generated invocation runs "
                        + "strictly last — one ordered list proves the guard's position relative to both "
                        + "neighbours simultaneously")
                .containsExactly("validation", "entitlement", "dlp", "content-policy", "invoke");
        assertThat(recordedOrder)
                .as("the frozen order must differ from the declared Set order — otherwise this row would "
                        + "pass even if ordering silently fell back to Set/Dagger iteration order")
                .isNotEqualTo(declarationOrder);
        assertThat(observedContext.get())
                .as("a permitted guard must actually receive the invocation context")
                .isNotNull();
        assertThat(observedContext.get().tool().name())
                .as("the guard must see the resolved tool's own descriptor")
                .isEqualTo(McpToolInterceptorPipelineTestFixture.GuardedFixtureInvoker.TOOL_NAME);
        assertThat(observedContext.get().request().method())
                .as("the projected request context must reflect the tools/call method")
                .isEqualTo(McpMethod.TOOLS_CALL);
    }

    // --- Row 3: a rejection stops the chain and the generated invocation never runs ---

    private void shouldRejectWithoutInvokingTheGeneratedInvoker() {
        List<String> recordedOrder = new ArrayList<>();
        AtomicInteger invokeCount = new AtomicInteger();
        McpToolInterceptorPipelineTestFixture.GuardedFixtureInvoker invoker =
                new McpToolInterceptorPipelineTestFixture.GuardedFixtureInvoker(recordedOrder, invokeCount);
        McpToolInterceptorPipelineTestFixture.PerToolEntitlementGuard rejecting =
                McpToolInterceptorPipelineTestFixture.PerToolEntitlementGuard.rejectingWithFailedFuture(
                        0, "entitlement-guard", recordedOrder, new AtomicReference<>());
        McpToolInterceptorPipelineTestFixture.DataLossPreventionGuard neverInvoked =
                McpToolInterceptorPipelineTestFixture.DataLossPreventionGuard.permitting(10, recordedOrder);

        Set<McpToolInterceptor> interceptors = new LinkedHashSet<>();
        interceptors.add(rejecting);
        interceptors.add(neverInvoked);

        McpRequestDispatcher dispatcher = McpToolInterceptorPipelineTestFixture.buildDispatcher(interceptors, invoker);
        McpToolInterceptorPipelineTestFixture.DispatchOutcome outcome =
                McpToolInterceptorPipelineTestFixture.dispatchToolCall(
                        dispatcher, McpToolInterceptorPipelineTestFixture.GuardedFixtureInvoker.TOOL_NAME);

        assertThat(outcome.status())
                .as("a tool-interceptor rejection still settles as SSE-framed HTTP 200 — SSE was already "
                        + "selected before this stage runs")
                .isEqualTo(200);
        assertThat(outcome.isError())
                .as("a rejection settles as the bounded text-only isError=true tool result")
                .isTrue();
        assertThat(outcome.text())
                .as("the bounded, non-leaking rejection text — no interceptor class name or exception detail")
                .isEqualTo("Tool call rejected");
        assertThat(invokeCount.get())
                .as("DECISIVE: the generated invocation must never run once a tool interceptor rejects")
                .isZero();
        assertThat(recordedOrder)
                .as("the later interceptor in the chain must never be invoked once the first rejects")
                .containsExactly("validation", "entitlement");
    }

    // --- Row 4: throw, a null future, and a failed future each fail closed, independently ---

    private void shouldFailClosedOnThrowNullFutureAndFailedFuture() {
        assertFailsClosed(
                McpToolInterceptorPipelineTestFixture.PerToolEntitlementGuard::throwing, "a synchronous throw");
        assertFailsClosed(
                McpToolInterceptorPipelineTestFixture.PerToolEntitlementGuard::rejectingWithNullFuture,
                "a null returned future");
        assertFailsClosed(
                McpToolInterceptorPipelineTestFixture.PerToolEntitlementGuard::rejectingWithFailedFuture,
                "a failed future");
    }

    private void assertFailsClosed(RejectingGuardFactory factory, String caseLabel) {
        List<String> recordedOrder = new ArrayList<>();
        AtomicInteger invokeCount = new AtomicInteger();
        McpToolInterceptorPipelineTestFixture.GuardedFixtureInvoker invoker =
                new McpToolInterceptorPipelineTestFixture.GuardedFixtureInvoker(recordedOrder, invokeCount);
        McpToolInterceptorPipelineTestFixture.PerToolEntitlementGuard rejecting =
                factory.create(0, "entitlement-guard", recordedOrder);
        McpToolInterceptorPipelineTestFixture.DataLossPreventionGuard neverInvoked =
                McpToolInterceptorPipelineTestFixture.DataLossPreventionGuard.permitting(10, recordedOrder);
        Set<McpToolInterceptor> interceptors = new LinkedHashSet<>();
        interceptors.add(rejecting);
        interceptors.add(neverInvoked);

        McpRequestDispatcher dispatcher = McpToolInterceptorPipelineTestFixture.buildDispatcher(interceptors, invoker);
        McpToolInterceptorPipelineTestFixture.DispatchOutcome outcome =
                McpToolInterceptorPipelineTestFixture.dispatchToolCall(
                        dispatcher, McpToolInterceptorPipelineTestFixture.GuardedFixtureInvoker.TOOL_NAME);

        assertThat(invokeCount.get())
                .as(caseLabel + " must never let the generated invocation run")
                .isZero();
        assertThat(outcome.isError())
                .as(caseLabel + " settles as the bounded text-only isError=true tool result")
                .isTrue();
        assertThat(outcome.text()).isEqualTo("Tool call rejected");
        assertThat(recordedOrder)
                .as(caseLabel + " must stop the chain before the next interceptor runs")
                .containsExactly("validation", "entitlement");
    }

    @FunctionalInterface
    private interface RejectingGuardFactory {
        McpToolInterceptorPipelineTestFixture.PerToolEntitlementGuard create(
                int priority, String orderKey, List<String> recordedOrder);
    }

    // --- Row 5: McpToolInvocationContext exposes no raw or normalized argument accessor ---

    /**
     * {@link McpToolInvocationContext} exposes no argument accessor of any kind: its only record
     * components are {@code request} and {@code tool}. Established from the type's actual declared
     * shape via reflection, not from a guard fixture merely declining to look — a fixture could
     * always "politely" not read an accessor that exists; only the type's shape proves none exists.
     */
    private void shouldExposeNoRawArgumentsToTheGuard() {
        List<String> componentNames = new ArrayList<>();
        for (RecordComponent component : McpToolInvocationContext.class.getRecordComponents()) {
            componentNames.add(component.getName());
        }
        assertThat(componentNames)
                .as("DECISIVE: the frozen record shape carries only request/tool — no raw wire argument, "
                        + "normalized argument, or result accessor exists to read")
                .containsExactly("request", "tool");
    }

    /** Framework wiring for the T017 matrix: dispatcher construction and driving, nothing decisive. */
    private static final class McpToolInterceptorPipelineTestFixture {

        private McpToolInterceptorPipelineTestFixture() {}

        /**
         * Builds an {@link McpRequestDispatcher} composed with {@code toolInterceptors} and one
         * registered fixture tool, exactly like production Dagger composition does through {@code
         * McpServerModule}'s {@code @Multibinds Set<McpToolInterceptor>}. The mocked {@link
         * McpPolicyEnforcer} unconditionally permits, isolating this matrix from the authorization
         * stage T017 sits after.
         */
        static McpRequestDispatcher buildDispatcher(Set<McpToolInterceptor> toolInterceptors, McpToolInvoker invoker) {
            SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
            SecurityContext anonymous = SecurityContexts.unauthenticated(SecurityIdentity.anonymous());
            when(securityRuntime.current()).thenReturn(anonymous);
            McpPolicyEnforcer policyEnforcer = mock(McpPolicyEnforcer.class);
            when(policyEnforcer.decide(any(), any()))
                    .thenReturn(Future.succeededFuture(AuthorizationDecision.permit("PERMITTED")));
            McpToolRegistry registry = McpToolRegistry.build(Set.of(invoker));
            return new McpRequestDispatcher(
                    McpServerConfig.defaults(),
                    securityRuntime,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    toolInterceptors,
                    HttpConfig.builder().build(),
                    registry,
                    policyEnforcer);
        }

        /**
         * Drives {@link McpRequestDispatcher#dispatch} once against a mocked {@link RoutingContext}
         * carrying a valid zero-argument {@code tools/call} request naming {@code toolName}, and
         * returns the observed HTTP status and the decoded {@code CallToolResult}'s {@code isError}
         * flag and sole text item.
         */
        static DispatchOutcome dispatchToolCall(McpRequestDispatcher dispatcher, String toolName) {
            RoutingContext context = mock(RoutingContext.class);
            HttpServerRequest request = mock(HttpServerRequest.class);
            HttpServerResponse response = mock(HttpServerResponse.class);
            RequestBody body = mock(RequestBody.class);

            when(context.request()).thenReturn(request);
            when(context.response()).thenReturn(response);
            when(context.body()).thenReturn(body);
            when(body.buffer()).thenReturn(Buffer.buffer(toolCallRequestBody(toolName)));
            when(response.putHeader(anyString(), anyString())).thenReturn(response);
            when(response.setStatusCode(anyInt())).thenReturn(response);
            when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());

            dispatcher.dispatch(context);

            ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
            verify(response).setStatusCode(statusCaptor.capture());
            ArgumentCaptor<Buffer> bodyCaptor = ArgumentCaptor.forClass(Buffer.class);
            verify(response).end(bodyCaptor.capture());

            int status = statusCaptor.getValue();
            JsonObject decoded = decodeSseJson(bodyCaptor.getValue().getBytes());
            JsonObject result = decoded.getJsonObject("result");
            boolean isError = result.getBoolean("isError");
            JsonArray content = result.getJsonArray("content");
            String text = content.isEmpty() ? null : content.getJsonObject(0).getString("text");
            return new DispatchOutcome(status, isError, text);
        }

        /** Strips the single {@code event: message}/{@code data:} SSE frame and decodes its JSON body. */
        private static JsonObject decodeSseJson(byte[] framed) {
            String text = new String(framed, StandardCharsets.UTF_8);
            String prefix = "event: message\ndata: ";
            String suffix = "\n\n";
            return new JsonObject(text.substring(prefix.length(), text.length() - suffix.length()));
        }

        private static byte[] toolCallRequestBody(String toolName) {
            return new JsonObject()
                    .put("jsonrpc", "2.0")
                    .put("id", 1)
                    .put("method", "tools/call")
                    .put("params", new JsonObject().put("name", toolName))
                    .toBuffer()
                    .getBytes();
        }

        /** One dispatch's observed outcome: HTTP status and the decoded {@code CallToolResult} facts. */
        record DispatchOutcome(int status, boolean isError, String text) {}

        /** The three fixture outcomes {@link McpToolInterceptor#beforeInvocation} may produce. */
        private enum Mode {
            PERMIT,
            THROW,
            NULL_FUTURE,
            FAILED_FUTURE
        }

        /**
         * Stands in for one {@code @McpTool}-generated invoker (T012/T015's frozen shape). {@code
         * prepare()} records {@code "validation"} — standing in for the stage 2-4 pipeline, including
         * Bean Validation, that a real generated invoker already ran before returning — and {@code
         * invoke()} records {@code "invoke"} and increments the shared counter, standing in for the
         * generated invocation T017's stage must never reach ahead of a permitting guard chain.
         */
        static final class GuardedFixtureInvoker implements McpToolInvoker {
            static final String TOOL_NAME = "guarded.fixture.tool";

            private final McpToolDescriptor descriptor;
            private final List<String> recordedOrder;
            private final AtomicInteger invokeCount;

            GuardedFixtureInvoker(List<String> recordedOrder, AtomicInteger invokeCount) {
                this.descriptor = new McpToolDescriptor(
                        TOOL_NAME,
                        null,
                        "T017 TP-001 fixture tool.",
                        new McpToolAnnotations(true, false, true, false),
                        "{\"type\":\"object\",\"additionalProperties\":false}",
                        null,
                        new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
                this.recordedOrder = recordedOrder;
                this.invokeCount = invokeCount;
            }

            @Override
            public McpToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
                recordedOrder.add("validation");
                return new McpPreparedToolCall() {
                    @Override
                    public Map<String, Object> normalizedArguments() {
                        return Map.of();
                    }

                    @Override
                    public Future<McpToolResult<?>> invoke() {
                        recordedOrder.add("invoke");
                        invokeCount.incrementAndGet();
                        return Future.succeededFuture(McpToolResult.text("ok"));
                    }
                };
            }
        }

        /** A fixture tool interceptor named for the application per-tool entitlement guard the contract names. */
        static final class PerToolEntitlementGuard implements McpToolInterceptor {
            private final int priority;
            private final String orderKey;
            private final List<String> recordedOrder;
            private final AtomicReference<McpToolInvocationContext> observedContext;
            private final Mode mode;

            private PerToolEntitlementGuard(
                    int priority,
                    String orderKey,
                    List<String> recordedOrder,
                    AtomicReference<McpToolInvocationContext> observedContext,
                    Mode mode) {
                this.priority = priority;
                this.orderKey = orderKey;
                this.recordedOrder = recordedOrder;
                this.observedContext = observedContext;
                this.mode = mode;
            }

            static PerToolEntitlementGuard permitting(
                    int priority,
                    String orderKey,
                    List<String> recordedOrder,
                    AtomicReference<McpToolInvocationContext> observedContext) {
                return new PerToolEntitlementGuard(priority, orderKey, recordedOrder, observedContext, Mode.PERMIT);
            }

            static PerToolEntitlementGuard rejectingWithFailedFuture(
                    int priority,
                    String orderKey,
                    List<String> recordedOrder,
                    AtomicReference<McpToolInvocationContext> observedContext) {
                return new PerToolEntitlementGuard(
                        priority, orderKey, recordedOrder, observedContext, Mode.FAILED_FUTURE);
            }

            static PerToolEntitlementGuard throwing(int priority, String orderKey, List<String> recordedOrder) {
                return new PerToolEntitlementGuard(
                        priority, orderKey, recordedOrder, new AtomicReference<>(), Mode.THROW);
            }

            static PerToolEntitlementGuard rejectingWithNullFuture(
                    int priority, String orderKey, List<String> recordedOrder) {
                return new PerToolEntitlementGuard(
                        priority, orderKey, recordedOrder, new AtomicReference<>(), Mode.NULL_FUTURE);
            }

            static PerToolEntitlementGuard rejectingWithFailedFuture(
                    int priority, String orderKey, List<String> recordedOrder) {
                return new PerToolEntitlementGuard(
                        priority, orderKey, recordedOrder, new AtomicReference<>(), Mode.FAILED_FUTURE);
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public String orderKey() {
                return orderKey;
            }

            @Override
            public Future<Void> beforeInvocation(McpToolInvocationContext context) {
                recordedOrder.add("entitlement");
                observedContext.set(context);
                return switch (mode) {
                    case PERMIT -> Future.succeededFuture();
                    case THROW -> throw new IllegalStateException("fixture throw: entitlement");
                    case NULL_FUTURE -> null;
                    case FAILED_FUTURE ->
                        Future.failedFuture(new IllegalStateException("fixture failed future: entitlement"));
                };
            }
        }

        /** A fixture tool interceptor named for the application data-loss-prevention guard the contract names. */
        static final class DataLossPreventionGuard implements McpToolInterceptor {
            private final int priority;
            private final List<String> recordedOrder;

            private DataLossPreventionGuard(int priority, List<String> recordedOrder) {
                this.priority = priority;
                this.recordedOrder = recordedOrder;
            }

            static DataLossPreventionGuard permitting(int priority, List<String> recordedOrder) {
                return new DataLossPreventionGuard(priority, recordedOrder);
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public Future<Void> beforeInvocation(McpToolInvocationContext context) {
                recordedOrder.add("dlp");
                return Future.succeededFuture();
            }
        }

        /** A third, always-permitting fixture guard — proves a three-interceptor composition. */
        static final class ContentPolicyGuard implements McpToolInterceptor {
            private final int priority;
            private final List<String> recordedOrder;

            private ContentPolicyGuard(int priority, List<String> recordedOrder) {
                this.priority = priority;
                this.recordedOrder = recordedOrder;
            }

            static ContentPolicyGuard permitting(int priority, List<String> recordedOrder) {
                return new ContentPolicyGuard(priority, recordedOrder);
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public Future<Void> beforeInvocation(McpToolInvocationContext context) {
                recordedOrder.add("content-policy");
                return Future.succeededFuture();
            }
        }
    }
}
