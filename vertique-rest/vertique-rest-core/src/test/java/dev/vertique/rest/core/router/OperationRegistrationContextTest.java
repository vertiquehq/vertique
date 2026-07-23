// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.RouteRegistration;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.security.authz.ActionRef;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the migrated {@link OperationRegistrationContext} record components and constructors:
 * the operation is exposed as a neutral {@link RestOperationDescriptor} and the route as a neutral
 * {@link RouteRegistration} (FR-022), replacing the previous {@code OpenAPIRoute}/{@code RouterBuilder}
 * components. The identity, security-policy, and required-action accessors are unchanged.
 */
class OperationRegistrationContextTest {

    @Test
    @DisplayName("full constructor exposes the supplied descriptor and route registration")
    void fullConstructorExposesComponents() {
        RestOperationDescriptor descriptor = mock(RestOperationDescriptor.class);
        RouteRegistration route = mock(RouteRegistration.class);
        ActionRef action = ActionRef.parse("orders.order.approve");

        OperationRegistrationContext ctx = new OperationRegistrationContext(
                "orders.approve", new SecurityPolicy.None(), Optional.of(action), descriptor, route);

        assertEquals("orders.approve", ctx.operationId());
        assertSame(descriptor, ctx.operation(), "operation() must return the supplied RestOperationDescriptor");
        assertSame(route, ctx.route(), "route() must return the supplied RouteRegistration");
        assertEquals(Optional.of(action), ctx.requiredAction());
    }

    @Test
    @DisplayName("convenience constructor defaults requiredAction to Optional.empty()")
    void convenienceConstructorDefaultsRequiredAction() {
        RestOperationDescriptor descriptor = mock(RestOperationDescriptor.class);
        RouteRegistration route = mock(RouteRegistration.class);

        OperationRegistrationContext ctx =
                new OperationRegistrationContext("getUser", new SecurityPolicy.None(), descriptor, route);

        assertSame(descriptor, ctx.operation());
        assertSame(route, ctx.route());
        assertTrue(ctx.requiredAction().isEmpty(), "requiredAction must default to empty");
    }

    @Test
    @DisplayName("compact constructor normalizes a null requiredAction to Optional.empty()")
    void compactConstructorNormalizesNullRequiredAction() {
        RestOperationDescriptor descriptor = mock(RestOperationDescriptor.class);
        RouteRegistration route = mock(RouteRegistration.class);

        OperationRegistrationContext ctx =
                new OperationRegistrationContext("getUser", new SecurityPolicy.None(), null, descriptor, route);

        assertTrue(ctx.requiredAction().isEmpty(), "null requiredAction must be normalized to empty");
    }
}
