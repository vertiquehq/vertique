// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.events;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pins {@link RequestCompletionRecorder#recordedOperation}: the claim's operation, or {@code null}. */
class RequestCompletionRecorderRecordedOperationTest {

    private static final TestOperation FIRST = new TestOperation("first", "GET", "/overlap/{id}");
    private static final TestOperation SECOND = new TestOperation("second", "GET", "/overlap/fixed");

    /** A context backed by a real map, bound to a fresh request. */
    private static RoutingContext context() {
        Map<String, Object> data = new HashMap<>();
        RoutingContext ctx = mock(RoutingContext.class);
        when(ctx.request()).thenReturn(mock(HttpServerRequest.class));
        when(ctx.get(anyString())).thenAnswer(inv -> data.get(inv.<String>getArgument(0)));
        when(ctx.put(anyString(), any())).thenAnswer(inv -> {
            data.put(inv.getArgument(0), inv.getArgument(1));
            return ctx;
        });
        return ctx;
    }

    @Test
    @DisplayName("a context without a holder has no recorded operation")
    void noHolder() {
        assertNull(RequestCompletionRecorder.recordedOperation(context()));
    }

    @Test
    @DisplayName("a holder before any operation route matched has no recorded operation")
    void holderWithoutClaim() {
        RoutingContext ctx = context();
        RequestCompletionRecorder.installHolder(ctx);

        assertNull(RequestCompletionRecorder.recordedOperation(ctx));
    }

    @Test
    @DisplayName("the recorded operation is the very descriptor the last matched route recorded")
    void lastMatchedRouteWins() {
        RoutingContext ctx = context();
        RequestCompletionRecorder.installHolder(ctx);

        RequestCompletionRecorder.operationRouteHandler(FIRST).handle(ctx);
        assertSame(FIRST, RequestCompletionRecorder.recordedOperation(ctx));

        RequestCompletionRecorder.operationRouteHandler(SECOND).handle(ctx);
        assertSame(SECOND, RequestCompletionRecorder.recordedOperation(ctx));
    }

    @Test
    @DisplayName("a request another transport claimed has no recorded operation")
    void otherTransportClaim() {
        RoutingContext ctx = context();
        RequestCompletionRecorder.installHolder(ctx);

        RequestCompletionRecorder.claimForOtherTransport(ctx);
        RequestCompletionRecorder.operationRouteHandler(FIRST).handle(ctx);

        assertNull(RequestCompletionRecorder.recordedOperation(ctx));
    }
}
