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
 * Unit tests for {@link ChannelOpenedEvent}.
 *
 * <p>Verifies: happy-path construction; null rejection for each required field; blank
 * {@code channelId} rejected; accessor return values match inputs; implements
 * {@link ChannelLifecycleEvent}.
 */
class ChannelOpenedEventTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-01-01T12:00:00Z");
    private static final String CHANNEL_ID = "ws-channel-001";
    private static final SecurityContext SECURITY_CTX = EventTestFixtures.minimalSecurityContext();
    private static final CorrelationContext CORRELATION = EventTestFixtures.minimalCorrelation();

    // --- happy path ---

    @Test
    @DisplayName("constructs and accessors return expected values")
    void happyPath() {
        ChannelOpenedEvent event = new ChannelOpenedEvent(OCCURRED_AT, CHANNEL_ID, SECURITY_CTX, CORRELATION);

        assertEquals(OCCURRED_AT, event.occurredAt());
        assertEquals(CHANNEL_ID, event.channelId());
        assertEquals(SECURITY_CTX, event.securityContext());
        assertEquals(CORRELATION, event.correlation());
    }

    @Test
    @DisplayName("implements ChannelLifecycleEvent")
    void implementsChannelLifecycleEvent() {
        ChannelLifecycleEvent event = new ChannelOpenedEvent(OCCURRED_AT, CHANNEL_ID, SECURITY_CTX, CORRELATION);

        assertEquals(OCCURRED_AT, event.occurredAt());
        assertEquals(CHANNEL_ID, event.channelId());
        assertEquals(SECURITY_CTX, event.securityContext());
        assertEquals(CORRELATION, event.correlation());
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
                    () -> new ChannelOpenedEvent(null, CHANNEL_ID, SECURITY_CTX, CORRELATION));
        }

        @Test
        @DisplayName("rejects null channelId")
        void rejectsNullChannelId() {
            assertThrows(
                    NullPointerException.class,
                    () -> new ChannelOpenedEvent(OCCURRED_AT, null, SECURITY_CTX, CORRELATION));
        }

        @Test
        @DisplayName("rejects null securityContext")
        void rejectsNullSecurityContext() {
            assertThrows(
                    NullPointerException.class,
                    () -> new ChannelOpenedEvent(OCCURRED_AT, CHANNEL_ID, null, CORRELATION));
        }

        @Test
        @DisplayName("rejects null correlation")
        void rejectsNullCorrelation() {
            assertThrows(
                    NullPointerException.class,
                    () -> new ChannelOpenedEvent(OCCURRED_AT, CHANNEL_ID, SECURITY_CTX, null));
        }
    }

    // --- blank channelId ---

    @Test
    @DisplayName("rejects blank channelId")
    void rejectsBlankChannelId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChannelOpenedEvent(OCCURRED_AT, "   ", SECURITY_CTX, CORRELATION));
    }
}
