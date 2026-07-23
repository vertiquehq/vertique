// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuthenticationAssurance}.
 *
 * <p>Verifies: typical OAuth assurance construction; null rejections for required Optional fields;
 * defensive copy of {@code amr} set; null {@code amr} treated as empty set.
 */
class AuthenticationAssuranceTest {

    private static final Instant AUTH_TIME = Instant.parse("2026-01-01T12:00:00Z");

    // --- typical OAuth assurance ---

    @Test
    @DisplayName("constructs typical OAuth assurance with acr, amr, authTime, and providerLevel")
    void typicalOauthAssurance() {
        AuthenticationAssurance assurance = new AuthenticationAssurance(
                Optional.of("urn:mace:incommon:iap:bronze"),
                Set.of("pwd", "mfa"),
                Optional.of(AUTH_TIME),
                Optional.of(2));

        assertEquals(Optional.of("urn:mace:incommon:iap:bronze"), assurance.acr());
        assertEquals(Set.of("pwd", "mfa"), assurance.amr());
        assertEquals(Optional.of(AUTH_TIME), assurance.authTime());
        assertEquals(Optional.of(2), assurance.providerLevel());
    }

    @Test
    @DisplayName("constructs with all optionals empty")
    void allOptionalsEmpty() {
        AuthenticationAssurance assurance =
                new AuthenticationAssurance(Optional.empty(), Set.of(), Optional.empty(), Optional.empty());

        assertFalse(assurance.acr().isPresent());
        assertTrue(assurance.amr().isEmpty());
        assertFalse(assurance.authTime().isPresent());
        assertFalse(assurance.providerLevel().isPresent());
    }

    // --- null checks ---

    @Nested
    @DisplayName("null rejection")
    class NullRejection {

        @Test
        @DisplayName("rejects null acr Optional")
        void rejectsNullAcr() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AuthenticationAssurance(null, Set.of(), Optional.empty(), Optional.empty()));
        }

        @Test
        @DisplayName("rejects null authTime Optional")
        void rejectsNullAuthTime() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AuthenticationAssurance(Optional.empty(), Set.of(), null, Optional.empty()));
        }

        @Test
        @DisplayName("rejects null providerLevel Optional")
        void rejectsNullProviderLevel() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AuthenticationAssurance(Optional.empty(), Set.of(), Optional.empty(), null));
        }
    }

    // --- defensive copy ---

    @Test
    @DisplayName("amr is a defensive copy — mutations to original set do not affect record")
    void amrDefensiveCopy() {
        Set<String> mutable = new HashSet<>();
        mutable.add("pwd");
        AuthenticationAssurance assurance =
                new AuthenticationAssurance(Optional.empty(), mutable, Optional.empty(), Optional.empty());

        mutable.add("mfa");
        assertEquals(1, assurance.amr().size());
        assertTrue(assurance.amr().contains("pwd"));
    }

    @Test
    @DisplayName("null amr treated as empty set")
    void nullAmrTreatedAsEmpty() {
        AuthenticationAssurance assurance =
                new AuthenticationAssurance(Optional.empty(), null, Optional.empty(), Optional.empty());

        assertTrue(assurance.amr().isEmpty());
    }
}
