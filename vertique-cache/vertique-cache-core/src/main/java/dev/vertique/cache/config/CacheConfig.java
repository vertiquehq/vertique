// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.config;

import dev.vertique.cache.CacheMode;
import java.util.Map;
import java.util.Objects;

/** Validated, typed configuration for provider-neutral cache runtime behavior. */
public record CacheConfig(
        boolean enabled,
        CacheMode defaultMode,
        long defaultTtlSeconds,
        long maxTtlSeconds,
        String jsonProfile,
        int maxKeyBytes,
        int maxValueBytes,
        int maximumEntries,
        long backendTimeoutMs,
        Map<String, CacheEntryConfig> caches) {

    public CacheConfig {
        defaultMode = Objects.requireNonNull(defaultMode, "defaultMode");
        jsonProfile = Objects.requireNonNull(jsonProfile, "jsonProfile");
        if (jsonProfile.isBlank()) {
            throw new IllegalArgumentException("jsonProfile must not be blank");
        }
        caches = Map.copyOf(Objects.requireNonNull(caches, "caches"));
        if (defaultTtlSeconds < 0) {
            throw new IllegalArgumentException("defaultTtlSeconds must not be negative");
        }
        if (maxTtlSeconds < defaultTtlSeconds || maxTtlSeconds <= 0) {
            throw new IllegalArgumentException("maxTtlSeconds must be positive and cover the default");
        }
        if (maxKeyBytes <= 0 || maxValueBytes <= 0 || maximumEntries <= 0) {
            throw new IllegalArgumentException("cache limits must be positive");
        }
        if (backendTimeoutMs < 1 || backendTimeoutMs > 10_000) {
            throw new IllegalArgumentException("backendTimeoutMs must be between 1 and 10000");
        }
        caches.forEach((name, entry) -> {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("cache names must not be blank");
            }
            Objects.requireNonNull(entry, "cache entry");
            if (entry.ttlSeconds() > maxTtlSeconds) {
                throw new IllegalArgumentException("cache TTL exceeds maxTtlSeconds: " + name);
            }
        });
    }

    /** Compatibility constructor retaining the pre-profile public shape. */
    public CacheConfig(
            boolean enabled,
            CacheMode defaultMode,
            long defaultTtlSeconds,
            long maxTtlSeconds,
            int maxKeyBytes,
            int maxValueBytes,
            int maximumEntries,
            long backendTimeoutMs,
            Map<String, CacheEntryConfig> caches) {
        this(
                enabled,
                defaultMode,
                defaultTtlSeconds,
                maxTtlSeconds,
                "vertx",
                maxKeyBytes,
                maxValueBytes,
                maximumEntries,
                backendTimeoutMs,
                caches);
    }

    /** Recommended defaults from the cache contract. */
    public static CacheConfig defaults() {
        return new CacheConfig(true, CacheMode.LOCAL, 60, 86_400, "vertx", 1_024, 1_048_576, 10_000, 100, Map.of());
    }
}
