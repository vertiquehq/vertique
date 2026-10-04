// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.correlation.CorrelationSessionRef;
import dev.vertique.correlation.CorrelationContextMutator;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.routing.RouteRegistration;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RoutingContext;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link JwtCorrelationSessionContributor} routing-handler behavior.
 */
class JwtCorrelationSessionContributorTest {

    @Test
    @DisplayName("priority is 55 — after claims validation, before identity resolution")
    void priority() {
        assertEquals(
                55,
                new JwtCorrelationSessionContributor(
                                new JwtCorrelationSessionEnricher(JwtSessionCorrelationConfig.defaults()),
                                mock(CorrelationContextMutator.class))
                        .priority());
    }

    @Test
    @DisplayName("authenticated user with sid binds CorrelationSessionRef via the mutator")
    void bindsSessionFromSid() {
        CorrelationContextMutator mutator = mock(CorrelationContextMutator.class);
        JwtCorrelationSessionContributor contributor = new JwtCorrelationSessionContributor(
                new JwtCorrelationSessionEnricher(JwtSessionCorrelationConfig.defaults()), mutator);

        AtomicReference<Handler<RoutingContext>> handler = new AtomicReference<>();
        RouteRegistration route = mock(RouteRegistration.class);
        when(route.addHandler(any())).thenAnswer(inv -> {
            handler.set(inv.getArgument(0));
            return route;
        });
        OperationRegistrationContext registration = mock(OperationRegistrationContext.class);
        when(registration.route()).thenReturn(route);
        contributor.contribute(registration);

        RoutingContext ctx = mock(RoutingContext.class);
        when(ctx.user())
                .thenReturn(User.create(new JsonObject().put("sid", "session-1").put("sub", "user-1")));
        handler.get().handle(ctx);

        ArgumentCaptor<CorrelationSessionRef> captor = ArgumentCaptor.forClass(CorrelationSessionRef.class);
        verify(mutator).setSession(captor.capture());
        assertEquals("session-1", captor.getValue().id());
        assertEquals("jwt-sid", captor.getValue().kind());
        verify(ctx).next();
    }

    @Test
    @DisplayName("anonymous request does not call setSession")
    void anonymousSkips() {
        CorrelationContextMutator mutator = mock(CorrelationContextMutator.class);
        JwtCorrelationSessionContributor contributor = new JwtCorrelationSessionContributor(
                new JwtCorrelationSessionEnricher(JwtSessionCorrelationConfig.defaults()), mutator);

        AtomicReference<Handler<RoutingContext>> handler = new AtomicReference<>();
        RouteRegistration route = mock(RouteRegistration.class);
        when(route.addHandler(any())).thenAnswer(inv -> {
            handler.set(inv.getArgument(0));
            return route;
        });
        OperationRegistrationContext registration = mock(OperationRegistrationContext.class);
        when(registration.route()).thenReturn(route);
        contributor.contribute(registration);

        RoutingContext ctx = mock(RoutingContext.class);
        when(ctx.user()).thenReturn(null);
        handler.get().handle(ctx);

        verify(mutator, never()).setSession(any());
        verify(ctx).next();
    }
}
