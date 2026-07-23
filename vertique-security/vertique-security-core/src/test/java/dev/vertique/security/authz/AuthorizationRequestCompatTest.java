// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * Unit tests pinning the {@link AuthorizationRequest} expand-contract compatibility surface: the
 * 4-arg convenience constructor must keep every pre-existing call site compiling and must delegate
 * to {@link InvocationOrigin#unspecified()}, while the canonical 5-arg constructor carries an
 * explicitly supplied {@link InvocationOrigin} unchanged.
 */
class AuthorizationRequestCompatTest {

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

    @Test
    @DisplayName("fourArgConvenienceCtorDelegates: 4-arg constructor yields origin() equal to unspecified()")
    void fourArgConvenienceCtorDelegates() {
        AuthorizationRequest request = new AuthorizationRequest(STUB_CTX, "READ", STUB_RESOURCE, Map.of());

        assertEquals(InvocationOrigin.unspecified(), request.origin());
    }

    @Test
    @DisplayName("fourArgConvenienceCtorDelegates: the 5-arg constructor carries the given origin")
    void fiveArgCtorCarriesGivenOrigin() {
        InvocationOrigin seeded = InvocationOrigin.of("rest");

        AuthorizationRequest request = new AuthorizationRequest(STUB_CTX, "READ", STUB_RESOURCE, seeded, Map.of());

        assertEquals(seeded, request.origin());
    }
}
