// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.apidocs;

import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The configuration the tests start the example with: the shipped {@code config/application.json},
 * read from the classpath as the application reads it, deep-merged with the test-only keys.
 *
 * <p>The test-only keys bind the HTTP server to {@code 127.0.0.1} on an ephemeral port and supply
 * {@link #JWT_KEY}, the key the tests mint their tokens with.
 */
final class TestConfiguration {

    /** The classpath location of the shipped configuration. */
    private static final String SHIPPED_CONFIGURATION = "config/application.json";

    /** The test-only HS256 key, 40 characters; never part of the shipped configuration. */
    static final String JWT_KEY = "test-only-hs256-key-for-apidocs-example!";

    private TestConfiguration() {}

    /**
     * Returns the shipped configuration merged with the test-only keys.
     *
     * @return a new configuration object
     * @throws IllegalStateException when the resource is missing
     */
    static JsonObject forTest() {
        JsonObject testKeys = new JsonObject()
                .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                .put("jwt", new JsonObject().put("hs256Key", JWT_KEY));
        return new JsonObject(shippedText()).mergeIn(testKeys, true);
    }

    /**
     * Returns the text of the shipped configuration.
     *
     * @return the resource's text
     * @throws IllegalStateException when the resource is missing
     */
    static String shippedText() {
        try (InputStream in = TestConfiguration.class.getClassLoader().getResourceAsStream(SHIPPED_CONFIGURATION)) {
            if (in == null) {
                throw new IllegalStateException(
                        "the shipped configuration " + SHIPPED_CONFIGURATION + " is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + SHIPPED_CONFIGURATION + " failed", e);
        }
    }
}
