// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen;

import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The shipped {@code config/application.json}, read from the classpath as the application reads it,
 * deep-merged with the test-only keys: the HTTP server on {@code 127.0.0.1} at an ephemeral port and
 * the management server disabled.
 */
final class ShippedTestConfiguration {

    /** The classpath location of the shipped configuration. */
    static final String SHIPPED_CONFIGURATION = "config/application.json";

    private ShippedTestConfiguration() {}

    /**
     * Returns the shipped configuration merged with the test-only keys.
     *
     * @return a new configuration object
     * @throws IllegalStateException when the resource is missing
     */
    static JsonObject forTest() {
        JsonObject testKeys = new JsonObject()
                .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                .put("management", new JsonObject().put("enabled", false));
        return shipped().mergeIn(testKeys, true);
    }

    private static JsonObject shipped() {
        try (InputStream in =
                ShippedTestConfiguration.class.getClassLoader().getResourceAsStream(SHIPPED_CONFIGURATION)) {
            if (in == null) {
                throw new IllegalStateException(
                        "the shipped configuration " + SHIPPED_CONFIGURATION + " is not on the classpath");
            }
            return new JsonObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + SHIPPED_CONFIGURATION + " failed", e);
        }
    }
}
