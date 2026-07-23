// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.routing.RouteRegistration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IdentityResolutionContributor}.
 *
 * <p>Verifies the contributor's priority constant and that it mounts the middleware on the route
 * via {@code addHandler()}.
 */
class IdentityResolutionContributorTest {

    @Test
    @DisplayName("priority() returns PRIORITY constant (80) — before authorization band (100+)")
    void priorityIsCorrect() {
        IdentityResolutionMiddleware middleware = mock(IdentityResolutionMiddleware.class);
        IdentityResolutionContributor contributor = new IdentityResolutionContributor(middleware);

        assertEquals(IdentityResolutionContributor.PRIORITY, contributor.priority());
        assertEquals(80, contributor.priority());
    }

    @Test
    @DisplayName("contribute() adds the middleware via route.addHandler()")
    void contributeAddsMiddlewareViaAddHandler() {
        IdentityResolutionMiddleware middleware = mock(IdentityResolutionMiddleware.class);
        IdentityResolutionContributor contributor = new IdentityResolutionContributor(middleware);

        RouteRegistration route = mock(RouteRegistration.class);
        when(route.addHandler(middleware)).thenReturn(route);

        OperationRegistrationContext registrationContext = mock(OperationRegistrationContext.class);
        when(registrationContext.route()).thenReturn(route);
        when(registrationContext.operationId()).thenReturn("testOperation");

        contributor.contribute(registrationContext);

        verify(route).addHandler(middleware);
    }

    @Test
    @DisplayName("Constructor rejects null middleware")
    void constructorRejectsNullMiddleware() {
        assertThrows(NullPointerException.class, () -> new IdentityResolutionContributor(null));
    }
}
