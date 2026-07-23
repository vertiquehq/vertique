// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.verification.JwksVerificationSource;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuthenticationEvidence}.
 *
 * <p>Verifies: construction with populated fields; null rejections for each required field;
 * defensive copy of {@code safeAttributes}; null {@code safeAttributes} treated as empty.
 */
class AuthenticationEvidenceTest {

    private static final AuthMethod JWT_METHOD = DefaultAuthMethod.jwt();
    private static final JwksVerificationSource JWKS_SOURCE = new JwksVerificationSource(
            Optional.of("https://idp.example.com"),
            Optional.of("https://idp.example.com/.well-known/jwks.json"),
            Optional.of("key-001"),
            Optional.of("RS256"));
    private static final Instant VERIFIED_AT = Instant.parse("2026-01-01T12:00:00Z");
    private static final Instant NOT_AFTER = Instant.parse("2026-01-01T13:00:00Z");

    // --- full construction ---

    @Test
    @DisplayName("constructs with all fields populated")
    void fullConstruction() {
        AuthenticationEvidence evidence = new AuthenticationEvidence(
                JWT_METHOD,
                Optional.of("cred-abc"),
                VERIFIED_AT,
                Optional.of(NOT_AFTER),
                JWKS_SOURCE,
                Map.of("jti", "token-xyz"));

        assertEquals(JWT_METHOD, evidence.method());
        assertEquals(Optional.of("cred-abc"), evidence.credentialId());
        assertEquals(VERIFIED_AT, evidence.verifiedAt());
        assertEquals(Optional.of(NOT_AFTER), evidence.notAfter());
        assertEquals(JWKS_SOURCE, evidence.verificationSource());
        assertEquals(Map.of("jti", "token-xyz"), evidence.safeAttributes());
    }

    @Test
    @DisplayName("constructs with empty optionals and empty attributes")
    void minimalConstruction() {
        AuthenticationEvidence evidence = new AuthenticationEvidence(
                JWT_METHOD, Optional.empty(), VERIFIED_AT, Optional.empty(), JWKS_SOURCE, Map.of());

        assertFalse(evidence.credentialId().isPresent());
        assertFalse(evidence.notAfter().isPresent());
        assertTrue(evidence.safeAttributes().isEmpty());
    }

    // --- null checks ---

    @Nested
    @DisplayName("null rejection")
    class NullRejection {

        @Test
        @DisplayName("rejects null method")
        void rejectsNullMethod() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AuthenticationEvidence(
                            null, Optional.empty(), VERIFIED_AT, Optional.empty(), JWKS_SOURCE, Map.of()));
        }

        @Test
        @DisplayName("rejects null credentialId Optional")
        void rejectsNullCredentialId() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AuthenticationEvidence(
                            JWT_METHOD, null, VERIFIED_AT, Optional.empty(), JWKS_SOURCE, Map.of()));
        }

        @Test
        @DisplayName("rejects null verifiedAt")
        void rejectsNullVerifiedAt() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AuthenticationEvidence(
                            JWT_METHOD, Optional.empty(), null, Optional.empty(), JWKS_SOURCE, Map.of()));
        }

        @Test
        @DisplayName("rejects null notAfter Optional")
        void rejectsNullNotAfter() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AuthenticationEvidence(
                            JWT_METHOD, Optional.empty(), VERIFIED_AT, null, JWKS_SOURCE, Map.of()));
        }

        @Test
        @DisplayName("rejects null verificationSource")
        void rejectsNullVerificationSource() {
            assertThrows(
                    NullPointerException.class,
                    () -> new AuthenticationEvidence(
                            JWT_METHOD, Optional.empty(), VERIFIED_AT, Optional.empty(), null, Map.of()));
        }
    }

    // --- defensive copy ---

    @Test
    @DisplayName("safeAttributes is a defensive copy — mutations to original map do not affect record")
    void safeAttributesDefensiveCopy() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v1");
        AuthenticationEvidence evidence = new AuthenticationEvidence(
                JWT_METHOD, Optional.empty(), VERIFIED_AT, Optional.empty(), JWKS_SOURCE, mutable);

        mutable.put("k", "v2");
        assertEquals("v1", evidence.safeAttributes().get("k"));
    }

    @Test
    @DisplayName("null safeAttributes treated as empty map")
    void nullSafeAttributesTreatedAsEmpty() {
        AuthenticationEvidence evidence = new AuthenticationEvidence(
                JWT_METHOD, Optional.empty(), VERIFIED_AT, Optional.empty(), JWKS_SOURCE, null);

        assertTrue(evidence.safeAttributes().isEmpty());
    }
}
