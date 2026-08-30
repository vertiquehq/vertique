// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi;

import java.lang.reflect.Type;
import java.util.Objects;

/** Exact value type and JSON profile selected by the shared cache runtime. */
public record CacheValueDescriptor(Type type, String jsonProfile) {
    public CacheValueDescriptor {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(jsonProfile, "jsonProfile");
        if (jsonProfile.isBlank()) {
            throw new IllegalArgumentException("jsonProfile must not be blank");
        }
    }
}
