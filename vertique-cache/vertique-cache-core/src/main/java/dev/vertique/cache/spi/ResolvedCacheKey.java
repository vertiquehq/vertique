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
        // Iterative scan: a regex alternation under a quantifier recurses per
        // character in java.util.regex and overflows the stack on long keys.
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " contains an invalid canonical key character");
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '%') {
                if (i + 2 >= value.length() || !isUpperHex(value.charAt(i + 1)) || !isUpperHex(value.charAt(i + 2))) {
                    throw new IllegalArgumentException(field + " contains an invalid canonical key character");
                }
                i += 2;
            } else if (!isCanonicalChar(ch)) {
                throw new IllegalArgumentException(field + " contains an invalid canonical key character");
            }
        }
    }

    private static boolean isCanonicalChar(char ch) {
        return ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9' || "._~:/=-".indexOf(ch) >= 0;
    }

    private static boolean isUpperHex(char ch) {
        return ch >= '0' && ch <= '9' || ch >= 'A' && ch <= 'F';
    }
}
