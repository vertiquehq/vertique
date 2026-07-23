// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChannelIdentityRefreshedEvent}.
 *
 * <p>Verifies: happy-path construction; null rejection for each required field (including
 * {@code priorSecurityContext}); blank {@code channelId} rejected; accessors return expected
 * values; implements {@link ChannelLifecycleEvent}.
 */
class ChannelIdentityRefreshedEventTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-01-01T13:00:00Z");
    private static final String CHANNEL_ID = "ws-channel-002";
    private static final SecurityContext CURRENT_CTX = EventTestFixtures.minimalSecurityContext();
    private static final SecurityContext PRIOR_CTX = new SecurityContext() {
        private final SecurityIdentity identity =
                SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-old", Map.of()));
        private final AuthenticationState authentication = new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());

        @Override
        public SecurityIdentity identity() {
            return identity;
        }

        @Override
        public AuthenticationState authentication() {
            return authentication;
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
    private static final CorrelationContext CORRELATION = EventTestFixtures.minimalCorrelation();

    // --- happy path ---

    @Test
    @DisplayName("constructs and accessors return expected values")
    void happyPath() {
        ChannelIdentityRefreshedEvent event =
                new ChannelIdentityRefreshedEvent(OCCURRED_AT, CHANNEL_ID, CURRENT_CTX, CORRELATION, PRIOR_CTX);

        assertEquals(OCCURRED_AT, event.occurredAt());
        assertEquals(CHANNEL_ID, event.channelId());
        assertEquals(CURRENT_CTX, event.securityContext());
        assertEquals(CORRELATION, event.correlation());
        assertEquals(PRIOR_CTX, event.priorSecurityContext());
    }

    @Test
    @DisplayName("implements ChannelLifecycleEvent and securityContext() returns the new context")
    void implementsChannelLifecycleEvent() {
        ChannelLifecycleEvent event =
                new ChannelIdentityRefreshedEvent(OCCURRED_AT, CHANNEL_ID, CURRENT_CTX, CORRELATION, PRIOR_CTX);

        assertEquals(CURRENT_CTX, event.securityContext());
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
                    () -> new ChannelIdentityRefreshedEvent(null, CHANNEL_ID, CURRENT_CTX, CORRELATION, PRIOR_CTX));
        }

        @Test
        @DisplayName("rejects null channelId")
        void rejectsNullChannelId() {
            assertThrows(
                    NullPointerException.class,
                    () -> new ChannelIdentityRefreshedEvent(OCCURRED_AT, null, CURRENT_CTX, CORRELATION, PRIOR_CTX));
        }

        @Test
        @DisplayName("rejects null securityContext")
        void rejectsNullSecurityContext() {
            assertThrows(
                    NullPointerException.class,
                    () -> new ChannelIdentityRefreshedEvent(OCCURRED_AT, CHANNEL_ID, null, CORRELATION, PRIOR_CTX));
        }

        @Test
        @DisplayName("rejects null correlation")
        void rejectsNullCorrelation() {
            assertThrows(
                    NullPointerException.class,
                    () -> new ChannelIdentityRefreshedEvent(OCCURRED_AT, CHANNEL_ID, CURRENT_CTX, null, PRIOR_CTX));
        }

        @Test
        @DisplayName("rejects null priorSecurityContext")
        void rejectsNullPriorSecurityContext() {
            assertThrows(
                    NullPointerException.class,
                    () -> new ChannelIdentityRefreshedEvent(OCCURRED_AT, CHANNEL_ID, CURRENT_CTX, CORRELATION, null));
        }
    }

    // --- blank channelId ---

    @Test
    @DisplayName("rejects blank channelId")
    void rejectsBlankChannelId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelIdentityRefreshedEvent(OCCURRED_AT, "  ", CURRENT_CTX, CORRELATION, PRIOR_CTX));
    }
}
