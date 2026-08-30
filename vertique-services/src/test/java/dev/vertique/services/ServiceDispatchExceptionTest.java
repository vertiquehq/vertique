// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.eventbus.EventBusDispatchException;
import dev.vertique.services.exception.ServiceDispatchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ServiceDispatchException}.
 *
 * <p>Verifies message formatting, contract and address accessors, cause propagation, and type
 * hierarchy including {@link EventBusDispatchException}.
 */
class ServiceDispatchExceptionTest {

    interface MyService {}

    @Test
    @DisplayName("Message should contain the contract simple name")
    void messageShouldContainContractName() {
        ServiceDispatchException ex = new ServiceDispatchException(
                MyService.class, "services/my/op", "handler rejected", new RuntimeException("dispatch failed"));

        assertTrue(ex.getMessage().contains("MyService"), "message should contain contract simple name");
    }

    @Test
    @DisplayName("Message should contain the address")
    void messageShouldContainAddress() {
        ServiceDispatchException ex = new ServiceDispatchException(
                MyService.class, "services/my/op", "handler rejected", new RuntimeException("dispatch failed"));

        assertTrue(ex.getMessage().contains("services/my/op"), "message should contain address");
    }

    @Test
    @DisplayName("Message should contain the detail message")
    void messageShouldContainDetailMessage() {
        ServiceDispatchException ex = new ServiceDispatchException(
                MyService.class, "services/my/op", "handler rejected request", new RuntimeException("dispatch failed"));

        assertTrue(ex.getMessage().contains("handler rejected request"), "message should contain detail message");
    }

    @Test
    @DisplayName("contract() should return the class passed to the constructor")
    void contractAccessorReturnsClass() {
        ServiceDispatchException ex = new ServiceDispatchException(
                MyService.class, "services/my/op", "failed", new RuntimeException("dispatch failed"));

        assertSame(MyService.class, ex.contract());
    }

    @Test
    @DisplayName("address() should return the address passed to the constructor (inherited)")
    void addressAccessorReturnsAddress() {
        ServiceDispatchException ex = new ServiceDispatchException(
                MyService.class, "services/svc/op", "failed", new RuntimeException("dispatch failed"));

        assertEquals("services/svc/op", ex.address());
    }

    @Test
    @DisplayName("getCause() should preserve the original cause")
    void causeIsPreserved() {
        Throwable cause = new RuntimeException("original dispatch failure");
        ServiceDispatchException ex = new ServiceDispatchException(MyService.class, "services/svc/op", "failed", cause);

        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("Should be an instance of EventBusDispatchException")
    void isInstanceOfEventBusDispatchException() {
        ServiceDispatchException ex = new ServiceDispatchException(
                MyService.class, "services/my/op", "failed", new RuntimeException("dispatch failed"));

        assertInstanceOf(EventBusDispatchException.class, ex);
    }

    @Test
    @DisplayName("Message should follow the expected service-specific pattern")
    void messageFollowsExpectedPattern() {
        ServiceDispatchException ex = new ServiceDispatchException(
                String.class, "services/foo/bar", "handler failed", new RuntimeException("dispatch failed"));

        assertEquals("Service dispatch failed: String at services/foo/bar: handler failed", ex.getMessage());
    }
}
