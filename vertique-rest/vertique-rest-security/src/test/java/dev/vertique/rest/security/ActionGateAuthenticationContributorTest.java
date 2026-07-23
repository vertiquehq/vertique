// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.routing.RouteRegistration;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.security.authz.ActionRef;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ActionGateAuthenticationContributor}.
 *
 * <p>Verifies the contributor's priority (before identity resolution) and its installation policy:
 * <ul>
 *   <li>an action-only route ({@link SecurityPolicy.None} with a present {@code @RequiresAction})
 *       installs the {@link RouteAuthHandler}'s handler via {@code route.addHandler()};</li>
 *   <li>a {@link SecurityPolicy.None} route with no action installs nothing;</li>
 *   <li>a restrictive policy ({@link SecurityPolicy.AuthenticatedOnly}/{@link SecurityPolicy.Constrained})
 *       installs nothing even with an action present — those routes already drive an OpenAPI security
 *       handler, so adding a second would double-authenticate;</li>
 *   <li>an action-only route with no, or multiple ambiguous, {@link RouteAuthHandler} bindings fails
 *       startup (fail-closed).</li>
 * </ul>
 */
class ActionGateAuthenticationContributorTest {

    private static final ActionRef CONTENT_READ = ActionRef.parse("cms.content.read");

    /** A {@link RouteAuthHandler} returning a recognizable handler instance for {@code addHandler} assertions. */
    private static final class StubAuthHandler implements RouteAuthHandler {
        private final String scheme;
        private final Handler<RoutingContext> handler = RoutingContext::next;

        StubAuthHandler(String scheme) {
            this.scheme = scheme;
        }

        @Override
        public String schemeName() {
            return scheme;
        }

        @Override
        public Handler<RoutingContext> createHandler() {
            return handler;
        }
    }

    /** Priority of {@code rest-auth-jwt}'s {@code JwtClaimsValidatorContributor}, asserted as a literal
     * because rest-security must not depend on rest-auth-jwt. Authentication on an action-only route must
     * run before this so the validator sees a populated {@code ctx.user()} rather than skipping. */
    private static final int JWT_CLAIMS_VALIDATOR_PRIORITY = 50;

    @Test
    @DisplayName("priority() returns PRIORITY (40) — before JWT claims validation (50) and identity resolution (80)")
    void priorityIsBeforeClaimsValidationAndIdentityResolution() {
        ActionGateAuthenticationContributor contributor =
                new ActionGateAuthenticationContributor(Set.of(new StubAuthHandler("bearerAuth")));
        assertEquals(ActionGateAuthenticationContributor.PRIORITY, contributor.priority());
        assertEquals(40, contributor.priority());
        assertEquals(
                true,
                contributor.priority() < JWT_CLAIMS_VALIDATOR_PRIORITY,
                "must run before the JWT claims validator (50) so the validator sees the authenticated user "
                        + "on an action-only route, not a null user it would skip");
        assertEquals(
                true,
                contributor.priority() < IdentityResolutionContributor.PRIORITY,
                "must run before identity resolution so evidence is appended first");
    }

    @Nested
    @DisplayName("installs handler")
    class Installs {

        @Test
        @DisplayName("action-only route (None + @RequiresAction) installs the auth handler")
        void actionOnlyInstallsAuthHandler() {
            StubAuthHandler auth = new StubAuthHandler("bearerAuth");
            ActionGateAuthenticationContributor contributor = new ActionGateAuthenticationContributor(Set.of(auth));

            RouteRegistration route = mock(RouteRegistration.class);
            when(route.addHandler(any())).thenReturn(route);
            OperationRegistrationContext ctx = mock(OperationRegistrationContext.class);
            when(ctx.securityPolicy()).thenReturn(new SecurityPolicy.None());
            when(ctx.requiredAction()).thenReturn(Optional.of(CONTENT_READ));
            when(ctx.route()).thenReturn(route);
            when(ctx.operationId()).thenReturn("getContent");

            contributor.contribute(ctx);

            verify(route).addHandler(auth.createHandler());
        }
    }

    @Nested
    @DisplayName("installs nothing")
    class InstallsNothing {

        @Test
        @DisplayName("None policy with no action installs nothing")
        void noneWithoutActionInstallsNothing() {
            assertNoHandlerInstalled(new SecurityPolicy.None(), Optional.empty());
        }

        @Test
        @DisplayName("AuthenticatedOnly with action installs nothing (OpenAPI security already authenticates)")
        void authenticatedOnlyWithActionInstallsNothing() {
            assertNoHandlerInstalled(new SecurityPolicy.AuthenticatedOnly(), Optional.of(CONTENT_READ));
        }

        @Test
        @DisplayName("Constrained with action installs nothing (OpenAPI security already authenticates)")
        void constrainedWithActionInstallsNothing() {
            assertNoHandlerInstalled(
                    new SecurityPolicy.Constrained(List.of("editor"), List.of(), false), Optional.of(CONTENT_READ));
        }

        private void assertNoHandlerInstalled(SecurityPolicy policy, Optional<ActionRef> action) {
            ActionGateAuthenticationContributor contributor =
                    new ActionGateAuthenticationContributor(Set.of(new StubAuthHandler("bearerAuth")));

            RouteRegistration route = mock(RouteRegistration.class);
            OperationRegistrationContext ctx = mock(OperationRegistrationContext.class);
            when(ctx.securityPolicy()).thenReturn(policy);
            when(ctx.requiredAction()).thenReturn(action);

            contributor.contribute(ctx);

            verify(route, never()).addHandler(any());
        }
    }

    @Nested
    @DisplayName("fail-closed selection")
    class FailClosed {

        @Test
        @DisplayName("no RouteAuthHandler registered → action-only route fails startup")
        void noHandlerFailsStartup() {
            ActionGateAuthenticationContributor contributor = new ActionGateAuthenticationContributor(Set.of());

            OperationRegistrationContext ctx = mock(OperationRegistrationContext.class);
            when(ctx.securityPolicy()).thenReturn(new SecurityPolicy.None());
            when(ctx.requiredAction()).thenReturn(Optional.of(CONTENT_READ));
            when(ctx.operationId()).thenReturn("getContent");

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> contributor.contribute(ctx));
            assertSame(IllegalStateException.class, ex.getClass());
        }

        @Test
        @DisplayName("multiple RouteAuthHandler bindings → action-only route fails startup (no selector)")
        void multipleHandlersFailStartup() {
            ActionGateAuthenticationContributor contributor = new ActionGateAuthenticationContributor(
                    Set.of(new StubAuthHandler("bearerAuth"), new StubAuthHandler("apiKeyAuth")));

            OperationRegistrationContext ctx = mock(OperationRegistrationContext.class);
            when(ctx.securityPolicy()).thenReturn(new SecurityPolicy.None());
            when(ctx.requiredAction()).thenReturn(Optional.of(CONTENT_READ));
            when(ctx.operationId()).thenReturn("getContent");

            assertThrows(IllegalStateException.class, () -> contributor.contribute(ctx));
        }
    }

    @Test
    @DisplayName("constructor rejects null handler set")
    void constructorRejectsNull() {
        assertThrows(NullPointerException.class, () -> new ActionGateAuthenticationContributor(null));
    }
}
