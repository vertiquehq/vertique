// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.SecurityContext;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests verifying the sealed structure of {@link ChannelLifecycleEvent}.
 *
 * <p>Asserts that the interface is sealed, that exactly the three expected permits are declared,
 * and that each permit correctly implements the interface's accessor contract.
 */
class ChannelLifecycleEventSealedTest {

    private static final Set<Class<?>> EXPECTED_PERMITS =
            Set.of(ChannelOpenedEvent.class, ChannelIdentityRefreshedEvent.class, ChannelClosedEvent.class);

    private static final Instant NOW = Instant.parse("2026-01-01T15:00:00Z");
    private static final String CHANNEL_ID = "ch-sealed-test";
    private static final SecurityContext SECURITY_CTX = EventTestFixtures.minimalSecurityContext();
    private static final CorrelationContext CORRELATION = EventTestFixtures.minimalCorrelation();

    // --- sealed interface structure ---

    @Test
    @DisplayName("ChannelLifecycleEvent is a sealed interface")
    void isSealed() {
        assertTrue(ChannelLifecycleEvent.class.isSealed(), "ChannelLifecycleEvent must be sealed");
    }

    @Test
    @DisplayName("ChannelLifecycleEvent has exactly 3 permitted subclasses")
    void hasExactlyThreePermits() {
        Class<?>[] permitted = ChannelLifecycleEvent.class.getPermittedSubclasses();
        assertEquals(3, permitted.length, "Expected exactly 3 permitted subclasses");
    }

    @Test
    @DisplayName("permitted subclasses are exactly Opened, IdentityRefreshed, and Closed")
    void permittedSubclassesMatchExpected() {
        Set<Class<?>> actual = Arrays.stream(ChannelLifecycleEvent.class.getPermittedSubclasses())
                .collect(Collectors.toSet());
        assertEquals(EXPECTED_PERMITS, actual);
    }

    // --- each permit implements the interface ---

    @Test
    @DisplayName("ChannelOpenedEvent implements ChannelLifecycleEvent and interface methods return correct values")
    void channelOpenedEventImplementsInterface() {
        ChannelOpenedEvent event = new ChannelOpenedEvent(NOW, CHANNEL_ID, SECURITY_CTX, CORRELATION);

        assertInstanceOf(ChannelLifecycleEvent.class, event);
        assertEquals(NOW, event.occurredAt());
        assertEquals(CHANNEL_ID, event.channelId());
        assertEquals(SECURITY_CTX, event.securityContext());
        assertEquals(CORRELATION, event.correlation());
    }

    @Test
    @DisplayName(
            "ChannelIdentityRefreshedEvent implements ChannelLifecycleEvent and interface methods return correct values")
    void channelIdentityRefreshedEventImplementsInterface() {
        ChannelIdentityRefreshedEvent event =
                new ChannelIdentityRefreshedEvent(NOW, CHANNEL_ID, SECURITY_CTX, CORRELATION, SECURITY_CTX);

        assertInstanceOf(ChannelLifecycleEvent.class, event);
        assertEquals(NOW, event.occurredAt());
        assertEquals(CHANNEL_ID, event.channelId());
        assertEquals(SECURITY_CTX, event.securityContext());
        assertEquals(CORRELATION, event.correlation());
    }

    @Test
    @DisplayName("ChannelClosedEvent implements ChannelLifecycleEvent and interface methods return correct values")
    void channelClosedEventImplementsInterface() {
        ChannelClosedEvent event =
                new ChannelClosedEvent(NOW, CHANNEL_ID, SECURITY_CTX, CORRELATION, "SERVER_SHUTDOWN");

        assertInstanceOf(ChannelLifecycleEvent.class, event);
        assertEquals(NOW, event.occurredAt());
        assertEquals(CHANNEL_ID, event.channelId());
        assertEquals(SECURITY_CTX, event.securityContext());
        assertEquals(CORRELATION, event.correlation());
    }

    // --- sealed switch exhaustiveness ---

    @Test
    @DisplayName("sealed switch over ChannelLifecycleEvent is exhaustive at compile time")
    void sealedSwitchIsExhaustive() {
        ChannelLifecycleEvent event = new ChannelOpenedEvent(NOW, CHANNEL_ID, SECURITY_CTX, CORRELATION);

        // The Java compiler enforces exhaustiveness for sealed switch expressions.
        // If a new permit is added without updating this switch, compilation fails.
        String type =
                switch (event) {
                    case ChannelOpenedEvent e -> "opened";
                    case ChannelIdentityRefreshedEvent e -> "refreshed";
                    case ChannelClosedEvent e -> "closed";
                };
        assertEquals("opened", type);
    }
}
