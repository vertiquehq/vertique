// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.vertique.mcp.server.McpServerConfig;
import dev.vertique.mcp.tool.McpToolDescriptor;
import java.net.URL;
import java.security.CodeSource;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Guards the role-based package layout of both open-core MCP modules (T006, TP-003).
 *
 * <p>MCP packages name a role, never a visibility tier, so no package may carry an {@code internal}
 * or {@code impl} segment. The recorded-package half of this rule lives in each module's own
 * inventory guard, where that module's local resource is actually on the test classpath.
 */
class McpPackageArchitectureTest {

    private static final Set<String> FORBIDDEN_SEGMENTS = Set.of("internal", "impl", "internals", "implementation");

    @Test
    void shouldUseRoleBasedPackagesWithoutVisibilitySegments() {
        JavaClasses mcpProductionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importUrls(List.of(
                        compiledLocationOf(McpToolDescriptor.class), compiledLocationOf(McpServerConfig.class)));

        Set<String> declaredPackages = mcpProductionClasses.stream()
                .map(JavaClass::getPackageName)
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> packagesWithVisibilitySegment = declaredPackages.stream()
                .filter(McpPackageArchitectureTest::hasVisibilitySegment)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(declaredPackages).as("no MCP production package was scanned").isNotEmpty();
        assertThat(packagesWithVisibilitySegment)
                .as(
                        "MCP packages naming a visibility tier instead of a role; forbidden segments are %s",
                        FORBIDDEN_SEGMENTS)
                .isEmpty();
    }

    private static boolean hasVisibilitySegment(String packageName) {
        for (String segment : packageName.toLowerCase(Locale.ROOT).split("\\.")) {
            if (FORBIDDEN_SEGMENTS.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private static URL compiledLocationOf(Class<?> type) {
        CodeSource codeSource = type.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            throw new AssertionError("No compiled location available for " + type.getName());
        }
        return codeSource.getLocation();
    }
}
