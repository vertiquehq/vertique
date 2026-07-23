// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mechanical completeness check for FR-025 (slice 14): after the tech-preview OpenAPI-router
 * dependency is removed from the default path, no reference to the preview packages
 * {@code io.vertx.openapi.} or {@code io.vertx.ext.web.openapi.router.} may remain anywhere in the
 * rest-core production source tree — not in imports, code, or javadoc {@code {@code ...}} blocks.
 * The preview surface is now isolated entirely in the opt-in {@code vertique-rest-openapi-validation}
 * module.
 *
 * <p>This test also asserts the structural removal: {@code vertique-rest-core/pom.xml} must not
 * declare a direct dependency on {@code vertx-web-openapi-router}, which is what dropped the
 * transitive {@code vertx-openapi} artifact from the entire default path.
 *
 * <p>The grep is a backstop, not a behavior test — it guards against a straggler reference
 * re-introducing the OpenAPI coupling in rest-core.
 */
class RestCoreHasNoOpenApiImportsTest {

    private static final String OPENAPI_PACKAGE = "io.vertx.openapi.";
    private static final String OPENAPI_ROUTER_PACKAGE = "io.vertx.ext.web.openapi.router.";
    private static final String ROUTER_ARTIFACT = "vertx-web-openapi-router";

    @Test
    @DisplayName("rest-core production source has no io.vertx.openapi reference")
    void restCoreHasNoOpenApiReference() throws IOException {
        Path srcMain = Path.of(System.getProperty("user.dir"), "src", "main", "java");
        assertTrue(Files.isDirectory(srcMain), "Expected src/main/java to exist at " + srcMain);

        try (Stream<Path> paths = Files.walk(srcMain)) {
            List<Path> offenders = paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(RestCoreHasNoOpenApiImportsTest::referencesOpenApi)
                    .toList();
            assertTrue(
                    offenders.isEmpty(),
                    "io.vertx.openapi must not appear in rest-core production source; found in: " + offenders);
        }
    }

    @Test
    @DisplayName("rest-core pom.xml does not declare vertx-web-openapi-router dependency")
    void restCorePomHasNoVertxWebOpenapiRouterDependency() throws IOException {
        Path pom = Path.of(System.getProperty("user.dir"), "pom.xml");
        assertTrue(Files.isRegularFile(pom), "Expected pom.xml to exist at " + pom);

        String contents = Files.readString(pom);
        assertFalse(
                contents.contains(ROUTER_ARTIFACT),
                "vertx-web-openapi-router must not be declared in rest-core pom.xml");
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
