// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.config;

/**
 * Typed configuration of a by-reference object cache: its entry bound and its optional expiry. The record carries
 * no defaults; the owner of a cache supplies the values it wants for members the application leaves unset.
 *
 * @param maximumEntries the most entries the cache keeps; at least 1
 * @param ttlSeconds how long an entry lives after it was created, in seconds; {@code 0} for no time expiration
 */
public record ObjectCacheConfig(int maximumEntries, long ttlSeconds) {

    public ObjectCacheConfig {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be at least 1");
        }
        if (ttlSeconds < 0) {
            throw new IllegalArgumentException("ttlSeconds must not be negative");
        }
    }
}
