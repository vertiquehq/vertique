// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.testing.compile.Compilation;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that consumers can compile only against the canonical security-core {@code Authorized}
 * annotation and that its runtime declaration contract is unchanged.
 */
class AuthorizedRelocationTest {

    private static final String CANONICAL_AUTHORIZED = "dev.vertique.security.authz.Authorized";
    private static final String REST_AUTHORIZED = "dev.vertique.rest.core.security.Authorized";
    private static final String CANONICAL_CONSUMER = "dev.vertique.test.authz.CanonicalConsumer";
    private static final String REST_CONSUMER = "dev.vertique.test.authz.RestConsumer";

    @Test
    @DisplayName("only the canonical Authorized import compiles with its preserved contract")
    void shouldCompileOnlyTheCanonicalAnnotation() throws ReflectiveOperationException {
        // Given real security-core and rest-core dependencies on the ProcessorTestHarness classpath,
        // and consumer sources that import one annotation name each without declaring stubs.
        var canonicalCompilation = compileConsumer(CANONICAL_CONSUMER, CANONICAL_AUTHORIZED);
        var restCompilation = compileConsumer(REST_CONSUMER, REST_AUTHORIZED);

        // When javac compiles both consumers and the canonical consumer's emitted annotation is read.
        // Then only the canonical consumer compiles, with RUNTIME retention, TYPE+METHOD targets,
        // empty scopes, and matchAll=true defaults on both annotated elements.
        assertAll(
                "Authorized relocation consumer contract",
                () -> assertCompilationOutcome(canonicalCompilation, Compilation.Status.SUCCESS, CANONICAL_AUTHORIZED),
                () -> assertCompilationOutcome(restCompilation, Compilation.Status.FAILURE, REST_AUTHORIZED),
                () -> {
                    if (canonicalCompilation.compilation().status() == Compilation.Status.SUCCESS) {
                        assertCanonicalAnnotationContract(canonicalCompilation);
                    }
                });
    }

    private static ProcessorTestHarness.Result compileConsumer(String consumerFqn, String annotationFqn) {
        int packageEnd = consumerFqn.lastIndexOf('.');
        String packageName = consumerFqn.substring(0, packageEnd);
        String simpleName = consumerFqn.substring(packageEnd + 1);
        JavaFileObject consumer =
                SourceFiles.inline(consumerFqn, """
                package %s;

                import %s;

                @Authorized
                public class %s {
                    @Authorized
                    public void guardedMethod() {}
                }
                """.formatted(packageName, annotationFqn, simpleName));

        return ProcessorTestHarness.run(new NoOpProcessor(), consumer);
    }

    private static void assertCompilationOutcome(
            ProcessorTestHarness.Result result, Compilation.Status expected, String annotationFqn) {
        Compilation.Status actual = result.compilation().status();
        if (actual == Compilation.Status.FAILURE) {
            assertOnlyExpectedMissingAnnotationError(result, annotationFqn);
        }
        assertEquals(
                expected,
                actual,
                "Unexpected javac outcome for consumer import " + annotationFqn + ": " + diagnosticSummary(result));
    }

    private static void assertOnlyExpectedMissingAnnotationError(
            ProcessorTestHarness.Result result, String annotationFqn) {
        List<Diagnostic<? extends JavaFileObject>> errors = result.compilation().diagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .toList();
        String packageName = annotationFqn.substring(0, annotationFqn.lastIndexOf('.'));
        String simpleName = annotationFqn.substring(annotationFqn.lastIndexOf('.') + 1);

        assertFalse(errors.isEmpty(), "Expected javac to report the missing annotation import " + annotationFqn);
        assertTrue(
                errors.stream()
                        .allMatch(diagnostic -> isMissingAnnotationDiagnostic(diagnostic, packageName, simpleName)),
                "Compilation must fail only because " + annotationFqn + " is unresolved; " + diagnosticSummary(result));
    }

    private static boolean isMissingAnnotationDiagnostic(
            Diagnostic<? extends JavaFileObject> diagnostic, String packageName, String simpleName) {
        String message = diagnostic.getMessage(Locale.ROOT);
        boolean missingPackage = message.contains(packageName) && message.contains("does not exist");
        boolean missingType = message.contains("cannot find symbol") && message.contains("class " + simpleName);
        return missingPackage || missingType;
    }

    private static void assertCanonicalAnnotationContract(ProcessorTestHarness.Result compilation)
            throws ReflectiveOperationException {
        Class<?> annotationType = compilation.generatedClassLoader().loadClass(CANONICAL_AUTHORIZED);
        assertTrue(annotationType.isAnnotation(), "The canonical type must be an annotation");

        Retention retention = annotationType.getDeclaredAnnotation(Retention.class);
        assertNotNull(retention, "The canonical annotation must declare its retention");
        assertEquals(RetentionPolicy.RUNTIME, retention.value());

        Target target = annotationType.getDeclaredAnnotation(Target.class);
        assertNotNull(target, "The canonical annotation must declare its targets");
        Set<ElementType> targets = new HashSet<>(Arrays.asList(target.value()));
        assertEquals(Set.of(ElementType.TYPE, ElementType.METHOD), targets);

        Class<?> consumer = compilation.loadGeneratedClass(CANONICAL_CONSUMER);
        Class<? extends Annotation> annotationClass = annotationType.asSubclass(Annotation.class);
        Annotation typeAnnotation = consumer.getDeclaredAnnotation(annotationClass);
        assertNotNull(typeAnnotation, "RUNTIME retention must expose the type annotation to consumers");
        assertDefaultValues(annotationType, typeAnnotation);

        Method guardedMethod = consumer.getDeclaredMethod("guardedMethod");
        Annotation methodAnnotation = guardedMethod.getDeclaredAnnotation(annotationClass);
        assertNotNull(methodAnnotation, "The method target must expose its runtime annotation");
        assertDefaultValues(annotationType, methodAnnotation);
    }

    private static void assertDefaultValues(Class<?> annotationType, Annotation annotation)
            throws ReflectiveOperationException {
        Method scopes = annotationType.getMethod("scopes");
        assertArrayEquals(new String[0], (String[]) scopes.getDefaultValue());
        assertArrayEquals(new String[0], (String[]) scopes.invoke(annotation));

        Method matchAll = annotationType.getMethod("matchAll");
        assertEquals(Boolean.TRUE, matchAll.getDefaultValue());
        assertEquals(Boolean.TRUE, matchAll.invoke(annotation));
    }

    private static String diagnosticSummary(ProcessorTestHarness.Result result) {
        return result.compilation().diagnostics().stream()
                .map(diagnostic -> "[" + diagnostic.getKind() + "] " + diagnostic.getMessage(Locale.ROOT))
                .collect(Collectors.joining("\n", "\n", ""));
    }

    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_21)
    private static final class NoOpProcessor extends AbstractProcessor {

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
            return true;
        }
    }
}
