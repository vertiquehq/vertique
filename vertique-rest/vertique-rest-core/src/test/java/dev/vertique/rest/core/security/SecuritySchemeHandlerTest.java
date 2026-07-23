// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import io.vertx.ext.web.handler.AuthenticationHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link SecuritySchemeHandler#configure} accepts the neutral
 * {@link SecuritySchemeRegistry} rather than the Vert.x OpenAPI {@code RouterBuilder} (FR-022).
 *
 * <p>The test is a compile-time contract check: a handler implementation that references only
 * {@link SecuritySchemeRegistry} in its {@code configure} body must satisfy the interface, proving
 * {@code RouterBuilder} is no longer part of the SPI surface.
 */
class SecuritySchemeHandlerTest {

    /**
     * Minimal {@link SecuritySchemeHandler} implementation whose {@code configure} body references
     * only the neutral {@link SecuritySchemeRegistry}. The presence of this class compiling proves
     * the migrated signature.
     */
    private static final class NeutralSchemeHandler implements SecuritySchemeHandler {

        private final AuthenticationHandler authHandler;

        NeutralSchemeHandler(AuthenticationHandler authHandler) {
            this.authHandler = authHandler;
        }

        @Override
        public String schemeName() {
            return "bearerAuth";
        }

        @Override
        public void configure(SecuritySchemeRegistry registry) {
            registry.authenticationHandler(authHandler);
        }
    }

    @Test
    @DisplayName("configure(SecuritySchemeRegistry) registers the authentication handler on the registry")
    void configureAcceptsSecuritySchemeRegistry() {
        AuthenticationHandler authHandler = mock(AuthenticationHandler.class);
        SecuritySchemeHandler handler = new NeutralSchemeHandler(authHandler);

        SecuritySchemeRegistry registry = mock(SecuritySchemeRegistry.class);
        handler.configure(registry);

        assertNotNull(handler.schemeName());
        verify(registry, times(1)).authenticationHandler(authHandler);
    }
}
