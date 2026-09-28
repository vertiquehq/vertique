// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;

/**
 * A mutual TLS security scheme description (OpenAPI {@code type: mutualTLS}). Immutable; built
 * through {@link #of()}.
 */
public final class MutualTls implements SecuritySchemeDescription {

    @Nullable
    private final String description;

    private MutualTls(@Nullable String description) {
        this.description = description;
    }

    /**
     * Describes a mutual TLS scheme.
     *
     * @return a new {@link MutualTls} description
     */
    public static MutualTls of() {
        return new MutualTls(null);
    }

    /**
     * Returns a copy of this description with the given human-readable description.
     *
     * @param description the description text
     * @return a new {@link MutualTls} instance; this instance is unchanged
     * @throws NullPointerException     if {@code description} is {@code null}
     * @throws IllegalArgumentException if {@code description} is blank
     */
    public MutualTls withDescription(String description) {
        Objects.requireNonNull(description, "description must not be null");
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        return new MutualTls(description);
    }

    @Override
    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    // --- Object contract ---

    /**
     * Two {@code MutualTls} descriptions are equal when their descriptions are equal.
     *
     * @param obj the object to compare to
     * @return {@code true} if the objects describe the same scheme
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MutualTls other)) {
            return false;
        }
        return Objects.equals(description, other.description);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(description);
    }

    /**
     * Returns a string of this description's fields.
     *
     * @return the string representation
     */
    @Override
    public String toString() {
        return "MutualTls[description=" + description + "]";
    }
}
