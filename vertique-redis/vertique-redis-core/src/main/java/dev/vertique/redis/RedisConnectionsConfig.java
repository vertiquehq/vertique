// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Typed root configuration for named Redis connection profiles. */
public record RedisConnectionsConfig(List<RedisConnectionConfig> connections) {
    public RedisConnectionsConfig {
        connections = List.copyOf(Objects.requireNonNull(connections, "connections"));
        Set<String> names = new HashSet<>();
        for (RedisConnectionConfig connection : connections) {
            Objects.requireNonNull(connection, "connections must not contain null profiles");
            if (!names.add(connection.name())) {
                throw new IllegalArgumentException("Redis connection names must be unique");
            }
        }
    }
}
