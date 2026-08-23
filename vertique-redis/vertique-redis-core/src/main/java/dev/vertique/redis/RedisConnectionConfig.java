// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/** Validated named Redis connection profile. */
public record RedisConnectionConfig(
        String name,
        List<String> endpoints,
        String username,
        String passwordSecret,
        boolean tlsEnabled,
        long connectTimeoutMs,
        int maxPoolSize,
        int maxPoolWaiting) {

    public RedisConnectionConfig {
        name = requireNonBlank(name, "name");
        endpoints = List.copyOf(Objects.requireNonNull(endpoints, "endpoints"));
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("endpoints must not be empty");
        }
        endpoints.forEach(RedisConnectionConfig::validateEndpoint);
        username = optional(username, "username");
        passwordSecret = optional(passwordSecret, "passwordSecret");
        if (connectTimeoutMs < 1 || connectTimeoutMs > 60_000) {
            throw new IllegalArgumentException("connectTimeoutMs must be between 1 and 60000");
        }
        if (maxPoolSize < 1 || maxPoolSize > 256) {
            throw new IllegalArgumentException("maxPoolSize must be between 1 and 256");
        }
        if (maxPoolWaiting < 0 || maxPoolWaiting > 10_000) {
            throw new IllegalArgumentException("maxPoolWaiting must be between 0 and 10000");
        }
    }

    private static void validateEndpoint(String endpoint) {
        try {
            URI uri = URI.create(requireNonBlank(endpoint, "endpoint"));
            if (!("redis".equalsIgnoreCase(uri.getScheme()) || "rediss".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || uri.getQuery() != null
                    || (uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65_535)) {
                throw new IllegalArgumentException("endpoint must be a credential-free redis URI");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid Redis endpoint", invalid);
        }
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String optional(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
