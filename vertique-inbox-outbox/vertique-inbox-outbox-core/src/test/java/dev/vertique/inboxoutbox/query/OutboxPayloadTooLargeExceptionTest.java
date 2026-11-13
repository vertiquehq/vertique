// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.core.exception.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link OutboxPayloadTooLargeException}. */
@DisplayName("OutboxPayloadTooLargeException")
class OutboxPayloadTooLargeExceptionTest {

    @Test
    @DisplayName("is a conflict with a fixed message that names no entry and no content")
    void fixedMessage() {
        OutboxPayloadTooLargeException exception = new OutboxPayloadTooLargeException();

        assertInstanceOf(ConflictException.class, exception);
        assertEquals("The outbox entry content exceeds the configured limit", exception.getMessage());
        assertNull(exception.getCause());
    }
}
