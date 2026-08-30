// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.eventbus.EventBusTimeoutException;
import dev.vertique.services.exception.ServiceTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ServiceTimeoutException}.
 *
 * <p>Verifies message formatting, contract and address accessors, cause propagation, and type
 * hierarchy including {@link EventBusTimeoutException}.
 */
class ServiceTimeoutExceptionTest {

    interface MyService {}

    @Test
    @DisplayName("Message should contain the contract simple name")
    void messageShouldContainContractName() {
        ServiceTimeoutException ex =
                new ServiceTimeoutException(MyService.class, "services/my/op", new RuntimeException("timeout"));

        assertTrue(ex.getMessage().contains("MyService"), "message should contain contract simple name");
    }

    @Test
    @DisplayName("Message should contain the address")
    void messageShouldContainAddress() {
        ServiceTimeoutException ex =
                new ServiceTimeoutException(MyService.class, "services/my/op", new RuntimeException("timeout"));

        assertTrue(ex.getMessage().contains("services/my/op"), "message should contain address");
    }

    @Test
    @DisplayName("contract() should return the class passed to the constructor")
    void contractAccessorReturnsClass() {
        ServiceTimeoutException ex =
                new ServiceTimeoutException(MyService.class, "services/my/op", new RuntimeException("timeout"));

        assertSame(MyService.class, ex.contract());
    }

    @Test
    @DisplayName("address() should return the address passed to the constructor (inherited)")
    void addressAccessorReturnsAddress() {
        ServiceTimeoutException ex =
                new ServiceTimeoutException(MyService.class, "services/svc/op", new RuntimeException("timeout"));

        assertEquals("services/svc/op", ex.address());
    }

    @Test
    @DisplayName("getCause() should preserve the original cause")
    void causeIsPreserved() {
        Throwable cause = new RuntimeException("original timeout cause");
        ServiceTimeoutException ex = new ServiceTimeoutException(MyService.class, "services/svc/op", cause);

        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("Should be an instance of EventBusTimeoutException")
    void isInstanceOfEventBusTimeoutException() {
        ServiceTimeoutException ex =
                new ServiceTimeoutException(MyService.class, "services/my/op", new RuntimeException("timeout"));

        assertInstanceOf(EventBusTimeoutException.class, ex);
    }

    @Test
    @DisplayName("Message should follow the expected service-specific pattern")
    void messageFollowsExpectedPattern() {
        ServiceTimeoutException ex =
                new ServiceTimeoutException(String.class, "services/foo/bar", new RuntimeException("timeout"));

        assertEquals("Service request timed out: String at services/foo/bar", ex.getMessage());
    }
}
