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
import dev.vertique.mcp.interceptor.McpRequestContext;
import dev.vertique.mcp.interceptor.McpRequestInterceptor;
import dev.vertique.mcp.interceptor.McpTraceContext;
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
import jakarta.annotation.Nullable;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

/**
 * R39 TP-003 — proves a request interceptor observes the extracted {@link
 * McpRequestContext#bodyTraceContext()} at the frozen pre-dispatch stage, driving {@link
 * McpRequestDispatcher#dispatch} directly against a mocked {@link RoutingContext} exactly like {@code
 * McpRequestInterceptorPipelineTest}'s T016 fixture, but observing the recorded trace context instead
 * of dispatch order.
 *
 * <p>The resolved body extraction shape (recorded in {@code evidence/R39.md} and {@code module.md} by
 * the producer slice that follows): the JSON-RPC request's {@code params._meta} carries plain,
 * unprefixed {@code traceparent}/{@code tracestate} string keys — the ordinary W3C wire format,
 * verbatim, never namespaced under the reserved {@code io.modelcontextprotocol/} prefix this same
 * {@code _meta} object already uses for {@code protocolVersion}/{@code clientCapabilities}.
 *
 * <p>{@code shouldExposeTheExtractedBodyTraceContextWhenMetaCarriesAValidTraceparent} is red today:
 * {@link McpRequestDispatcher#dispatch} always constructs {@link McpRequestContext} with a {@code
 * null} body trace context (both construction sites), so the interceptor always observes {@code null}
 * regardless of the request body. The other two rows are controls, green both before and after the
 * producer fix: an absent {@code _meta} entry and malformed {@code traceparent} syntax must both still
 * yield {@code null} once the producer exists.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpRequestContextBodyTraceVisibilityTest {

    private static final String VALID_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String VALID_SPAN_ID = "00f067aa0ba902b7";
    private static final String VALID_TRACEPARENT = "00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01";
    private static final String VALID_TRACESTATE = "vendor=value1";

    @Test
    @DisplayName("R39: a valid body _meta.traceparent is visible on the interceptor's McpRequestContext "
            + "(RED today — producer missing)")
    void shouldExposeTheExtractedBodyTraceContextWhenMetaCarriesAValidTraceparent() {
        AtomicReference<McpTraceContext> recorded = new AtomicReference<>();
        McpRequestDispatcher dispatcher = buildDispatcher(recordingInterceptor(recorded));

        DispatchOutcome outcome =
                dispatchDiscover(dispatcher, requestBodyWithMeta(VALID_TRACEPARENT, VALID_TRACESTATE));

        assertThat(outcome.status())
                .as("a valid discover request must still be admitted")
                .isEqualTo(200);
        McpTraceContext bodyTraceContext = recorded.get();
        assertThat(bodyTraceContext)
                .as("DECISIVE: the interceptor must observe the extracted body trace context")
                .isNotNull();
        assertThat(bodyTraceContext.traceId()).isEqualTo(VALID_TRACE_ID);
        assertThat(bodyTraceContext.spanId()).isEqualTo(VALID_SPAN_ID);
        assertThat(bodyTraceContext.sampled())
                .as("the '01' traceparent flag byte means sampled")
                .isTrue();
        assertThat(bodyTraceContext.traceState()).isEqualTo(VALID_TRACESTATE);
    }

    @Test
    @DisplayName("R39 control: an absent body _meta.traceparent leaves bodyTraceContext null (green both "
            + "before and after the producer fix)")
    void shouldExposeNoBodyTraceContextWhenTraceparentIsAbsent() {
        AtomicReference<McpTraceContext> recorded = new AtomicReference<>();
        McpRequestDispatcher dispatcher = buildDispatcher(recordingInterceptor(recorded));

        DispatchOutcome outcome = dispatchDiscover(dispatcher, requestBodyWithMeta(null, null));

        assertThat(outcome.status()).isEqualTo(200);
        assertThat(recorded.get())
                .as("no _meta.traceparent means no body trace context, today and after the fix")
                .isNull();
    }

    @Test
    @DisplayName("R39 control: malformed body _meta.traceparent syntax leaves bodyTraceContext null "
            + "and the request still succeeds (green both before and after the producer fix)")
    void shouldExposeNoBodyTraceContextWhenTraceparentIsMalformed() {
        AtomicReference<McpTraceContext> recorded = new AtomicReference<>();
        McpRequestDispatcher dispatcher = buildDispatcher(recordingInterceptor(recorded));

        DispatchOutcome outcome = dispatchDiscover(dispatcher, requestBodyWithMeta("not-a-valid-traceparent", null));

        assertThat(outcome.status())
                .as("malformed body trace data must never fail the request")
                .isEqualTo(200);
        assertThat(recorded.get())
                .as("malformed traceparent syntax must never surface as a body trace context")
                .isNull();
    }

    // --- Fixtures (mirrors McpRequestInterceptorPipelineTest's T016 driving style) ---

    private static McpRequestInterceptor recordingInterceptor(AtomicReference<McpTraceContext> recorded) {
        return new McpRequestInterceptor() {
            @Override
            public Future<Void> beforeRequest(McpRequestContext context) {
                recorded.set(context.bodyTraceContext());
                return Future.succeededFuture();
            }
        };
    }

    private static McpRequestDispatcher buildDispatcher(McpRequestInterceptor interceptor) {
        SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
        SecurityContext anonymous = SecurityContexts.unauthenticated(SecurityIdentity.anonymous());
        when(securityRuntime.current()).thenReturn(anonymous);
        return new McpRequestDispatcher(
                McpServerConfig.defaults(),
                securityRuntime,
                Set.of(),
                Set.of(),
                Set.of(interceptor),
                Set.of(),
                HttpConfig.builder().build(),
                McpToolRegistry.build(Set.of()),
                mock(McpPolicyEnforcer.class),
                NO_OP_CONTEXT_HOLDER,
                new CorrelationContextFactory(Optional.empty()));
    }

    /**
     * Drives {@link McpRequestDispatcher#dispatch} once against a mocked {@link RoutingContext}
     * carrying {@code body}, and returns the observed HTTP status.
     */
    private static DispatchOutcome dispatchDiscover(McpRequestDispatcher dispatcher, byte[] body) {
        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        RequestBody requestBody = mock(RequestBody.class);

        when(context.request()).thenReturn(request);
        when(context.response()).thenReturn(response);
        when(context.body()).thenReturn(requestBody);
        when(requestBody.buffer()).thenReturn(Buffer.buffer(body));
        when(request.headers()).thenReturn(negotiationHeaders());
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());

        dispatcher.dispatch(context);

        ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(response).setStatusCode(statusCaptor.capture());
        return new DispatchOutcome(statusCaptor.getValue());
    }

    /** The negotiation headers self-consistent with {@link #requestBodyWithMeta}. */
    private static io.vertx.core.MultiMap negotiationHeaders() {
        io.vertx.core.MultiMap headers = io.vertx.core.MultiMap.caseInsensitiveMultiMap();
        headers.set("MCP-Protocol-Version", "2026-07-28");
        headers.set("Mcp-Method", "server/discover");
        headers.set("Mcp-Name", "server/discover");
        return headers;
    }

    /**
     * Builds one {@code server/discover} request body whose {@code params._meta} carries the mandatory
     * reserved negotiation fields plus optional {@code traceparent}/{@code tracestate} — R39's resolved
     * body-trace extraction shape.
     *
     * @param traceparent the {@code _meta.traceparent} value, or {@code null} to omit it
     * @param tracestate the {@code _meta.tracestate} value, or {@code null} to omit it
     */
    private static byte[] requestBodyWithMeta(@Nullable String traceparent, @Nullable String tracestate) {
        JsonObject meta = new JsonObject()
                .put("io.modelcontextprotocol/protocolVersion", "2026-07-28")
                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject());
        if (traceparent != null) {
            meta.put("traceparent", traceparent);
        }
        if (tracestate != null) {
            meta.put("tracestate", tracestate);
        }
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "server/discover")
                .put("params", new JsonObject().put("_meta", meta))
                .toBuffer()
                .getBytes();
    }

    /** One dispatch's observed HTTP status; nothing else is decisive for this test. */
    private record DispatchOutcome(int status) {}

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
