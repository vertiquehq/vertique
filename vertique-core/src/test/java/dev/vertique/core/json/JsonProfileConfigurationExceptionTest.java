// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.vertique.core.exception.ConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests verifying that {@link JsonProfileConfigurationException} is rooted at the core
 * {@link ConfigurationException} semantic type and carries its message through to the base class.
 */
class JsonProfileConfigurationExceptionTest {

    @Test
    @DisplayName("JsonProfileConfigurationException is a ConfigurationException carrying its message")
    void isA_ConfigurationException() {
        JsonProfileConfigurationException exception = new JsonProfileConfigurationException("x");
        assertInstanceOf(ConfigurationException.class, exception);
        assertEquals("x", exception.getMessage());
    }
}
