// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proves core's main sources do not import Jackson databind or Jackson core types. */
class CoreDependencyBoundaryTest {

    private static final List<String> FORBIDDEN =
            List.of("import com.fasterxml.jackson.databind.", "import com.fasterxml.jackson.core.");

    @Test
    @DisplayName("vertique-core main sources import no Jackson databind or core types")
    void mainSourcesImportNoJacksonDatabindOrCore() throws IOException {
        Path mainSources =
                Path.of(System.getProperty("basedir", "")).toAbsolutePath().resolve("src/main/java");
        assertTrue(Files.isDirectory(mainSources), "main sources not found at " + mainSources);

        List<String> offenders;
        try (Stream<Path> files = Files.walk(mainSources)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(CoreDependencyBoundaryTest::importsForbiddenType)
                    .map(p -> mainSources.relativize(p).toString())
                    .sorted()
                    .toList();
        }

        assertTrue(
                offenders.isEmpty(),
                "core main sources must not import Jackson databind/core; offenders: " + offenders);
    }

    private static boolean importsForbiddenType(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.map(String::strip).anyMatch(l -> FORBIDDEN.stream().anyMatch(l::startsWith));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }
}
