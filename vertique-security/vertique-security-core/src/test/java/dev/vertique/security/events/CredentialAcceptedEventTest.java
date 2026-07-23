// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.origin.RequestOrigin;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CredentialAcceptedEvent}.
 *
 * <p>Verifies: happy-path construction with all fields; null rejection for each required field;
 * non-null Optional fields; accessor return values match inputs.
 */
class CredentialAcceptedEventTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-01-01T10:00:00Z");
    private static final CorrelationContext CORRELATION = EventTestFixtures.minimalCorrelation();
    private static final AuthenticationState AUTH_STATE =
            new AuthenticationState(DefaultAuthMethod.jwt(), List.of(), Optional.empty(), Optional.empty(), Map.of());
    private static final SecurityIdentity IDENTITY =
            SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-1", Map.of()));

    // --- happy path ---

    @Test
    @DisplayName("constructs with all fields populated and accessors return expected values")
    void happyPathAllFields() {
        RequestOrigin origin = EventTestFixtures.minimalRequestOrigin();
        CredentialAcceptedEvent event =
                new CredentialAcceptedEvent(OCCURRED_AT, CORRELATION, Optional.of(origin), AUTH_STATE, IDENTITY);

        assertEquals(OCCURRED_AT, event.occurredAt());
        assertEquals(CORRELATION, event.correlation());
        assertTrue(event.origin().isPresent());
        assertEquals(AUTH_STATE, event.authentication());
        assertEquals(IDENTITY, event.identity());
    }

    @Test
    @DisplayName("constructs with empty origin Optional")
    void happyPathEmptyOrigin() {
        CredentialAcceptedEvent event =
                new CredentialAcceptedEvent(OCCURRED_AT, CORRELATION, Optional.empty(), AUTH_STATE, IDENTITY);

        assertFalse(event.origin().isPresent());
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
                    () -> new CredentialAcceptedEvent(null, CORRELATION, Optional.empty(), AUTH_STATE, IDENTITY));
        }

        @Test
        @DisplayName("rejects null correlation")
        void rejectsNullCorrelation() {
            assertThrows(
                    NullPointerException.class,
                    () -> new CredentialAcceptedEvent(OCCURRED_AT, null, Optional.empty(), AUTH_STATE, IDENTITY));
        }

        @Test
        @DisplayName("rejects null origin Optional reference")
        void rejectsNullOriginOptional() {
            assertThrows(
                    NullPointerException.class,
                    () -> new CredentialAcceptedEvent(OCCURRED_AT, CORRELATION, null, AUTH_STATE, IDENTITY));
        }

        @Test
        @DisplayName("rejects null authentication")
        void rejectsNullAuthentication() {
            assertThrows(
                    NullPointerException.class,
                    () -> new CredentialAcceptedEvent(OCCURRED_AT, CORRELATION, Optional.empty(), null, IDENTITY));
        }

        @Test
        @DisplayName("rejects null identity")
        void rejectsNullIdentity() {
            assertThrows(
                    NullPointerException.class,
                    () -> new CredentialAcceptedEvent(OCCURRED_AT, CORRELATION, Optional.empty(), AUTH_STATE, null));
        }
    }
}
