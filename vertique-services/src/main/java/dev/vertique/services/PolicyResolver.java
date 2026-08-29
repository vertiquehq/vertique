// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Resolves policy and annotation metadata for service contract methods.
 *
 * <p>Centralizes all policy annotation resolution as the single extension point for
 * current and future policy types (resilience, cache, rate-limiting). Method-level
 * annotations override type-level defaults.
 */
final class PolicyResolver {

    private PolicyResolver() {}

    /**
     * Resolves resilience annotations for the given contract method.
     * Method-level annotations override type-level defaults.
     *
     * @param contract the contract interface
     * @param method the contract method
     * @return resolved resilience annotations
     */
    static ResilienceAnnotations resolveResilience(Class<?> contract, Method method) {
        return ResilienceAnnotations.resolve(contract, method);
    }

    /**
     * Resolves all annotations from the method and its hierarchy.
     *
     * @param method the method to inspect
     * @return annotations resolved from the method and its hierarchy
     */
    static List<Annotation> resolveMethodAnnotations(Method method) {
        return AnnotationResolver.resolveMethodAnnotations(method);
    }

    /**
     * Resolves all annotations from the contract class and its hierarchy.
     *
     * @param contract the contract interface
     * @return annotations resolved from the class and its hierarchy
     */
    static List<Annotation> resolveClassAnnotations(Class<?> contract) {
        return AnnotationResolver.resolveClassAnnotations(contract);
    }
}
