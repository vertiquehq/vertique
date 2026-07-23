// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mechanical completeness check for FR-025 (slice 14): no reference to the tech-preview OpenAPI
 * packages {@code io.vertx.openapi.} or {@code io.vertx.ext.web.openapi.router.} may remain in the
 * rest-security production source tree. The default path is preview-free; the preview surface is
 * isolated in the opt-in {@code vertique-rest-openapi-validation} module.
 *
 * <p>The grep is a backstop, not a behavior test — it guards against a straggler reference
 * re-introducing the OpenAPI coupling.
 */
class RestSecurityHasNoOpenApiImportsTest {

    private static final String OPENAPI_PACKAGE = "io.vertx.openapi.";
    private static final String OPENAPI_ROUTER_PACKAGE = "io.vertx.ext.web.openapi.router.";

    @Test
    @DisplayName("rest-security production source has no io.vertx.openapi reference")
    void restSecurityHasNoOpenApiReference() throws IOException {
        Path srcMain = Path.of(System.getProperty("user.dir"), "src", "main", "java");
        assertTrue(Files.isDirectory(srcMain), "Expected src/main/java to exist at " + srcMain);

        try (Stream<Path> paths = Files.walk(srcMain)) {
            List<Path> offenders = paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(RestSecurityHasNoOpenApiImportsTest::referencesOpenApi)
                    .toList();
            assertTrue(
                    offenders.isEmpty(),
                    "io.vertx.openapi must not appear in rest-security production source; found in: " + offenders);
        }
    }

    /**
     * Returns whether the given source file references the preview OpenAPI packages.
     *
     * @param file the source file to scan
     * @return {@code true} if the file contains either preview package prefix
     */
    private static boolean referencesOpenApi(Path file) {
        try {
            String contents = Files.readString(file);
            return contents.contains(OPENAPI_PACKAGE) || contents.contains(OPENAPI_ROUTER_PACKAGE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + file, e);
        }
    }
}
