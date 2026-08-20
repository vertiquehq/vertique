// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import dev.vertique.input.processing.internal.SyntheticPrerequisiteImplementationType;
import dev.vertique.json.schema.SyntheticPrerequisitePackageRedeclaration;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.server.McpServerConfig;
import java.net.URL;
import java.security.CodeSource;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the ownership boundary between MCP and its prerequisite artifacts on real compiled bytecode: MCP declares none
 * of their packages and reaches none of their implementation packages.
 *
 * <p>Both prerequisites publish exactly one API package — {@code dev.vertique.input.processing} and
 * {@code dev.vertique.json.schema} — so any strict subpackage of those roots is implementation, not API.
 */
class McpPrerequisiteArchitectureTest {

    @Test
    void shouldOwnNoInputProcessingOrJsonSchemaPackages() {
        JavaClasses mcpProductionClasses = McpPrerequisiteArchitectureTestFixture.importCompiledMcpClasses();
        JavaClasses syntheticRedeclaration =
                McpPrerequisiteArchitectureTestFixture.importClasses(SyntheticPrerequisitePackageRedeclaration.class);
        JavaClasses syntheticImplementationDependency =
                McpPrerequisiteArchitectureTestFixture.importClasses(SyntheticImplementationPackageProbe.class);
        ArchRule packageOwnership = McpPrerequisiteArchitectureTestFixture.prerequisitePackageOwnershipRule();
        ArchRule implementationDependency =
                McpPrerequisiteArchitectureTestFixture.prerequisiteImplementationDependencyRule();

        assertThat(McpPrerequisiteArchitectureTestFixture.typeNames(mcpProductionClasses))
                .as("both MCP artifacts must be inside the scanned scope")
                .contains(
                        "dev.vertique.mcp.lifecycle.McpRequestObservation", "dev.vertique.mcp.server.McpServerConfig");
        assertThat(McpPrerequisiteArchitectureTestFixture.violations(packageOwnership, mcpProductionClasses))
                .isEmpty();
        assertThat(McpPrerequisiteArchitectureTestFixture.violations(implementationDependency, mcpProductionClasses))
                .isEmpty();
        assertThat(McpPrerequisiteArchitectureTestFixture.violations(packageOwnership, syntheticRedeclaration))
                .hasSize(1);
        assertThat(McpPrerequisiteArchitectureTestFixture.violations(
                        implementationDependency, syntheticImplementationDependency))
                .hasSize(1);
    }

    /**
     * The single synthetic forbidden dependency that proves the implementation-package rule is sensitive: the return
     * type reaches into an implementation package of the input-processing artifact. Replacing it with a neutral type
     * must drop that rule's violation count from 1 to 0.
     */
    public static final class SyntheticImplementationPackageProbe {
        public SyntheticPrerequisiteImplementationType implementationType() {
            return null;
        }
    }

    /** Framework wiring for the prerequisite scan: class import, rule construction, violation extraction. */
    private static final class McpPrerequisiteArchitectureTestFixture {
        private static final List<String> PREREQUISITE_API_PACKAGES =
                List.of("dev.vertique.input.processing", "dev.vertique.json.schema");

        private McpPrerequisiteArchitectureTestFixture() {}

        /** Imports every compiled production class of {@code vertique-mcp-core} and {@code vertique-mcp-server}. */
        static JavaClasses importCompiledMcpClasses() {
            return new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importUrls(List.of(
                            compiledLocationOf(McpRequestObservation.class),
                            compiledLocationOf(McpServerConfig.class)));
        }

        static JavaClasses importClasses(Class<?>... types) {
            return new ClassFileImporter().importClasses(types);
        }

        static ArchRule prerequisitePackageOwnershipRule() {
            return classes().should(new PrerequisitePackageOwnershipCondition());
        }

        static ArchRule prerequisiteImplementationDependencyRule() {
            return classes().should(new PrerequisiteImplementationDependencyCondition());
        }

        static List<String> violations(ArchRule rule, JavaClasses classes) {
            return rule.evaluate(classes).getFailureReport().getDetails();
        }

        static List<String> typeNames(JavaClasses classes) {
            return classes.stream().map(JavaClass::getName).toList();
        }

        private static URL compiledLocationOf(Class<?> type) {
            CodeSource codeSource = type.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                throw new AssertionError("No compiled location available for " + type.getName());
            }
            return codeSource.getLocation();
        }

        private static boolean isPrerequisiteOwnedPackage(String packageName) {
            return PREREQUISITE_API_PACKAGES.stream()
                    .anyMatch(apiPackage -> packageName.equals(apiPackage) || packageName.startsWith(apiPackage + "."));
        }

        private static boolean isPrerequisiteImplementationPackage(String packageName) {
            return PREREQUISITE_API_PACKAGES.stream().anyMatch(apiPackage -> packageName.startsWith(apiPackage + "."));
        }

        /** Reports one violation per class that declares a package owned by a prerequisite artifact. */
        private static final class PrerequisitePackageOwnershipCondition extends ArchCondition<JavaClass> {
            PrerequisitePackageOwnershipCondition() {
                super("declare no package owned by the input-processing or JSON-schema artifacts");
            }

            @Override
            public void check(JavaClass type, ConditionEvents events) {
                if (isPrerequisiteOwnedPackage(type.getPackageName())) {
                    events.add(SimpleConditionEvent.violated(
                            type, type.getName() + " declares prerequisite-owned package " + type.getPackageName()));
                }
            }
        }

        /** Reports one violation per class that depends on an implementation package of a prerequisite artifact. */
        private static final class PrerequisiteImplementationDependencyCondition extends ArchCondition<JavaClass> {
            PrerequisiteImplementationDependencyCondition() {
                super("depend on no implementation package of the input-processing or JSON-schema artifacts");
            }

            @Override
            public void check(JavaClass type, ConditionEvents events) {
                List<String> forbiddenDependencies = type.getDirectDependenciesFromSelf().stream()
                        .map(Dependency::getTargetClass)
                        .filter(target -> isPrerequisiteImplementationPackage(target.getPackageName()))
                        .map(JavaClass::getName)
                        .distinct()
                        .sorted()
                        .toList();
                if (!forbiddenDependencies.isEmpty()) {
                    events.add(SimpleConditionEvent.violated(
                            type,
                            type.getName() + " depends on prerequisite implementation types " + forbiddenDependencies));
                }
            }
        }
    }
}
