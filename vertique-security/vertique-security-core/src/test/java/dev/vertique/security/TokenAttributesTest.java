// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TokenAttributes}.
 *
 * <p>Verifies: construction with all three Optionals; null rejections for Optional references;
 * defensive copy of inner maps so mutations to originals after construction do not affect the
 * record.
 */
class TokenAttributesTest {

    // --- full construction ---

    @Test
    @DisplayName("constructs with all three maps present")
    void fullConstruction() {
        Map<String, Object> header = Map.of("alg", "RS256", "kid", "key-001");
        Map<String, Object> claims = Map.of("sub", "user-42", "exp", 1700000000L);
        Map<String, Object> introspection = Map.of("active", true, "client_id", "my-app");

        TokenAttributes attrs =
                new TokenAttributes(Optional.of(header), Optional.of(claims), Optional.of(introspection));

        assertEquals(Optional.of(header), attrs.jwtHeader());
        assertEquals(Optional.of(claims), attrs.jwtClaims());
        assertEquals(Optional.of(introspection), attrs.introspectionResponse());
    }

    @Test
    @DisplayName("constructs with all optionals empty")
    void allOptionalsEmpty() {
        TokenAttributes attrs = new TokenAttributes(Optional.empty(), Optional.empty(), Optional.empty());

        assertFalse(attrs.jwtHeader().isPresent());
        assertFalse(attrs.jwtClaims().isPresent());
        assertFalse(attrs.introspectionResponse().isPresent());
    }

    // --- null checks ---

    @Nested
    @DisplayName("null rejection")
    class NullRejection {

        @Test
        @DisplayName("rejects null jwtHeader Optional")
        void rejectsNullJwtHeader() {
            assertThrows(
                    NullPointerException.class, () -> new TokenAttributes(null, Optional.empty(), Optional.empty()));
        }

        @Test
        @DisplayName("rejects null jwtClaims Optional")
        void rejectsNullJwtClaims() {
            assertThrows(
                    NullPointerException.class, () -> new TokenAttributes(Optional.empty(), null, Optional.empty()));
        }

        @Test
        @DisplayName("rejects null introspectionResponse Optional")
        void rejectsNullIntrospectionResponse() {
            assertThrows(
                    NullPointerException.class, () -> new TokenAttributes(Optional.empty(), Optional.empty(), null));
        }
    }

    // --- defensive copy of inner maps ---

    @Test
    @DisplayName("jwtHeader inner map is defensively copied — mutations to original do not affect record")
    void jwtHeaderDefensiveCopy() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("alg", "RS256");
        TokenAttributes attrs = new TokenAttributes(Optional.of(mutable), Optional.empty(), Optional.empty());

        mutable.put("alg", "HS256");
        assertTrue(attrs.jwtHeader().isPresent());
        assertEquals("RS256", attrs.jwtHeader().get().get("alg"));
    }

    @Test
    @DisplayName("jwtClaims inner map is defensively copied")
    void jwtClaimsDefensiveCopy() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("sub", "user-original");
        TokenAttributes attrs = new TokenAttributes(Optional.empty(), Optional.of(mutable), Optional.empty());

        mutable.put("sub", "user-mutated");
        assertEquals("user-original", attrs.jwtClaims().get().get("sub"));
    }

    @Test
    @DisplayName("introspectionResponse inner map is defensively copied")
    void introspectionResponseDefensiveCopy() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("active", true);
        TokenAttributes attrs = new TokenAttributes(Optional.empty(), Optional.empty(), Optional.of(mutable));

        mutable.put("active", false);
        assertEquals(true, attrs.introspectionResponse().get().get("active"));
    }
}
