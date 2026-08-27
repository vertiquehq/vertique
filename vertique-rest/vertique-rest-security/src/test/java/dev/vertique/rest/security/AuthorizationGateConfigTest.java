// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.exception.ConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuthorizationGateConfig} (issue #417, R42).
 *
 * <p>Verifies the default gate deadline, the {@code fromJson} Jackson factory's omitted-value
 * default, and the compact constructor's positive-only validation — mirroring {@code
 * PrincipalAuthorityResolutionConfig}'s own established validation shape.
 */
class AuthorizationGateConfigTest {

    @Nested
    @DisplayName("defaults()")
    class Defaults {

        @Test
        @DisplayName("gateDeadlineMs is DEFAULT_GATE_DEADLINE_MS (5000)")
        void defaultGateDeadlineMsIs5000() {
            assertEquals(
                    AuthorizationGateConfig.DEFAULT_GATE_DEADLINE_MS,
                    AuthorizationGateConfig.defaults().gateDeadlineMs());
            assertEquals(5_000L, AuthorizationGateConfig.DEFAULT_GATE_DEADLINE_MS);
        }
    }

    @Nested
    @DisplayName("fromJson(...)")
    class FromJson {

        @Test
        @DisplayName("null gateDeadlineMs defaults to DEFAULT_GATE_DEADLINE_MS")
        void nullGateDeadlineMsDefaults() {
            AuthorizationGateConfig config = AuthorizationGateConfig.fromJson(null);
            assertEquals(AuthorizationGateConfig.DEFAULT_GATE_DEADLINE_MS, config.gateDeadlineMs());
        }

        @Test
        @DisplayName("an explicit gateDeadlineMs is honored")
        void explicitGateDeadlineMsIsHonored() {
            AuthorizationGateConfig config = AuthorizationGateConfig.fromJson(250L);
            assertEquals(250L, config.gateDeadlineMs());
        }
    }

    @Nested
    @DisplayName("constructor validation")
    class Validation {

        @Test
        @DisplayName("zero gateDeadlineMs throws ConfigurationException")
        void zeroGateDeadlineMsThrows() {
            assertThrows(ConfigurationException.class, () -> new AuthorizationGateConfig(0L));
        }

        @Test
        @DisplayName("negative gateDeadlineMs throws ConfigurationException")
        void negativeGateDeadlineMsThrows() {
            assertThrows(ConfigurationException.class, () -> new AuthorizationGateConfig(-1L));
        }

        @Test
        @DisplayName("a positive gateDeadlineMs is allowed")
        void positiveGateDeadlineMsIsAllowed() {
            assertDoesNotThrow(() -> new AuthorizationGateConfig(1L));
        }
    }
}
