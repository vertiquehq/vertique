// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.eventbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.UnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EventBusAddressUnavailableException}.
 *
 * <p>Verifies message formatting, address accessor, cause propagation, and type hierarchy.
 */
class EventBusAddressUnavailableExceptionTest {

    @Test
    @DisplayName("Message should contain the address")
    void messageShouldContainAddress() {
        EventBusAddressUnavailableException ex =
                new EventBusAddressUnavailableException("services/my/address", new RuntimeException("root"));

        assertTrue(ex.getMessage().contains("services/my/address"), "message should contain address");
    }

    @Test
    @DisplayName("address() accessor should return the address passed to the constructor")
    void addressAccessorReturnsAddress() {
        EventBusAddressUnavailableException ex =
                new EventBusAddressUnavailableException("test.address", new RuntimeException("root"));

        assertEquals("test.address", ex.address());
    }

    @Test
    @DisplayName("Cause should be preserved")
    void causeIsPreserved() {
        Throwable cause = new RuntimeException("no handlers");
        EventBusAddressUnavailableException ex = new EventBusAddressUnavailableException("addr", cause);

        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("Should be an instance of UnavailableException")
    void isInstanceOfUnavailableException() {
        EventBusAddressUnavailableException ex =
                new EventBusAddressUnavailableException("addr", new RuntimeException("root"));

        assertInstanceOf(UnavailableException.class, ex);
    }

    @Test
    @DisplayName("Message should follow the expected pattern")
    void messageFollowsExpectedPattern() {
        EventBusAddressUnavailableException ex =
                new EventBusAddressUnavailableException("services/foo/bar", new RuntimeException("root"));

        assertEquals("No handlers at address: services/foo/bar", ex.getMessage());
    }
}
