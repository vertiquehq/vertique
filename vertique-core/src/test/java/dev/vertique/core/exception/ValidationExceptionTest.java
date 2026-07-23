// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ValidationException}.
 *
 * <p>Verifies both constructors and message/cause propagation.
 */
class ValidationExceptionTest {

    @Test
    @DisplayName("Single-arg constructor sets message")
    void shouldCreateWithMessageOnly() {
        ValidationException ex = new ValidationException("invalid input");

        assertEquals("invalid input", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    @DisplayName("Two-arg constructor sets message and cause")
    void shouldCreateWithMessageAndCause() {
        Throwable cause = new IllegalStateException("root cause");
        ValidationException ex = new ValidationException("wrapped", cause);

        assertEquals("wrapped", ex.getMessage());
        assertSame(cause, ex.getCause());
    }
}
