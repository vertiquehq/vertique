// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.archetype.restpostgresql;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Post-generation reproducibility proof for the PostgreSQL-backed REST archetype.
 *
 * <p>Bound to {@code failsafe:integration-test} at the {@code post-integration-test} phase (see the
 * module POM), which runs strictly after {@code archetype:integration-test} has generated every
 * case under {@code src/test/resources/projects/**}, including the no-build {@code
 * reproducibility-a} and {@code reproducibility-b} cases. Both cases share byte-identical {@code
 * archetype.properties} and an empty {@code goal.txt} (generation only, no nested build), so two
 * independent generations of the same coordinates must produce byte-identical trees.
 *
 * <p>The class name deliberately does not end in {@code IT} so the module's Surefire and
 * inherited/default Failsafe executions do not select it; only the explicit {@code
 * post-integration-test} execution does.
 */
public class RestPostgresqlArchetypeReproducibilityCheck {

    // --- Generated project roots ---

    /** The artifact identifier shared by both no-build reproducibility fixtures. */
    private static final String ARTIFACT_ID = "rest-postgresql-app";

    private static final Path GENERATED_A = generatedProjectRoot("reproducibility-a");
    private static final Path GENERATED_B = generatedProjectRoot("reproducibility-b");

    // --- Tests ---

    @Test
    @DisplayName("two independently generated PostgreSQL REST projects with identical properties are byte-identical")
    void generatesIdenticalTrackedContent() throws IOException {
        // Given two independent, same-property PostgreSQL REST generations.
        assertTrue(Files.isDirectory(GENERATED_A), () -> "missing reproducibility root: " + GENERATED_A);
        assertTrue(Files.isDirectory(GENERATED_B), () -> "missing reproducibility root: " + GENERATED_B);

        // When their tracked regular files are collected by normalized relative path.
        Map<String, Path> filesA = trackedRegularFiles(GENERATED_A);
        Map<String, Path> filesB = trackedRegularFiles(GENERATED_B);

        // Then neither tree has a path the other is missing.
        Set<String> onlyInA = new TreeSet<>(filesA.keySet());
        onlyInA.removeAll(filesB.keySet());
        Set<String> onlyInB = new TreeSet<>(filesB.keySet());
        onlyInB.removeAll(filesA.keySet());
        assertTrue(onlyInA.isEmpty(), () -> "paths only present in reproducibility-a: " + onlyInA);
        assertTrue(onlyInB.isEmpty(), () -> "paths only present in reproducibility-b: " + onlyInB);

        // And every shared path is byte-identical.
        for (Map.Entry<String, Path> entry : filesA.entrySet()) {
            String relative = entry.getKey();
            byte[] contentA = readBytes(entry.getValue());
            byte[] contentB = readBytes(filesB.get(relative));
            assertArrayEquals(contentA, contentB, () -> "byte mismatch at " + relative);
        }
    }

    // --- Helpers ---

    /**
     * Resolves the generated project root for one archetype integration-test case.
     *
     * @param caseName the case directory name under {@code src/test/resources/projects}
     * @return the generated project root, relative to the module basedir
     */
    private static Path generatedProjectRoot(String caseName) {
        return Path.of("target", "test-classes", "projects", caseName, "project", ARTIFACT_ID);
    }

    /**
     * Walks a generated project tree and collects its regular files, excluding {@code target/**}.
     *
     * @param root the generated project root
     * @return the tracked regular files, keyed by normalized path relative to {@code root}
     * @throws IOException when the tree cannot be walked
     */
    private static Map<String, Path> trackedRegularFiles(Path root) throws IOException {
        Map<String, Path> tracked = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.forEach(path -> {
                if (Files.isSymbolicLink(path)) {
                    fail("symlink is not permitted beneath a generated project: " + path);
                }
                String relative = normalizedRelative(root, path);
                if (!isExcluded(relative) && Files.isRegularFile(path)) {
                    tracked.put(relative, path);
                }
            });
        }
        return tracked;
    }

    /**
     * Normalizes a path relative to a root using forward slashes, regardless of platform.
     *
     * @param root the root to relativize against
     * @param path the path to normalize
     * @return the normalized relative path
     */
    private static String normalizedRelative(Path root, Path path) {
        return root.relativize(path).toString().replace(File.separatorChar, '/');
    }

    /**
     * Determines whether a normalized relative path falls beneath the generated project's own
     * {@code target} build output, which is excluded from comparison.
     *
     * @param relative the normalized relative path
     * @return {@code true} when the path is {@code target} or falls beneath it
     */
    private static boolean isExcluded(String relative) {
        return relative.equals("target") || relative.startsWith("target/");
    }

    /**
     * Reads a file's full byte content.
     *
     * @param path the file to read
     * @return the file's bytes
     */
    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("unreadable file: " + path, e);
        }
    }
}
