// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.correlation.CorrelationSessionRef;
import dev.vertique.correlation.CorrelationContextMutator;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.routing.RouteRegistration;
import dev.vertique.rest.security.RestAuthenticationEvidence;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.verification.CustomVerificationSource;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RoutingContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
    @DisplayName("verified JWT evidence + sid claim binds CorrelationSessionRef via the mutator")
    void bindsSessionFromSidWhenJwtVerified() {
        CorrelationContextMutator mutator = mock(CorrelationContextMutator.class);
        Handler<RoutingContext> handler = contribute(mutator);

        RoutingContext ctx = stubContext();
        RestAuthenticationEvidence.append(ctx, jwtEvidence());
        when(ctx.user())
                .thenReturn(User.create(new JsonObject().put("sid", "session-1").put("sub", "user-1")));
        handler.handle(ctx);

        ArgumentCaptor<CorrelationSessionRef> captor = ArgumentCaptor.forClass(CorrelationSessionRef.class);
        verify(mutator).setSession(captor.capture());
        assertEquals("session-1", captor.getValue().id());
        assertEquals("jwt-sid", captor.getValue().kind());
        verify(ctx).next();
    }

    @Test
    @DisplayName("ambient user without JWT evidence does not call setSession")
    void ambientUserWithoutJwtEvidenceSkips() {
        CorrelationContextMutator mutator = mock(CorrelationContextMutator.class);
        Handler<RoutingContext> handler = contribute(mutator);

        RoutingContext ctx = stubContext();
        when(ctx.user()).thenReturn(User.create(new JsonObject().put("sid", "not-verified-by-jwt")));
        handler.handle(ctx);

        verify(mutator, never()).setSession(any());
        verify(ctx).next();
    }

    @Test
    @DisplayName("non-JWT authentication evidence does not bind a jwt-claim session")
    void nonJwtEvidenceSkips() {
        CorrelationContextMutator mutator = mock(CorrelationContextMutator.class);
        Handler<RoutingContext> handler = contribute(mutator);

        RoutingContext ctx = stubContext();
        RestAuthenticationEvidence.append(
                ctx,
                new AuthenticationEvidence(
                        DefaultAuthMethod.apiKey(),
                        Optional.of("key-1"),
                        Instant.now(),
                        Optional.empty(),
                        new CustomVerificationSource("api-key", Map.of()),
                        Map.of()));
        when(ctx.user()).thenReturn(User.create(new JsonObject().put("sid", "session-from-other-scheme")));
        handler.handle(ctx);

        verify(mutator, never()).setSession(any());
        verify(ctx).next();
    }

    @Test
    @DisplayName("anonymous request does not call setSession")
    void anonymousSkips() {
        CorrelationContextMutator mutator = mock(CorrelationContextMutator.class);
        Handler<RoutingContext> handler = contribute(mutator);

        RoutingContext ctx = stubContext();
        when(ctx.user()).thenReturn(null);
        handler.handle(ctx);

        verify(mutator, never()).setSession(any());
        verify(ctx).next();
    }

    private static Handler<RoutingContext> contribute(CorrelationContextMutator mutator) {
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
        return handler.get();
    }

    /** Routing context whose {@code get}/{@code put} back a mutable map (evidence storage). */
    private static RoutingContext stubContext() {
        Map<String, Object> store = new HashMap<>();
        RoutingContext ctx = mock(RoutingContext.class);
        when(ctx.get(any())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        doAnswer(inv -> {
                    store.put(inv.getArgument(0), inv.getArgument(1));
                    return ctx;
                })
                .when(ctx)
                .put(any(), any());
        return ctx;
    }

    private static AuthenticationEvidence jwtEvidence() {
        return new AuthenticationEvidence(
                DefaultAuthMethod.jwt(),
                Optional.of("tok"),
                Instant.now(),
                Optional.empty(),
                new CustomVerificationSource("jwt", Map.of()),
                Map.of("sub", "user-1"));
    }
}
