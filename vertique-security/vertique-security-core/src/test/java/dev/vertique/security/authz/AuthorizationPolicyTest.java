// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Smoke tests for the {@link AuthorizationPolicy} functional interface.
 *
 * <p>Verifies that a lambda implementation can be used as a policy and that the decision is
 * propagated correctly.
 */
class AuthorizationPolicyTest {

    /** Minimal {@link SecurityContext} stub for testing. */
    private static final SecurityContext STUB_CTX = new SecurityContext() {
        @Override
        public SecurityIdentity identity() {
            return SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-42", Map.of()));
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

    private static final ResourceRef STUB_RESOURCE = new ResourceRef("invoice", "inv-001", Map.of());

    @Test
    @DisplayName("lambda policy returning permit produces permitted=true decision")
    void lambdaPolicyPermit() {
        AuthorizationPolicy policy = req -> AuthorizationDecision.permit("OK");
        AuthorizationRequest request = new AuthorizationRequest(STUB_CTX, "READ", STUB_RESOURCE, Map.of());

        AuthorizationDecision decision = policy.decide(request);

        assertTrue(decision.permitted());
        assertEquals("OK", decision.reasonCode());
    }

    @Test
    @DisplayName("lambda policy returning deny produces permitted=false decision")
    void lambdaPolicyDeny() {
        AuthorizationPolicy policy = req -> AuthorizationDecision.deny("INSUFFICIENT_ROLE");
        AuthorizationRequest request = new AuthorizationRequest(STUB_CTX, "WRITE", STUB_RESOURCE, Map.of());

        AuthorizationDecision decision = policy.decide(request);

        assertTrue(!decision.permitted());
        assertEquals("INSUFFICIENT_ROLE", decision.reasonCode());
    }

    @Test
    @DisplayName("policy receives the full AuthorizationRequest in decide()")
    void policyReceivesRequest() {
        AuthorizationRequest[] captured = new AuthorizationRequest[1];
        AuthorizationPolicy policy = req -> {
            captured[0] = req;
            return AuthorizationDecision.permit("OK");
        };
        AuthorizationRequest request =
                new AuthorizationRequest(STUB_CTX, "DELETE", STUB_RESOURCE, Map.of("env", "prod"));

        policy.decide(request);

        assertEquals(request, captured[0]);
        assertEquals("DELETE", captured[0].action());
        assertEquals("prod", captured[0].context().get("env"));
    }
}
