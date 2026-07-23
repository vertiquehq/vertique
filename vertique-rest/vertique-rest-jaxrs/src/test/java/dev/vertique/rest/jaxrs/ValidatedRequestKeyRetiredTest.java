// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mechanical completeness check for slice 4: after the generated-runtime SPI migrates off the Vert.x
 * OpenAPI router, the {@code KEY_META_DATA_VALIDATED_REQUEST} stash key must no longer appear
 * anywhere in the rest-jaxrs production source tree. The generated and reflective dispatch paths now
 * stash and read a {@link dev.vertique.rest.jaxrs.request.BoundRequest} instead.
 *
 * <p>The test walks {@code src/main/java} and asserts zero occurrences of the retired key. It is a
 * grep backstop, not a behavior test — it guards against a straggler reference re-introducing the
 * OpenAPI router coupling.
 */
class ValidatedRequestKeyRetiredTest {

    @Test
    @DisplayName("KEY_META_DATA_VALIDATED_REQUEST is retired from rest-jaxrs production source")
    void validatedRequestKeyIsRetired() throws IOException {
        Path srcMain = Path.of(System.getProperty("user.dir"), "src", "main", "java");
        assertTrue(Files.isDirectory(srcMain), "Expected src/main/java to exist at " + srcMain);

        try (Stream<Path> paths = Files.walk(srcMain)) {
            List<Path> offenders = paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(ValidatedRequestKeyRetiredTest::containsRetiredKey)
                    .toList();
            assertTrue(
                    offenders.isEmpty(),
                    "KEY_META_DATA_VALIDATED_REQUEST must not appear in production source; found in: " + offenders);
        }
    }

    /**
     * Returns whether the given source file references the retired
     * {@code KEY_META_DATA_VALIDATED_REQUEST} key.
     *
     * @param file the source file to scan
     * @return {@code true} if the file contains the retired key
     */
    private static boolean containsRetiredKey(Path file) {
        try {
            return Files.readString(file).contains("KEY_META_DATA_VALIDATED_REQUEST");
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + file, e);
        }
    }
}
