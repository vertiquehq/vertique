// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.origin.RequestOrigin;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuthorizationRequest}.
 *
 * <p>Verifies: happy-path construction; null {@code securityContext} rejected; null/blank
 * {@code action} rejected; null {@code resource} rejected; null {@code context} treated as empty
 * map; defensive copy of {@code context}.
 */
class AuthorizationRequestTest {

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

    // --- happy path ---

    @Test
    @DisplayName("constructs AuthorizationRequest with all required fields")
    void happyPath() {
        AuthorizationRequest req = new AuthorizationRequest(STUB_CTX, "READ", STUB_RESOURCE, Map.of("tenant", "acme"));
        assertEquals(STUB_CTX, req.securityContext());
        assertEquals("READ", req.action());
        assertEquals(STUB_RESOURCE, req.resource());
        assertEquals("acme", req.context().get("tenant"));
    }

    @Test
    @DisplayName("constructs AuthorizationRequest with null context (treated as empty)")
    void happyPathNullContext() {
        AuthorizationRequest req = new AuthorizationRequest(STUB_CTX, "READ", STUB_RESOURCE, null);
        assertTrue(req.context().isEmpty());
    }

    // --- null rejection for securityContext ---

    @Test
    @DisplayName("null securityContext throws NullPointerException with message containing \"securityContext\"")
    void nullSecurityContextThrowsNpe() {
        NullPointerException ex = assertThrows(
                NullPointerException.class, () -> new AuthorizationRequest(null, "READ", STUB_RESOURCE, Map.of()));
        assertTrue(
                ex.getMessage().contains("securityContext"),
                "NPE message should mention 'securityContext' but was: " + ex.getMessage());
    }

    // --- null/blank rejection for action ---

    @Test
    @DisplayName("null action throws NullPointerException")
    void nullActionThrowsNpe() {
        assertThrows(
                NullPointerException.class, () -> new AuthorizationRequest(STUB_CTX, null, STUB_RESOURCE, Map.of()));
    }

    @Test
    @DisplayName("blank action throws IllegalArgumentException")
    void blankActionThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthorizationRequest(STUB_CTX, "   ", STUB_RESOURCE, Map.of()));
    }

    @Test
    @DisplayName("empty action throws IllegalArgumentException")
    void emptyActionThrowsIae() {
        assertThrows(
                IllegalArgumentException.class, () -> new AuthorizationRequest(STUB_CTX, "", STUB_RESOURCE, Map.of()));
    }

    // --- null rejection for resource ---

    @Test
    @DisplayName("null resource throws NullPointerException with message containing \"resource\"")
    void nullResourceThrowsNpe() {
        NullPointerException ex = assertThrows(
                NullPointerException.class, () -> new AuthorizationRequest(STUB_CTX, "READ", null, Map.of()));
        assertTrue(
                ex.getMessage().contains("resource"),
                "NPE message should mention 'resource' but was: " + ex.getMessage());
    }

    // --- defensive copy ---

    @Test
    @DisplayName("mutating source context map after construction does not affect record")
    void defensivelyCopiesContext() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v");
        AuthorizationRequest req = new AuthorizationRequest(STUB_CTX, "READ", STUB_RESOURCE, mutable);
        mutable.put("injected", "evil");
        assertEquals(1, req.context().size(), "Context must not reflect mutation of source map");
    }

    @Test
    @DisplayName("context map returned by accessor is unmodifiable")
    void contextIsUnmodifiable() {
        AuthorizationRequest req = new AuthorizationRequest(STUB_CTX, "READ", STUB_RESOURCE, Map.of("k", "v"));
        assertThrows(UnsupportedOperationException.class, () -> req.context().put("x", "y"));
    }

    @Test
    @DisplayName("context instance is not the original map reference")
    void contextIsDefensiveCopy() {
        Map<String, Object> original = new HashMap<>(Map.of("k", "v"));
        AuthorizationRequest req = new AuthorizationRequest(STUB_CTX, "READ", STUB_RESOURCE, original);
        assertNotSame(original, req.context());
    }
}
