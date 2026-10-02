// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.apidocs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

/**
 * Calls the example's {@code JWTAuth} provider with missing, short, blank, and minimal keys, and
 * checks that the shipped configuration holds no key.
 *
 * <p>A rejected key must fail with a message that names the {@code jwt.hs256Key} setting and its
 * 32-character minimum and never echoes the key.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class JwtKeyConfigurationTest {

    /** Ends every generated key, so a message that echoes the key is detectable. */
    private static final String SENTINEL = "KEYZX";

    private static Vertx vertx;

    @BeforeAll
    static void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("the JWT key must be configured with at least 32 characters, and the shipped configuration holds none")
    void jwtKeyMustBeConfiguredWithAtLeast32Characters() throws Exception {
        // Given: one configuration per case
        JsonObject noJwtSection = new JsonObject();
        String shortKey = keyOfLength(31);
        JsonObject shortKeyConfig = withKey(shortKey);
        String blankKey = "   ";
        JsonObject blankKeyConfig = withKey(blankKey);
        String minimalKey = keyOfLength(32);
        JsonObject minimalKeyConfig = withKey(minimalKey);

        // When / Then: missing, short, and blank keys are refused without echoing the key
        assertAll(
                () -> assertRefused(noJwtSection, null),
                () -> assertRefused(shortKeyConfig, shortKey),
                () -> assertRefused(blankKeyConfig, null));

        // When: a token minted with a 32-character key is authenticated by the provider for that key
        JWTAuth provider = AppModule.jwtAuth(vertx, minimalKeyConfig);
        String token = JwtAuthFactory.fromSymmetricKey(vertx, "HS256", minimalKey)
                .generateToken(new JsonObject().put("sub", "ada"));
        User user = provider.authenticate(new TokenCredentials(token))
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);

        // Then: the token is accepted
        assertEquals("ada", user.principal().getString("sub"), "the minted token's subject");

        // Given: a flat top-level "jwt.hs256Key" entry and no jwt section
        String flatKey = keyOfLength(40);
        JsonObject flatKeyConfig = new JsonObject().put("jwt.hs256Key", flatKey);

        // When: a token minted with the flat key is authenticated by the provider for that configuration
        JWTAuth flatProvider = AppModule.jwtAuth(vertx, flatKeyConfig);
        String flatToken = JwtAuthFactory.fromSymmetricKey(vertx, "HS256", flatKey)
                .generateToken(new JsonObject().put("sub", "grace"));
        User flatUser = flatProvider
                .authenticate(new TokenCredentials(flatToken))
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);

        // Then: the token is accepted
        assertEquals("grace", flatUser.principal().getString("sub"), "the flat-key token's subject");

        // Given: a short flat top-level key beside a valid nested jwt.hs256Key
        String shortFlatKey = keyOfLength(31);
        JsonObject shortFlatBesideNested = withKey(minimalKey).put("jwt.hs256Key", shortFlatKey);

        // When / Then: the flat key takes precedence, so the configuration is refused without echoing it
        assertRefused(shortFlatBesideNested, shortFlatKey);

        // Then: the shipped configuration supplies no key
        assertFalse(
                TestConfiguration.shippedText().contains("hs256Key"),
                "the shipped configuration must not hold jwt.hs256Key");
    }

    /**
     * Asserts that the provider refuses the configuration with a message naming the setting and its
     * minimum, containing neither the sentinel nor the key.
     *
     * @param config the configuration
     * @param key    the configured key to look for in the message, or {@code null} to skip that check
     */
    private static void assertRefused(JsonObject config, String key) {
        Executable call = () -> AppModule.jwtAuth(vertx, config);
        Exception refusal = assertThrows(Exception.class, call, "the provider must refuse " + config.encode());
        String message = String.valueOf(refusal.getMessage());
        assertAll(
                () -> assertTrue(message.contains("jwt.hs256Key"), "the message must name jwt.hs256Key: " + message),
                () -> assertTrue(message.contains("32"), "the message must state the 32-character minimum: " + message),
                () -> assertFalse(message.contains(SENTINEL), "the message must not echo the key: " + message),
                () -> assertTrue(key == null || !message.contains(key), "the message must not contain the key"));
    }

    /**
     * Returns a configuration whose {@code jwt.hs256Key} is the given key.
     *
     * @param key the key
     * @return the configuration
     */
    private static JsonObject withKey(String key) {
        return new JsonObject().put("jwt", new JsonObject().put("hs256Key", key));
    }

    /**
     * Builds a key of exactly the given length that ends in {@link #SENTINEL}.
     *
     * @param length the key length, at least the sentinel's
     * @return the key
     */
    private static String keyOfLength(int length) {
        return "k".repeat(length - SENTINEL.length()) + SENTINEL;
    }
}
