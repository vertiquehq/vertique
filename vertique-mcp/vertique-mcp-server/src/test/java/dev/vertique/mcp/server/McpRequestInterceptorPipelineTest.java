// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.interceptor.McpRequestContext;
import dev.vertique.mcp.interceptor.McpRequestInterceptor;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/**
 * T016 TP-001 — the frozen request-interceptor SPI and pre-dispatch stage contract matrix.
 *
 * <p>Every row drives {@link McpRequestDispatcher#dispatch} directly against a mocked {@link
 * RoutingContext} carrying a valid {@code server/discover} request — mirroring {@code
 * McpCacheHintsGoldenTest}'s driving style — and observes two facts: the recorded interceptor
 * invocation order, and whether dispatch actually reached {@code writeDiscovery} (observable as HTTP
 * {@code 200}, since a valid discovery request cannot otherwise fail). A row that constructs the
 * dispatcher itself — the duplicate-order-key row — instead observes whether construction throws.
 */
@DisplayName("MCP request interceptor SPI and pre-dispatch stage — T016 contract matrix")
class McpRequestInterceptorPipelineTest {

    private static final String ZERO_INTERCEPTORS_ROW = "shouldDispatchWithZeroInterceptors";
    private static final String FROZEN_ORDER_ROW = "shouldRunInterceptorsInTheFrozenOrder";
    private static final String REJECT_STOPS_DISPATCH_ROW = "shouldRejectWithoutInvokingDispatch";
    private static final String FAIL_CLOSED_ROW = "shouldFailClosedOnThrowNullFutureAndFailedFuture";
    private static final String DUPLICATE_ORDER_KEY_ROW = "shouldFailStartupOnADuplicateOrderKeyNamingBothClasses";

