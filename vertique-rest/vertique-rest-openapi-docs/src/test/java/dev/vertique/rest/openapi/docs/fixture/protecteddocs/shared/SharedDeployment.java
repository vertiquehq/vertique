// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared;

import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import dev.vertique.rest.auth.jwt.JwtClaimsValidator;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import java.util.Map;

/**
 * The configurations and bearer tokens of the shared deployment.
 *
 * <p>The JWT provider verifies HS256 tokens signed with {@link #SIGNING_KEY}, and the configuration
 * sets {@code jwt.validation.issuer} to {@value #ISSUER}, which the JWT handler enforces after
 * verification. The tokens:
 *
 * <ul>
 *   <li>{@code alice}: roles {@code [admin]};
 *   <li>{@code bob}: roles {@code [user]};
 *   <li>{@code carol}: roles {@code [admin]} and the claim {@value #TENANT_CLAIM}: {@value
 *       #BLOCKED_TENANT}, which the bound {@link BlockedTenantValidator} rejects;
 *   <li>a token of {@code alice}'s roles from the foreign issuer {@value #FOREIGN_ISSUER}.
 * </ul>
 */
public final class SharedDeployment {

    /** The HS256 signing secret of the JWT provider and of every minted token. */
    public static final String SIGNING_KEY = "protected-docs-test-secret-key-with-at-least-256-bits-for-hs256";

    /** The issuer the deployment accepts. */
    public static final String ISSUER = "https://issuer.docs.test";

    /** An issuer the deployment does not accept. */
    public static final String FOREIGN_ISSUER = "https://foreign.docs.test";

    /** The claim the claims validator reads. */
    public static final String TENANT_CLAIM = "tenant";

    /** The value of {@value #TENANT_CLAIM} the claims validator rejects. */
    public static final String BLOCKED_TENANT = "blocked";

    /** The permissive configured default {@code Cache-Control} of the deployment. */
    public static final String DEFAULT_CACHE_CONTROL = "public, max-age=3600";

    /** The configured {@code serverUrl} of the {@code management} document. */
    public static final String MANAGEMENT_SERVER_URL = "https://docs.example/api/mgmt";

    private SharedDeployment() {}

    /**
     * Returns the shared configuration: the server on {@code 127.0.0.1} port {@code 0}, the {@code
     * none} validation strategy, {@code jaxrs.defaultHeaders.cacheControl} {@value
     * #DEFAULT_CACHE_CONTROL}, {@code jwt.validation.issuer} {@value #ISSUER}, and {@code
     * apidocs.documents.management} with {@code enabled} {@code true} and {@code serverUrl} {@value
     * #MANAGEMENT_SERVER_URL}, the non-disabling configuration a document accepts.
     *
     * @return a fresh configuration
     */
    public static JsonObject configured() {
        JsonObject config = withoutApidocs();
        config.put(
                "apidocs",
                new JsonObject()
                        .put(
                                "documents",
                                new JsonObject()
                                        .put(
                                                GuardedManagementApi.NAME,
                                                new JsonObject()
                                                        .put("enabled", true)
                                                        .put("serverUrl", MANAGEMENT_SERVER_URL))));
        return config;
    }

    /**
     * Returns the shared configuration without any {@code apidocs} section.
     *
     * @return a fresh configuration
     */
    public static JsonObject withoutApidocs() {
        return new JsonObject()
                .put("http", new JsonObject().put("host", "127.0.0.1").put("port", 0))
                .put(
                        "jaxrs",
                        new JsonObject()
                                .put("validationStrategy", "none")
                                .put("defaultHeaders", new JsonObject().put("cacheControl", DEFAULT_CACHE_CONTROL)))
                .put("jwt", new JsonObject().put("validation", new JsonObject().put("issuer", ISSUER)));
    }

    /**
     * Builds the JWT provider the deployment verifies tokens with, and the test mints them with.
     *
     * @param vertx the Vert.x instance
     * @return the provider
     */
    public static JWTAuth jwtAuth(Vertx vertx) {
        return JwtAuthFactory.fromSymmetricKey(vertx, "HS256", SIGNING_KEY);
    }

    /**
     * Mints {@code alice}'s token: roles {@code [admin]}.
     *
     * @param provider the provider of {@link #jwtAuth(Vertx)}
     * @return the token
     */
    public static String alice(JWTAuth provider) {
        return mint(provider, "alice", ISSUER, "admin", Map.of());
    }

    /**
     * Mints {@code bob}'s token: roles {@code [user]}.
     *
     * @param provider the provider of {@link #jwtAuth(Vertx)}
     * @return the token
     */
    public static String bob(JWTAuth provider) {
        return mint(provider, "bob", ISSUER, "user", Map.of());
    }

    /**
     * Mints {@code carol}'s token: roles {@code [admin]} and the blocked tenant.
     *
     * @param provider the provider of {@link #jwtAuth(Vertx)}
     * @return the token
     */
    public static String carol(JWTAuth provider) {
        return mint(provider, "carol", ISSUER, "admin", Map.of(TENANT_CLAIM, BLOCKED_TENANT));
    }

    /**
     * Mints a token with roles {@code [admin]} from the foreign issuer.
     *
     * @param provider the provider of {@link #jwtAuth(Vertx)}
     * @return the token
     */
    public static String foreignIssuer(JWTAuth provider) {
        return mint(provider, "alice", FOREIGN_ISSUER, "admin", Map.of());
    }

    private static String mint(
            JWTAuth provider, String subject, String issuer, String role, Map<String, String> extra) {
        JsonObject claims =
                new JsonObject().put("sub", subject).put("iss", issuer).put("roles", new JsonArray().add(role));
        extra.forEach(claims::put);
        return provider.generateToken(claims);
    }

    /** Rejects every token whose {@value #TENANT_CLAIM} claim is {@value #BLOCKED_TENANT}. */
    public static final class BlockedTenantValidator implements JwtClaimsValidator {

        /** Creates the validator. */
        public BlockedTenantValidator() {}

        @Override
        public void validate(Map<String, Object> claims) {
            if (BLOCKED_TENANT.equals(claims.get(TENANT_CLAIM))) {
                throw new SecurityException("the tenant is blocked");
            }
        }
    }
}
