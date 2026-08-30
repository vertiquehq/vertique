// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the typed collection boundary for named Redis profiles. */
class RedisConnectionsConfigTest {
    @Test
    @DisplayName("accepts unique named profiles and preserves their order")
    void acceptsUniqueNamedProfiles() {
        RedisConnectionConfig primary = profile("primary");
        RedisConnectionConfig replica = profile("replica");

        RedisConnectionsConfig config = new RedisConnectionsConfig(List.of(primary, replica));

        assertEquals(List.of(primary, replica), config.connections());
    }

    @Test
    @DisplayName("rejects duplicate profile names")
    void rejectsDuplicateProfileNames() {
        RedisConnectionConfig primary = profile("primary");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> new RedisConnectionsConfig(List.of(primary, primary)));

        assertTrue(failure.getMessage().contains("unique"));
    }

    @Test
    @DisplayName("copies the profile collection defensively")
    void copiesProfileCollectionDefensively() {
        List<RedisConnectionConfig> profiles = new java.util.ArrayList<>(List.of(profile("primary")));
        RedisConnectionsConfig config = new RedisConnectionsConfig(profiles);

        profiles.clear();

        assertEquals(1, config.connections().size());
    }

    private static RedisConnectionConfig profile(String name) {
        return new RedisConnectionConfig(name, List.of("redis://redis:6379"), null, null, false, 500, 8, 100);
    }
}
