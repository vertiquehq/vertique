// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi;

import java.util.Objects;

/** Immutable provider-neutral cache key composed from a region, identity, and selector. */
public record CacheKey(CacheRegion region, String identityComponent, String selector) {
    public CacheKey {
        Objects.requireNonNull(region, "region");
        requireCanonicalComponent(identityComponent, "identityComponent");
        requireCanonicalComponent(selector, "selector");
    }

    /** Returns the single canonical string providers may use for storage. */
    public String canonical() {
        return canonical(region, identityComponent, selector);
    }

    private static String canonical(CacheRegion region, String identity, String selector) {
        return region.canonicalPrefix() + ":" + identity + ":" + selector;
    }

    private static void requireCanonicalComponent(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || !value.matches("[A-Za-z0-9._~:/=%-]+")) {
            throw new IllegalArgumentException(field + " contains an invalid canonical key character");
        }
    }
}
