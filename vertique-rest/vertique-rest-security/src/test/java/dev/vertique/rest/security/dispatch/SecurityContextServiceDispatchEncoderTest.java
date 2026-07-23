// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.core.context.ServiceDispatchEncodeContext;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SecurityContextServiceDispatchEncoder}.
 *
 * <p>Verifies that the encoder reports the correct type and performs an identity pass-through
 * (the value returned by {@code encode} is the same object that was passed in).
 */
class SecurityContextServiceDispatchEncoderTest {

    private final SecurityContextServiceDispatchEncoder encoder = new SecurityContextServiceDispatchEncoder();

    private static final ServiceDispatchEncodeContext ENCODE_CTX = new ServiceDispatchEncodeContext("service-dispatch");

    // --- type() ---

    @Test
    @DisplayName("type() returns SecurityContext.class")
    void typeReturnsSecurityContextClass() {
        assertEquals(SecurityContext.class, encoder.type());
    }

    // --- key() ---

    @Test
    @DisplayName("key() defaults to SecurityContext FQCN")
    void keyDefaultsToFqcn() {
        assertEquals(SecurityContext.class.getName(), encoder.key());
    }

    // --- encode() identity pass-through ---

    @Test
    @DisplayName("encode returns the same SecurityContext instance (identity pass-through)")
    void encodeReturnsIdentity() {
        SecurityContext sc = stubSecurityContext("user-1");
        Object result = encoder.encode(sc, ENCODE_CTX);
        assertSame(sc, result, "encode must return the same object reference");
    }

    @Test
    @DisplayName("encode returns the same SecurityContext subtype instance unchanged")
    void encodeReturnsSameSubtypeInstance() {
        SecurityContext subtype = new SubtypeSecurityContext("client-42");
        Object result = encoder.encode(subtype, ENCODE_CTX);
        assertSame(subtype, result, "encode must return the same subtype reference unchanged");
    }

    // --- Helpers ---

    /**
     * Creates a minimal stub {@link SecurityContext} for a USER actor with the given id.
     *
     * @param userId the user identifier
     * @return a minimal security context implementation
     */
    private static SecurityContext stubSecurityContext(String userId) {
        SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, userId, Map.of()));
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        return new MinimalSecurityContext(identity, auth);
    }

    /**
     * Minimal {@link SecurityContext} for encoder/decoder tests. Delegates to the typed identity
     * model without depending on the package-private {@code AuthenticatedSecurityContext}.
     *
     * @param identity       the security identity
     * @param authentication the authentication state
     */
    record MinimalSecurityContext(SecurityIdentity identity, AuthenticationState authentication)
            implements SecurityContext {

        @Override
        public AuthorizationClaims authorization() {
            return AuthorizationClaims.empty();
        }

        @Override
        public Optional<RequestOrigin> origin() {
            return Optional.empty();
        }
    }

    /**
     * A concrete subtype of {@link SecurityContext} used to verify that subtype instances
     * are returned by reference without any wrapping or copying.
     *
     * @param clientId the client identifier
     */
    record SubtypeSecurityContext(String clientId) implements SecurityContext {

        @Override
        public SecurityIdentity identity() {
            return SecurityIdentity.service(new PrincipalRef(PrincipalType.SERVICE, clientId, Map.of()));
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
    }
}
