// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DelegationContext}.
 *
 * <p>Verifies: happy-path construction with all fields; null {@code kind} and {@code authorityId}
 * rejected with {@link NullPointerException}; blank/empty required fields rejected with
 * {@link IllegalArgumentException}; {@code reason} as {@link Optional#empty()} is valid; null
 * {@code Optional} reference for {@code reason} rejected; null {@code attributes} treated as empty
 * map; defensive copy of {@code attributes}; {@code equals}/{@code hashCode} consistency.
 */
class DelegationContextTest {

    // --- happy path ---

    @Test
    @DisplayName("constructs DelegationContext with all fields populated")
    void happyPathAllFields() {
        DelegationContext ctx = new DelegationContext(
                "psd2-pis", "consent-abc123", Optional.of("Payment initiation by TPP"), Map.of("scope", "payments"));
        assertEquals("psd2-pis", ctx.kind());
        assertEquals("consent-abc123", ctx.authorityId());
        assertTrue(ctx.reason().isPresent());
        assertEquals("Payment initiation by TPP", ctx.reason().get());
        assertEquals("payments", ctx.attributes().get("scope"));
    }

    @Test
    @DisplayName("constructs DelegationContext with Optional.empty() reason")
    void happyPathEmptyReason() {
        DelegationContext ctx = new DelegationContext("impersonation", "admin-policy-v1", Optional.empty(), Map.of());
        assertEquals("impersonation", ctx.kind());
        assertEquals("admin-policy-v1", ctx.authorityId());
        assertFalse(ctx.reason().isPresent());
    }

    // --- null rejection for required fields ---

    @Test
    @DisplayName("null kind throws NullPointerException with message containing \"kind\"")
    void nullKindThrowsNpe() {
        NullPointerException ex = assertThrows(
                NullPointerException.class, () -> new DelegationContext(null, "auth-id", Optional.empty(), Map.of()));
        assertTrue(ex.getMessage().contains("kind"), "NPE message should mention 'kind' but was: " + ex.getMessage());
    }

    @Test
    @DisplayName("null authorityId throws NullPointerException with message containing \"authorityId\"")
    void nullAuthorityIdThrowsNpe() {
        NullPointerException ex = assertThrows(
                NullPointerException.class, () -> new DelegationContext("psd2-pis", null, Optional.empty(), Map.of()));
        assertTrue(
                ex.getMessage().contains("authorityId"),
                "NPE message should mention 'authorityId' but was: " + ex.getMessage());
    }

    // --- blank rejection ---

    @Test
    @DisplayName("empty kind throws IllegalArgumentException")
    void emptyKindThrowsIae() {
        assertThrows(
                IllegalArgumentException.class, () -> new DelegationContext("", "auth-id", Optional.empty(), Map.of()));
    }

    @Test
    @DisplayName("blank kind throws IllegalArgumentException")
    void blankKindThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DelegationContext("   ", "auth-id", Optional.empty(), Map.of()));
    }

    @Test
    @DisplayName("empty authorityId throws IllegalArgumentException")
    void emptyAuthorityIdThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DelegationContext("psd2-pis", "", Optional.empty(), Map.of()));
    }

    @Test
    @DisplayName("blank authorityId throws IllegalArgumentException")
    void blankAuthorityIdThrowsIae() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DelegationContext("psd2-pis", "  ", Optional.empty(), Map.of()));
    }

    // --- null Optional reference for reason rejected ---

    @Test
    @DisplayName("null Optional reference for reason throws NullPointerException")
    void nullOptionalReasonThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new DelegationContext("psd2-pis", "consent-id", null, Map.of()));
    }

    // --- null attributes treated as empty ---

    @Test
    @DisplayName("null attributes treated as empty map — no NullPointerException thrown")
    void nullAttributesTreatedAsEmpty() {
        DelegationContext ctx = new DelegationContext("kind", "auth-id", Optional.empty(), null);
        assertTrue(ctx.attributes().isEmpty());
    }

    // --- defensive copy ---

    @Test
    @DisplayName("mutating source attributes map after construction does not affect record")
    void defensivelyCopiesAttributes() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v");
        DelegationContext ctx = new DelegationContext("kind", "auth-id", Optional.empty(), mutable);
        mutable.put("injected", "evil");
        assertEquals(1, ctx.attributes().size(), "Attributes must not reflect mutation of source map");
    }

    @Test
    @DisplayName("attributes map returned by accessor is unmodifiable")
    void attributesIsUnmodifiable() {
        DelegationContext ctx = new DelegationContext("kind", "auth-id", Optional.empty(), Map.of("k", "v"));
        assertThrows(UnsupportedOperationException.class, () -> ctx.attributes().put("x", "y"));
    }

    @Test
    @DisplayName("attributes instance is not the original map reference")
    void attributesIsDefensiveCopy() {
        Map<String, Object> original = new HashMap<>(Map.of("k", "v"));
        DelegationContext ctx = new DelegationContext("kind", "auth-id", Optional.empty(), original);
        assertNotSame(original, ctx.attributes());
    }

    // --- equals / hashCode ---

    @Test
    @DisplayName("two DelegationContexts with same fields are equal")
    void equalityHolds() {
        DelegationContext a = new DelegationContext("psd2-pis", "consent-abc", Optional.of("reason"), Map.of("s", "p"));
        DelegationContext b = new DelegationContext("psd2-pis", "consent-abc", Optional.of("reason"), Map.of("s", "p"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("reflexive equality — record equals itself")
    void reflexiveEquality() {
        DelegationContext ctx = new DelegationContext("kind", "auth-id", Optional.empty(), Map.of());
        assertEquals(ctx, ctx);
    }
}
