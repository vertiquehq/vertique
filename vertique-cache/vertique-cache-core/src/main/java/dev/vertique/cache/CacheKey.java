// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ordered logical key components produced by a cache selector function.
 *
 * <p>A {@code CacheKey} carries logical selector values only. The runtime alone owns
 * canonical encoding, framing, identity, and region material; no provider SPI or
 * {@link Cache}/{@link CacheBuilder} method accepts a {@code CacheKey} as a resolved
 * storage key.
 */
public final class CacheKey {
    private final List<Object> components;

    private CacheKey(List<Object> components) {
        this.components = components;
    }

    /** Creates an ordered component tuple; every component must be non-null. */
    public static CacheKey of(Object first, Object... rest) {
        Objects.requireNonNull(first, "cache key component");
        Objects.requireNonNull(rest, "components");
        List<Object> all = new ArrayList<>(1 + rest.length);
        all.add(first);
        for (Object component : rest) {
            all.add(Objects.requireNonNull(component, "cache key component"));
        }
        return new CacheKey(List.copyOf(all));
    }

    List<Object> components() {
        return components;
    }
}
