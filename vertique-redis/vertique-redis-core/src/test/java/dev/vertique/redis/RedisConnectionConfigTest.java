// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the shared Redis profile validation boundary. */
class RedisConnectionConfigTest {
    @Test
    @DisplayName("accepts complete credential-free Redis profile")
    void acceptsMultipleCredentialFreeEndpoints() {
        RedisConnectionConfig config = new RedisConnectionConfig(
                "primary",
                List.of("rediss://redis-a:6380", "rediss://redis-b:6380"),
                "cache",
                "secret/ref",
                true,
                500,
                8,
                100);

        assertEquals(2, config.endpoints().size());
        assertEquals("secret/ref", config.passwordSecret());
    }

    @Test
    @DisplayName("rejects credentials in endpoints and invalid timeout bounds")
    void rejectsCredentialsAndInvalidBoundsInEndpoints() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig(
                        "primary", List.of("redis://user:password@redis:6379"), null, null, false, 500, 8, 100));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig(
                        "primary", List.of("redis://redis:6379"), null, null, false, 0, 8, 100));
    }

    @Test
    @DisplayName("rejects blank names and unsupported endpoint syntax")
    void rejectsBlankNamesAndUnsupportedEndpointSyntax() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig(" ", List.of("redis://redis:6379"), null, null, false, 500, 8, 100));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig(
                        "primary", List.of("http://redis:6379"), null, null, false, 500, 8, 100));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig(
                        "primary", List.of("redis://redis:6379?password=secret"), null, null, false, 500, 8, 100));
    }

    @Test
    @DisplayName("rejects invalid pool bounds")
    void rejectsInvalidPoolBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig(
                        "primary", List.of("redis://redis:6379"), null, null, false, 500, 0, 100));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig(
                        "primary", List.of("redis://redis:6379"), null, null, false, 500, 8, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig(
                        "primary", List.of("redis://redis:6379"), null, null, false, 500, 257, 100));
    }

    @Test
    @DisplayName("does not leak credential references in validation diagnostics")
    void doesNotLeakCredentialReferencesInValidationDiagnostics() {
        String secretReference = "vault/prod/redis-password";
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new RedisConnectionConfig(
                        "primary",
                        List.of("redis://user:actual-password@redis:6379"),
                        null,
                        secretReference,
                        false,
                        0,
                        8,
                        100));

        assertFalse(failure.getMessage().contains(secretReference));
        assertFalse(failure.getMessage().contains("actual-password"));
    }
}
