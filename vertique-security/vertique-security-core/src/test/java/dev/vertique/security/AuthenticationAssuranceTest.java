// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuthenticationAssurance}.
 *
 * <p>Verifies: typical OAuth assurance construction; null rejections for required Optional fields;
 * defensive copy of {@code amr} set; null {@code amr} treated as empty set; and — the contract this
 * class pins for issue #181 — that {@code amr} preserves its source encounter order, stays
 * immutable, and rejects null elements.
 */
class AuthenticationAssuranceTest {

    private static final Instant AUTH_TIME = Instant.parse("2026-01-01T12:00:00Z");

    // --- typical OAuth assurance ---

    @Test
    @DisplayName("constructs typical OAuth assurance with acr, amr, authTime, and providerLevel")
    void typicalOauthAssurance() {
        AuthenticationAssurance assurance = new AuthenticationAssurance(
                Optional.of("urn:mace:incommon:iap:bronze"),
                new LinkedHashSet<>(List.of("pwd", "mfa")),
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
        AuthenticationAssurance assurance = new AuthenticationAssurance(
                Optional.empty(), new LinkedHashSet<>(), Optional.empty(), Optional.empty());

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
                    () -> new AuthenticationAssurance(null, new LinkedHashSet<>(), Optional.empty(), Optional.empty()));
        }

        @Test
        @DisplayName("rejects null authTime Optional")
        void rejectsNullAuthTime() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AuthenticationAssurance(Optional.empty(), new LinkedHashSet<>(), null, Optional.empty()));
        }

        @Test
        @DisplayName("rejects null providerLevel Optional")
        void rejectsNullProviderLevel() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AuthenticationAssurance(Optional.empty(), new LinkedHashSet<>(), Optional.empty(), null));
        }
    }

    // --- defensive copy ---

    @Test
    @DisplayName("amr is a defensive copy — mutations to original set do not affect record")
    void amrDefensiveCopy() {
        SequencedSet<String> mutable = new LinkedHashSet<>();
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

    // --- encounter order (issue #181) ---

    @Test
    @DisplayName("amr preserves encounter order for both insertion orders")
    void preservesEncounterOrderForBothInsertionOrders() {
        // The stored JSON array order of amr must round-trip through the typed model, otherwise a
        // snapshot signed on one node fails its own HMAC when re-serialized on another (issue #181).
        // A hash-ordered copy collapses BOTH sources below to one identical iteration order, so at
        // most one of these two assertions can hold unless the copy is encounter-order preserving.
        SequencedSet<String> forward = new LinkedHashSet<>(List.of("pwd", "otp"));
        SequencedSet<String> reverse = new LinkedHashSet<>(List.of("otp", "pwd"));

        AuthenticationAssurance forwardAssurance =
                new AuthenticationAssurance(Optional.empty(), forward, Optional.empty(), Optional.empty());
        AuthenticationAssurance reverseAssurance =
                new AuthenticationAssurance(Optional.empty(), reverse, Optional.empty(), Optional.empty());

        assertEquals(
                List.of("pwd", "otp"),
                List.copyOf(forwardAssurance.amr()),
                "amr must iterate in its own source encounter order, not a hash-derived order");
        assertEquals(
                List.of("otp", "pwd"),
                List.copyOf(reverseAssurance.amr()),
                "amr must iterate in its own source encounter order, not a hash-derived order");
    }

    @Test
    @DisplayName("amr is immutable — mutation attempts on the record's set are rejected")
    void amrIsImmutable() {
        AuthenticationAssurance assurance = new AuthenticationAssurance(
                Optional.empty(), new LinkedHashSet<>(List.of("pwd", "otp")), Optional.empty(), Optional.empty());

        assertThrows(
                UnsupportedOperationException.class,
                () -> assurance.amr().add("x"),
                "the order-preserving copy must remain unmodifiable");
    }

    @Test
    @DisplayName("rejects a null element inside amr")
    void rejectsNullAmrElement() {
        SequencedSet<String> withNullElement = new LinkedHashSet<>();
        withNullElement.add("pwd");
        withNullElement.add(null);

        assertThrows(
                NullPointerException.class,
                () -> new AuthenticationAssurance(
                        Optional.empty(), withNullElement, Optional.empty(), Optional.empty()),
                "the order-preserving copy must keep Set.copyOf's null-element rejection");
    }
}
