// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.vertx.core.Vertx;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves that <em>every</em> {@link JwtAuthFactory} construction path returns a {@link JWTAuth} that
 * attests the {@link JwtValidationConfig} it was built with.
 *
 * <p>This is the proof {@link JwtAuthModuleProvenanceTest} cannot supply. That class verifies the
 * comparison in {@link JwtAuthModule} against hand-built {@link AttestedJwtAuth} instances, and
 * {@code JwtAuthFactoryTest} verifies that the wrapper forwards every {@link JWTAuth} method — but
 * neither observes a real factory return. Without the assertions below, deleting the wrapping in
 * {@code createFromJwksContent} or {@code createFromPubSecKey} leaves the suite green: the startup
 * check treats an unattested provider as "not the framework's business" and returns silently, which
 * is indistinguishable from a matching attestation on every passing test.
 *
 * <p>Each path is pinned twice over:
 * <ul>
 *   <li>the no-config overloads must attest the documented default clock skew — the whole point of
 *       routing them through {@code defaultValidation()} instead of letting Vert.x default the
 *       leeway to {@code 0};</li>
 *   <li>the config overloads must attest the <em>caller's own</em> instance, so a path that quietly
 *       substituted an equivalent-looking config could not pass.</li>
 * </ul>
 *
 * <p>{@link RefreshableJwtAuth} is included because it carries its attestation itself rather than
 * being wrapped: it must report the config it re-applies to every refreshed delegate.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class JwtAuthFactoryAttestationTest {

    /**
     * The clock skew {@link JwtValidationConfig} documents as its default. Written as a literal, not
     * derived from the type under test, so a silent change to the default fails this test.
     */
    private static final int DOCUMENTED_DEFAULT_SKEW_SECONDS = 30;

    private static final int CONFIGURED_SKEW_SECONDS = 90;

    private static final String CLASSPATH_JWKS = "classpath:test-jwks.json";
    private static final String HS256 = "HS256";
    private static final String RS256 = "RS256";
    private static final String SECRET = "super-secret-key-for-testing-minimum-256-bits-long!";
    private static final String RSA_PUBLIC_KEY_PEM = generateRsaPublicKeyPem();

    // --- fromJwks ---

    @Test
    @DisplayName("fromJwks(vertx, location) attests the documented default clock skew")
    void shouldAttestDefaultsFromJwks(Vertx vertx) {
        assertAttestsDefaults(JwtAuthFactory.fromJwks(vertx, CLASSPATH_JWKS));
    }

    @Test
    @DisplayName("fromJwks(vertx, location, config) attests the supplied config")
    void shouldAttestSuppliedConfigFromJwks(Vertx vertx) {
        JwtValidationConfig config = configuredValidation();

        assertAttestsExactly(JwtAuthFactory.fromJwks(vertx, CLASSPATH_JWKS, config), config);
    }

    // --- fromJwksAsync ---

    @Test
    @DisplayName("fromJwksAsync(vertx, location) attests the documented default clock skew")
    void shouldAttestDefaultsFromJwksAsync(Vertx vertx, VertxTestContext testContext) {
        JwtAuthFactory.fromJwksAsync(vertx, CLASSPATH_JWKS)
                .onComplete(testContext.succeeding(auth -> testContext.verify(() -> {
                    assertAttestsDefaults(auth);
                    testContext.completeNow();
                })));
    }

    @Test
    @DisplayName("fromJwksAsync(vertx, location, config) attests the supplied config")
    void shouldAttestSuppliedConfigFromJwksAsync(Vertx vertx, VertxTestContext testContext) {
        JwtValidationConfig config = configuredValidation();

        JwtAuthFactory.fromJwksAsync(vertx, CLASSPATH_JWKS, config)
                .onComplete(testContext.succeeding(auth -> testContext.verify(() -> {
                    assertAttestsExactly(auth, config);
                    testContext.completeNow();
                })));
    }

    // --- fromSymmetricKey ---

    @Test
    @DisplayName("fromSymmetricKey(vertx, algorithm, secret) attests the documented default clock skew")
    void shouldAttestDefaultsFromSymmetricKey(Vertx vertx) {
        assertAttestsDefaults(JwtAuthFactory.fromSymmetricKey(vertx, HS256, SECRET));
    }

    @Test
    @DisplayName("fromSymmetricKey(vertx, algorithm, secret, config) attests the supplied config")
    void shouldAttestSuppliedConfigFromSymmetricKey(Vertx vertx) {
        JwtValidationConfig config = configuredValidation();

        assertAttestsExactly(JwtAuthFactory.fromSymmetricKey(vertx, HS256, SECRET, config), config);
    }

    // --- fromPublicKey ---

    @Test
    @DisplayName("fromPublicKey(vertx, algorithm, pem) attests the documented default clock skew")
    void shouldAttestDefaultsFromPublicKey(Vertx vertx) {
        assertAttestsDefaults(JwtAuthFactory.fromPublicKey(vertx, RS256, RSA_PUBLIC_KEY_PEM));
    }

    @Test
    @DisplayName("fromPublicKey(vertx, algorithm, pem, config) attests the supplied config")
    void shouldAttestSuppliedConfigFromPublicKey(Vertx vertx) {
        JwtValidationConfig config = configuredValidation();

        assertAttestsExactly(JwtAuthFactory.fromPublicKey(vertx, RS256, RSA_PUBLIC_KEY_PEM, config), config);
    }

    // --- fromJwksRefreshing ---

    @Test
    @DisplayName("The refreshing provider reports the documented default clock skew as its own attestation")
    void shouldAttestDefaultsFromJwksRefreshing(Vertx vertx, VertxTestContext testContext) {
        JwtAuthFactory.fromJwksRefreshing(vertx, CLASSPATH_JWKS, Duration.ofMinutes(5))
                .onComplete(testContext.succeeding(refreshable -> testContext.verify(() -> {
                    refreshable.close();
                    assertAttestsDefaults(refreshable);
                    testContext.completeNow();
                })));
    }

    @Test
    @DisplayName("The refreshing provider reports the supplied config as its own attestation")
    void shouldAttestSuppliedConfigFromJwksRefreshing(Vertx vertx, VertxTestContext testContext) {
        JwtValidationConfig config = configuredValidation();

        JwtAuthFactory.fromJwksRefreshing(vertx, CLASSPATH_JWKS, Duration.ofMinutes(5), config)
                .onComplete(testContext.succeeding(refreshable -> testContext.verify(() -> {
                    refreshable.close();
                    assertAttestsExactly(refreshable, config);
                    testContext.completeNow();
                })));
    }

    // --- Helpers ---

    /**
     * Returns a validation config whose clock skew is distinguishable from the default, so an
     * assertion against it cannot pass by accident on a path that applied the defaults.
     *
     * @return a fresh config carrying {@link #CONFIGURED_SKEW_SECONDS}
     */
    private static JwtValidationConfig configuredValidation() {
        return JwtValidationConfig.builder()
                .clockSkewSeconds(CONFIGURED_SKEW_SECONDS)
                .build();
    }

    /**
     * Asserts that {@code auth} attests the framework defaults: the documented clock skew, and no
     * issuer or audience constraint (the no-config overloads never invent one).
     *
     * @param auth the provider a factory path returned
     */
    private static void assertAttestsDefaults(JWTAuth auth) {
        JwtValidationConfig applied = appliedValidationOf(auth);
        assertEquals(
                DOCUMENTED_DEFAULT_SKEW_SECONDS,
                applied.clockSkewSeconds(),
                "a no-config factory path must apply and attest the documented default clock skew");
        assertNull(applied.issuer(), "a no-config factory path must leave the issuer unconstrained");
        assertNull(applied.audience(), "a no-config factory path must leave the audience unconstrained");
    }

    /**
     * Asserts that {@code auth} attests exactly the caller's config instance — not merely an
     * equivalent one — so a path that substituted its own config cannot pass.
     *
     * @param auth     the provider a factory path returned
     * @param expected the config that was passed into that path
     */
    private static void assertAttestsExactly(JWTAuth auth, JwtValidationConfig expected) {
        assertSame(
                expected,
                appliedValidationOf(auth),
                "a factory path must attest the very JwtValidationConfig it was handed");
    }

    /**
     * Asserts that a factory return carries an attestation at all, and returns it.
     *
     * <p>This is the assertion the suite was missing: {@link JwtAuthModule}'s startup check is a
     * no-op for an unattested provider, so a factory that stopped attesting would break the fail-fast
     * without breaking any other test.
     *
     * @param auth the provider a factory path returned
     * @return the validation config it attests
     */
    private static JwtValidationConfig appliedValidationOf(JWTAuth auth) {
        ValidationAttested attested = assertInstanceOf(
                ValidationAttested.class,
                auth,
                "every JwtAuthFactory construction path must return an attested JWTAuth, otherwise "
                        + "JwtAuthModule's clock-skew fail-fast silently does nothing");
        return attested.appliedValidation();
    }

    /**
     * Generates a throwaway RSA public key in PEM form, so the {@code fromPublicKey} paths are
     * exercised without checking key material into the repository.
     *
     * @return a PEM-encoded RSA public key
     */
    private static String generateRsaPublicKeyPem() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            byte[] encoded = generator.generateKeyPair().getPublic().getEncoded();
            return "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded)
                    + "\n-----END PUBLIC KEY-----\n";
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to generate an RSA key pair for the test", e);
        }
    }
}
