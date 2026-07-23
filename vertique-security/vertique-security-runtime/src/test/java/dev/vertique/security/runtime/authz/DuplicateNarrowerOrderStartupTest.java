// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.ActionCapability;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationIntrospector;
import dev.vertique.security.authz.AuthorizationNarrower;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.RequirementDescriptor;
import dev.vertique.security.authz.ResourceRef;
import io.vertx.core.Future;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Startup-validation tests for the {@link AuthorizationNarrower} duplicate-order guard.
 *
 * <p>Mirrors {@code dev.vertique.rest.security.IdentityResolutionMiddleware}'s NFR-ID-003 guard for
 * {@code SecurityIdentityResolver}: two narrowers sharing the same {@code (priority, orderKey)} pair
 * is a configuration error that must fail component construction, not silently pick one.
 */
class DuplicateNarrowerOrderStartupTest {

    @Test
    @DisplayName("constructing NarrowingAuthorizer with two narrowers sharing (priority, orderKey) fails startup")
    void failsClosed() {
        AuthorizationNarrower first = noOpNarrower(5, "dup");
        AuthorizationNarrower second = noOpNarrower(5, "dup");
        Authorizer base = fixedDecisionAuthorizer(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> new NarrowingAuthorizer(base, Set.of(first, second)));

        assertTrue(
                ex.getMessage().contains("priority=5"),
                "message must name the conflicting priority: " + ex.getMessage());
        assertTrue(
                ex.getMessage().contains("orderKey=dup"),
                "message must name the conflicting orderKey: " + ex.getMessage());
        assertTrue(
                ex.getMessage().contains(first.getClass().getName()),
                "message must name the first conflicting narrower: " + ex.getMessage());
        assertTrue(
                ex.getMessage().contains(second.getClass().getName()),
                "message must name the second conflicting narrower: " + ex.getMessage());
    }

    @Test
    @DisplayName("constructing NarrowingIntrospector with two narrowers sharing (priority, orderKey) fails startup")
    void failsClosed_introspector() {
        AuthorizationNarrower first = noOpNarrower(7, "dup-introspector");
        AuthorizationNarrower second = noOpNarrower(7, "dup-introspector");
        AuthorizationIntrospector base = fixedIntrospector(Set.of());

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> new NarrowingIntrospector(base, Set.of(first, second)));

        assertTrue(ex.getMessage().contains("priority=7"));
        assertTrue(ex.getMessage().contains("orderKey=dup-introspector"));
    }

    @Test
    @DisplayName("distinct orderKeys at the same priority do not trip the duplicate guard")
    void distinctOrderKeys_ok() {
        AuthorizationNarrower first = noOpNarrower(5, "a");
        AuthorizationNarrower second = noOpNarrower(5, "b");
        Authorizer base = fixedDecisionAuthorizer(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));

        assertDoesNotThrow(() -> new NarrowingAuthorizer(base, Set.of(first, second)));
    }

    @Test
    @DisplayName("distinct priorities with the same orderKey do not trip the duplicate guard")
    void distinctPriorities_sameOrderKey_ok() {
        AuthorizationNarrower first = noOpNarrower(1, "same-key");
        AuthorizationNarrower second = noOpNarrower(2, "same-key");
        Authorizer base = fixedDecisionAuthorizer(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));

        assertDoesNotThrow(() -> new NarrowingAuthorizer(base, Set.of(first, second)));
    }

    // --- helpers ---

    private static AuthorizationNarrower noOpNarrower(int priority, String orderKey) {
        return new AuthorizationNarrower() {
            @Override
            public int priority() {
                return priority;
            }

            @Override
            public String orderKey() {
                return orderKey;
            }

            @Override
            public Future<AuthorizationDecision> narrow(AuthorizationRequest request, AuthorizationDecision base) {
                return Future.succeededFuture(base);
            }

            @Override
            public Optional<RequirementDescriptor> requirementFor(SecurityContext ctx, ActionRef action) {
                return Optional.empty();
            }
        };
    }

    private static Authorizer fixedDecisionAuthorizer(AuthorizationDecision decision) {
        return new Authorizer() {
            @Override
            public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
                Objects.requireNonNull(request, "request");
                return Future.succeededFuture(decision);
            }

            @Override
            public Future<AuthorizationDecision> authorize(
                    SecurityContext ctx, ActionRef action, ResourceRef resource) {
                Objects.requireNonNull(action, "action");
                Objects.requireNonNull(resource, "resource");
                return Future.succeededFuture(decision);
            }
        };
    }

    private static AuthorizationIntrospector fixedIntrospector(Set<ActionRef> allowed) {
        return new AuthorizationIntrospector() {
            @Override
            public Set<ActionRef> allowedActions(SecurityContext ctx) {
                Objects.requireNonNull(ctx, "ctx");
                return allowed;
            }

            @Override
            public Set<ActionCapability> capabilities(SecurityContext ctx) {
                Objects.requireNonNull(ctx, "ctx");
                return Set.of();
            }
        };
    }
}
