// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.origin.RequestOrigin;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that {@link AuthorizationRequest#origin()} is threaded through to {@link
 * AuthorizationPolicy} evaluation — a policy can discriminate a decision on the invocation's {@link
 * InvocationOrigin#kind()}, denying for a non-interactive ingress kind while permitting an
 * interactive one.
 */
class InvocationOriginPolicyTest {

    /** Minimal {@link SecurityContext} stub for testing. */
    private static final SecurityContext STUB_CTX = new SecurityContext() {
        @Override
        public SecurityIdentity identity() {
            return SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-1", Map.of()));
        }

        @Override
        public AuthenticationState authentication() {
            return new AuthenticationState(
                    DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        }

        @Override
        public AuthorizationClaims authorization() {
            return AuthorizationClaims.empty();
        }

        @Override
        public Optional<RequestOrigin> origin() {
            return Optional.empty();
        }
    };

    private static final ResourceRef STUB_RESOURCE = new ResourceRef("order", "order-123", Map.of());

    /** A policy that denies non-interactive ({@code "camel"}) invocation kinds and permits {@code "rest"}. */
    private static final AuthorizationPolicy DENY_NON_INTERACTIVE_POLICY =
            request -> "camel".equals(request.origin().kind())
                    ? AuthorizationDecision.deny("NON_INTERACTIVE_ORIGIN")
                    : AuthorizationDecision.permit("INTERACTIVE_ORIGIN");

    @Test
    @DisplayName("denyForKindWhilePermitInteractive: a request whose origin kind is \"camel\" is denied")
    void denyForKindWhilePermitInteractive() {
        AuthorizationRequest camelRequest =
                new AuthorizationRequest(STUB_CTX, "READ", STUB_RESOURCE, InvocationOrigin.of("camel"), Map.of());

        AuthorizationDecision decision = DENY_NON_INTERACTIVE_POLICY.decide(camelRequest);

        assertFalse(decision.permitted(), "a camel-origin request must be denied by the policy");
    }

    @Test
    @DisplayName("denyForKindWhilePermitInteractive: a request whose origin kind is \"rest\" is permitted")
    void denyForKindWhilePermitInteractivePermitsRest() {
        AuthorizationRequest restRequest =
                new AuthorizationRequest(STUB_CTX, "READ", STUB_RESOURCE, InvocationOrigin.of("rest"), Map.of());

        AuthorizationDecision decision = DENY_NON_INTERACTIVE_POLICY.decide(restRequest);

        assertTrue(decision.permitted(), "a rest-origin request must be permitted by the policy");
    }
}
