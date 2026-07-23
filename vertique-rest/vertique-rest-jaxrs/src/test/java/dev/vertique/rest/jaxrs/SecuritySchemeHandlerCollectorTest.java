// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import io.vertx.ext.web.handler.AuthenticationHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the fail-closed duplicate-scheme detection in {@link SecuritySchemeHandlerCollector}.
 *
 * <p>Two {@link SecuritySchemeHandler}s claiming the same {@code schemeName()} are a configuration
 * error: without detection, whichever Dagger set iteration recorded last would silently win, making
 * it non-deterministic which authentication handler guards the scheme. The collector must instead
 * fail startup with a {@link RestConfigurationException} that names the conflicting scheme and the
 * contributing handler classes. Distinct scheme names must record independently and both resolve.
 */
class SecuritySchemeHandlerCollectorTest {

    /** Test {@link SecuritySchemeHandler} that reports a fixed scheme name and registers a handler. */
    static final class StubSchemeHandler implements SecuritySchemeHandler {
        private final String schemeName;
        private final AuthenticationHandler authHandler;

        StubSchemeHandler(String schemeName, AuthenticationHandler authHandler) {
            this.schemeName = schemeName;
            this.authHandler = authHandler;
        }

        @Override
        public String schemeName() {
            return schemeName;
        }

        @Override
        public void configure(SecuritySchemeRegistry registry) {
            registry.authenticationHandler(authHandler);
        }
    }

    @Test
    @DisplayName("Two handlers with the same schemeName fail startup with RestConfigurationException")
    void duplicateSchemeName_throws() {
        SecuritySchemeHandlerCollector collector = new SecuritySchemeHandlerCollector();
        AuthenticationHandler first = mock(AuthenticationHandler.class);
        AuthenticationHandler second = mock(AuthenticationHandler.class);

        StubSchemeHandler handlerA = new StubSchemeHandler("bearerAuth", first);
        StubSchemeHandler handlerB = new StubSchemeHandler("bearerAuth", second);

        collector.record(handlerA, first);

        RestConfigurationException ex =
                assertThrows(RestConfigurationException.class, () -> collector.record(handlerB, second));

        assertTrue(ex.getMessage().contains("bearerAuth"), "message should name the conflicting scheme");
        assertTrue(
                ex.getMessage().contains(StubSchemeHandler.class.getName()),
                "message should name the contributing handler classes");
    }

    @Test
    @DisplayName("Distinct scheme names record independently and both resolve")
    void distinctSchemeNames_bothResolve() {
        SecuritySchemeHandlerCollector collector = new SecuritySchemeHandlerCollector();
        AuthenticationHandler bearer = mock(AuthenticationHandler.class);
        AuthenticationHandler apiKey = mock(AuthenticationHandler.class);

        collector.record(new StubSchemeHandler("bearerAuth", bearer), bearer);
        collector.record(new StubSchemeHandler("apiKeyAuth", apiKey), apiKey);

        assertSame(bearer, collector.handlerFor("bearerAuth").orElseThrow());
        assertSame(apiKey, collector.handlerFor("apiKeyAuth").orElseThrow());
    }
}
