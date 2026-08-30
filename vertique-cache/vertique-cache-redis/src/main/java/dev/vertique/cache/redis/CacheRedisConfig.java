// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import java.util.Objects;

/** Typed configuration for the Redis cache provider facade. */
public record CacheRedisConfig(String connection, String namespace, int formatVersion) {
    public CacheRedisConfig {
        connection = require(connection, "connection");
        namespace = require(namespace, "namespace");
        if (formatVersion < 1) {
            throw new IllegalArgumentException("formatVersion must be positive");
        }
    }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
