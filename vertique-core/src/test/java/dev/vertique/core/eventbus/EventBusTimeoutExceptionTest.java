// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.eventbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.TechnicalException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EventBusTimeoutException}.
 *
 * <p>Verifies message formatting, address accessor, cause propagation, and type hierarchy.
 */
class EventBusTimeoutExceptionTest {

    @Test
    @DisplayName("Message should contain the address")
    void messageShouldContainAddress() {
        EventBusTimeoutException ex = new EventBusTimeoutException("services/my/address", new RuntimeException("root"));

        assertTrue(ex.getMessage().contains("services/my/address"), "message should contain address");
    }

    @Test
    @DisplayName("address() accessor should return the address passed to the constructor")
    void addressAccessorReturnsAddress() {
        EventBusTimeoutException ex = new EventBusTimeoutException("test.address", new RuntimeException("root"));

        assertEquals("test.address", ex.address());
    }

    @Test
    @DisplayName("Cause should be preserved")
    void causeIsPreserved() {
        Throwable cause = new RuntimeException("timeout cause");
        EventBusTimeoutException ex = new EventBusTimeoutException("addr", cause);

        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("Should be an instance of TechnicalException")
    void isInstanceOfTechnicalException() {
        EventBusTimeoutException ex = new EventBusTimeoutException("addr", new RuntimeException("root"));

        assertInstanceOf(TechnicalException.class, ex);
    }

    @Test
    @DisplayName("Message should follow the expected pattern")
    void messageFollowsExpectedPattern() {
        EventBusTimeoutException ex = new EventBusTimeoutException("services/foo/bar", new RuntimeException("root"));

        assertEquals("Event bus request timed out at services/foo/bar", ex.getMessage());
    }
}
