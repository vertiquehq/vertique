// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies the shared Redis profile validation boundary. */
class RedisConnectionConfigTest {
    @Test
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
}
