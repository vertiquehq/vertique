// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.security.SecurityContext;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.impl.UserContextInternal;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #406 — the no-scheme admit path must clear an ambient {@link SecurityRuntime} holder
 * binding so {@code McpRequestDispatcher#establishedSecurity} cannot snapshot a foreign ROOT-
 * middleware identity before MCP identity resolution runs.
 */
class McpIdentityEstablisherForeignSecurityClearTest {

    @Test
    @DisplayName("no-scheme admit clears ambient SecurityRuntime holder binding")
    void noSchemeAdmitClearsForeignSecurityRuntime() {
        AtomicBoolean cleared = new AtomicBoolean();
        AtomicReference<SecurityContext> bound = new AtomicReference<>(mock(SecurityContext.class));
        SecurityRuntime securityRuntime = recordingRuntime(bound, cleared);
        McpIdentityEstablisher establisher = establisher(null, securityRuntime);

        RoutingContext context = routingContextWithClearableUser();
        AtomicBoolean advanced = new AtomicBoolean();
        doAnswer(invocation -> {
                    advanced.set(true);
                    return null;
                })
                .when(context)
                .next();

        establisher.admit(context);

        assertThat(cleared.get()).isTrue();
        assertThat(bound.get()).isNull();
        assertThat(advanced.get()).isTrue();
    }

    @Test
    @DisplayName("scheme-configured admit with clean state does not clear SecurityRuntime")
    void schemeConfiguredAdmitDoesNotClearSecurityRuntime() {
        AtomicBoolean cleared = new AtomicBoolean();
        AtomicReference<SecurityContext> bound = new AtomicReference<>(mock(SecurityContext.class));
        SecurityRuntime securityRuntime = recordingRuntime(bound, cleared);
        SecurityContext foreign = bound.get();
        McpIdentityEstablisher establisher = establisher("bearer", securityRuntime);

        RoutingContext context = routingContextWithClearableUser();
        when(context.user()).thenReturn(null);
        AtomicBoolean advanced = new AtomicBoolean();
        doAnswer(invocation -> {
                    advanced.set(true);
                    return null;
                })
                .when(context)
                .next();

        establisher.admit(context);

        assertThat(cleared.get()).isFalse();
        assertThat(bound.get()).isSameAs(foreign);
        assertThat(advanced.get()).isTrue();
    }

    private static McpIdentityEstablisher establisher(String scheme, SecurityRuntime securityRuntime) {
        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .serverName("test")
                .serverVersion("1.0")
                .authenticationScheme(scheme)
                .build();

        IdentityResolutionMiddleware identityResolution = mock(IdentityResolutionMiddleware.class);
        when(identityResolution.handlerFor(any())).thenReturn(RoutingContext::next);

        Set<RouteAuthHandler> handlers = Set.of();
        if (scheme != null) {
            RouteAuthHandler handler = mock(RouteAuthHandler.class);
            when(handler.schemeName()).thenReturn(scheme);
            when(handler.createOptionalHandler()).thenReturn(Optional.of(RoutingContext::next));
            handlers = Set.of(handler);
        }

        return new McpIdentityEstablisher(config, handlers, identityResolution, securityRuntime);
    }

    private static SecurityRuntime recordingRuntime(AtomicReference<SecurityContext> bound, AtomicBoolean cleared) {
        return new SecurityRuntime() {
            @Override
            public SecurityContext current() {
                return bound.get();
            }

            @Override
            public ContextHolder.Scope bindCurrent(SecurityContext context) {
                bound.set(context);
                return () -> {};
            }

            @Override
            public void clearCurrent() {
                cleared.set(true);
                bound.set(null);
            }

            @Override
            public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
                return null;
            }
        };
    }

    private static RoutingContext routingContextWithClearableUser() {
        RoutingContext context = mock(RoutingContext.class);
        UserContextInternal userContext = mock(UserContextInternal.class);
        when(context.userContext()).thenReturn(userContext);
        when(context.user()).thenReturn(null);
        return context;
    }
}
