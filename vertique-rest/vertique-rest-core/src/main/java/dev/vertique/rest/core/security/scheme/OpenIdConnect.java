// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security.scheme;

import jakarta.annotation.Nullable;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * An OpenID Connect security scheme description (OpenAPI {@code type: openIdConnect}). Immutable;
 * built through {@link #of(URI)}.
 */
public final class OpenIdConnect implements SecuritySchemeDescription {

    private final URI openIdConnectUrl;

    @Nullable
    private final String description;

    private OpenIdConnect(URI openIdConnectUrl, @Nullable String description) {
        this.openIdConnectUrl = openIdConnectUrl;
        this.description = description;
    }

    /**
     * Describes an OpenID Connect scheme discoverable at the given URL.
     *
     * @param openIdConnectUrl the absolute OpenID Connect discovery URL
     * @return a new {@link OpenIdConnect} description
     * @throws NullPointerException     if {@code openIdConnectUrl} is {@code null}
     * @throws IllegalArgumentException if {@code openIdConnectUrl} is relative
     */
    public static OpenIdConnect of(URI openIdConnectUrl) {
        Objects.requireNonNull(openIdConnectUrl, "openIdConnectUrl must not be null");
        if (!openIdConnectUrl.isAbsolute()) {
            throw new IllegalArgumentException("openIdConnectUrl must be an absolute URI");
        }
        return new OpenIdConnect(openIdConnectUrl, null);
    }

    /**
     * Returns a copy of this description with the given human-readable description.
     *
     * @param description the description text
     * @return a new {@link OpenIdConnect} instance; this instance is unchanged
     * @throws NullPointerException     if {@code description} is {@code null}
     * @throws IllegalArgumentException if {@code description} is blank
     */
    public OpenIdConnect withDescription(String description) {
        Objects.requireNonNull(description, "description must not be null");
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        return new OpenIdConnect(openIdConnectUrl, description);
    }

    /**
     * The OpenID Connect discovery URL.
     *
     * @return the discovery URL
     */
    public URI openIdConnectUrl() {
        return openIdConnectUrl;
    }

    @Override
    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    // --- Object contract ---

    /**
     * Two {@code OpenIdConnect} descriptions are equal when their discovery URL and description are
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
        if (!(obj instanceof OpenIdConnect other)) {
            return false;
        }
        return openIdConnectUrl.equals(other.openIdConnectUrl) && Objects.equals(description, other.description);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(openIdConnectUrl, description);
    }

    /**
     * Returns a string of this description's fields.
     *
     * @return the string representation
     */
    @Override
    public String toString() {
        return "OpenIdConnect[openIdConnectUrl=" + openIdConnectUrl + ", description=" + description + "]";
    }
}
