// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import jakarta.annotation.Nullable;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One OAuth Flow Object of an {@link OAuthFlows} value, carrying exactly the URLs its flow type
 * uses: {@code implicit} an authorization URL, {@code password} and {@code clientCredentials} a
 * token URL, and {@code authorizationCode} both; the refresh URL is optional for every flow.
 * Immutable; built only through {@link OAuthFlows.Builder}.
 */
public final class OAuthFlow {

    @Nullable
    private final URI authorizationUrl;

    @Nullable
    private final URI tokenUrl;

    @Nullable
    private final URI refreshUrl;

    private final Map<String, String> scopes;

    private OAuthFlow(
            @Nullable URI authorizationUrl,
            @Nullable URI tokenUrl,
            @Nullable URI refreshUrl,
            Map<String, String> scopes) {
        this.authorizationUrl = authorizationUrl;
        this.tokenUrl = tokenUrl;
        this.refreshUrl = refreshUrl;
        this.scopes = scopes;
    }

    /**
     * Creates a flow without a refresh URL for {@link OAuthFlows.Builder}, which has already
     * validated the URLs. Validates the scopes and keeps an unmodifiable copy, so a later change to
     * the caller's map never reaches the flow.
     *
     * @param authorizationUrl the absolute authorization URL, or {@code null} when the flow type
     *                         uses none
     * @param tokenUrl         the absolute token URL, or {@code null} when the flow type uses none
     * @param scopes           the available scopes and their descriptions
     * @return a new flow
     * @throws NullPointerException     if {@code scopes} is {@code null} or holds a {@code null}
     *                                  scope name or description
     * @throws IllegalArgumentException if {@code scopes} holds a blank scope name
     */
    static OAuthFlow create(@Nullable URI authorizationUrl, @Nullable URI tokenUrl, Map<String, String> scopes) {
        return new OAuthFlow(authorizationUrl, tokenUrl, null, copyScopes(scopes));
    }

    /**
     * Returns a copy of this flow with the given refresh URL, which {@link OAuthFlows.Builder}
     * has already validated.
     *
     * @param refreshUrl the absolute refresh URL
     * @return a new flow; this flow is unchanged
     */
    OAuthFlow withRefreshUrl(URI refreshUrl) {
        return new OAuthFlow(authorizationUrl, tokenUrl, refreshUrl, scopes);
    }

    /**
     * The authorization URL, present only for {@code implicit} and {@code authorizationCode} flows.
     *
     * @return the authorization URL, or {@link Optional#empty()}
     */
    public Optional<URI> authorizationUrl() {
        return Optional.ofNullable(authorizationUrl);
    }

    /**
     * The token URL, present for every flow but {@code implicit}.
     *
     * @return the token URL, or {@link Optional#empty()}
     */
    public Optional<URI> tokenUrl() {
        return Optional.ofNullable(tokenUrl);
    }

    /**
     * The refresh URL, optional for every flow.
     *
     * @return the refresh URL, or {@link Optional#empty()}
     */
    public Optional<URI> refreshUrl() {
        return Optional.ofNullable(refreshUrl);
    }

    /**
     * The available scopes and their descriptions, in the order the caller's map iterated them. A
     * scope's description may be empty.
     *
     * @return an unmodifiable map from scope name to description; possibly empty
     */
    public Map<String, String> scopes() {
        return scopes;
    }

    // --- Object contract ---

    /**
     * Two flows are equal when their URLs and scopes are equal.
     *
     * @param obj the object to compare to
     * @return {@code true} if the objects describe the same flow
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof OAuthFlow other)) {
            return false;
        }
        return Objects.equals(authorizationUrl, other.authorizationUrl)
                && Objects.equals(tokenUrl, other.tokenUrl)
                && Objects.equals(refreshUrl, other.refreshUrl)
                && scopes.equals(other.scopes);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(authorizationUrl, tokenUrl, refreshUrl, scopes);
    }

    /**
     * Returns a string of this flow's fields.
     *
     * @return the string representation
     */
    @Override
    public String toString() {
        return "OAuthFlow[authorizationUrl=" + authorizationUrl + ", tokenUrl=" + tokenUrl + ", refreshUrl="
                + refreshUrl + ", scopes=" + scopes + "]";
    }

    // --- Validation ---

    /**
     * Copies {@code scopes} first and validates the copy, so a concurrent change to the caller's map
     * cannot slip an invalid entry past the check.
     */
    private static Map<String, String> copyScopes(Map<String, String> scopes) {
        Objects.requireNonNull(scopes, "scopes must not be null");
        Map<String, String> copy = new LinkedHashMap<>(scopes);
        for (Map.Entry<String, String> scope : copy.entrySet()) {
            if (scope.getKey() == null) {
                throw new NullPointerException("scopes must not hold a null scope name");
            }
            if (scope.getValue() == null) {
                throw new NullPointerException("scopes must not hold a null scope description");
            }
            if (scope.getKey().isBlank()) {
                throw new IllegalArgumentException("scopes must not hold a blank scope name");
            }
        }
        return Collections.unmodifiableMap(copy);
    }
}
