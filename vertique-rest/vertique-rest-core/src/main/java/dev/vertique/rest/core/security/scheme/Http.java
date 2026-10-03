// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security.scheme;

import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;

/**
 * An HTTP authentication security scheme description (OpenAPI {@code type: http}), for example
 * {@code bearer} or {@code basic}. Immutable; built through {@link #bearer(String)} or
 * {@link #of(String)}.
 */
public final class Http implements SecuritySchemeDescription {

    private static final String BEARER = "bearer";

    private final String scheme;

    @Nullable
    private final String bearerFormat;

    @Nullable
    private final String description;

    private Http(String scheme, @Nullable String bearerFormat, @Nullable String description) {
        this.scheme = scheme;
        this.bearerFormat = bearerFormat;
        this.description = description;
    }

    /**
     * Describes an HTTP {@code bearer} scheme.
     *
     * @param bearerFormat the bearer format hint (for example {@code "JWT"}), or {@code null} for none
     * @return a new {@link Http} description whose {@link #scheme()} is {@code bearer}
     * @throws IllegalArgumentException if {@code bearerFormat} is blank
     */
    public static Http bearer(@Nullable String bearerFormat) {
        if (bearerFormat != null && bearerFormat.isBlank()) {
            throw new IllegalArgumentException("bearerFormat must not be blank");
        }
        return new Http(BEARER, bearerFormat, null);
    }

    /**
     * Describes an HTTP scheme by its name, for example {@code "basic"}. The description carries no
     * bearer format; use {@link #bearer(String)} to give one.
     *
     * @param scheme the HTTP authentication scheme name
     * @return a new {@link Http} description
     * @throws NullPointerException     if {@code scheme} is {@code null}
     * @throws IllegalArgumentException if {@code scheme} is blank
     */
    public static Http of(String scheme) {
        return new Http(requireNonBlank(scheme, "scheme"), null, null);
    }

    /**
     * Returns a copy of this description with the given human-readable description.
     *
     * @param description the description text
     * @return a new {@link Http} instance; this instance is unchanged
     * @throws NullPointerException     if {@code description} is {@code null}
     * @throws IllegalArgumentException if {@code description} is blank
     */
    public Http withDescription(String description) {
        return new Http(scheme, bearerFormat, requireNonBlank(description, "description"));
    }

    /**
     * The HTTP authentication scheme name.
     *
     * @return the scheme name
     */
    public String scheme() {
        return scheme;
    }

    /**
     * The bearer format hint.
     *
     * @return the bearer format, or {@link Optional#empty()}
     */
    public Optional<String> bearerFormat() {
        return Optional.ofNullable(bearerFormat);
    }

    @Override
    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    // --- Object contract ---

    /**
     * Two {@code Http} descriptions are equal when their scheme, bearer format, and description are
     * equal.
     *
     * @param obj the object to compare to
     * @return {@code true} if the objects describe the same scheme
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Http other)) {
            return false;
        }
        return scheme.equals(other.scheme)
                && Objects.equals(bearerFormat, other.bearerFormat)
                && Objects.equals(description, other.description);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(scheme, bearerFormat, description);
    }

    /**
     * Returns a string of this description's fields.
     *
     * @return the string representation
     */
    @Override
    public String toString() {
        return "Http[scheme=" + scheme + ", bearerFormat=" + bearerFormat + ", description=" + description + "]";
    }

    // --- Validation ---

    private static String requireNonBlank(String value, String argument) {
        Objects.requireNonNull(value, argument + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(argument + " must not be blank");
        }
        return value;
    }
}
