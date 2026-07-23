// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.security.DefaultCredentialRejectionReporter;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.handler.AuthenticationHandler;
import io.vertx.ext.web.handler.ChainAuthHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Reproduces finding C2: the framework's own JWT scheme handler must be composable into a Vert.x
 * {@link ChainAuthHandler} OR chain.
 *
 * <p>The registrar composes alternative {@code @SecurityRequirement}s with
 * {@link ChainAuthHandler#any()} and {@code chain.add(handler)}. Vert.x 5.1.2's
 * {@code ChainAuthHandlerImpl.add} casts each member to
 * {@code io.vertx.ext.web.handler.impl.AuthenticationHandlerInternal}. The handler the framework
 * registers — {@code DelegatingJwtAuthHandler} (obtained here via {@link
 * JwtBearerSecuritySchemeHandler#configure}) — implements {@code JWTAuthHandler} but not the internal
 * interface, so {@code add(...)} throws {@code ClassCastException} at router build. An operation
 * declaring two bearer requirements would crash startup.
 *
 * <p>This test exercises the real handler the framework registers (not a {@code
 * SimpleAuthenticationHandler}, which IS internal and masks the defect), asserting the OR chain BUILDS
 * without a CCE. The end-to-end request-time proof — that each alternative authenticates its own
 * issuer's token and that an invalid/missing token yields 401 — lives in
 * {@code DelegatingJwtAuthHandlerOrChainIT}.
 */
class DelegatingJwtAuthHandlerChainComposabilityTest {

    /** Capturing scheme-registry that records the {@link AuthenticationHandler} a handler registers. */
    private static final class CapturingRegistry implements SecuritySchemeRegistry {
        private AuthenticationHandler captured;

        @Override
        public void authenticationHandler(AuthenticationHandler handler) {
            this.captured = handler;
        }
    }

    /**
     * Builds the real {@code DelegatingJwtAuthHandler} the framework registers for a JWT bearer
     * scheme, by configuring a {@link JwtBearerSecuritySchemeHandler} and capturing the registered
     * authentication handler.
     *
     * @param schemeName the scheme name
     * @return the registered authentication handler (a {@code DelegatingJwtAuthHandler})
     */
    private static AuthenticationHandler realJwtHandler(String schemeName) {
        JWTAuth jwtAuth = Mockito.mock(JWTAuth.class);
        ContextHolder holder = Mockito.mock(ContextHolder.class);
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        CorrelationContext correlation = factory.create(
                new CorrelationIdentifier("req-001", "test"), new CorrelationIdentifier("cor-001", "test"));
        Mockito.when(holder.current(CorrelationContext.class)).thenReturn(Optional.of(correlation));
        DefaultCredentialRejectionReporter reporter =
                new DefaultCredentialRejectionReporter(holder, new SecurityEventEmitter(Set.of()));

        JwtBearerSecuritySchemeHandler schemeHandler = new JwtBearerSecuritySchemeHandler(
                schemeName, jwtAuth, JwtValidationConfig.builder().build(), reporter);
        CapturingRegistry registry = new CapturingRegistry();
        schemeHandler.configure(registry);
        return registry.captured;
    }

    @Test
    @DisplayName("Two real DelegatingJwtAuthHandlers compose into a ChainAuthHandler.any() OR without a CCE (C2)")
    void twoRealJwtHandlersComposeIntoOrChainWithoutClassCastException() {
        List<AuthenticationHandler> handlers = new ArrayList<>();
        handlers.add(realJwtHandler("schemeA"));
        handlers.add(realJwtHandler("schemeB"));

        // Mirror exactly what JaxRsRouteRegistrar.applySecurity does for the multi-scheme OR path.
        assertDoesNotThrow(
                () -> {
                    ChainAuthHandler orChain = ChainAuthHandler.any();
                    handlers.forEach(orChain::add);
                },
                "two real JWT scheme handlers must compose into a ChainAuthHandler.any() OR chain "
                        + "without a ClassCastException at router build");
    }
}
