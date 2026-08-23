// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi;

import java.util.Objects;

/** Immutable provider-neutral identity for a logical cache region. */
public record CacheRegion(String namespace, String name, int formatVersion) {
    public CacheRegion {
        namespace = requireSegment(namespace, "namespace");
        name = requireSegment(name, "name");
        if (formatVersion < 1) {
            throw new IllegalArgumentException("formatVersion must be positive");
        }
    }

    /** Returns the stable region prefix used by provider implementations. */
    public String canonicalPrefix() {
        return namespace + ":v" + formatVersion + ":" + name;
    }

    private static String requireSegment(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || !value.matches("[A-Za-z0-9._~-]+")) {
            throw new IllegalArgumentException(field + " must contain only ASCII cache segment characters");
        }
        return value;
    }
}
