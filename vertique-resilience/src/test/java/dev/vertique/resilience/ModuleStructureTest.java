// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the public resilience package layout and the absence of bypass/provider surfaces. */
class ModuleStructureTest {

    private static final String SOURCE_ROOT = "src/main/java";

    @Test
    @DisplayName("T011 public types remain in the canonical resilience packages")
    void publicTypesRemainInCanonicalPackages() {
        assertPackage("dev.vertique.resilience.Resilience", "dev.vertique.resilience");
        assertPackage("dev.vertique.resilience.ResiliencePipeline", "dev.vertique.resilience");
        assertPackage("dev.vertique.resilience.Timeout", "dev.vertique.resilience");
        assertPackage("dev.vertique.resilience.PolicyCallbackKind", "dev.vertique.resilience");
        assertPackage("dev.vertique.resilience.adapter.AdapterOperationIdentity", "dev.vertique.resilience.adapter");
        assertPackage("dev.vertique.resilience.exception.ResilienceException", "dev.vertique.resilience.exception");
        assertPackage(
                "dev.vertique.resilience.exception.ResilienceUnavailableException",
                "dev.vertique.resilience.exception");
        assertPackage(
                "dev.vertique.resilience.exception.ResilienceTimeoutException", "dev.vertique.resilience.exception");
        assertPackage(
                "dev.vertique.resilience.exception.ResilienceClosedException", "dev.vertique.resilience.exception");
        assertPackage(
                "dev.vertique.resilience.exception.ResiliencePolicyException", "dev.vertique.resilience.exception");
        assertPackage(
                "dev.vertique.resilience.exception.ResiliencePolicyFailureReason", "dev.vertique.resilience.exception");
        assertPackage("dev.vertique.resilience.exception.CircuitOpenException", "dev.vertique.resilience.exception");
        assertPackage(
                "dev.vertique.resilience.exception.BulkheadRejectedException", "dev.vertique.resilience.exception");
        assertPackage(
                "dev.vertique.resilience.exception.BulkheadQueueTimeoutException", "dev.vertique.resilience.exception");
        assertNoLoadableType("dev.vertique.core.resilience.Resilience");
    }

    @Test
    @DisplayName("resilience main sources contain no internal package")
    void mainSourcesContainNoInternalPackage() throws IOException {
        Path sourceRoot = Path.of(System.getProperty("user.dir"), SOURCE_ROOT);
        assertTrue(Files.isDirectory(sourceRoot), "Expected " + SOURCE_ROOT + " to exist");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                String normalized = sourceRoot
                        .relativize(path)
                        .toString()
                        .replace(path.getFileSystem().getSeparator(), "/");
                if (normalized.contains("/internal/") || normalized.endsWith("/internal")) {
                    violations.add("internal source path: " + normalized);
                }
                if (path.toString().endsWith(".java")) {
                    collectForbiddenDeclarations(path, violations);
                }
            });
        }

        if (!violations.isEmpty()) {
            fail("T011 must not publish an internal or generic provider/stage surface:\n  "
                    + String.join("\n  ", violations));
        }

        assertPackageResourceAbsent("dev.vertique.resilience.internal");
    }

    private static void collectForbiddenDeclarations(Path file, List<String> violations) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.startsWith("package ") && line.contains(".internal")) {
                violations.add(file + ":" + (i + 1) + ": internal package declaration");
            }
            if (line.matches("public (final )?(interface|class) (Stage|Provider|.*Stage|.*Provider) .*")
                    || line.matches("public (interface|class) (Stage|Provider|.*Stage|.*Provider)\\s*\\{.*")) {
                violations.add(file + ":" + (i + 1) + ": generic provider/stage declaration");
            }
        }
    }

    private static void assertPackage(String typeName, String expectedPackage) {
        Class<?> type = load(typeName);
        assertTrue(Modifier.isPublic(type.getModifiers()), typeName + " must be public");
        assertEquals(expectedPackage, type.getPackageName(), typeName + " package");
    }

    private static void assertNoLoadableType(String typeName) {
        try {
            Class.forName(typeName, false, ModuleStructureTest.class.getClassLoader());
            fail("legacy type must not be loadable: " + typeName);
        } catch (ClassNotFoundException expected) {
            // Expected: T001 removed the former runtime package.
        }
    }

    private static void assertPackageResourceAbsent(String packageName) throws IOException {
        String resourceName = packageName.replace('.', '/');
        Enumeration<URL> resources = ModuleStructureTest.class.getClassLoader().getResources(resourceName);
        assertFalse(resources.hasMoreElements(), "package must not exist: " + packageName);
    }

    private static Class<?> load(String typeName) {
        try {
            return Class.forName(typeName, false, ModuleStructureTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Required T011 type is absent: " + typeName, e);
        }
    }
}
