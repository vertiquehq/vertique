// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mechanical completeness check for FR-022 (slice 5): after the JWT bearer security-scheme handler
 * migrates off the Vert.x OpenAPI {@code RouterBuilder}, no reference to the
 * {@code io.vertx.ext.web.openapi.router} package may remain in the rest-auth-jwt production source
 * tree (neither {@code RouterBuilder} nor {@code Security}). The handler now registers its
 * authentication handler through the neutral {@code SecuritySchemeRegistry}.
 *
 * <p>The test walks {@code src/main/java} and asserts zero occurrences of the retired package prefix.
 * It is a grep backstop, not a behavior test — it guards against a straggler reference
 * re-introducing the OpenAPI-router coupling.
 */
class RestAuthJwtHasNoOpenApiRouterImportTest {

    private static final String OPENAPI_ROUTER_PACKAGE = "io.vertx.ext.web.openapi.router";

    @Test
    @DisplayName("rest-auth-jwt production source has no io.vertx.ext.web.openapi.router reference")
    void restAuthJwtHasNoOpenApiRouterReference() throws IOException {
        Path srcMain = Path.of(System.getProperty("user.dir"), "src", "main", "java");
        assertTrue(Files.isDirectory(srcMain), "Expected src/main/java to exist at " + srcMain);

        try (Stream<Path> paths = Files.walk(srcMain)) {
            List<Path> offenders = paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(RestAuthJwtHasNoOpenApiRouterImportTest::referencesOpenApiRouter)
                    .toList();
            assertTrue(
                    offenders.isEmpty(),
                    "io.vertx.ext.web.openapi.router must not appear in rest-auth-jwt production source; found in: "
                            + offenders);
        }
    }

    /**
     * Returns whether the given source file references the retired OpenAPI-router package.
     *
     * @param file the source file to scan
     * @return {@code true} if the file contains the {@code io.vertx.ext.web.openapi.router} prefix
     */
    private static boolean referencesOpenApiRouter(Path file) {
        try {
            return Files.readString(file).contains(OPENAPI_ROUTER_PACKAGE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + file, e);
        }
    }
}
