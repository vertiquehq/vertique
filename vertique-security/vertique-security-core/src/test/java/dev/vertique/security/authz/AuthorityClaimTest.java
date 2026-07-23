// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuthorityClaim}.
 *
 * <p>Verifies: happy-path construction with all required fields; {@code value} must be non-null and
 * non-blank; {@code kind}, {@code issuer}, {@code audience}, and {@code source} must be non-null but
 * MAY be empty string for {@code issuer}/{@code audience}/{@code source}; null {@code attributes}
 * treated as empty map; defensive copy of {@code attributes}.
 */
class AuthorityClaimTest {

    // --- happy path ---

    @Test
    @DisplayName("constructs AuthorityClaim with all required fields")
    void happyPath() {
        AuthorityClaim claim = new AuthorityClaim(
                AuthorityKind.ROLE, "admin", "https://auth.example.com", "api", "jwt-roles", Map.of("tier", "gold"));
        assertEquals(AuthorityKind.ROLE, claim.kind());
        assertEquals("admin", claim.value());
        assertEquals("https://auth.example.com", claim.issuer());
        assertEquals("api", claim.audience());
        assertEquals("jwt-roles", claim.source());
        assertEquals("gold", claim.attributes().get("tier"));
    }

    @Test
    @DisplayName("constructs AuthorityClaim with empty issuer, audience, and source (not applicable)")
    void happyPathEmptyOptionalStrings() {
        AuthorityClaim claim = new AuthorityClaim(AuthorityKind.PERMISSION, "items:read", "", "", "", Map.of());
        assertEquals("items:read", claim.value());
        assertEquals("", claim.issuer());
        assertEquals("", claim.audience());
        assertEquals("", claim.source());
    }

    // --- null rejection for kind ---

    @Test
    @DisplayName("null kind throws NullPointerException with message containing \"kind\"")
    void nullKindThrowsNpe() {
        NullPointerException ex = assertThrows(
                NullPointerException.class,
                () -> new AuthorityClaim(null, "admin", "issuer", "aud", "source", Map.of()));
        assertTrue(ex.getMessage().contains("kind"), "NPE message should mention 'kind' but was: " + ex.getMessage());
    }

    // --- null/blank rejection for value ---

    @Test
    @DisplayName("null value throws NullPointerException")
    void nullValueThrowsNpe() {
        assertThrows(
                NullPointerException.class,
                () -> new AuthorityClaim(AuthorityKind.ROLE, null, "issuer", "aud", "source", Map.of()));
    }

    @Test
    @DisplayName("blank value throws IllegalArgumentException")
    void blankValueThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthorityClaim(AuthorityKind.ROLE, "   ", "issuer", "aud", "source", Map.of()));
    }

    @Test
    @DisplayName("empty value throws IllegalArgumentException")
    void emptyValueThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthorityClaim(AuthorityKind.ROLE, "", "issuer", "aud", "source", Map.of()));
    }

    // --- null rejection for issuer, audience, source ---

    @Test
    @DisplayName("null issuer throws NullPointerException with message containing \"issuer\"")
    void nullIssuerThrowsNpe() {
        NullPointerException ex = assertThrows(
                NullPointerException.class,
                () -> new AuthorityClaim(AuthorityKind.ROLE, "admin", null, "aud", "source", Map.of()));
        assertTrue(
                ex.getMessage().contains("issuer"), "NPE message should mention 'issuer' but was: " + ex.getMessage());
    }

    @Test
    @DisplayName("null audience throws NullPointerException with message containing \"audience\"")
    void nullAudienceThrowsNpe() {
        NullPointerException ex = assertThrows(
                NullPointerException.class,
                () -> new AuthorityClaim(AuthorityKind.ROLE, "admin", "issuer", null, "source", Map.of()));
        assertTrue(
                ex.getMessage().contains("audience"),
                "NPE message should mention 'audience' but was: " + ex.getMessage());
    }

    @Test
    @DisplayName("null source throws NullPointerException with message containing \"source\"")
    void nullSourceThrowsNpe() {
        NullPointerException ex = assertThrows(
                NullPointerException.class,
                () -> new AuthorityClaim(AuthorityKind.ROLE, "admin", "issuer", "aud", null, Map.of()));
        assertTrue(
                ex.getMessage().contains("source"), "NPE message should mention 'source' but was: " + ex.getMessage());
    }

    // --- null attributes treated as empty ---

    @Test
    @DisplayName("null attributes treated as empty map")
    void nullAttributesTreatedAsEmpty() {
        AuthorityClaim claim = new AuthorityClaim(AuthorityKind.SCOPE, "read", "", "", "", null);
        assertTrue(claim.attributes().isEmpty());
    }

    // --- defensive copy ---

    @Test
    @DisplayName("mutating source attributes map after construction does not affect record")
    void defensivelyCopiesAttributes() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v");
        AuthorityClaim claim = new AuthorityClaim(AuthorityKind.SCOPE, "read", "", "", "", mutable);
        mutable.put("injected", "evil");
        assertEquals(1, claim.attributes().size(), "Attributes must not reflect mutation of source map");
    }

    @Test
    @DisplayName("attributes map returned by accessor is unmodifiable")
    void attributesIsUnmodifiable() {
        AuthorityClaim claim = new AuthorityClaim(AuthorityKind.ROLE, "admin", "", "", "", Map.of("k", "v"));
        assertThrows(
                UnsupportedOperationException.class, () -> claim.attributes().put("x", "y"));
    }

    @Test
    @DisplayName("attributes instance is not the original map reference")
    void attributesIsDefensiveCopy() {
        Map<String, Object> original = new HashMap<>(Map.of("k", "v"));
        AuthorityClaim claim = new AuthorityClaim(AuthorityKind.ROLE, "admin", "", "", "", original);
        assertNotSame(original, claim.attributes());
    }
}
