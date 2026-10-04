// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen;

import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The shipped module-root {@code config/application.json}, read from the filesystem the same way
 * ConfigBootstrap does at runtime, deep-merged with the test-only keys: the HTTP server on
 * {@code 127.0.0.1} at an ephemeral port and the management server disabled.
 *
 * <p>Surefire uses the module basedir as the working directory, so {@code config/application.json}
 * resolves to this example's relocated project-root config file.
 */
final class ShippedTestConfiguration {

    /** Filesystem path of the shipped configuration relative to the module basedir. */
    private static final Path SHIPPED_CONFIGURATION = Path.of("config/application.json");

    private ShippedTestConfiguration() {}

    /**
     * Returns the shipped configuration merged with the test-only keys.
     *
     * @return a new configuration object
     * @throws IllegalStateException when the file is missing
     */
    static JsonObject forTest() {
        JsonObject testKeys = new JsonObject()
                .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                .put("management", new JsonObject().put("enabled", false));
        return shipped().mergeIn(testKeys, true);
    }

    private static JsonObject shipped() {
        if (!Files.isRegularFile(SHIPPED_CONFIGURATION)) {
            throw new IllegalStateException("the shipped configuration "
                    + SHIPPED_CONFIGURATION.toAbsolutePath().normalize() + " is missing (expected at the module root)");
        }
        try {
            return new JsonObject(Files.readString(SHIPPED_CONFIGURATION, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + SHIPPED_CONFIGURATION + " failed", e);
        }
    }
}
