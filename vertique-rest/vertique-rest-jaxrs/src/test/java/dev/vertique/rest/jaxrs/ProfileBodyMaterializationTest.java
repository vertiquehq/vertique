// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.core.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link ProfileBodyMaterialization#rejection} keeps the client-facing message static and
 * value-free while retaining the original rejection as the exception cause for server-side
 * diagnosis (the cause is never serialized to the client — the REST mapper surfaces only
 * {@code getMessage()}).
 */
class ProfileBodyMaterializationTest {

    @Test
    @DisplayName("rejection retains the original throwable as the exception cause")
    void rejection_retainsCause() {
        Throwable cause = new IllegalArgumentException("Cannot coerce String \"5\" to int");
        ValidationException ex = ProfileBodyMaterialization.rejection(cause);
        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("rejection client message is static and value-free (no record content)")
    void rejection_messageIsValueFree() {
        ValidationException ex =
                ProfileBodyMaterialization.rejection(new IllegalArgumentException("offending value SENTINEL_999"));
        assertEquals("Request body rejected by JSON profile", ex.getMessage());
        assertFalse(ex.getMessage().contains("SENTINEL_999"));
    }
}
