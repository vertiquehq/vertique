// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.test;

import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * Test fixture processor that emits a fixed {@link Diagnostic.Kind#ERROR} message for every
 * {@link Deprecated}-annotated element. Used to exercise
 * {@link ProcessorTestHarness.Result#assertErrorMessage} without depending on javac's own
 * diagnostic wording.
 */
final class ErrorEmittingProcessor extends AbstractProcessor {

    private final String errorMessage;

    ErrorEmittingProcessor(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(Deprecated.class.getName());
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(Deprecated.class)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, errorMessage, element);
        }
        return false;
    }
}
