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
 * Mechanical completeness check for FR-023 (slice 6): after the {@code SecurityPolicyValidator} SPI
 * migrates off the Vert.x OpenAPI route/contract onto the neutral
 * {@code dev.vertique.rest.core.routing.RestOperationDescriptor}, the package
 * {@code io.vertx.openapi.contract} must no longer appear anywhere in the rest-core production
 * source tree.
 *
 * <p>The test walks {@code src/main/java} and asserts zero occurrences of the retired package. It is
 * a grep backstop, not a behavior test — it guards against a straggler reference re-introducing the
 * OpenAPI-contract coupling in rest-core.
 */
class SecurityCoreHasNoOpenApiContractImportTest {

    private static final String OPENAPI_CONTRACT_PACKAGE = "io.vertx.openapi.contract";

    @Test
    @DisplayName("rest-core production source has no io.vertx.openapi.contract reference")
    void restCoreHasNoOpenApiContractReference() throws IOException {
        Path srcMain = Path.of(System.getProperty("user.dir"), "src", "main", "java");
        assertTrue(Files.isDirectory(srcMain), "Expected src/main/java to exist at " + srcMain);

        try (Stream<Path> paths = Files.walk(srcMain)) {
            List<Path> offenders = paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(SecurityCoreHasNoOpenApiContractImportTest::referencesOpenApiContract)
                    .toList();
            assertTrue(
                    offenders.isEmpty(),
                    "io.vertx.openapi.contract must not appear in rest-core production source; found in: " + offenders);
        }
    }

    /**
     * Returns whether the given source file references the retired {@code io.vertx.openapi.contract}
     * package.
     *
     * @param file the source file to scan
     * @return {@code true} if the file contains the {@code io.vertx.openapi.contract} package name
     */
    private static boolean referencesOpenApiContract(Path file) {
        try {
            return Files.readString(file).contains(OPENAPI_CONTRACT_PACKAGE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + file, e);
        }
    }
}
