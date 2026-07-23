// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mechanical completeness check for FR-022 (slice 5): after the route-registration SPIs migrate off
 * the Vert.x OpenAPI {@code RouterBuilder}, the type
 * {@code io.vertx.ext.web.openapi.router.RouterBuilder} must no longer appear anywhere in the
 * rest-core production source tree. The neutral {@code RouterSetup} / {@code SecuritySchemeRegistry}
 * / {@code RouteRegistration} types now stand in its place.
 *
 * <p>The test walks {@code src/main/java} and asserts zero occurrences of the retired import. It is a
 * grep backstop, not a behavior test — it guards against a straggler reference re-introducing the
 * OpenAPI-router coupling in rest-core. Other {@code io.vertx.openapi.contract.*} references in
 * rest-core (e.g. in the security-policy validator) are migrated in a later slice and are out of
 * scope here.
 */
class RestCoreHasNoRouterBuilderImportTest {

    private static final String ROUTER_BUILDER_FQN = "io.vertx.ext.web.openapi.router.RouterBuilder";

    @Test
    @DisplayName("rest-core production source has no io.vertx.ext.web.openapi.router.RouterBuilder reference")
    void restCoreHasNoRouterBuilderReference() throws IOException {
        Path srcMain = Path.of(System.getProperty("user.dir"), "src", "main", "java");
        assertTrue(Files.isDirectory(srcMain), "Expected src/main/java to exist at " + srcMain);

        try (Stream<Path> paths = Files.walk(srcMain)) {
            List<Path> offenders = paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(RestCoreHasNoRouterBuilderImportTest::referencesRouterBuilder)
                    .toList();
            assertTrue(
                    offenders.isEmpty(),
                    "RouterBuilder must not appear in rest-core production source; found in: " + offenders);
        }
    }

    /**
     * Returns whether the given source file references the retired {@code RouterBuilder} type.
     *
     * @param file the source file to scan
     * @return {@code true} if the file contains the {@code RouterBuilder} fully-qualified name
     */
    private static boolean referencesRouterBuilder(Path file) {
        try {
            return Files.readString(file).contains(ROUTER_BUILDER_FQN);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + file, e);
        }
    }
}
