// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Typed root configuration for named Redis connection profiles. */
public record RedisConnectionsConfig(List<RedisConnectionConfig> connections) {
    public RedisConnectionsConfig {
        connections = List.copyOf(Objects.requireNonNull(connections, "connections"));
        Map<String, RedisConnectionConfig> byName = connections.stream()
                .collect(java.util.stream.Collectors.toMap(RedisConnectionConfig::name, value -> value));
        if (byName.size() != connections.size()) {
            throw new IllegalArgumentException("Redis connection names must be unique");
        }
    }
}
