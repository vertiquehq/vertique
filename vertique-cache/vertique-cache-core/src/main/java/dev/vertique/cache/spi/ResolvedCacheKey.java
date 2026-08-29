// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi;

import java.util.Objects;

/** Runtime-resolved key supplied to a trusted cache-store provider. */
public record ResolvedCacheKey(CacheRegion region, String identityComponent, String selector) {
    public ResolvedCacheKey {
        Objects.requireNonNull(region, "region");
        requireCanonicalComponent(identityComponent, "identityComponent");
        requireCanonicalComponent(selector, "selector");
    }

    /** Returns the canonical provider key. */
    public String canonical() {
        return region.canonicalPrefix() + ":" + identityComponent + ":" + selector;
    }

    private static void requireCanonicalComponent(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || !value.matches("[A-Za-z0-9._~:/=%-]+")) {
            throw new IllegalArgumentException(field + " contains an invalid canonical key character");
        }
    }
}
