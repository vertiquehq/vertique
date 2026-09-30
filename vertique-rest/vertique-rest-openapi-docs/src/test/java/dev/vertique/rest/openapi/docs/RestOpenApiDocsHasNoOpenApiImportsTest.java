// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Backstop: the module's production source must not reference the tech-preview OpenAPI packages
 * {@code io.vertx.openapi.} or {@code io.vertx.ext.web.openapi.router.}, and its packaged resources
 * must be the module reference document only (no UI assets, no bundled specifications).
 */
class RestOpenApiDocsHasNoOpenApiImportsTest {

    private static final String OPENAPI_PACKAGE = "io.vertx.openapi.";
    private static final String OPENAPI_ROUTER_PACKAGE = "io.vertx.ext.web.openapi.router.";
    private static final String MODULE_DOC = "META-INF/vertique/module.md";

    @Test
    @DisplayName("production source has no preview OpenAPI reference and the only resource is module.md")
    void noPreviewRoutingOrUiAssets() throws IOException {
        // Given the module's production source and resource trees
        Path main = Path.of(System.getProperty("user.dir"), "src", "main");
        Path java = main.resolve("java");
        Path resources = main.resolve("resources");

        // When they are scanned
        List<Path> referencing = List.of();
        if (Files.isDirectory(java)) {
            try (Stream<Path> paths = Files.walk(java)) {
                referencing = paths.filter(p -> p.toString().endsWith(".java"))
                        .filter(RestOpenApiDocsHasNoOpenApiImportsTest::referencesPreviewOpenApi)
                        .toList();
            }
        }
        List<String> resourceFiles = Files.isDirectory(resources) ? relativeFiles(resources) : List.of();

        // Then no source references the preview packages, and no resource other than module.md exists
        assertTrue(
                referencing.isEmpty(),
                "io.vertx.openapi and io.vertx.ext.web.openapi.router must not appear in production source; found in: "
                        + referencing);
        List<String> unexpected =
                resourceFiles.stream().filter(f -> !f.equals(MODULE_DOC)).toList();
        assertEquals(List.of(), unexpected, "Only " + MODULE_DOC + " may be a resource; found: " + unexpected);
    }

    private static List<String> relativeFiles(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    private static boolean referencesPreviewOpenApi(Path file) {
        try {
            String contents = Files.readString(file);
            return contents.contains(OPENAPI_PACKAGE) || contents.contains(OPENAPI_ROUTER_PACKAGE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + file, e);
        }
    }
}