    private static Stream<String> t016ContractRows() {
        return Stream.of(
                ZERO_INTERCEPTORS_ROW,
                FROZEN_ORDER_ROW,
                REJECT_STOPS_DISPATCH_ROW,
                FAIL_CLOSED_ROW,
                DUPLICATE_ORDER_KEY_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t016ContractRows")
    @DisplayName("enforces the T016 contract matrix")
    void shouldEnforceT016ContractMatrix(String row) {
        switch (row) {
            case ZERO_INTERCEPTORS_ROW -> shouldDispatchWithZeroInterceptors();
            case FROZEN_ORDER_ROW -> shouldRunInterceptorsInTheFrozenOrder();
            case REJECT_STOPS_DISPATCH_ROW -> shouldRejectWithoutInvokingDispatch();
            case FAIL_CLOSED_ROW -> shouldFailClosedOnThrowNullFutureAndFailedFuture();
            case DUPLICATE_ORDER_KEY_ROW -> shouldFailStartupOnADuplicateOrderKeyNamingBothClasses();
            default -> fail("unknown T016 contract matrix row: " + row);
        }
    }

    /**
     * {@link McpRequestContext} exposes no request-body, header, or credential accessor: its only
     * record components are {@code method}, {@code securityContext}, and {@code correlation} — R51
     * (trace-reference consolidation) removed the former {@code bodyTraceContext} component with no
     * replacement; no interceptor ever consumed it, and the body trace reference now travels solely
     * on the payload-free terminal lifecycle observation. Established from the type's actual declared
     * shape via reflection, not from an interceptor fixture merely declining to look — a fixture
     * could always "politely" not read a payload accessor that exists; only the type's shape proves
     * none exists.
     */
    @Test
    @DisplayName("McpRequestContext declares no payload, header, or credential accessor")
    void shouldExposeNoPayloadAccessorOnRequestContext() {
        List<String> componentNames = new ArrayList<>();
        for (RecordComponent component : McpRequestContext.class.getRecordComponents()) {
            componentNames.add(component.getName());
        }
        assertThat(componentNames)
                .as("the frozen record shape carries only method/securityContext/correlation (R51)")
                .containsExactly("method", "securityContext", "correlation");
    }

    // --- Row 1: zero interceptors dispatch, proving the dispatch counter can increment at all ---

    private void shouldDispatchWithZeroInterceptors() {
        McpRequestDispatcher dispatcher = McpRequestInterceptorPipelineTestFixture.buildDispatcher(Set.of());

        McpRequestInterceptorPipelineTestFixture.DispatchOutcome outcome =
                McpRequestInterceptorPipelineTestFixture.dispatchDiscover(dispatcher);

        assertThat(outcome.status())
                .as("a zero-interceptor composition must still reach dispatch")
                .isEqualTo(200);
        assertThat(outcome.dispatchCount())
                .as("the dispatch counter must be able to increment — this is the baseline that proves it")
                .isEqualTo(1);
    }

    // --- Row 2: three interceptors run in frozen phase/priority/orderKey order, never Set order ---

    private void shouldRunInterceptorsInTheFrozenOrder() {
        List<String> recordedOrder = new ArrayList<>();
        McpRequestInterceptorPipelineTestFixture.TenantEntitlementGuard tenant =
                McpRequestInterceptorPipelineTestFixture.TenantEntitlementGuard.permitting(
                        10, "policy-guard", recordedOrder);
        McpRequestInterceptorPipelineTestFixture.RegionAllowlistGuard region =
                McpRequestInterceptorPipelineTestFixture.RegionAllowlistGuard.permitting(15, recordedOrder);
        McpRequestInterceptorPipelineTestFixture.MaintenanceWindowGuard maintenance =
                McpRequestInterceptorPipelineTestFixture.MaintenanceWindowGuard.permitting(
                        20, "policy-guard", recordedOrder);

        // Declared in a deliberately non-ascending-priority order (maintenance, tenant, region): a raw
        // Set/Dagger iteration order would reproduce exactly this declaration order, which is why it is
        // asserted below to differ from the frozen expectation.
        Set<McpRequestInterceptor> declared = new LinkedHashSet<>();
        declared.add(maintenance);
        declared.add(tenant);
        declared.add(region);
        List<String> declarationOrder = List.of("maintenance-window", "tenant-entitlement", "region-allowlist");

        McpRequestDispatcher dispatcher = McpRequestInterceptorPipelineTestFixture.buildDispatcher(declared);
        McpRequestInterceptorPipelineTestFixture.DispatchOutcome outcome =
                McpRequestInterceptorPipelineTestFixture.dispatchDiscover(dispatcher);

        assertThat(outcome.status()).as("all three interceptors permit").isEqualTo(200);
        assertThat(outcome.dispatchCount()).isEqualTo(1);
        assertThat(recordedOrder)
                .as("ascending priority is the frozen order: tenant(10), region(15), maintenance(20)")
                .containsExactly("tenant-entitlement", "region-allowlist", "maintenance-window");
        assertThat(recordedOrder)
                .as("the frozen order must differ from the declared Set order — otherwise this row would "
                        + "pass even if ordering silently fell back to Set/Dagger iteration order")
                .isNotEqualTo(declarationOrder);
    }

    // --- Row 3: a rejection stops dispatch and every later interceptor in the chain ---

    private void shouldRejectWithoutInvokingDispatch() {
        List<String> recordedOrder = new ArrayList<>();
        McpRequestInterceptorPipelineTestFixture.TenantEntitlementGuard rejecting =
                McpRequestInterceptorPipelineTestFixture.TenantEntitlementGuard.rejectingWithFailedFuture(
                        0, "tenant", recordedOrder);
        McpRequestInterceptorPipelineTestFixture.RegionAllowlistGuard neverInvoked =
                McpRequestInterceptorPipelineTestFixture.RegionAllowlistGuard.permitting(10, recordedOrder);

        Set<McpRequestInterceptor> interceptors = new LinkedHashSet<>();
        interceptors.add(rejecting);
        interceptors.add(neverInvoked);

        McpRequestDispatcher dispatcher = McpRequestInterceptorPipelineTestFixture.buildDispatcher(interceptors);
        McpRequestInterceptorPipelineTestFixture.DispatchOutcome outcome =
                McpRequestInterceptorPipelineTestFixture.dispatchDiscover(dispatcher);

        assertThat(outcome.status())
                .as("a rejection settles as the bounded interceptor-rejected status")
                .isEqualTo(403);
        assertThat(outcome.errorCode()).isEqualTo(-32001);
        assertThat(outcome.dispatchCount())
                .as("dispatch must never run once an interceptor rejects")
                .isZero();
        assertThat(recordedOrder)
                .as("the later interceptor in the chain must never be invoked once the first rejects")
                .containsExactly("tenant-entitlement");
    }

    // --- Row 4: throw, a null future, and a failed future each fail closed, independently ---

    private void shouldFailClosedOnThrowNullFutureAndFailedFuture() {
        assertFailsClosed(
                McpRequestInterceptorPipelineTestFixture.TenantEntitlementGuard::throwing, "a synchronous throw");
        assertFailsClosed(
                McpRequestInterceptorPipelineTestFixture.TenantEntitlementGuard::rejectingWithNullFuture,
                "a null returned future");
        assertFailsClosed(
                McpRequestInterceptorPipelineTestFixture.TenantEntitlementGuard::rejectingWithFailedFuture,
                "a failed future");
    }

    private void assertFailsClosed(RejectingGuardFactory factory, String caseLabel) {
        List<String> recordedOrder = new ArrayList<>();
        McpRequestInterceptorPipelineTestFixture.TenantEntitlementGuard rejecting =
                factory.create(0, "tenant", recordedOrder);
        McpRequestInterceptorPipelineTestFixture.RegionAllowlistGuard neverInvoked =
                McpRequestInterceptorPipelineTestFixture.RegionAllowlistGuard.permitting(10, recordedOrder);
        Set<McpRequestInterceptor> interceptors = new LinkedHashSet<>();
        interceptors.add(rejecting);
        interceptors.add(neverInvoked);

        McpRequestDispatcher dispatcher = McpRequestInterceptorPipelineTestFixture.buildDispatcher(interceptors);
        McpRequestInterceptorPipelineTestFixture.DispatchOutcome outcome =
                McpRequestInterceptorPipelineTestFixture.dispatchDiscover(dispatcher);

        assertThat(outcome.dispatchCount())
                .as(caseLabel + " must never let dispatch run")
                .isZero();
        assertThat(outcome.status())
                .as(caseLabel + " settles as the bounded interceptor-rejected status")
                .isEqualTo(403);
        assertThat(recordedOrder)
                .as(caseLabel + " must stop the chain before the next interceptor runs")
                .containsExactly("tenant-entitlement");
    }

    @FunctionalInterface
    private interface RejectingGuardFactory {
        McpRequestInterceptorPipelineTestFixture.TenantEntitlementGuard create(
                int priority, String orderKey, List<String> recordedOrder);
    }

    // --- Row 5: two interceptors sharing (phase, priority, orderKey) fail startup, naming both classes ---

    private void shouldFailStartupOnADuplicateOrderKeyNamingBothClasses() {
        List<String> recordedOrder = new ArrayList<>();
        McpRequestInterceptorPipelineTestFixture.TenantEntitlementGuard tenant =
                McpRequestInterceptorPipelineTestFixture.TenantEntitlementGuard.permitting(
                        10, "dup-guard", recordedOrder);
        McpRequestInterceptorPipelineTestFixture.MaintenanceWindowGuard maintenance =
                McpRequestInterceptorPipelineTestFixture.MaintenanceWindowGuard.permitting(
                        10, "dup-guard", recordedOrder);

        assertThatThrownBy(() -> McpRequestInterceptorPipelineTestFixture.buildDispatcher(Set.of(tenant, maintenance)))
                .as("a shared (phase, priority, orderKey) triple must fail startup")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(tenant.getClass().getName())
                .hasMessageContaining(maintenance.getClass().getName());
    }

    /** Framework wiring for the T016 matrix: dispatcher construction and driving, nothing decisive. */
    private static final class McpRequestInterceptorPipelineTestFixture {

        private McpRequestInterceptorPipelineTestFixture() {}

        /**
         * Builds an {@link McpRequestDispatcher} composed with {@code interceptors}, exactly like
         * production Dagger composition does through {@code McpServerModule}'s {@code
         * @Multibinds Set<McpRequestInterceptor>}. Throws {@link IllegalStateException} synchronously
         * when {@code interceptors} contains a duplicate {@code (phase, priority, orderKey)} triple.
         */
        static McpRequestDispatcher buildDispatcher(Set<McpRequestInterceptor> interceptors) {
            SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
            SecurityContext anonymous = SecurityContexts.unauthenticated(SecurityIdentity.anonymous());
            when(securityRuntime.current()).thenReturn(anonymous);
            return new McpRequestDispatcher(
                    McpServerConfig.defaults(),
                    securityRuntime,
                    Set.of(),
                    Set.of(),
                    interceptors,
                    Set.of(),
                    HttpConfig.builder().build(),
                    McpToolRegistry.build(Set.of()),
                    mock(McpPolicyEnforcer.class),
                    NO_OP_CONTEXT_HOLDER,
                    new CorrelationContextFactory(Optional.empty()));
        }

        /**
         * Drives {@link McpRequestDispatcher#dispatch} once against a mocked {@link RoutingContext}
         * carrying a valid {@code server/discover} request, and returns the observed status, error
         * code (when present), and derived dispatch count.
         */
        static DispatchOutcome dispatchDiscover(McpRequestDispatcher dispatcher) {
            RoutingContext context = mock(RoutingContext.class);
            HttpServerRequest request = mock(HttpServerRequest.class);
            HttpServerResponse response = mock(HttpServerResponse.class);
            RequestBody body = mock(RequestBody.class);

            when(context.request()).thenReturn(request);
            when(context.response()).thenReturn(response);
            when(context.body()).thenReturn(body);
            when(body.buffer()).thenReturn(Buffer.buffer(discoverRequestBody()));
            when(request.headers()).thenReturn(negotiationHeaders());
            when(response.putHeader(anyString(), anyString())).thenReturn(response);
            when(response.setStatusCode(anyInt())).thenReturn(response);
            when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());

            dispatcher.dispatch(context);

            ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
            verify(response).setStatusCode(statusCaptor.capture());
            ArgumentCaptor<Buffer> bodyCaptor = ArgumentCaptor.forClass(Buffer.class);
            verify(response).end(bodyCaptor.capture());

            int status = statusCaptor.getValue();
            JsonObject decoded = new JsonObject(bodyCaptor.getValue());
            JsonObject error = decoded.getJsonObject("error");
            Integer errorCode = error == null ? null : error.getInteger("code");
            return new DispatchOutcome(status, errorCode, status == 200 ? 1 : 0);
        }

        /** The negotiation headers (R05, issue #429) self-consistent with {@link #discoverRequestBody()}. */
        private static io.vertx.core.MultiMap negotiationHeaders() {
            io.vertx.core.MultiMap headers = io.vertx.core.MultiMap.caseInsensitiveMultiMap();
            headers.set("MCP-Protocol-Version", "2026-07-28");
            headers.set("Mcp-Method", "server/discover");
            headers.set("Mcp-Name", "server/discover");
            return headers;
        }

        private static byte[] discoverRequestBody() {
            return new JsonObject()
                    .put("jsonrpc", "2.0")
                    .put("id", 1)
                    .put("method", "server/discover")
                    .put(
                            "params",
                            new JsonObject()
                                    .put(
                                            "_meta",
                                            new JsonObject()
                                                    .put("io.modelcontextprotocol/protocolVersion", "2026-07-28")
                                                    .put(
                                                            "io.modelcontextprotocol/clientCapabilities",
                                                            new JsonObject())))
                    .toBuffer()
                    .getBytes();
        }

        /** One dispatch's observed outcome: HTTP status, the JSON-RPC error code when present, and the derived dispatch count. */
        record DispatchOutcome(int status, Integer errorCode, int dispatchCount) {}

        /** The three fixture outcomes {@link McpRequestInterceptor#beforeRequest} may produce. */
        private enum Mode {
            PERMIT,
            THROW,
            NULL_FUTURE,
            FAILED_FUTURE
        }

        /** A fixture request interceptor named for the application tenant-entitlement guard the contract names. */
        static final class TenantEntitlementGuard implements McpRequestInterceptor {
            private final ExtensionPhase phase;
            private final int priority;
            private final String orderKey;
            private final List<String> recordedOrder;
            private final Mode mode;

            private TenantEntitlementGuard(int priority, String orderKey, List<String> recordedOrder, Mode mode) {
                this.phase = ExtensionPhase.APPLICATION;
                this.priority = priority;
                this.orderKey = orderKey;
                this.recordedOrder = recordedOrder;
                this.mode = mode;
            }

            static TenantEntitlementGuard permitting(int priority, String orderKey, List<String> recordedOrder) {
                return new TenantEntitlementGuard(priority, orderKey, recordedOrder, Mode.PERMIT);
            }

            static TenantEntitlementGuard throwing(int priority, String orderKey, List<String> recordedOrder) {
                return new TenantEntitlementGuard(priority, orderKey, recordedOrder, Mode.THROW);
            }

            static TenantEntitlementGuard rejectingWithNullFuture(
                    int priority, String orderKey, List<String> recordedOrder) {
                return new TenantEntitlementGuard(priority, orderKey, recordedOrder, Mode.NULL_FUTURE);
            }

            static TenantEntitlementGuard rejectingWithFailedFuture(
                    int priority, String orderKey, List<String> recordedOrder) {
                return new TenantEntitlementGuard(priority, orderKey, recordedOrder, Mode.FAILED_FUTURE);
            }

            @Override
            public ExtensionPhase phase() {
                return phase;
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
            public Future<Void> beforeRequest(McpRequestContext context) {
                recordedOrder.add("tenant-entitlement");
                return switch (mode) {
                    case PERMIT -> Future.succeededFuture();
                    case THROW -> throw new IllegalStateException("fixture throw: tenant-entitlement");
                    case NULL_FUTURE -> null;
                    case FAILED_FUTURE ->
                        Future.failedFuture(new IllegalStateException("fixture failed future: tenant-entitlement"));
                };
            }
        }

        /** A fixture request interceptor named for the application maintenance-window guard the contract names. */
        static final class MaintenanceWindowGuard implements McpRequestInterceptor {
            private final ExtensionPhase phase;
            private final int priority;
            private final String orderKey;
            private final List<String> recordedOrder;

            private MaintenanceWindowGuard(int priority, String orderKey, List<String> recordedOrder) {
                this.phase = ExtensionPhase.APPLICATION;
                this.priority = priority;
                this.orderKey = orderKey;
                this.recordedOrder = recordedOrder;
            }

            static MaintenanceWindowGuard permitting(int priority, String orderKey, List<String> recordedOrder) {
                return new MaintenanceWindowGuard(priority, orderKey, recordedOrder);
            }

            @Override
            public ExtensionPhase phase() {
                return phase;
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
            public Future<Void> beforeRequest(McpRequestContext context) {
                recordedOrder.add("maintenance-window");
                return Future.succeededFuture();
            }
        }

        /** A third, always-permitting fixture interceptor — proves a three-interceptor composition and doubles as the "never invoked" sentinel. */
        static final class RegionAllowlistGuard implements McpRequestInterceptor {
            private final int priority;
            private final List<String> recordedOrder;

            private RegionAllowlistGuard(int priority, List<String> recordedOrder) {
                this.priority = priority;
                this.recordedOrder = recordedOrder;
            }

            static RegionAllowlistGuard permitting(int priority, List<String> recordedOrder) {
                return new RegionAllowlistGuard(priority, recordedOrder);
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public Future<Void> beforeRequest(McpRequestContext context) {
                recordedOrder.add("region-allowlist");
                return Future.succeededFuture();
            }
        }
    }

    /** A {@link ContextHolder} that resolves nothing and discards every binding (R09). */
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
