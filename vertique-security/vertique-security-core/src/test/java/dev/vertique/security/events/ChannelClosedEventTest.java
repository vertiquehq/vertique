// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.SecurityContext;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChannelClosedEvent}.
 *
 * <p>Verifies: happy-path construction; null rejection for each required field; blank
 * {@code channelId} rejected; blank {@code reasonCode} rejected; accessor return values match
 * inputs; implements {@link ChannelLifecycleEvent}.
 */
class ChannelClosedEventTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-01-01T14:00:00Z");
    private static final String CHANNEL_ID = "ws-channel-003";
    private static final SecurityContext SECURITY_CTX = EventTestFixtures.minimalSecurityContext();
    private static final CorrelationContext CORRELATION = EventTestFixtures.minimalCorrelation();
    private static final String REASON_CODE = "CLIENT_DISCONNECT";

    // --- happy path ---

    @Test
    @DisplayName("constructs and accessors return expected values")
    void happyPath() {
        ChannelClosedEvent event =
                new ChannelClosedEvent(OCCURRED_AT, CHANNEL_ID, SECURITY_CTX, CORRELATION, REASON_CODE);

        assertEquals(OCCURRED_AT, event.occurredAt());
        assertEquals(CHANNEL_ID, event.channelId());
        assertEquals(SECURITY_CTX, event.securityContext());
        assertEquals(CORRELATION, event.correlation());
        assertEquals(REASON_CODE, event.reasonCode());
    }

    @Test
    @DisplayName("implements ChannelLifecycleEvent")
    void implementsChannelLifecycleEvent() {
        ChannelLifecycleEvent event =
                new ChannelClosedEvent(OCCURRED_AT, CHANNEL_ID, SECURITY_CTX, CORRELATION, REASON_CODE);

        assertEquals(OCCURRED_AT, event.occurredAt());
        assertEquals(CHANNEL_ID, event.channelId());
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
                    () -> new ChannelClosedEvent(null, CHANNEL_ID, SECURITY_CTX, CORRELATION, REASON_CODE));
        }

        @Test
        @DisplayName("rejects null channelId")
        void rejectsNullChannelId() {
            assertThrows(
                    NullPointerException.class,
                    () -> new ChannelClosedEvent(OCCURRED_AT, null, SECURITY_CTX, CORRELATION, REASON_CODE));
        }

        @Test
        @DisplayName("rejects null securityContext")
        void rejectsNullSecurityContext() {
            assertThrows(
                    NullPointerException.class,
                    () -> new ChannelClosedEvent(OCCURRED_AT, CHANNEL_ID, null, CORRELATION, REASON_CODE));
        }

        @Test
        @DisplayName("rejects null correlation")
        void rejectsNullCorrelation() {
            assertThrows(
                    NullPointerException.class,
                    () -> new ChannelClosedEvent(OCCURRED_AT, CHANNEL_ID, SECURITY_CTX, null, REASON_CODE));
        }

        @Test
        @DisplayName("rejects null reasonCode")
        void rejectsNullReasonCode() {
            assertThrows(
                    NullPointerException.class,
                    () -> new ChannelClosedEvent(OCCURRED_AT, CHANNEL_ID, SECURITY_CTX, CORRELATION, null));
        }
    }

    // --- blank field rejection ---

    @Test
    @DisplayName("rejects blank channelId")
    void rejectsBlankChannelId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelClosedEvent(OCCURRED_AT, "  ", SECURITY_CTX, CORRELATION, REASON_CODE));
    }

    @Test
    @DisplayName("rejects blank reasonCode")
    void rejectsBlankReasonCode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelClosedEvent(OCCURRED_AT, CHANNEL_ID, SECURITY_CTX, CORRELATION, "  "));
    }
}
