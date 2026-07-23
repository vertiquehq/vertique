// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.middleware;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.rest.core.config.DefaultHeadersConfig;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultHeadersMiddleware}.
 *
 * <p>Verifies middleware scope and ordering, default header values, configurable header
 * overrides, header suppression via null/blank values, and custom header application.
 */
class DefaultHeadersMiddlewareTest {

    // --- Middleware contract ---

    @Test
    @DisplayName("Should have ROOT scope")
    void shouldHaveRootScope() {
        DefaultHeadersMiddleware middleware =
                new DefaultHeadersMiddleware(DefaultHeadersConfig.builder().build());
        assertEquals(MiddlewareScope.ROOT, middleware.scope());
    }

    @Test
    @DisplayName("Should have priority 10")
    void shouldHaveOrder10() {
        DefaultHeadersMiddleware middleware =
                new DefaultHeadersMiddleware(DefaultHeadersConfig.builder().build());
        assertEquals(10, middleware.priority());
    }

    // --- Default headers ---

    @Test
    @DisplayName("Should set Cache-Control, X-Content-Type-Options, and X-Frame-Options by default")
    void shouldSetDefaultHeaders() {
        DefaultHeadersMiddleware middleware =
                new DefaultHeadersMiddleware(DefaultHeadersConfig.builder().build());
        RoutingContext ctx = mockContext();

        middleware.handle(ctx);

        verify(ctx.response()).putHeader("Cache-Control", "no-store");
        verify(ctx.response()).putHeader("X-Content-Type-Options", "nosniff");
        verify(ctx.response()).putHeader("X-Frame-Options", "DENY");
        verify(ctx).next();
    }

    // --- Configurable headers ---

    @Test
    @DisplayName("Should apply configured Cache-Control override")
    void shouldApplyCacheControlOverride() {
        DefaultHeadersConfig config = DefaultHeadersConfig.builder()
                .cacheControl("no-cache, no-store, must-revalidate")
                .build();
        DefaultHeadersMiddleware middleware = new DefaultHeadersMiddleware(config);
        RoutingContext ctx = mockContext();

        middleware.handle(ctx);

        verify(ctx.response()).putHeader("Cache-Control", "no-cache, no-store, must-revalidate");
    }

    @Test
    @DisplayName("Should apply Strict-Transport-Security when configured")
    void shouldApplyStrictTransportSecurity() {
        DefaultHeadersConfig config = DefaultHeadersConfig.builder()
                .strictTransportSecurity("max-age=31536000; includeSubDomains")
                .build();
        DefaultHeadersMiddleware middleware = new DefaultHeadersMiddleware(config);
        RoutingContext ctx = mockContext();

        middleware.handle(ctx);

        verify(ctx.response()).putHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
    }

    @Test
    @DisplayName("Should apply Referrer-Policy when configured")
    void shouldApplyReferrerPolicy() {
        DefaultHeadersConfig config =
                DefaultHeadersConfig.builder().referrerPolicy("no-referrer").build();
        DefaultHeadersMiddleware middleware = new DefaultHeadersMiddleware(config);
        RoutingContext ctx = mockContext();

        middleware.handle(ctx);

        verify(ctx.response()).putHeader("Referrer-Policy", "no-referrer");
    }

    // --- Header suppression ---

    @Test
    @DisplayName("Should suppress Cache-Control when set to null")
    void shouldSuppressCacheControlWhenNull() {
        DefaultHeadersConfig config =
                DefaultHeadersConfig.builder().cacheControl(null).build();
        DefaultHeadersMiddleware middleware = new DefaultHeadersMiddleware(config);
        RoutingContext ctx = mockContext();

        middleware.handle(ctx);

        verify(ctx.response(), never()).putHeader(eq("Cache-Control"), anyString());
    }

    @Test
    @DisplayName("Should suppress X-Frame-Options when set to blank")
    void shouldSuppressXFrameOptionsWhenBlank() {
        DefaultHeadersConfig config =
                DefaultHeadersConfig.builder().frameOptions("").build();
        DefaultHeadersMiddleware middleware = new DefaultHeadersMiddleware(config);
        RoutingContext ctx = mockContext();

        middleware.handle(ctx);

        verify(ctx.response(), never()).putHeader(eq("X-Frame-Options"), anyString());
    }

    // --- Custom headers ---

    @Test
    @DisplayName("Should apply additional custom headers from additionalHeaders map")
    void shouldApplyCustomHeaders() {
        DefaultHeadersConfig config = DefaultHeadersConfig.builder()
                .header("X-Custom-Header", "custom-value")
                .header("X-Another-Header", "another-value")
                .build();
        DefaultHeadersMiddleware middleware = new DefaultHeadersMiddleware(config);
        RoutingContext ctx = mockContext();

        middleware.handle(ctx);

        verify(ctx.response()).putHeader("X-Custom-Header", "custom-value");
        verify(ctx.response()).putHeader("X-Another-Header", "another-value");
    }

    // --- ctx.next() called ---

    @Test
    @DisplayName("Should always call ctx.next() after setting headers")
    void shouldCallNext() {
        DefaultHeadersMiddleware middleware =
                new DefaultHeadersMiddleware(DefaultHeadersConfig.builder().build());
        RoutingContext ctx = mockContext();

        middleware.handle(ctx);

        verify(ctx).next();
    }

    // --- toHeaderMap ---

    @Test
    @DisplayName("Should include all known and additional headers in toHeaderMap()")
    void shouldCombineKnownAndAdditionalHeadersInToHeaderMap() {
        DefaultHeadersConfig config = DefaultHeadersConfig.builder()
                .cacheControl("no-store")
                .strictTransportSecurity("max-age=31536000")
                .header("X-Custom", "value")
                .build();

        Map<String, String> map = config.toHeaderMap();

        assertEquals("no-store", map.get("Cache-Control"));
        assertEquals("max-age=31536000", map.get("Strict-Transport-Security"));
        assertEquals("value", map.get("X-Custom"));
        assertTrue(map.containsKey("X-Content-Type-Options"));
        assertTrue(map.containsKey("X-Frame-Options"));
    }

    // --- Helpers ---

    /**
     * Builds a mocked {@link RoutingContext} with a stubbed HTTP response that returns itself
     * from {@code putHeader}.
     *
     * @return the mocked routing context
     */
    private RoutingContext mockContext() {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(ctx.response()).thenReturn(response);
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        return ctx;
    }
}
