// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.architecture.fixture.Bucket4jExposingFixture;
import java.net.URL;
import java.security.CodeSource;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TP-004: no Bucket4j type may appear in a public method, field, or constructor signature under
 * {@code dev.vertique.ratelimit} (and its {@code .exception}/{@code .spi}/{@code .spi.event}/
 * {@code .dagger} sub-packages), per FR-002/FR-003 and contracts/rate-limit-runtime.md's
 * "Boundary".
 */
class RateLimitCoreArchitectureTest {

    private static final String BUCKET4J_PACKAGE_PREFIX = "io.github.bucket4j";

    private static final ArchCondition<JavaClass> EXPOSE_NO_BUCKET4J_TYPE_IN_PUBLIC_SIGNATURE =
            new ArchCondition<>("expose no Bucket4j type in a public method, field, or constructor signature") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    for (JavaMethod method : javaClass.getMethods()) {
                        if (!isPublic(method)) {
                            continue;
                        }
                        checkType(method.getRawReturnType(), method, events);
                        for (JavaParameter parameter : method.getParameters()) {
                            checkType(parameter.getRawType(), method, events);
                        }
                    }
                    for (JavaField field : javaClass.getFields()) {
                        if (isPublic(field)) {
                            checkType(field.getRawType(), field, events);
                        }
                    }
                    for (JavaConstructor constructor : javaClass.getConstructors()) {
                        if (!isPublic(constructor)) {
                            continue;
                        }
                        for (JavaParameter parameter : constructor.getParameters()) {
                            checkType(parameter.getRawType(), constructor, events);
                        }
                    }
                }
            };

    @Test
    void shouldExposeNoBucket4jTypeInPublicSignatures() {
        ArchRule rule = ArchRuleDefinition.classes().should(EXPOSE_NO_BUCKET4J_TYPE_IN_PUBLIC_SIGNATURE);

        JavaClasses productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importUrls(List.of(compiledLocationOf(RateLimiters.class)));
        JavaClasses syntheticFixtureClass =
                new ClassFileImporter().importUrls(List.of(compiledLocationOf(Bucket4jExposingFixture.class)));

        int productionViolations =
                rule.evaluate(productionClasses).getFailureReport().getDetails().size();
        int syntheticViolations = rule.evaluate(syntheticFixtureClass)
                .getFailureReport()
                .getDetails()
                .size();

        assertThat(productionViolations)
                .as("production Bucket4j-in-public-signature violations")
                .isEqualTo(0);
        assertThat(syntheticViolations)
                .as("synthetic fixture's known Bucket4j-typed public member")
                .isEqualTo(1);
    }

    private static boolean isPublic(JavaMember member) {
        return member.getModifiers().contains(JavaModifier.PUBLIC);
    }

    private static void checkType(JavaClass involvedType, JavaMember member, ConditionEvents events) {
        if (involvedType.getPackageName().startsWith(BUCKET4J_PACKAGE_PREFIX)) {
            events.add(SimpleConditionEvent.violated(
                    member, member.getFullName() + " exposes Bucket4j type " + involvedType.getName()));
        }
    }

    private static URL compiledLocationOf(Class<?> type) {
        CodeSource codeSource = type.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            throw new AssertionError("No compiled location available for " + type.getName());
        }
        return codeSource.getLocation();
    }
}
