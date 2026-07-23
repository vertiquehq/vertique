// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.security.AuthMethod;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link JaxRsSecurityContext}.
 *
 * <p>Verifies that the JAX-RS {@link jakarta.ws.rs.core.SecurityContext} bridge correctly
 * delegates to the framework's {@link SecurityContext}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Principal extraction from framework context (via {@code identity().actor().id()})</li>
 *   <li>Role membership checking via {@code authorization().claims()} filtered by
 *       {@link AuthorityKind#ROLE}</li>
 *   <li>Secure flag handling</li>
 *   <li>Authentication scheme mapping from AuthMethod</li>
 *   <li>Null-safety when framework context is null</li>
 * </ul>
 */
class JaxRsSecurityContextTest {

    @Test
    @DisplayName("Should return principal from framework context")
    void shouldReturnPrincipal() {
        SecurityContext frameworkCtx =
                createContext("testuser", DefaultAuthMethod.jwt(), Set.of("admin"), Set.of("read"));

        JaxRsSecurityContext jaxRsCtx = new JaxRsSecurityContext(frameworkCtx, false);

        assertNotNull(jaxRsCtx.getUserPrincipal());
        assertEquals("testuser", jaxRsCtx.getUserPrincipal().getName());
    }

    @Test
    @DisplayName("Should return null principal when framework context is null")
    void shouldReturnNullPrincipalWhenContextNull() {
        JaxRsSecurityContext jaxRsCtx = new JaxRsSecurityContext(null, false);
        assertNull(jaxRsCtx.getUserPrincipal());
    }

    @Test
    @DisplayName("Should check roles (not scopes) for isUserInRole")
    void shouldCheckRolesForRole() {
        SecurityContext frameworkCtx =
                createContext("user1", DefaultAuthMethod.jwt(), Set.of("user", "admin"), Set.of("read", "write"));

        JaxRsSecurityContext jaxRsCtx = new JaxRsSecurityContext(frameworkCtx, false);

        assertTrue(jaxRsCtx.isUserInRole("user"), "should find role 'user'");
        assertTrue(jaxRsCtx.isUserInRole("admin"), "should find role 'admin'");
        assertFalse(jaxRsCtx.isUserInRole("read"), "should NOT find scope 'read' via isUserInRole");
        assertFalse(jaxRsCtx.isUserInRole("write"), "should NOT find scope 'write' via isUserInRole");
    }

    @Test
    @DisplayName("Should return false for role check when context is null")
    void shouldReturnFalseForRoleCheckWhenNull() {
        JaxRsSecurityContext jaxRsCtx = new JaxRsSecurityContext(null, false);
        assertFalse(jaxRsCtx.isUserInRole("admin"));
    }

    @Test
    @DisplayName("Anonymous identity: null principal, no role, null auth scheme")
    void anonymousIdentityHasNoPrincipalOrRole() {
        SecurityContext anonCtx = anonymousContext();
        JaxRsSecurityContext jaxRsCtx = new JaxRsSecurityContext(anonCtx, false);

        assertNull(jaxRsCtx.getUserPrincipal(), "anonymous must have null principal per JAX-RS contract");
        assertFalse(jaxRsCtx.isUserInRole("admin"), "anonymous must not be in any role");
        assertNull(jaxRsCtx.getAuthenticationScheme(), "anonymous (NONE method) maps to null scheme");
    }

    @Test
    @DisplayName("Anonymous identity is not in any role even if role claims are present")
    void anonymousIsNeverInRoleEvenWithClaims() {
        // Defensive: even if an anonymous context somehow carries ROLE claims, the bridge must
        // enforce the JAX-RS contract that an anonymous principal holds no roles.
        AuthorizationClaims withRole = new AuthorizationClaims(
                Set.of(new AuthorityClaim(AuthorityKind.ROLE, "admin", "", "", "", Map.of())), Map.of());
        SecurityContext anonCtx = anonymousContextWith(withRole);
        JaxRsSecurityContext jaxRsCtx = new JaxRsSecurityContext(anonCtx, false);

        assertFalse(jaxRsCtx.isUserInRole("admin"), "anonymous must not be in 'admin' even with a ROLE claim");
    }

    @Test
    @DisplayName("Should return secure flag correctly")
    void shouldReturnSecureFlag() {
        SecurityContext frameworkCtx = createContext("user1", DefaultAuthMethod.jwt(), Set.of(), Set.of());

        assertFalse(new JaxRsSecurityContext(frameworkCtx, false).isSecure());
        assertTrue(new JaxRsSecurityContext(frameworkCtx, true).isSecure());
    }

    @Test
    @DisplayName("Should map auth method to authentication scheme")
    void shouldMapAuthMethodToScheme() {
        assertEquals("BEARER", createJaxRsCtx(DefaultAuthMethod.jwt()).getAuthenticationScheme());
        assertEquals("BASIC", createJaxRsCtx(DefaultAuthMethod.basic()).getAuthenticationScheme());
        assertEquals("API_KEY", createJaxRsCtx(DefaultAuthMethod.apiKey()).getAuthenticationScheme());
        assertEquals(
                "CUSTOM", createJaxRsCtx(DefaultAuthMethod.custom("custom")).getAuthenticationScheme());
        assertNull(createJaxRsCtx(DefaultAuthMethod.none()).getAuthenticationScheme());
    }

    @Test
    @DisplayName("Should map MTLS to the JAX-RS CLIENT_CERT_AUTH scheme")
    void shouldMapMtlsToScheme() {
        assertEquals(
                jakarta.ws.rs.core.SecurityContext.CLIENT_CERT_AUTH,
                createJaxRsCtx(DefaultAuthMethod.mtls()).getAuthenticationScheme());
    }

    @Test
    @DisplayName("Should map UNKNOWN to UNKNOWN scheme")
    void shouldMapUnknownToScheme() {
        assertEquals("UNKNOWN", createJaxRsCtx(DefaultAuthMethod.unknown()).getAuthenticationScheme());
    }

    @Test
    @DisplayName("Should return null auth scheme when framework context is null")
    void shouldReturnNullSchemeWhenContextNull() {
        JaxRsSecurityContext jaxRsCtx = new JaxRsSecurityContext(null, false);
        assertNull(jaxRsCtx.getAuthenticationScheme());
    }

    // --- Helpers ---

    /**
     * Creates a {@link JaxRsSecurityContext} with the specified auth method for testing scheme mapping.
     *
     * @param authMethod the authentication method to use
     * @return a JaxRsSecurityContext wrapping a test SecurityContext
     */
    private JaxRsSecurityContext createJaxRsCtx(AuthMethod authMethod) {
        SecurityContext frameworkCtx = createContext("user1", authMethod, Set.of(), Set.of());
        return new JaxRsSecurityContext(frameworkCtx, false);
    }

    /**
     * Creates a test {@link SecurityContext} using the new typed identity model.
     *
     * <p>The actor is a USER principal with the given {@code userId}. Roles are mapped to
     * {@link AuthorityKind#ROLE} claims and scopes to {@link AuthorityKind#SCOPE} claims.
     *
     * @param userId     the user ID (actor id)
     * @param authMethod the authentication method
     * @param roles      the role values to include as ROLE claims
     * @param scopes     the scope values to include as SCOPE claims
     * @return a minimal {@link SecurityContext} backed by the new typed model
     */
    /** Creates an anonymous {@link SecurityContext} (NONE auth method, empty claims). */
    private SecurityContext anonymousContext() {
        return anonymousContextWith(AuthorizationClaims.empty());
    }

    /** Creates an anonymous {@link SecurityContext} carrying the given authorization claims. */
    private SecurityContext anonymousContextWith(AuthorizationClaims authzClaims) {
        SecurityIdentity identity = SecurityIdentity.anonymous();
        AuthenticationState authState = new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        return new SecurityContext() {
            @Override
            public SecurityIdentity identity() {
                return identity;
            }

            @Override
            public AuthenticationState authentication() {
                return authState;
            }

            @Override
            public AuthorizationClaims authorization() {
                return authzClaims;
            }

            @Override
            public Optional<RequestOrigin> origin() {
                return Optional.empty();
            }
        };
    }

    private SecurityContext createContext(String userId, AuthMethod authMethod, Set<String> roles, Set<String> scopes) {
        SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, userId, Map.of()));
        AuthenticationState authState =
                new AuthenticationState(authMethod, List.of(), Optional.empty(), Optional.empty(), Map.of());

        Set<AuthorityClaim> claims = roles.stream()
                .map(r -> new AuthorityClaim(AuthorityKind.ROLE, r, "", "", "", Map.of()))
                .collect(Collectors.toSet());
        scopes.stream()
                .map(s -> new AuthorityClaim(AuthorityKind.SCOPE, s, "", "", "", Map.of()))
                .forEach(claims::add);
        AuthorizationClaims authzClaims = new AuthorizationClaims(claims, Map.of());

        return new SecurityContext() {
            @Override
            public SecurityIdentity identity() {
                return identity;
            }

            @Override
            public AuthenticationState authentication() {
                return authState;
            }

            @Override
            public AuthorizationClaims authorization() {
                return authzClaims;
            }

            @Override
            public Optional<RequestOrigin> origin() {
                return Optional.empty();
            }
        };
    }
}
