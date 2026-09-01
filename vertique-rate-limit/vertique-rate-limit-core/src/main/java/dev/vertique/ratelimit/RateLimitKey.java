// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import java.util.Objects;

/**
 * Bounded, canonical key construction from scalar components.
 *
 * <p><strong>Deliberately minimal.</strong> This is this task's own construction-only shape — it
 * does not yet implement {@code contracts/rate-limit-runtime.md}'s "Key model" scalar allowlist,
 * {@code global()}, the 256-byte component bound, or the type-framed percent-encoded canonical
 * form. A later task owns that full encoding discipline; this task's {@link
 * RateLimiters}/{@link RateLimiter}/{@link KeyedRateLimiter} only need distinct component tuples
 * to produce distinct canonical keys, which this minimal encoding already guarantees.
 */
public final class RateLimitKey {

    private final String canonicalEncoding;

    private RateLimitKey(String canonicalEncoding) {
        this.canonicalEncoding = canonicalEncoding;
    }

    /**
     * Builds a key from one or more scalar components. Component order is significant.
     *
     * @param first first, required component
     * @param rest additional components, if any
     * @return a key whose canonical encoding is distinct for distinct component tuples
     */
    public static RateLimitKey of(Object first, Object... rest) {
        Objects.requireNonNull(first, "first");
        StringBuilder encoding = new StringBuilder();
        appendComponent(encoding, first);
        if (rest != null) {
            for (Object component : rest) {
                encoding.append(':');
                appendComponent(encoding, component);
            }
        }
        return new RateLimitKey(encoding.toString());
    }

    /**
     * @return the canonical encoding used as the LOCAL storage identity's key component; engine-
     *     private, not part of this task's public contract
     */
    String canonicalEncoding() {
        return canonicalEncoding;
    }

    private static void appendComponent(StringBuilder encoding, Object component) {
        Objects.requireNonNull(component, "component");
        encoding.append(component.getClass().getSimpleName()).append('=').append(component);
    }
}
