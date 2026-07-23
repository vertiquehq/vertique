// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.UnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ServiceUnavailableException}.
 *
 * <p>Verifies message formatting, contract accessor, and inheritance from
 * {@link UnavailableException}. Covers both the single-argument constructor and the
 * two-argument constructor with a custom reason.
 */
class ServiceUnavailableExceptionTest {

    interface MyService {}

    @Test
    @DisplayName("Message should contain contract simple name and explanation")
    void constructsWithContractClass() {
        ServiceUnavailableException ex = new ServiceUnavailableException(MyService.class);

        assertTrue(ex.getMessage().contains("MyService"), "message should contain simple name");
        assertTrue(ex.getMessage().contains("restart budget exhausted"), "message should explain cause");
    }

    @Test
    @DisplayName("contract() should return the class passed to the constructor")
    void contractGetterReturnsClass() {
        ServiceUnavailableException ex = new ServiceUnavailableException(MyService.class);

        assertSame(MyService.class, ex.contract());
    }

    @Test
    @DisplayName("Should be an instance of UnavailableException")
    void isUnavailableException() {
        ServiceUnavailableException ex = new ServiceUnavailableException(MyService.class);

        assertInstanceOf(UnavailableException.class, ex);
    }

    @Test
    @DisplayName("Message should follow the pattern: 'Service unavailable: <Name> (restart budget exhausted)'")
    void messageFollowsExpectedPattern() {
        ServiceUnavailableException ex = new ServiceUnavailableException(String.class);

        assertEquals("Service unavailable: String (restart budget exhausted)", ex.getMessage());
    }

    @Test
    @DisplayName("Two-argument constructor should produce message containing the custom reason")
    void twoArgConstructorProducesMessageWithReason() {
        ServiceUnavailableException ex = new ServiceUnavailableException(MyService.class, "no handlers registered");

        assertTrue(ex.getMessage().contains("MyService"), "message should contain contract simple name");
        assertTrue(ex.getMessage().contains("no handlers registered"), "message should contain the reason");
    }

    @Test
    @DisplayName("Two-argument constructor message should follow the expected pattern")
    void twoArgConstructorMessageFollowsExpectedPattern() {
        ServiceUnavailableException ex = new ServiceUnavailableException(String.class, "no handlers registered");

        assertEquals("Service unavailable: String (no handlers registered)", ex.getMessage());
    }

    @Test
    @DisplayName("contract() should return the class passed to the two-argument constructor")
    void contractGetterReturnClassFromTwoArgConstructor() {
        ServiceUnavailableException ex = new ServiceUnavailableException(MyService.class, "no handlers registered");

        assertSame(MyService.class, ex.contract());
    }

    @Test
    @DisplayName("Single-argument constructor should delegate to two-argument constructor with default reason")
    void singleArgConstructorDelegatesToTwoArgWithDefaultReason() {
        ServiceUnavailableException ex = new ServiceUnavailableException(MyService.class);

        assertEquals("Service unavailable: MyService (restart budget exhausted)", ex.getMessage());
    }
}
