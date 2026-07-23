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
 * Unit tests for {@link EventBusDispatchException}.
 *
 * <p>Verifies message formatting, address accessor, cause propagation, and type hierarchy.
 */
class EventBusDispatchExceptionTest {

    @Test
    @DisplayName("Message should contain the address")
    void messageShouldContainAddress() {
        EventBusDispatchException ex =
                new EventBusDispatchException("services/my/address", "operation failed", new RuntimeException("root"));

        assertTrue(ex.getMessage().contains("services/my/address"), "message should contain address");
    }

    @Test
    @DisplayName("Message should contain the failure message")
    void messageShouldContainFailureMessage() {
        EventBusDispatchException ex =
                new EventBusDispatchException("addr", "handler rejected request", new RuntimeException("root"));

        assertTrue(ex.getMessage().contains("handler rejected request"), "message should contain failure message");
    }

    @Test
    @DisplayName("address() accessor should return the address passed to the constructor")
    void addressAccessorReturnsAddress() {
        EventBusDispatchException ex =
                new EventBusDispatchException("test.address", "failed", new RuntimeException("root"));

        assertEquals("test.address", ex.address());
    }

    @Test
    @DisplayName("Cause should be preserved")
    void causeIsPreserved() {
        Throwable cause = new RuntimeException("recipient failure");
        EventBusDispatchException ex = new EventBusDispatchException("addr", "msg", cause);

        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("Should be an instance of TechnicalException")
    void isInstanceOfTechnicalException() {
        EventBusDispatchException ex = new EventBusDispatchException("addr", "msg", new RuntimeException("root"));

        assertInstanceOf(TechnicalException.class, ex);
    }

    @Test
    @DisplayName("Message should follow the expected pattern")
    void messageFollowsExpectedPattern() {
        EventBusDispatchException ex =
                new EventBusDispatchException("services/foo/bar", "handler failed", new RuntimeException("root"));

        assertEquals("Event bus dispatch failed at services/foo/bar: handler failed", ex.getMessage());
    }
}
