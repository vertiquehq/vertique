// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor.support;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.NoAutoWire;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

/**
 * Shared candidate-filter helpers used by all CG-002 collectors and scanners.
 *
 * <p>Centralises the {@link NoAutoWire} opt-out check and the single-{@code @Inject}-constructor
 * validation so that the discovery classes ({@code AnnotationRootedCollector},
 * {@code DelayedJobExecutorScanner}) share one audited implementation rather than copy-pasted
 * logic. All methods are stateless.
 */
public final class Filters {

    private static final String NO_AUTO_WIRE_FQN = NoAutoWire.class.getName();
    private static final String JAKARTA_INJECT_FQN = "jakarta.inject.Inject";
    private static final String JAVAX_INJECT_FQN = "javax.inject.Inject";

    private Filters() {}

    /**
     * Returns {@code true} when the given type carries {@link NoAutoWire}.
     *
     * @param type the type to inspect; must not be {@code null}
     * @return {@code true} if {@code @NoAutoWire} is present
     */
    public static boolean isOptedOut(TypeElement type) {
        return AnnotationMirrors.isPresent(type, NO_AUTO_WIRE_FQN);
    }

    /**
     * Validates that the type has exactly one {@code @Inject} constructor.
     *
     * <p>Behaviour by count:
     * <ul>
     *   <li>0 — emits a {@code NOTE} via {@link Diagnostics#note} and returns {@code false}.</li>
     *   <li>1 — returns {@code true}.</li>
     *   <li>&gt;1 — emits an {@code ERROR} via
     *       {@link Diagnostics#duplicateInjectConstructor(String)} and returns {@code false}.</li>
     * </ul>
     *
     * @param type        the type to validate; must not be {@code null}
     * @param ctx         the codegen context for diagnostic emission; must not be {@code null}
     * @param markerLabel a short noun phrase describing why the type was discovered (e.g.
     *                    {@code "@ServiceContract"}) for the zero-constructor NOTE message
     * @return {@code true} when exactly one {@code @Inject} constructor is present
     */
    public static boolean validateSingleInjectConstructor(TypeElement type, CodegenContext ctx, String markerLabel) {
        long count = type.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.CONSTRUCTOR)
                .filter(Filters::hasInjectAnnotation)
                .count();

        if (count == 0) {
            ctx.diagnostics()
                    .note(
                            type,
                            "%s %s but has no @Inject constructor; not auto-wired",
                            type.getQualifiedName(),
                            markerLabel);
            return false;
        }
        if (count > 1) {
            ctx.diagnostics()
                    .error(
                            type,
                            Diagnostics.duplicateInjectConstructor(
                                    type.getQualifiedName().toString()));
            return false;
        }
        return true;
    }

    private static boolean hasInjectAnnotation(Element element) {
        return AnnotationMirrors.isPresent(element, JAKARTA_INJECT_FQN)
                || AnnotationMirrors.isPresent(element, JAVAX_INJECT_FQN);
    }
}
