// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.security;

import jakarta.annotation.Nullable;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The OAuth Flows Object of an {@link OAuth2} description: the set of configured flows
 * ({@code implicit}, {@code password}, {@code clientCredentials}, {@code authorizationCode}), at
 * least one of them. Immutable; built only through {@link #builder()}.
 */
public final class OAuthFlows {

    @Nullable
    private final OAuthFlow implicit;

    @Nullable
    private final OAuthFlow password;

    @Nullable
    private final OAuthFlow clientCredentials;

    @Nullable
    private final OAuthFlow authorizationCode;

    private OAuthFlows(
            @Nullable OAuthFlow implicit,
            @Nullable OAuthFlow password,
            @Nullable OAuthFlow clientCredentials,
            @Nullable OAuthFlow authorizationCode) {
        this.implicit = implicit;
        this.password = password;
        this.clientCredentials = clientCredentials;
        this.authorizationCode = authorizationCode;
    }

    /**
     * Creates a new builder for {@link OAuthFlows}. At least one flow must be set before
     * {@link Builder#build()}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The {@code implicit} flow.
     *
     * @return the flow, or {@link Optional#empty()} when not set
     */
    public Optional<OAuthFlow> implicit() {
        return Optional.ofNullable(implicit);
    }

    /**
     * The {@code password} flow.
     *
     * @return the flow, or {@link Optional#empty()} when not set
     */
    public Optional<OAuthFlow> password() {
        return Optional.ofNullable(password);
    }

    /**
     * The {@code clientCredentials} flow.
     *
     * @return the flow, or {@link Optional#empty()} when not set
     */
    public Optional<OAuthFlow> clientCredentials() {
        return Optional.ofNullable(clientCredentials);
    }

    /**
     * The {@code authorizationCode} flow.
     *
     * @return the flow, or {@link Optional#empty()} when not set
     */
    public Optional<OAuthFlow> authorizationCode() {
        return Optional.ofNullable(authorizationCode);
    }

    // --- Object contract ---

    /**
     * Two {@code OAuthFlows} values are equal when each of their four flows is equal.
     *
     * @param obj the object to compare to
     * @return {@code true} if the objects configure the same flows
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof OAuthFlows other)) {
            return false;
        }
        return Objects.equals(implicit, other.implicit)
                && Objects.equals(password, other.password)
                && Objects.equals(clientCredentials, other.clientCredentials)
                && Objects.equals(authorizationCode, other.authorizationCode);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(implicit, password, clientCredentials, authorizationCode);
    }

    /**
     * Returns a string of this value's flows.
     *
     * @return the string representation
     */
    @Override
    public String toString() {
        return "OAuthFlows[implicit=" + implicit + ", password=" + password + ", clientCredentials=" + clientCredentials
                + ", authorizationCode=" + authorizationCode + "]";
    }

    /**
     * Builds an {@link OAuthFlows} value, one typed method per flow kind, so that each flow carries
     * exactly the URLs its type uses. Every method validates its arguments when called: a
     * {@code null} argument, or a {@code null} scope name or description, throws
     * {@link NullPointerException}; a relative URL or a blank scope name throws
     * {@link IllegalArgumentException}. Scope descriptions may be empty. Setting a flow again
     * replaces it. The builder is not thread-safe.
     */
    public static final class Builder {

        @Nullable
        private OAuthFlow implicit;

        @Nullable
        private OAuthFlow password;

        @Nullable
        private OAuthFlow clientCredentials;

        @Nullable
        private OAuthFlow authorizationCode;

        @Nullable
        private URI refreshUrl;

        private Builder() {}

        /**
         * Sets the {@code implicit} flow.
         *
         * @param authorizationUrl the absolute authorization URL
         * @param scopes           the available scopes and their descriptions; copied
         * @return this builder
         * @throws NullPointerException     if an argument, a scope name, or a scope description is
         *                                  {@code null}
         * @throws IllegalArgumentException if {@code authorizationUrl} is relative or a scope name is
         *                                  blank
         */
        public Builder implicit(URI authorizationUrl, Map<String, String> scopes) {
            implicit = OAuthFlow.create(requireAbsolute(authorizationUrl, "authorizationUrl"), null, scopes);
            return this;
        }

        /**
         * Sets the {@code password} flow.
         *
         * @param tokenUrl the absolute token URL
         * @param scopes   the available scopes and their descriptions; copied
         * @return this builder
         * @throws NullPointerException     if an argument, a scope name, or a scope description is
         *                                  {@code null}
         * @throws IllegalArgumentException if {@code tokenUrl} is relative or a scope name is blank
         */
        public Builder password(URI tokenUrl, Map<String, String> scopes) {
            password = OAuthFlow.create(null, requireAbsolute(tokenUrl, "tokenUrl"), scopes);
            return this;
        }

        /**
         * Sets the {@code clientCredentials} flow.
         *
         * @param tokenUrl the absolute token URL
         * @param scopes   the available scopes and their descriptions; copied
         * @return this builder
         * @throws NullPointerException     if an argument, a scope name, or a scope description is
         *                                  {@code null}
         * @throws IllegalArgumentException if {@code tokenUrl} is relative or a scope name is blank
         */
        public Builder clientCredentials(URI tokenUrl, Map<String, String> scopes) {
            clientCredentials = OAuthFlow.create(null, requireAbsolute(tokenUrl, "tokenUrl"), scopes);
            return this;
        }

        /**
         * Sets the {@code authorizationCode} flow.
         *
         * @param authorizationUrl the absolute authorization URL
         * @param tokenUrl         the absolute token URL
         * @param scopes           the available scopes and their descriptions; copied
         * @return this builder
         * @throws NullPointerException     if an argument, a scope name, or a scope description is
         *                                  {@code null}
         * @throws IllegalArgumentException if a URL is relative or a scope name is blank
         */
        public Builder authorizationCode(URI authorizationUrl, URI tokenUrl, Map<String, String> scopes) {
            authorizationCode = OAuthFlow.create(
                    requireAbsolute(authorizationUrl, "authorizationUrl"),
                    requireAbsolute(tokenUrl, "tokenUrl"),
                    scopes);
            return this;
        }

        /**
         * Sets the refresh URL, applied to every flow set on this builder, before or after this call.
         *
         * @param refreshUrl the absolute refresh URL
         * @return this builder
         * @throws NullPointerException     if {@code refreshUrl} is {@code null}
         * @throws IllegalArgumentException if {@code refreshUrl} is relative
         */
        public Builder refreshUrl(URI refreshUrl) {
            this.refreshUrl = requireAbsolute(refreshUrl, "refreshUrl");
            return this;
        }

        /**
         * Builds the {@link OAuthFlows} value from the flows set on this builder.
         *
         * @return the built value
         * @throws IllegalStateException if no flow was set
         */
        public OAuthFlows build() {
            if (implicit == null && password == null && clientCredentials == null && authorizationCode == null) {
                throw new IllegalStateException("OAuthFlows requires at least one flow");
            }
            return new OAuthFlows(
                    withRefreshUrl(implicit),
                    withRefreshUrl(password),
                    withRefreshUrl(clientCredentials),
                    withRefreshUrl(authorizationCode));
        }

        @Nullable
        private OAuthFlow withRefreshUrl(@Nullable OAuthFlow flow) {
            return flow == null || refreshUrl == null ? flow : flow.withRefreshUrl(refreshUrl);
        }

        private static URI requireAbsolute(URI url, String argument) {
            Objects.requireNonNull(url, argument + " must not be null");
            if (!url.isAbsolute()) {
                throw new IllegalArgumentException(argument + " must be an absolute URI");
            }
            return url;
        }
    }
}
