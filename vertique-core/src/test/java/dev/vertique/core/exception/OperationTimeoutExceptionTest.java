// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OperationTimeoutExceptionTest {

    @Test
    @DisplayName("is a technical failure that is not an unavailability")
    void isTechnicalAndNotUnavailable() {
        OperationTimeoutException timeout = new OperationTimeoutException("deadline elapsed");

        assertSame(TechnicalException.class, OperationTimeoutException.class.getSuperclass());
        assertFalse(UnavailableException.class.isAssignableFrom(OperationTimeoutException.class));
        assertEquals("deadline elapsed", timeout.getMessage());
    }

    @Test
    @DisplayName("keeps its cause")
    void keepsCause() {
        Throwable cause = new IllegalStateException("slow");

        assertSame(cause, new OperationTimeoutException("deadline elapsed", cause).getCause());
    }
}
