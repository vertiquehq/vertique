// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;

/**
 * INTERNAL framework seam — processor-authoring substrate consumed by sibling framework modules;
 * not an application contract and outside the maturity promise. An application uses the wiring
 * annotations this module documents and never calls this type.
 *
 * <p>Utility class for discovering {@code @Inject}-annotated constructors on a {@link TypeElement}
 * during annotation processing.
 *
 * <p>Recognises both {@code jakarta.inject.Inject} and {@code javax.inject.Inject} so that
 * processors remain compatible with codebases using either Jakarta EE or {@code javax.inject}.
 *
 * <p>This helper provides <em>discovery</em> semantics only — it returns all matching constructors
 * and leaves <em>validation</em> (e.g., requiring exactly one) to the caller. This intentionally
 * differs from
 * {@code dev.vertique.codegen.dagger.processor.support.Filters#validateSingleInjectConstructor},
 * which has different semantics (silent skip on zero) and is still used by the CG-002
 * {@code AutoWireProcessor}.
 *
 * <p>Usage:
 * <pre>{@code
 * List<ExecutableElement> injectCtors = Constructors.findInjectConstructors(typeElement);
 * if (injectCtors.isEmpty()) {
 *     ctx.diagnostics().error(typeElement, "no @Inject constructor found");
 * } else if (injectCtors.size() > 1) {
 *     ctx.diagnostics().error(typeElement, Diagnostics.duplicateInjectConstructor(...));
 * }
 * }</pre>
 */
public final class Constructors {

    private static final String JAKARTA_INJECT = "jakarta.inject.Inject";
    private static final String JAVAX_INJECT = "javax.inject.Inject";

    private Constructors() {}

    /**
     * Returns an immutable list of all constructors on {@code type} annotated with
     * {@code @Inject} ({@code jakarta.inject.Inject} or {@code javax.inject.Inject}).
     *
     * <p>Scans {@link ElementFilter#constructorsIn(java.lang.Iterable)} over
     * {@link TypeElement#getEnclosedElements()} — direct members only, no supertype walk.
     *
     * @param type the type element to inspect; must not be {@code null}
     * @return an immutable, possibly-empty list of {@code @Inject}-annotated constructors in
     *         declaration order
     */
    public static List<ExecutableElement> findInjectConstructors(TypeElement type) {
        return ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
                .filter(ctor -> hasInjectAnnotation(ctor))
                .toList();
    }

    // --- Internal helpers ---

    /**
     * Returns {@code true} if the given constructor element carries {@code @jakarta.inject.Inject}
     * or {@code @javax.inject.Inject}.
     *
     * <p>{@link ElementFilter#constructorsIn} pre-filters to constructor elements, so no
     * additional {@link javax.lang.model.element.ElementKind} check is necessary here.
     *
     * @param ctor the constructor element to inspect; guaranteed to be a constructor by the caller
     * @return {@code true} if an inject annotation is present
     */
    private static boolean hasInjectAnnotation(ExecutableElement ctor) {
        return ctor.getAnnotationMirrors().stream().anyMatch(m -> {
            var annotationElement = m.getAnnotationType().asElement();
            String fqn;
            if (annotationElement instanceof TypeElement typeEl) {
                fqn = typeEl.getQualifiedName().toString();
            } else {
                fqn = annotationElement.toString();
            }
            return JAKARTA_INJECT.equals(fqn) || JAVAX_INJECT.equals(fqn);
        });
    }
}
