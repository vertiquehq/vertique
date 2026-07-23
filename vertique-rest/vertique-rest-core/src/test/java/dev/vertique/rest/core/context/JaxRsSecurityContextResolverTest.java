// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.security.SecurityRuntime;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.SecurityContext;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JaxRsSecurityContextResolver}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@link JaxRsSecurityContextResolver#priority()} returns {@code 110}.
 *   <li>When {@code Optional<SecurityRuntime>} is empty, {@code resolve} returns empty for any
 *       type.
 *   <li>When the runtime is present, {@code resolve(SecurityContext.class, ctx)} calls
 *       {@link SecurityRuntime#toJaxRs(dev.vertique.security.SecurityContext, boolean)} with
 *       the result of {@link SecurityRuntime#current()} and the request SSL flag, and returns the
 *       bridge instance.
 *   <li>When the runtime is present but {@code toJaxRs} returns a non-null bridge even when
 *       {@code current()} is {@code null}, the resolver returns a non-empty result.
 *   <li>{@code resolve(String.class, ctx)} always returns empty (wrong type).
 * </ul>
 */
class JaxRsSecurityContextResolverTest {

    // --- helpers ---

    /** Creates a mock RoutingContext whose request reports the given SSL state. */
    private static RoutingContext mockCtx(boolean ssl) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
        when(request.isSSL()).thenReturn(ssl);
        return ctx;
    }

    // --- priority ---

    @Nested
    @DisplayName("priority")
    class Priority {

        @Test
        @DisplayName("priority() returns 110")
        void priorityIsOneHundredTen() {
            JaxRsSecurityContextResolver resolver = new JaxRsSecurityContextResolver(Optional.empty());
            assertEquals(110, resolver.priority());
        }
    }

    // --- absent security runtime ---

    @Nested
    @DisplayName("absent SecurityRuntime")
    class AbsentRuntime {

        @Test
        @DisplayName("resolve returns empty when SecurityRuntime is absent")
        void returnsEmptyWhenRuntimeAbsent() {
            JaxRsSecurityContextResolver resolver = new JaxRsSecurityContextResolver(Optional.empty());
            RoutingContext ctx = mockCtx(false);

            Optional<SecurityContext> result = resolver.resolve(SecurityContext.class, ctx);

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("resolve(String.class) returns empty when SecurityRuntime is absent")
        void returnsEmptyForWrongTypeWhenRuntimeAbsent() {
            JaxRsSecurityContextResolver resolver = new JaxRsSecurityContextResolver(Optional.empty());
            RoutingContext ctx = mockCtx(false);

            Optional<String> result = resolver.resolve(String.class, ctx);

            assertFalse(result.isPresent());
        }
    }

    // --- present security runtime ---

    @Nested
    @DisplayName("present SecurityRuntime")
    class PresentRuntime {

        @Test
        @DisplayName("resolve returns bridge from toJaxRs; passes current() and SSL flag")
        void resolvesJaxRsSecurityContext() {
            SecurityRuntime runtime = mock(SecurityRuntime.class);
            dev.vertique.security.SecurityContext frameworkSc = mock(dev.vertique.security.SecurityContext.class);
            SecurityContext jaxRsBridge = mock(SecurityContext.class);

            when(runtime.current()).thenReturn(frameworkSc);
            when(runtime.toJaxRs(frameworkSc, false)).thenReturn(jaxRsBridge);

            RoutingContext ctx = mockCtx(false);
            JaxRsSecurityContextResolver resolver = new JaxRsSecurityContextResolver(Optional.of(runtime));

            Optional<SecurityContext> result = resolver.resolve(SecurityContext.class, ctx);

            assertTrue(result.isPresent());
            assertEquals(jaxRsBridge, result.get());
            verify(runtime).toJaxRs(eq(frameworkSc), eq(false));
        }

        @Test
        @DisplayName("resolve passes SSL=true to toJaxRs when request is over SSL")
        void passesSslFlagToJaxRs() {
            SecurityRuntime runtime = mock(SecurityRuntime.class);
            SecurityContext jaxRsBridge = mock(SecurityContext.class);

            when(runtime.current()).thenReturn(null);
            when(runtime.toJaxRs(null, true)).thenReturn(jaxRsBridge);

            RoutingContext ctx = mockCtx(true);
            JaxRsSecurityContextResolver resolver = new JaxRsSecurityContextResolver(Optional.of(runtime));

            Optional<SecurityContext> result = resolver.resolve(SecurityContext.class, ctx);

            assertTrue(result.isPresent());
            verify(runtime).toJaxRs(eq(null), eq(true));
        }

        @Test
        @DisplayName("resolve returns non-empty when current() is null but toJaxRs returns a bridge")
        void returnsNonEmptyWhenCurrentNullButBridgePresent() {
            SecurityRuntime runtime = mock(SecurityRuntime.class);
            SecurityContext jaxRsBridge = mock(SecurityContext.class);

            when(runtime.current()).thenReturn(null);
            when(runtime.toJaxRs(null, false)).thenReturn(jaxRsBridge);

            RoutingContext ctx = mockCtx(false);
            JaxRsSecurityContextResolver resolver = new JaxRsSecurityContextResolver(Optional.of(runtime));

            Optional<SecurityContext> result = resolver.resolve(SecurityContext.class, ctx);

            assertTrue(result.isPresent());
            assertEquals(jaxRsBridge, result.get());
        }

        @Test
        @DisplayName("resolve returns empty when toJaxRs returns null (no bridge factory)")
        void returnsEmptyWhenToJaxRsReturnsNull() {
            SecurityRuntime runtime = mock(SecurityRuntime.class);

            when(runtime.current()).thenReturn(null);
            when(runtime.toJaxRs(any(), anyBoolean())).thenReturn(null);

            RoutingContext ctx = mockCtx(false);
            JaxRsSecurityContextResolver resolver = new JaxRsSecurityContextResolver(Optional.of(runtime));

            Optional<SecurityContext> result = resolver.resolve(SecurityContext.class, ctx);

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("resolve(String.class) returns empty even when SecurityRuntime is present")
        void returnsEmptyForWrongTypeEvenWithRuntime() {
            SecurityRuntime runtime = mock(SecurityRuntime.class);
            JaxRsSecurityContextResolver resolver = new JaxRsSecurityContextResolver(Optional.of(runtime));
            RoutingContext ctx = mockCtx(false);

            Optional<String> result = resolver.resolve(String.class, ctx);

            assertFalse(result.isPresent());
        }
    }
}
