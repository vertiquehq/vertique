// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.rest.core.correlation.CorrelationIngressMiddleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OriginCaptureMiddleware}.
 *
 * <p>Verifies the middleware contract:
 * <ul>
 *   <li>{@link OriginCaptureMiddleware#order()} is {@link CorrelationIngressMiddleware#ORDER} + 10</li>
 *   <li>{@link OriginCaptureMiddleware#scope()} is {@link MiddlewareScope#ROOT}</li>
 *   <li>{@link OriginCaptureMiddleware#handle(RoutingContext)} captures a {@link RequestOrigin} and
 *       stores it on the routing context under the well-known key</li>
 *   <li>{@code ctx.next()} is always called after origin capture</li>
 * </ul>
 */
class OriginCaptureMiddlewareTest {

    private RequestOriginCapturer capturer;
    private OriginCaptureMiddleware middleware;

    @BeforeEach
    void setup() {
        capturer = new RequestOriginCapturer(RequestOriginConfig.defaults());
        middleware = new OriginCaptureMiddleware(capturer);
    }

    // --- Middleware contract ---

    @Test
    @DisplayName("priority() returns CorrelationIngressMiddleware.ORDER + 10")
    void orderIsCorrelationOrderPlusTen() {
        assertEquals(CorrelationIngressMiddleware.ORDER + 10, middleware.priority());
    }

    @Test
    @DisplayName("scope() returns ROOT")
    void scopeIsRoot() {
        assertEquals(MiddlewareScope.ROOT, middleware.scope());
    }

    // --- handle(ctx) behaviour ---

    @Test
    @DisplayName("handle stores RequestOrigin on routing context and calls ctx.next()")
    void handleStoresOriginAndCallsNext() {
        Map<String, Object> store = new HashMap<>();
        RoutingContext ctx = stubContext(store);

        middleware.handle(ctx);

        // Origin stored under the well-known key
        Object stored = store.get(RequestOrigin.class.getName());
        assertNotNull(stored);
        assertInstanceOf(RequestOrigin.class, stored);

        // ctx.next() was called
        verify(ctx).next();
    }

    @Test
    @DisplayName("handle always calls ctx.next() even on plain HTTP with no headers")
    void handleAlwaysCallsNext() {
        Map<String, Object> store = new HashMap<>();
        RoutingContext ctx = stubContext(store);

        middleware.handle(ctx);

        verify(ctx, times(1)).next();
        verify(ctx, never()).fail(anyInt());
    }

    @Test
    @DisplayName("ORDER constant matches CorrelationIngressMiddleware.ORDER + 10")
    void orderConstantMatchesFormula() {
        assertEquals(CorrelationIngressMiddleware.ORDER + 10, OriginCaptureMiddleware.ORDER);
    }

    // --- Stub helpers ---

    /**
     * Creates a stub {@link RoutingContext} backed by a map, with a minimal HTTP request
     * (127.0.0.1, port 80, scheme http, host localhost).
     *
     * @param store backing map for routing context data
     * @return stubbed routing context
     */
    private static RoutingContext stubContext(Map<String, Object> store) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        SocketAddress remote = mock(SocketAddress.class);
        HttpConnection connection = mock(HttpConnection.class);

        when(remote.host()).thenReturn("127.0.0.1");
        when(remote.port()).thenReturn(80);
        when(request.remoteAddress()).thenReturn(remote);
        when(request.scheme()).thenReturn("http");
        HostAndPort authority = mock(HostAndPort.class);
        when(authority.host()).thenReturn("localhost");
        when(request.authority()).thenReturn(authority);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("X-Forwarded-Proto")).thenReturn(null);
        when(request.getHeader("X-Forwarded-Host")).thenReturn(null);
        when(request.connection()).thenReturn(connection);
        when(connection.sslSession()).thenReturn(null);

        when(ctx.request()).thenReturn(request);
        when(ctx.put(anyString(), any())).thenAnswer(inv -> {
            store.put(inv.getArgument(0, String.class), inv.getArgument(1));
            return ctx;
        });
        when(ctx.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0, String.class)));

        return ctx;
    }
}
