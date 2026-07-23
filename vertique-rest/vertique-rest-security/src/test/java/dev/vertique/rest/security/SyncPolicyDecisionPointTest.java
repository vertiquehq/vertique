// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationPolicy;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.ResourceRef;
import io.vertx.core.Future;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SyncPolicyDecisionPoint}.
 *
 * <p>Since ADR-0114 the adapter is a <strong>pure evaluator</strong>: it returns the wrapped
 * policy's decision and emits nothing. These tests verify:
 * <ul>
 *   <li>App policy permits → future succeeds with permit, no emitter dependency</li>
 *   <li>App policy denies → future succeeds with deny</li>
 *   <li>App policy throws → future is failed with the exception</li>
 *   <li>Null constructor argument → {@link NullPointerException}</li>
 *   <li>Null {@code request} argument → {@link NullPointerException}</li>
 * </ul>
 */
class SyncPolicyDecisionPointTest {

    // --- Fixtures ---

    private static SecurityContext stubSecCtx() {
        return new dev.vertique.security.SecurityContext() {
            @Override
            public dev.vertique.security.SecurityIdentity identity() {
                return dev.vertique.security.SecurityIdentity.anonymous();
            }

            @Override
            public dev.vertique.security.AuthenticationState authentication() {
                return new dev.vertique.security.AuthenticationState(
                        dev.vertique.security.DefaultAuthMethod.none(),
                        java.util.List.of(),
                        Optional.empty(),
                        Optional.empty(),
                        Map.of());
            }

            @Override
            public AuthorizationClaims authorization() {
                return AuthorizationClaims.empty();
            }

            @Override
            public Optional<dev.vertique.security.origin.RequestOrigin> origin() {
                return Optional.empty();
            }
        };
    }

    private static AuthorizationRequest stubRequest(SecurityContext ctx) {
        return new AuthorizationRequest(ctx, "GET", new ResourceRef("route", "/test", Map.of()), Map.of());
    }

    // --- Permit path ---

    @Nested
    @DisplayName("Policy permits → future succeeds with permit")
    class PermitPath {

        @Test
        @DisplayName("permit: policy returns permit → future succeeds with permitted decision")
        void policyPermitsSucceeds() {
            AuthorizationPolicy policy = req -> AuthorizationDecision.permit("PERMITTED");
            SyncPolicyDecisionPoint dp = new SyncPolicyDecisionPoint(policy);

            SecurityContext ctx = stubSecCtx();
            AuthorizationRequest request = stubRequest(ctx);
            Future<AuthorizationDecision> result = dp.decide(request);

            assertTrue(result.succeeded(), "future must succeed");
            assertTrue(result.result().permitted(), "decision must be permitted");
        }
    }

    // --- Deny path ---

    @Nested
    @DisplayName("Policy denies → future succeeds with deny")
    class DenyPath {

        @Test
        @DisplayName("deny: policy returns deny → future succeeds with denied decision")
        void policyDeniesSucceeds() {
            AuthorizationPolicy policy = req -> AuthorizationDecision.deny("CUSTOM_DENY");
            SyncPolicyDecisionPoint dp = new SyncPolicyDecisionPoint(policy);

            SecurityContext ctx = stubSecCtx();
            AuthorizationRequest request = stubRequest(ctx);
            Future<AuthorizationDecision> result = dp.decide(request);

            assertTrue(result.succeeded(), "future must succeed even for deny");
            assertFalse(result.result().permitted(), "decision must be denied");
            assertEquals("CUSTOM_DENY", result.result().reasonCode());
        }
    }

    // --- Policy throws ---

    @Nested
    @DisplayName("Policy throws → future fails")
    class PolicyThrows {

        @Test
        @DisplayName("policy throws RuntimeException → future fails with that exception")
        void policyThrowsFailsFuture() {
            RuntimeException boom = new RuntimeException("policy crashed");
            AuthorizationPolicy policy = req -> {
                throw boom;
            };
            SyncPolicyDecisionPoint dp = new SyncPolicyDecisionPoint(policy);

            SecurityContext ctx = stubSecCtx();
            AuthorizationRequest request = stubRequest(ctx);
            Future<AuthorizationDecision> result = dp.decide(request);

            assertTrue(result.failed(), "future must fail when policy throws");
            assertSame(boom, result.cause(), "future must carry the original exception");
        }
    }

    // --- Constructor null rejection ---

    @Nested
    @DisplayName("Constructor null argument rejection")
    class ConstructorNullArguments {

        @Test
        @DisplayName("null policy throws NullPointerException")
        void nullPolicyThrows() {
            assertThrows(NullPointerException.class, () -> new SyncPolicyDecisionPoint(null));
        }
    }

    // --- Null request rejection ---

    @Nested
    @DisplayName("Null request rejection")
    class NullRequestRejection {

        @Test
        @DisplayName("null request throws NullPointerException")
        void nullRequestThrows() {
            AuthorizationPolicy policy = req -> AuthorizationDecision.permit("PERMITTED");
            SyncPolicyDecisionPoint dp = new SyncPolicyDecisionPoint(policy);

            assertThrows(NullPointerException.class, () -> dp.decide(null));
        }
    }
}
