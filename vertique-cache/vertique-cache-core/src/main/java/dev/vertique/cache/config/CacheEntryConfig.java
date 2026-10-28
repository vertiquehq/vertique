// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.config;

import dev.vertique.cache.CacheMode;
import java.util.Objects;

/** Typed per-cache configuration override. */
public record CacheEntryConfig(CacheMode mode, long ttlSeconds, String jsonProfile) {
    public CacheEntryConfig {
        mode = Objects.requireNonNull(mode, "mode");
        if (ttlSeconds < -1) {
            throw new IllegalArgumentException("ttlSeconds must be -1, 0, or positive");
        }
        if (jsonProfile != null && jsonProfile.isBlank()) {
            throw new IllegalArgumentException("jsonProfile must not be blank");
        }
    }
}
