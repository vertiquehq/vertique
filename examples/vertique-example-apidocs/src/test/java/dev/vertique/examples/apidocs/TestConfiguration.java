// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.apidocs;

import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The configuration the tests start the example with: the shipped module-root
 * {@code config/application.json}, read from the filesystem the same way ConfigBootstrap does at
 * runtime, deep-merged with the test-only keys.
 *
 * <p>The test-only keys bind the HTTP server to {@code 127.0.0.1} on an ephemeral port and supply
 * {@link #JWT_KEY}, the key the tests mint their tokens with.
 *
 * <p>Surefire uses the module basedir as the working directory, so {@code config/application.json}
 * resolves to this example's relocated project-root config file.
 */
final class TestConfiguration {

    /** Filesystem path of the shipped configuration relative to the module basedir. */
    private static final Path SHIPPED_CONFIGURATION = Path.of("config/application.json");

    /** The test-only HS256 key, 40 characters; never part of the shipped configuration. */
    static final String JWT_KEY = "test-only-hs256-key-for-apidocs-example!";

    private TestConfiguration() {}

    /**
     * Returns the shipped configuration merged with the test-only keys.
     *
     * @return a new configuration object
     * @throws IllegalStateException when the file is missing
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
     * @return the file's text
     * @throws IllegalStateException when the file is missing
     */
    static String shippedText() {
        if (!Files.isRegularFile(SHIPPED_CONFIGURATION)) {
            throw new IllegalStateException("the shipped configuration "
                    + SHIPPED_CONFIGURATION.toAbsolutePath().normalize() + " is missing (expected at the module root)");
        }
        try {
            return Files.readString(SHIPPED_CONFIGURATION, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + SHIPPED_CONFIGURATION + " failed", e);
        }
    }
}
