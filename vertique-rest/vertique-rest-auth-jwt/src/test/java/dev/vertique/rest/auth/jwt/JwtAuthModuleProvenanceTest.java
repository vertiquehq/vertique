// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.security.CredentialRejectionReporter;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authentication.Credentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the startup provenance check in {@link JwtAuthModule}: a framework-built
 * {@link JWTAuth} attests the {@link JwtValidationConfig} it was constructed with, and the module
 * fails startup when that attestation diverges from the effective {@link JwtAuthConfig}.
 *
 * <p>The check is exercised through {@link JwtAuthModule#jwtBearerSchemeHandler} rather than by
 * calling the comparison directly, so the tests prove the check is actually <em>wired</em> into the
 * binding an application resolves at startup, not merely that it exists.
 *
 * <p>Three outcomes are pinned:
 * <ul>
 *   <li>attested and divergent — {@link ConfigurationException} naming both values and the config
 *       path, so a misconfiguration cannot reach the first request;</li>
 *   <li>attested and matching — returns normally;</li>
 *   <li>not attested (a hand-rolled {@link JWTAuth}) — returns normally and silently; a provider the
 *       framework did not build is the application's own business.</li>
 * </ul>
 *
 * <p>{@link #shouldAcceptDistinctButEquivalentDefaultConfigInstances()} guards the comparison's
 * shape: {@link JwtValidationConfig} declares no {@code equals}, so it inherits identity semantics,
 * and the two instances being compared are <em>always</em> distinct objects on the all-defaults
 * path. A comparison written with {@code .equals()} would therefore fail startup on essentially
 * every default deployment; that test fails loudly if anyone reintroduces one.
 */
class JwtAuthModuleProvenanceTest {

    private static final int DEFAULT_SKEW_SECONDS = 30;
    private static final int DIVERGENT_SKEW_SECONDS = 90;

    @Test
    @DisplayName("Divergent applied clock skew fails startup, naming both values and the config path")
    void shouldFailStartupWhenAppliedLeewayDivergesFromConfig() {
        JWTAuth attested = new AttestedJwtAuth(
                new HandRolledJwtAuth(),
                JwtValidationConfig.builder()
                        .clockSkewSeconds(DEFAULT_SKEW_SECONDS)
                        .build());
        JwtAuthConfig effective = configWithSkew(DIVERGENT_SKEW_SECONDS);

        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> JwtAuthModule.jwtBearerSchemeHandler(
                        attested, effective, mock(CredentialRejectionReporter.class)));

        String message = failure.getMessage();
        assertTrue(
                message.contains(String.valueOf(DEFAULT_SKEW_SECONDS)),
                () -> "message must name the applied skew, was: " + message);
        assertTrue(
                message.contains(String.valueOf(DIVERGENT_SKEW_SECONDS)),
                () -> "message must name the configured skew, was: " + message);
        assertTrue(
                message.contains("jwt.validation.clockSkewSeconds"),
                () -> "message must name the config path, was: " + message);
    }

    @Test
    @DisplayName("Matching applied clock skew is accepted")
    void shouldAcceptMatchingAppliedValidation() {
        JWTAuth attested = new AttestedJwtAuth(
                new HandRolledJwtAuth(),
                JwtValidationConfig.builder()
                        .clockSkewSeconds(DEFAULT_SKEW_SECONDS)
                        .build());

        assertNotNull(JwtAuthModule.jwtBearerSchemeHandler(
                attested, configWithSkew(DEFAULT_SKEW_SECONDS), mock(CredentialRejectionReporter.class)));
    }

    @Test
    @DisplayName("A hand-rolled JWTAuth carries no attestation and is accepted silently")
    void shouldAcceptUnattestedJwtAuthSilently() {
        // Diverges wildly from the effective config — but an unattested provider was not built by
        // the framework, so the framework has nothing to compare and must stay out of the way.
        assertNotNull(JwtAuthModule.jwtBearerSchemeHandler(
                new HandRolledJwtAuth(),
                configWithSkew(DIVERGENT_SKEW_SECONDS),
                mock(CredentialRejectionReporter.class)));
    }

    @Test
    @DisplayName("Two distinct all-defaults config instances agree — the check compares fields, not identity")
    void shouldAcceptDistinctButEquivalentDefaultConfigInstances() {
        // Exactly the shape of the commonest real deployment: JwtAuthFactory builds a fresh default
        // config for the JWTAuth, and JwtAuthConfig's @JsonCreator independently builds another.
        JwtValidationConfig applied = JwtValidationConfig.builder().build();
        JwtValidationConfig configured = JwtValidationConfig.builder().build();
        assertNotSame(applied, configured, "the guarded scenario requires two distinct instances");

        assertNotNull(JwtAuthModule.jwtBearerSchemeHandler(
                new AttestedJwtAuth(new HandRolledJwtAuth(), applied),
                new JwtAuthConfig("bearerAuth", configured),
                mock(CredentialRejectionReporter.class)));
    }

    // --- Helpers ---

    private static JwtAuthConfig configWithSkew(int clockSkewSeconds) {
        return new JwtAuthConfig(
                "bearerAuth",
                JwtValidationConfig.builder().clockSkewSeconds(clockSkewSeconds).build());
    }

    /**
     * A {@link JWTAuth} the framework did not build: it implements the interface and nothing else,
     * so it carries no validation attestation.
     */
    private static final class HandRolledJwtAuth implements JWTAuth {

        @Override
        public Future<User> authenticate(Credentials credentials) {
            return Future.failedFuture(new UnsupportedOperationException("not used in this test"));
        }

        @Override
        public String generateToken(JsonObject claims, JWTOptions options) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public String generateToken(JsonObject claims) {
            throw new UnsupportedOperationException("not used in this test");
        }
    }
}
