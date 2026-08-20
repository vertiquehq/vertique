// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.server.McpServerConfig;
import java.net.URL;
import java.security.CodeSource;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the neutral open-core MCP surface against enterprise audit vocabulary.
 *
 * <p>The scan reads the compiled production bytecode of both open-core MCP artifacts, so a newly
 * added public type is covered without touching this test.
 */
class McpOpenCoreArchitectureTest {

    @Test
    void shouldExposeOnlyNeutralLifecycleVocabulary() {
        JavaClasses openCoreProductionClasses = McpOpenCoreArchitectureTestFixture.importCompiledOpenCoreClasses();
        JavaClasses syntheticProbe =
                McpOpenCoreArchitectureTestFixture.importClasses(SyntheticForbiddenVocabularyProbe.class);
        ArchRule neutralVocabulary = McpOpenCoreArchitectureTestFixture.neutralVocabularyRule();

        List<String> productionViolations =
                McpOpenCoreArchitectureTestFixture.violations(neutralVocabulary, openCoreProductionClasses);
        List<String> syntheticViolations =
                McpOpenCoreArchitectureTestFixture.violations(neutralVocabulary, syntheticProbe);

        assertThat(McpOpenCoreArchitectureTestFixture.typeNames(openCoreProductionClasses))
                .as("both open-core MCP artifacts must be inside the scanned scope")
                .contains(
                        "dev.vertique.mcp.lifecycle.McpRequestObservation", "dev.vertique.mcp.server.McpServerConfig");
        assertThat(productionViolations).isEmpty();
        assertThat(syntheticViolations).hasSize(1);
        assertThat(syntheticViolations.getFirst()).contains("SyntheticForbiddenVocabularyProbe");
    }

    /**
     * The single synthetic forbidden signature that proves the rule is sensitive: {@code auditTrailId} carries the
     * forbidden vocabulary. Renaming it to a neutral name must drop the synthetic violation count from 1 to 0.
     */
    public static final class SyntheticForbiddenVocabularyProbe {
        public String auditTrailId() {
            return "synthetic";
        }
    }

    /** Framework wiring for the open-core vocabulary scan: class import, rule construction, violation extraction. */
    private static final class McpOpenCoreArchitectureTestFixture {
        private static final List<String> FORBIDDEN_VOCABULARY = List.of("audit", "enterprise");

        private McpOpenCoreArchitectureTestFixture() {}

        /** Imports every compiled production class of {@code vertique-mcp-core} and {@code vertique-mcp-server}. */
        static JavaClasses importCompiledOpenCoreClasses() {
            return new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importUrls(List.of(
                            compiledLocationOf(McpRequestObservation.class),
                            compiledLocationOf(McpServerConfig.class)));
        }

        static JavaClasses importClasses(Class<?>... types) {
            return new ClassFileImporter().importClasses(types);
        }

        static ArchRule neutralVocabularyRule() {
            return classes().should(new NeutralVocabularyCondition());
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

        /** Reports one violation per class whose name, public signature, or dependency carries audit vocabulary. */
        private static final class NeutralVocabularyCondition extends ArchCondition<JavaClass> {
            NeutralVocabularyCondition() {
                super("expose only neutral lifecycle vocabulary");
            }

            @Override
            public void check(JavaClass type, ConditionEvents events) {
                List<String> forbiddenFacts = forbiddenFacts(type);
                if (!forbiddenFacts.isEmpty()) {
                    events.add(SimpleConditionEvent.violated(
                            type, type.getName() + " exposes forbidden vocabulary in " + forbiddenFacts));
                }
            }

            private static List<String> forbiddenFacts(JavaClass type) {
                return Stream.concat(
                                Stream.of(type.getName()), Stream.concat(publicSignatures(type), dependencies(type)))
                        .filter(NeutralVocabularyCondition::containsForbiddenVocabulary)
                        .distinct()
                        .sorted()
                        .toList();
            }

            private static Stream<String> publicSignatures(JavaClass type) {
                Stream<String> fields = type.getFields().stream()
                        .filter(NeutralVocabularyCondition::isPublic)
                        .flatMap(field ->
                                Stream.of(field.getName(), field.getRawType().getName()));
                Stream<String> codeUnits = Stream.concat(type.getMethods().stream(), type.getConstructors().stream())
                        .filter(NeutralVocabularyCondition::isPublic)
                        .flatMap(NeutralVocabularyCondition::codeUnitSignature);
                return Stream.concat(fields, codeUnits);
            }

            private static Stream<String> codeUnitSignature(JavaCodeUnit codeUnit) {
                return Stream.concat(
                        Stream.of(
                                codeUnit.getName(), codeUnit.getRawReturnType().getName()),
                        codeUnit.getRawParameterTypes().stream().map(JavaClass::getName));
            }

            private static Stream<String> dependencies(JavaClass type) {
                return type.getDirectDependenciesFromSelf().stream()
                        .map(Dependency::getTargetClass)
                        .map(JavaClass::getName);
            }

            private static boolean isPublic(JavaMember member) {
                return member.getModifiers().contains(JavaModifier.PUBLIC);
            }

            private static boolean containsForbiddenVocabulary(String fact) {
                String normalized = fact.toLowerCase(Locale.ROOT);
                return FORBIDDEN_VOCABULARY.stream().anyMatch(normalized::contains);
            }
        }
    }
}
