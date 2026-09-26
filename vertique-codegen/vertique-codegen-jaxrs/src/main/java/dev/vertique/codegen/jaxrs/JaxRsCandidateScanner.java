// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import dev.vertique.codegen.NoAutoWire;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

/**
 * Discovers JAX-RS resource candidates in an annotation processing round by walking
 * {@link RoundEnvironment#getRootElements()} and filtering to concrete classes that have an
 * effective {@code @Path} (direct or via a transitively implemented interface).
 *
 * <p>This scanner separates <em>semantic candidates</em> (any concrete class with an effective
 * {@code @Path}) from <em>DI-emission candidates</em> (those that also have an {@code @Inject}
 * constructor and are not opted out via {@link NoAutoWire}). The distinction matters because
 * validation, descriptor emission, and execution-plan emission apply to all semantic candidates,
 * while Dagger module generation applies only to DI-emission candidates.
 *
 * <p>All returned sets are {@link LinkedHashSet}s to preserve discovery order, making diagnostic
 * output deterministic across compilation runs.
 */
public final class JaxRsCandidateScanner {

    private JaxRsCandidateScanner() {}

    // --- Public API ---

    /**
     * Scans the round environment for semantic JAX-RS candidates: concrete (non-abstract,
     * non-interface) classes that have an effective {@code @Path} annotation as determined by
     * {@link EffectiveJaxRsContractResolver#hasEffectivePath(TypeElement)}.
     *
     * <p>The {@code @Inject} and {@link NoAutoWire} filters are intentionally absent here —
     * they are DI-emission concerns, applied separately by {@link #filterDiCandidates}.
     *
     * @param roundEnv the current annotation processing round environment; must not be {@code null}
     * @param resolver the contract resolver used to check for an effective {@code @Path};
     *                 must not be {@code null}
     * @return a stable, ordered set of semantic candidate type elements; never {@code null}
     */
    public static Set<TypeElement> scan(RoundEnvironment roundEnv, EffectiveJaxRsContractResolver resolver) {
        Set<TypeElement> result = new LinkedHashSet<>();
        for (Element element : roundEnv.getRootElements()) {
            if (!(element instanceof TypeElement typeElement)) {
                continue;
            }
            if (typeElement.getKind() == ElementKind.INTERFACE) {
                continue;
            }
            if (typeElement.getKind() == ElementKind.ANNOTATION_TYPE) {
                continue;
            }
            if (!resolver.hasEffectivePath(typeElement)) {
                continue;
            }
            result.add(typeElement);
        }
        return result;
    }

    /**
     * Filters semantic candidates to those eligible for Dagger module auto-wiring.
     *
     * <p>A semantic candidate is a DI-emission candidate when both of the following are true:
     * <ol>
     *   <li>The class has at least one constructor annotated with {@code @jakarta.inject.Inject}
     *       or {@code @javax.inject.Inject}.</li>
     *   <li>The class is not annotated with {@link NoAutoWire}.</li>
     * </ol>
     *
     * @param semanticCandidates the full set of semantic candidates produced by
     *                           {@link #scan(RoundEnvironment, EffectiveJaxRsContractResolver)};
     *                           must not be {@code null}
     * @param elements           the {@link Elements} utility from the processing environment;
     *                           must not be {@code null}
     * @return the subset of {@code semanticCandidates} eligible for DI module emission;
     *         never {@code null}
     */
    public static Set<TypeElement> filterDiCandidates(Set<TypeElement> semanticCandidates, Elements elements) {
        Set<TypeElement> result = new LinkedHashSet<>();
        for (TypeElement candidate : semanticCandidates) {
            if (candidate.getAnnotation(NoAutoWire.class) != null) {
                continue;
            }
            if (!hasInjectConstructor(candidate)) {
                continue;
            }
            result.add(candidate);
        }
        return result;
    }

    // --- Internal helpers ---

    /**
     * Returns {@code true} if the given type element has at least one constructor annotated with
     * {@code @jakarta.inject.Inject} or {@code @javax.inject.Inject}.
     *
     * <p>Both Jakarta EE and legacy {@code javax.inject} flavours are recognised for
     * compatibility with mixed codebases.
     *
     * <p>Package-private (not {@code private}) so {@link JaxRsApplicationScanner} can reuse this
     * same detection for its own application-construction rule instead of duplicating it.
     *
     * @param typeElement the type element to inspect; must not be {@code null}
     * @return {@code true} when an {@code @Inject}-annotated constructor is found
     */
    static boolean hasInjectConstructor(TypeElement typeElement) {
        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.CONSTRUCTOR) {
                continue;
            }
            for (var mirror : enclosed.getAnnotationMirrors()) {
                var annotationElement = mirror.getAnnotationType().asElement();
                String fqn;
                if (annotationElement instanceof TypeElement typeEl) {
                    fqn = typeEl.getQualifiedName().toString();
                } else {
                    fqn = annotationElement.toString();
                }
                if ("jakarta.inject.Inject".equals(fqn) || "javax.inject.Inject".equals(fqn)) {
                    return true;
                }
            }
        }
        return false;
    }
}
