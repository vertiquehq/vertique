// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;

/**
 * An OAuth 2 security scheme description (OpenAPI {@code type: oauth2}). Immutable; built through
 * {@link #of(OAuthFlows)}.
 */
public final class OAuth2 implements SecuritySchemeDescription {

    private final OAuthFlows flows;

    @Nullable
    private final String description;

    private OAuth2(OAuthFlows flows, @Nullable String description) {
        this.flows = flows;
        this.description = description;
    }

    /**
     * Describes an OAuth 2 scheme with the given flows.
     *
     * @param flows the configured OAuth flows, built through {@link OAuthFlows#builder()}
     * @return a new {@link OAuth2} description
     * @throws NullPointerException if {@code flows} is {@code null}
     */
    public static OAuth2 of(OAuthFlows flows) {
        return new OAuth2(Objects.requireNonNull(flows, "flows must not be null"), null);
    }

    /**
     * Returns a copy of this description with the given human-readable description.
     *
     * @param description the description text
     * @return a new {@link OAuth2} instance; this instance is unchanged
     * @throws NullPointerException     if {@code description} is {@code null}
     * @throws IllegalArgumentException if {@code description} is blank
     */
    public OAuth2 withDescription(String description) {
        Objects.requireNonNull(description, "description must not be null");
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        return new OAuth2(flows, description);
    }

    /**
     * The configured OAuth flows.
     *
     * @return the flows
     */
    public OAuthFlows flows() {
        return flows;
    }

    @Override
    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    // --- Object contract ---

    /**
     * Two {@code OAuth2} descriptions are equal when their flows and description are equal.
     *
     * @param obj the object to compare to
     * @return {@code true} if the objects describe the same scheme
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof OAuth2 other)) {
            return false;
        }
        return flows.equals(other.flows) && Objects.equals(description, other.description);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(flows, description);
    }

    /**
     * Returns a string of this description's fields.
     *
     * @return the string representation
     */
    @Override
    public String toString() {
        return "OAuth2[flows=" + flows + ", description=" + description + "]";
    }
}
