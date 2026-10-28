// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.config;

import dev.vertique.cache.CacheMode;
import java.util.Objects;

/**
 * Typed per-cache configuration override.
 *
 * @param mode the provider mode the cache runs on
 * @param ttlSeconds {@code -1} to inherit, {@code 0} to disable time expiration, or a positive time to live
 * @param jsonProfile the per-cache JSON profile, or {@code null} to inherit {@code cache.jsonProfile}
 * @param maximumEntries the per-cache entry bound, or {@code null} to leave it to the cache's owner; at least 1 when
 *     present. Honored by caches that size themselves from their entry, such as the by-reference object caches; the
 *     {@code CacheStore} providers keep sizing from {@code cache.maximumEntries}
 */
public record CacheEntryConfig(CacheMode mode, long ttlSeconds, String jsonProfile, Integer maximumEntries) {

    /**
     * Creates an entry without a per-cache entry bound.
     *
     * @param mode the provider mode the cache runs on
     * @param ttlSeconds {@code -1}, {@code 0} or a positive time to live
     * @param jsonProfile the per-cache JSON profile, or {@code null}
     */
    public CacheEntryConfig(CacheMode mode, long ttlSeconds, String jsonProfile) {
        this(mode, ttlSeconds, jsonProfile, null);
    }

    public CacheEntryConfig {
        mode = Objects.requireNonNull(mode, "mode");
        if (ttlSeconds < -1) {
            throw new IllegalArgumentException("ttlSeconds must be -1, 0, or positive");
        }
        if (jsonProfile != null && jsonProfile.isBlank()) {
            throw new IllegalArgumentException("jsonProfile must not be blank");
        }
        if (maximumEntries != null && maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be at least 1");
        }
    }
}
