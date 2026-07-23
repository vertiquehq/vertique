// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.AuthMethod;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.verification.JwksVerificationSource;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CredentialRejectedEvent}.
 *
 * <p>Verifies: happy-path construction with all optional fields; null rejection for each required
 * field; null Optional references rejected; blank {@code reasonCode} rejected; null
 * {@code safeAttributes} treated as empty map; defensive copy of {@code safeAttributes}.
 */
class CredentialRejectedEventTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-01-01T10:00:00Z");
    private static final CorrelationContext CORRELATION = EventTestFixtures.minimalCorrelation();
    private static final AuthMethod JWT_METHOD = DefaultAuthMethod.jwt();
    private static final JwksVerificationSource JWKS_SOURCE = new JwksVerificationSource(
            Optional.of("https://idp.example.com"),
            Optional.of("https://idp.example.com/.well-known/jwks.json"),
            Optional.of("key-001"),
            Optional.of("RS256"));

    // --- happy path ---

    @Test
    @DisplayName("constructs with all optional fields present and accessors return expected values")
    void happyPathAllFields() {
        CredentialRejectedEvent event = new CredentialRejectedEvent(
                OCCURRED_AT,
                CORRELATION,
                Optional.of(EventTestFixtures.minimalRequestOrigin()),
                JWT_METHOD,
                Optional.of("jti-abc"),
                Optional.of(JWKS_SOURCE),
                "TOKEN_EXPIRED",
                Map.of("audience", "api.example.com"));

        assertEquals(OCCURRED_AT, event.occurredAt());
        assertEquals(CORRELATION, event.correlation());
        assertTrue(event.origin().isPresent());
        assertEquals(JWT_METHOD, event.attemptedMethod());
        assertEquals(Optional.of("jti-abc"), event.credentialId());
        assertEquals(Optional.of(JWKS_SOURCE), event.verificationSource());
        assertEquals("TOKEN_EXPIRED", event.reasonCode());
        assertEquals("api.example.com", event.safeAttributes().get("audience"));
    }

    @Test
    @DisplayName("constructs with all empty Optional fields and empty safeAttributes")
    void happyPathMinimal() {
        CredentialRejectedEvent event = new CredentialRejectedEvent(
                OCCURRED_AT,
                CORRELATION,
                Optional.empty(),
                JWT_METHOD,
                Optional.empty(),
                Optional.empty(),
                "SIGNATURE_INVALID",
                Map.of());

        assertTrue(event.origin().isEmpty());
        assertTrue(event.credentialId().isEmpty());
        assertTrue(event.verificationSource().isEmpty());
        assertTrue(event.safeAttributes().isEmpty());
    }

    // --- null rejection ---

    @Nested
    @DisplayName("null rejection")
    class NullRejection {

        @Test
        @DisplayName("rejects null occurredAt")
        void rejectsNullOccurredAt() {
            assertThrows(
                    NullPointerException.class,
                    () -> new CredentialRejectedEvent(
                            null,
                            CORRELATION,
                            Optional.empty(),
                            JWT_METHOD,
                            Optional.empty(),
                            Optional.empty(),
                            "CODE",
                            Map.of()));
        }

        @Test
        @DisplayName("rejects null correlation")
        void rejectsNullCorrelation() {
            assertThrows(
                    NullPointerException.class,
                    () -> new CredentialRejectedEvent(
                            OCCURRED_AT,
                            null,
                            Optional.empty(),
                            JWT_METHOD,
                            Optional.empty(),
                            Optional.empty(),
                            "CODE",
                            Map.of()));
        }

        @Test
        @DisplayName("rejects null origin Optional reference")
        void rejectsNullOriginOptional() {
            assertThrows(
                    NullPointerException.class,
                    () -> new CredentialRejectedEvent(
                            OCCURRED_AT,
                            CORRELATION,
                            null,
                            JWT_METHOD,
                            Optional.empty(),
                            Optional.empty(),
                            "CODE",
                            Map.of()));
        }

        @Test
        @DisplayName("rejects null attemptedMethod")
        void rejectsNullAttemptedMethod() {
            assertThrows(
                    NullPointerException.class,
                    () -> new CredentialRejectedEvent(
                            OCCURRED_AT,
                            CORRELATION,
                            Optional.empty(),
                            null,
                            Optional.empty(),
                            Optional.empty(),
                            "CODE",
                            Map.of()));
        }

        @Test
        @DisplayName("rejects null credentialId Optional reference")
        void rejectsNullCredentialIdOptional() {
            assertThrows(
                    NullPointerException.class,
                    () -> new CredentialRejectedEvent(
                            OCCURRED_AT,
                            CORRELATION,
                            Optional.empty(),
                            JWT_METHOD,
                            null,
                            Optional.empty(),
                            "CODE",
                            Map.of()));
        }

        @Test
        @DisplayName("rejects null verificationSource Optional reference")
        void rejectsNullVerificationSourceOptional() {
            assertThrows(
                    NullPointerException.class,
                    () -> new CredentialRejectedEvent(
                            OCCURRED_AT,
                            CORRELATION,
                            Optional.empty(),
                            JWT_METHOD,
                            Optional.empty(),
                            null,
                            "CODE",
                            Map.of()));
        }

        @Test
        @DisplayName("rejects null reasonCode")
        void rejectsNullReasonCode() {
            assertThrows(
                    NullPointerException.class,
                    () -> new CredentialRejectedEvent(
                            OCCURRED_AT,
                            CORRELATION,
                            Optional.empty(),
                            JWT_METHOD,
                            Optional.empty(),
                            Optional.empty(),
                            null,
                            Map.of()));
        }
    }

    // --- blank reasonCode ---

    @Test
    @DisplayName("rejects blank reasonCode")
    void rejectsBlankReasonCode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CredentialRejectedEvent(
                        OCCURRED_AT,
                        CORRELATION,
                        Optional.empty(),
                        JWT_METHOD,
                        Optional.empty(),
                        Optional.empty(),
                        "   ",
                        Map.of()));
    }

    // --- null safeAttributes treated as empty ---

    @Test
    @DisplayName("null safeAttributes is treated as empty map")
    void nullSafeAttributesTreatedAsEmpty() {
        CredentialRejectedEvent event = new CredentialRejectedEvent(
                OCCURRED_AT,
                CORRELATION,
                Optional.empty(),
                JWT_METHOD,
                Optional.empty(),
                Optional.empty(),
                "CODE",
                null);

        assertTrue(event.safeAttributes().isEmpty());
    }

    // --- defensive copy of safeAttributes ---

    @Test
    @DisplayName("safeAttributes is a defensive copy — mutations to the original map do not affect the record")
    void safeAttributesDefensiveCopy() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v1");
        CredentialRejectedEvent event = new CredentialRejectedEvent(
                OCCURRED_AT,
                CORRELATION,
                Optional.empty(),
                JWT_METHOD,
                Optional.empty(),
                Optional.empty(),
                "CODE",
                mutable);

        mutable.put("k", "v2");
        assertEquals("v1", event.safeAttributes().get("k"));
    }
}
