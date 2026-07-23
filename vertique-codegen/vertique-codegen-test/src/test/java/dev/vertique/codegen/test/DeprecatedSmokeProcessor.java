// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.test;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;

/**
 * Smoke-test processor that satisfies PRD CG-001 acceptance criterion #2.
 *
 * <p>For each {@code @Deprecated} {@link TypeElement} encountered, this processor writes a
 * generated source file {@code dev.vertique.codegen.test.generated.<Name>Marker} containing a
 * comment {@code // generated for <fqn>}. The generated file is minimal Java (just a package
 * declaration and an empty class with the comment) so that the compiler accepts it.
 *
 * <p>This processor lives only in {@code src/test/java} of {@code vertique-codegen-test} and is
 * never published as a production artifact.
 */
@SupportedAnnotationTypes("java.lang.Deprecated")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
class DeprecatedSmokeProcessor extends AbstractProcessor {

    /** The package used for all generated marker classes. */
    static final String GENERATED_PACKAGE = "dev.vertique.codegen.test.generated";

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(Deprecated.class)) {
            if (!(element instanceof TypeElement typeElement)) {
                continue;
            }
            String fqn = typeElement.getQualifiedName().toString();
            String simpleName = typeElement.getSimpleName().toString();
            String generatedName = GENERATED_PACKAGE + "." + simpleName + "Marker";

            try {
                JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(generatedName, typeElement);
                try (PrintWriter writer = new PrintWriter(sourceFile.openWriter())) {
                    writer.println("package " + GENERATED_PACKAGE + ";");
                    writer.println();
                    writer.println("// generated for " + fqn);
                    writer.println("class " + simpleName + "Marker {}");
                }
            } catch (IOException e) {
                processingEnv
                        .getMessager()
                        .printMessage(
                                javax.tools.Diagnostic.Kind.ERROR,
                                "Failed to write generated source for " + fqn + ": " + e.getMessage(),
                                typeElement);
            }
        }
        return false;
    }
}
